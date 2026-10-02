package seeyuer.yingli.player.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import kotlinx.coroutines.delay
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.playback.FrameCounterState
import seeyuer.yingli.player.domain.playback.PlaybackRecoveryAction
import seeyuer.yingli.player.domain.playback.PlayerPanel
import seeyuer.yingli.player.domain.playback.AbPoint
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.PlaybackState
import seeyuer.yingli.player.domain.playback.ScreenshotUiState
import seeyuer.yingli.player.domain.playback.VideoScaleMode
import seeyuer.yingli.player.domain.playback.VideoRotation
import seeyuer.yingli.player.domain.playback.VideoMirror
import seeyuer.yingli.player.domain.playback.PlaybackOrder
import seeyuer.yingli.player.domain.playback.PlayerControlLayout
import seeyuer.yingli.player.domain.playback.frameCounterStateOf
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
    /** 后台播放开关（托盘里的状态型按钮）：true = 退到后台继续播放。 */
    onSetBackgroundPlayback: (Boolean) -> Unit = {},
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
        // 镜像翻转：与自由缩放一样只做视图层变换，不进播放管线。
        // 按 mediaId 记忆：当前播放内保留，切换媒体即复位，与缩放等会话内状态同生命周期。
        var mirror by androidx.compose.runtime.remember(state.playback.request?.mediaId) {
            androidx.compose.runtime.mutableStateOf(VideoMirror.Default)
        }
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
        // 镜像翻转动画：从 1f 动画到 -1f 必然经过 0，画面先压扁再朝另一侧展开，
        // 视觉上就是"翻面"，无需额外做 3D 旋转；时长与缩放过渡一致，两者手感统一。
        val mirrorScaleX by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (mirror.horizontal) -1f else 1f,
            animationSpec = androidx.compose.animation.core.tween(ZOOM_TRANSITION_MILLIS),
            label = "mirror-x",
        )
        val mirrorScaleY by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (mirror.vertical) -1f else 1f,
            animationSpec = androidx.compose.animation.core.tween(ZOOM_TRANSITION_MILLIS),
            label = "mirror-y",
        )
        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                // 镜像与缩放都是倍数，直接相乘；平移量不受翻转影响（翻转不改变画面中心所在位置）。
                scaleX = zoomScale * mirrorScaleX
                scaleY = zoomScale * mirrorScaleY
                translationX = zoomOffsetX * size.width * zoomScale
                translationY = -zoomOffsetY * size.height * zoomScale
            },
        ) {
            VideoRotationStage(
                rotation = state.rotation,
                videoAspect = state.videoAspect(),
                fillScreen = state.fillScreen,
                // 镜像与旋转/缩放一样只作用于视图层级：SurfaceView 的画面由 SurfaceFlinger 单独合成，
                // 不会跟随父级 graphicsLayer 的负缩放，因此只要镜像生效就必须切到 TextureView 输出，
                // 否则按钮状态变了、画面却纹丝不动。
                zoomActive = state.zoom.isActive || mirror.isActive,
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
        // 截图胶囊在组合期占用底栏的工具托盘行（BottomPlaybackControls 的 screenshotTool 插槽），
        // 所以只要它在场，底栏就必须留在组合里：截图工具本身是浮层，和帧数胶囊一样不吃控件自动隐藏，
        // 否则在截图模式里单击画面收起控件会把胶囊一起藏掉。
        // 注意两个条件是"或"：胶囊在场不等于控件在场（控件那一侧仍受自动隐藏与传输控件可用性约束），
        // 但两者都会把底栏拉进组合，而底栏内三段各自的收起逻辑不变。
        val controlsOnScreen = state.overlay.controlsVisible && state.playback.hasTransportControls()
        val screenshotCapsuleVisible = state.screenshot.isCapsuleVisible()
        if (!state.overlay.locked && (controlsOnScreen || screenshotCapsuleVisible)) {
            if (controlsOnScreen) {
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
            }
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
                mirror = mirror,
                onToggleMirrorHorizontal = { mirror = mirror.toggleHorizontal() },
                onToggleMirrorVertical = { mirror = mirror.toggleVertical() },
                backgroundPlaybackEnabled = state.preferences.backgroundPlaybackEnabled,
                onToggleBackgroundPlayback = {
                    onSetBackgroundPlayback(!state.preferences.backgroundPlaybackEnabled)
                },
                onOpenSettings = { onOpenPanel(PlayerPanel.SETTINGS) },
                controlLayout = state.controlLayout,
                // 截图胶囊交给底栏渲染在托盘行那一格：动画与居中都由底栏负责（从右向左滑入），
                // 它不再是一个自己定位的浮层，所以这里只挂测试标记，不带任何位置修饰符。
                screenshotTool = {
                    ScreenshotToolCapsule(
                        state = state.screenshot,
                        onPreviousFrame = onPreviousScreenshotFrame,
                        onCapture = onCaptureScreenshot,
                        onNextFrame = onNextScreenshotFrame,
                        onClose = onCloseScreenshot,
                        modifier = Modifier.testTag(PlayerTestTags.SCREENSHOT_CAPSULE),
                    )
                },
                compact = !landscape,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .testTag(if (landscape) PlayerTestTags.LANDSCAPE_CONTROLS else PlayerTestTags.PORTRAIT_CONTROLS),
            )
        }
        // 帧数胶囊**下移到顶栏下方**：顶栏右侧那排快捷按钮（音轨/字幕/设置）在竖屏下占满右上角，
        // 帧数文本一长（`488912 / 802008` 这种）按整屏居中后右端就会被压住（真机截图证实）。
        // 下沉之后它与顶栏所有按钮既不共享水平带、也不再共享点击区域，从根上避开重叠；
        // 顶栏标题因此不必再为它让位。
        //
        // 顶部内边距 = 状态栏 inset（胶囊自己的 `windowInsetsPadding(safeDrawing)` 已经加过一次）
        // + 顶栏自身高度（PlayerTopBar 的上下内边距 + 按钮圆径）
        // + PlayerFrameCounterTopGap（顶栏底边到胶囊的间距）。
        // 横竖屏用的是同一个公式：顶栏高度只由按钮尺寸与内边距决定，与方向无关，
        // 所以横屏下取到的值完全相同（区别只是状态栏 inset 通常为 0）。
        // 它跟截图胶囊一样不受控件自动隐藏影响：截图工具本身就是浮层，隐藏控件不应把工具一起藏掉。
        val screenshotActive = state.isScreenshotToolActive()
        val density = LocalDensity.current
        val frameCounterTopPadding = PlayerTopBarContentHeight +
            with(density) { WindowInsets.safeDrawing.getTop(density).toDp() } +
            PlayerFrameCounterTopGap
        AnimatedVisibility(
            // 可见性只由「截图工具是否打开」决定：三段收起与它同步，自动隐藏不参与。
            visible = screenshotActive,
            modifier = Modifier.align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = frameCounterTopPadding)
                // 宽度兜底：上限取整屏宽度的八成。帧数胶囊本身按内容取宽（通常 150~210dp），
                // 这个上限只在极窄屏 / 最大字体下才会生效，避免它横向铺满整屏压到别的浮层。
                .widthIn(max = maxWidth * PlayerFrameCounterMaxWidthFraction)
                .testTag(PlayerTestTags.FRAME_COUNTER),
            enter = fadeIn(tween(TRANSPORT_SECTION_TRANSITION_MILLIS)),
            exit = fadeOut(tween(TRANSPORT_SECTION_TRANSITION_MILLIS)),
        ) {
            // 帧率不可用时 state.frameCounter() 恒为 null → 整个胶囊不出现：
            // 宁可不出这个胶囊，也不显示编造的帧号（口径与逐帧步进一致）。
            state.frameCounter()?.let { counter -> FrameCounterCapsule(counter) }
        }
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
                        onScreenshot = onScreenshot,
                        onOpenAbTool = onOpenAbTool,
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
                    onScreenshot = onScreenshot,
                    onOpenAbTool = onOpenAbTool,
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

