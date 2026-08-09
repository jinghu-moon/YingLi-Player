package seeyuer.yingli.player.domain.playback

import kotlinx.coroutines.flow.StateFlow
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId

enum class PlaybackSourceContext {
    HOME,
    LIBRARY,
    DETAIL,
}

data class PlaybackRequest(
    val mediaId: MediaItemId,
    val locationId: MediaLocationId,
    val startPositionMillis: Long,
    val sourceContext: PlaybackSourceContext,
    val incognito: Boolean = false,
) {
    init {
        require(startPositionMillis >= 0)
    }
}

data class PlaybackTimeline(
    val positionMillis: Long = 0,
    val durationMillis: Long? = null,
    val bufferedPositionMillis: Long = 0,
) {
    init {
        require(positionMillis >= 0)
        require(durationMillis == null || durationMillis >= 0)
        require(bufferedPositionMillis >= 0)
    }
}

enum class PlaybackAction {
    PLAY,
    PAUSE,
    SEEK,
    STOP,
    RETRY,
    REPLAY,
    GO_BACK,
}

sealed interface PlaybackState {
    val request: PlaybackRequest?
    val timeline: PlaybackTimeline
    val availableActions: Set<PlaybackAction>

    data object Idle : PlaybackState {
        override val request: PlaybackRequest? = null
        override val timeline = PlaybackTimeline()
        override val availableActions = emptySet<PlaybackAction>()
    }

    data class Preparing(
        override val request: PlaybackRequest,
        override val timeline: PlaybackTimeline = PlaybackTimeline(
            positionMillis = request.startPositionMillis,
            bufferedPositionMillis = request.startPositionMillis,
        ),
    ) : PlaybackState {
        override val availableActions = setOf(PlaybackAction.STOP, PlaybackAction.GO_BACK)
    }

    data class Ready(
        override val request: PlaybackRequest,
        override val timeline: PlaybackTimeline,
    ) : PlaybackState {
        override val availableActions = setOf(
            PlaybackAction.PLAY,
            PlaybackAction.SEEK,
            PlaybackAction.STOP,
            PlaybackAction.GO_BACK,
        )
    }

    data class Playing(
        override val request: PlaybackRequest,
        override val timeline: PlaybackTimeline,
    ) : PlaybackState {
        override val availableActions = setOf(
            PlaybackAction.PAUSE,
            PlaybackAction.SEEK,
            PlaybackAction.STOP,
            PlaybackAction.GO_BACK,
        )
    }

    data class Paused(
        override val request: PlaybackRequest,
        override val timeline: PlaybackTimeline,
    ) : PlaybackState {
        override val availableActions = setOf(
            PlaybackAction.PLAY,
            PlaybackAction.SEEK,
            PlaybackAction.STOP,
            PlaybackAction.GO_BACK,
        )
    }

    data class Ended(
        override val request: PlaybackRequest,
        override val timeline: PlaybackTimeline,
        val hasNext: Boolean = false,
    ) : PlaybackState {
        override val availableActions = buildSet {
            add(PlaybackAction.REPLAY)
            add(PlaybackAction.STOP)
            add(PlaybackAction.GO_BACK)
        }
    }

    data class Failed(
        override val request: PlaybackRequest?,
        override val timeline: PlaybackTimeline,
        val error: PlaybackError,
    ) : PlaybackState {
        override val availableActions = buildSet {
            if (error.recoverable) add(PlaybackAction.RETRY)
            add(PlaybackAction.STOP)
            add(PlaybackAction.GO_BACK)
        }
    }
}

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

data class ResolvedPlaybackSource(
    val request: PlaybackRequest,
    val uri: String,
    val title: String,
)

interface PlaybackSourceRepository {
    suspend fun resolve(
        mediaId: MediaItemId,
        sourceContext: PlaybackSourceContext,
        incognito: Boolean = false,
    ): ResolvedPlaybackSource?

    suspend fun resolve(request: PlaybackRequest): ResolvedPlaybackSource?
}

interface PlaybackProgressRepository {
    suspend fun saveProgress(
        mediaId: MediaItemId,
        positionMillis: Long,
        completed: Boolean,
    )
}
