package seeyuer.yingli.player.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.component.YingLiIconButton
import seeyuer.yingli.player.core.designsystem.component.YingLiSlider
import seeyuer.yingli.player.core.designsystem.component.YingLiSliderDefaults
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.playback.PlaybackState
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.PlayerControlId
import seeyuer.yingli.player.domain.playback.PlayerControlSurface
import seeyuer.yingli.player.domain.playback.PlaybackOrder
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

/** 竖屏进度行与按钮行之间的间距。 */
private val PlayerPortraitControlsSpacing = 16.dp

/**
 * 底栏「辅助带」的高度：**工具托盘行与截图胶囊叠放在同一格**里共用这个固定高度。
 *
 * 尺寸依据：[PlayerChromeButtonSize]（48dp，`PlayerTopBar.kt` 里定义）既是托盘行按钮的真实圆径
 * （[PlayerShortcut] 的 `size = PlayerChromeButtonSize`），也是截图胶囊的高度
 * （`ScreenshotToolCapsule` 固定 48dp）；该行与下方按钮行之间的间距取
 * [PlayerPortraitControlsSpacing]。托盘行原本的内容高度就是"48dp 按钮 + 16dp 下内边距"，
 * 所以胶囊占这一格时，与托盘行处在同一条竖直带上，也与"托盘展开时的底栏高度"完全一致。
 *
 * **不要按"当前有没有内容"去算这个高度，也不要让它参与动画**：胶囊的竖直带
 * （= 按钮行槽位高度 + 底栏内边距）必须自始至终固定，带子塌掉的那一帧胶囊就会跟着上下跳。
 */
