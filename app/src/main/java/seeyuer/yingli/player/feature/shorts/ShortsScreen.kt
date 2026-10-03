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
                        CustomAccessibilityAction("下一条") { onNext(); true },
                        CustomAccessibilityAction("上一条") { onPrevious(); true },
                        CustomAccessibilityAction(if (state.playing) "暂停" else "播放") { onPlayPause(); true },
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
                text = if (state.currentIndex >= 0) "${state.currentIndex + 1}/${state.candidates.size}" else "0/0",
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
                label = if (state.isFavorite) "已收藏" else "收藏",
                onClick = onFavorite,
                tint = if (state.isFavorite) YingLiTheme.colors.accentFavorite else YingLiTheme.player.controlPrimary,
            )
            ShortsRailAction(
                icon = YingLiIcon.BLOCK,
                label = if (state.isBlocked) "已屏蔽" else "屏蔽",
                onClick = onBlocked,
                tint = if (state.isBlocked) MaterialTheme.colorScheme.error else YingLiTheme.player.controlPrimary,
            )
            ShortsRailAction(YingLiIcon.OVERFLOW, "更多", onClick = { moreOpen = true })
        }
        when {
            state.loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = YingLiTheme.player.controlPrimary,
            )
            state.errorCode != null -> Text(
                text = "短视频加载失败，请返回后重试",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            state.candidates.isEmpty() -> Text(
                text = "没有可播放的竖屏视频",
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
                Text(
                    text = buildString {
                        append("${it.width ?: "?"}x${it.height ?: "?"}")
                        append("  ")
                        append(formatShortsTime(state.progressMillis))
                        append(" / ")
                        append(formatShortsTime(it.durationMillis))
                    },
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
                Text("短视频操作", modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                ShortsSheetSwitchRow("自动切换下一条", state.autoNext, onAutoNextChanged)
                ShortsSheetSwitchRow("循环当前视频", state.repeatCurrent, onRepeatChanged)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("画面比例", modifier = Modifier.weight(1f))
                    Text(state.fitMode.name, modifier = Modifier.padding(end = 8.dp))
                    YingLiIconButton(YingLiIcon.ARROW_RIGHT, "切换画面比例", onCycleFitMode)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("锁定倍速", modifier = Modifier.weight(1f))
                    Text("${state.lockedSpeed}x", modifier = Modifier.padding(end = 8.dp))
                    YingLiIconButton(YingLiIcon.PLAY, "切换锁定倍速", onToggleSpeed)
                }
                TextButton(onClick = { moreOpen = false; infoOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("视频信息")
                }
                TextButton(onClick = { moreOpen = false; onCaptureScreenshot() }, modifier = Modifier.fillMaxWidth()) { Text("截图") }
                TextButton(onClick = { moreOpen = false; onPictureInPicture() }, modifier = Modifier.fillMaxWidth()) { Text("画中画") }
                TextButton(onClick = { managedMode = "favorites" }, modifier = Modifier.fillMaxWidth()) { Text("收藏列表 (${state.favoriteCount})") }
                TextButton(onClick = { managedMode = "blocked" }, modifier = Modifier.fillMaxWidth()) { Text("黑名单视频管理 (${state.blockedCount})") }
                TextButton(
                    onClick = { moreOpen = false; candidate?.let(onShare) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = candidate != null,
                ) { Text("分享") }
                TextButton(onClick = { moreOpen = false; deleteConfirmOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("移入回收站")
                }
            }
        }
        if (infoOpen) {
            val info = state.mediaInfo
            AlertDialog(
                onDismissRequest = { infoOpen = false },
                title = { Text("视频信息") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("标题：${info?.title ?: candidate?.title.orEmpty()}")
                        Text("分辨率：${info?.width ?: candidate?.width ?: "未知"} x ${info?.height ?: candidate?.height ?: "未知"}")
                        Text("时长：${info?.durationMillis ?: candidate?.durationMillis ?: "未知"} ms")
                        Text("视频编码：${info?.videoCodec ?: "未知"}")
                        Text("音频编码：${info?.audioCodec ?: "未知"}")
                        // 帧率统一走 frameRateLabel：这里原来直接打印 Float（既没有单位也没有小数口径），
                        // 与播放页顶栏/信息对话框不一致；同一个媒体在三个界面必须显示同一个数字。
                        Text("帧率：${frameRateLabel(info?.frameRate) ?: "未知"}")
                    }
                },
                confirmButton = { TextButton(onClick = { infoOpen = false }) { Text("关闭") } },
            )
        }
        if (deleteConfirmOpen) {
            AlertDialog(
                onDismissRequest = { deleteConfirmOpen = false },
                title = { Text("移入回收站？") },
                text = { Text("当前短视频将从候选队列移除，可在回收站恢复。") },
                dismissButton = { TextButton(onClick = { deleteConfirmOpen = false }) { Text("取消") } },
                confirmButton = {
                    TextButton(onClick = { deleteConfirmOpen = false; onDelete() }) { Text("确认") }
                },
            )
        }
        if (managedMode != null) {
            ModalBottomSheet(onDismissRequest = { managedMode = null }, containerColor = YingLiTheme.colors.surface) {
                val items = if (managedMode == "favorites") state.favoriteItems else state.blockedItems
                Text(if (managedMode == "favorites") "收藏视频" else "黑名单视频", modifier = Modifier.padding(18.dp))
                if (items.isEmpty()) {
                    Text("暂无项目", modifier = Modifier.padding(18.dp))
                } else {
                    items.forEach { item ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.title, modifier = Modifier.weight(1f), maxLines = 1)
                            YingLiIconButton(YingLiIcon.CLOSE, "移除 ${item.title}", onClick = {
                                if (managedMode == "favorites") onRemoveFavorite(item.id) else onRemoveBlocked(item.id)
                            })
                        }
                    }
                }
            }
        }
        if (!state.hintShown && state.candidates.isNotEmpty()) {
            Text(
                "上滑下一条 · 下滑上一条\n左右滑动快进或快退",
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
                    contentDescription = "截图预览",
                    modifier = Modifier.size(width = 116.dp, height = 72.dp),
                )
                Text(preview.displayName, color = YingLiTheme.player.controlPrimary, modifier = Modifier.padding(end = 8.dp))
                Text("${((preview.remainingMillis + 999L) / 1_000L).coerceAtMost(3L)}s", color = YingLiTheme.player.controlSecondary)
                YingLiIconButton(YingLiIcon.PAUSE, if (preview.expanded) "收起截图预览" else "展开截图预览", onToggleScreenshotPause, tint = YingLiTheme.player.controlPrimary)
                YingLiIconButton(YingLiIcon.CLOSE, "关闭截图预览", onCloseScreenshot, tint = YingLiTheme.player.controlPrimary)
                YingLiIconButton(YingLiIcon.DELETE, "删除截图", onDeleteScreenshot, tint = MaterialTheme.colorScheme.error)
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

private fun formatShortsTime(millis: Long?): String {
    val totalSeconds = (millis ?: 0L).coerceAtLeast(0L) / 1_000L
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

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
