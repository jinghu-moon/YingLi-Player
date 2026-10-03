package seeyuer.yingli.player.feature.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import seeyuer.yingli.player.domain.playback.VideoRotation
import seeyuer.yingli.player.domain.playback.VideoScaleMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 画面旋转动画时长。 */
private const val ROTATION_ANIMATION_MILLIS = 320

/** 全屏填满/恢复的缩放动画时长。 */
private const val FILL_ANIMATION_MILLIS = 260

/** 某一时刻的画面旋转渲染参数。 */
internal data class VideoRotationRender(
    val rotationDegrees: Float,
    val scale: Float,
)

/**
 * 一块轴对齐矩形（像素，舞台坐标系）：[left]/[top] 是左上角，[right]/[bottom] 是右下角。
 *
 * 用它表达"视频画面**实际渲染**的区域"（不含黑边），而不是"承载画面的那个 Box"——
 * 后者在竖屏里等于整块画布，右下角就是屏幕右下角，与用户看到的画面角落差了整整一条黑边。
 */
internal data class VideoPictureBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * 画面旋转的几何计算（纯函数，可单测）。
 *
 * 旋转 90°/270° 时画面观的尺寸与画布互换，因此目标态的取景框要交换宽高再旋转；
 * 动画过程中再按旋转后的外接矩形做一次“保持完整可见”的缩放，
 * 避免旋转中途画面被屏幕边缘裁掉。
 */
internal object VideoRotationStageMath {
    /** 顺时针 90° 的整数倍方向（0/90/180/270…）需要交换取景框宽高。 */
    fun swapsStage(degrees: Float): Boolean = (degrees / 90f).roundToInt().let { steps -> abs(steps) % 2 == 1 }

    /**
     * @param stageWidth 播放画布宽（与 [stageHeight] 同单位即可）
     * @param videoAspect 视频宽高比；未知时传 null，此时不做额外缩放，只保证不越界
     * @param fromDegrees 动画起始角度
     * @param toDegrees 动画目标角度
     * @param progress 0..1 的动画进度
     */
    fun render(
        stageWidth: Float,
        stageHeight: Float,
        videoAspect: Float?,
        fromDegrees: Float,
        toDegrees: Float,
        progress: Float,
    ): VideoRotationRender {
        if (stageWidth <= 0f || stageHeight <= 0f) return VideoRotationRender(toDegrees, 1f)
        val fraction = progress.coerceIn(0f, 1f)
        val degrees = fromDegrees + (toDegrees - fromDegrees) * fraction

        val targetPicture = pictureSize(videoAspect, stageWidth, stageHeight, swapsStage(toDegrees))
        val startPicture = pictureSize(videoAspect, stageWidth, stageHeight, swapsStage(fromDegrees))
        // 画面始终是同一形状，只是取景缩放不同，因此用宽度比即可连续地从起点尺寸过渡到目标尺寸。
        val startScale = if (targetPicture.first <= 0f) 1f else startPicture.first / targetPicture.first
        val interpolated = startScale + (1f - startScale) * fraction

        val pictureWidth = targetPicture.first * interpolated
        val pictureHeight = targetPicture.second * interpolated
        val bounds = rotatedBounds(pictureWidth, pictureHeight, degrees)
        val keepVisible = minOf(
            1f,
            if (bounds.first <= 0f) 1f else stageWidth / bounds.first,
            if (bounds.second <= 0f) 1f else stageHeight / bounds.second,
        )
        return VideoRotationRender(rotationDegrees = degrees, scale = interpolated * keepVisible)
    }

    /** 取景框内按 contain 规则得到的画面尺寸（宽, 高）。 */
    private fun pictureSize(aspect: Float?, stageWidth: Float, stageHeight: Float, swapped: Boolean): Pair<Float, Float> {
        val boxWidth = if (swapped) stageHeight else stageWidth
        val boxHeight = if (swapped) stageWidth else stageHeight
        if (aspect == null || aspect <= 0f || boxHeight <= 0f) return boxWidth to boxHeight
        return if (boxWidth / boxHeight > aspect) {
            (boxHeight * aspect) to boxHeight
        } else {
            boxWidth to (boxWidth / aspect)
        }
    }

    /**
     * 让画面填满画布所需的等比放大系数（cover）。
     * 画面已经是 cover（比例恰好一致或未知）时返回 1，避免无意义放大。
     */
    fun coverScale(stageWidth: Float, stageHeight: Float, aspect: Float?): Float {
        if (aspect == null || aspect <= 0f || !aspect.isFinite()) return 1f
        if (stageWidth <= 0f || stageHeight <= 0f) return 1f
        val contained = pictureSize(aspect, stageWidth, stageHeight, swapped = false)
        if (contained.first <= 0f || contained.second <= 0f) return 1f
        return maxOf(stageWidth / contained.first, stageHeight / contained.second).coerceAtLeast(1f)
    }

