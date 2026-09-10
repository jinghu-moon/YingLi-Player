package seeyuer.yingli.player.domain.playback

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