private val PlayerAuxiliaryBandHeight = PlayerChromeButtonSize + PlayerPortraitControlsSpacing

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
 * 底栏三段（进度行 / 工具托盘 / 按钮行）在进出截图模式时的收起-展开时长。
 * 与截图胶囊的滑入滑出同取 240ms 一档（设计稿 §6 的 0.24s），保证两侧同时开始、同时结束。
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
            contentDescription = "上一项",
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
            contentDescription = "下一项",
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
    modifier: Modifier,
    compact: Boolean = false,
) {
    val duration = state.playback.timeline.durationMillis
    val abStart = state.abLoop.pointA
    val abEnd = state.abLoop.pointB
    var dragging by remember(state.playback.request?.mediaId) { mutableStateOf(false) }
    var previewPositionMillis by remember(state.playback.request?.mediaId) {
        mutableStateOf(state.displayedPositionMillis)
    }
    // 拖动中的"待投放目标"：**只保留最后一次**，等播放器从上一次 seek 的重缓冲里恢复后再投放。
    // REX 的做法是取消在途 seek（PlaybackManager.seekJob.cancel()，本地文件不排队）只保留最新；
    // Media3 的 seekTo 在 seek 进行中会排队，所以固定时间节流会让快速拖动越拖越滞后。
    var pendingSeekMillis by remember(state.playback.request?.mediaId) { mutableStateOf<Long?>(null) }
    var lastLiveSeekAt by remember(state.playback.request?.mediaId) { mutableStateOf(0L) }
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
        val capsuleVisible = state.screenshot.isCapsuleVisible()
        // 辅助带的"退出留位窗口"：胶囊滑出需要 TRANSPORT_SECTION_TRANSITION_MILLIS，
        // 这段里带子必须继续满高，否则内容会被挤成 0 高（滑出动画等于被吃掉）。
        // 进入不看这个标志（约束 3），所以它只负责"晚一点撤"，不参与"什么时候出现"。
        var capsuleBandHeld by remember { mutableStateOf(false) }
        LaunchedEffect(capsuleVisible) {
            if (capsuleVisible) {
                capsuleBandHeld = true
            } else if (capsuleBandHeld) {
                delay(TRANSPORT_SECTION_TRANSITION_MILLIS.toLong())
                capsuleBandHeld = false
            }
        }
        // 三段（进度行 / 工具托盘行 / 按钮行）共用一个可见性判据：截图会话结束就回来。
        // 唯一的例外不是"判据不同"，而是"回来的时机"：辅助带只是为胶囊留位、且托盘没开时，
        // 带子稍后要塌回 0（胶囊滑完那 240ms），三段必须等它塌完再淡入——否则它们会在带子
        // 塌掉的那一帧整体下移一个带高。托盘本来就开着时带子不塌，三段立刻回来。
        val sectionsVisible = !screenshotSession && !(capsuleBandHeld && !toolsExpanded)
        // 进度区是固定槽位（高度 = 进度行高度 + AB 行的预留）：截图模式下只淡出内容，
        // 槽位高度不变。**这里若用 shrink/expand，底对齐的整个 Column 会在截图模式切换时
        // 重新定位**，胶囊虽然住在辅助带里不受这一段影响，但三段一起变高变矮仍然会让底栏
        // 在动画中途整体抽动，所以三段一律固定槽位。
        val hasAbMarkers = abStart != null || abEnd != null
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PlayerChromeButtonSize + if (hasAbMarkers) PlayerPortraitControlsSpacing else 0.dp),
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
                Box(Modifier.weight(1f)) {
                    if (duration != null && duration > 0 && abStart != null) {
                        val markerColor = YingLiTheme.colors.selectionStructural
                        Canvas(Modifier.matchParentSize().padding(horizontal = 10.dp)) {
                            val startX = size.width * (abStart.toFloat() / duration).coerceIn(0f, 1f)
                            val endX = abEnd?.let { size.width * (it.toFloat() / duration).coerceIn(0f, 1f) }
                            if (endX != null) {
                                drawRect(
                                    color = markerColor.copy(alpha = 0.28f),
                                    topLeft = androidx.compose.ui.geometry.Offset(startX, size.height * 0.4f),
                                    size = androidx.compose.ui.geometry.Size((endX - startX).coerceAtLeast(0f), size.height * 0.2f),
                                )
                            }
                            drawLine(markerColor, androidx.compose.ui.geometry.Offset(startX, 0f), androidx.compose.ui.geometry.Offset(startX, size.height), strokeWidth = 2.dp.toPx())
                            if (endX != null) {
                                drawLine(markerColor, androidx.compose.ui.geometry.Offset(endX, 0f), androidx.compose.ui.geometry.Offset(endX, size.height), strokeWidth = 2.dp.toPx())
                            }
                        }
                    }
                    YingLiSlider(
                        value = displayedPositionMillis.toFloat().coerceAtMost((duration ?: 1).toFloat()),
                        onValueChange = {
                            dragging = true
                            previewPositionMillis = it.toLong().let { candidate ->
                                if (abStart != null && abEnd != null) candidate.coerceIn(abStart, abEnd) else candidate
                            }
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
                    )
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
                    if (hasAbMarkers) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("A ${formatDuration(abStart)}", color = YingLiTheme.player.controlPrimary)
                            Text("B ${formatDuration(abEnd)}", color = YingLiTheme.player.controlPrimary)
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
        // 辅助带：工具托盘行与截图胶囊**叠放在同一个固定高度的槽位**里（这就是"托盘行 + 胶囊
        // 放进一个固定高度 Box"），槽位高度不参与任何动画，只在这两种情况下切换：
        //   · 想要它（托盘展开 / 截图会话中）→ 满高；
        //   · 想要它 + 胶囊正在滑出 → 继续满高（capsuleBandHeld，约束 2）；
        //   · 其余 → 0（与不挂载这个 Box 等价，但节点留在组合里）。
        // 槽位**常驻组合**：两层内容的 AnimatedVisibility 因此一直存在，进出场都只由状态翻转
        // 驱动，不会出现"节点刚建立、进场动画来不及播"的时序问题。
        val auxiliaryBandHeight = if (toolsExpanded || screenshotSession || capsuleBandHeld) {
            PlayerAuxiliaryBandHeight
        } else {
            0.dp
        }
        Box(modifier = Modifier.fillMaxWidth().height(auxiliaryBandHeight)) {
            androidx.compose.animation.AnimatedVisibility(
                visible = toolsExpanded && sectionsVisible,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(tween(TRANSPORT_SECTION_TRANSITION_MILLIS)),
                exit = fadeOut(tween(TRANSPORT_SECTION_TRANSITION_MILLIS)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = PlayerPortraitControlsSpacing),
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
                                onToggleTools = { toolsExpanded = !toolsExpanded },
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
            ScreenshotToolAnimatedSlot(
                visible = capsuleVisible,
                screenshotTool = screenshotTool,
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
                                        onToggleTools = { toolsExpanded = !toolsExpanded },
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

@Composable
private fun ScreenshotToolAnimatedSlot(
    visible: Boolean,
    screenshotTool: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(TRANSPORT_SECTION_TRANSITION_MILLIS),
            ) + fadeIn(tween(TRANSPORT_SECTION_TRANSITION_MILLIS)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(TRANSPORT_SECTION_TRANSITION_MILLIS),
            ) + fadeOut(tween(TRANSPORT_SECTION_TRANSITION_MILLIS)),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().height(PlayerChromeButtonSize),
                contentAlignment = Alignment.TopCenter,
            ) {
                screenshotTool()
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
        PlayerControlId.AB_LOOP -> YingLiIcon.REPLAY
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
        PlayerControlId.PLAYLIST -> "播放列表"
        PlayerControlId.INFO -> "视频信息"
        PlayerControlId.PIP -> "画中画"
        PlayerControlId.ORIENTATION -> stringResource(
            R.string.player_rotation_state,
            stringResource(videoRotationLabelRes(state.rotation)),
        )
        // 图标表达状态，文案表达动作：未锁定点击后锁定，锁定后点击解锁。
        PlayerControlId.LOCK -> stringResource(if (state.overlay.locked) R.string.player_unlock else R.string.player_lock)
        PlayerControlId.SETTINGS -> "播放设置"
        PlayerControlId.MORE -> "更多"
        PlayerControlId.PREVIOUS -> "上一项"
        PlayerControlId.NEXT -> "下一项"
        PlayerControlId.FULLSCREEN -> stringResource(
            if (state.isFullscreen) R.string.player_fullscreen_exit else R.string.player_fullscreen,
        )
    }
    PlayerChromeIconButton(
        icon = icon,
        contentDescription = label,
        onClick = action,
        size = PlayerChromeButtonSize,
        // 镜像与后台播放都是"开关"而非"动作"，生效时用选中态表达；其余按钮保持原有外观。
        filled = when (id) {
            PlayerControlId.MIRROR_HORIZONTAL -> mirror.horizontal
            PlayerControlId.MIRROR_VERTICAL -> mirror.vertical
            PlayerControlId.BACKGROUND_PLAYBACK -> backgroundPlaybackEnabled
            else -> false
        },
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

private fun formatDuration(durationMillis: Long?): String {
    if (durationMillis == null) return "--:--"
    val totalSeconds = durationMillis.coerceAtLeast(0) / 1_000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
