package seeyuer.yingli.player.domain.playback

/**
 * 退到后台时是否应暂停：用户关掉了后台播放（[PlayerPreferences.backgroundPlaybackEnabled] = false），
 * 且当前不在画中画里。
 *
 * 为什么画中画不暂停：画中画本身就是"人离开了这个页面"的一种形态，画面仍然可见、可交互，
 * 此时暂停等于把 PiP 变成一个静止的缩略图 —— 与用户开 PiP 的意图相反。
 *
 * 判定放在域层纯函数里：它只依赖偏好与一个生命周期标志，不碰播放器，既能直接单测，
 * 也避免 Activity 与将来可能的其它宿主各自实现一套规则。
 */
fun shouldPauseInBackground(
    backgroundPlaybackEnabled: Boolean,
    inPictureInPicture: Boolean,
): Boolean = !backgroundPlaybackEnabled && !inPictureInPicture
