package seeyuer.yingli.player.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YingLiSliderMathTest {
    @Test
    fun `track span leaves the thumb radius at both ends`() {
        assertEquals(230f, sliderTrackSpanPx(widthPx = 278f, thumbRadiusPx = 24f), 0.001f)
    }

    @Test
    fun `touch at the track ends yields the range ends`() {
        assertEquals(0f, sliderFractionFromTouch(touchX = 24f, widthPx = 278f, thumbRadiusPx = 24f), 0.001f)
        assertEquals(1f, sliderFractionFromTouch(touchX = 254f, widthPx = 278f, thumbRadiusPx = 24f), 0.001f)
        assertEquals(0.5f, sliderFractionFromTouch(touchX = 139f, widthPx = 278f, thumbRadiusPx = 24f), 0.001f)
    }

    @Test
    fun `touches outside the track clamp to the ends`() {
        assertEquals(0f, sliderFractionFromTouch(touchX = -50f, widthPx = 278f, thumbRadiusPx = 24f), 0.001f)
        assertEquals(1f, sliderFractionFromTouch(touchX = 999f, widthPx = 278f, thumbRadiusPx = 24f), 0.001f)
    }

    @Test
    fun `degenerate width never divides by zero`() {
        assertTrue(sliderTrackSpanPx(widthPx = 10f, thumbRadiusPx = 24f) > 0f)
        assertEquals(0f, sliderFractionFromTouch(touchX = 5f, widthPx = 10f, thumbRadiusPx = 24f), 0.001f)
    }
}
