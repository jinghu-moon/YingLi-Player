package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId

class AdvancedPlaybackContractsTest {
    @Test(expected = IllegalArgumentException::class)
    fun `unsupported playback speed is rejected`() {
        PlaybackSpeed.of(1.1f)
    }

    @Test
    fun `per-media preference overrides global preference`() {
        val mediaId = MediaItemId("media_1")
        val global = TrackPreference(speed = PlaybackSpeed.of(1.5f))
        val local = TrackPreference(speed = PlaybackSpeed.of(2f), subtitlesEnabled = true)

        assertEquals(local, TrackPreferenceSet(global, mapOf(mediaId to local)).resolve(mediaId))
        assertEquals(global, TrackPreferenceSet(global).resolve(mediaId))
    }

    @Test
    fun `queue next item requires explicit continuous playback`() {
        val ids = listOf(MediaItemId("media_1"), MediaItemId("media_2"))
        assertNull(PlaybackQueue(ids, 0).next())
        assertEquals(ids[1], PlaybackQueue(ids, 0, continuousPlayback = true).next())
    }

    @Test
    fun `overlay auto hides unless locked or dragging`() {
        val visible = PlayerOverlayState(lastInteractionEpochMillis = 1_000)
        assertFalse(PlayerOverlayReducer.reduce(visible, PlayerOverlayEvent.Timeout(4_000)).controlsVisible)
        assertTrue(PlayerOverlayReducer.reduce(visible.copy(locked = true), PlayerOverlayEvent.Timeout(4_000)).controlsVisible)
        assertTrue(PlayerOverlayReducer.reduce(visible.copy(dragging = true), PlayerOverlayEvent.Timeout(4_000)).controlsVisible)
    }

    @Test
    fun `buffer regression is ignored during dragging`() {
        val state = PlayerOverlayState(dragging = true, bufferedFraction = 0.6f)
        val updated = PlayerOverlayReducer.reduce(state, PlayerOverlayEvent.DragUpdated(0.4f, 0.2f))
        assertEquals(0.6f, updated.bufferedFraction)
    }
}
