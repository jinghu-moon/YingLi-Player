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
            ScreenshotUiEvent.CaptureCompleted(
                ScreenshotResult.Saved("frame.jpg", "content://media/external/images/1", "Pictures/YingLi/frame.jpg"),
            ),
        )

        assertEquals(
            ScreenshotUiState.Preview("frame.jpg", "content://media/external/images/1", "Pictures/YingLi/frame.jpg"),
            preview,
        )
        assertEquals("content://media/external/images/1", (preview as ScreenshotUiState.Preview).uri)
        assertEquals("Pictures/YingLi/frame.jpg", preview.location)
        assertEquals(
            ScreenshotUiState.Idle,
            ScreenshotUiReducer.reduce(preview, ScreenshotUiEvent.TimeElapsed(3_000)),
        )
    }

    @Test
    fun expandedPreviewDoesNotConsumeExpiry() {
        val preview = ScreenshotUiState.Preview("frame.jpg", "content://frame")
        val expanded = ScreenshotUiReducer.reduce(preview, ScreenshotUiEvent.ExpandChanged(true))

        assertEquals(true, (expanded as ScreenshotUiState.Preview).expanded)
        // 展开期间倒计时定格（设计稿 §6「点击暂停：读条定格」）。
        assertEquals(expanded, ScreenshotUiReducer.reduce(expanded, ScreenshotUiEvent.TimeElapsed(3_000)))
        assertEquals(ScreenshotUiState.Idle, ScreenshotUiReducer.reduce(expanded, ScreenshotUiEvent.Close))
    }

    @Test
    fun collapsingResumesTheRemainingCountdown() {
        val preview = ScreenshotUiState.Preview("frame.jpg", "content://frame", remainingMillis = 1_200)
        val expanded = ScreenshotUiReducer.reduce(preview, ScreenshotUiEvent.ExpandChanged(true))
        val collapsed = ScreenshotUiReducer.reduce(expanded, ScreenshotUiEvent.ExpandChanged(false))

        // 收起后接着原来的余量走：1000 走完还剩 200，不是重新给满 3 秒。
        val afterOneSecond = ScreenshotUiReducer.reduce(collapsed, ScreenshotUiEvent.TimeElapsed(1_000))
        assertEquals(200L, (afterOneSecond as ScreenshotUiState.Preview).remainingMillis)
    }

    @Test
    fun previewWithoutUriStaysCollapsed() {
        // 没有图可放大：展开这一位不许被置起来，否则会出现一张空的大图预览。
        val preview = ScreenshotUiState.Preview("frame.jpg")
        val expanded = ScreenshotUiReducer.reduce(preview, ScreenshotUiEvent.ExpandChanged(true))

        assertEquals(false, (expanded as ScreenshotUiState.Preview).expanded)
    }

    @Test
    fun naturalExpiryIsDistinguishableFromManualClose() {
        val preview = ScreenshotUiState.Preview("frame.jpg", "content://frame", remainingMillis = 100)

        // 到期：需要提示保存路径。判定必须看"事件之前的那个状态"。
        assertEquals(true, screenshotExpiredNaturally(preview, ScreenshotUiEvent.TimeElapsed(100)))
        assertEquals(false, screenshotExpiredNaturally(preview, ScreenshotUiEvent.TimeElapsed(99)))
        // 关闭 / 换媒体 / 展开中的计时都不是"到期"，都不该提示保存路径。
        assertEquals(false, screenshotExpiredNaturally(preview, ScreenshotUiEvent.Close))
        assertEquals(false, screenshotExpiredNaturally(preview, ScreenshotUiEvent.MediaChanged))
        assertEquals(
            false,
            screenshotExpiredNaturally(
                (ScreenshotUiReducer.reduce(preview, ScreenshotUiEvent.ExpandChanged(true))),
                ScreenshotUiEvent.TimeElapsed(100),
            ),
        )
        assertEquals(false, screenshotExpiredNaturally(ScreenshotUiState.Armed, ScreenshotUiEvent.TimeElapsed(100)))
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
