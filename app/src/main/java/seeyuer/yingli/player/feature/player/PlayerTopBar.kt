package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.icon.imageVector
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.playback.PlaybackOrder
import seeyuer.yingli.player.domain.playback.PlayerControlId
import seeyuer.yingli.player.domain.playback.PlayerControlLayout
import seeyuer.yingli.player.domain.playback.PlayerControlSurface
import seeyuer.yingli.player.domain.playback.ScreenshotUiState

@Composable
internal fun PlayerTopBar(
    state: PlayerUiState,
    onBack: () -> Unit,
    onScreenshot: () -> Unit,
    onPictureInPicture: () -> Unit,
    onOpenPlaylist: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenVideoInfo: () -> Unit,
    onOpenAbTool: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleLock: () -> Unit,
    onSetPlaybackOrder: (PlaybackOrder) -> Unit,
    onCycleScaleMode: () -> Unit,
    controlLayout: PlayerControlLayout,
    landscape: Boolean,
    allowScreenshot: Boolean,
    allowPictureInPicture: Boolean,
    modifier: Modifier,
) {
    if (!state.overlay.controlsVisible) return
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(
                start = PlayerTopBarStartPadding,
                end = PlayerTopBarEndPadding,
                top = PlayerTopBarVerticalPadding,
                bottom = PlayerTopBarVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerChromeIconButton(
            icon = YingLiIcon.BACK,
            contentDescription = stringResource(R.string.action_back),
            onClick = onBack,
        )
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.weight(1f).padding(
                start = PlayerTopBarTitleStartPadding,
                end = PlayerTopBarTitleEndPadding,
            ),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            // 截图模式下标题不再让位：帧数胶囊已经下移到顶栏下方（见 PlayerScreen 里
            // 以 PlayerTopBarContentHeight 算出的顶部内边距），与标题不再争同一个槽位，两者可同时在场。
            Text(
                text = state.title.ifBlank { "未知视频" },
                color = YingLiTheme.player.controlPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
            state.playerSubtitle()?.let { subtitle ->
                Text(
                    text = subtitle,
                    color = YingLiTheme.player.controlSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
        }
        controlLayout.controls(PlayerControlSurface.LANDSCAPE_TOP_RIGHT)
            .filterNot { !landscape && it in setOf(PlayerControlId.AUDIO, PlayerControlId.SUBTITLE) }
            .forEach { id ->
                val action = when (id) {
                    PlayerControlId.PLAYLIST -> onOpenPlaylist
                    PlayerControlId.AUDIO, PlayerControlId.SUBTITLE -> onOpenSettings
                    PlayerControlId.SCALE -> onCycleScaleMode
                    PlayerControlId.INFO -> onOpenVideoInfo
                    PlayerControlId.SCREENSHOT -> onScreenshot
                    PlayerControlId.AB_LOOP -> onOpenAbTool
                    PlayerControlId.PIP -> onPictureInPicture
                    PlayerControlId.LOCK -> onToggleLock
                    PlayerControlId.SETTINGS -> onOpenSettings
                    PlayerControlId.PREVIOUS -> onPrevious
                    PlayerControlId.NEXT -> onNext
                    else -> onOpenSettings
                }
                val icon = when (id) {
                    PlayerControlId.PLAYLIST -> YingLiIcon.PLAYLIST
                    PlayerControlId.AUDIO -> YingLiIcon.VOLUME
                    PlayerControlId.SUBTITLE -> YingLiIcon.SUBTITLES
                    PlayerControlId.SCALE -> videoScaleModeIcon(state.scaleMode)
                    PlayerControlId.INFO -> YingLiIcon.DIAGNOSTICS
                    PlayerControlId.SCREENSHOT -> YingLiIcon.SCREENSHOT
                    PlayerControlId.AB_LOOP -> YingLiIcon.AB2
                    PlayerControlId.PIP -> YingLiIcon.PICTURE_IN_PICTURE
                    PlayerControlId.LOCK -> YingLiIcon.LOCK
                    PlayerControlId.SETTINGS -> YingLiIcon.SETTINGS
                    PlayerControlId.PREVIOUS -> YingLiIcon.PREVIOUS
                    PlayerControlId.NEXT -> YingLiIcon.NEXT
                    else -> YingLiIcon.SETTINGS
                }
                // AB 循环是"开关"：生效中同样用实心表达，与托盘、设置面板 chip 读**同一个**判定
                // （[abLoopActive]），并把该状态写进语义（读屏会念"已选中"，测试据此断言激活态）。
                PlayerChromeIconButton(
                    icon,
                    id.name,
                    action,
                    size = PlayerChromeButtonSize,
                    filled = id == PlayerControlId.AB_LOOP && state.abLoopActive,
                    selectedState = if (id == PlayerControlId.AB_LOOP) state.abLoopActive else null,
                )
            }
        Box {
            PlayerChromeIconButton(
                icon = YingLiIcon.OVERFLOW,
                contentDescription = "更多播放选项",
                onClick = { menuExpanded = true },
            )
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                // 文案区分"进入截图模式"与"按下拍摄"（后者在截图胶囊里），
                // 图标也按设计稿 §2.3 分成 camera / camera-filled 两个。
                // 用户可见文案一律走字符串资源（与 transport 底栏同一份），不在各处手写中文：
                // 手写副本既会与资源里的文案漂移，也让资源看起来"没人用"。
                if (allowScreenshot) MenuItem(stringResource(R.string.player_screenshot), onScreenshot) { menuExpanded = false }
                if (allowPictureInPicture) MenuItem(stringResource(R.string.player_pip), onPictureInPicture) { menuExpanded = false }
                MenuItem(stringResource(R.string.player_playlist), onOpenPlaylist) { menuExpanded = false }
                MenuItem("播放设置", onOpenSettings) { menuExpanded = false }
                MenuItem("视频信息", onOpenVideoInfo) { menuExpanded = false }
                MenuItem("A-B 循环", onOpenAbTool) { menuExpanded = false }
                MenuItem(stringResource(R.string.player_previous), onPrevious) { menuExpanded = false }
                MenuItem(stringResource(R.string.player_next), onNext) { menuExpanded = false }
                MenuItem(if (state.overlay.locked) "解锁屏幕" else "锁定屏幕", onToggleLock) { menuExpanded = false }
                PlaybackOrder.entries.forEach { order ->
                    val orderLabel = stringResource(playbackOrderLabelRes(order))
                    MenuItem(
                        stringResource(R.string.player_order_state, orderLabel),
                        { onSetPlaybackOrder(order) },
                    ) { menuExpanded = false }
                }
            }
        }
    }
}

/**
 * 顶栏与底栏共用的圆形按钮尺寸。两处必须引用同一常量，
 * 否则同一屏上的按钮圆径不一致。
 */
internal val PlayerChromeButtonSize = 48.dp

/**
 * 顶栏的水平内边距与标题槽两侧留白（都是渲染顶栏本身用的字面量）。
 *
 * 之所以抽成常量而不是留在 `padding(...)` 里：顶栏高度（[PlayerTopBarContentHeight]）要由这些
 * 数字算出来给帧数胶囊定位，写死字面量就会变成"改一处忘一处"，胶囊又会贴回顶栏按钮上。
 */
private val PlayerTopBarStartPadding = 16.dp
private val PlayerTopBarEndPadding = 12.dp

/** 顶栏上下内边距：与按钮尺寸一起决定顶栏的占用高度。 */
private val PlayerTopBarVerticalPadding = 8.dp

/** 标题槽与左侧返回按钮、右侧快捷按钮之间的留白。 */
private val PlayerTopBarTitleStartPadding = 12.dp
private val PlayerTopBarTitleEndPadding = 8.dp

/**
 * 帧数胶囊贴在顶栏下方时，与顶栏底边之间留出的间距（下移量 = 顶栏高度 + 它）。
 *
 * 取 12dp：与项目里既有的 8/12/16dp 一档间距一致（[PlayerShortcutSpacing] 8dp、
 * 顶栏右侧内边距 12dp）。这个间距同时也是"胶囊与顶栏按钮彻底分开"的视觉保险。
 */
internal val PlayerFrameCounterTopGap = 12.dp

/**
 * 顶栏**自身**占用的高度（不含状态栏内边距）：上下内边距 + 一枚快捷按钮的圆径。
 *
 * 顶栏是一个 `Row`，行高由最高子项决定，而子项里最高的就是 [PlayerChromeButtonSize] 的圆按钮
 * （标题那段是两行文本，在竖屏标题较短时也不会超过 48dp 的按钮）。所以这里就是顶栏的真实高度，
 * 而不是估出来的数字。
 */
internal val PlayerTopBarContentHeight = PlayerTopBarVerticalPadding * 2 + PlayerChromeButtonSize

/**
 * 底栏按钮的材质（底色 / 描边 / 形状）。截图胶囊与帧数胶囊必须与底栏按钮**同源**：
 * 它们与按钮出现在同一屏上，另造一套视觉会立刻看出色差与圆角不一致，
 * 因此这里抽出唯一一份定义，按钮与胶囊都只引用它，不再各写 alpha 字面量。
 */
internal val PlayerChromeControlFillAlpha = 0.10f

/** 与底栏按钮同源的细描边宽度与透明度。 */
internal val PlayerChromeControlBorderWidth = 1.dp
internal val PlayerChromeControlBorderAlpha = 0.12f

/** 与底栏按钮同源的圆角：全圆（胶囊形），对应设计稿 `border-radius: 999px`。 */
internal val PlayerChromeCapsuleShape = CircleShape

@Composable
internal fun PlayerChromeIconButton(
    icon: YingLiIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: androidx.compose.ui.unit.Dp = PlayerChromeButtonSize,
    tint: Color = YingLiTheme.player.controlPrimary,
    filled: Boolean = false,
    /** 非空时按钮内显示这段短文本而不是图标（例如倍速数值）。 */
    valueLabel: String? = null,
    /**
     * **开关型**按钮的选中态（[PlayerControlId.MIRROR_HORIZONTAL] / 后台播放 / AB 循环这一族）。
     *
     * 为什么单独开一个参数而不是直接写 `selected = filled`：`filled` 在这里兼着两种含义 ——
     * "开关已生效"（镜像、后台播放、AB 循环）与"这是当前最强调的动作"（中央播放键）。
     * 后者说"已选中"是错的语义，所以只有真正的开关才传这个值（非开关一律传 null，语义树上
     * 不会凭空多出一个 `selected`）。
     *
     * 有了它，"激活态"才是**可断言**的：颜色（实心）本身既读不出也测不了，读屏也需要
     * "已选中"这句话。断言点：`assertIsSelected()` / `assertIsNotSelected()`。
     */
    selectedState: Boolean? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(size)
            .then(if (selectedState == null) Modifier else Modifier.semantics { selected = selectedState }),
        enabled = enabled,
        shape = CircleShape,
        color = if (filled) {
            YingLiTheme.player.controlPrimary
        } else {
            YingLiTheme.player.controlPrimary.copy(alpha = PlayerChromeControlFillAlpha)
        },
        contentColor = if (filled) YingLiTheme.player.canvas else tint,
        border = if (filled) {
            null
        } else {
            BorderStroke(PlayerChromeControlBorderWidth, YingLiTheme.player.controlPrimary.copy(alpha = PlayerChromeControlBorderAlpha))
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (valueLabel != null) {
                Text(
                    text = valueLabel,
                    color = if (filled) YingLiTheme.player.canvas else tint,
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    modifier = Modifier.semantics { this.contentDescription = contentDescription },
                )
            } else {
                Icon(
                    imageVector = icon.imageVector,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(if (size >= 64.dp) 30.dp else 22.dp),
                    tint = if (filled) YingLiTheme.player.canvas else tint,
                )
            }
        }
    }
}

