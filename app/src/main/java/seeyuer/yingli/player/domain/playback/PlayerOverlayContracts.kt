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
        // 锁定时单击不切换控件显隐，只用来唤出解锁入口；解锁入口同样吃 3 秒自动隐藏。
        is PlayerOverlayEvent.Tap -> if (state.locked) state.copy(
            controlsVisible = true,
            lastInteractionEpochMillis = event.nowEpochMillis,
        ) else state.copy(
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
            state.dragging || event.nowEpochMillis - state.lastInteractionEpochMillis < AUTO_HIDE_MILLIS
        ) state else state.copy(controlsVisible = false)
    }
}

enum class PlayerPanel {
    NONE,
    SETTINGS,
    PLAYLIST,
    VIDEO_INFO,
    /** 倍速档位条：就地展开在底栏按钮行内，不是浮层。 */
    SPEED,
}

sealed interface PlayerPanelEvent {
    data class Open(val panel: PlayerPanel) : PlayerPanelEvent {
        init { require(panel != PlayerPanel.NONE) }
    }
    data object Close : PlayerPanelEvent
    data object Back : PlayerPanelEvent
}

object PlayerPanelReducer {
    fun reduce(state: PlayerPanel, event: PlayerPanelEvent): PlayerPanel = when (event) {
        is PlayerPanelEvent.Open -> event.panel
        PlayerPanelEvent.Close, PlayerPanelEvent.Back -> PlayerPanel.NONE
    }
}
