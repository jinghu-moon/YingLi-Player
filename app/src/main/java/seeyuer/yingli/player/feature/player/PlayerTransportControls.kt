package seeyuer.yingli.player.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.component.YingLiIconButton
import seeyuer.yingli.player.core.designsystem.component.YingLiSlider
import seeyuer.yingli.player.core.designsystem.component.YingLiSliderDefaults
import seeyuer.yingli.player.core.designsystem.component.YingLiSliderThumbRadius
import seeyuer.yingli.player.core.designsystem.component.YingLiSliderTrackHeight
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.icon.imageVector
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.playback.PlaybackState
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.PlayerControlId
import seeyuer.yingli.player.domain.playback.PlayerControlSurface
import seeyuer.yingli.player.domain.playback.PlaybackOrder
import seeyuer.yingli.player.domain.playback.ScreenshotUiState
import seeyuer.yingli.player.domain.playback.VideoScaleMode

/** 同一组内相邻快捷按钮的间距。 */
internal val PlayerShortcutSpacing = 8.dp

/** 横屏左右两组快捷槽之间的额外间距。 */
private val PlayerLandscapeGroupSpacing = 12.dp

/** 竖屏底栏内边距：两侧收窄，把宽度让给进度条。 */
internal val PlayerPortraitBarHorizontalPadding = 12.dp
internal val PlayerPortraitBarVerticalPadding = 10.dp

/** 横屏底栏内边距。 */
internal val PlayerLandscapeBarHorizontalPadding = 16.dp
internal val PlayerLandscapeBarVerticalPadding = 12.dp

/**
 * 竖屏进度行与按钮行之间的间距。
 *
 * 它同时是「辅助带 = 工具格高度 + 这一个间距」里的那个间距，以及截图胶囊比托盘按钮高出的一档，
 * 所以底栏的竖直几何、胶囊尺寸、托盘行的顶部内边距都引用这**同一个**常量。
 */
internal val PlayerPortraitControlsSpacing = 16.dp

/**
 * 底栏「辅助带」的满高：**工具托盘行与截图胶囊叠放在同一格**里共用它。
 *
 * 尺寸依据：这一格要同时住得下 [PlayerScreenshotCapsuleHeight] 的胶囊与 [PlayerChromeButtonSize]
 * 的托盘按钮，且两者与下方按钮行之间都要留出 [PlayerPortraitControlsSpacing] 的间距，
 * 所以满高取"胶囊高度 + 该间距"（64dp + 16dp = 80dp）。托盘行比胶囊矮一档，
 * 靠 [PlayerToolRowTopInset] 把顶部补齐——托盘按钮的**绝对位置与旧实现完全一致**
 * （仍然离按钮行 16dp），多出来的 16dp 落在带子上方（空白）。
 *
 * 为什么托盘态与截图态共用一个满高（而不是各算各的）：辅助带位于进度行下方，
 * 底栏又是底对齐的 Column，带子高度一变，进度行就整体位移。两者共用同一个满高之后，
 * 「托盘 → 截图会话」「截图会话 → 托盘」这两次切换里进度行一帧都不动，
 * 也就不存在"退出截图后整段跳一下"这类瞬时位移。
 *
 * 三条硬约束（改这一段之前请先读完）：
 *   1. 只要胶囊在场——**包含它滑出屏幕的那 [SCREENSHOT_CAPSULE_TRANSITION_MILLIS]ms**——
 *      辅助带就必须是满高，而且必须**瞬时**到位（见 [playerAuxiliaryBandHeight] 与
 *      [auxiliaryBandTransition]）；带子若晚一帧或跟着动画走，胶囊就会在滑入/滑出时被带着上下跳。
 *   2. 普通模式下的托盘开关（**没有胶囊在场**时）**必须走高度动画**：进度行的"上移/回位"就是
 *      这个高度在变，不许退回 `if (toolsExpanded) 满高 else 0.dp` 那种瞬时切换——那正是用户实测到的
 *      "进度条突然上移、突然回到原位"。胶囊滑出之后的回位也走同一条动画（此时三段已经可见，
 *      瞬时塌掉同样会看到进度行瞬移）。
 *   3. 托盘行/胶囊都必须按 [PlayerAuxiliaryBandHeight] 固定自身高度再参与裁剪，
 *      不能让它们被动画中途的带子高度挤小（见托盘行的 `requiredHeight`）。
 *
 * 注意这里是 `get()` 而不是普通顶层 `val`：它由**另一个文件**里的 [PlayerScreenshotCapsuleHeight]
 * 推出，而那个常量又要读本文件的 [PlayerPortraitControlsSpacing]——两个文件的顶层 `val` 会构成
 * 初始化环，谁先被加载谁就拿到对方的默认值（实测会算出 16dp 这种"说不出理由"的带子高度）。
 * 写成计算属性后与取值顺序无关，环随即消失。**不要再把它改回顶层 val。**
 */
internal val PlayerAuxiliaryBandHeight: Dp
    get() = PlayerScreenshotCapsuleHeight + PlayerPortraitControlsSpacing

/**
 * 托盘行在辅助带里的顶部内边距 = 胶囊比托盘按钮高出的那一档。
 *
 * 它的唯一作用：把"托盘按钮在同一格里的最终位置"钉在辅助带只有托盘行高度时的位置
 * （即离下方按钮行 [PlayerPortraitControlsSpacing]）。它和胶囊高度必须同源，
 * 否则托盘展开后按钮会整体上下漂。同样是 `get()`：理由见 [PlayerAuxiliaryBandHeight]。
 */
private val PlayerToolRowTopInset: Dp
    get() = PlayerScreenshotCapsuleHeight - PlayerChromeButtonSize

/**
 * 辅助带的**目标高度**：工具胶囊在场（[bandHold]）或托盘展开时取满高，否则为 0。
 *
 * 注意它只回答"终值是多少"，**不回答"怎么过去"**：方式由 [auxiliaryBandTransition] 给出。
 * 把两者分开是这一条几何的核心 —— 曾经它们混在一条路径里（动画值 + 硬覆盖），
 * 于是"胶囊出现"和"托盘开关"这两种成因互相污染，先后出过"胶囊竖直跳变"与"进度行瞬移"。
 */
internal fun playerAuxiliaryBandHeight(bandHold: Boolean, trayExpanded: Boolean): Dp =
    if (bandHold || trayExpanded) PlayerAuxiliaryBandHeight else 0.dp

/** 辅助带高度的变化方式。 */
internal enum class AuxiliaryBandTransition {
    /**
     * **瞬时**到位：带子上有工具胶囊（或它正在滑出）。这一帧带子就必须是终值，
     * 否则胶囊会跟着带子的 0→满高 动画一起从下往上滑（上一轮修掉的"胶囊竖直跳变"，commit 01ebdfa）。
     */
    SNAP,

    /**
     * 走 [TRANSPORT_SECTION_TRANSITION_MILLIS] 的高度动画：托盘开关，以及胶囊滑出后的回位。
     * 这三段里底栏内容都是可见的，瞬时变高变矮就是用户实测到的"进度条突然上移/突然回到原位"。
     */
    ANIMATE,
}

/**
 * 辅助带高度该怎么变（判据与 [playerAuxiliaryBandHeight] 配对使用，两者都是纯函数、有单测）。
 *
 * **只有"胶囊在场"这一种成因允许瞬时**：它是唯一一种"带子上挂着会跟着带子动的东西"的情况。
 */
internal fun auxiliaryBandTransition(bandHold: Boolean): AuxiliaryBandTransition =
    if (bandHold) AuxiliaryBandTransition.SNAP else AuxiliaryBandTransition.ANIMATE

/** 时间文本最小宽度，保证播放中进度条长度不随时长位数跳动。 */
private val PlayerTimeLabelMinWidth = 42.dp

/**
 * 拖动进度条时实时 seek 的**最小间隔**。
 *
 * 真正的限流来自"就绪即投放"（上一次 seek 引起的重缓冲结束前不投放新目标，只保留最新一个），
 * 这个下限只用于 seek 秒回（缓冲区已覆盖目标、不进入重缓冲）时避免逐帧派发。
 */
private const val LIVE_SEEK_MIN_INTERVAL_MILLIS = 60L

/**
 * 底栏三段（进度行 / 工具托盘 / 按钮行）进出截图模式时的收起-展开时长，也是托盘开关时
 * 辅助带高度动画的时长（设计稿 §6 的 0.24s）。三段淡入淡出与带子高度必须同取这一档，
 * 否则进度行的上移会晚于托盘内容出现，看起来还是"先跳一下再淡出"。
 *
 * 它**不是**截图胶囊的出入场时长：胶囊取更慢的 [SCREENSHOT_CAPSULE_TRANSITION_MILLIS]。
 */
internal const val TRANSPORT_SECTION_TRANSITION_MILLIS = 240

/**
 * 画面中央的三连控件：**上一个 / 播放暂停 / 下一个**（与 REX-Player 同构：
 * `PlayerControls.kt:1074-1195` 的中间区就是这三连，且只在存在播放队列时可用）。
 *
 * 这里**不再**放"快退/快进 N 秒"：那一对与"上一个/下一个"共用 PlayerSkipBack/Forward 字形，
 * 放在中央会被误认为切集按钮（用户实测反馈）。跳秒改由双击画面左右两侧承担（步长可配置），
 * 横向拖动画面仍是连续 seek。
 */
@Composable
internal fun CenterPlaybackControls(
    playing: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    canNavigatePrevious: Boolean,
    canNavigateNext: Boolean,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(38.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerChromeIconButton(
            icon = YingLiIcon.PREVIOUS,
            contentDescription = stringResource(R.string.player_previous),
            onClick = onPrevious,
            enabled = canNavigatePrevious,
            size = 56.dp,
        )
        PlayerChromeIconButton(
            icon = if (playing) YingLiIcon.PAUSE else YingLiIcon.PLAY,
            contentDescription = stringResource(if (playing) R.string.player_pause else R.string.player_play),
            onClick = if (playing) onPause else onPlay,
            size = 74.dp,
            filled = true,
        )
        PlayerChromeIconButton(
            icon = YingLiIcon.NEXT,
            contentDescription = stringResource(R.string.player_next),
            onClick = onNext,
            enabled = canNavigateNext,
            size = 56.dp,
        )
    }
}