/**
 * 带文字的胶囊按钮（AB 胶囊的 A / B / 清除）。
 *
 * **与 [PlayerChromeIconButton] 的分工**（改之前先读这段）：
 *  - [PlayerChromeIconButton] 是**定尺寸圆钮**：宽度永远等于 [PlayerChromeButtonSize]，
 *    内容只能是图标或"短到能塞进圆里"的值（倍速 `1.5x`）。图标按钮的宽度不携带信息。
 *  - 本组件是**弹性宽度**的文字按钮：高度仍然钉在 [PlayerChromeButtonSize]（48dp 是**最小触控高度**，
 *    不是固定宽度 —— 需求明确要求文字按钮宽度随内容/字号变化），宽度由文字决定。
 *    当可用宽度不够时它**缩字号**而不是把文字省略掉：`A 00:12` 被截成 `A 00…`
 *    就失去了"这是哪一个时间点"的信息，而字号小一点仍然完整可读。
 *
 * 视觉材质（底色 alpha、描边、胶囊圆角）与图标按钮、截图胶囊**同源**，全部来自上面那组常量，
 * 不在这里写第二份 alpha。
 *
 * 可用宽度由调用方通过 `Modifier.weight(...)` / 约束给出；这里只负责"在给出的宽度里放下文字"。
 */
