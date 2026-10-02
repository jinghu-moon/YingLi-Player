package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerPanelReducerTest {
    @Test
    fun openingAnotherPanelReplacesCurrentPanel() {
        val state = PlayerPanelReducer.reduce(PlayerPanel.NONE, PlayerPanelEvent.Open(PlayerPanel.SETTINGS))
        val replaced = PlayerPanelReducer.reduce(state, PlayerPanelEvent.Open(PlayerPanel.PLAYLIST))

        assertEquals(PlayerPanel.PLAYLIST, replaced)
    }

    @Test
    fun closeAndBackAlwaysReturnToNoPanel() {
        val state = PlayerPanel.VIDEO_INFO

        assertEquals(PlayerPanel.NONE, PlayerPanelReducer.reduce(state, PlayerPanelEvent.Close))
        assertEquals(PlayerPanel.NONE, PlayerPanelReducer.reduce(state, PlayerPanelEvent.Back))
    }
}
