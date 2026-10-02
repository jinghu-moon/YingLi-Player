package seeyuer.yingli.player.feature.player

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.playback.PlaybackSpeed

/** 手势提示的显示时长（规格 §5.15 / §19.3）：约 2800ms 后自动消失并记为「已展示」。 */
internal const val GESTURE_HINT_SHOW_MILLIS = 2_800L

/**
 * 一条提示文案。顺序即显示顺序；[longPressSpeedArgument] 为真时，
 * 文案要把当前长按档位（设置里可选 1.5/2/3 倍）作为参数拼进去，不能写死成 2x。
 */
internal data class GestureHintEntry(
    @StringRes val labelRes: Int,
    val longPressSpeedArgument: Boolean = false,
)

/**
 * 手势提示条目的唯一来源：可见文案与读屏文案同源，
 * 条目覆盖音量/亮度/进度/长按倍速/双击播放暂停/双指缩放六项手势。
 */
internal val GESTURE_HINT_ENTRIES: List<GestureHintEntry> = listOf(
    GestureHintEntry(R.string.player_gesture_hint_volume),
    GestureHintEntry(R.string.player_gesture_hint_brightness),
    GestureHintEntry(R.string.player_gesture_hint_seek),
    GestureHintEntry(R.string.player_gesture_hint_long_press_speed, longPressSpeedArgument = true),
    GestureHintEntry(R.string.player_gesture_hint_double_tap_center),
    GestureHintEntry(R.string.player_gesture_hint_zoom),
)

/**
 * 常规播放页首次进入的一次性手势提示（规格 §5.15 / §19.3）。
 *
 * 只是一块深色半透明浮岛：浅色文字 + [YingLiTheme.player.edgeScrim] 底，不做大面积彩色遮罩，
 * 也不接管触摸（画面手势照常穿透）。显示时长由调用方 [PlayerScreen] 计时，这里不持有状态；
 * 整块浮岛合并成一个读屏节点并作为 liveRegion 播报，读屏用户听到的条目与可见文案完全一致。
 */
@Composable
internal fun PlayerGestureHint(
    longPressSpeed: PlaybackSpeed,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
        },
        shape = YingLiTheme.components.componentCorner,
        color = YingLiTheme.player.edgeScrim,
        contentColor = YingLiTheme.player.controlPrimary,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GESTURE_HINT_ENTRIES.forEach { entry ->
                Text(
                    text = if (entry.longPressSpeedArgument) {
                        stringResource(entry.labelRes, longPressSpeed.displayLabel())
                    } else {
                        stringResource(entry.labelRes)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
