package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoZoomTest {
    @Test
    fun `zoom clamps scale and pan to a valid viewport`() {
        val zoom = VideoZoom.of(scale = 9f, offsetX = 2f, offsetY = -2f)

        assertEquals(VideoZoom.MAX_SCALE, zoom.scale)
        assertTrue(zoom.offsetX <= 0.375f)
        assertTrue(zoom.offsetY >= -0.375f)
    }

    @Test
    fun `one point scale and pan are preserved together`() {
        val zoom = VideoZoom.of(scale = 1.5f, offsetX = 0.1f, offsetY = -0.2f)

        assertEquals(1.5f, zoom.scale)
        assertEquals(0.1f, zoom.offsetX)
        assertEquals(-1f / 6f, zoom.offsetY)
    }
}
