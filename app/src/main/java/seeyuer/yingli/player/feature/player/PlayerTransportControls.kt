package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
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
    controlLayout: seeyuer.yingli.player.domain.playback.PlayerControlLayout = seeyuer.yingli.player.domain.playback.PlayerControlLayout(),
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
        if (abStart != null || abEnd != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("A ${formatDuration(abStart)}", color = YingLiTheme.player.controlPrimary)
                Text("B ${formatDuration(abEnd)}", color = YingLiTheme.player.controlPrimary)
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
        BoxWithConstraints(Modifier.fillMaxWidth()) {
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
            // "更多"托盘展开状态：纯 UI 状态，不需要进 ViewModel。
            var toolsExpanded by remember { mutableStateOf(false) }
            // 预览状态必须跨"提交后挡位变化"保持同一个实例：手势协程在重组间持续运行，
            // 若这里按 state.speed 重建状态，拖动时就写不到按钮读的那个状态，数值不再实时更新。
            var previewedSpeed by remember { mutableStateOf<PlaybackSpeed?>(null) }
            LaunchedEffect(state.speed, sliderActive) { previewedSpeed = null }
            val shownSpeed = previewedSpeed ?: state.speed
            androidx.compose.animation.AnimatedVisibility(
                visible = toolsExpanded,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = PlayerPortraitControlsSpacing),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.controlLayout
                        .controls(seeyuer.yingli.player.domain.playback.PlayerControlSurface.TOOLS)
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
                            )
                        }
                }
            }
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
        PlayerControlId.SCREENSHOT -> "截图"
        PlayerControlId.AB_LOOP -> "AB循环"
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
