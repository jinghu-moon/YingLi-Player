package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * 帧数胶囊：截图模式下显示 `当前帧 / 总帧数`，**位置在顶栏下方**（由调用方给出顶部内边距）。
 *
 * 为什么下沉而不是留在标题槽：竖屏底栏没有音轨/字幕键，这两个槽位被顶栏占着，帧数文本一长
 * （`488912 / 802008` 这种）就会压到右上角快捷按钮上——真机截图证实右端被音轨按钮压住。
 * 下沉到顶栏下方后，它与顶栏所有按钮在**视觉与点击区域**上都彻底分开：不再共享任何一条
 * 水平带，也就不会再有"字越长越容易压住按钮"这种随位数变化的隐患。
 *
 * 宽度约束只是**兜底**：调用方会先给出 `widthIn(max = ...)`（整屏宽度的一个比例）作为上限，
 * 极窄屏 / 最大字体下这里再按可用宽度反推字号（等宽数字，不换行、不省略），
 * 保证数字依旧完整可读；正常机型上推出来的字号就是基础字号，不做缩放。
 */
@Composable
internal fun FrameCounterCapsule(
    counter: FrameCounterState,
    modifier: Modifier = Modifier,
) {
    ScreenshotCapsuleSurface(modifier) {
        // 可用宽度 = 外层 widthIn 给出的上限（也是 BoxWithConstraints 的 maxWidth）。
        // 它同时封住了胶囊自身的最大宽度：字号再大也不会撑出这个宽度去压住别的控件。
        BoxWithConstraints {
            val text = "${counter.currentFrame} / ${counter.totalFrames}"
            val maxContentWidth = maxWidth - PlayerFrameCounterTextHorizontalPadding * 2
            Text(
                text = text,
                color = YingLiTheme.player.controlPrimary,
                // 等宽数字：帧号每帧都在变，比例数字会让整段文本左右抖动（与进度时间文本同一做法）。
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFeatureSettings = "tnum",
                    fontSize = playerFrameCounterFontSizeSp(
                        availableWidth = maxContentWidth,
                        characterCount = text.length,
                        baseFontSize = MaterialTheme.typography.labelLarge.fontSize,
                    ),
                ),
                maxLines = 1,
                // 兜底也不许省略成 `488912 / 80…`：宁可整体缩一圈，也要看到完整帧号。
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(
                    horizontal = PlayerFrameCounterTextHorizontalPadding,
                    vertical = 6.dp,
                ),
            )
        }
    }
}

/** 帧数文本左右内边距：字号兜底要用它扣掉非文本宽度，所以抽成唯一一份。 */
private val PlayerFrameCounterTextHorizontalPadding = 12.dp

/** 帧数文本字号下限：正常机型用不到，只在极窄屏 / 最大字体下兜底。 */
private val PlayerFrameCounterMinFontSize = 10.sp

/** 表格数字每字符宽度与字号之比（Roboto tabular figures 的 advance 约为 0.95em）。 */
private const val PlayerFrameCounterDigitAdvanceRatio = 0.95f

/**
 * 帧数文本能在 [availableWidth] 里完整放下的字号（sp），上限为 [baseFontSize]（调用方传主题的
 * `labelLarge`：与进度时间文本同一档，字号只有一个来源）。
 *
 * 依据：等宽数字每个字符约占 `0.95 × 字号`，`n` 个字符需要 `n × 字号 × 0.95`；
 * 取倒数即得字号。数字多一位只会让字号小一点，**不会**让某一位被裁掉——
 * 这正是"数字必须完整可读"这条要求在此处的实现。
 *
 * 纯函数（不读主题）以便直接做单元测试：主题字号由调用方传进来。
 */
internal fun playerFrameCounterFontSizeSp(
    availableWidth: Dp,
    characterCount: Int,
    baseFontSize: TextUnit,
): TextUnit {
    if (characterCount <= 0 || availableWidth <= 0.dp) return baseFontSize
    val fitted = availableWidth / (characterCount * PlayerFrameCounterDigitAdvanceRatio)
    return fitted.value.coerceIn(PlayerFrameCounterMinFontSize.value, baseFontSize.value).sp
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
