package seeyuer.yingli.player.feature.player

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.R
import seeyuer.yingli.player.domain.playback.ScreenshotDeleteFailure
import seeyuer.yingli.player.domain.playback.PlaybackCommandResult
import seeyuer.yingli.player.domain.playback.PlaybackConnectionState
import seeyuer.yingli.player.domain.playback.DeviceControlGateway
import seeyuer.yingli.player.domain.playback.PlaybackSessionClient
import seeyuer.yingli.player.domain.playback.PlaybackSessionCommand
import seeyuer.yingli.player.domain.playback.PlaybackSessionId
import seeyuer.yingli.player.domain.playback.PlaybackOpenRequest
import seeyuer.yingli.player.domain.playback.PlaybackPhase
import seeyuer.yingli.player.domain.playback.PlaybackSessionSnapshot
import seeyuer.yingli.player.domain.playback.PlaybackOrder
import seeyuer.yingli.player.domain.playback.PlayerControlLayout
import seeyuer.yingli.player.domain.playback.PlayerControlLayoutRepository
import seeyuer.yingli.player.domain.playback.ScreenshotFileGateway
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackState
import seeyuer.yingli.player.domain.playback.PlaybackSessionEvent
import seeyuer.yingli.player.domain.playback.PictureInPictureGateway
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.PlaybackQueue
import seeyuer.yingli.player.domain.playback.PlaybackQueueRepository
import seeyuer.yingli.player.domain.playback.PlaybackQueueSource
import seeyuer.yingli.player.domain.playback.AbLoopLimiter
import seeyuer.yingli.player.domain.playback.AbLoopState
import seeyuer.yingli.player.domain.playback.AbPoint
import seeyuer.yingli.player.domain.playback.PlayerOverlayEvent
import seeyuer.yingli.player.domain.playback.PlayerOverlayReducer
import seeyuer.yingli.player.domain.playback.PlayerOverlayState
import seeyuer.yingli.player.domain.playback.PlayerPanel
import seeyuer.yingli.player.domain.playback.PlayerPanelEvent
import seeyuer.yingli.player.domain.playback.PlayerPanelReducer
import seeyuer.yingli.player.domain.playback.PlayerPreferenceRepository
import seeyuer.yingli.player.domain.playback.PlayerPreferences
import seeyuer.yingli.player.domain.playback.ScreenshotGateway
import seeyuer.yingli.player.domain.playback.ScreenshotResult
import seeyuer.yingli.player.domain.playback.ScreenshotUiEvent
import seeyuer.yingli.player.domain.playback.ScreenshotUiReducer
import seeyuer.yingli.player.domain.playback.ScreenshotUiState
import seeyuer.yingli.player.domain.playback.TrackChoice
import seeyuer.yingli.player.domain.playback.TrackPreference
import seeyuer.yingli.player.domain.playback.TrackPreferenceRepository
import seeyuer.yingli.player.domain.playback.VideoScaleMode
import seeyuer.yingli.player.domain.playback.VideoZoom
import seeyuer.yingli.player.domain.playback.VideoRotation
import seeyuer.yingli.player.domain.playback.FullscreenPolicy
import seeyuer.yingli.player.domain.playback.RequestedOrientation
import seeyuer.yingli.player.domain.playback.WindowPlaybackGateway
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryPagingRepository
import seeyuer.yingli.player.domain.security.VaultItemId

data class PlayerUiState(
    val playback: PlaybackState = PlaybackState.Idle,
    val connection: PlaybackConnectionState = PlaybackConnectionState.CONNECTING,
    val title: String = "",
    val displayedPositionMillis: Long = 0,
    val sourceUnavailable: Boolean = false,
    val audioTracks: List<TrackChoice> = emptyList(),
    val subtitleTracks: List<TrackChoice> = emptyList(),
    val speed: PlaybackSpeed = PlaybackSpeed.Normal,
    val scaleMode: VideoScaleMode = VideoScaleMode.FIT,
    val overlay: PlayerOverlayState = PlayerOverlayState(),
    val panel: PlayerPanel = PlayerPanel.NONE,
    val abLoop: AbLoopState = AbLoopState(),
    val abToolOpen: Boolean = false,
    val queue: PlaybackQueue? = null,
    val preferences: PlayerPreferences = PlayerPreferences(),
    val screenshot: ScreenshotUiState = ScreenshotUiState.Idle,
    val playbackOrder: PlaybackOrder = PlaybackOrder.SEQUENCE,
    val rotation: VideoRotation = VideoRotation.Default,
    /** 来自窗口网关的真实全屏状态，不做乐观更新。 */
    val isFullscreen: Boolean = false,
    /** 全屏时是否需要视图层把画面填满（竖屏播放竖版视频）。 */
    val fillScreen: Boolean = false,
    val mediaInfo: seeyuer.yingli.player.domain.playback.PlaybackMediaInfo? = null,
    val controlLayout: PlayerControlLayout = PlayerControlLayout(),
    /** 画面手势的即时反馈；null 表示当前没有手势反馈。 */
    val gestureHud: PlayerGestureHud? = null,
    /** 自由缩放（双指捏合），只做视图层变换，随媒体切换重置。 */
    val zoom: VideoZoom = VideoZoom.Default,
    /** 长按临时倍速进行中：UI 显示"快进中"，且绝不写入媒体偏好。 */
    val isTemporarySpeed: Boolean = false,
)

/** 视频宽高比；宽高缺失或非法时返回 null，调用方按“形状未知”处理。 */
internal fun PlayerUiState.videoAspect(): Float? {
    val width = mediaInfo?.width
    val height = mediaInfo?.height
    return if (width != null && height != null && width > 0 && height > 0) width.toFloat() / height else null
}

