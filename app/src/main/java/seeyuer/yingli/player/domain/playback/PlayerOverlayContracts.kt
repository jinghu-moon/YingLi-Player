package seeyuer.yingli.player.domain.playback

data class PlayerOverlayState(
    val controlsVisible: Boolean = true,
    val locked: Boolean = false,
    val dragging: Boolean = false,
    val bufferedFraction: Float = 0f,
    val playedFraction: Float = 0f,
    val lastInteractionEpochMillis: Long = 0,
) {
    init {
        require(bufferedFraction in 0f..1f)
        require(playedFraction in 0f..1f)
    }
}

sealed interface PlayerOverlayEvent {
    data class Tap(val nowEpochMillis: Long) : PlayerOverlayEvent
    data class Interaction(val nowEpochMillis: Long) : PlayerOverlayEvent
    data object ToggleLock : PlayerOverlayEvent
    data object DragStarted : PlayerOverlayEvent
    data class DragUpdated(val playedFraction: Float, val bufferedFraction: Float) : PlayerOverlayEvent
    data object DragEnded : PlayerOverlayEvent
    data class Timeout(val nowEpochMillis: Long) : PlayerOverlayEvent
}

object PlayerOverlayReducer {
    const val AUTO_HIDE_MILLIS = 3_000L

    fun reduce(state: PlayerOverlayState, event: PlayerOverlayEvent): PlayerOverlayState = when (event) {
        is PlayerOverlayEvent.Tap -> if (state.locked) state else state.copy(
            controlsVisible = !state.controlsVisible,
            lastInteractionEpochMillis = event.nowEpochMillis,
        )
        is PlayerOverlayEvent.Interaction -> state.copy(
            controlsVisible = true,
            lastInteractionEpochMillis = event.nowEpochMillis,
        )
        PlayerOverlayEvent.ToggleLock -> state.copy(
            locked = !state.locked,
            controlsVisible = true,
            dragging = false,
        )
        PlayerOverlayEvent.DragStarted -> if (state.locked) state else state.copy(
            dragging = true,
            controlsVisible = true,
        )
        is PlayerOverlayEvent.DragUpdated -> if (!state.dragging || state.locked) state else state.copy(
            playedFraction = event.playedFraction.coerceIn(0f, 1f),
            bufferedFraction = event.bufferedFraction.coerceIn(state.bufferedFraction, 1f),
        )
        PlayerOverlayEvent.DragEnded -> state.copy(dragging = false)
        is PlayerOverlayEvent.Timeout -> if (
            state.locked || state.dragging || event.nowEpochMillis - state.lastInteractionEpochMillis < AUTO_HIDE_MILLIS
        ) state else state.copy(controlsVisible = false)
    }
}
