package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenPolicyTest {
    private val policy = FullscreenPolicy()
    private val landscapeVideo = 1920f / 1080f
    private val portraitVideo = 1080f / 1920f

    @Test
    fun `portrait window with landscape video requests landscape fullscreen`() {
        val plan = policy.toggle(
            isFullscreen = false,
            videoAspect = landscapeVideo,
            portraitWindow = true,
            scaleMode = VideoScaleMode.FIT,
        )

        assertTrue(plan.isFullscreen)
        assertEquals(RequestedOrientation.LANDSCAPE, plan.orientation)
        assertFalse(plan.fillScreen)
    }

    @Test
    fun `portrait window with portrait video stays portrait and fills the screen`() {
        val plan = policy.toggle(
            isFullscreen = false,
            videoAspect = portraitVideo,
            portraitWindow = true,
            scaleMode = VideoScaleMode.FIT,
        )

        assertTrue(plan.isFullscreen)
        assertEquals(RequestedOrientation.SENSOR, plan.orientation)
        assertTrue(plan.fillScreen)
    }

    @Test
    fun `already filled scale mode is not filled twice`() {
        val plan = policy.toggle(
            isFullscreen = false,
            videoAspect = portraitVideo,
            portraitWindow = true,
            scaleMode = VideoScaleMode.FILL,
        )

        assertFalse(plan.fillScreen)
    }

    @Test
    fun `landscape window only hides system bars`() {
        val plan = policy.toggle(
            isFullscreen = false,
            videoAspect = landscapeVideo,
            portraitWindow = false,
            scaleMode = VideoScaleMode.FIT,
        )

        assertTrue(plan.isFullscreen)
        assertEquals(RequestedOrientation.SENSOR, plan.orientation)
        assertFalse(plan.fillScreen)
    }

    @Test
    fun `unknown video shape only hides system bars`() {
        listOf<Float?>(null, 0f, Float.NaN).forEach { aspect ->
            val plan = policy.toggle(
                isFullscreen = false,
                videoAspect = aspect,
                portraitWindow = true,
                scaleMode = VideoScaleMode.FIT,
            )

            assertTrue(plan.isFullscreen)
            assertEquals("aspect=$aspect", RequestedOrientation.SENSOR, plan.orientation)
            assertFalse("aspect=$aspect", plan.fillScreen)
        }
    }

    @Test
    fun `exiting fullscreen restores system-controlled orientation`() {
        val plan = policy.toggle(
            isFullscreen = true,
            videoAspect = landscapeVideo,
            portraitWindow = false,
            scaleMode = VideoScaleMode.FILL,
        )

        assertFalse(plan.isFullscreen)
        assertEquals(RequestedOrientation.SENSOR, plan.orientation)
        assertFalse(plan.fillScreen)
    }
}
