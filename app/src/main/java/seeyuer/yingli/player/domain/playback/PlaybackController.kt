package seeyuer.yingli.player.domain.playback

import kotlinx.coroutines.flow.StateFlow

enum class PlaybackCommandRejection {
    NOT_CONNECTED,
    INVALID_STATE,
    SOURCE_UNAVAILABLE,
    NO_CANDIDATE,
    CAPABILITY_UNAVAILABLE,
    TRACK_UNAVAILABLE,
    INVALID_AB_RANGE,
    /** AB 在当前媒体上不可用：时长为未知值或当前媒体项不可 seek，循环区间无法定义。 */
    AB_UNAVAILABLE,
    STALE_COMMAND,
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
    fun prepare(source: ResolvedPlaybackSource): PlaybackCommandResult = prepare(source.request)
    fun play(): PlaybackCommandResult
    fun pause(): PlaybackCommandResult
    fun seekTo(positionMillis: Long): PlaybackCommandResult
    fun stop(): PlaybackCommandResult
    fun retry(): PlaybackCommandResult
}
