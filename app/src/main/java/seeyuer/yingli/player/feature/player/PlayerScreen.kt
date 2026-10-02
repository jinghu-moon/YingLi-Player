package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import kotlinx.coroutines.delay
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.playback.PlaybackRecoveryAction
import seeyuer.yingli.player.domain.playback.PlayerPanel
import seeyuer.yingli.player.domain.playback.AbPoint
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.PlaybackState
import seeyuer.yingli.player.domain.playback.ScreenshotUiState
import seeyuer.yingli.player.domain.playback.VideoScaleMode
import seeyuer.yingli.player.domain.playback.VideoRotation
import seeyuer.yingli.player.domain.playback.PlaybackOrder
import seeyuer.yingli.player.domain.playback.PlayerControlLayout
import seeyuer.yingli.player.domain.thumbnail.ThumbnailLoader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onReplay: () -> Unit,
    onRetry: () -> Unit,
    onRecovery: (PlaybackRecoveryAction) -> Unit,
    videoSurface: @Composable (transformed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    canNavigatePrevious: Boolean = false,
    canNavigateNext: Boolean = false,
    onSeekBackward: () -> Unit = {},
    onSeekForward: () -> Unit = {},
    onToggleOverlay: () -> Unit = {},
    onToggleLock: () -> Unit = {},
    onSetSpeed: (PlaybackSpeed) -> Unit = {},
    onSetScaleMode: (VideoScaleMode) -> Unit = {},
    onSelectAudioTrack: (String) -> Unit = {},
    onSelectSubtitleTrack: (String?) -> Unit = {},
    onPictureInPicture: () -> Unit = {},
    onScreenshot: () -> Unit = {},
    onCaptureScreenshot: () -> Unit = {},
    onPreviousScreenshotFrame: () -> Unit = {},
    onNextScreenshotFrame: () -> Unit = {},
    onToggleScreenshotPreview: () -> Unit = {},
    onCloseScreenshot: () -> Unit = {},
    onDeleteScreenshot: () -> Unit = onCloseScreenshot,
    onToggleFullscreen: () -> Unit = {},
    onExitFullscreen: () -> Unit = {},
    onRotateVideo: () -> Unit = {},
    onToggleSpeedPanel: () -> Unit = {},
    onCycleScaleMode: () -> Unit = {},
    /** 首次进入的一次性手势提示已展示（规格 §5.15 / §19.3）；默认空实现便于预览与测试。 */
    onGestureHintShown: () -> Unit = {},
    /** 画面手势（音量/亮度/进度/长按倍速）的回调；默认空实现便于预览与测试。 */
    gestureCallbacks: PlayerGestureCallbacks = PlayerGestureCallbacks(),
    onSetRotation: (VideoRotation) -> Unit = {},
    onOpenPanel: (PlayerPanel) -> Unit = {},
    onClosePanel: () -> Unit = {},
    onSetPlaybackOrder: (PlaybackOrder) -> Unit = {},
    onSetControlLayout: (PlayerControlLayout) -> Unit = {},
    onOpenPlaylist: () -> Unit = {},
    onSelectPlaylistItem: (Int) -> Unit = {},
    playlistItems: LazyPagingItems<PlaylistMediaItem>? = null,
    thumbnailRepository: ThumbnailLoader? = null,
    onOpenAbTool: () -> Unit = {},
    onSetAbPoint: (AbPoint) -> Unit = {},
    onClearAb: () -> Unit = {},
    onCloseAbTool: () -> Unit = {},
    allowPictureInPicture: Boolean = true,
    allowScreenshot: Boolean = true,
    transientMessage: PlayerUiEvent.TransientMessage? = null,
    onTransientMessageConsumed: () -> Unit = {},
) {
    LaunchedEffect(transientMessage) {
        if (transientMessage != null) {
            delay(3_000L)
            onTransientMessageConsumed()
        }
    }
    // 一次性手势提示（规格 §5.15 / §19.3）：只在"从未看过"时出现，展示约 2800ms 后记为已展示。
    // key 用这个布尔值：偏好一旦读出"已展示"，计时协程立即取消，不会补一次多余的落库。
    val showGestureHint = !state.preferences.gestureHintShown
    LaunchedEffect(showGestureHint) {
        if (showGestureHint) {
            delay(GESTURE_HINT_SHOW_MILLIS)
            onGestureHintShown()
        }
    }
    val hasTransientTool = state.panel != PlayerPanel.NONE || state.abToolOpen ||
        state.screenshot is ScreenshotUiState.Armed || state.screenshot is ScreenshotUiState.Capturing ||
        state.screenshot is ScreenshotUiState.Preview
    // 画面手势的唯一所有者是 playerCanvasDragGestures：把单击/双击接线到画布回调。
    // 放大状态下双击改为复位缩放（规格要求提供复位入口，且不会误伤播放状态）。
    val latestZoomActive by rememberUpdatedState(state.zoom.isActive)
    val latestGestureCallbacks by rememberUpdatedState(gestureCallbacks)
    // 锁定态只允许播放/暂停与解锁：双击的分区行为全部屏蔽。
    val canvasDoubleTap: (() -> Unit) -> Unit = { action ->
        if (!state.overlay.locked) {
            if (latestZoomActive) latestGestureCallbacks.onResetZoom() else action()
        }
    }
    // 缩放手势是否"真的在进行"，用于区分跟手与复位动画。
    // 不能用 HUD 是否显示来判断：缩放浮岛会停留 1 秒，用户在 1 秒内双击复位时会被误判成
    // 手势进行中（走 snap 分支），复位也就没有动画了。
    val zoomGestureInProgress = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val canvasGestureCallbacks = gestureCallbacks.copy(
        onZoomBegin = {
            zoomGestureInProgress.value = true
            gestureCallbacks.onZoomBegin()
        },
        onZoomEnd = {
            zoomGestureInProgress.value = false
            gestureCallbacks.onZoomEnd()
        },
        onTap = onToggleOverlay,
        onDoubleTapBackward = { canvasDoubleTap(onSeekBackward) },
        onDoubleTapForward = { canvasDoubleTap(onSeekForward) },
        onDoubleTapCenter = {
            canvasDoubleTap {
                if (state.playback is seeyuer.yingli.player.domain.playback.PlaybackState.Playing) {
                    onPause()
                } else {
                    onPlay()
                }
            }
        },
    )
    // 返回优先级（规格 §8）：关闭面板/工具 -> 解锁 -> 退出全屏 -> 退出播放页。
    // 只有“退出播放页”不拦截，交给路由栈处理。
    val backAction = resolvePlayerBack(
        hasTransientTool = hasTransientTool,
        locked = state.overlay.locked,
        isFullscreen = state.isFullscreen,
    )
    BackHandler(
        enabled = backAction != PlayerBackAction.LEAVE_PLAYER,
        onBack = {
            when (backAction) {
                PlayerBackAction.CLOSE_TOOL -> when {
                    state.panel != PlayerPanel.NONE -> onClosePanel()
                    state.abToolOpen -> onCloseAbTool()
                    else -> onCloseScreenshot()
                }
                PlayerBackAction.UNLOCK -> onToggleLock()
                PlayerBackAction.EXIT_FULLSCREEN -> onExitFullscreen()
                PlayerBackAction.LEAVE_PLAYER -> Unit
            }
        },
    )
    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
            .background(YingLiTheme.player.canvas)
            .playerCanvasDragGestures(
                config = PlayerGestureConfig(
                    seekEnabled = state.preferences.gestureSeekEnabled,
                    volumeEnabled = state.preferences.gestureVolumeEnabled,
                    brightnessEnabled = state.preferences.gestureBrightnessEnabled,
                    zoomEnabled = state.preferences.gestureZoomEnabled,
                    leftSideIsVolume = state.preferences.gestureLeftSideIsVolume,
                    locked = state.overlay.locked,
                ),
                // 单一所有者：单击/双击/长按/拖动/缩放全部由 playerCanvasDragGestures 处理。
                // 之前这里另挂一个 detectTapGestures，它会在按下时 consume 整个 down，导致
                // 画面手势只在"没被它消费"的少数位置生效（实测 21 次按下里 17 次被消费），
                // 表现为捏合时好时坏、长按只有一块区域能用。REX-Player 的画面链上同样没有它。
                callbacks = canvasGestureCallbacks,
                zoomActive = state.zoom.isActive,
                swipeDownExitEnabled = state.preferences.gestureSwipeDownToExitEnabled,
                onSwipeDownExit = onBack,
            )
            .testTag(PlayerTestTags.CANVAS),
    ) {
        val landscape = maxWidth > maxHeight
        // 自由缩放：只做视图层变换（缩放 + 平移），不进播放管线；倍数与平移都已按视口夹紧。
        // 手势进行中（真实手势边界）直接跟手；手势结束后的复位用动画过渡，避免"啪"地跳回去。
        val zoomGestureActive = zoomGestureInProgress.value
        val zoomSpec: androidx.compose.animation.core.AnimationSpec<Float> = if (zoomGestureActive) {
            androidx.compose.animation.core.snap()
        } else {
            androidx.compose.animation.core.tween(ZOOM_TRANSITION_MILLIS)
        }
        val zoomScale = androidx.compose.animation.core.animateFloatAsState(
            targetValue = state.zoom.scale,
            animationSpec = zoomSpec,
            label = "zoom-scale",
        ).value
        val zoomOffsetX = androidx.compose.animation.core.animateFloatAsState(
            targetValue = state.zoom.offsetX,
            animationSpec = zoomSpec,
            label = "zoom-offset-x",
        ).value
        val zoomOffsetY = androidx.compose.animation.core.animateFloatAsState(
            targetValue = state.zoom.offsetY,
            animationSpec = zoomSpec,
            label = "zoom-offset-y",
        ).value
        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoomScale
                scaleY = zoomScale
                translationX = zoomOffsetX * size.width * zoomScale
                translationY = -zoomOffsetY * size.height * zoomScale
            },
        ) {
            VideoRotationStage(
                rotation = state.rotation,
                videoAspect = state.videoAspect(),
                fillScreen = state.fillScreen,
                zoomActive = state.zoom.isActive,
                modifier = Modifier.fillMaxSize(),
                mediaKey = state.playback.request?.mediaId?.value,
            ) { transformed ->
                // SurfaceView 不能跟随父级 graphicsLayer 缩放/平移；自由缩放时强制使用
                // TextureView，否则 ViewModel 中的 zoom 会变化但画面仍停在原尺寸。
                videoSurface(transformed || state.zoom.isActive)
            }
        }
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to YingLiTheme.player.edgeScrim,
                    0.22f to androidx.compose.ui.graphics.Color.Transparent,
                    0.72f to androidx.compose.ui.graphics.Color.Transparent,
                    1f to YingLiTheme.player.edgeScrim,
                ),
            ),
        )
        if (state.overlay.locked) {
            // 锁定态保留"播放/暂停 + 解锁"：单击唤出、随控件自动隐藏，位置与底栏锁定键同一角。
            if (state.overlay.controlsVisible) {
                PlayerLockedControls(
                    isPlaying = state.playback is seeyuer.yingli.player.domain.playback.PlaybackState.Playing,
                    onTogglePlayback = { if (state.playback is seeyuer.yingli.player.domain.playback.PlaybackState.Playing) onPause() else onPlay() },
                    onUnlock = onToggleLock,
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(
                            end = if (landscape) {
                                PlayerLandscapeBarHorizontalPadding
                            } else {
                                PlayerPortraitBarHorizontalPadding
                            },
                            bottom = if (landscape) {
                                PlayerLandscapeBarVerticalPadding
                            } else {
                                PlayerPortraitBarVerticalPadding
                            },
                        ),
                )
            }
        } else {
            PlayerTopBar(
                state = state,
                onBack = onBack,
                onScreenshot = onScreenshot,
                onPictureInPicture = onPictureInPicture,
                onOpenPlaylist = onOpenPlaylist,
                onOpenSettings = { onOpenPanel(PlayerPanel.SETTINGS) },
                onOpenVideoInfo = { onOpenPanel(PlayerPanel.VIDEO_INFO) },
                onOpenAbTool = onOpenAbTool,
                onPrevious = onPrevious,
                onNext = onNext,
                onToggleLock = onToggleLock,
                onSetPlaybackOrder = onSetPlaybackOrder,
                onCycleScaleMode = onCycleScaleMode,
                controlLayout = state.controlLayout,
                landscape = landscape,
                allowScreenshot = allowScreenshot,
                allowPictureInPicture = allowPictureInPicture,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        // 手势反馈：音量贴左边、亮度贴右边（跟手势所在侧一致），进度与缩放居中。
        // 保留最后一个非空手势反馈：退出动画期间 state.gestureHud 已为 null，
        // 若直接用 when(null) 会落到 Center，导致音量/亮度条"在屏幕中央一闪而过"。
        val lastGestureHud = androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf<PlayerGestureHud?>(null)
        }
        state.gestureHud?.let { lastGestureHud.value = it }
        lastGestureHud.value?.let { currentHud ->
            PlayerGestureHudOverlay(
                hud = currentHud,
                visible = state.gestureHud != null,
                onToggleAutoBrightness = gestureCallbacks.onToggleAutoBrightness,
                modifier = Modifier
                    .align(
                        when (currentHud) {
                            is PlayerGestureHud.Volume -> Alignment.CenterStart
                            is PlayerGestureHud.Brightness -> Alignment.CenterEnd
                            else -> Alignment.Center
                        },
                    )
                    .padding(horizontal = 16.dp),
            )
        }
        // 首次进入的手势提示：贴在顶栏下方，避开画面中心的播放/暂停控件；不接管触摸，手势可穿透。
        if (showGestureHint) {
            PlayerGestureHint(
                longPressSpeed = state.preferences.longPressSpeed,
                modifier = Modifier.align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 72.dp, start = 16.dp, end = 16.dp)
                    .testTag(PlayerTestTags.GESTURE_HINT),
            )
        }
        // 长按临时倍速必须有可见反馈，否则用户无从得知长按已生效（放在手势提示下方，避免重叠）。
        if (state.isTemporarySpeed) {
            PlayerSpeedBoostBadge(
                label = state.speed.displayLabel(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 120.dp),
            )
        }
        // 边界反馈：音量/亮度到顶或到底时轻震一次（同一端只震一次，避免持续嗡嗡）。
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        val boundary = when (val hud = state.gestureHud) {
            is PlayerGestureHud.Volume -> hud.fraction <= 0f || hud.fraction >= 1f
            is PlayerGestureHud.Brightness -> hud.fraction <= 0f || hud.fraction >= 1f
            else -> false
        }
        val boundaryEdge = when (val hud = state.gestureHud) {
            is PlayerGestureHud.Volume -> hud.fraction.takeIf { boundary }
            is PlayerGestureHud.Brightness -> hud.fraction.takeIf { boundary }
            else -> null
        }
        androidx.compose.runtime.LaunchedEffect(boundaryEdge) {
            if (boundaryEdge != null) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            }
        }
        transientMessage?.let { message ->
            Surface(
                modifier = Modifier.align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 80.dp),
                shape = YingLiTheme.components.componentCorner,
                color = YingLiTheme.colors.surfaceComponent,
                tonalElevation = 4.dp,
            ) {
                Text(
                    text = message.argumentRes?.let { argument ->
                        stringResource(message.messageRes, stringResource(argument))
                    } ?: stringResource(message.messageRes),
                    color = YingLiTheme.colors.textPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        PlayerStatusOverlay(
            state = state,
            onBack = onBack,
            onReplay = onReplay,
            onRetry = onRetry,
            onRecovery = onRecovery,
            onNext = onNext,
            canNavigateNext = canNavigateNext,
            modifier = Modifier.align(Alignment.Center),
        )
        if (state.overlay.controlsVisible && !state.overlay.locked && state.playback.hasTransportControls()) {
            CenterPlaybackControls(
                // 重缓冲期间播放意图仍是"播放中"：按钮不应翻成"播放"，
                // 否则拖动进度条时会看到"页面自动暂停、松手又恢复"的错觉。
                playing = state.playback.isPlayingIntent(),
                onPlay = onPlay,
                onPause = onPause,
                onPrevious = onPrevious,
                onNext = onNext,
                canNavigatePrevious = canNavigatePrevious,
                canNavigateNext = canNavigateNext,
                modifier = Modifier.align(Alignment.Center)
                    .then(if (landscape) Modifier else Modifier.padding(bottom = 96.dp))
                    .testTag(if (landscape) PlayerTestTags.LANDSCAPE_CENTER_CONTROLS else PlayerTestTags.PORTRAIT_CENTER_CONTROLS),
            )
            BottomPlaybackControls(
                state = state,
                onSeek = onSeek,
                onToggleFullscreen = onToggleFullscreen,
                onRotateVideo = onRotateVideo,
                onToggleSpeedPanel = onToggleSpeedPanel,
                onCycleScaleMode = onCycleScaleMode,
                onSetSpeed = onSetSpeed,
                onOpenPlaylist = onOpenPlaylist,
                onPictureInPicture = onPictureInPicture,
                allowPictureInPicture = allowPictureInPicture,
                onSetPlaybackOrder = onSetPlaybackOrder,
                onScreenshot = onScreenshot,
                onOpenAbTool = onOpenAbTool,
                onToggleLock = onToggleLock,
                onPrevious = onPrevious,
                onNext = onNext,
                onOpenVideoInfo = { onOpenPanel(PlayerPanel.VIDEO_INFO) },
                onSelectAudioTrack = { if (state.audioTracks.isNotEmpty()) onOpenPanel(PlayerPanel.SETTINGS) },
                onSelectSubtitleTrack = { onOpenPanel(PlayerPanel.SETTINGS) },
                onOpenSettings = { onOpenPanel(PlayerPanel.SETTINGS) },
                controlLayout = state.controlLayout,
                compact = !landscape,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .testTag(if (landscape) PlayerTestTags.LANDSCAPE_CONTROLS else PlayerTestTags.PORTRAIT_CONTROLS),
            )
        }
        ScreenshotToolCapsule(
            state = state.screenshot,
            onPreviousFrame = onPreviousScreenshotFrame,
            onCapture = onCaptureScreenshot,
            onNextFrame = onNextScreenshotFrame,
            onClose = onCloseScreenshot,
            modifier = Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = if (landscape) 112.dp else 148.dp)
                .testTag(PlayerTestTags.SCREENSHOT_CAPSULE),
        )
        (state.screenshot as? ScreenshotUiState.Preview)?.let { preview ->
            ScreenshotPreview(
                state = preview,
                onTogglePause = onToggleScreenshotPreview,
                onClose = onCloseScreenshot,
                onDelete = onDeleteScreenshot,
                modifier = Modifier.align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 16.dp, top = 64.dp)
                    .testTag(PlayerTestTags.SCREENSHOT_PREVIEW),
            )
        }
        (state.screenshot as? ScreenshotUiState.Failed)?.let {
            Text(
                text = stringResource(R.string.player_screenshot_failed),
                color = YingLiTheme.player.controlPrimary,
                modifier = Modifier.align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 80.dp),
            )
        }
        if (state.panel == PlayerPanel.SETTINGS && landscape) {
            Surface(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().widthIn(max = 340.dp)
                    .testTag(PlayerTestTags.LANDSCAPE_SETTINGS),
                color = YingLiTheme.colors.surface,
                tonalElevation = 4.dp,
            ) {
                Column(Modifier.padding(top = 16.dp)) {
                    PlayerSettingsSheet(
                        state, onSetScaleMode, onSelectAudioTrack, onSelectSubtitleTrack,
                        onSetOrder = onSetPlaybackOrder,
                        onSetRotation = onSetRotation,
                        onOpenVideoInfo = { onOpenPanel(PlayerPanel.VIDEO_INFO) },
                        onSetLayout = onSetControlLayout,
                    )
                }
            }
        }
        if (state.panel == PlayerPanel.PLAYLIST && landscape) {
            PlaylistPanel(
                queue = state.queue,
                items = emptyList(),
                pagingItems = playlistItems,
                currentMediaId = state.playback.request?.mediaId?.value,
                thumbnailRepository = thumbnailRepository,
                onSelect = onSelectPlaylistItem,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().widthIn(max = 360.dp)
                    .testTag(PlayerTestTags.LANDSCAPE_PLAYLIST),
            )
        }
        if (state.panel == PlayerPanel.SETTINGS && !landscape) {
            ModalBottomSheet(
                onDismissRequest = onClosePanel,
                modifier = Modifier.testTag(PlayerTestTags.PORTRAIT_SETTINGS),
            ) {
                PlayerSettingsSheet(
                    state, onSetScaleMode, onSelectAudioTrack, onSelectSubtitleTrack,
                    onSetOrder = onSetPlaybackOrder,
                    onSetRotation = onSetRotation,
                    onOpenVideoInfo = { onOpenPanel(PlayerPanel.VIDEO_INFO) },
                    onSetLayout = onSetControlLayout,
                )
            }
        }
        if (state.panel == PlayerPanel.PLAYLIST && !landscape) {
            ModalBottomSheet(
                onDismissRequest = onClosePanel,
                containerColor = YingLiTheme.player.canvas,
                contentColor = YingLiTheme.player.controlPrimary,
                dragHandle = { BottomSheetDefaults.DragHandle(color = YingLiTheme.player.controlSecondary) },
            ) {
                PlaylistPanel(
                    queue = state.queue,
                    items = emptyList(),
                    pagingItems = playlistItems,
                    currentMediaId = state.playback.request?.mediaId?.value,
                    thumbnailRepository = thumbnailRepository,
                    onSelect = onSelectPlaylistItem,
                    modifier = Modifier.testTag(PlayerTestTags.PORTRAIT_PLAYLIST),
                )
            }
        }
        if (state.panel == PlayerPanel.VIDEO_INFO) {
            VideoInfoDialog(state, onClosePanel)
        }
        if (state.abToolOpen) {
            AbLoopCapsule(
                state = state.abLoop,
                onSetA = { onSetAbPoint(AbPoint.A) },
                onSetB = { onSetAbPoint(AbPoint.B) },
                onClear = onClearAb,
                onClose = onCloseAbTool,
                modifier = Modifier.align(Alignment.Center)
                    .padding(bottom = if (landscape) 96.dp else 176.dp)
                    .testTag(PlayerTestTags.AB_CAPSULE),
            )
        }
    }
}