@Composable
internal fun PlayerChromeTextButton(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    tint: Color = YingLiTheme.player.controlPrimary,
    baseFontSize: TextUnit = androidx.compose.material3.MaterialTheme.typography.labelLarge.fontSize,
    /** 左右内边距：常规档 / 紧凑档由调用方（胶囊的排版决策）给出，见 [abCapsuleTextLayout]。 */
    horizontalPadding: Dp = PlayerChromeTextButtonHorizontalPadding,
) {
    val fontScale = LocalDensity.current.fontScale
    // 局部的可用宽度（而不是整屏宽度）：按钮在自己的约束里再兜一次底 ——
    // 调用方（胶囊）已经按整排文字算过一个统一字号并从 [baseFontSize] 传进来，
    // 这里只负责"连单独一个标签都放不下"的极端情况。
    BoxWithConstraints(modifier = modifier.height(PlayerChromeButtonSize)) {
        val textWidth = maxWidth - horizontalPadding * 2
        Surface(
            onClick = onClick,
            // **宽度不写死**：由文字 + 内边距决定（见上面的分工说明）。
            modifier = Modifier.fillMaxHeight(),
            enabled = enabled,
            shape = PlayerChromeCapsuleShape,
            color = if (filled) {
                YingLiTheme.player.controlPrimary
            } else {
                YingLiTheme.player.controlPrimary.copy(alpha = PlayerChromeControlFillAlpha)
            },
            contentColor = if (filled) YingLiTheme.player.canvas else tint,
            border = if (filled) {
                null
            } else {
                BorderStroke(PlayerChromeControlBorderWidth, YingLiTheme.player.controlPrimary.copy(alpha = PlayerChromeControlBorderAlpha))
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = label,
                    color = if (filled) YingLiTheme.player.canvas else tint,
                    fontSize = playerChromeTextFontSizeSp(
                        availableWidth = textWidth,
                        labels = listOf(label),
                        baseFontSize = baseFontSize,
                        fontScale = fontScale,
                    ),
                    maxLines = 1,
                    // 不换行、不省略：宽度由上面的字号适配保证，字号到底仍放不下时宁可整体裁掉一点，
                    // 也不产出 `A 00…` 这种"看起来是别的意思"的省略号文案。
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier
                        .padding(horizontal = horizontalPadding)
                        .semantics { this.contentDescription = contentDescription },
                )
            }
        }
    }
}

