package seeyuer.yingli.player.domain.playback

/** 全屏切换的窗口与画面决策。 */
data class FullscreenPlan(
    val isFullscreen: Boolean,
    val orientation: RequestedOrientation,
    val fillScreen: Boolean,
)

/**
 * 全屏语义（产品确认 + 决策 336）：
 *
 * - 竖屏播放横版视频：请求横屏全屏，画面按 contain 自然铺满；
 * - 竖屏播放竖版视频：保持竖屏，画面按 cover 填满屏幕（[FullscreenPlan.fillScreen]）；
 * - 横屏，或视频形状未知：只做沉浸全屏，不改方向、不裁切；
 * - 退出全屏：方向回到跟随系统，并取消填充。
 *
 * 刻意不改用户的画面比例偏好：填满由视图层负责，因此退出全屏时比例天然恢复，
 * 不需要额外记录/回滚比例状态。
 */
class FullscreenPolicy {
    fun toggle(
        isFullscreen: Boolean,
        videoAspect: Float?,
        portraitWindow: Boolean,
        scaleMode: VideoScaleMode,
    ): FullscreenPlan {
        if (isFullscreen) return FullscreenPlan(isFullscreen = false, orientation = RequestedOrientation.SENSOR, fillScreen = false)
        if (!portraitWindow) return FullscreenPlan(isFullscreen = true, orientation = RequestedOrientation.SENSOR, fillScreen = false)
        val shape = when {
            videoAspect == null || !videoAspect.isFinite() || videoAspect <= 0f -> VideoShape.UNKNOWN
            videoAspect > 1f -> VideoShape.LANDSCAPE
            else -> VideoShape.PORTRAIT
        }
        return when (shape) {
            VideoShape.LANDSCAPE -> FullscreenPlan(true, RequestedOrientation.LANDSCAPE, fillScreen = false)
            VideoShape.PORTRAIT -> FullscreenPlan(true, RequestedOrientation.SENSOR, fillScreen = scaleMode == VideoScaleMode.FIT)
            VideoShape.UNKNOWN -> FullscreenPlan(true, RequestedOrientation.SENSOR, fillScreen = false)
        }
    }
}

/** 视频相对当前方向的形状，只用于全屏决策。 */
enum class VideoShape {
    LANDSCAPE,
    PORTRAIT,
    UNKNOWN,
}
