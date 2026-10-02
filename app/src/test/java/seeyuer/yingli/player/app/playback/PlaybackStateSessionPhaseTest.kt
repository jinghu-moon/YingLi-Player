package seeyuer.yingli.player.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.playback.BackendId
import seeyuer.yingli.player.domain.playback.BufferingReason
import seeyuer.yingli.player.domain.playback.PlaybackPhase
import seeyuer.yingli.player.domain.playback.PlaybackRequest
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackState
import seeyuer.yingli.player.domain.playback.PlaybackTimeline

class PlaybackStateSessionPhaseTest {
    @Test
    fun `initial preparation remains a preparation phase`() {
        val phase = PlaybackState.Preparing(REQUEST, TIMELINE, isRebuffering = false).toSessionPhase()

        assertEquals(PlaybackPhase.Preparing(BackendId.MEDIA3), phase)
    }

    @Test
    fun `seek rebuffer remains buffering instead of becoming initial preparation`() {
        val phase = PlaybackState.Preparing(REQUEST, TIMELINE, isRebuffering = true).toSessionPhase()

        assertEquals(PlaybackPhase.Buffering(BufferingReason.REBUFFER), phase)
    }

    private companion object {
        val REQUEST = PlaybackRequest(
            MediaItemId("media_1"),
            MediaLocationId("location_1"),
            0L,
            PlaybackSourceContext.HOME,
        )
        val TIMELINE = PlaybackTimeline(12_000L, 60_000L, 20_000L)
    }
}
