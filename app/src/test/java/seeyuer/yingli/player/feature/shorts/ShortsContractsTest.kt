package seeyuer.yingli.player.feature.shorts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import seeyuer.yingli.player.domain.playback.ScreenshotUiEvent
import seeyuer.yingli.player.domain.playback.ScreenshotUiReducer
import seeyuer.yingli.player.domain.playback.ScreenshotUiState
import seeyuer.yingli.player.domain.playback.ScreenshotResult
import seeyuer.yingli.player.core.model.media.MediaItemId

class ShortsContractsTest {
    @Test
    fun `loaded portrait candidates select first item and reset progress`() {
        val state = ShortsReducer.reduce(
            ShortsUiState(currentIndex = 4, progressMillis = 800),
            ShortsEvent.CandidatesLoaded(listOf(candidate("first"), candidate("second"))),
        )

        assertEquals(0, state.currentIndex)
        assertEquals("first", state.current?.title)
        assertEquals(0L, state.progressMillis)
        assertEquals(false, state.loading)
    }

    @Test
    fun `next and previous wrap around the candidate queue`() {
        val state = ShortsUiState(
            candidates = listOf(candidate("first"), candidate("second")),
            currentIndex = 0,
        )

        assertEquals(1, ShortsReducer.nextIndex(state))
        assertEquals(1, ShortsReducer.previousIndex(state))
        assertNull(ShortsReducer.nextIndex(ShortsUiState()))
    }

    @Test
    fun `invalid current change and failure preserve candidate data`() {
        val state = ShortsUiState(candidates = listOf(candidate("first")), currentIndex = 0)
        val unchanged = ShortsReducer.reduce(state, ShortsEvent.CurrentChanged(3))
        val failed = ShortsReducer.reduce(unchanged, ShortsEvent.Failed("SOURCE_UNAVAILABLE"))

        assertEquals(unchanged, state)
        assertEquals(listOf("first"), failed.candidates.map(ShortsCandidate::title))
        assertEquals("SOURCE_UNAVAILABLE", failed.errorCode)
        assertEquals(false, failed.playing)
    }

    @Test
    fun `empty queue has no navigation target`() {
        val state = ShortsUiState()

        assertNull(ShortsReducer.nextIndex(state))
        assertNull(ShortsReducer.previousIndex(state))
        assertNull(state.current)
    }

    @Test
    fun `gesture reducer locks axis and applies direction thresholds`() {
        val vertical = ShortsGestureReducer.drag(ShortsGestureState(), 3f, -80f)
        val horizontal = ShortsGestureReducer.drag(ShortsGestureState(), 80f, -3f)

        assertEquals(ShortsGestureAxis.VERTICAL, vertical.axis)
        assertEquals(ShortsGestureAction.Next, ShortsGestureReducer.finish(vertical))
        assertEquals(ShortsGestureAxis.HORIZONTAL, horizontal.axis)
        assertEquals(ShortsGestureAction.SeekForward, ShortsGestureReducer.finish(horizontal))
        assertEquals(
            ShortsGestureAction.None,
            ShortsGestureReducer.finish(ShortsGestureReducer.drag(ShortsGestureState(), 30f, 5f)),
        )
    }

    @Test
    fun `screenshot preview supports pause and expires at three seconds`() {
        val preview = ScreenshotUiReducer.reduce(
            ScreenshotUiState.Capturing,
            ScreenshotUiEvent.CaptureCompleted(ScreenshotResult.Saved("frame.jpg", "content://frame")),
        )
        val paused = ScreenshotUiReducer.reduce(preview, ScreenshotUiEvent.ToggleExpiryPause)
        val stillVisible = ScreenshotUiReducer.reduce(paused, ScreenshotUiEvent.TimeElapsed(3_000))
        val resumed = ScreenshotUiReducer.reduce(stillVisible, ScreenshotUiEvent.ToggleExpiryPause)

        assertEquals(true, (paused as ScreenshotUiState.Preview).expiryPaused)
        assertEquals(paused, stillVisible)
        assertEquals(ScreenshotUiState.Idle, ScreenshotUiReducer.reduce(resumed, ScreenshotUiEvent.TimeElapsed(3_000)))
    }

    private fun candidate(id: String) = ShortsCandidate(
        id = MediaItemId(id),
        title = id,
        durationMillis = 10_000,
        width = 720,
        height = 1_280,
    )
}
