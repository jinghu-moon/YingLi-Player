package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameCounterTest {
    @Test
    fun `thirty fps timeline maps position to one based frame numbers`() {
        // 10 秒 @30fps = 300 帧；位置 0 是第 1 帧，位置 2 秒是第 61 帧。
        assertEquals(FrameCounterState(1, 300), frameCounterStateOf(0, 10_000, 30f))
        assertEquals(FrameCounterState(61, 300), frameCounterStateOf(2_000, 10_000, 30f))
    }

    @Test
    fun `position at duration clamps to total frames`() {
        assertEquals(FrameCounterState(300, 300), frameCounterStateOf(10_000, 10_000, 30f))
    }

    @Test
    fun `position beyond duration clamps to total frames`() {
        // 位置超过总时长（末帧 seek 未落定、时长元信息偏短）不允许显示越界帧号。
        assertEquals(FrameCounterState(300, 300), frameCounterStateOf(999_999, 10_000, 30f))
    }

    @Test
    fun `negative position clamps to first frame`() {
        assertEquals(FrameCounterState(1, 300), frameCounterStateOf(-500, 10_000, 30f))
    }

    @Test
    fun `non integer frame rate is rounded to nearest frame`() {
        // 29.97fps、10 秒 → 299.7 帧 → 300；位置 5 秒 → 149.85 → 150 → 第 151 帧。
        assertEquals(FrameCounterState(151, 300), frameCounterStateOf(5_000, 10_000, 29.97f))
    }

    @Test
    fun `unusable frame rate is not displayable`() {
        assertNull(frameCounterStateOf(1_000, 10_000, null))
        assertNull(frameCounterStateOf(1_000, 10_000, 0f))
        assertNull(frameCounterStateOf(1_000, 10_000, -30f))
    }

    @Test
    fun `missing duration is not displayable`() {
        // 时长未知（直播/尚未探测到）时没有"总帧数"，同样不显示，避免出现 `1324 / 0`。
        assertNull(frameCounterStateOf(1_000, null, 30f))
        assertNull(frameCounterStateOf(1_000, 0, 30f))
    }

    @Test
    fun `sub frame duration still reports a single frame`() {
        // 极短素材（半帧）：总帧数至少为 1，且当前帧不出这个范围。
        assertEquals(FrameCounterState(1, 1), frameCounterStateOf(5, 5, 30f))
    }

    // ---- 校准口径（第 2 步）----

    @Test
    fun `calibrated frame count replaces the estimate for the total`() {
        // 容器时长与容器帧率都不精确时，真实总帧数必须以校准值为准：
        // 估算会给出 300 帧，校准值说明实际是 301 帧。
        val calibrated = frameCounterStateOf(
            positionMillis = 0,
            durationMillis = 10_000,
            frameRate = 30f,
            calibration = FrameCalibration(frameCount = 301, measuredFrameRate = 30f),
        )

        assertEquals(FrameCounterState(1, 301), calibrated)
    }

    @Test
    fun `calibrated measured frame rate wins over the container field`() {
        // 容器说 25fps、实测 30fps：当前帧按实测算（位置 5 秒 → 151 帧），总帧数用真实值。
        val counter = frameCounterStateOf(
            positionMillis = 5_000,
            durationMillis = 10_000,
            frameRate = 25f,
            calibration = FrameCalibration(frameCount = 300, measuredFrameRate = 30f),
        )

        assertEquals(FrameCounterState(151, 300), counter)
    }

    @Test
    fun `calibration stays usable without container duration or frame rate`() {
        // 时长与容器帧率全不可用时，校准值仍然足以给出帧号：退化为 1 帧，至少不越界、不隐藏。
        val counter = frameCounterStateOf(
            positionMillis = 0,
            durationMillis = null,
            frameRate = null,
            calibration = FrameCalibration(frameCount = 240, measuredFrameRate = null),
        )

        assertEquals(FrameCounterState(1, 240), counter)
    }

    @Test
    fun `calibrated counter clamps beyond the real total`() {
        // 位置超过总时长时不能显示越界帧号（校准值也一样）。
        val counter = frameCounterStateOf(
            positionMillis = 999_999,
            durationMillis = 10_000,
            frameRate = 30f,
            calibration = FrameCalibration(frameCount = 301, measuredFrameRate = 30f),
        )

        assertEquals(FrameCounterState(301, 301), counter)
    }

    @Test
    fun `without calibration the estimate is untouched`() {
        // 回归：校准缺席时行为必须与第 1 步完全一致。
        assertEquals(FrameCounterState(61, 300), frameCounterStateOf(2_000, 10_000, 30f, calibration = null))
        assertNull(frameCounterStateOf(1_000, 10_000, null, calibration = null))
    }

    // ---- 逐帧步进的步长与边界 ----

    @Test
    fun `frame duration follows the frame rate with a one millisecond floor`() {
        assertEquals(33L, frameDurationMillisOf(30f))
        assertEquals(42L, frameDurationMillisOf(24f))
        assertEquals(1L, frameDurationMillisOf(1000f))
        assertNull(frameDurationMillisOf(null))
        assertNull(frameDurationMillisOf(0f))
        assertNull(frameDurationMillisOf(-30f))
    }

    @Test
    fun `stepping moves exactly one frame`() {
        assertEquals(1_033L, frameStepTargetMillis(1_000, 30f, 10_000, forward = true))
        assertEquals(967L, frameStepTargetMillis(1_000, 30f, 10_000, forward = false))
    }

    @Test
    fun `stepping clamps at both ends of the media`() {
        // 第 1 帧再往前 → 停在 0；最后 1 帧再往后 → 停在总时长：不越界、不抛错。
        assertEquals(0L, frameStepTargetMillis(0, 30f, 10_000, forward = false))
        assertEquals(0L, frameStepTargetMillis(10, 30f, 10_000, forward = false))
        assertEquals(10_000L, frameStepTargetMillis(10_000, 30f, 10_000, forward = true))
        assertEquals(10_000L, frameStepTargetMillis(9_990, 30f, 10_000, forward = true))
    }

    @Test
    fun `stepping without a duration only clamps the lower bound`() {
        // 时长未知（直播/未探测到）时不能把目标夹到 0；步进仍要往前推进。
        assertEquals(1_033L, frameStepTargetMillis(1_000, 30f, null, forward = true))
        assertEquals(0L, frameStepTargetMillis(0, 30f, null, forward = false))
    }

    @Test
    fun `stepping without a frame rate falls back to the default step`() {
        assertEquals(1_034L, frameStepTargetMillis(1_000, null, 10_000, forward = true))
        assertEquals(966L, frameStepTargetMillis(1_000, null, 10_000, forward = false))
    }
}
