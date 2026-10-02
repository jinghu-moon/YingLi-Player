package seeyuer.yingli.player.feature.player

import androidx.annotation.StringRes
import seeyuer.yingli.player.R
import seeyuer.yingli.player.domain.playback.PlaybackCommandRejection
import seeyuer.yingli.player.domain.playback.PlaybackOrder
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.VideoRotation
import seeyuer.yingli.player.domain.playback.VideoScaleMode

/**
 * 播放顺序的唯一文案映射。顶栏溢出菜单、设置面板和底栏快捷按钮共用，
 * 避免多处各写一份中文而出现“顺序 / 顺序播放”这类不一致。
 */
@StringRes
internal fun playbackOrderLabelRes(order: PlaybackOrder): Int = when (order) {
    PlaybackOrder.SEQUENCE -> R.string.player_order_sequence
    PlaybackOrder.SHUFFLE -> R.string.player_order_shuffle
    PlaybackOrder.QUEUE_REPEAT -> R.string.player_order_queue_repeat
    PlaybackOrder.SINGLE_REPEAT -> R.string.player_order_single_repeat
}

/**
 * 画面旋转状态的唯一文案映射。底栏按钮提示、设置面板单选共用。
 * 顺时针 90° 与逆时针 90° 分别叫「向右 90°」「向左 90°」，180° 两个方向结果相同，只留一项。
 */
@StringRes
internal fun videoRotationLabelRes(rotation: VideoRotation): Int = when (rotation) {
    VideoRotation.DEGREES_0 -> R.string.player_rotation_0
    VideoRotation.DEGREES_90 -> R.string.player_rotation_90
    VideoRotation.DEGREES_180 -> R.string.player_rotation_180
    VideoRotation.DEGREES_270 -> R.string.player_rotation_270
}

/**
 * 画面比例状态的唯一文案映射。底栏快捷按钮提示与统一瞬时反馈共用。
 */
@StringRes
internal fun videoScaleModeLabelRes(mode: VideoScaleMode): Int = when (mode) {
    VideoScaleMode.FIT -> R.string.player_scale_fit
    VideoScaleMode.FILL -> R.string.player_scale_fill
    VideoScaleMode.ORIGINAL -> R.string.player_scale_original
}

/**
 * 倍速显示文案：整数档不带小数（`1x`、`2x`），其余保留一位或两位（`0.75x`、`1.25x`）。
 * 档位条、按钮读屏文案、统一反馈都用它，避免三处各写一种格式。
 */
internal fun PlaybackSpeed.displayLabel(): String {
    val rounded = value
    return if (rounded % 1f == 0f) "${rounded.toInt()}x" else "${rounded}x"
}

/**
 * 命令拒绝码（[PlaybackCommandRejection.name]）到提示文案的唯一映射。
 * 未知码回退到通用文案，这样会话层新增拒绝原因时 UI 仍然有反馈，
 * 不会退化成静默失败。
 */
@StringRes
internal fun playbackRejectionMessageRes(code: String): Int = when (code) {
    PlaybackCommandRejection.NOT_CONNECTED.name -> R.string.player_reject_not_connected
    PlaybackCommandRejection.INVALID_STATE.name -> R.string.player_reject_invalid_state
    PlaybackCommandRejection.SOURCE_UNAVAILABLE.name -> R.string.player_reject_source_unavailable
    PlaybackCommandRejection.NO_CANDIDATE.name -> R.string.player_reject_no_candidate
    PlaybackCommandRejection.CAPABILITY_UNAVAILABLE.name -> R.string.player_reject_capability_unavailable
    PlaybackCommandRejection.TRACK_UNAVAILABLE.name -> R.string.player_reject_track_unavailable
    PlaybackCommandRejection.INVALID_AB_RANGE.name -> R.string.player_reject_invalid_ab_range
    PlaybackCommandRejection.STALE_COMMAND.name -> R.string.player_reject_stale_command
    else -> R.string.player_reject_unknown
}
