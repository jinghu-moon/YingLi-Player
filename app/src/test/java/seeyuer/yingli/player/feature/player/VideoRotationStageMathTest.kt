package seeyuer.yingli.player.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.domain.playback.VideoRotation

class VideoRotationStageMathTest {
    private val portraitStage = 1080f to 2400f
    private val landscapeVideoAspect = 16f / 9f

    @Test
    fun `quarter turns swap the framing box`() {
        assertEquals(false, VideoRotationStageMath.swapsStage(0f))
        assertEquals(true, VideoRotationStageMath.swapsStage(90f))
        assertEquals(false, VideoRotationStageMath.swapsStage(180f))
        assertEquals(true, VideoRotationStageMath.swapsStage(270f))
        assertEquals(false, VideoRotationStageMath.swapsStage(360f))
        assertEquals(true, VideoRotationStageMath.swapsStage(-90f))
    }

    @Test
    fun `animation ends exactly at the target angle with no extra scaling`() {
        val (width, height) = portraitStage

        val result = VideoRotationStageMath.render(width, height, landscapeVideoAspect, 0f, 90f, 1f)

        assertEquals(90f, result.rotationDegrees, 0.01f)
        assertEquals(1f, result.scale, 0.01f)
    }

    @Test
    fun `animation starts from the previous framing size`() {
        val (width, height) = portraitStage

        val result = VideoRotationStageMath.render(width, height, landscapeVideoAspect, 0f, 90f, 0f)

        // 起点必须和未旋转时的取景一致：16:9 视频在 1080x2400 画布中取 1080x607.5。
        assertEquals(0f, result.rotationDegrees, 0.01f)
        val pictureWidth = 1920f * result.scale
        val pictureHeight = 1080f * result.scale
        assertEquals(1080f, pictureWidth, 1f)
        assertEquals(607.5f, pictureHeight, 1f)
    }

    @Test
    fun `rotating picture never leaves the stage`() {
        val (width, height) = portraitStage

        listOf(0f to 90f, 90f to 180f, 180f to 270f, 270f to 0f, 0f to 270f).forEach { (from, to) ->
            for (step in 0..20) {
                val progress = step / 20f
                val result = VideoRotationStageMath.render(width, height, landscapeVideoAspect, from, to, progress)
                val swapped = VideoRotationStageMath.swapsStage(to)
                val boxWidth = if (swapped) height else width
                val boxHeight = if (swapped) width else height
                val pictureWidth = minOf(boxWidth, boxHeight * landscapeVideoAspect) * result.scale
                val pictureHeight = pictureWidth / landscapeVideoAspect

                val radians = Math.toRadians(result.rotationDegrees.toDouble())
                val cos = kotlin.math.abs(kotlin.math.cos(radians)).toFloat()
                val sin = kotlin.math.abs(kotlin.math.sin(radians)).toFloat()
                val boundsWidth = pictureWidth * cos + pictureHeight * sin
                val boundsHeight = pictureWidth * sin + pictureHeight * cos

                assertTrue(
                    "$from->$to @ $progress 越界：${boundsWidth}x${boundsHeight}",
                    boundsWidth <= width + 1f && boundsHeight <= height + 1f,
                )
            }
        }
    }

    @Test
    fun `unknown video aspect still fits the stage`() {
        val (width, height) = portraitStage

        val result = VideoRotationStageMath.render(width, height, null, 0f, 90f, 0.5f)

        assertTrue(result.scale > 0f)
        assertEquals(45f, result.rotationDegrees, 0.01f)
        assertTrue(result.scale <= 1f)
    }

    @Test
    fun `zero sized stage is a no-op`() {
        val result = VideoRotationStageMath.render(0f, 0f, landscapeVideoAspect, 0f, 90f, 0.5f)

        assertEquals(90f, result.rotationDegrees, 0.01f)
        assertEquals(1f, result.scale, 0.01f)
    }
}

class VideoRotationStateTest {
    @Test
    fun `shortcut cycles clockwise through four angles`() {
        assertEquals(VideoRotation.DEGREES_90, VideoRotation.DEGREES_0.next())
        assertEquals(VideoRotation.DEGREES_180, VideoRotation.DEGREES_90.next())
        assertEquals(VideoRotation.DEGREES_270, VideoRotation.DEGREES_180.next())
        assertEquals(VideoRotation.DEGREES_0, VideoRotation.DEGREES_270.next())
    }

    @Test
    fun `angles map to rotation degrees`() {
        assertEquals(
            listOf(0, 90, 180, 270),
            VideoRotation.entries.map(VideoRotation::degrees),
        )
    }

    @Test
    fun `state changes always turn the short way`() {
        assertEquals(0, signedSteps(VideoRotation.DEGREES_0, VideoRotation.DEGREES_0))
        assertEquals(1, signedSteps(VideoRotation.DEGREES_0, VideoRotation.DEGREES_90))
        assertEquals(2, signedSteps(VideoRotation.DEGREES_0, VideoRotation.DEGREES_180))
        assertEquals(-1, signedSteps(VideoRotation.DEGREES_0, VideoRotation.DEGREES_270))
        assertEquals(1, signedSteps(VideoRotation.DEGREES_270, VideoRotation.DEGREES_0))
    }
}
