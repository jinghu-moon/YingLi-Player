package seeyuer.yingli.player.feature.shorts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import coil3.compose.AsyncImage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.component.YingLiIconButton
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.icon.imageVector
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.feature.player.formatDuration
import seeyuer.yingli.player.feature.player.frameRateLabel

object ShortsTestTags {
    const val SCREEN = "shorts.screen"
    const val VIDEO = "shorts.video"
    const val PLAY = "shorts.play"
    const val NEXT = "shorts.next"
    const val PREVIOUS = "shorts.previous"
}

@Composable
fun ShortsRoute(
    viewModel: ShortsViewModel,
    videoSurface: @Composable () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onShare: (ShortsCandidate) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.initialize() }
    ShortsScreen(
        state = state,
        videoSurface = videoSurface,
        onBack = onBack,
        onShare = onShare,
        onPlayPause = viewModel::togglePlayback,
        onNext = viewModel::next,
        onPrevious = viewModel::previous,
        onFavorite = viewModel::toggleFavorite,
        onBlocked = viewModel::toggleBlocked,
        onRemoveFavorite = viewModel::removeFavorite,
        onRemoveBlocked = viewModel::removeBlocked,
        onAutoNextChanged = viewModel::setAutoNext,
        onRepeatChanged = viewModel::setRepeatCurrent,
        onCycleFitMode = viewModel::cycleFitMode,
        onSeekBy = viewModel::seekBy,
        onToggleSpeed = viewModel::toggleLockedSpeed,
        onTemporarySpeedStart = viewModel::beginTemporarySpeed,
        onTemporarySpeedEnd = viewModel::endTemporarySpeed,
        onDelete = viewModel::deleteCurrent,
        onCaptureScreenshot = viewModel::captureScreenshot,
        onCloseScreenshot = viewModel::closeScreenshot,
        onToggleScreenshotPause = viewModel::toggleScreenshotExpiryPause,
        onDeleteScreenshot = viewModel::deleteScreenshot,
        onPictureInPicture = { viewModel.enterPictureInPicture() },
        modifier = modifier,
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ShortsScreen(
    state: ShortsUiState,
    videoSurface: @Composable () -> Unit,
    onBack: () -> Unit,
    onShare: (ShortsCandidate) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onFavorite: () -> Unit,
    onBlocked: () -> Unit,
    modifier: Modifier = Modifier,
    onRemoveFavorite: (MediaItemId) -> Unit = {},
    onRemoveBlocked: (MediaItemId) -> Unit = {},
    onAutoNextChanged: (Boolean) -> Unit,
    onRepeatChanged: (Boolean) -> Unit,
    onCycleFitMode: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onToggleSpeed: () -> Unit,
    onTemporarySpeedStart: () -> Unit = {},
    onTemporarySpeedEnd: () -> Unit = {},
    onDelete: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    onCloseScreenshot: () -> Unit,
    onToggleScreenshotPause: () -> Unit = {},
    onDeleteScreenshot: () -> Unit,
    onPictureInPicture: () -> Unit,
) {
    val candidate = state.current
    val gestureState = remember { mutableStateOf(ShortsGestureState()) }
    var temporarySpeedActive by remember { mutableStateOf(false) }
    var moreOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var deleteConfirmOpen by remember { mutableStateOf(false) }
    var managedMode by remember { mutableStateOf<String?>(null) }
    // semantics { } 不是 @Composable 上下文，无障碍动作文案必须先取出来。
    val accessibilityNext = stringResource(R.string.shorts_accessibility_next)
    val accessibilityPrevious = stringResource(R.string.shorts_accessibility_previous)
    val accessibilityPlayPause = if (state.playing) {
        stringResource(R.string.shorts_accessibility_pause)
    } else {
        stringResource(R.string.shorts_accessibility_play)
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(YingLiTheme.player.canvas)
            .testTag(ShortsTestTags.SCREEN),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(ShortsTestTags.VIDEO)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(accessibilityNext) { onNext(); true },
                        CustomAccessibilityAction(accessibilityPrevious) { onPrevious(); true },
                        CustomAccessibilityAction(accessibilityPlayPause) { onPlayPause(); true },
                    )
                }
                .pointerInput(state.currentIndex) {
                    detectDragGestures(
                        onDragStart = { gestureState.value = ShortsGestureState() },
                        onDragEnd = {
                            when (ShortsGestureReducer.finish(gestureState.value)) {
                                ShortsGestureAction.Next -> onNext()
                                ShortsGestureAction.Previous -> onPrevious()
                                ShortsGestureAction.SeekForward -> onSeekBy(5_000)
                                ShortsGestureAction.SeekBackward -> onSeekBy(-5_000)
                                ShortsGestureAction.None -> Unit
                            }
                            gestureState.value = ShortsGestureState()
                        },
                        onDragCancel = { gestureState.value = ShortsGestureState() },
                        onDrag = { change, amount ->
                            change.consume()
                            gestureState.value = ShortsGestureReducer.drag(gestureState.value, amount.x, amount.y)
                        },
                    )
                }
                .pointerInput(state.currentIndex) {
                    detectTapGestures(
                        onTap = { onPlayPause() },
                        onLongPress = {
                            temporarySpeedActive = true
                            onTemporarySpeedStart()
                        },
                        onPress = {
                            try {
                                awaitRelease()
                            } finally {
                                if (temporarySpeedActive) {
                                    temporarySpeedActive = false
                                    onTemporarySpeedEnd()
                                }
                            }
                        },
                    )
                },
        ) {
            videoSurface()
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            YingLiIconButton(
                icon = YingLiIcon.BACK,
                contentDescription = stringResource(R.string.action_back),
                onClick = onBack,
                tint = YingLiTheme.player.controlPrimary,
            )
            Text(
                text = if (state.currentIndex >= 0) {
                    stringResource(R.string.shorts_index_format, state.currentIndex + 1, state.candidates.size)
                } else {
                    stringResource(R.string.shorts_index_empty)
                },
                color = YingLiTheme.player.controlPrimary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ShortsRailAction(
                icon = if (state.isFavorite) YingLiIcon.FAVORITE_FILLED else YingLiIcon.FAVORITE,
                label = if (state.isFavorite) {
                    stringResource(R.string.shorts_favorite_added)
                } else {
                    stringResource(R.string.shorts_favorite)
                },
                onClick = onFavorite,
                tint = if (state.isFavorite) YingLiTheme.colors.accentFavorite else YingLiTheme.player.controlPrimary,
            )
            ShortsRailAction(
                icon = YingLiIcon.BLOCK,
                label = if (state.isBlocked) {
                    stringResource(R.string.shorts_blocked)
                } else {
                    stringResource(R.string.shorts_block)
                },
                onClick = onBlocked,
                tint = if (state.isBlocked) MaterialTheme.colorScheme.error else YingLiTheme.player.controlPrimary,
            )
            ShortsRailAction(YingLiIcon.OVERFLOW, stringResource(R.string.shorts_more), onClick = { moreOpen = true })
        }
        when {
            state.loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = YingLiTheme.player.controlPrimary,
            )
            state.errorCode != null -> Text(
                text = stringResource(R.string.shorts_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            state.candidates.isEmpty() -> Text(
                text = stringResource(R.string.shorts_empty),
                color = YingLiTheme.player.controlSecondary,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            candidate?.let {
                Text(it.title, color = YingLiTheme.player.controlPrimary, maxLines = 2)
                val dimension = stringResource(
                    R.string.shorts_dimension_format,
                    it.width?.toString() ?: stringResource(R.string.shorts_dimension_unknown),
                    it.height?.toString() ?: stringResource(R.string.shorts_dimension_unknown),
                )
                Text(
                    text = stringResource(
                        R.string.shorts_progress_format,
                        dimension,
                        formatShortsTime(state.progressMillis),
                        formatShortsTime(it.durationMillis),
                    ),
                    color = YingLiTheme.player.controlSecondary,
                )
            }
            val duration = state.durationMillis ?: candidate?.durationMillis
            val progress = duration?.takeIf { it > 0 }?.let { state.progressMillis.toFloat() / it } ?: 0f
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                color = YingLiTheme.player.controlPrimary,
                trackColor = YingLiTheme.player.controlSecondary.copy(alpha = 0.35f),
            )
        }
        if (moreOpen) {
            ModalBottomSheet(
                onDismissRequest = { moreOpen = false },
                containerColor = YingLiTheme.colors.surface,
            ) {
                Text(stringResource(R.string.shorts_sheet_title), modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                ShortsSheetSwitchRow(stringResource(R.string.shorts_sheet_auto_next), state.autoNext, onAutoNextChanged)
                ShortsSheetSwitchRow(stringResource(R.string.shorts_sheet_repeat_current), state.repeatCurrent, onRepeatChanged)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.shorts_sheet_fit_mode), modifier = Modifier.weight(1f))
                    Text(state.fitMode.name, modifier = Modifier.padding(end = 8.dp))
                    YingLiIconButton(YingLiIcon.ARROW_RIGHT, stringResource(R.string.shorts_sheet_fit_mode_action), onCycleFitMode)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.shorts_sheet_locked_speed), modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.shorts_sheet_speed_value, state.lockedSpeed), modifier = Modifier.padding(end = 8.dp))
                    YingLiIconButton(YingLiIcon.PLAY, stringResource(R.string.shorts_sheet_locked_speed_action), onToggleSpeed)
                }
                TextButton(onClick = { moreOpen = false; infoOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.shorts_info))
                }
                TextButton(onClick = { moreOpen = false; onCaptureScreenshot() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.shorts_screenshot)) }
                TextButton(onClick = { moreOpen = false; onPictureInPicture() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.shorts_pip)) }
                TextButton(onClick = { managedMode = "favorites" }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.shorts_favorites_count, state.favoriteCount)) }
                TextButton(onClick = { managedMode = "blocked" }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.shorts_blocked_count, state.blockedCount)) }
                TextButton(
                    onClick = { moreOpen = false; candidate?.let(onShare) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = candidate != null,
                ) { Text(stringResource(R.string.shorts_share)) }
                TextButton(onClick = { moreOpen = false; deleteConfirmOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.shorts_move_to_trash))
                }
            }
        }
        if (infoOpen) {
            val info = state.mediaInfo
            AlertDialog(
                onDismissRequest = { infoOpen = false },
                title = { Text(stringResource(R.string.shorts_info)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.shorts_info_title, info?.title ?: candidate?.title.orEmpty()))
                        Text(
                            stringResource(
                                R.string.shorts_info_resolution,
                                info?.width ?: candidate?.width ?: stringResource(R.string.shorts_unknown),
                                info?.height ?: candidate?.height ?: stringResource(R.string.shorts_unknown),
                            ),
                        )
                        Text(
                            stringResource(
                                R.string.shorts_info_duration,
                                info?.durationMillis ?: candidate?.durationMillis ?: stringResource(R.string.shorts_unknown),
                            ),
                        )
                        Text(stringResource(R.string.shorts_info_video_codec, info?.videoCodec ?: stringResource(R.string.shorts_unknown)))
                        Text(stringResource(R.string.shorts_info_audio_codec, info?.audioCodec ?: stringResource(R.string.shorts_unknown)))
                        // 帧率统一走 frameRateLabel：这里原来直接打印 Float（既没有单位也没有小数口径），
                        // 与播放页顶栏/信息对话框不一致；同一个媒体在三个界面必须显示同一个数字。
                        Text(stringResource(R.string.shorts_info_frame_rate, frameRateLabel(info?.frameRate) ?: stringResource(R.string.shorts_unknown)))
                    }
                },
                confirmButton = { TextButton(onClick = { infoOpen = false }) { Text(stringResource(R.string.shorts_close)) } },
            )
        }
        if (deleteConfirmOpen) {
            AlertDialog(
                onDismissRequest = { deleteConfirmOpen = false },
                title = { Text(stringResource(R.string.shorts_delete_confirm_title)) },
                text = { Text(stringResource(R.string.shorts_delete_confirm_message)) },
                dismissButton = { TextButton(onClick = { deleteConfirmOpen = false }) { Text(stringResource(R.string.shorts_cancel)) } },
                confirmButton = {
                    TextButton(onClick = { deleteConfirmOpen = false; onDelete() }) { Text(stringResource(R.string.shorts_confirm)) }
                },
            )
        }
        if (managedMode != null) {
            ModalBottomSheet(onDismissRequest = { managedMode = null }, containerColor = YingLiTheme.colors.surface) {
                val items = if (managedMode == "favorites") state.favoriteItems else state.blockedItems
                Text(
                    if (managedMode == "favorites") stringResource(R.string.shorts_favorites_title) else stringResource(R.string.shorts_blocked_title),
                    modifier = Modifier.padding(18.dp),
                )
                if (items.isEmpty()) {
                    Text(stringResource(R.string.shorts_managed_empty), modifier = Modifier.padding(18.dp))
                } else {
                    items.forEach { item ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.title, modifier = Modifier.weight(1f), maxLines = 1)
                            YingLiIconButton(YingLiIcon.CLOSE, stringResource(R.string.shorts_remove_item, item.title), onClick = {
                                if (managedMode == "favorites") onRemoveFavorite(item.id) else onRemoveBlocked(item.id)
                            })
                        }
                    }
                }
            }
        }
        if (!state.hintShown && state.candidates.isNotEmpty()) {
            Text(
                stringResource(R.string.shorts_hint),
                color = YingLiTheme.player.controlPrimary,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        }
        val preview = state.screenshot as? seeyuer.yingli.player.domain.playback.ScreenshotUiState.Preview
        if (preview != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 72.dp)
                    .clickable(onClick = onToggleScreenshotPause),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = preview.uri.takeIf { it.isNotBlank() },
                    contentDescription = stringResource(R.string.shorts_screenshot_preview),
                    modifier = Modifier.size(width = 116.dp, height = 72.dp),
                )
                Text(preview.displayName, color = YingLiTheme.player.controlPrimary, modifier = Modifier.padding(end = 8.dp))
                val remainingSeconds = ((preview.remainingMillis + 999L) / 1_000L).coerceAtMost(3L)
                Text(
                    stringResource(R.string.shorts_screenshot_remaining, remainingSeconds),
                    color = YingLiTheme.player.controlSecondary,
                )
                YingLiIconButton(
                    YingLiIcon.PAUSE,
                    if (preview.expanded) stringResource(R.string.shorts_screenshot_collapse) else stringResource(R.string.shorts_screenshot_expand),
                    onToggleScreenshotPause,
                    tint = YingLiTheme.player.controlPrimary,
                )
                YingLiIconButton(YingLiIcon.CLOSE, stringResource(R.string.shorts_screenshot_close), onCloseScreenshot, tint = YingLiTheme.player.controlPrimary)
                YingLiIconButton(YingLiIcon.DELETE, stringResource(R.string.shorts_screenshot_delete), onDeleteScreenshot, tint = MaterialTheme.colorScheme.error)
            }
            LinearProgressIndicator(
                progress = { preview.remainingMillis / seeyuer.yingli.player.domain.playback.ScreenshotUiState.PREVIEW_DURATION_MILLIS.toFloat() },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 144.dp)
                    .fillMaxWidth(0.42f),
                color = YingLiTheme.player.controlPrimary,
                trackColor = YingLiTheme.player.controlSecondary.copy(alpha = 0.35f),
            )
        }
    }
}

@Composable
private fun ShortsRailAction(
    icon: YingLiIcon,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = YingLiTheme.player.controlPrimary,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        YingLiIconButton(icon, label, onClick, tint = tint)
        Text(label, color = tint, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * 短视频页的时间读数：**就是**播放页那一份格式化（[formatDuration]，不足 1 小时 `mm:ss`、
 * 1 小时起 `hh:mm:ss`）。
 *
 * 旧实现在这里另写了一遍 `%02d:%02d`：同一个影片在短视频页与播放页会显示成两个样子
 * （95 分钟在这里是 `95:00`、在播放页是 `01:35:00`）。时长未知时与进度行一致显示 `--:--`。
 */
private fun formatShortsTime(millis: Long?): String = formatDuration(millis)

@Composable
private fun ShortsSheetSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