@Composable
fun MiniPlayerBar(
    state: PlayerUiState,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playing = state.playback is PlaybackState.Playing
    Surface(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth().height(56.dp),
        color = YingLiTheme.colors.surfaceComponent,
        contentColor = YingLiTheme.colors.textPrimary,
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(state.title, modifier = Modifier.weight(1f), maxLines = 1)
            YingLiIconButton(
                icon = if (playing) YingLiIcon.PAUSE else YingLiIcon.PLAY,
                contentDescription = stringResource(if (playing) R.string.player_pause else R.string.player_play),
                onClick = if (playing) onPause else onPlay,
            )
        }
    }
}

@Composable
internal fun BottomPlaybackControls(
    state: PlayerUiState,
    onSeek: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onOpenPlaylist: () -> Unit,
    onPictureInPicture: () -> Unit,
    allowPictureInPicture: Boolean,
    onRotateVideo: () -> Unit = {},
    onToggleSpeedPanel: () -> Unit = {},
    onCycleScaleMode: () -> Unit = {},
    onSetSpeed: (PlaybackSpeed) -> Unit = {},
    onSetPlaybackOrder: (PlaybackOrder) -> Unit = {},
    onScreenshot: () -> Unit = {},
    onOpenAbTool: () -> Unit = {},
    /** 关闭 AB 胶囊（只收 UI，**不**取消循环）：托盘要展开时 AB 胶囊让位用得到它。 */
    onCloseAbTool: () -> Unit = {},
    onToggleLock: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onOpenVideoInfo: () -> Unit = {},
    onSelectAudioTrack: () -> Unit = {},
    onSelectSubtitleTrack: () -> Unit = {},
    /** 当前会话的镜像翻转状态：按钮用它显示选中态。 */
    mirror: seeyuer.yingli.player.domain.playback.VideoMirror = seeyuer.yingli.player.domain.playback.VideoMirror(),
    onToggleMirrorHorizontal: () -> Unit = {},
    onToggleMirrorVertical: () -> Unit = {},
    /** 后台播放开关的当前值：托盘按钮用它显示选中态（开 = 实心）。 */
    backgroundPlaybackEnabled: Boolean = true,
    onToggleBackgroundPlayback: () -> Unit = {},
    controlLayout: seeyuer.yingli.player.domain.playback.PlayerControlLayout = seeyuer.yingli.player.domain.playback.PlayerControlLayout(),
    /**
     * 截图模式下的截图胶囊插槽：胶囊**占用工具托盘行本身的位置**，
     * 因此托盘行让位时它就在同一行里从右向左滑入（起点即托盘最右端的截图按钮）。
     * 默认空实现：不传插槽时这一行什么都不渲染，托盘行照常收起。
     */
    screenshotTool: @Composable () -> Unit = {},
    /**
     * AB 工具胶囊插槽：与 [screenshotTool] **同一个槽位**（辅助带那一格），
     * 由 `auxiliaryToolCapsule` 决定这一帧画哪一枚。几何/材质/出入场全部同源，
     * 所以这里传进来的胶囊不要再自带位置修饰符。
     */
    abTool: @Composable () -> Unit = {},
    modifier: Modifier,
    compact: Boolean = false,
) {
    val duration = state.playback.timeline.durationMillis
    val abStart = state.abLoop.pointA
    val abEnd = state.abLoop.pointB
    // 循环次数**只从会话快照读**（`AbLoopSession.loopCount`，唯一写入者是会话 runtime）：
    // 视图层不累计、不推算，也不在关闭胶囊时清零（D3：关闭 ≠ 取消）。
    val abLoopCount = state.abLoop.loopCount
    var dragging by remember(state.playback.request?.mediaId) { mutableStateOf(false) }
    var previewPositionMillis by remember(state.playback.request?.mediaId) {
        // Long：用 mutableLongStateOf 避免每次拖动预览都装箱（lint 的 AutoboxingStateCreation）。
        mutableLongStateOf(state.displayedPositionMillis)
    }
    // 拖动中的"待投放目标"：**只保留最后一次**，等播放器从上一次 seek 的重缓冲里恢复后再投放。
    // REX 的做法是取消在途 seek（PlaybackManager.seekJob.cancel()，本地文件不排队）只保留最新；
    // Media3 的 seekTo 在 seek 进行中会排队，所以固定时间节流会让快速拖动越拖越滞后。
    var pendingSeekMillis by remember(state.playback.request?.mediaId) { mutableStateOf<Long?>(null) }
    var lastLiveSeekAt by remember(state.playback.request?.mediaId) { mutableLongStateOf(0L) }
    val seekBusy = (state.playback as? seeyuer.yingli.player.domain.playback.PlaybackState.Preparing)
        ?.isRebuffering == true
    LaunchedEffect(seekBusy, pendingSeekMillis) {
        val target = pendingSeekMillis ?: return@LaunchedEffect
        if (seekBusy) return@LaunchedEffect
        // 下限间隔：seek 秒回（缓冲区已覆盖目标、不进入重缓冲）时避免逐帧派发。
        val wait = LIVE_SEEK_MIN_INTERVAL_MILLIS -
            (android.os.SystemClock.uptimeMillis() - lastLiveSeekAt)
        if (wait > 0) kotlinx.coroutines.delay(wait)
        lastLiveSeekAt = android.os.SystemClock.uptimeMillis()
        pendingSeekMillis = null
        onSeek(target)
    }
    LaunchedEffect(state.displayedPositionMillis, dragging) {
        if (!dragging) previewPositionMillis = state.displayedPositionMillis
    }
    val displayedPositionMillis = if (dragging) previewPositionMillis else state.displayedPositionMillis
    Column(
        modifier = modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(
                horizontal = if (compact) PlayerPortraitBarHorizontalPadding else PlayerLandscapeBarHorizontalPadding,
                vertical = if (compact) PlayerPortraitBarVerticalPadding else PlayerLandscapeBarVerticalPadding,
            ),
    ) {
        // "更多"托盘展开状态：纯 UI 状态，不进 ViewModel；声明在托盘与按钮行共同的父作用域里。
        var toolsExpanded by remember { mutableStateOf(false) }
        // 托盘与工具胶囊是**同一格**的两个占用者，所以"点更多"就是一次让位：
        // 展开托盘时先收起 AB 胶囊（关闭 ≠ 取消循环，D3：区间与计数都留着），否则
        // 胶囊在场时托盘行会被压住不显示，用户会觉得这个按钮点不动。
        val toggleTools = {
            val expand = !toolsExpanded
            if (expand) onCloseAbTool()
            toolsExpanded = expand
        }
        // ── 底栏竖直几何：改这一段之前请先读完 ────────────────────────────────────────
        // 底栏是**底对齐**的 Column：某一段的竖直位置只由"它下方所有兄弟槽位的高度"决定，
        // 与它自己上方有几段、Column 总高多少都无关。胶囊住在辅助带里、辅助带下方只有按钮行，
        // 于是有一条唯一等式：
        //     胶囊的竖直带 = 按钮行的槽位高度 + 底栏内边距
        // 由此得到三条硬约束，破坏任何一条都会让胶囊在动画中途上下跳：
        //   1. 按钮行槽位恒为 PlayerChromeButtonSize：截图模式下按钮行只淡出内容，
        //      槽位高度一帧都不变（不能再让 AnimatedVisibility 自己决定占多少空间）；
        //   2. 只要胶囊在场——**包含它滑出屏幕的那 240ms**——辅助带槽位就必须是满高；
        //   3. 进入截图会话的那一帧，辅助带与按钮行就已经是终值：可见性判据都取同一帧的
        //      state，只有"退出后继续留位"才用延时（见下面的 capsuleBandHeld）。
        // **胶囊的竖直带必须自始至终固定，不得随底栏高度变化而移动。**
        //
        // 截图会话：底栏三段让位给截图胶囊。
        // 这里只做「收起」，不接管可见性判定——控件的自动隐藏仍然由 overlay.controlsVisible 决定
        // （PlayerOverlayReducer 那套 Interaction/Timeout 原样生效），二者是"与"的关系：
        // 截图模式下无论自动隐藏是否触发，这三段都不出现；退出截图模式后按自动隐藏的当前状态回来。
        //
        // 两套判据的口径（必须保持包含关系）：isScreenshotToolActive() = Armed / Capturing / Preview，
        // 三段收起与帧数胶囊用它；isCapsuleVisible() = Armed / Capturing，只有胶囊本身用它。
        // 即「胶囊可见 ⇒ 截图会话中」。给辅助带留位要用**截图会话**（较大的那个），
        // 这样胶囊的整个生命期都被覆盖；反过来用胶囊判据留位的话，Capturing → Preview 的那一帧
        // 带子会跟着塌掉，胶囊的滑出就没有落脚点了。
        val screenshotSession = state.isScreenshotToolActive()
        // 辅助带那一格的占用者：截图胶囊与 AB 胶囊**共用一个槽位**（§3.3 的"同一格"），
        // 因此必须由**一个**判定决定画哪一枚，而不是两处各画一个、靠状态互斥去保证不重叠。
        val toolCapsule = auxiliaryToolCapsule(abToolOpen = state.abToolOpen, screenshot = state.screenshot)
        val toolCapsuleVisible = toolCapsule != AuxiliaryToolCapsule.NONE
        // AB 胶囊与截图胶囊的区别：它**住在底栏里**（三段必须同时在场，区间高亮与"循环 ×N"都在进度行上），
        // 而截图胶囊是"接管播放页"的工具（三段让位）。下面是这个差别的唯一落点。
        val abCapsuleVisible = toolCapsule == AuxiliaryToolCapsule.AB_LOOP
        // 辅助带的"退出留位窗口"：胶囊滑出需要 SCREENSHOT_CAPSULE_TRANSITION_MILLIS，
        // 这段里带子必须继续满高，否则内容会被挤成 0 高（滑出动画等于被吃掉）。
        // 进入不看这个标志（约束 1），所以它只负责"晚一点撤"，不参与"什么时候出现"。
        // 延长到胶囊自己的时长：胶囊滑出比底栏三段的 240ms 慢，留位窗口必须跟着它走，
        // 否则带子会在胶囊还在滑的时候提前塌掉。
        //
        // 初值取"这一帧是否已经有胶囊"，与下面 Animatable 的初值同源：底栏会因为控件自动隐藏
        // 整体离开组合再回来（用户单击画面收起、再单击唤出），回来时若从 0 高开始演，
        // 胶囊会先在 0 高的带子里被裁掉一帧 —— 那正是"胶囊竖直跳变"的一帧版本。
        val bandHoldAtFirstFrame = screenshotSession || toolCapsuleVisible
        var capsuleBandHeld by remember { mutableStateOf(toolCapsuleVisible) }
        LaunchedEffect(toolCapsuleVisible) {
            if (toolCapsuleVisible) {
                capsuleBandHeld = true
            } else if (capsuleBandHeld) {
                delay(SCREENSHOT_CAPSULE_TRANSITION_MILLIS.toLong())
                capsuleBandHeld = false
            }
        }
        // 三段（进度行 / 工具托盘行 / 按钮行）共用一个可见性判据：截图会话结束就回来。
        // 唯一的例外不是"判据不同"，而是"回来的时机"：辅助带只是为**截图**胶囊留位、且托盘没开时，
        // 带子稍后要塌回 0，三段必须等它塌完再淡入——否则它们会在带子塌掉的那一帧整体下移一个带高。
        // **AB 胶囊不适用这条等待**：它不接管播放页，三段本来就该在场并一直留在组合里。
        val sectionsVisible = !screenshotSession && !(capsuleBandHeld && !toolsExpanded && !abCapsuleVisible)
        // 辅助带是否被"胶囊在场（含滑出留位窗口）"或"截图会话"钉在满高：这一条走**瞬时**路径。
        val bandHold = screenshotSession || capsuleBandHeld
        // 带子高度**只有一个驱动**：终端值来自 playerAuxiliaryBandHeight，变化方式来自
        // auxiliaryBandTransition（胶囊在场 → 瞬时；托盘开关 / 胶囊滑出后回位 → 240ms 动画）。
        // 旧实现是"托盘动画值 + 截图会话硬覆盖"两条路叠加，胶囊滑出后的回位只能靠硬塌，
        // 而 AB 胶囊打开时三段是可见的——那样就会看到进度行瞬移。合成一个 Animatable 之后，
        // 两种成因各自拿到自己的变化方式，谁也不会污染谁。
        //
        // 初值 = 首次组合那一刻的终值：胶囊本来就在场时（底栏被自动隐藏收起又唤出）第一帧
        // 就必须是满高，不能等下面那个 effect 跑起来再补。
        val auxiliaryBand = remember {
            Animatable(if (bandHoldAtFirstFrame) PlayerAuxiliaryBandHeight.value else 0f)
        }
        LaunchedEffect(bandHold, toolsExpanded) {
            val target = playerAuxiliaryBandHeight(bandHold, toolsExpanded).value
            when (auxiliaryBandTransition(bandHold)) {
                AuxiliaryBandTransition.SNAP -> auxiliaryBand.snapTo(target)
                AuxiliaryBandTransition.ANIMATE ->
                    auxiliaryBand.animateTo(target, tween(TRANSPORT_SECTION_TRANSITION_MILLIS))
            }
        }
        val auxiliaryBandHeight = auxiliaryBand.value.dp
        // 进度区是固定槽位（高度 = 进度行高度 + AB 读数行的预留）：截图模式下只淡出内容，
        // 槽位高度不变。**这里若用 shrink/expand，底对齐的整个 Column 会在截图模式切换时
        // 重新定位**，胶囊虽然住在辅助带里不受这一段影响，但三段一起变高变矮仍然会让底栏
        // 在动画中途整体抽动，所以三段一律固定槽位。
        //
        // 读数行的行高天条只有一份实现（[abReadoutBandHeight]）：AB 读数条与底栏的高度账都读它，
        // 因此任何字号下两处都取到同一个数。它描述的是"这一行要住多高"，与"有没有设点"无关 ——
        // 未设置态也要显示引导文案（**不留空白**），所以占位判据取"AB 工具是否打开"。
        val abReadoutBandHeight = abReadoutBandHeight(
            fontSize = MaterialTheme.typography.labelLarge.fontSize,
            fontScale = LocalDensity.current.fontScale,
        )
        val abReadoutReservedHeight = if (state.abToolOpen) abReadoutBandHeight else 0.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PlayerChromeButtonSize + abReadoutReservedHeight),
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = sectionsVisible,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(tween(TRANSPORT_SECTION_TRANSITION_MILLIS)),
                exit = fadeOut(tween(TRANSPORT_SECTION_TRANSITION_MILLIS)),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatDuration(displayedPositionMillis),
                    color = YingLiTheme.player.controlPrimary,
                    style = playerTimeTextStyle(),
                    maxLines = 1,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = PlayerTimeLabelMinWidth),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        // **固定 48dp 槽位**（= 滑杆那条触控带）：标记组住在自己的 64dp 画布里、
                        // 向上溢出这一行 6dp（徽标顶边），所以这一行的高度**不允许**由内容决定 ——
                        // 一旦被内容撑到 64dp，底栏整体变高、进度行跟着位移（历史回归过的
                        // "进度行瞬移"）。溢出由画布的 requiredHeight 提供，不由槽位提供。
                        .height(PlayerChromeButtonSize),
                ) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val density = LocalDensity.current
                        // 标记组的几何在**组合期**算一次，绘制层 / 压暗层 / 端点热区三处共用同一份 ——
                        // 各自算一次迟早会错位。两处像素输入的来源：
                        //   · 轨道宽 = 这一格的宽 − 两端各一个圆钮半径（与滑杆自己的绘制同一段几何，
                        //     见 YingLiControls 的 sliderTrackSpanPx）；
                        //   · 轨道中线 = 这一格的中心（滑杆的 48dp 触控带与这一格重合）。
                        val markerGeometry = if (abStart != null && duration != null && duration > 0) {
                            abRangeGeometry(
                                trackWidthPx = with(density) {
                                    (constraints.maxWidth - YingLiSliderThumbRadius.toPx() * 2f)
                                        .coerceAtLeast(0f)
                                },
                                fractionStart = abStart.toFloat() / duration,
                                fractionEnd = abEnd?.toFloat()?.div(duration),
                                markerDiameterPx = with(density) { AbMarkerDiameter.toPx() },
                                minMarkerGapPx = with(density) { AbMarkerMinGap.toPx() },
                                markerCenterYPx = constraints.maxHeight / 2f,
                                // 播放头比例只在"仅 A"时有意义（预判条的起止 + 幽灵竖线）。区间完整时
                                // 几何自己忽略它 —— 已经定下来的区间不该再跟着播放头变。
                                fractionHead = displayedPositionMillis.toFloat() / duration,
                                // 预判条的最小可辨识宽度 = 条自身厚度（见 abRangeBarMinWidthPx）。
                                barMinWidthPx = with(density) {
                                    abRangeBarMinWidthPx(AbRangeBarThickness.toPx())
                                },
                            )
                        } else {
                            null
                        }
                        // ③ 区间外压暗：**必须**住在滑杆的 `trackOverlay` 里（`DstOut` 只允许作用在
                        // 本行像素上，滑杆为此把这行隔离成离屏层），但它的左右边界与标记组**同一份**
                        // 几何 —— 所以几何在组合期算好后传进来，而不是在绘制里再算一次。
                        // ① 标记组（区间条 / 竖线 + 徽标 / 夸大虚线）：与滑杆同格，但画在滑杆**之前**。
                        //    为什么必须"之前"：滑块住在滑杆内部、是滑杆的最后一个笔迹，而标记组与轨道
                        //    在竖直方向**不重叠**（最低只到轨道上沿），所以把滑杆放在后面，
                        //    "滑块永不被压暗、永不被盖住"由**绘制顺序**保证，而不是靠碰巧不重叠。
                        //    它自带 AbMarkerLayerHeight（64dp）画布：标记组要从轨道中线往上占 30dp，
                        //    而滑杆那条 48dp 触控带只给到中线以上 24dp（溢出 6dp）——
                        //    画在滑杆里面任何一层（含 trackOverlay）都会被那 48dp 裁掉，
                        //    这就是"徽标被裁一半"的成因，也是这块兄弟画布存在的唯一理由。
                        if (markerGeometry != null) {
                            AbMarkerLayer(
                                geometry = markerGeometry,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .fillMaxWidth()
                                    .requiredHeight(AbMarkerLayerHeight),
                            )
                        }
                        YingLiSlider(
                            value = displayedPositionMillis.toFloat().coerceAtMost((duration ?: 1).toFloat()),
                            onValueChange = {
                                dragging = true
                                // **不把拖动钳进 [A,B]**：D8-A（阶段 0 裁决保留）允许循环期间拖到区间外，
                                // 旧实现在这里 `coerceIn(abStart, abEnd)`，属于被删掉的旧语义。
                                previewPositionMillis = it.toLong()
                                // 只记录最新目标；真正投放由上面的 gate 决定（就绪即投放），因此不会排队。
                                pendingSeekMillis = previewPositionMillis
                            },
                            onValueChangeFinished = {
                                // 松手必定投放最终位置（gate 会在就绪时执行一次）。
                                pendingSeekMillis = previewPositionMillis
                                dragging = false
                            },
                            modifier = Modifier.fillMaxWidth().testTag(PlayerTestTags.PROGRESS),
                            enabled = duration != null && duration > 0,
                            valueRange = 0f..(duration ?: 1).coerceAtLeast(1).toFloat(),
                            trackHeight = 4.dp,
                            thumbRadius = 7.dp,
                            colors = YingLiSliderDefaults.colors(
                                activeTrack = YingLiTheme.player.controlPrimary,
                                inactiveTrack = YingLiTheme.player.track,
                                thumb = YingLiTheme.player.controlPrimary,
                            ),
                            // 只设了 A 时也要画那一枚徽标（以及那条 28% 的预判条），所以条件是
                            // "有 A 且时长可用"，而不是"区间完整"。
                            trackOverlay = if (markerGeometry != null) {
                                { AbRangeDimOverlay(markerGeometry) }
                            } else {
                                null
                            },
                        )
                        // ⑤ 端点热区（点按徽标 = 跳到该端点）。它必须是这一格的**最后一个**子节点：
                        //    命中测试从最后一个子节点开始，而滑杆在 down 时就 seek —— 只有让热区先拿到
                        //    这次 down 并消费掉，滑杆才会放手（见 YingLiControls 的 `down.isConsumed`）。
                        if (markerGeometry != null) {
                            AbMarkerTapTargets(
                                geometry = markerGeometry,
                                pointAMillis = abStart,
                                pointBMillis = abEnd,
                                onSeekToPoint = onSeek,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .fillMaxWidth()
                                    .requiredHeight(PlayerChromeButtonSize),
                            )
                        }
                    }
                }
                Text(
                    text = formatDuration(duration),
                    color = YingLiTheme.player.controlPrimary,
                    style = playerTimeTextStyle(),
                    maxLines = 1,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.widthIn(min = PlayerTimeLabelMinWidth),
                )
                    }
                    // ── 区间读数条（本批：demo 的四态文案 + 可点数值）─────────────────────────
                    // 它是 AB 的**唯一**数值落点（胶囊里的按钮不带时间文字：文字会把圆钮撑成椭圆）。
                    // 四态与分段由纯函数 `abReadoutSegments` 决定（有单测）：两端时刻设了就显示，
                    // "Δ 时长 + 循环次数"只在区间完整时出现（只设 A 时循环还没开始，
                    // 显示 `循环 ×0` 会让人以为"已经在循环但一次没跑"）。
                    //
                    // **区间时长必须显式写出来**：短区间被进度条放大之后（见 AbLoopMath），
                    // 渲染长度不再代表真实长度，真实长度只能在文字上有一个落点。
                    //
                    // **位置口径的偏离（如实记录）**：demo 与 `docs/21` §3.1 把这一行放在
                    // **AB 胶囊首行**。本批**判定为放不下**，因此保留在**进度区第二行**：
                    // 胶囊的高度是 `PlayerScreenshotCapsuleHeight` = 64dp，而它与截图胶囊**同格同高**
                    // （「辅助带里两枚胶囊顶边与高度必须一致」是 instrumental 断言 + `docs/16` §5.11 的
                    // 既有约定）。胶囊里已经住着一排 48dp 圆钮，只剩 16dp；而这一行的行高天条是
                    // `max(AbValueTapTargetHeight=20dp, abReadoutBandHeight)` —— 1 倍字号下就要 20dp
                    // （48 + 20 = 68 > 64），2 倍系统字号下 abReadoutBandHeight = 32.2dp
                    // （48 + 32.2 = 80.2 > 64），**放不下**。除非把胶囊改得比截图胶囊高
                    // （破坏"同格同高"）或把触控尺寸/行高天条降到 16dp 以内（`docs/16` §5.11 已把
                    // 32 × 20dp 与那条天条写死为口径）—— 两者都不是本批能单方面改的。
                    // 搬家的前提因此是：先生效一份"AB 胶囊允许比截图胶囊高"的裁决，
                    // 并同步 `PlayerScreenshotCapsuleHeight` / `PlayerAuxiliaryBandHeight` 的高度账。
                    //
                    // 显示判据取"AB 工具打开"（而不是"设过点"）：未设置态要显示引导文案，
                    // **不留空白**（demo 的第 1 态）。
                    if (state.abToolOpen) {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            AbReadoutRow(
                                segments = abReadoutSegments(
                                    pointAMillis = abStart,
                                    pointBMillis = abEnd,
                                    // 文案在**Composable 上下文里**取好再传进去：纯函数不碰资源，
                                    // 计数的事实来源仍然是会话侧的 `AbLoopSession.loopCount`。
                                    loopCountLabel = stringResource(R.string.player_ab_loop_count, abLoopCount),
                                    noneLabel = stringResource(R.string.player_ab_readout_none),
                                    pendingLabel = stringResource(R.string.player_ab_readout_pending_b),
                                    deltaPrefix = stringResource(R.string.player_ab_delta),
                                ),
                                availableWidth = maxWidth,
                                lineBandHeight = abReadoutBandHeight,
                                // 点 A / B 数值 = 跳到该端点（与点轨道上那枚徽标同一个回调）。
                                // 它走**用户跳转**（`onSeek` → `SeekOrigin.USER`），
                                // **不得**用 `AB_LOOP` / `AB_ACTIVATION` 那两档。
                                onSeekToPoint = onSeek,
                            )
                        }
                    }
                }
            }
        }
        val controlGroups = if (compact) {
            listOf(controlLayout.controls(PlayerControlSurface.PORTRAIT_BOTTOM))
        } else {
            listOf(
                controlLayout.controls(PlayerControlSurface.LANDSCAPE_BOTTOM_LEFT),
                controlLayout.controls(PlayerControlSurface.LANDSCAPE_BOTTOM_RIGHT),
            )
        }
        if (compact) Spacer(Modifier.height(PlayerPortraitControlsSpacing))
        // 辅助带：工具托盘行与截图胶囊**叠放在同一个槽位**里（这就是"托盘行 + 胶囊
        // 放进一个固定高度 Box"），槽位高度只在这两种情况下切换：
        //   · 想要它（托盘展开 / 截图会话中）→ 满高；
        //   · 想要它 + 胶囊正在滑出 → 继续满高（capsuleBandHeld，约束 1）；
        //   · 其余 → 0（与不挂载这个 Box 等价，但节点留在组合里）。
        // 托盘展开/收起这条路径的高度**由动画给出**（trayBandHeight），因此进度行是滑上去、
        // 滑回来的；截图会话那条路径瞬时取满高，胶囊的竖直带因此一帧都不动。
        // 槽位**常驻组合**：两层内容的 AnimatedVisibility 因此一直存在，进出场都只由状态翻转
        // 驱动，不会出现"节点刚建立、进场动画来不及播"的时序问题。
        //
        // clipToBounds 有两个作用，缺一不可：
        //   · 托盘行按满高参与布局（requiredHeight），动画中途它比带子高，不裁剪就会把按钮
        //     画到下方按钮行上——裁剪之后它表现为"从按钮行后面长出来"，这才是"随高度展开露出"；
        //   · 带子塌回 0 的那一帧彻底不可见，不会有 0 高 Box 里的内容残留。
        Box(
            modifier = Modifier.fillMaxWidth()
                // **requiredHeight 而不是 height**：带子是一个**固定槽位**（胶囊的竖直带就是它），
                // 高度只能由 auxiliaryBandHeight（终值 / 动画值）给出，**不允许**被"底栏还剩多少空间"
                // 悄悄夹小。用 height() 时它会被 Column 的剩余空间夹掉，而它自己带 clipToBounds ——
                // 于是 64dp 的胶囊被裁成那个剩余高度（真机取证：400×200dp 的横屏宿主里带子被夹成
                // 48dp，胶囊 `unclipped 116..180 / clipped 124..172`，上下各被切 8dp —— 正是
                // `PlayerScreenStateTest.landscapeAbCapsuleHidesTheCenterControls` 抓到的形态）。
                // 夹小的代价从来不是"带子变矮"（用户看不到带子），而是**里面那枚胶囊被切一半**。
                // 底栏放不下时该溢出的是这一格，而不是把工具切成两半。
                .requiredHeight(auxiliaryBandHeight)
                .clipToBounds()
                // 辅助带的**唯一**标记：它的宽度就是工具胶囊用于排版决策的可用宽度，
                // instrumented 用它断言"胶囊确实住在带子里"以及"排版用的是同一处宽度"。
                .testTag(PlayerTestTags.AUXILIARY_BAND),
        ) {
            // 外层的 contentAlignment 显式写 TopStart：托盘行按满高测量（requiredHeight），动画中途
            // 比带子高，**必须**从带子顶边往下摆，否则托盘会随着带子高度在格子里上下浮动。
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                androidx.compose.animation.AnimatedVisibility(
                    // 托盘行让位给**任何**一枚工具胶囊：两者住在同一个槽位里，
                    // 同时可见就是两套控件叠在一起（截图胶囊那条路以前靠 sectionsVisible 顺带挡住，
                    // AB 胶囊打开时三段是可见的，所以这里必须显式排除）。
                    visible = toolsExpanded && sectionsVisible && !toolCapsuleVisible,
                    enter = fadeIn(tween(TRANSPORT_SECTION_TRANSITION_MILLIS)),
                    exit = fadeOut(tween(TRANSPORT_SECTION_TRANSITION_MILLIS)),
                ) {
                    Row(
                        // requiredHeight 而不是 height：子项按**满高**测量，不受动画中途的带子高度
                        // 约束（否则带子还矮时 padding 会把 48dp 按钮压小，托盘会"边涨边挤"）。
                        // 顶部内边距把托盘按钮挪回"辅助带只有托盘行高度时"的位置，见 PlayerToolRowTopInset。
                        modifier = Modifier.fillMaxWidth()
                            .requiredHeight(PlayerAuxiliaryBandHeight)
                            .padding(
                                top = PlayerToolRowTopInset,
                                bottom = PlayerPortraitControlsSpacing,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(PlayerShortcutSpacing, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                    state.controlLayout
                        .controls(seeyuer.yingli.player.domain.playback.PlayerControlSurface.TOOLS)
                        .reversed()
                        .forEach { id ->
                            PlayerShortcut(
                                id = id,
                                state = state,
                                allowPictureInPicture = allowPictureInPicture,
                                onOpenSettings = onOpenSettings,
                                onToggleFullscreen = onToggleFullscreen,
                                onRotateVideo = onRotateVideo,
                                onToggleSpeedPanel = onToggleSpeedPanel,
                                onCycleScaleMode = onCycleScaleMode,
                                onOpenPlaylist = onOpenPlaylist,
                                onPictureInPicture = onPictureInPicture,
                                onSetPlaybackOrder = onSetPlaybackOrder,
                                onScreenshot = onScreenshot,
                                onOpenAbTool = onOpenAbTool,
                                onToggleLock = onToggleLock,
                                onPrevious = onPrevious,
                                onNext = onNext,
                                onToggleTools = toggleTools,
                                onOpenVideoInfo = onOpenVideoInfo,
                                onSelectAudioTrack = onSelectAudioTrack,
                                onSelectSubtitleTrack = onSelectSubtitleTrack,
                                mirror = mirror,
                                onToggleMirrorHorizontal = onToggleMirrorHorizontal,
                                onToggleMirrorVertical = onToggleMirrorVertical,
                                backgroundPlaybackEnabled = backgroundPlaybackEnabled,
                                onToggleBackgroundPlayback = onToggleBackgroundPlayback,
                            )
                        }
                    }
                }
            }
            AuxiliaryToolCapsuleSlot(
                tool = toolCapsule,
                screenshotTool = screenshotTool,
                abTool = abTool,
            )
        }
        // 按钮行槽位：**恒为 PlayerChromeButtonSize，永远是 Column 的最后一个子项**。
        // 它既是胶囊竖直带的唯一依据（约束 1），也是"按钮行位置不变"这条底栏规则的落点：
        // 截图模式下按钮行只把内容淡出，槽位高度一帧都不变，所以底对齐 Column 不会在截图模式
        // 切换时重新定位，辅助带里的胶囊也就不会被带着上下跳。
        // 注意：高度只能写在这一层的 Box 上，不能写在 AnimatedVisibility 上——AnimatedVisibility
        // 隐藏时会把自己的测量尺寸收成 0，槽位高度会跟着它的退场动画在某一帧突然塌掉。
        Box(modifier = Modifier.fillMaxWidth().height(PlayerChromeButtonSize)) {
            // 这里和进度行一样必须写全限定名：进入 Box 之后，ColumnScope 那个
            // `AnimatedVisibility` 扩展会被 Compose 的布局作用域标记（@LayoutScopeMarker）
            // 挡掉，只剩下 androidx.compose.animation 里的顶层重载可用。
            androidx.compose.animation.AnimatedVisibility(
                visible = sectionsVisible,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(tween(TRANSPORT_SECTION_TRANSITION_MILLIS)),
                exit = fadeOut(tween(TRANSPORT_SECTION_TRANSITION_MILLIS)),
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val buttonCount = controlGroups.sumOf { it.size }
                    // 竖屏优先铺满整行：只要按钮本体放得下，间距就按剩余宽度压缩（可以压到很小），
                    // 这样 7 个按钮仍然一行放得下，不必退化成横向滚动。
                    val spreadAcrossRow = compact && PlayerChromeButtonSize * buttonCount <= maxWidth
                    val scrollState = rememberScrollState()
                    // 倍速编辑态：倍速按钮留在原位显示实时数值，它之后的按钮暂时隐藏，
                    // 空出的那段宽度交给滑杆（长度即"第二个按钮最左侧到最后一个按钮最右侧"）。
                    val speedPanelOpen = state.panel == seeyuer.yingli.player.domain.playback.PlayerPanel.SPEED
                    val speedFlatIndex = controlGroups.flatten().indexOf(PlayerControlId.SPEED)
                    val sliderActive = speedPanelOpen && speedFlatIndex >= 0
                    val visibleGroups = if (!sliderActive) {
                        controlGroups
                    } else {
                        var remaining = speedFlatIndex
                        controlGroups.mapNotNull { ids ->
                            if (remaining < 0) return@mapNotNull null
                            val kept = ids.take(remaining + 1)
                            remaining -= kept.size
                            kept.takeIf(List<PlayerControlId>::isNotEmpty)
                        }
                    }
                    val rowGap = if (spreadAcrossRow && buttonCount > 1) {
                        (maxWidth - PlayerChromeButtonSize * buttonCount) / (buttonCount - 1)
                    } else {
                        PlayerShortcutSpacing
                    }
                    // 预览状态必须跨"提交后挡位变化"保持同一个实例：手势协程在重组间持续运行，
                    // 若这里按 state.speed 重建状态，拖动时就写不到按钮读的那个状态，数值不再实时更新。
                    var previewedSpeed by remember { mutableStateOf<PlaybackSpeed?>(null) }
                    LaunchedEffect(state.speed, sliderActive) { previewedSpeed = null }
                    val shownSpeed = previewedSpeed ?: state.speed
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .then(if (spreadAcrossRow) Modifier else Modifier.horizontalScroll(scrollState)),
                        horizontalArrangement = if (sliderActive) Arrangement.spacedBy(rowGap) else Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        visibleGroups.forEachIndexed { index, ids ->
                            Row(
                                horizontalArrangement = if (spreadAcrossRow) {
                                    Arrangement.SpaceBetween
                                } else {
                                    Arrangement.spacedBy(PlayerShortcutSpacing)
                                },
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = when {
                                    spreadAcrossRow && !sliderActive -> Modifier.weight(1f)
                                    compact || index == 0 -> Modifier
                                    else -> Modifier.padding(start = PlayerLandscapeGroupSpacing)
                                },
                            ) {
                                ids.forEach { id ->
                                    PlayerShortcut(
                                        id = id,
                                        state = state,
                                        allowPictureInPicture = allowPictureInPicture,
                                        onOpenSettings = onOpenSettings,
                                        onToggleFullscreen = onToggleFullscreen,
                                        onRotateVideo = onRotateVideo,
                                        onToggleSpeedPanel = onToggleSpeedPanel,
                                        onCycleScaleMode = onCycleScaleMode,
                                        onOpenPlaylist = onOpenPlaylist,
                                        onPictureInPicture = onPictureInPicture,
                                        onSetPlaybackOrder = onSetPlaybackOrder,
                                        onScreenshot = onScreenshot,
                                        onOpenAbTool = onOpenAbTool,
                                        onToggleLock = onToggleLock,
                                        onPrevious = onPrevious,
                                        onNext = onNext,
                                        onToggleTools = toggleTools,
                                        onOpenVideoInfo = onOpenVideoInfo,
                                        onSelectAudioTrack = onSelectAudioTrack,
                                        onSelectSubtitleTrack = onSelectSubtitleTrack,
                                        mirror = mirror,
                                        onToggleMirrorHorizontal = onToggleMirrorHorizontal,
                                        onToggleMirrorVertical = onToggleMirrorVertical,
                                        backgroundPlaybackEnabled = backgroundPlaybackEnabled,
                                        onToggleBackgroundPlayback = onToggleBackgroundPlayback,
                                        valueLabel = if (sliderActive && id == PlayerControlId.SPEED) {
                                            shownSpeed.displayLabel()
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                            if (sliderActive && ids.contains(PlayerControlId.SPEED)) {
                                PlayerSpeedRail(
                                    current = state.speed,
                                    onPreview = { previewedSpeed = it },
                                    onCommit = { committed ->
                                        previewedSpeed = null
                                        onSetSpeed(committed)
                                    },
                                    modifier = if (spreadAcrossRow) {
                                        Modifier.weight(1f)
                                    } else {
                                        Modifier.width((trailingSpan(controlGroups, PlayerControlId.SPEED) - rowGap).coerceAtLeast(0.dp))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


/**
 * 进度条上 A–B 区间的**压暗层**：由 [YingLiSlider] 的 `trackOverlay` 槽插在
 * 「轨道底 + 播放进度填充」之后、「滑块」之前。
 *
 * 这一层**只**做区间外压暗；区间条、竖线、徽标、夸大虚线合起来叫"标记组"，它住在滑杆**之外**
 * 的兄弟画布里（[AbMarkerLayer]，`AbMarkerLayerHeight` = 64dp）。为什么必须分开：标记组要从
 * 轨道中线往上占 `AbMarkerTopOffset + AbMarkerRadius = 30dp`，而滑杆那条 48dp 触控带只给到
 * 中线以上 24dp（溢出 6dp）—— 画在滑杆内部任何一层（含这个 `trackOverlay` 离屏层）都会被那
 * 48dp 裁掉，那就是"徽标被裁一半"的成因。
 *
 * 两层用的**是同一份** [AbRangeGeometry]（由调用方在组合期算好后传进来），所以"亮区按放大后的
 * 区间画、暗区按真实边界切"这类错位不可能发生。
 *
 * ## 为什么必须是 `DstOut` + **只覆盖区间外**
 *
 * 用 `DstOut`（结果 = `dst × (1 − srcAlpha)`）：保留这批像素的**颜色**，只把它们的 alpha
 * 按系数缩放，也就是"同色降 alpha"，而不是往上面叠一层黑 —— chrome 底本来就是黑的，
 * 叠黑既看不出压暗，又会在轨道之外多压一层画面。
 *  · `DstIn`（`dst × srcAlpha`）是**全表面**混合 —— 源没覆盖到的像素会被乘 0，整行会被擦掉；
 *    "先整行压暗、再把区间内还原成 1.0"也不行，乘法不可逆，还原那一道只是再乘一次 1；
 *  · `DstOut` 只影响**被覆盖的像素**，区间内没有被覆盖，因此原样保留 —— 这正是要的效果；
 *  · 只设了 A 时**不压暗**：那还没有"区间"可言，整行压暗会被读成"进度条被禁用了"，
 *    这一状态由 A 徽标与那条 28% 的预判条自己表达（见 [AbMarkerLayer]）。
 *
 * 它只在本行被 [YingLiSlider] 隔离出来的离屏层里生效（见 `trackOverlay` 的参数文档）：
 * 没有那层隔离，`DstOut` 会把这条轨道**下面的画面**也一起"打薄"。
 */
@Composable
private fun BoxScope.AbRangeDimOverlay(geometry: AbRangeGeometry) {
    Canvas(
        Modifier
            .matchParentSize()
            .testTag(PlayerTestTags.AB_RANGE),
    ) {
        if (!geometry.complete) return@Canvas
        val thumbRadiusPx = YingLiSliderThumbRadius.toPx()
        // 压暗的左右边界与徽标/虚线**同一份几何**（`abRangeGeometry` 的渲染区间），只是把
        // 轨道坐标系平移到整行坐标系。**跟随渲染后的区间**（放大之后的 startPx/endPx），
        // 不是真实边界：否则会出现"亮区按放大后的画、暗区按真实边界切"的错位。
        val intervalLeft = thumbRadiusPx + geometry.startPx
        val intervalRight = thumbRadiusPx + geometry.endPx
        drawRect(
            color = AbRangeDimSource,
            topLeft = Offset(0f, 0f),
            size = Size(intervalLeft, size.height),
            blendMode = BlendMode.DstOut,
        )
        drawRect(
            color = AbRangeDimSource,
            topLeft = Offset(intervalRight, 0f),
            size = Size((size.width - intervalRight).coerceAtLeast(0f), size.height),
            blendMode = BlendMode.DstOut,
        )
    }
}

/**
 * 进度条上的 **A–B 标记组**：区间条 / 竖线 + 徽标 / 夸大虚线（`PlayerTestTags.AB_MARKER_LAYER`）。
 *
 * ## 它为什么是一块滑杆之外的独立画布
 *
 * 标记组整体上移到了轨道**上方**（分寸链见 [abMarkerLayout]），从轨道中线往上占 30dp，而滑杆那条
 * 48dp 触控带只给到 24dp —— **明确溢出 6dp**。画在滑杆内部（它的 `trackOverlay` 离屏层、或它自己
 * 那个 `height(48.dp)` 的 Box）里，徽标的上半边会被裁掉。所以它由调用方挂在滑杆的**兄弟**位置、
 * 自带 [AbMarkerLayerHeight]（64dp）画布：中线以上 32dp > 30dp，余量 2dp。
 * 也就是说"徽标允许向上溢出"由**布局**保证，不靠"指望某个祖先不裁剪"、也不把滑杆撑高
 * （撑高会让左右两端的时间文本重新居中，那正是历史回归过的"进度行瞬移"）。
 *
 * 画在滑杆**之前**（z 序更低）是刻意的：标记组与轨道在竖直方向不重叠（最低只到轨道上沿），
 * 而滑块住在滑杆内部、是滑杆的最后一个笔迹，于是"滑块永不被压暗、永不被盖住"由绘制顺序保证。
 *
 * ## 画的是什么
 *
 *  1. **区间条**（[AbRangeBarThickness] = 3dp、两端 [AbRangeBarCornerRadius] = 半厚圆头）：
 *     完整区间 **100%**；只设了 A 时是那条 **28% 的预判条**（[AbRangeInactiveAlpha]），
 *     起止按 `min(A, 播放头)` 排（播放头在 A 之前时条画在 `[播放头, A]`），并保证不小于
 *     [abRangeBarMinWidthPx]；
 *  2. **播放头幽灵竖线**（只设了 A）：[AbRangeGhostHeadWidth] × [AbRangeGhostHeadAlpha]，
 *     落在夹取后的播放头上 —— 预判条只说"从 A 到这里"，这条线把"这里"指出来；
 *  3. **竖线**（[AbMarkerConnectorWidth]）：从徽标下沿连到**轨道上沿** —— 徽标不再贴着轨道，
 *     这条线是它"属于哪个时刻"的唯一线索；
 *  4. **夸大虚线**：画出来的宽度 ≠ 真实宽度时，区间上下沿走虚线（实线 = 真实长度）；
 *  5. **徽标 / 合并块**：两端各一枚字母徽标（[AbMarkerDiameter] = 18dp，
 *     内画 [YingLiIcon.LETTER_A] / [YingLiIcon.LETTER_B]），两端靠得比 [AbMarkerMergeThreshold]
 *     还近时合成**一枚**合并块（两半各放一个字形框，中间一道反色缝）。
 *
 * 几何（最小可视宽度、中心锚定、越界夹取、是否合并、是否被夸大、徽标落点、预判条起止、
 * 幽灵竖线位置）**全部**由 [abRangeGeometry] 回答，竖直落点**全部**由 [abMarkerLayout] 回答，
 * 这里只把结果画出来，**不做任何判定**。
 */
@Composable
private fun AbMarkerLayer(
    geometry: AbRangeGeometry,
    modifier: Modifier,
) {
    // 要用的颜色必须在**组合上下文**里取好：`YingLiTheme.player` 是 @Composable 属性，
    // 绘制作用域里读不到。
    val markerColor = YingLiTheme.player.controlPrimary
    // 字母/缝取播放器 chrome 的**底色**（黑，`YingLiTheme.player.canvas`）：这是播放器里既有的
    // "实心控件 + 反色内容"配对，在任意画面上对比度都最高，也不引入新颜色。
    val letterColor = YingLiTheme.player.canvas
    // 字母用**字形**而不是 Text：绘制作用域里没有字体测量，而字形本来就能按任意尺寸画。
    // 两个 painter 在组合期取好（rememberVectorPainter 是 @Composable），再交给绘制 lambda。
    val letterA = rememberVectorPainter(YingLiIcon.LETTER_A.imageVector)
    val letterB = rememberVectorPainter(YingLiIcon.LETTER_B.imageVector)

    Canvas(modifier.testTag(PlayerTestTags.AB_MARKER_LAYER)) {
        if (!geometry.valid) return@Canvas

        // 竖直分寸链只有这一个来源：画布中心 = 轨道中线（两者由布局保证重合）。
        val layout = abMarkerLayout(
            trackCenterYPx = size.height / 2f,
            markerOffsetPx = AbMarkerTopOffset.toPx(),
            markerRadiusPx = AbMarkerRadius.toPx(),
            barThicknessPx = AbRangeBarThickness.toPx(),
            trackThicknessPx = YingLiSliderTrackHeight.toPx(),
            bandHeightPx = AbRangeBandHeight.toPx(),
        )
        val thumbRadiusPx = YingLiSliderThumbRadius.toPx()
        val radius = AbMarkerRadius.toPx()
        val glyphSize = AbMarkerGlyphSize.toPx()
        val badgeCorner = CornerRadius(AbMarkerCornerRadius.toPx())
        val barThicknessPx = AbRangeBarThickness.toPx()
        val centerY = layout.markerCenterYPx

        fun drawBadge(centerX: Float) {
            drawRoundRect(
                color = markerColor,
                topLeft = Offset(centerX - radius, centerY - radius),
                size = Size(radius * 2f, radius * 2f),
                cornerRadius = badgeCorner,
            )
        }

        fun drawLetter(painter: VectorPainter, centerX: Float) {
            translate(left = centerX - glyphSize / 2f, top = centerY - glyphSize / 2f) {
                with(painter) {
                    draw(size = Size(glyphSize, glyphSize), colorFilter = ColorFilter.tint(letterColor))
                }
            }
        }

        // 一切 x 都画在**轨道坐标系**里（以轨道左端为 0）：整行坐标系向左平移一个圆钮半径就是它。
        translate(left = thumbRadiusPx) {
            // ── ① 区间条 ────────────────────────────────────────────────────────────────────
            // 完整区间 → 100%；只设了 A → 28% 的预判条（起止与最小可辨宽度由几何给出）。
            // 只取这两档：demo 里属于编辑态的"整组 50% 休眠"不采纳（见 AbRangeInactiveAlpha）。
            val barStart = geometry.predictedStartPx ?: geometry.startPx
            val barEnd = geometry.predictedEndPx ?: geometry.endPx
            drawRoundRect(
                color = markerColor.copy(alpha = if (geometry.complete) 1f else AbRangeInactiveAlpha),
                topLeft = Offset(barStart, layout.barTopPx),
                size = Size((barEnd - barStart).coerceAtLeast(0f), barThicknessPx),
                cornerRadius = CornerRadius(AbRangeBarCornerRadius.toPx()),
            )

            // ── ② 只设了 A：播放头幽灵竖线（"B 会落在这里"的锚点）──────────────────────────
            geometry.headCenterXPx?.let { headX ->
                drawLine(
                    color = markerColor.copy(alpha = AbRangeGhostHeadAlpha),
                    start = Offset(headX, layout.barTopPx),
                    end = Offset(headX, layout.barBottomPx),
                    strokeWidth = AbRangeGhostHeadWidth.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            // ── ③ 夸大虚线（区间完整且被放大）────────────────────────────────────────────────
            // 夸大必须可辨识：画出来的宽度不等于真实宽度时，区间上下沿走虚线（实线 = 真实长度）。
            // 区间色块取消之后，这条虚线是"为了看得见而放大过"的**唯一**视觉落点，所以必须保留。
            // 它仍以**轨道中线**为锚（AbRangeBandHeight 的取值理由就是"不许吃掉整行 48dp"），
            // 不跟着徽标上移。
            if (geometry.complete && geometry.exaggerated) {
                val stroke = AbRangeDashStrokeWidth.toPx()
                val dash = AbRangeDashLength.toPx()
                val inset = stroke / 2f
                val effect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))
                listOf(layout.bandTopPx + inset, layout.bandBottomPx - inset).forEach { y ->
                    drawLine(
                        color = markerColor,
                        start = Offset(geometry.startPx, y),
                        end = Offset(geometry.endPx, y),
                        strokeWidth = stroke,
                        pathEffect = effect,
                    )
                }
            }

            // ── ④ 竖线（徽标 → 轨道）────────────────────────────────────────────────────────
            // 合并块是**一件**东西，所以只在它的中心画一条；未合并时两端各一条。
            val connectorCenters = if (geometry.merged) {
                listOf(geometry.centerXPx)
            } else {
                listOfNotNull(geometry.aMarkerCenterXPx, geometry.bMarkerCenterXPx.takeIf { geometry.complete })
            }
            connectorCenters.forEach { centerX ->
                drawLine(
                    color = markerColor,
                    start = Offset(centerX, layout.connectorTopPx),
                    end = Offset(centerX, layout.connectorBottomPx),
                    strokeWidth = AbMarkerConnectorWidth.toPx(),
                    // 下端是平的：它正好停在轨道上沿，圆头会往轨道里多探半个线宽。
                    cap = StrokeCap.Butt,
                )
            }

            // ── ⑤ 徽标 / 合并块 ──────────────────────────────────────────────────────────────
            if (geometry.merged) {
                // 合并块：**一枚**宽度 = 最小可视宽度（= 合并阈值 = 直径 + 最小间距）的圆角块，
                // 中间一道 1dp 缝把它读成"A 与 B 被并到一起"。
                // 两半各放**完整**的字母，而不是各放半个：每半正好是一个字形框的宽度，字形框放得下；
                // 半个字母（每半只剩约 3.5dp 墨宽）根本认不出，而只画一道缝的空白块连"哪一端是哪一端"
                // 都回答不了。块的宽度由徽标尺寸与最小间距推导，不是另拍的一个数。
                drawRoundRect(
                    color = markerColor,
                    topLeft = Offset(geometry.startPx, centerY - radius),
                    size = Size(geometry.visualWidthPx, radius * 2f),
                    cornerRadius = badgeCorner,
                )
                drawLine(
                    // 缝是两枚徽标之间的"空"。徽标本体是**实心强调色**，所以这里的"空"只能取**反色**
                    //（与字母同一支墨，[letterColor]）：旧实现用轨道色（半透明白）画在这块实心白上，
                    // 半透明白叠白 = 白，**实测完全看不见**（`.tmp-abloop-badges/12-merged-5s.png`
                    // 的逐像素取证）。画成反色之后，合并块才真的读成"两枚徽标被并到一起"。
                    color = letterColor,
                    start = Offset(geometry.centerXPx, centerY - radius),
                    end = Offset(geometry.centerXPx, centerY + radius),
                    strokeWidth = AbMarkerSeamWidth.toPx(),
                )
                drawLetter(letterA, geometry.aMarkerCenterXPx)
                drawLetter(letterB, geometry.bMarkerCenterXPx)
            } else {
                // 未合并：两端各一枚徽标（只设了 A 时只有 A 那一枚）。
                drawBadge(geometry.aMarkerCenterXPx)
                drawLetter(letterA, geometry.aMarkerCenterXPx)
                if (geometry.complete) {
                    drawBadge(geometry.bMarkerCenterXPx)
                    drawLetter(letterB, geometry.bMarkerCenterXPx)
                }
            }
        }
    }
}

/**
 * 进度条上两枚徽标的**端点热区**：点按徽标 = 跳到该端点（裁决 U6），与读数条里的 A / B 数值
 * 共用 [AbValueTapTargetWidth] × [AbValueTapTargetHeight] 这个最小触控尺寸、也共用同一个回调。
 *
 * ## 为什么竖直方向不"以徽标中心居中"
 *
 * 徽标中心在轨道中线上方 [AbMarkerTopOffset]（21dp），而这一行（滑杆那条 48dp 触控带）的上沿只到
 * 中线以上 24dp —— 也就是说徽标中心在这一行上沿**之下 3dp**，而徽标顶边在这一行**之外** 6dp。
 * Compose 的命中测试要求指针落在**每一个**祖先节点的边界内，越界的部分点不到；所以热区取
 * "这一行上沿 → 往下 [AbValueTapTargetHeight]"：它覆盖徽标可见部分的**下 12dp**（含中心），
 * 徽标顶部那 6dp 落在槽位之外、本来就无法命中 —— 这不是热区被裁，而是它必须落在可命中的范围内。
 *
 * ## 为什么必须是这一格的最后一个子节点
 *
 * 滑杆在 `awaitFirstDown` 之后**立刻**按 down 的位置 seek（`YingLiControls`）。命中测试从最后一个
 * 子节点开始，所以只有让热区排在滑杆**之后**、并在命中时 `consume()` 掉这次 down，滑杆才会放手
 * （它对"已被消费的 down"直接返回）—— 否则同一次点按会先跳端点、再被 down-seek 覆盖成
 * "点在圆钮左边一点"。
 */
@Composable
private fun AbMarkerTapTargets(
    geometry: AbRangeGeometry,
    pointAMillis: Long?,
    pointBMillis: Long?,
    onSeekToPoint: (Long) -> Unit,
    modifier: Modifier,
) {
    if (!geometry.valid) return
    val density = LocalDensity.current
    // 轨道坐标系 → 这一行的坐标系：整体向右平移一个圆钮半径（与绘制层同一段换算）。
    val trackLeftPx = with(density) { YingLiSliderThumbRadius.toPx() }
    val tapWidthPx = with(density) { AbValueTapTargetWidth.toPx() }
    Box(modifier) {
        pointAMillis?.let { pointMillis ->
            BadgeTapTarget(
                leftPx = trackLeftPx + geometry.aMarkerCenterXPx - tapWidthPx / 2f,
                pointMillis = pointMillis,
                testTag = PlayerTestTags.AB_MARKER_POINT_A,
                onSeekToPoint = onSeekToPoint,
            )
        }
        if (geometry.complete) {
            pointBMillis?.let { pointMillis ->
                BadgeTapTarget(
                    leftPx = trackLeftPx + geometry.bMarkerCenterXPx - tapWidthPx / 2f,
                    pointMillis = pointMillis,
                    testTag = PlayerTestTags.AB_MARKER_POINT_B,
                    onSeekToPoint = onSeekToPoint,
                )
            }
        }
    }
}

/**
 * 单枚徽标的端点热区（见 [AbMarkerTapTargets] 的竖直位置与命中顺序说明）。
 *
 * [leftPx] 是热区左边缘在**这一行坐标系**里的位置（已含"轨道两端各让出一个圆钮半径"的换算）。
 *
 * 手势是**手写的 down 消费**（而不是 `clickable`）：滑杆在 down 的那一刻就 seek，只有在这里
 * 第一时间 `consume()` 掉这次按下，滑杆才会放手（它对"已被消费的 down"直接返回，见
 * `YingLiControls` 的手势注释）—— 用 `clickable` 的话，是否在 down 阶段消费取决于它内部的
 * 判定时机，同一次点按就可能先跳端点、再被 down-seek 覆盖成"点在圆钮左边一点"。
 */
@Composable
private fun BadgeTapTarget(
    leftPx: Float,
    pointMillis: Long,
    testTag: String,
    onSeekToPoint: (Long) -> Unit,
) {
    Box(
        Modifier
            .offset { IntOffset(x = leftPx.roundToInt(), y = 0) }
            .size(AbValueTapTargetWidth, AbValueTapTargetHeight)
            .testTag(testTag)
            .pointerInput(pointMillis) {
                awaitEachGesture {
                    // 这次按下可能已经被更上层的控件认领（当前没有，但保持与滑杆同一条判据）。
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    onSeekToPoint(pointMillis)
                }
            },
    )
}

/**
 * ③ 压暗用的 `DstOut` 源：混合结果是 `dst × (1 − srcAlpha)`，所以源的 alpha 取
 * `1 − [AbRangeOutsideAlpha]`（区间外留在 0.55）。遮罩本身的颜色不参与混合（只有 alpha 参与），
 * 黑色只是"没有颜色"的中性选择。
 *
 * 它只在本行被 [YingLiSlider] 隔离出来的离屏层里生效（见 `trackOverlay` 的参数文档）：
 * 没有那层隔离，`DstOut` 会把这条轨道**下面的画面**也一起"打薄"。
 */
private val AbRangeDimSource = Color.Black.copy(alpha = 1f - AbRangeOutsideAlpha)
/**
 * 底栏辅助带那一格里当前该渲染哪一枚工具胶囊。
 *
 * 截图工具与 AB 工具**共用同一格**（§3.3），所以"谁在里面"必须是**一个**判定的结果：
 * 两处各画一个、再靠状态互斥去保证不重叠，一旦某个时序漏掉就会出现两枚胶囊叠在一起。
 */
internal enum class AuxiliaryToolCapsule { NONE, SCREENSHOT, AB_LOOP }

/**
 * 解析辅助带的占用者。
 *
 * **AB 优先**：打开 AB 工具的动作本身就会结束截图会话（`Armed` / `Capturing`）或让截图工具让位
 * （`Preview`，保留预览卡），因此两者同时为真是"同一帧内的中间态"，此时渲染 AB ——
 * 用户刚才点的就是 AB，先出现的应该是它。
 */
internal fun auxiliaryToolCapsule(
    abToolOpen: Boolean,
    screenshot: ScreenshotUiState,
): AuxiliaryToolCapsule = when {
    abToolOpen -> AuxiliaryToolCapsule.AB_LOOP
    screenshot.isCapsuleVisible() -> AuxiliaryToolCapsule.SCREENSHOT
    else -> AuxiliaryToolCapsule.NONE
}

/**
 * 工具胶囊在辅助带里的叠放槽：从右向左滑入/滑出 + 淡入淡出，时长取胶囊自己的
 * [SCREENSHOT_CAPSULE_TRANSITION_MILLIS]（比底栏三段的 240ms 慢，理由见该常量）。
 *
 * 截图胶囊与 AB 胶囊**渲染在这里的同一个槽**里（[AuxiliaryToolCapsule] 只选一枚），
 * 于是两枚胶囊的竖直带、水平起点、时序完全同源 —— 这正是 §3.3 要求的"位置/几何/材质同源"。
 *
 * 内层 Box 的高度固定为 [PlayerScreenshotCapsuleHeight]：胶囊在带子里的竖直位置只由"带子顶边"
 * 决定，槽位高度不参与测量，避免胶囊被动画中途的带子高度压扁。
 *
 * 滑出期间要继续画**刚才那一枚**胶囊的内容：AnimatedVisibility 在退场期间会保留自身，
 * 若内容跟着这次状态一起变成 `NONE`，用户看到的就是"一个空框滑出去"。
 */
@Composable
private fun AuxiliaryToolCapsuleSlot(
    tool: AuxiliaryToolCapsule,
    screenshotTool: @Composable () -> Unit,
    abTool: @Composable () -> Unit,
) {
    // 记住最后一次真正在屏幕上的那一枚（只依赖入参，写自己的 state 不会引入环）。
    var lastShownTool by remember { mutableStateOf(AuxiliaryToolCapsule.NONE) }
    if (tool != AuxiliaryToolCapsule.NONE) lastShownTool = tool
    val shownTool = if (tool == AuxiliaryToolCapsule.NONE) lastShownTool else tool
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = tool != AuxiliaryToolCapsule.NONE,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(SCREENSHOT_CAPSULE_TRANSITION_MILLIS),
            ) + fadeIn(tween(SCREENSHOT_CAPSULE_TRANSITION_MILLIS)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(SCREENSHOT_CAPSULE_TRANSITION_MILLIS),
            ) + fadeOut(tween(SCREENSHOT_CAPSULE_TRANSITION_MILLIS)),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().requiredHeight(PlayerScreenshotCapsuleHeight),
                contentAlignment = Alignment.TopCenter,
            ) {
                when (shownTool) {
                    AuxiliaryToolCapsule.SCREENSHOT -> screenshotTool()
                    AuxiliaryToolCapsule.AB_LOOP -> abTool()
                    AuxiliaryToolCapsule.NONE -> Unit
                }
            }
        }
    }
}

/**
 * 关闭态那一行里，[target] 右边缘到最后一个控件右边缘之间的宽度（含紧随 [target] 的那个间距）。
 * 用于把滑杆撑到"其余按钮原本占用的那段"，包括跨组时的组间距。
 */
private fun trailingSpan(groups: List<List<PlayerControlId>>, target: PlayerControlId): Dp {
    val flat = groups.flatMapIndexed { index, ids -> ids.map { index to it } }
    val targetIndex = flat.indexOfFirst { it.second == target }
    if (targetIndex < 0) return 0.dp
    var span: Dp = 0.dp
    var previousGroup = flat[targetIndex].first
    for (index in targetIndex + 1 until flat.size) {
        val group = flat[index].first
        span += if (group == previousGroup) PlayerShortcutSpacing else PlayerLandscapeGroupSpacing
        span += PlayerChromeButtonSize
        previousGroup = group
    }
    return span
}

@Composable
private fun PlayerShortcut(
    id: PlayerControlId,
    state: PlayerUiState,
    allowPictureInPicture: Boolean,
    onOpenSettings: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onRotateVideo: () -> Unit,
    onToggleSpeedPanel: () -> Unit,
    onCycleScaleMode: () -> Unit,
    onOpenPlaylist: () -> Unit,
    onPictureInPicture: () -> Unit,
    onSetPlaybackOrder: (PlaybackOrder) -> Unit,
    onScreenshot: () -> Unit,
    onOpenAbTool: () -> Unit,
    onToggleLock: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleTools: () -> Unit = {},
    onOpenVideoInfo: () -> Unit,
    onSelectAudioTrack: () -> Unit,
    onSelectSubtitleTrack: () -> Unit,
    /** 会话内镜像状态：镜像按钮与锁定/全屏按钮一样，用"选中态"表达当前是否已翻转。 */
    mirror: seeyuer.yingli.player.domain.playback.VideoMirror = seeyuer.yingli.player.domain.playback.VideoMirror(),
    onToggleMirrorHorizontal: () -> Unit = {},
    onToggleMirrorVertical: () -> Unit = {},
    /** 会话内后台播放开关状态：与镜像按钮一样，用"选中态"表达开关当前是否开启。 */
    backgroundPlaybackEnabled: Boolean = true,
    onToggleBackgroundPlayback: () -> Unit = {},
    valueLabel: String? = null,
) {
    val action: (() -> Unit)? = when (id) {
        PlayerControlId.SPEED -> onToggleSpeedPanel
        PlayerControlId.ORDER -> {            val next = PlaybackOrder.entries[(PlaybackOrder.entries.indexOf(state.playbackOrder) + 1) % PlaybackOrder.entries.size]
            { onSetPlaybackOrder(next) }
        }
        PlayerControlId.AUDIO -> onSelectAudioTrack
        PlayerControlId.SUBTITLE -> onSelectSubtitleTrack
        PlayerControlId.SCALE -> onCycleScaleMode
        PlayerControlId.SCREENSHOT -> onScreenshot
        PlayerControlId.AB_LOOP -> onOpenAbTool
        PlayerControlId.MIRROR_HORIZONTAL -> onToggleMirrorHorizontal
        PlayerControlId.MIRROR_VERTICAL -> onToggleMirrorVertical
        PlayerControlId.BACKGROUND_PLAYBACK -> onToggleBackgroundPlayback
        PlayerControlId.PLAYLIST -> onOpenPlaylist
        PlayerControlId.INFO -> onOpenVideoInfo
        PlayerControlId.PIP -> if (allowPictureInPicture) onPictureInPicture else null
        PlayerControlId.ORIENTATION -> onRotateVideo
        PlayerControlId.LOCK -> onToggleLock
        PlayerControlId.SETTINGS -> onOpenSettings
        PlayerControlId.MORE -> onToggleTools
        PlayerControlId.PREVIOUS -> onPrevious
        PlayerControlId.NEXT -> onNext
        PlayerControlId.FULLSCREEN -> onToggleFullscreen
    }
    if (action == null) return
    val icon = when (id) {
        PlayerControlId.SPEED -> YingLiIcon.SPEED
        PlayerControlId.ORDER -> playbackOrderIcon(state.playbackOrder)
        PlayerControlId.AUDIO -> YingLiIcon.VOLUME
        PlayerControlId.SUBTITLE -> YingLiIcon.SUBTITLES
        PlayerControlId.SCALE -> videoScaleModeIcon(state.scaleMode)
        PlayerControlId.SCREENSHOT -> YingLiIcon.SCREENSHOT
        PlayerControlId.AB_LOOP -> YingLiIcon.AB2
        PlayerControlId.MIRROR_HORIZONTAL -> YingLiIcon.FLIP_HORIZONTAL
        PlayerControlId.MIRROR_VERTICAL -> YingLiIcon.FLIP_VERTICAL
        // 后台播放是"声音继续、画面不可见"，用耳机字形表达比用齿轮/扬声器更直观。
        PlayerControlId.BACKGROUND_PLAYBACK -> YingLiIcon.BACKGROUND_PLAYBACK
        PlayerControlId.PLAYLIST -> YingLiIcon.PLAYLIST
        PlayerControlId.INFO -> YingLiIcon.DIAGNOSTICS
        PlayerControlId.PIP -> YingLiIcon.PICTURE_IN_PICTURE
        PlayerControlId.ORIENTATION -> YingLiIcon.ROTATE
        PlayerControlId.FULLSCREEN -> if (state.isFullscreen) YingLiIcon.EXIT_FULLSCREEN else YingLiIcon.FULLSCREEN
        // 锁定按钮的图标表达"当前状态"：未锁定是开锁，锁定后是闭合锁。
        PlayerControlId.LOCK -> if (state.overlay.locked) YingLiIcon.LOCK else YingLiIcon.UNLOCK
        PlayerControlId.SETTINGS -> YingLiIcon.SETTINGS
        PlayerControlId.MORE -> YingLiIcon.OVERFLOW
        PlayerControlId.PREVIOUS -> YingLiIcon.PREVIOUS
        PlayerControlId.NEXT -> YingLiIcon.NEXT
    }
    val label = when (id) {
        PlayerControlId.SPEED -> stringResource(R.string.player_speed_state, state.speed.displayLabel())
        PlayerControlId.ORDER -> stringResource(
            R.string.player_order_state,
            stringResource(playbackOrderLabelRes(state.playbackOrder)),
        )
        PlayerControlId.AUDIO -> "音轨"
        PlayerControlId.SUBTITLE -> "字幕"
        PlayerControlId.SCALE -> stringResource(
            R.string.player_scale_state,
            stringResource(videoScaleModeLabelRes(state.scaleMode)),
        )
        PlayerControlId.SCREENSHOT -> stringResource(R.string.player_screenshot)
        PlayerControlId.AB_LOOP -> "AB循环"
        PlayerControlId.MIRROR_HORIZONTAL -> "水平翻转"
        PlayerControlId.MIRROR_VERTICAL -> "垂直翻转"
        PlayerControlId.BACKGROUND_PLAYBACK -> "后台播放"
        PlayerControlId.PLAYLIST -> stringResource(R.string.player_playlist)
        PlayerControlId.INFO -> "视频信息"
        PlayerControlId.PIP -> stringResource(R.string.player_pip)
        PlayerControlId.ORIENTATION -> stringResource(
            R.string.player_rotation_state,
            stringResource(videoRotationLabelRes(state.rotation)),
        )
        // 图标表达状态，文案表达动作：未锁定点击后锁定，锁定后点击解锁。
        PlayerControlId.LOCK -> stringResource(if (state.overlay.locked) R.string.player_unlock else R.string.player_lock)
        PlayerControlId.SETTINGS -> "播放设置"
        PlayerControlId.MORE -> "更多"
        PlayerControlId.PREVIOUS -> stringResource(R.string.player_previous)
        PlayerControlId.NEXT -> stringResource(R.string.player_next)
        PlayerControlId.FULLSCREEN -> stringResource(
            if (state.isFullscreen) R.string.player_fullscreen_exit else R.string.player_fullscreen,
        )
    }
    // 托盘按钮的"生效中"：镜像、后台播放、AB 循环都是**开关**而非动作，生效时实心。
    // AB 循环的判定来自 [PlayerUiState.abLoopActive]（会话侧区间是否完整），与顶栏快捷槽、
    // 设置面板 chip 读的是同一个值 —— 三处不允许各写一套判定。
    val toggleOn = when (id) {
        PlayerControlId.MIRROR_HORIZONTAL -> mirror.horizontal
        PlayerControlId.MIRROR_VERTICAL -> mirror.vertical
        PlayerControlId.BACKGROUND_PLAYBACK -> backgroundPlaybackEnabled
        PlayerControlId.AB_LOOP -> state.abLoopActive
        else -> null
    }
    PlayerChromeIconButton(
        icon = icon,
        contentDescription = label,
        onClick = action,
        size = PlayerChromeButtonSize,
        filled = toggleOn == true,
        // 开关型按钮把选中态同时写进语义（读屏念"已选中"，测试据此断言激活态）；
        // 其余按钮传 null，语义树里不会凭空多出一个 selected。
        selectedState = toggleOn,
        valueLabel = valueLabel,
    )
}

/** 进度时间文本：等宽数字，避免播放中数字宽度变化导致文本抖动。 */
@Composable
private fun playerTimeTextStyle(): TextStyle =
    MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")

/** 播放顺序按钮用图标直接表达当前模式：顺序、随机、列表循环、单个循环。 */
private fun playbackOrderIcon(order: PlaybackOrder): YingLiIcon = when (order) {
    PlaybackOrder.SEQUENCE -> YingLiIcon.PLAY_MODE_SEQUENCE
    PlaybackOrder.SHUFFLE -> YingLiIcon.ARROWS_SHUFFLE
    PlaybackOrder.QUEUE_REPEAT -> YingLiIcon.REPEAT
    PlaybackOrder.SINGLE_REPEAT -> YingLiIcon.REPEAT_ONCE
}

/** 画面比例按钮同样用图标直接表达当前模式：适应、裁剪、拉伸。顶栏与底栏共用同一映射。 */
internal fun videoScaleModeIcon(mode: VideoScaleMode): YingLiIcon = when (mode) {
    VideoScaleMode.FIT -> YingLiIcon.ASPECT_RATIO
    VideoScaleMode.FILL -> YingLiIcon.CROP
    VideoScaleMode.ORIGINAL -> YingLiIcon.STRETCH
}

/**
 * 播放页各处时间读数的**唯一格式**（进度行两端、AB 区间读数行、手势 HUD）。
 *
 * **两档**（本批定稿）：
 *  · **不足 1 小时**：`mm:ss`（`00:12` / `59:59`）—— 秒级媒体上多出来的 `00:` 只是噪声；
 *  · **≥ 1 小时**：`hh:mm:ss`，小时**补零**（95 分钟 → `01:35:00`）。
 *    旧实现超过一小时**不进位**（95 分钟显示 `95:00`）：那既不是 `mm:ss` 的语义（分钟位超过 59），
 *    也让"这片子多长"要多做一次心算 —— 用户明确要求按 `01:35:00` 显示。
 *
 * 它是**同源**的唯一实现：端点读数（[formatAbTime]）、区间时长（[formatAbIntervalDuration]）、
 * 手势 HUD、短视频页都调它，所以这一处改动在那些地方一起生效，不会出现"同一段视频的同一个时刻
 * 在两处显示成两个样子"。
 *
 * `internal` 而不是 `private`：AB 读数与手势 HUD 必须显示与进度条**完全一样**的时间字符串。
 * `null`（时长未知）统一显示 `--:--`。
 */
internal fun formatDuration(durationMillis: Long?): String {
    if (durationMillis == null) return "--:--"
    val totalSeconds = durationMillis.coerceAtLeast(0) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    // 小时补零：`01:35:00`。三档之间的**唯一**判据是"有没有小时"，与进度行的时间文本同源。
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
