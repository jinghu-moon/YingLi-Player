package seeyuer.yingli.player.feature.settings

import seeyuer.yingli.player.domain.playback.PlaybackSpeed

/**
 * 画面手势设置的回调集合（规格 FR-PLAYER-004 / #208：每项手势可分别关闭）。
 *
 * 集中成一个对象是为了**逐层转发一个参数**而不是八个：设置页在 `RouteContent` 里，
 * 从主 composable 到它中间隔着几层只做转发的函数，八个回调分别透传很容易漏。
 * 全部默认空实现，预览与测试可以只关心需要的一项。
 */
data class GestureSettingsCallbacks(
    val onSeekEnabled: (Boolean) -> Unit = {},
    val onVolumeEnabled: (Boolean) -> Unit = {},
    val onBrightnessEnabled: (Boolean) -> Unit = {},
    val onZoomEnabled: (Boolean) -> Unit = {},
    val onLeftSideIsVolume: (Boolean) -> Unit = {},
    val onDoubleTapSeekMillis: (Int) -> Unit = {},
    val onSwipeDownToExitEnabled: (Boolean) -> Unit = {},
    val onLongPressSpeed: (PlaybackSpeed) -> Unit = {},
)