sealed interface PlayerUiEvent {
    /**
     * 统一瞬时反馈：命令结果和状态变化都走这一条通道（规格 §14.4）。
     * 文案用字符串资源 id 表达，不在事件里传裸文案；[argumentRes] 供
     * 「播放顺序：随机播放」这类需要拼接一条资源文案的消息使用。
     */
    data class TransientMessage(
        @StringRes val messageRes: Int,
        @StringRes val argumentRes: Int? = null,
    ) : PlayerUiEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    private val sessionClient: PlaybackSessionClient,
    private val dispatchers: AppDispatchers,
    private val playerPreferenceRepository: PlayerPreferenceRepository? = null,
    private val trackPreferenceRepository: TrackPreferenceRepository? = null,
    private val screenshotGateway: ScreenshotGateway? = null,
    private val pictureInPictureGateway: PictureInPictureGateway? = null,
    private val playbackQueueRepository: PlaybackQueueRepository? = null,
    private val controlLayoutRepository: PlayerControlLayoutRepository? = null,
    private val libraryRepository: LibraryPagingRepository? = null,
    private val windowPlaybackGateway: WindowPlaybackGateway? = null,
    private val deviceControlGateway: DeviceControlGateway? = null,
    private val ownsSessionClient: Boolean = true,
) : ViewModel() {
    private val events = Channel<PlayerUiEvent>(Channel.BUFFERED)
    val event: Flow<PlayerUiEvent> = events.receiveAsFlow()
    private val title = MutableStateFlow("")
    private val sourceUnavailable = MutableStateFlow(false)
    private val overlay = MutableStateFlow(PlayerOverlayState())
    private val panel = MutableStateFlow(PlayerPanel.NONE)
    private val abLoop = MutableStateFlow(AbLoopState())
    private val abToolOpen = MutableStateFlow(false)
    private val abLimiter = AbLoopLimiter()
    private val screenshot = MutableStateFlow<ScreenshotUiState>(ScreenshotUiState.Idle)

    /** 画面旋转是视图层变换：不进播放管线，但按媒体持久化。 */
    private val rotation = MutableStateFlow(VideoRotation.Default)
    private val isFullscreen = MutableStateFlow(false)
    private val fillScreen = MutableStateFlow(false)

    /** 画面手势：HUD 反馈、自由缩放、长按临时倍速。 */
    private val gestureHud = MutableStateFlow<PlayerGestureHud?>(null)
    private val zoom = MutableStateFlow(VideoZoom.Default)
    private val isTemporarySpeed = MutableStateFlow(false)
    private var gestureHudHideJob: Job? = null

    /** 手势起点值：拖动期间用"起点 + 位移比例"计算目标值，避免逐帧累加误差。 */
    private var volumeAnchor = 0f
    private var brightnessAnchor = 0f
    private var zoomAnchor: VideoZoom = VideoZoom.Default
    private var temporarySpeedAnchor: PlaybackSpeed? = null
    private var seekAnchorMillis = 0L

    private var overlayHideJob: Job? = null
    private var screenshotExpiryJob: Job? = null
    private var queueBuildJob: Job? = null
    private var activeQueueSource: PlaybackQueueSource? = null

    override fun onCleared() {
        if (ownsSessionClient) (sessionClient as? AutoCloseable)?.close()
        super.onCleared()
    }
    private val projectedPlayback: Flow<Pair<PlaybackState, Long>> = sessionClient.snapshot.flatMapLatest { snapshot ->
        val state = snapshot.toPlaybackState()
        if (state is PlaybackState.Playing) projectedPlayingState(state) else flowOf(state to state.timeline.positionMillis)
    }

    private val baseState = combine(
        projectedPlayback,
        sessionClient.snapshot,
        title,
        sourceUnavailable,
    ) { (playback, displayedPosition), snapshot, currentTitle, unavailable ->
        PlayerUiState(
            playback = playback,
            connection = snapshot.connectionState,
            title = currentTitle.ifBlank { snapshot.title.orEmpty() },
            displayedPositionMillis = displayedPosition,
            sourceUnavailable = unavailable,
            playbackOrder = snapshot.queue?.order ?: PlaybackOrder.SEQUENCE,
            mediaInfo = snapshot.mediaInfo,
        )
    }
    private val advancedState = sessionClient.snapshot
        .let { snapshots -> snapshots.map { AdvancedState(it.audioTracks, it.subtitleTracks, it.speed, it.scaleMode) } }
    private val preferences = playerPreferenceRepository?.playerPreferences ?: flowOf(PlayerPreferences())
    private val queue = playbackQueueRepository?.queue ?: flowOf(null)
    val playlistPagingData = queue.flatMapLatest { currentQueue ->
        if (currentQueue == null || libraryRepository == null) {
            flowOf(PagingData.empty<PlaylistMediaItem>())
        } else {
            Pager(
                config = PagingConfig(
                    pageSize = PLAYLIST_PAGE_SIZE,
                    initialLoadSize = PLAYLIST_PAGE_SIZE,
                    prefetchDistance = PLAYLIST_PREFETCH_DISTANCE,
                    maxSize = PLAYLIST_MAX_SIZE,
                    enablePlaceholders = false,
                ),
                pagingSourceFactory = { PlaylistPagingSource(currentQueue.mediaIds, libraryRepository) },
            ).flow
        }
    }.cachedIn(viewModelScope)
    private val overlayAndPanel = combine(overlay, panel) { currentOverlay, currentPanel ->
        currentOverlay to currentPanel
    }
    private val overlayPanelAbAndQueue = combine(overlayAndPanel, abLoop, abToolOpen, queue) { overlayPanel, currentAb, toolOpen, currentQueue ->
        Quadruple(overlayPanel.first, overlayPanel.second, currentAb, toolOpen, currentQueue)
    }
    private val preferencesAndQueue = combine(preferences, queue) { currentPreferences, currentQueue ->
        currentPreferences to currentQueue
    }
    private val fullscreenPolicy = FullscreenPolicy()

    private val screenshotLayoutAndRotation = combine(
        screenshot,
        controlLayoutRepository?.layout ?: flowOf(PlayerControlLayout()),
        rotation,
        isFullscreen,
        fillScreen,
    ) { currentScreenshot, currentLayout, currentRotation, fullscreen, fill ->
        PlayerSurfaceState(currentScreenshot, currentLayout, currentRotation, fullscreen, fill)
    }

    /** 手势相关子状态：单独合成一层，避免外层 combine 超过 5 个参数。 */
    private val surfaceAndGesture = combine(
        screenshotLayoutAndRotation,
        gestureHud,
        zoom,
        isTemporarySpeed,
    ) { surface, hud, currentZoom, temporarySpeed ->
        GestureSurfaceState(surface, hud, currentZoom, temporarySpeed)
    }

    val state: StateFlow<PlayerUiState> = combine(
        baseState,
        advancedState,
        overlayPanelAbAndQueue,
        preferencesAndQueue,
        surfaceAndGesture,
    ) { base, advanced, overlayPanelAbAndQueueState, preferencesAndQueueState, gestureSurface ->
        val (currentOverlay, currentPanel, currentAb, currentAbToolOpen, currentQueue) = overlayPanelAbAndQueueState
        val (currentPreferences, _) = preferencesAndQueueState
        val surfaceState = gestureSurface.surface
        base.copy(
            audioTracks = advanced.audioTracks,
            subtitleTracks = advanced.subtitleTracks,
            speed = advanced.speed,
            scaleMode = advanced.scaleMode,
            overlay = currentOverlay,
            panel = currentPanel,
            abLoop = currentAb,
            abToolOpen = currentAbToolOpen,
            queue = currentQueue,
            preferences = currentPreferences,
            screenshot = surfaceState.screenshot,
            controlLayout = surfaceState.layout,
            rotation = surfaceState.rotation,
            isFullscreen = surfaceState.isFullscreen,
            fillScreen = surfaceState.fillScreen,
            gestureHud = gestureSurface.hud,
            zoom = gestureSurface.zoom,
            isTemporarySpeed = gestureSurface.temporarySpeed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), PlayerUiState())

    fun open(
        mediaId: String,
        sourceContext: PlaybackSourceContext,
        queueSource: PlaybackQueueSource? = null,
        preserveQueue: Boolean = false,
    ) {
        val id = runCatching { MediaItemId(mediaId) }.getOrNull() ?: return
        if (sessionClient.snapshot.value.mediaId == id &&
            (queueSource == null || queueSource == activeQueueSource)
        ) return
        panel.value = PlayerPanel.NONE
        closeScreenshot()
        resetAbSession()
        sourceUnavailable.value = false
        val effectiveQueueSource = if (preserveQueue) {
            activeQueueSource ?: PlaybackQueueSource.allVideos()
        } else {
            queueSource ?: PlaybackQueueSource.allVideos()
        }
        activeQueueSource = effectiveQueueSource
        sourceUnavailable.value = false
        sessionClient.dispatch(
            PlaybackSessionCommand.Open(
                PlaybackOpenRequest(
                    sessionId = sessionClient.snapshot.value.sessionId ?: PlaybackSessionId("activity-session"),
                    mediaId = id,
                    sourceContext = sourceContext,
                ),
            ),
        )
        if (!preserveQueue) {
            replaceQueueForSource(id, effectiveQueueSource)
        }
        restoreTrackPreference(id)
    }

    fun openVault(itemId: String, displayTitle: String) {
        val id = runCatching { VaultItemId(itemId) }.getOrNull() ?: return
        panel.value = PlayerPanel.NONE
        closeScreenshot()
        resetAbSession()
        sourceUnavailable.value = false
        title.value = displayTitle
        // Vault 播放没有按媒体偏好，画面旋转回到默认，避免沿用上一个视频的角度。
        rotation.value = VideoRotation.Default
        sessionClient.dispatch(PlaybackSessionCommand.OpenVault(id, displayTitle))
    }

    fun closeVault() {
        sessionClient.dispatch(PlaybackSessionCommand.Stop)
        sourceUnavailable.value = false
    }

    fun play(): PlaybackCommandResult = dispatchResult(PlaybackSessionCommand.Play)
    fun pause(): PlaybackCommandResult = dispatchResult(PlaybackSessionCommand.Pause)
    fun seekTo(positionMillis: Long): PlaybackCommandResult = dispatchResult(PlaybackSessionCommand.Seek(clampSeek(positionMillis)))
    fun replay(): PlaybackCommandResult {
        return dispatchResult(PlaybackSessionCommand.Play)
    }
    fun retry(): PlaybackCommandResult = dispatchResult(PlaybackSessionCommand.Retry)
    fun seekBackward(): PlaybackCommandResult = seekTo(state.value.displayedPositionMillis - doubleTapSeekMillis())

    fun seekForward(): PlaybackCommandResult = seekTo(state.value.displayedPositionMillis + doubleTapSeekMillis())

    /** 双击快进/快退的步长来自设置（5/10/15/30 秒），不再是硬编码常量。 */
    private fun doubleTapSeekMillis(): Long =
        state.value.preferences.gestureDoubleTapSeekMillis.toLong()

    fun next(): PlaybackCommandResult {
        return dispatchResult(PlaybackSessionCommand.Next)
    }

    fun previous(): PlaybackCommandResult {
        // 两种口径由设置决定：默认"先回本集开头"（超过 5 秒时），也可设为"永远直接切上一项"。
        // 规则本身在域层纯函数里，客户端与媒体会话共用同一判定。
        return if (
            seeyuer.yingli.player.domain.playback.shouldRestartCurrentItemOnPrevious(
                positionMillis = state.value.displayedPositionMillis,
                previousRestartsCurrentItem = state.value.preferences.previousRestartsCurrentItem,
            )
        ) {
            seekTo(0L)
        } else {
            dispatchResult(PlaybackSessionCommand.Previous)
        }
    }

    fun selectQueueItem(index: Int): PlaybackCommandResult {
        val current = state.value.queue ?: return PlaybackCommandResult.Rejected(seeyuer.yingli.player.domain.playback.PlaybackCommandRejection.NO_CANDIDATE)
        val mediaId = current.mediaIds.getOrNull(index) ?: return PlaybackCommandResult.Rejected(seeyuer.yingli.player.domain.playback.PlaybackCommandRejection.NO_CANDIDATE)
        viewModelScope.launch { playbackQueueRepository?.setQueue(current.copy(currentIndex = index)) }
        open(
            mediaId.value,
            state.value.playback.request?.sourceContext ?: PlaybackSourceContext.HOME,
            preserveQueue = true,
        )
        closePanel()
        return PlaybackCommandResult.Accepted
    }

    fun openAbTool() {
        panel.value = PlayerPanel.NONE
        closeScreenshot()
        abToolOpen.value = true
        registerInteraction()
    }

    fun closeAbTool() {
        abToolOpen.value = false
        scheduleOverlayHide()
    }

    fun setAbPoint(point: AbPoint): Boolean {
        val timeline = state.value.playback.timeline
        val duration = timeline.durationMillis ?: return false
        val update = abLimiter.setPoint(abLoop.value, point, state.value.displayedPositionMillis, duration, null)
        val next = update.getOrNull() ?: return false
        abLoop.value = next
        registerInteraction()
        return true
    }

    fun clearAb() {
        abLoop.value = abLimiter.clear()
    }

    fun setSpeed(value: PlaybackSpeed): PlaybackCommandResult {
        val result = dispatchResult(PlaybackSessionCommand.SetSpeed(value))
        state.value.playback.request?.mediaId?.let { mediaId ->
            viewModelScope.launch {
                val current = trackPreferenceRepository?.trackPreferences?.first()?.resolve(mediaId)
                    ?: TrackPreference()
                trackPreferenceRepository?.setForMedia(mediaId, current.copy(speed = value, scaleMode = state.value.scaleMode))
            }
        }
        return result
    }

    fun setScaleMode(value: VideoScaleMode): PlaybackCommandResult {
        val result = dispatchResult(PlaybackSessionCommand.SetScale(value))
        if (result == PlaybackCommandResult.Accepted) {
            state.value.playback.request?.mediaId?.let { mediaId ->
                persistTrackPreference(mediaId) { copy(scaleMode = value) }
            }
            // 与播放顺序一致：图标、短文本、设置值三处同步反映当前比例。
            events.trySend(
                PlayerUiEvent.TransientMessage(
                    messageRes = R.string.player_scale_state,
                    argumentRes = videoScaleModeLabelRes(value),
                ),
            )
        }
        return result
    }

    /** 底栏快捷按钮：在适应 → 裁剪 → 拉伸之间循环。 */
    fun cycleScaleMode(): PlaybackCommandResult {
        val modes = VideoScaleMode.entries
        val next = modes[(modes.indexOf(state.value.scaleMode) + 1) % modes.size]
        return setScaleMode(next)
    }

    fun setPlaybackOrder(value: PlaybackOrder): PlaybackCommandResult {
        val result = dispatchResult(PlaybackSessionCommand.SetOrder(value))
        if (result == PlaybackCommandResult.Accepted) {
            // 规格 §18.4：图标、短文本、设置值三处必须同步反映当前播放顺序。
            events.trySend(
                PlayerUiEvent.TransientMessage(
                    messageRes = R.string.player_order_state,
                    argumentRes = playbackOrderLabelRes(value),
                ),
            )
        }
        return result
    }

    fun setControlLayout(value: PlayerControlLayout) {
        viewModelScope.launch { controlLayoutRepository?.set(value) }
    }

    /** 底栏快捷按钮：顺时针转到下一个画面角度。 */
    fun rotateVideo() {
        setRotation(state.value.rotation.next())
    }

    /** 设置画面旋转角度并按媒体记住；与播放顺序一样给出统一瞬时反馈。 */
    fun setRotation(value: VideoRotation) {
        rotation.value = value
        state.value.playback.request?.mediaId?.let { mediaId ->
            persistTrackPreference(mediaId) { copy(rotation = value) }
        }
        events.trySend(
            PlayerUiEvent.TransientMessage(
                messageRes = R.string.player_rotation_state,
                argumentRes = videoRotationLabelRes(value),
            ),
        )
    }

    /**
     * 全屏切换：按 [FullscreenPolicy] 决定方向请求与是否填满，然后分别交给窗口网关执行。
     * 只有系统确认成功才更新状态；失败走统一瞬时反馈，不改变已确认状态。
     */
    fun toggleFullscreen() {
        val gateway = windowPlaybackGateway
        val current = state.value
        val plan = fullscreenPolicy.toggle(
            isFullscreen = current.isFullscreen,
            videoAspect = current.videoAspect(),
            portraitWindow = gateway?.state?.value?.isPortrait ?: true,
            scaleMode = current.scaleMode,
        )
        if (gateway == null) {
            isFullscreen.value = plan.isFullscreen
            fillScreen.value = plan.fillScreen
            return
        }
        // 先请求方向：方向失败就整体不生效，避免出现“系统栏藏了但方向没变”的半成品状态。
        val orientationResult = gateway.requestOrientation(plan.orientation)
        if (orientationResult.isFailure) {
            events.trySend(PlayerUiEvent.TransientMessage(R.string.player_orientation_failed))
            return
        }
        val fullscreenResult = gateway.setFullscreen(plan.isFullscreen)
        if (fullscreenResult.isFailure) {
            events.trySend(PlayerUiEvent.TransientMessage(R.string.player_fullscreen_failed))
            return
        }
        fillScreen.value = plan.fillScreen
    }

    /** 离开播放页时调用：恢复系统栏与方向，避免全屏状态泄漏到其他页面。 */
    fun exitFullscreen() {
        val gateway = windowPlaybackGateway ?: return
        if (!state.value.isFullscreen) return
        gateway.setFullscreen(false)
        gateway.requestOrientation(RequestedOrientation.SENSOR)
        fillScreen.value = false
    }
    fun selectAudioTrack(id: String): PlaybackCommandResult {
        val track = state.value.audioTracks.firstOrNull { it.id == id }
        val result = if (track == null) PlaybackCommandResult.Rejected(seeyuer.yingli.player.domain.playback.PlaybackCommandRejection.TRACK_UNAVAILABLE)
        else dispatchResult(PlaybackSessionCommand.SelectTrack(seeyuer.yingli.player.domain.playback.TrackSelection(seeyuer.yingli.player.domain.playback.TrackType.AUDIO, track.toFingerprint())))
        if (result == PlaybackCommandResult.Accepted) {
            state.value.playback.request?.mediaId?.let { mediaId ->
                state.value.audioTracks.firstOrNull { it.id == id }?.language?.let { language ->
                    persistTrackPreference(mediaId) { copy(audioLanguage = language) }
                }
            }
        }
        return result
    }

    fun selectSubtitleTrack(id: String?): PlaybackCommandResult {
        val track = id?.let { value -> state.value.subtitleTracks.firstOrNull { it.id == value } }
        val result = dispatchResult(PlaybackSessionCommand.SelectTrack(seeyuer.yingli.player.domain.playback.TrackSelection(seeyuer.yingli.player.domain.playback.TrackType.SUBTITLE, track?.toFingerprint())))
        if (result == PlaybackCommandResult.Accepted) {
            state.value.playback.request?.mediaId?.let { mediaId ->
                val language = id?.let { selected -> state.value.subtitleTracks.firstOrNull { it.id == selected }?.language }
                persistTrackPreference(mediaId) { copy(subtitleLanguage = language, subtitlesEnabled = id != null) }
            }
        }
        return result
    }

    fun toggleOverlay() {
        overlay.value = PlayerOverlayReducer.reduce(overlay.value, PlayerOverlayEvent.Tap(0))
        // 倍速档位条长在底栏行内：控件收起时它就不可见了，把编辑态一并收掉，
        // 避免下次唤出控件时底栏还停在档位条上。
        if (!overlay.value.controlsVisible) closeSpeedPanel()
        if (overlay.value.controlsVisible) scheduleOverlayHide()
    }

    /** 倍速档位条就地展开/收起（再点一次倍速按钮即收起）。 */
    fun toggleSpeedPanel() {
        if (panel.value == PlayerPanel.SPEED) {
            closePanel()
        } else {
            openPanel(PlayerPanel.SPEED)
        }
    }

    private fun closeSpeedPanel() {
        if (panel.value == PlayerPanel.SPEED) panel.value = PlayerPanel.NONE
    }

    fun toggleLock() {
        panel.value = PlayerPanel.NONE
        abToolOpen.value = false
        closeScreenshot()
        overlay.value = PlayerOverlayReducer.reduce(overlay.value, PlayerOverlayEvent.ToggleLock)
        // 锁定态只保留解锁入口，且和普通控件一样吃 3 秒自动隐藏。
        events.trySend(
            PlayerUiEvent.TransientMessage(
                if (overlay.value.locked) R.string.player_locked else R.string.player_unlocked,
            ),
        )
        scheduleOverlayHide()
    }

    fun openPanel(value: PlayerPanel) {
        abToolOpen.value = false
        closeScreenshot()
        panel.value = PlayerPanelReducer.reduce(panel.value, PlayerPanelEvent.Open(value))
    }

    fun closePanel() {
        panel.value = PlayerPanelReducer.reduce(panel.value, PlayerPanelEvent.Close)
        scheduleOverlayHide()
    }

    fun registerInteraction() {
        overlay.value = PlayerOverlayReducer.reduce(overlay.value, PlayerOverlayEvent.Interaction(0))
        scheduleOverlayHide()
    }

    fun armScreenshot() {
        if (screenshotGateway == null || !state.value.playback.supportsScreenshot()) return
        panel.value = PlayerPanel.NONE
        abToolOpen.value = false
        screenshot.value = ScreenshotUiReducer.reduce(screenshot.value, ScreenshotUiEvent.Arm)
        registerInteraction()
    }

    fun captureScreenshot() {
        val gateway = screenshotGateway ?: return
        if (screenshot.value != ScreenshotUiState.Armed) return
        screenshot.value = ScreenshotUiReducer.reduce(screenshot.value, ScreenshotUiEvent.CaptureStarted)
        val captureMediaId = state.value.playback.request?.mediaId
        viewModelScope.launch {
            val result = gateway.capture(state.value.title, state.value.displayedPositionMillis, state.value.rotation)
            if (captureMediaId != state.value.playback.request?.mediaId || screenshot.value != ScreenshotUiState.Capturing) {
                return@launch
            }
            screenshot.value = ScreenshotUiReducer.reduce(
                screenshot.value,
                ScreenshotUiEvent.CaptureCompleted(result),
            )
            if (result is ScreenshotResult.Saved) scheduleScreenshotExpiry()
        }
    }

    fun stepScreenshotFrame(forward: Boolean) {
        if (screenshot.value != ScreenshotUiState.Armed) return
        dispatchResult(PlaybackSessionCommand.Pause)
        val offset = if (forward) FRAME_STEP_FALLBACK_MILLIS else -FRAME_STEP_FALLBACK_MILLIS
        dispatchResult(PlaybackSessionCommand.SeekBy(offset))
        registerInteraction()
    }

    fun toggleScreenshotExpiry() {
        screenshot.value = ScreenshotUiReducer.reduce(screenshot.value, ScreenshotUiEvent.ToggleExpiryPause)
        val preview = screenshot.value as? ScreenshotUiState.Preview ?: return
        if (preview.expiryPaused) screenshotExpiryJob?.cancel() else scheduleScreenshotExpiry()
    }

    fun closeScreenshot() {
        screenshotExpiryJob?.cancel()
        screenshotExpiryJob = null
        screenshot.value = ScreenshotUiReducer.reduce(screenshot.value, ScreenshotUiEvent.Close)
        scheduleOverlayHide()
    }

    fun deleteScreenshot() {
        val preview = screenshot.value as? ScreenshotUiState.Preview ?: run {
            return
        }
        val uri = preview.uri
        closeScreenshot()
        if (uri.isBlank()) return
        viewModelScope.launch {
            val result = (screenshotGateway as? ScreenshotFileGateway)?.delete(uri)
                ?: Result.failure(IllegalStateException(ScreenshotDeleteFailure.FAILED.code))
            result.fold(
                onSuccess = {
                    events.send(PlayerUiEvent.TransientMessage(R.string.player_screenshot_deleted))
                },
                onFailure = { error ->
                    val messageRes = when (error.message) {
                        ScreenshotDeleteFailure.PERMISSION_DENIED.code ->
                            R.string.player_screenshot_delete_permission_denied
                        else -> R.string.player_screenshot_delete_failed
                    }
                    events.send(PlayerUiEvent.TransientMessage(messageRes))
                },
            )
        }
    }

    fun enterPictureInPicture(): Boolean = pictureInPictureGateway?.enter() == true

    // ---- 画面手势（规格 FR-PLAYER-004 / #527 / #339）----
    //
    // 识别出的轴向锁定、区域划分和阈值由 feature 层的 PlayerGestureRecognizer 负责；
    // 这里只负责"把位移应用到具体能力"，因此全部可按 JVM 测试驱动。

    fun togglePlayback() {
        when (state.value.playback) {
            is PlaybackState.Playing -> pause()
            else -> play()
        }
    }

    /** 左侧/右侧竖向拖动开始：记录起点值，后续按位移比例调整。 */
    fun beginVolumeGesture() {
        volumeAnchor = deviceControlGateway?.currentVolume() ?: return
    }

    fun beginBrightnessGesture() {
        brightnessAnchor = deviceControlGateway?.currentBrightness() ?: return
    }

    fun applyVolumeGesture(dyFraction: Float) {
        val gateway = deviceControlGateway ?: return
        val next = (volumeAnchor + dyFraction * GESTURE_VALUE_GAIN).coerceIn(0f, 1f)
        gateway.setVolume(next)
        showGestureHud(PlayerGestureHud.Volume(gateway.currentVolume()))
    }

    fun applyBrightnessGesture(dyFraction: Float) {
        val gateway = deviceControlGateway ?: return
        val next = (brightnessAnchor + dyFraction * GESTURE_VALUE_GAIN).coerceIn(0f, 1f)
        gateway.setBrightness(next)
        // 一旦滑动就说明用户在手动调，自动状态随之解除（isAuto 默认 false）。
        showGestureHud(PlayerGestureHud.Brightness(next))
    }

    /**
     * 亮度条顶部图标：在"自动（交回系统亮度覆盖）"与"手动（本页亮度）"之间切换。
     * 自动状态下仍可竖向滑动——滑动即切回手动，并从当前系统亮度接着调，避免跳变。
     */
    fun toggleAutoBrightness() {
        val gateway = deviceControlGateway ?: return
        if (gateway.isFollowingSystemBrightness()) {
            val current = gateway.currentBrightness()
            gateway.setBrightness(current)
            showGestureHud(PlayerGestureHud.Brightness(current, isAuto = false))
        } else {
            gateway.resetBrightness()
            showGestureHud(PlayerGestureHud.Brightness(gateway.currentBrightness(), isAuto = true))
        }
    }

    /** 手势结束：HUD 继续停留一小段时间再消退。 */
    fun endVolumeGesture() = scheduleGestureHudHide()

    fun endBrightnessGesture() = scheduleGestureHudHide()

    /**
     * 长按临时倍速：绝不写入媒体偏好（[setSpeed] 会按媒体持久化），松手恢复到
     * 按下前的倍速，避免"长按一下就把这个视频的偏好速度改了"。
     */
    fun beginTemporarySpeed() {
        if (temporarySpeedAnchor != null) return
        val target = state.value.preferences.longPressSpeed
        val current = state.value.speed
        if (target == current) return
        temporarySpeedAnchor = current
        isTemporarySpeed.value = true
        sessionClient.dispatch(PlaybackSessionCommand.SetSpeed(target))
    }

    fun endTemporarySpeed() {
        val anchor = temporarySpeedAnchor ?: return
        temporarySpeedAnchor = null
        isTemporarySpeed.value = false
        sessionClient.dispatch(PlaybackSessionCommand.SetSpeed(anchor))
    }

    fun setZoom(scale: Float, offsetX: Float, offsetY: Float) {
        zoom.value = VideoZoom.of(scale, offsetX, offsetY)
        showGestureHud(PlayerGestureHud.Zoom(zoom.value.scale))
    }

    fun captureZoomAnchor() {
        zoomAnchor = zoom.value
    }

    /**
     * 双指手势按"相对起点"的增量缩放与平移：捏合传倍率因子（1f 不变），
     * 平移传归一化位移。用增量而不是绝对值，Compose 层就不必自己维护锚点。
     */
    fun applyZoomGesture(scaleFactor: Float, dxFraction: Float = 0f, dyFraction: Float = 0f) {
        val anchor = zoomAnchor
        setZoom(
            scale = anchor.scale * scaleFactor,
            offsetX = anchor.offsetX + dxFraction,
            offsetY = anchor.offsetY + dyFraction,
        )
    }

    /** 放大状态下单指拖动改为平移画面（规格 #529），不再调音量/亮度/进度。 */
    fun panZoom(dxFraction: Float, dyFraction: Float) {
        val current = zoom.value
        if (!current.isActive) return
        zoom.value = VideoZoom.of(current.scale, current.offsetX + dxFraction, current.offsetY + dyFraction)
    }

    /** 水平拖动调整进度：拖动只更新 HUD 预览，松手才提交（与进度条一致）。 */
    fun beginSeekGesture() {
        seekAnchorMillis = state.value.displayedPositionMillis
    }

    fun applySeekGesture(dxFraction: Float) {
        val duration = state.value.playback.timeline.durationMillis ?: return
        val target = clampSeek((seekAnchorMillis + dxFraction * duration).toLong())
        gestureHud.value = PlayerGestureHud.Seek(target, duration)
        scheduleGestureHudHide()
    }

    fun endSeekGesture(dxFraction: Float) {
        val duration = state.value.playback.timeline.durationMillis
        if (duration != null) {
            seekTo((seekAnchorMillis + dxFraction * duration).toLong())
        }
        scheduleGestureHudHide()
    }

    fun resetZoom() {
        if (!zoom.value.isActive) return
        zoom.value = VideoZoom.Default
    }

    /** 离开播放页：恢复系统亮度、清掉临时倍速与缩放，不把页面状态带出去。 */
    fun onPlayerPageClosed() {
        exitFullscreen()
        deviceControlGateway?.resetBrightness()
        endTemporarySpeed()
        resetZoom()
        gestureHudHideJob?.cancel()
        gestureHud.value = null
    }

    private fun showGestureHud(hud: PlayerGestureHud) {
        gestureHud.value = hud
        scheduleGestureHudHide()
    }

    private fun scheduleGestureHudHide() {
        gestureHudHideJob?.cancel()
        gestureHudHideJob = viewModelScope.launch {
            delay(PlayerGestureHud.HUD_LINGER_MILLIS)
            gestureHud.value = null
        }
    }

    fun setMiniPlayerEnabled(enabled: Boolean) {
        viewModelScope.launch { playerPreferenceRepository?.setMiniPlayerEnabled(enabled) }
    }

    fun setAutoPictureInPicture(enabled: Boolean) {
        viewModelScope.launch { playerPreferenceRepository?.setAutoPictureInPicture(enabled) }
    }

    /** "上一个"的行为：true = 先回本集开头（默认），false = 永远直接切上一项。 */
    fun setPreviousRestartsCurrentItem(enabled: Boolean) {
        viewModelScope.launch { playerPreferenceRepository?.setPreviousRestartsCurrentItem(enabled) }
    }

    // ---- 画面手势设置（规格 FR-PLAYER-004 / #208：每项手势可分别关闭）----

    fun setGestureSeekEnabled(enabled: Boolean) {
        viewModelScope.launch { playerPreferenceRepository?.setGestureSeekEnabled(enabled) }
    }

    fun setGestureVolumeEnabled(enabled: Boolean) {
        viewModelScope.launch { playerPreferenceRepository?.setGestureVolumeEnabled(enabled) }
    }

    fun setGestureBrightnessEnabled(enabled: Boolean) {
        viewModelScope.launch { playerPreferenceRepository?.setGestureBrightnessEnabled(enabled) }
    }

    fun setGestureZoomEnabled(enabled: Boolean) {
        viewModelScope.launch { playerPreferenceRepository?.setGestureZoomEnabled(enabled) }
    }

    fun setGestureLeftSideIsVolume(enabled: Boolean) {
        viewModelScope.launch { playerPreferenceRepository?.setGestureLeftSideIsVolume(enabled) }
    }

    fun setGestureDoubleTapSeekMillis(millis: Int) {
        viewModelScope.launch { playerPreferenceRepository?.setGestureDoubleTapSeekMillis(millis) }
    }

    fun setGestureSwipeDownToExitEnabled(enabled: Boolean) {
        viewModelScope.launch { playerPreferenceRepository?.setGestureSwipeDownToExitEnabled(enabled) }
    }

    /**
     * 首次进入播放页的手势提示已展示：持久化为"已展示"，此后不再出现（规格 §5.15 / §19.3）。
     * 提示本身由 PlayerScreen 计时收起，这里只负责落库。
     */
    fun markGestureHintShown() {
        viewModelScope.launch { playerPreferenceRepository?.setGestureHintShown(true) }
    }

    fun setGestureLongPressSpeed(speed: PlaybackSpeed) {
        viewModelScope.launch { playerPreferenceRepository?.setGestureLongPressSpeed(speed) }
    }

    private fun scheduleOverlayHide() {
        overlayHideJob?.cancel()
        if (
            panel.value != PlayerPanel.NONE || abToolOpen.value ||
            screenshot.value is ScreenshotUiState.Armed || screenshot.value is ScreenshotUiState.Capturing
        ) return
        // 规格 §8「暂停默认保持显示」：不在这里短路（提前 return 会改变既有调用方的时序假设，
        // 曾让两个截图用例在删除前就丢失 Preview 状态），改为照常安排超时，在超时回调里判断。
        overlayHideJob = viewModelScope.launch {
            delay(PlayerOverlayReducer.AUTO_HIDE_MILLIS)
            val keepVisible = !overlay.value.locked && state.value.playback !is PlaybackState.Playing
            overlay.value = PlayerOverlayReducer.reduce(
                overlay.value,
                if (keepVisible) {
                    // 暂停中：只刷新交互时间，不隐藏控件；锁定态仍走正常隐藏（§5.13 防误触）。
                    PlayerOverlayEvent.Interaction(0)
                } else {
                    PlayerOverlayEvent.Timeout(PlayerOverlayReducer.AUTO_HIDE_MILLIS)
                },
            )
        }
    }

    private fun scheduleScreenshotExpiry() {
        screenshotExpiryJob?.cancel()
        val preview = screenshot.value as? ScreenshotUiState.Preview ?: return
        screenshotExpiryJob = viewModelScope.launch {
            var remaining = preview.remainingMillis
            while (remaining > 0) {
                val elapsed = minOf(SCREENSHOT_EXPIRY_TICK_MILLIS, remaining)
                delay(elapsed)
                screenshot.value = ScreenshotUiReducer.reduce(screenshot.value, ScreenshotUiEvent.TimeElapsed(elapsed))
                remaining -= elapsed
            }
        }
    }

    private fun clampSeek(positionMillis: Long): Long {
        val duration = state.value.playback.timeline.durationMillis ?: Long.MAX_VALUE
        return abLimiter.clamp(abLoop.value, positionMillis, duration)
    }

    private fun dispatchResult(command: PlaybackSessionCommand): PlaybackCommandResult {
        sessionClient.dispatch(command)
        return PlaybackCommandResult.Accepted
    }

    private fun resetAbSession() {
        clearAb()
        abToolOpen.value = false
    }

    private fun replaceQueueForSource(mediaId: MediaItemId, source: PlaybackQueueSource) {
        val repository = playbackQueueRepository ?: return
        val library = libraryRepository ?: return
        queueBuildJob?.cancel()
        queueBuildJob = viewModelScope.launch {
            val items = withContext(dispatchers.io) { library.findAll(source.toQuery()) }
            // A newer route may have started another queue build while this query was running.
            // Never let a stale, slower query overwrite the queue for the active source.
            if (activeQueueSource != source) return@launch
            val mediaIds = items.map(LibraryMedia::id).distinct().toMutableList()
            if (mediaId !in mediaIds) mediaIds += mediaId
            val currentIndex = mediaIds.indexOf(mediaId)
            repository.setQueue(
                PlaybackQueue(
                    mediaIds = mediaIds,
                    currentIndex = currentIndex,
                    continuousPlayback = true,
                ),
            )
        }
    }

    private fun restoreTrackPreference(mediaId: MediaItemId) {
        val repository = trackPreferenceRepository ?: return
        viewModelScope.launch {
            val preference = repository.trackPreferences.first().resolve(mediaId)
            sessionClient.dispatch(PlaybackSessionCommand.SetSpeed(preference.speed))
            sessionClient.dispatch(PlaybackSessionCommand.SetScale(preference.scaleMode))
            rotation.value = preference.rotation
            val loaded = withTimeoutOrNull(TRACK_LOAD_TIMEOUT_MILLIS) { state.filter {
                it.playback.request?.mediaId == mediaId &&
                    (it.audioTracks.isNotEmpty() || it.subtitleTracks.isNotEmpty())
            }.first() } ?: return@launch
            preference.audioLanguage?.let { language ->
                loaded.audioTracks.firstOrNull { it.language == language }?.let { selectAudioTrack(it.id) }
            }
            if (!preference.subtitlesEnabled) {
                selectSubtitleTrack(null)
            } else {
                preference.subtitleLanguage?.let { language ->
                    loaded.subtitleTracks.firstOrNull { it.language == language }?.let { selectSubtitleTrack(it.id) }
                }
            }
        }
    }

    private fun persistTrackPreference(mediaId: MediaItemId, update: TrackPreference.() -> TrackPreference) {
        val repository = trackPreferenceRepository ?: return
        viewModelScope.launch {
            val current = repository.trackPreferences.first().resolve(mediaId)
            repository.setForMedia(mediaId, update(current))
        }
    }

    private fun projectedPlayingState(state: PlaybackState.Playing): Flow<Pair<PlaybackState, Long>> = flow {
        var position = state.timeline.positionMillis
        emit(state to position)
        while (true) {
            delay(PROGRESS_TICK_MILLIS)
            position = (position + PROGRESS_TICK_MILLIS).coerceAtMost(
                state.timeline.durationMillis ?: Long.MAX_VALUE,
            )
            abLimiter.loopPosition(
                abLoop.value,
                position,
            )?.let { loopStart ->
                sessionClient.dispatch(PlaybackSessionCommand.Seek(loopStart, seeyuer.yingli.player.domain.playback.SeekOrigin.AB_LOOP))
                position = loopStart
            }
            emit(state to position)
        }
    }

    /**
     * 规格 §14.4：命令被拒绝时也要走统一瞬时反馈通道，而不是静默失败。
     * 拒绝码由会话层在 dispatch 时同步产生（见 [PlaybackSessionEvent.OneShotFeedback]），
     * 这里只负责把码翻译成文案。
     */
    init {
        viewModelScope.launch {
            sessionClient.events.collect { event ->
                if (event is PlaybackSessionEvent.OneShotFeedback) {
                    events.trySend(PlayerUiEvent.TransientMessage(playbackRejectionMessageRes(event.code)))
                }
            }
        }
        // 全屏以系统事实为准：镜像窗口网关的状态，而不是点击后就假设成功。
        viewModelScope.launch {
            windowPlaybackGateway?.state?.collect { windowState ->
                isFullscreen.value = windowState.isFullscreen
            }
        }
        // 暂停时唤出控件（规格 §8「暂停默认保持显示」），只在"播放 → 暂停"的跳变时做一次，
        // 这样用户暂停后主动隐藏控件不会被反复抢回来。
        // 暂停态唤出控件的策略待定（规格 §8 与 §5.13 冲突），先在 init 里不做处理。
    }

    /** 播放画布上依赖的 UI 子状态，合成一个数据类避免 combine 元组过长。 */
    private data class PlayerSurfaceState(
        val screenshot: ScreenshotUiState,
        val layout: PlayerControlLayout,
        val rotation: VideoRotation,
        val isFullscreen: Boolean,
        val fillScreen: Boolean,
    )

    /** 手势层子状态。 */
    private data class GestureSurfaceState(
        val surface: PlayerSurfaceState,
        val hud: PlayerGestureHud?,
        val zoom: VideoZoom,
        val temporarySpeed: Boolean,
    )

    companion object {
        private const val STOP_TIMEOUT = 5_000L
        private const val PROGRESS_TICK_MILLIS = 250L
        /**
         * 竖向手势灵敏度：`2f` = "半屏滑到底"即覆盖整个取值范围。
         * 取 1f 时从屏幕中部划到顶部只能到 50%，用户会以为"不跟手"，故按反馈放大。
         */
        private const val GESTURE_VALUE_GAIN = 2f
        private const val TRACK_LOAD_TIMEOUT_MILLIS = 5_000L
        private const val FRAME_STEP_FALLBACK_MILLIS = 34L
        private const val SCREENSHOT_EXPIRY_TICK_MILLIS = 100L
        private const val PLAYLIST_PAGE_SIZE = 60
        private const val PLAYLIST_PREFETCH_DISTANCE = 12
        private const val PLAYLIST_MAX_SIZE = 240

        fun factory(
            sessionClient: PlaybackSessionClient,
            dispatchers: AppDispatchers,
            playerPreferenceRepository: PlayerPreferenceRepository? = null,
            trackPreferenceRepository: TrackPreferenceRepository? = null,
            screenshotGateway: ScreenshotGateway? = null,
            pictureInPictureGateway: PictureInPictureGateway? = null,
            playbackQueueRepository: PlaybackQueueRepository? = null,
            controlLayoutRepository: PlayerControlLayoutRepository? = null,
            libraryRepository: LibraryPagingRepository? = null,
            windowPlaybackGateway: WindowPlaybackGateway? = null,
            deviceControlGateway: DeviceControlGateway? = null,
            ownsSessionClient: Boolean = true,
        ) = viewModelFactory {
            initializer {
                PlayerViewModel(
                    sessionClient,
                    dispatchers,
                    playerPreferenceRepository,
                    trackPreferenceRepository,
                    screenshotGateway,
                    pictureInPictureGateway,
                    playbackQueueRepository,
                    controlLayoutRepository,
                    libraryRepository,
                    windowPlaybackGateway,
                    deviceControlGateway,
                    ownsSessionClient,
                )
            }
        }
    }

    private data class AdvancedState(
        val audioTracks: List<TrackChoice> = emptyList(),
        val subtitleTracks: List<TrackChoice> = emptyList(),
        val speed: PlaybackSpeed = PlaybackSpeed.Normal,
        val scaleMode: VideoScaleMode = VideoScaleMode.FIT,
    )

    private data class Quadruple<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
}