/**
 * 帧数胶囊要显示的两个数：帧率取自媒体格式（Media3 `Format.frameRate`，经 `PlaybackMediaInfo` 冒泡到 UI），
 * 时长为时间轴总时长，位置用「当前显示位置」——拖动进度条时它跟手，帧号也就跟手。
 *
 * 计算本身是纯逻辑，放在域层 [frameCounterStateOf]；这里只负责把三份状态取出来。
 */
private fun PlayerUiState.frameCounter(): FrameCounterState? = frameCounterStateOf(
    positionMillis = displayedPositionMillis,
    durationMillis = playback.timeline.durationMillis ?: mediaInfo?.durationMillis,
    frameRate = mediaInfo?.frameRate,
)

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
    const val FRAME_COUNTER = "player.frame_counter"
    const val AB_CAPSULE = "player.ab.capsule"
    const val GESTURE_HINT = "player.gesture_hint"
    const val LANDSCAPE_PLAYLIST = "player.playlist.landscape"
    const val PORTRAIT_PLAYLIST = "player.playlist.portrait"
}

/**
 * 帧数胶囊的宽度上限占整屏宽度的比例。
 *
 * 帧数胶囊本身按内容取宽（`488912 / 802008` 这种约 150dp），正常机型根本碰不到这个上限；
 * 它只是**兜底**：防止极端字号/极窄屏下胶囊横向铺满整屏、压到别的浮层。
 * 真正的避让靠"下移到顶栏下方"完成，不靠这个比例。
 */
private const val PlayerFrameCounterMaxWidthFraction = 0.8f
