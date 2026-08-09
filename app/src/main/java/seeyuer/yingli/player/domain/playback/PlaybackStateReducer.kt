package seeyuer.yingli.player.domain.playback

sealed interface PlaybackTransition {
    data class Prepare(val request: PlaybackRequest) : PlaybackTransition
    data class Ready(val timeline: PlaybackTimeline) : PlaybackTransition
    data object Play : PlaybackTransition
    data object Pause : PlaybackTransition
    data class Seek(val positionMillis: Long) : PlaybackTransition
    data class Progress(val timeline: PlaybackTimeline) : PlaybackTransition
    data object End : PlaybackTransition
    data class Fail(val error: PlaybackError) : PlaybackTransition
    data object Retry : PlaybackTransition
    data object Stop : PlaybackTransition
}

object PlaybackStateReducer {
    fun reduce(state: PlaybackState, transition: PlaybackTransition): PlaybackState = when (transition) {
        is PlaybackTransition.Prepare -> prepare(state, transition.request)
        is PlaybackTransition.Ready -> when (state) {
            is PlaybackState.Preparing -> PlaybackState.Ready(state.request, transition.timeline.clamped())
            else -> state
        }
        PlaybackTransition.Play -> play(state)
        PlaybackTransition.Pause -> when (state) {
            is PlaybackState.Playing -> PlaybackState.Paused(state.request, state.timeline)
            else -> state
        }
        is PlaybackTransition.Seek -> seek(state, transition.positionMillis)
        is PlaybackTransition.Progress -> progress(state, transition.timeline)
        PlaybackTransition.End -> end(state)
        is PlaybackTransition.Fail -> PlaybackState.Failed(state.request, state.timeline, transition.error)
        PlaybackTransition.Retry -> when (state) {
            is PlaybackState.Failed -> state.request?.let(PlaybackState::Preparing) ?: state
            else -> state
        }
        PlaybackTransition.Stop -> PlaybackState.Idle
    }

    private fun prepare(state: PlaybackState, request: PlaybackRequest): PlaybackState {
        if (state.request == request && state !is PlaybackState.Failed && state !is PlaybackState.Ended) return state
        return PlaybackState.Preparing(request)
    }

    private fun play(state: PlaybackState): PlaybackState = when (state) {
        is PlaybackState.Ready -> PlaybackState.Playing(state.request, state.timeline)
        is PlaybackState.Paused -> PlaybackState.Playing(state.request, state.timeline)
        is PlaybackState.Ended -> PlaybackState.Playing(
            state.request.copy(startPositionMillis = 0),
            state.timeline.copy(positionMillis = 0, bufferedPositionMillis = 0),
        )
        else -> state
    }

    private fun seek(state: PlaybackState, positionMillis: Long): PlaybackState {
        if (PlaybackAction.SEEK !in state.availableActions) return state
        val timeline = state.timeline.copy(positionMillis = positionMillis.coerceAtLeast(0)).clamped()
        return state.withTimeline(timeline)
    }

    private fun progress(state: PlaybackState, timeline: PlaybackTimeline): PlaybackState = when (state) {
        PlaybackState.Idle -> state
        is PlaybackState.Failed, is PlaybackState.Ended -> state
        else -> state.withTimeline(timeline.clamped())
    }

    private fun end(state: PlaybackState): PlaybackState = state.request?.let { request ->
        val duration = state.timeline.durationMillis
        PlaybackState.Ended(
            request,
            state.timeline.copy(positionMillis = duration ?: state.timeline.positionMillis).clamped(),
        )
    } ?: state

    private fun PlaybackState.withTimeline(timeline: PlaybackTimeline): PlaybackState = when (this) {
        PlaybackState.Idle -> this
        is PlaybackState.Preparing -> copy(timeline = timeline)
        is PlaybackState.Ready -> copy(timeline = timeline)
        is PlaybackState.Playing -> copy(timeline = timeline)
        is PlaybackState.Paused -> copy(timeline = timeline)
        is PlaybackState.Ended -> copy(timeline = timeline)
        is PlaybackState.Failed -> copy(timeline = timeline)
    }

    private fun PlaybackTimeline.clamped(): PlaybackTimeline {
        val safeDuration = durationMillis?.coerceAtLeast(0)
        val max = safeDuration ?: Long.MAX_VALUE
        return copy(
            positionMillis = positionMillis.coerceIn(0, max),
            durationMillis = safeDuration,
            bufferedPositionMillis = bufferedPositionMillis.coerceIn(0, max),
        )
    }
}