private fun PlaybackState.hasTransportControls(): Boolean = when (this) {
    is PlaybackState.Ready, is PlaybackState.Playing, is PlaybackState.Paused -> true
    // seek 造成的瞬时重缓冲仍属于"播放中"：控件必须留在组合里。
    // 否则拖动进度条时实时 seek 会让状态短暂变为 Preparing，控件被卸载、拖拽手势丢失，
    // 表现就是"进度条只能点击跳转、不能拖动"。
    is PlaybackState.Preparing -> isRebuffering
    else -> false
}

/** 播放意图：Playing 为真；seek/缓冲不足造成的重缓冲也视为"仍在播放"。 */
private fun PlaybackState.isPlayingIntent(): Boolean =
    this is PlaybackState.Playing || (this is PlaybackState.Preparing && isRebuffering)

@Composable
private fun VideoInfoDialog(state: PlayerUiState, onDismiss: () -> Unit) {
    val info = state.mediaInfo
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("视频信息") },
        text = {
            Column {
                InfoLine("标题", info?.title ?: state.title)
                InfoLine("时长", info?.durationMillis?.let(::formatInfoDuration) ?: "未知")
                InfoLine("分辨率", if (info?.width != null && info.height != null) "${info.width} x ${info.height}" else "未知")
                InfoLine("视频编码", info?.videoCodec ?: "未知")
                InfoLine("音频编码", info?.audioCodec ?: "未知")
                InfoLine("帧率", info?.frameRate?.let { "${it} fps" } ?: "未知")
                InfoLine("文件大小", info?.fileSizeBytes?.let(::formatInfoBytes) ?: "未知")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("$label: ", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        Text(value)
    }
}

private fun formatInfoDuration(value: Long): String {
    val totalSeconds = value / 1_000
    return "%02d:%02d:%02d".format(totalSeconds / 3600, (totalSeconds / 60) % 60, totalSeconds % 60)
}

private fun formatInfoBytes(value: Long): String = when {
    value >= 1_000_000_000 -> "%.1f GB".format(value / 1_000_000_000.0)
    value >= 1_000_000 -> "%.1f MB".format(value / 1_000_000.0)
    value >= 1_000 -> "%.1f KB".format(value / 1_000.0)
    else -> "$value B"
}

object PlayerTestTags {
    const val CANVAS = "player.canvas"
    const val LOADING = "player.loading"
    const val PLAY_PAUSE = "player.play_pause"
    const val PROGRESS = "player.progress"
    const val LANDSCAPE_CONTROLS = "player.controls.landscape"
    const val PORTRAIT_CONTROLS = "player.controls.portrait"
    const val LANDSCAPE_CENTER_CONTROLS = "player.center_controls.landscape"
    const val PORTRAIT_CENTER_CONTROLS = "player.center_controls.portrait"
    const val LANDSCAPE_SETTINGS = "player.settings.landscape"
    const val PORTRAIT_SETTINGS = "player.settings.portrait"
    const val SCREENSHOT_CAPSULE = "player.screenshot.capsule"
    const val SCREENSHOT_PREVIEW = "player.screenshot.preview"
    const val AB_CAPSULE = "player.ab.capsule"
    const val GESTURE_HINT = "player.gesture_hint"
    const val LANDSCAPE_PLAYLIST = "player.playlist.landscape"
    const val PORTRAIT_PLAYLIST = "player.playlist.portrait"
}
