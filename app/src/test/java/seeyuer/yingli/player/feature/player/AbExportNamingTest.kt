package seeyuer.yingli.player.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.domain.clips.ClipExportMode
import seeyuer.yingli.player.domain.clips.ClipSegment

/**
 * AB 导出的两个纯函数（段名与读数）。
 *
 * 为什么单独钉：段名是**用户唯一能分辨"这是哪一段"的线索**（导出落点里没有别的元数据），
 * 读数则是用户核对"导出的是不是我看到的区间"的唯一依据；两者都不该只靠 UI 快照测试顺带覆盖。
 */
class AbExportNamingTest {
    @Test
    fun `segment name keeps the source stem and the integer second range`() {
        assertEquals("影片_clip_6s-12s", abExportSegmentName("影片.mkv", 6_123, 12_800, ClipExportMode.FAST))
    }

    @Test
    fun `accurate exports are distinguishable from fast ones of the same interval`() {
        val fast = abExportSegmentName("影片.mkv", 6_123, 12_800, ClipExportMode.FAST)
        val accurate = abExportSegmentName("影片.mkv", 6_123, 12_800, ClipExportMode.ACCURATE)

        assertEquals("影片_clip_6s-12s_exact", accurate)
        assertTrue("同区间两种模式不能同名", fast != accurate)
    }

    @Test
    fun `a name without extension is used as is`() {
        assertEquals("影片_clip_0s-1s", abExportSegmentName("影片", 0, 1_000, ClipExportMode.FAST))
    }

    @Test
    fun `truncation keeps the range suffix intact and respects the segment name limit`() {
        val longStem = "影".repeat(200)

        val name = abExportSegmentName("$longStem.mp4", 6_000, 12_000, ClipExportMode.FAST)

        assertEquals(ClipSegment.MAX_NAME_LENGTH, name.length)
        assertTrue("后缀被截断，区间信息就丢了", name.endsWith("_clip_6s-12s"))
    }

    @Test
    fun `range readout keeps milliseconds and switches to hours exactly like the transport clock`() {
        assertEquals("00:00.000", formatAbExportMark(0))
        assertEquals("01:05.400", formatAbExportMark(65_400))
        assertEquals("59:59.999", formatAbExportMark(3_599_999))
        assertEquals("01:00:00.000", formatAbExportMark(3_600_000))
        assertEquals("01:35:00.500", formatAbExportMark(5_700_500))
    }

    @Test
    fun `a negative readout is clamped instead of rendered`() {
        assertEquals("00:00.000", formatAbExportMark(-1))
    }
}
