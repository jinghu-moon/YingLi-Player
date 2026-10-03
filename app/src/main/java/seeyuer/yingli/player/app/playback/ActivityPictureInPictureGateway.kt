package seeyuer.yingli.player.app.playback

import android.app.Activity
import android.app.PictureInPictureParams
import android.graphics.Rect
import android.util.Rational
import seeyuer.yingli.player.domain.playback.PictureInPictureGateway

/**
 * 画中画入口（每个 Activity 只有一个实例）。
 *
 * ## 为什么是单实例 + 单一参数构造点
 *
 * 系统侧保存的是一整份**参数快照**（`ActivityRecord.pictureInPictureArgs`，逐项按"是否显式 set 过"覆盖），
 * 用户离开应用那一刻用的就是这份快照。所以"当前生效的参数"是**窗口级状态**：
 * 之前每个调用方各拿一个实例、各自拼一份参数，等于让同一份窗口状态有三个互不知情的副本
 *（手动进入时另造的一份参数甚至会把已下发的自动进入悄悄改掉）。这里收敛成一个实现：
 * 应用层共享同一个实例，[params] 是全类唯一的参数构造点。
 *
 * ## 谁负责何时下发
 *
 * - [applyAutoEnter]：策略输入（偏好 / 安全内容 / 有无媒体）变化时由 `MainActivity` 的收集器驱动；
 * - [refreshParams]：画面几何可能变了（旋转、进出全屏、从后台回来）时重新下发同一份参数，
 *   让 [sourceRectHint] 这份**快照**尽量新鲜——它同时是入场与**退出**动画的起点；
 * - [enter]：用户在页面里主动点画中画。
 *
 * [sourceRectHint] 给出视频画面在窗口里的矩形（由播放层提供，拿不到时为 null）：
 * Android 12+ 用它做**入场动画的起点**，没有它系统只能从整屏缩放过去，
 * 观感就是"一个窗口凭空出现"。它为 null 时不设这一项——错的起点比没有起点更难看。
 */
class ActivityPictureInPictureGateway(
    private val activity: Activity,
    private val sourceRectHint: () -> Rect? = { null },
) : PictureInPictureGateway {

    /**
     * 参数镜像的窗口侧副本：最后一次下发的「自动进入」取值。
     *
     * 为什么要自己存：`Activity.setPictureInPictureParams` 是"设置后由系统保存"的语义，
     * 客户端读不回来；而 [enter] 下发的参数会**逐项覆盖**系统保存的那份，
     * 因此手动进入时必须带上同一个值，否则会在用户不知情的情况下把自动进入关掉。
     */
    private var autoEnterEnabled = false

    override fun isAvailable(): Boolean =
        activity.packageManager.hasSystemFeature("android.software.picture_in_picture")

    override fun applyAutoEnter(enabled: Boolean) {
        autoEnterEnabled = enabled
        pushParams()
    }

    override fun enter(): Boolean {
        if (!isAvailable()) return false
        return runCatching {
            activity.enterPictureInPictureMode(params(autoEnter = autoEnterEnabled))
        }.getOrDefault(false)
    }

    /**
     * 用**同一份镜像值**重新下发一次（几何变化时刷新 source rect 快照）。
     *
     * 没有武装过就什么都不做：source rect 这份快照只有自动进入用得上，
     * 而 [enter] 是当场现取的，不需要任何后台刷新。
     */
    fun refreshParams() {
        if (!autoEnterEnabled) return
        pushParams()
    }

    private fun pushParams() {
        if (!isAvailable()) return
        // 处于画中画时一律不下发：此刻量到的"视频画面矩形"是浮窗大小，写进去会污染
        // 退出画中画的动画起点（Android 12+ 复用同一份 source rect hint 做退出动画，
        // 见 PictureInPictureParams.Builder#setSourceRectHint 的文档）。
        // 退出画中画后 onResume / 配置回调会再刷一次，那时的矩形才是对的。
        if (activity.isInPictureInPictureMode) return
        // 重复下发同一份参数是安全的：aspect ratio 没变，不会触发系统的
        // "aspect ratio 变更过于频繁"配额（ActivityClientController 里的 quota tracker）。
        runCatching { activity.setPictureInPictureParams(params(autoEnter = autoEnterEnabled)) }
    }

    /**
     * 唯一的参数构造点：宽高比、入场动画起点、自动进入三项永远一起下发，
     * 不存在"某条路径少带一项"的可能。
     */
    private fun params(autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(DEFAULT_ASPECT_WIDTH, DEFAULT_ASPECT_HEIGHT))
            .setAutoEnterEnabled(autoEnter)
        // 直接调用而不是套在 apply{} 里：这是"入场动画起点"这一项的唯一赋值点，
        // 写成一条普通语句，静态检查（与读代码的人）都能一眼看见它确实被下发过。
        sourceRectHint()?.let { builder.setSourceRectHint(it) }
        return builder.build()
    }

    private companion object {
        const val DEFAULT_ASPECT_WIDTH = 16
        const val DEFAULT_ASPECT_HEIGHT = 9
    }
}
