package seeyuer.yingli.player.domain.playback

/**
 * "上一个"按钮的两种口径，由偏好 [PlayerPreferences.previousRestartsCurrentItem] 选择：
 *
 * - `true`（默认）：当前已播放超过 [PREVIOUS_RESTART_THRESHOLD_MILLIS] 时先回到本集开头，
 *   再按一次才切上一项 —— 这是多数播放器（含 REX-Player）的惯例，避免误触丢失进度；
 * - `false`：永远直接切上一项。
 *
 * 判定放在域层纯函数里：它不依赖播放器状态，可以直接单测，也避免 UI/客户端各自实现一套规则。
 */
const val PREVIOUS_RESTART_THRESHOLD_MILLIS: Long = 5_000L

/** 见 [PREVIOUS_RESTART_THRESHOLD_MILLIS]：返回 true 表示这次"上一个"应当先回到本集开头。 */
fun shouldRestartCurrentItemOnPrevious(
    positionMillis: Long,
    previousRestartsCurrentItem: Boolean,
): Boolean = previousRestartsCurrentItem && positionMillis > PREVIOUS_RESTART_THRESHOLD_MILLIS
