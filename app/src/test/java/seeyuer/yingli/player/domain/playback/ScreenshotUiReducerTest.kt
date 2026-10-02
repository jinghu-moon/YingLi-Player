package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotUiReducerTest {
    @Test
    fun captureSuccessCreatesExpiringPreview() {
        val armed = ScreenshotUiReducer.reduce(ScreenshotUiState.Idle, ScreenshotUiEvent.Arm)
        val capturing = ScreenshotUiReducer.reduce(armed, ScreenshotUiEvent.CaptureStarted)
        val preview = ScreenshotUiReducer.reduce(
            capturing,
            ScreenshotUiEvent.CaptureCompleted(ScreenshotResult.Saved("frame.jpg", "content://media/external/images/1")),
        )

        assertEquals(ScreenshotUiState.Preview("frame.jpg", "content://media/external/images/1"), preview)
        assertEquals("content://media/external/images/1", (preview as ScreenshotUiState.Preview).uri)
        assertEquals(
            ScreenshotUiState.Idle,
            ScreenshotUiReducer.reduce(preview, ScreenshotUiEvent.TimeElapsed(3_000)),
        )
    }

    @Test
    fun pausedPreviewDoesNotConsumeExpiry() {
        val preview = ScreenshotUiState.Preview("frame.jpg")
        val paused = ScreenshotUiReducer.reduce(preview, ScreenshotUiEvent.ToggleExpiryPause)

        assertEquals(paused, ScreenshotUiReducer.reduce(paused, ScreenshotUiEvent.TimeElapsed(3_000)))
        assertEquals(ScreenshotUiState.Idle, ScreenshotUiReducer.reduce(paused, ScreenshotUiEvent.Close))
    }

    @Test
    fun invalidCaptureCompletionDoesNotLeaveCapturingState() {
        val capturing = ScreenshotUiState.Capturing
        val failed = ScreenshotUiReducer.reduce(
            capturing,
            ScreenshotUiEvent.CaptureCompleted(ScreenshotResult.Failed(ScreenshotFailure.EMPTY_FRAME)),
        )

        assertEquals(ScreenshotUiState.Failed(ScreenshotFailure.EMPTY_FRAME), failed)
    }
}
