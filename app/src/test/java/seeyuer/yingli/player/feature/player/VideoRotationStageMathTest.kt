package seeyuer.yingli.player.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.domain.playback.VideoRotation
import seeyuer.yingli.player.domain.playback.VideoScaleMode

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

/**
 * "视频画面实际渲染区域"（[VideoRotationStageMath.pictureBounds]）的单测。
 *
 * 这是截图预览卡飞入**起点**的唯一依据：起点要落在画面（不含黑边）的右下角，
 * 而不是承载画面的容器右下角。竖屏里容器等于整块画布，两者相差整整一条 letterbox，
 * 所以下面既钉住"有黑边时起点上移"，也钉住"cover/固定宽度时可见画面就是整块画布"。
 */
class VideoPictureBoundsTest {
    private val portraitStage = 1080f to 2400f
    private val landscapeVideoAspect = 16f / 9f

    private fun bounds(
        stage: Pair<Float, Float> = portraitStage,
        aspect: Float? = landscapeVideoAspect,
        scaleMode: VideoScaleMode = VideoScaleMode.FIT,
        rotation: Float = 0f,
        fillScreen: Boolean = false,
    ): VideoPictureBounds = VideoRotationStageMath.pictureBounds(
        stageWidth = stage.first,
        stageHeight = stage.second,
        videoAspect = aspect,
        scaleMode = scaleMode,
        rotationDegrees = rotation,
        fillScreen = fillScreen,
    )

    @Test
    fun `letterboxed picture bottom right sits above the canvas corner`() {
        // 16:9 视频在 1080x2400 画布上按 contain 得到 1080x607.5 的横带，居中：
        // 上下各留 896.25 的黑边，于是画面右下角是 (1080, 1503.75) —— **不是**屏幕右下角 (1080, 2400)。
        val picture = bounds()

        assertEquals(0f, picture.left, 0.5f)
        assertEquals(896.25f, picture.top, 0.5f)
        assertEquals(1080f, picture.right, 0.5f)
        assertEquals(1503.75f, picture.bottom, 0.5f)
        assertTrue("画面下沿必须明显高于画布下沿", picture.bottom < 2400f - 800f)
    }

    @Test
    fun `unknown aspect falls back to the whole canvas`() {
        // 拿不到媒体宽高时不猜比例：宁可给画布角落，也不能把起点画进一条并不存在的黑边里。
        val picture = bounds(aspect = null)

        assertEquals(0f, picture.left, 0.01f)
        assertEquals(0f, picture.top, 0.01f)
        assertEquals(1080f, picture.right, 0.01f)
        assertEquals(2400f, picture.bottom, 0.01f)
    }

    @Test
    fun `cover mode leaves no black bars so the visible picture is the whole canvas`() {
        val picture = bounds(scaleMode = VideoScaleMode.FILL)

        // cover：等比放大到铺满，多出来的部分被容器裁掉 → 可见画面 = 整块画布。
        assertEquals(0f, picture.left, 0.5f)
        assertEquals(0f, picture.top, 0.5f)
        assertEquals(1080f, picture.right, 0.5f)
        assertEquals(2400f, picture.bottom, 0.5f)
    }

    @Test
    fun `fixed width mode uses the container width even when that overflows`() {
        // 横屏画布 + 正方形视频：contain 会得到居中的 1080x1080（左右各 660 黑边），
        // 固定宽度则铺满容器宽度、上下溢出后被裁掉 → 可见画面又是整块画布。两种模式必须算得不同。
        val square = 1f
        val contain = bounds(stage = 2400f to 1080f, aspect = square, scaleMode = VideoScaleMode.FIT)
        val fixedWidth = bounds(stage = 2400f to 1080f, aspect = square, scaleMode = VideoScaleMode.ORIGINAL)

        assertEquals(660f, contain.left, 0.5f)
        assertEquals(1740f, contain.right, 0.5f)
        assertEquals(0f, fixedWidth.left, 0.5f)
        assertEquals(2400f, fixedWidth.right, 0.5f)
    }

    @Test
    fun `quarter turn keeps the picture inside the canvas`() {
        val picture = bounds(rotation = 90f)

        // 旋转 90° 后画面是竖着的 1080x1920，居中落在画布内：左右贴边、上下各留 240。
        assertEquals(0f, picture.left, 1f)
        assertEquals(1080f, picture.right, 1f)
        assertEquals(240f, picture.top, 1f)
        assertEquals(2160f, picture.bottom, 1f)
        assertTrue(picture.left >= 0f && picture.top >= 0f)
        assertTrue(picture.right <= 1080f && picture.bottom <= 2400f)
    }

    @Test
    fun `fill screen scale is clipped to the canvas`() {
        // fillScreen 把画面放大到 cover（1.25 倍），横向会溢出画布：
        // 可见画面只能是画布本身，起点不能落到屏幕外。
        val picture = bounds(aspect = 9f / 16f, fillScreen = true)

        assertEquals(0f, picture.left, 0.5f)
        assertEquals(0f, picture.top, 0.5f)
        assertEquals(1080f, picture.right, 0.5f)
        assertEquals(2400f, picture.bottom, 0.5f)
    }

    @Test
    fun `degenerate stage yields an empty rect instead of NaN`() {
        val picture = bounds(stage = 0f to 0f)

        assertEquals(0f, picture.left, 0.01f)
        assertEquals(0f, picture.right, 0.01f)
        assertEquals(0f, picture.bottom, 0.01f)
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
