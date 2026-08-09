package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId

class PlaybackStateReducerTest {
    @Test
    fun `normal playback path is deterministic`() {
        val preparing = PlaybackStateReducer.reduce(PlaybackState.Idle, PlaybackTransition.Prepare(REQUEST))
        val ready = PlaybackStateReducer.reduce(preparing, PlaybackTransition.Ready(TIMELINE))
        val playing = PlaybackStateReducer.reduce(ready, PlaybackTransition.Play)
        val paused = PlaybackStateReducer.reduce(playing, PlaybackTransition.Pause)
        val sought = PlaybackStateReducer.reduce(paused, PlaybackTransition.Seek(8_000))
        val ended = PlaybackStateReducer.reduce(sought, PlaybackTransition.End)

        assertEquals(PlaybackState.Preparing(REQUEST), preparing)
        assertEquals(PlaybackState.Ready(REQUEST, TIMELINE), ready)
        assertEquals(PlaybackState.Playing(REQUEST, TIMELINE), playing)
        assertEquals(PlaybackState.Paused(REQUEST, TIMELINE), paused)
        assertEquals(8_000, sought.timeline.positionMillis)
        assertEquals(10_000, ended.timeline.positionMillis)
    }

    @Test
    fun `illegal and duplicate commands are idempotent`() {
        assertSame(PlaybackState.Idle, PlaybackStateReducer.reduce(PlaybackState.Idle, PlaybackTransition.Seek(2_000)))
        val preparing = PlaybackState.Preparing(REQUEST)
        assertSame(preparing, PlaybackStateReducer.reduce(preparing, PlaybackTransition.Prepare(REQUEST)))
        assertSame(preparing, PlaybackStateReducer.reduce(preparing, PlaybackTransition.Pause))
    }

    @Test
    fun `ended play restarts from zero and failed retry reuses one request`() {
        val ended = PlaybackState.Ended(REQUEST, TIMELINE.copy(positionMillis = 10_000))
        val replaying = PlaybackStateReducer.reduce(ended, PlaybackTransition.Play)
        val failed = PlaybackState.Failed(REQUEST, TIMELINE, DefaultPlaybackErrorMapper.map(PlaybackFailureSignal.OTHER))

        assertEquals(0, replaying.timeline.positionMillis)
        assertEquals(PlaybackState.Preparing(REQUEST), PlaybackStateReducer.reduce(failed, PlaybackTransition.Retry))
    }

    private companion object {
        val REQUEST = PlaybackRequest(
            MediaItemId("media_1"),
            MediaLocationId("location_1"),
            1_000,
            PlaybackSourceContext.HOME,
        )
        val TIMELINE = PlaybackTimeline(1_000, 10_000, 4_000)
    }
}
