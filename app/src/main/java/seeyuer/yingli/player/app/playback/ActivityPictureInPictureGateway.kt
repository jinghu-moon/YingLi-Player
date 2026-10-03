package seeyuer.yingli.player.app.playback

import android.app.Activity
import android.app.PictureInPictureParams
import android.graphics.Rect
import android.util.Rational
import seeyuer.yingli.player.domain.playback.PictureInPictureGateway

/**
 * 画中画入口。
 *
 * [sourceRectHint] 给出视频画面在窗口里的矩形（由播放层提供，拿不到时为 null）：
 * Android 12+ 用它做**入场动画的起点**，没有它系统只能从整屏缩放过去，
 * 观感就是"一个窗口凭空出现"。它为 null 时不设这一项——错的起点比没有起点更难看。
 */
class ActivityPictureInPictureGateway(
    private val activity: Activity,
    private val sourceRectHint: () -> Rect? = { null },
) : PictureInPictureGateway {
    override fun isAvailable(): Boolean =
        activity.packageManager.hasSystemFeature("android.software.picture_in_picture")

    override fun enter(): Boolean {
        if (!isAvailable()) return false
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(DEFAULT_ASPECT_WIDTH, DEFAULT_ASPECT_HEIGHT))
        // 直接调用而不是套在 apply{} 里：这是"入场动画起点"这一项的唯一赋值点，
        // 写成一条普通语句，静态检查（与读代码的人）都能一眼看见它确实被下发过。
        sourceRectHint()?.let { builder.setSourceRectHint(it) }
        return runCatching {
            activity.enterPictureInPictureMode(builder.build())
        }.getOrDefault(false)
    }

    private companion object {
        const val DEFAULT_ASPECT_WIDTH = 16
        const val DEFAULT_ASPECT_HEIGHT = 9
    }
}
