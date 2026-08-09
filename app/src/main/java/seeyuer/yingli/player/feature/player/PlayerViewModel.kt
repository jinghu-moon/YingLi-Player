package seeyuer.yingli.player.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import seeyuer.yingli.player.core.foundation.AppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.playback.PlaybackCommandResult
import seeyuer.yingli.player.domain.playback.PlaybackConnectionState
import seeyuer.yingli.player.domain.playback.PlaybackController
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackSourceRepository
import seeyuer.yingli.player.domain.playback.PlaybackState

data class PlayerUiState(
    val playback: PlaybackState = PlaybackState.Idle,
    val connection: PlaybackConnectionState = PlaybackConnectionState.CONNECTING,
    val title: String = "",
    val displayedPositionMillis: Long = 0,
    val sourceUnavailable: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    private val controller: PlaybackController,
    private val sourceRepository: PlaybackSourceRepository,
    private val dispatchers: AppDispatchers,
) : ViewModel() {
    private val title = MutableStateFlow("")
    private val sourceUnavailable = MutableStateFlow(false)
    private val projectedPlayback: Flow<Pair<PlaybackState, Long>> = controller.state.flatMapLatest { state ->
        if (state is PlaybackState.Playing) projectedPlayingState(state) else flowOf(state to state.timeline.positionMillis)
    }

    val state: StateFlow<PlayerUiState> = combine(
        projectedPlayback,
        controller.connectionState,
        title,
        sourceUnavailable,
    ) { (playback, displayedPosition), connection, currentTitle, unavailable ->
        PlayerUiState(playback, connection, currentTitle, displayedPosition, unavailable)
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

    fun play(): PlaybackCommandResult = controller.play()
    fun pause(): PlaybackCommandResult = controller.pause()
    fun seekTo(positionMillis: Long): PlaybackCommandResult = controller.seekTo(positionMillis)
    fun replay(): PlaybackCommandResult {
        return controller.play()
    }
    fun retry(): PlaybackCommandResult = controller.retry()

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

        fun factory(
            controller: PlaybackController,
            sourceRepository: PlaybackSourceRepository,
            dispatchers: AppDispatchers,
        ) = viewModelFactory {
            initializer { PlayerViewModel(controller, sourceRepository, dispatchers) }
        }
    }
}