/**
 * 文字按钮左右内边距（常规档）：它同时也是算"文字可用宽度"时要扣掉的那部分（唯一一份）。
 */
internal val PlayerChromeTextButtonHorizontalPadding = 12.dp

/**
 * 文字按钮左右内边距（**紧凑档**）：只在"常规档 + 可读下限"仍然放不下三段文字时启用
 * （极窄屏 + 最大系统字号），见 [abCapsuleTextLayout]。
 *
 * 为什么是"收窄内边距"而不是继续缩字号：字号有可读下限，再往下缩就是拿可读性换排版；
 * 而大字号下文字本身就占了按钮的绝大部分，4dp 的横向内边距在视觉上仍然是"文字外面有一圈边"。
 */
internal val PlayerChromeTextButtonCompactHorizontalPadding = 4.dp

/**
 * 文字按钮/文字胶囊的字号下限：正常机型用不到，只在极窄屏 / 最大系统字号下兜底。
 * 与帧数胶囊的兜底同一档（10sp），全项目只有这一个"仍算可读"的下限。
 */
internal val PlayerChromeTextMinFontSize = 10.sp

/**
 * 窄字符（拉丁字母、数字、空格、`×`、`:`）的宽度占字号的比例。
 * Roboto 数字/字母的实测 advance ≈ 0.555em（真机测量：12.78sp 下 `A 00:12` 共 49.67dp），取 0.55。
 */
