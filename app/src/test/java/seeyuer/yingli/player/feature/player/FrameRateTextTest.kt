package seeyuer.yingli.player.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧率显示口径的单元测试。这份用例存在的理由：帧率是**用户可见的数字**，
 * 真机上精确 30fps 的容器会被 Media3 报成 `29.999x`，`toInt()` 截断就会显示 "29 fps"。
 */
class FrameRateTextTest {
    @Test
    fun `integer container frame rate is never shown one short`() {
        // 真机实测形态：29.999x 必须显示 30，而不是截断成 29。
        assertEquals("30 fps", frameRateLabel(29.999f))
        assertEquals("30 fps", frameRateLabel(29.9995f))
        assertEquals("30 fps", frameRateLabel(30f))
        assertEquals("30 fps", frameRateLabel(29.995f))
        assertEquals("30 fps", frameRateLabel(30.005f))
        assertEquals("25 fps", frameRateLabel(24.999f))
        assertEquals("60 fps", frameRateLabel(60f))
        assertEquals("120 fps", frameRateLabel(120f))
    }

    @Test
    fun `fractional frame rates stay distinguishable from the rounded integer`() {
        // 59.94 不许被显示成 60：两者的差别正是这一段存在的意义。
        assertEquals("59.94 fps", frameRateLabel(59.94f))
        assertEquals("29.97 fps", frameRateLabel(29.97f))
        // 23.976 保留两位小数（23.98），它仍然与 24 明显不同。
        assertEquals("23.98 fps", frameRateLabel(23.976f))
        assertEquals("24 fps", frameRateLabel(24f))
        assertEquals("23.98 fps", frameRateLabel(23.98f))
        // 判据：分数帧率显示出来必须带小数，整数帧率不许带小数。
        assertTrue(frameRateLabel(59.94f)?.contains('.') == true)
        assertFalse(frameRateLabel(29.999f)?.contains('.') == true)
    }

    @Test
    fun `frame rates Media3 reports with float noise still round to whole numbers`() {
        // 59.9999 / 23.9999 这类"整数帧率 + 浮点噪声"同样按整数显示。
        assertEquals("60 fps", frameRateLabel(59.9999f))
        assertEquals("24 fps", frameRateLabel(23.9999f))
        assertEquals("50 fps", frameRateLabel(50.0001f))
    }

    @Test
    fun `missing or impossible frame rates produce no text at all`() {
        // 缺失/0/负数/NaN 一律不显示：宁可不出现帧率字段，也不能编一个 "0 fps" 出来。
        assertNull(frameRateLabel(null))
        assertNull(frameRateLabel(0f))
        assertNull(frameRateLabel(0.001f))
        assertNull(frameRateLabel(-30f))
        assertNull(frameRateLabel(Float.NaN))
        assertNull(frameRateLabel(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `very high frame rates keep two decimals instead of collapsing to scientific notation`() {
        // 高帧率容器的实际值（真机自造 3000fps 容器）必须是一个正常数字，不是 3.0E3。
        assertEquals("3000 fps", frameRateLabel(3000f))
        assertEquals("2999.65 fps", frameRateLabel(2999.65f))
    }
}