    /** 旋转后画面的外接矩形（宽, 高）。 */
    private fun rotatedBounds(width: Float, height: Float, degrees: Float): Pair<Float, Float> {
        val radians = Math.toRadians(degrees.toDouble())
        val cosValue = abs(cos(radians)).toFloat()
        val sinValue = abs(sin(radians)).toFloat()
        return (width * cosValue + height * sinValue) to (width * sinValue + height * cosValue)
    }

    /**
     * 视频画面**实际渲染区域**的矩形（舞台坐标系，像素，已经过旋转/缩放，取轴对齐外接矩形）。
     *
     * 为什么要单独算：舞台里那个 `requiredSize(frameWidth, frameHeight)` 的 Box 只是播放输出的
     * **容器**，画面在容器内还要按缩放模式再摆一次（media3 的 `resizeMode` 口径）：
     *   - [VideoScaleMode.FIT] → contain：容器内居中，宽或高有一侧留黑边（letterbox）；
     *   - [VideoScaleMode.FILL] → cover：等比放大到铺满容器，多出来的部分被容器裁掉
     *     （此时"可见画面"就是容器本身，没有黑边）；
     *   - [VideoScaleMode.ORIGINAL] → 固定宽度：宽 = 容器宽，高按比例，可能上下溢出而只露出中间一条。
     * 容器还会被舞台旋转（90°/270° 时宽高互换）、并在 [fillScreen] 时整体等比放大到 cover。
     * 需求"飞入起点 = 视频画面的右下角"要的正是这整条链路的终点，**不能取根布局的右下角**。
     * 返回值最后会按画布夹一次：cover/旋转都可能让矩形超出画布，而用户看得到的只有画布内的部分。
     *
     * 宽高比未知（尚未拿到媒体信息）时退化为"画面铺满容器"：没有依据时宁可给容器角落，
     * 也不猜一个假比例把起点画到黑边里。
     */
    fun pictureBounds(
        stageWidth: Float,
        stageHeight: Float,
        videoAspect: Float?,
        scaleMode: VideoScaleMode,
        rotationDegrees: Float,
        fillScreen: Boolean,
    ): VideoPictureBounds {
        if (stageWidth <= 0f || stageHeight <= 0f) return VideoPictureBounds(0f, 0f, 0f, 0f)
        val swapped = swapsStage(rotationDegrees)
        // 舞台里的容器尺寸：旋转 90°/270° 时与画布互换（`VideoRotationStage` 的 requiredSize）。
        val frameWidth = if (swapped) stageHeight else stageWidth
        val frameHeight = if (swapped) stageWidth else stageHeight
        val frameLeft = (stageWidth - frameWidth) / 2f
        val frameTop = (stageHeight - frameHeight) / 2f
        val aspect = videoAspect?.takeIf { it.isFinite() && it > 0f }
        // 画面在容器内的绘制尺寸。
        val drawn = when {
            aspect == null -> frameWidth to frameHeight
            scaleMode == VideoScaleMode.FILL -> {
                val contained = pictureSize(aspect, frameWidth, frameHeight, swapped = false)
                val factor = maxOf(frameWidth / contained.first, frameHeight / contained.second)
                (contained.first * factor) to (contained.second * factor)
            }
            scaleMode == VideoScaleMode.ORIGINAL -> frameWidth to (frameWidth / aspect)
            else -> pictureSize(aspect, frameWidth, frameHeight, swapped = false)
        }
        // 画面在容器内居中；超出容器的部分（cover / 固定宽度）被容器裁掉。
        val left = (frameLeft + (frameWidth - drawn.first) / 2f).coerceAtLeast(frameLeft)
        val top = (frameTop + (frameHeight - drawn.second) / 2f).coerceAtLeast(frameTop)
        val right = (left + drawn.first).coerceAtMost(frameLeft + frameWidth)
        val bottom = (top + drawn.second).coerceAtMost(frameTop + frameHeight)
        // 舞台的 graphicsLayer：绕舞台中心旋转 + 等比缩放（fillScreen 时放大到 cover，与
        // `VideoRotationStage` 里的 coverScale 用同一个函数，保证"算出来的"和"画出来的"一致）。
        val scale = if (fillScreen) coverScale(stageWidth, stageHeight, aspect) else 1f
        val centerX = stageWidth / 2f
        val centerY = stageHeight / 2f
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val cosValue = cos(radians).toFloat()
        val sinValue = sin(radians).toFloat()
        val corners = listOf(
            left to top,
            right to top,
            left to bottom,
            right to bottom,
        ).map { (x, y) ->
            val dx = (x - centerX) * scale
            val dy = (y - centerY) * scale
            (centerX + dx * cosValue - dy * sinValue) to (centerY + dx * sinValue + dy * cosValue)
        }
        // 最后再按画布夹一次：fillScreen（放大到 cover）与旋转都会让矩形超出画布，
        // 而"画面上看得到的区域"只能落在画布里——飞入起点落到画布外就飞不回来了。
        return VideoPictureBounds(
            left = corners.minOf { it.first }.coerceAtLeast(0f),
            top = corners.minOf { it.second }.coerceAtLeast(0f),
            right = corners.maxOf { it.first }.coerceAtMost(stageWidth),
            bottom = corners.maxOf { it.second }.coerceAtMost(stageHeight),
        )
    }
}