internal fun orderPlaylistItems(
    queueIds: List<MediaItemId>,
    items: List<LibraryMedia>,
): List<LibraryMedia> {
    val mediaById = items.associateBy { it.id }
    return queueIds.mapNotNull(mediaById::get)
}

private fun PlaybackSessionSnapshot.toPlaybackState(): PlaybackState {
    val request = mediaId?.let {
        seeyuer.yingli.player.domain.playback.PlaybackRequest(
            it,
            seeyuer.yingli.player.core.model.media.MediaLocationId(it.value),
            timeline.positionMillis,
            PlaybackSourceContext.HOME,
        )
    }
    return when (val current = phase) {
        PlaybackPhase.Idle -> PlaybackState.Idle
        is PlaybackPhase.Resolving, is PlaybackPhase.Preparing -> request?.let { PlaybackState.Preparing(it, timeline) } ?: PlaybackState.Idle
        is PlaybackPhase.Ready -> request?.let { PlaybackState.Ready(it, timeline) } ?: PlaybackState.Idle
        is PlaybackPhase.Playing -> request?.let { PlaybackState.Playing(it, timeline) } ?: PlaybackState.Idle
        is PlaybackPhase.Paused -> request?.let { PlaybackState.Paused(it, timeline) } ?: PlaybackState.Idle
        // 重缓冲（seek/缓冲不足）单独标记：UI 据此不显示全屏加载圈，避免快进/快退/拖动进度条时闪加载。
        is PlaybackPhase.Buffering -> request?.let { PlaybackState.Preparing(it, timeline, isRebuffering = true) } ?: PlaybackState.Idle
        is PlaybackPhase.Ended -> request?.let { PlaybackState.Ended(it, timeline, current.next != null) } ?: PlaybackState.Idle
        is PlaybackPhase.Failed -> PlaybackState.Failed(request, timeline, current.error)
    }
}

private fun TrackChoice.toFingerprint() = seeyuer.yingli.player.domain.playback.TrackFingerprint(
    type = seeyuer.yingli.player.domain.playback.TrackType.AUDIO,
    language = language,
    roleFlags = 0,
    codec = null,
    channelCount = null,
    label = label,
)

private fun PlaybackState.supportsScreenshot(): Boolean =
    this is PlaybackState.Ready || this is PlaybackState.Playing || this is PlaybackState.Paused