private const val PlayerChromeTextNarrowAdvanceEm = 0.55f

/**
 * 宽字符（CJK、全角标点）的宽度占字号的比例。
 *
 * **1.1 而不是 1.0**：中文字形在真机上实测约 1.095em（2 倍系统字号下 `清除` 占 43.8dp / 20sp），
 * 按 1.0 估算会少算将近 10% —— 那 10% 正是"关不掉的溢出"：模型说放得下，实际把关闭圆钮挤成 0 宽。
 */
private const val PlayerChromeTextWideAdvanceEm = 1.1f

/**
 * 估算宽度的**安全余量**：真机取整与字形微差可能让实际宽度比模型多一两个 dp，
 * 而"算出来刚好等于可用宽度"正是最容易被裁的位置。留 3%。
 */
private const val PlayerChromeTextWidthSafetyFactor = 1.03f

/** 一个字符的估算宽度（em）。CJK 统一表意文字起点 U+2E80 起按全角算。 */
private fun playerChromeTextAdvanceEm(character: Char): Float =
    if (character.code >= 0x2E80) PlayerChromeTextWideAdvanceEm else PlayerChromeTextNarrowAdvanceEm

/** [labels] 在**1sp**字号下的估算总宽度（em 之和）。 */
private fun playerChromeTextAdvanceSum(labels: List<String>): Float =
    labels.sumOf { label -> label.fold(0f) { acc, character -> acc + playerChromeTextAdvanceEm(character) }.toDouble() }.toFloat()

/** [labels] 在 **1sp / fontScale=1** 下的估算宽度（dp，含 [PlayerChromeTextWidthSafetyFactor]）。 */
private fun playerChromeTextUnitWidthDp(labels: List<String>, fontScale: Float): Float =
    playerChromeTextAdvanceSum(labels) * fontScale.coerceAtLeast(0.01f) * PlayerChromeTextWidthSafetyFactor

/**
 * [labels] 在 [fontSize]（sp）与 [fontScale] 下的**估算总宽度**（dp）。
 *
 * `fontSize.value` 是 sp 数值，落到屏幕上还要乘系统字号缩放 [fontScale] —— 这一项漏掉的话，
 * "系统字号放大"这一档就会算出一个放不下的字号（实测：2 倍字号下三枚按钮把关闭键挤成 0 宽）。
 *
 * 它和 [playerChromeTextFontSizeSp] 是同一个模型的两种用法（共用 [playerChromeTextUnitWidthDp]）：
 * 前者算"这么宽放得下多大字号"，它算"这个字号要占多宽"。因此"算出来的字号一定放得下"这件事
 * 可以在测试里直接断言（`estimated(labels, fitted) <= available`），而不是靠肉眼看截图。
 */
