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
}