/**
 * 画面旋转舞台：把视频输出按 [rotation] 旋转、按 [fillScreen] 决定是否填满画布，并在切换时播放动画。
 *
 * 顺/逆时针都按用户点击的方向前进（每次只转 90°），不会因为 270°→0° 反向绕一圈。
 * 填满（全屏且竖版视频）不改用户的画面比例偏好，而是在这里把输出等比放大到 cover，
 * 因此退出全屏时比例天然恢复，也没有裁切状态需要回滚。
 *
 * [content] 会收到 `transformed`：只要当前有任何变换（旋转或填满，含动画过程中）就为真，
 * 调用方需要据此选择可被视图层级变换的播放输出（详见 `Media3VideoSurface`）。
 * 一旦为真就保持到 [mediaKey] 变化：切换输出类型要重建播放视图、会有短暂黑帧，
 * 转回 0° 时立刻切回去会再闪一次，代价比一直用可变换输出更大。
 */
@Composable
internal fun VideoRotationStage(
    rotation: VideoRotation,
    videoAspect: Float?,
    modifier: Modifier = Modifier,
    fillScreen: Boolean = false,
    /** 自由缩放是否生效；生效期间同样需要可被视图层级变换的播放输出。 */
    zoomActive: Boolean = false,
    mediaKey: Any? = null,
    content: @Composable (transformed: Boolean) -> Unit,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val stageWidth = maxWidth
        val stageHeight = maxHeight

        var displayedTarget by remember { mutableFloatStateOf(rotation.degrees.toFloat()) }
        var fromDegrees by remember { mutableFloatStateOf(rotation.degrees.toFloat()) }
        var previousRotation by remember { mutableStateOf(rotation) }

        LaunchedEffect(rotation) {
            val steps = signedSteps(previousRotation, rotation)
            previousRotation = rotation
            if (steps != 0) {
                fromDegrees = displayedTarget
                displayedTarget += steps * 90f
            }
        }

        val animatedDegrees by animateFloatAsState(
            targetValue = displayedTarget,
            animationSpec = tween(durationMillis = ROTATION_ANIMATION_MILLIS),
            label = "video-rotation",
        )
        val span = displayedTarget - fromDegrees
        val progress = if (span == 0f) 1f else ((animatedDegrees - fromDegrees) / span).coerceIn(0f, 1f)
        val render = VideoRotationStageMath.render(
            stageWidth = stageWidth.value,
            stageHeight = stageHeight.value,
            videoAspect = videoAspect,
            fromDegrees = fromDegrees,
            toDegrees = displayedTarget,
            progress = progress,
        )

        val frameWidth: Dp = if (VideoRotationStageMath.swapsStage(displayedTarget)) stageHeight else stageWidth
        val frameHeight: Dp = if (VideoRotationStageMath.swapsStage(displayedTarget)) stageWidth else stageHeight
        val coverScale by animateFloatAsState(
            targetValue = if (fillScreen) VideoRotationStageMath.coverScale(stageWidth.value, stageHeight.value, videoAspect) else 1f,
            animationSpec = tween(durationMillis = FILL_ANIMATION_MILLIS),
            label = "video-fill",
        )
        val rotating = normalizedDegrees(animatedDegrees) > 0.01f || normalizedDegrees(displayedTarget) > 0.01f
        // 缩放在缩放生效期间同样要求走 TextureView：SurfaceView 由 SurfaceFlinger 单独合成，
        // 跨过 1.0x 或复位时会重新合成一帧，用户看到的就是"黑屏一闪"。
        val transformRequested = rotating || coverScale > 1.001f || fillScreen || zoomActive
        var transformedOnce by remember(mediaKey) { mutableStateOf(false) }
        LaunchedEffect(transformRequested) { if (transformRequested) transformedOnce = true }
        Box(
            modifier = Modifier
                .requiredSize(frameWidth, frameHeight)
                .graphicsLayer {
                    rotationZ = render.rotationDegrees
                    scaleX = render.scale * coverScale
                    scaleY = render.scale * coverScale
                },
        ) {
            content(transformRequested || transformedOnce)
        }
    }
}

/** 归一到 [0, 360) 的角度，用于判断当前是否还带着旋转。 */
internal fun normalizedDegrees(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f

/** 状态之间按最短方向需要的 90° 步数（-1/0/1/2）。 */
internal fun signedSteps(from: VideoRotation, to: VideoRotation): Int {
    val diff = (to.ordinal - from.ordinal + VideoRotation.entries.size) % VideoRotation.entries.size
    return when (diff) {
        0 -> 0
        1 -> 1
        2 -> 2
        else -> -1
    }
}
