package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import coil3.compose.AsyncImage
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.component.YingLiIconButton
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.playback.FrameCounterState
import seeyuer.yingli.player.domain.playback.ScreenshotUiState

/**
 * 截图工具胶囊是否在场：Armed 与 Capturing 都算「工具打开」——捕获中胶囊要保持在场
 * （捕获按钮切等待态），否则按下的瞬间胶囊会在手指底下消失；Preview 让位给预览卡、
 * Idle / Failed 都没有胶囊。
 *
 * 这里是**唯一**的判定入口：底栏用它决定「托盘行让位 + 胶囊滑入」，
 * Screen 层用它决定底栏要不要因为胶囊而留在组合里（截图工具是浮层，不吃控件自动隐藏）。
 */
internal fun ScreenshotUiState.isCapsuleVisible(): Boolean =
    this is ScreenshotUiState.Armed || this is ScreenshotUiState.Capturing

/**
 * 截图工具胶囊的**内容**，出入场动画由外层负责（见 `BottomPlaybackControls` 的
 * `screenshotTool` 插槽）：它占用竖屏「更多」工具托盘行的位置，从右向左滑入并停在这一行中间。
 *
 * 截图入口在托盘最右端（托盘按 `TOOLS` 槽位反序渲染，`SCREENSHOT` 为第一项 → 显示在最右），
 * 所以外层按整行宽度从右滑入，视觉上就是「从这个按钮的位置滑出来」，退出时反向滑回去；
 * 若改成从底部滑入，起点与刚才点的按钮毫无关系，动效会显得凭空出现。
 *
 * 动画不放在这里：内外两层位移会叠加成一段突兀的加速，而且可见性只有一处说了算。
 */
@Composable
internal fun ScreenshotToolCapsule(
    state: ScreenshotUiState,
    onPreviousFrame: () -> Unit,
    onCapture: () -> Unit,
    onNextFrame: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenshotCapsuleSurface(modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            YingLiIconButton(
                YingLiIcon.SEEK_BACKWARD,
                stringResource(R.string.player_previous_frame),
                onPreviousFrame,
                enabled = state is ScreenshotUiState.Armed,
                tint = YingLiTheme.player.controlPrimary,
            )
            YingLiIconButton(
                YingLiIcon.SCREENSHOT,
                stringResource(R.string.player_screenshot),
                onCapture,
                enabled = state is ScreenshotUiState.Armed,
                tint = YingLiTheme.player.controlPrimary,
            )
            YingLiIconButton(
                YingLiIcon.SEEK_FORWARD,
                stringResource(R.string.player_next_frame),
                onNextFrame,
                enabled = state is ScreenshotUiState.Armed,
                tint = YingLiTheme.player.controlPrimary,
            )
            YingLiIconButton(
                YingLiIcon.CLOSE,
                stringResource(R.string.action_cancel),
                onClose,
                tint = YingLiTheme.player.controlPrimary,
            )
        }
    }
}

/**
 * 截图工具与帧数胶囊共用的容器材质：**与底栏按钮同源**——同一份
 * [PlayerChromeControlFillAlpha] 底、[PlayerChromeControlBorderWidth] /
 * [PlayerChromeControlBorderAlpha] 细描边，以及同一枚胶囊形圆角
 * [PlayerChromeCapsuleShape]。胶囊与按钮同屏出现，各写一套 alpha 必然出现色差。
 *
 * 这里用 [BorderStroke] 而不是 `border` 参数：与 `PlayerChromeIconButton` 保持同一写法，
 * 描边宽度/透明度的唯一来源就是上面那几个常量。
 */
@Composable
private fun ScreenshotCapsuleSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = PlayerChromeCapsuleShape,
        color = YingLiTheme.player.controlPrimary.copy(alpha = PlayerChromeControlFillAlpha),
        contentColor = YingLiTheme.player.controlPrimary,
        border = BorderStroke(
            PlayerChromeControlBorderWidth,
            YingLiTheme.player.controlPrimary.copy(alpha = PlayerChromeControlBorderAlpha),
        ),
        content = content,
    )
}

/**
 * 帧数胶囊：截图模式下占用**顶部标题胶囊的位置**，内容为 `当前帧 / 总帧数`。
 *
 * 之所以与标题互换而不是同时显示：设计稿 §3.2 的浮层原则不允许浮层叠浮层，
 * 而 §4.1 里那个位置本来只属于标题胶囊。
 */
@Composable
internal fun FrameCounterCapsule(
    counter: FrameCounterState,
    modifier: Modifier = Modifier,
) {
    ScreenshotCapsuleSurface(modifier) {
        Text(
            text = "${counter.currentFrame} / ${counter.totalFrames}",
            color = YingLiTheme.player.controlPrimary,
            // 等宽数字：帧号每帧都在变，比例数字会让整段文本左右抖动（与进度时间文本同一做法）。
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
internal fun ScreenshotPreview(
    state: ScreenshotUiState.Preview,
    onTogglePause: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit = onClose,
) {
    Surface(
        onClick = onTogglePause,
        modifier = modifier.width(220.dp),
        shape = YingLiTheme.components.componentCorner,
        color = YingLiTheme.colors.surfaceComponent,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.fillMaxWidth().height(72.5.dp)) {
                if (state.uri.isNotBlank()) {
                    AsyncImage(
                        model = state.uri,
                        contentDescription = "截图预览",
                        modifier = Modifier.width(116.dp).aspectRatio(1.6f),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.displayName,
                    maxLines = 1,
                    color = YingLiTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${((state.remainingMillis + 999L) / 1_000L).coerceAtMost(3L)}s",
                    color = YingLiTheme.colors.textSecondary,
                )
                YingLiIconButton(
                    YingLiIcon.CLOSE,
                    stringResource(R.string.action_cancel),
                    onClose,
                    modifier = Modifier.size(48.dp),
                )
                YingLiIconButton(
                    YingLiIcon.WARNING,
                    "删除截图",
                    onDelete,
                    modifier = Modifier.size(48.dp),
                )
            }
            LinearProgressIndicator(
                progress = { state.remainingMillis / ScreenshotUiState.PREVIEW_DURATION_MILLIS.toFloat() },
                color = YingLiTheme.player.controlPrimary,
            )
        }
    }
}