internal fun playerChromeTextEstimatedWidthDp(
    labels: List<String>,
    fontSize: TextUnit,
    fontScale: Float = 1f,
): Dp = (playerChromeTextUnitWidthDp(labels, fontScale) * fontSize.value).dp

/**
 * 让 [labels] **全部完整放下**的统一字号（sp）：上限 [baseFontSize]（正常机型就是它），
 * 下限 [PlayerChromeTextMinFontSize]。
 *
 * 依据：每个字符约占 `k × 字号 × 系统字号缩放`（[playerChromeTextAdvanceEm]，宽/窄两档），
 * 因此 `字号 ≤ 可用宽度 / (Σk × fontScale)`。取整个标签集合一起算，是为了让同一排文字
 * **只有一个字号** —— 每个按钮各算各的会出现"A 12sp、清除 10sp"这种高低不齐。
 *
 * 纯函数（不读主题/密度）以便直接做单元测试：主题字号与系统字号缩放都由调用方传进来。
 */
internal fun playerChromeTextFontSizeSp(
    availableWidth: Dp,
    labels: List<String>,
    baseFontSize: TextUnit,
    fontScale: Float = 1f,
): TextUnit {
    val unitWidth = playerChromeTextUnitWidthDp(labels, fontScale)
    if (unitWidth <= 0f || availableWidth <= 0.dp) return baseFontSize
    return (availableWidth.value / unitWidth)
        .coerceIn(PlayerChromeTextMinFontSize.value, baseFontSize.value)
        .sp
}

/**
 * 与底栏按钮同源的胶囊容器：截图胶囊与 AB 胶囊**共用这一份材质**
 * （[PlayerChromeControlFillAlpha] 底 + [PlayerChromeControlBorderWidth]/[PlayerChromeControlBorderAlpha]
 * 描边 + [PlayerChromeCapsuleShape] 圆角）。两枚胶囊会同屏出现在同一格里，各写一套 alpha 必然出现色差。
 *
 * [verticalAlignment] 只管**竖直**位置，默认 `Top`（与 `Box` 默认一致；帧数胶囊按文本自身的内边距摆放，
 * 不需要居中）。**装着一排按钮的胶囊必须传 [Alignment.CenterVertically]**：胶囊比里面的按钮高一圈
 *（[PlayerScreenshotCapsuleHeight] vs [PlayerChromeButtonSize]，四周是 [ScreenshotCapsuleInnerPadding]
 * 的呼吸圈），行内那点 `verticalAlignment` 只管"按钮彼此对齐"，管不到"这一行摆在胶囊的什么位置"。
 * 不传就会出现"按钮贴在胶囊顶边、下面空一圈"——真机实测反馈的"按钮没有垂直居中"就是它。
 *
 * 只暴露竖直对齐（而不是完整的 [Alignment]）：水平方向必须继续由内容自己决定，
 * 否则在胶囊里写死水平居中会把带内边距的排版决策一起改掉。
 */
@Composable
internal fun PlayerChromeCapsuleSurface(
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
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
    ) {
        // 水平方向一律 `Start`：内容的左右位置由它自己的内边距决定（见 [abCapsuleTextLayout]），
        // 这里只接管竖直方向。
        Box(contentAlignment = verticalAlignment + Alignment.Start, content = { content() })
    }
}

@Composable
internal fun PlayerUnlockButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    // 图标跟状态（锁定态显示闭合锁），文案跟动作（点击即解锁）。
    PlayerChromeIconButton(YingLiIcon.LOCK, stringResource(R.string.player_unlock), onClick, modifier)
}

