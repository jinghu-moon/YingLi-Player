package seeyuer.yingli.player.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.playback.PlaybackCommandResult
import seeyuer.yingli.player.domain.playback.PlaybackConnectionState
import seeyuer.yingli.player.domain.playback.PlaybackController
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackSourceRepository
import seeyuer.yingli.player.domain.playback.PlaybackState
import seeyuer.yingli.player.domain.playback.AdvancedPlaybackController
import seeyuer.yingli.player.domain.playback.PictureInPictureGateway
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.PlayerOverlayEvent
import seeyuer.yingli.player.domain.playback.PlayerOverlayReducer
import seeyuer.yingli.player.domain.playback.PlayerOverlayState
import seeyuer.yingli.player.domain.playback.PlayerPreferenceRepository
import seeyuer.yingli.player.domain.playback.PlayerPreferences
import seeyuer.yingli.player.domain.playback.ScreenshotGateway
import seeyuer.yingli.player.domain.playback.ScreenshotResult
import seeyuer.yingli.player.domain.playback.TrackChoice
import seeyuer.yingli.player.domain.playback.TrackPreference
import seeyuer.yingli.player.domain.playback.TrackPreferenceRepository
import seeyuer.yingli.player.domain.playback.VideoScaleMode
import seeyuer.yingli.player.domain.security.SecurePlaybackController
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
    val preferences: PlayerPreferences = PlayerPreferences(),
    val screenshotResult: ScreenshotResult? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    private val controller: PlaybackController,
    private val sourceRepository: PlaybackSourceRepository,
    private val dispatchers: AppDispatchers,
    private val playerPreferenceRepository: PlayerPreferenceRepository? = null,
    private val trackPreferenceRepository: TrackPreferenceRepository? = null,
    private val screenshotGateway: ScreenshotGateway? = null,
    private val pictureInPictureGateway: PictureInPictureGateway? = null,
) : ViewModel() {
    private val advancedController = controller as? AdvancedPlaybackController
    private val title = MutableStateFlow("")
    private val sourceUnavailable = MutableStateFlow(false)
    private val overlay = MutableStateFlow(PlayerOverlayState())
    private val screenshotResult = MutableStateFlow<ScreenshotResult?>(null)
    private var overlayHideJob: Job? = null
    private val projectedPlayback: Flow<Pair<PlaybackState, Long>> = controller.state.flatMapLatest { state ->
        if (state is PlaybackState.Playing) projectedPlayingState(state) else flowOf(state to state.timeline.positionMillis)
    }

    private val baseState = combine(
        projectedPlayback,
        controller.connectionState,
        title,
        sourceUnavailable,
    ) { (playback, displayedPosition), connection, currentTitle, unavailable ->
        PlayerUiState(playback, connection, currentTitle, displayedPosition, unavailable)
    }
    private val advancedState = advancedController?.let { advanced ->
        combine(advanced.audioTracks, advanced.subtitleTracks, advanced.speed, advanced.scaleMode) {
                audio, subtitles, speed, scale -> AdvancedState(audio, subtitles, speed, scale)
        }
    } ?: flowOf(AdvancedState())
    private val preferences = playerPreferenceRepository?.playerPreferences ?: flowOf(PlayerPreferences())

    val state: StateFlow<PlayerUiState> = combine(
        baseState,
        advancedState,
        overlay,
        preferences,
        screenshotResult,
    ) { base, advanced, currentOverlay, currentPreferences, screenshot ->
        base.copy(
            audioTracks = advanced.audioTracks,
            subtitleTracks = advanced.subtitleTracks,
            speed = advanced.speed,
            scaleMode = advanced.scaleMode,
            overlay = currentOverlay,
            preferences = currentPreferences,
            screenshotResult = screenshot,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), PlayerUiState())

    fun open(mediaId: String, sourceContext: PlaybackSourceContext) {
        val id = runCatching { MediaItemId(mediaId) }.getOrNull() ?: return
        if (controller.state.value.request?.mediaId == id) return
        sourceUnavailable.value = false
        viewModelScope.launch {
            val source = try {
                withContext(dispatchers.io) { sourceRepository.resolve(id, sourceContext) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (source == null) {
                sourceUnavailable.value = true
                return@launch
            }
            title.value = source.title
            sourceUnavailable.value = false
            controller.prepare(source.request)
        }
    }

    fun openVault(itemId: String, displayTitle: String) {
        val id = runCatching { VaultItemId(itemId) }.getOrNull() ?: return
        val secureController = controller as? SecurePlaybackController ?: return
        sourceUnavailable.value = false
        title.value = displayTitle
        if (!secureController.prepare(id)) sourceUnavailable.value = true
    }

    fun closeVault() {
        (controller as? SecurePlaybackController)?.invalidateSecureSession()
    }

    fun play(): PlaybackCommandResult = controller.play()
    fun pause(): PlaybackCommandResult = controller.pause()
    fun seekTo(positionMillis: Long): PlaybackCommandResult = controller.seekTo(positionMillis)
    fun replay(): PlaybackCommandResult {
        return controller.play()
    }
    fun retry(): PlaybackCommandResult = controller.retry()
    fun seekBackward(): PlaybackCommandResult = advancedController?.seekBy(-SEEK_STEP_MILLIS)
        ?: controller.seekTo((state.value.displayedPositionMillis - SEEK_STEP_MILLIS).coerceAtLeast(0))
    fun seekForward(): PlaybackCommandResult = advancedController?.seekBy(SEEK_STEP_MILLIS)
        ?: controller.seekTo(state.value.displayedPositionMillis + SEEK_STEP_MILLIS)

    fun setSpeed(value: PlaybackSpeed): PlaybackCommandResult {
        val result = advancedController?.setSpeed(value)
            ?: PlaybackCommandResult.Rejected(seeyuer.yingli.player.domain.playback.PlaybackCommandRejection.INVALID_STATE)
        state.value.playback.request?.mediaId?.let { mediaId ->
            viewModelScope.launch {
                trackPreferenceRepository?.setForMedia(mediaId, TrackPreference(speed = value, scaleMode = state.value.scaleMode))
            }
        }
        return result
    }

    fun setScaleMode(value: VideoScaleMode): PlaybackCommandResult = advancedController?.setScaleMode(value)
        ?: PlaybackCommandResult.Rejected(seeyuer.yingli.player.domain.playback.PlaybackCommandRejection.INVALID_STATE)
    fun selectAudioTrack(id: String): PlaybackCommandResult = advancedController?.selectAudioTrack(id)
        ?: PlaybackCommandResult.Rejected(seeyuer.yingli.player.domain.playback.PlaybackCommandRejection.INVALID_STATE)
    fun selectSubtitleTrack(id: String?): PlaybackCommandResult = advancedController?.selectSubtitleTrack(id)
        ?: PlaybackCommandResult.Rejected(seeyuer.yingli.player.domain.playback.PlaybackCommandRejection.INVALID_STATE)

    fun toggleOverlay() {
        overlay.value = PlayerOverlayReducer.reduce(overlay.value, PlayerOverlayEvent.Tap(0))
        if (overlay.value.controlsVisible) scheduleOverlayHide()
    }

    fun toggleLock() {
        overlay.value = PlayerOverlayReducer.reduce(overlay.value, PlayerOverlayEvent.ToggleLock)
        scheduleOverlayHide()
    }

    fun registerInteraction() {
        overlay.value = PlayerOverlayReducer.reduce(overlay.value, PlayerOverlayEvent.Interaction(0))
        scheduleOverlayHide()
    }

    fun captureScreenshot() {
        val gateway = screenshotGateway ?: return
        viewModelScope.launch {
            screenshotResult.value = gateway.capture(state.value.title, state.value.displayedPositionMillis)
        }
    }

    fun enterPictureInPicture(): Boolean = pictureInPictureGateway?.enter() == true

    fun setMiniPlayerEnabled(enabled: Boolean) {
        viewModelScope.launch { playerPreferenceRepository?.setMiniPlayerEnabled(enabled) }
    }

    fun setAutoPictureInPicture(enabled: Boolean) {
        viewModelScope.launch { playerPreferenceRepository?.setAutoPictureInPicture(enabled) }
    }

    private fun scheduleOverlayHide() {
        overlayHideJob?.cancel()
        if (overlay.value.locked) return
        overlayHideJob = viewModelScope.launch {
            delay(PlayerOverlayReducer.AUTO_HIDE_MILLIS)
            overlay.value = PlayerOverlayReducer.reduce(
                overlay.value,
                PlayerOverlayEvent.Timeout(PlayerOverlayReducer.AUTO_HIDE_MILLIS),
            )
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
            emit(state to position)
        }
    }

    companion object {
        private const val STOP_TIMEOUT = 5_000L
        private const val PROGRESS_TICK_MILLIS = 250L
        private const val SEEK_STEP_MILLIS = 10_000L

        fun factory(
            controller: PlaybackController,
            sourceRepository: PlaybackSourceRepository,
            dispatchers: AppDispatchers,
            playerPreferenceRepository: PlayerPreferenceRepository? = null,
            trackPreferenceRepository: TrackPreferenceRepository? = null,
            screenshotGateway: ScreenshotGateway? = null,
            pictureInPictureGateway: PictureInPictureGateway? = null,
        ) = viewModelFactory {
            initializer {
                PlayerViewModel(
                    controller,
                    sourceRepository,
                    dispatchers,
                    playerPreferenceRepository,
                    trackPreferenceRepository,
                    screenshotGateway,
                    pictureInPictureGateway,
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
}
