package seeyuer.yingli.player.domain.playback

import kotlinx.coroutines.flow.StateFlow

enum class PlaybackCommandRejection {
    NOT_CONNECTED,
    INVALID_STATE,
    SOURCE_UNAVAILABLE,
}

enum class PlaybackConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
}

sealed interface PlaybackCommandResult {
    data object Accepted : PlaybackCommandResult
    data object AlreadyApplied : PlaybackCommandResult
    data class Rejected(val reason: PlaybackCommandRejection) : PlaybackCommandResult
}

interface PlaybackStateRepository {
    val state: StateFlow<PlaybackState>
    val connectionState: StateFlow<PlaybackConnectionState>
}

interface PlaybackController : PlaybackStateRepository {
    fun prepare(request: PlaybackRequest): PlaybackCommandResult
    fun play(): PlaybackCommandResult
    fun pause(): PlaybackCommandResult
    fun seekTo(positionMillis: Long): PlaybackCommandResult
    fun stop(): PlaybackCommandResult
    fun retry(): PlaybackCommandResult
}