/**
 * 锁定态保留的控件：**播放/暂停 + 解锁**（需求文档 FR-PLAYER-003 / 决策 #394）。
 * 二者都只在"单击唤出"的窗口内显示、随后随控件一起自动隐藏，因此锁定仍然是防误触的：
 * 想暂停需要先唤出、再点击，不会因为误碰一下就改变播放状态。
 */
@Composable
internal fun PlayerLockedControls(
    isPlaying: Boolean,
    onTogglePlayback: () -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(PlayerShortcutSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerChromeIconButton(
            icon = if (isPlaying) YingLiIcon.PAUSE else YingLiIcon.PLAY,
            contentDescription = if (isPlaying) "暂停" else "播放",
            onClick = onTogglePlayback,
        )
        PlayerUnlockButton(onClick = onUnlock)
    }
}

/**
 * 顶栏溢出菜单的一项。
 *
 * 名字必须以大写开头：它是返回 Unit 的 @Composable（Compose 的 `ComposableNaming` 规则），
 * 与普通小写函数区分开，读代码时一眼能看出"这里会进组合"。
 */
@Composable
private fun MenuItem(
    label: String,
    action: () -> Unit,
    dismiss: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = {
            dismiss()
            action()
        },
    )
}

/**
 * 截图工具是否正在**占用**播放页（Armed / Capturing / Preview 三段）。
 *
 * 这三段的界面差别只是胶囊内容，对「底栏三段要不要收起让位、帧数胶囊要不要在场」而言是同一件事，
 * 所以判定集中在这里，避免各调用点各写一遍状态枚举、日后新增状态时漏改一处。
 *
 * **AB 工具打开时截图工具让位**（docs/20 §3.2 的互斥，`Preview` 分支）：AB 与截图是二选一的
 * 浮层工具，AB 胶囊要占的正是截图工具占着的那条辅助带。让位只影响"谁占着播放页 chrome"，
 * **不动截图状态本身** —— `Preview` 的预览卡与倒计时因此照常在（那是用户已经拿到的结果），
 * 只是截图工具模式（底栏让位 + 帧数胶囊）退出；关闭 AB 后它自然回来。`Armed` / `Capturing`
 * 不走这条路（打开 AB 时那个会话已经被整个结束），所以这里不必区分。
 */
internal fun PlayerUiState.isScreenshotToolActive(): Boolean = !abToolOpen && when (screenshot) {
    ScreenshotUiState.Armed, ScreenshotUiState.Capturing -> true
    is ScreenshotUiState.Preview -> true
    else -> false
}

/**
 * AB 循环是否**生效中** —— 托盘按钮（实心选中态）、顶栏快捷槽、设置面板「工具」chip
 * 以及进度行的区间/计数**共用这一个判定**。
 *
 * 唯一依据是会话侧的 [AbLoopSession.active]：只要 A、B 都设好了就算生效，
 * **与 AB 胶囊是否打开无关**（D3：关闭胶囊 ≠ 取消循环）。视图层不许再写第二套判定
 * （例如"设过 A 就算选中"），否则同一个循环在三个入口上会显示成三种状态。
 */
internal val PlayerUiState.abLoopActive: Boolean
    get() = abLoop.active

private fun PlayerUiState.playerSubtitle(): String? {
    val info = mediaInfo ?: return null
    return listOfNotNull(
        if (info.width != null && info.height != null) "${info.width} × ${info.height}" else null,
        info.videoCodec?.uppercase(),
        // 帧率的显示口径集中在 frameRateLabel（纯函数，有单测）：这里**不能**用 toInt() 截断，
        // 否则 Media3 报出的 29.999x 会显示成 "29 fps"（见该函数注释）。
        frameRateLabel(info.frameRate),
    ).takeIf(List<String>::isNotEmpty)?.joinToString(" · ")
}
