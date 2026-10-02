package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekPrecisionTest {
    @Test
    fun `frame accurate mode always wins over the duration rule`() {
        // 截图工具激活时必须精确跳转：逐帧检查落点错一帧就失去意义，与媒体长短无关。
        assertEquals(
            SeekPrecision.FRAME_ACCURATE,
            seekPrecisionFor(durationMillis = 1_000, frameAccurate = true),
        )
        assertEquals(
            SeekPrecision.FRAME_ACCURATE,
            seekPrecisionFor(durationMillis = 10_000_000, frameAccurate = true),
        )
        assertEquals(
            SeekPrecision.FRAME_ACCURATE,
            seekPrecisionFor(durationMillis = null, frameAccurate = true),
        )
        assertEquals(
            SeekPrecision.FRAME_ACCURATE,
            seekPrecisionFor(durationMillis = 0, frameAccurate = true),
        )
    }

    @Test
    fun `duration rule keeps the original one hundred twenty second boundary`() {
        // 边界两侧：120s 整仍是精确跳转，120s + 1ms 落到关键帧跳转。
        assertEquals(SeekPrecision.FRAME_ACCURATE, seekPrecisionFor(120_000, frameAccurate = false))
        assertEquals(SeekPrecision.CLOSEST_SYNC, seekPrecisionFor(120_001, frameAccurate = false))
    }

    @Test
    fun `duration rule matches the previous behaviour for short and long media`() {
        assertEquals(SeekPrecision.FRAME_ACCURATE, seekPrecisionFor(1, frameAccurate = false))
        assertEquals(SeekPrecision.FRAME_ACCURATE, seekPrecisionFor(60_000, frameAccurate = false))
        assertEquals(SeekPrecision.CLOSEST_SYNC, seekPrecisionFor(3_600_000, frameAccurate = false))
    }

    @Test
    fun `unknown duration falls back to keyframe seeking`() {
        // 时长未知（网络流/探测失败）按"长视频"处理：精确跳转在拿不到长度的源上只会卡住画面。
        assertEquals(SeekPrecision.CLOSEST_SYNC, seekPrecisionFor(null, frameAccurate = false))
        assertEquals(SeekPrecision.CLOSEST_SYNC, seekPrecisionFor(0, frameAccurate = false))
        assertEquals(SeekPrecision.CLOSEST_SYNC, seekPrecisionFor(-1, frameAccurate = false))
    }

    @Test
    fun `default precision is the duration rule without frame accurate mode`() {
        assertEquals(defaultSeekPrecision(120_000), seekPrecisionFor(120_000, frameAccurate = false))
        assertEquals(defaultSeekPrecision(null), seekPrecisionFor(null, frameAccurate = false))
    }

    @Test
    fun `shared control publishes the latest requested precision`() {
        val control = MutableSeekPrecisionControl()
        assertEquals(SeekPrecision.CLOSEST_SYNC, control.precision.value)

        control.setPrecision(SeekPrecision.FRAME_ACCURATE)
        assertEquals(SeekPrecision.FRAME_ACCURATE, control.precision.value)

        control.setPrecision(SeekPrecision.CLOSEST_SYNC)
        assertEquals(SeekPrecision.CLOSEST_SYNC, control.precision.value)
    }
}
