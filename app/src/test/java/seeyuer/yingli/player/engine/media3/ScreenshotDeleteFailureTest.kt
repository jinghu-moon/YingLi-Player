package seeyuer.yingli.player.engine.media3

import org.junit.Assert.assertEquals
import org.junit.Test
import seeyuer.yingli.player.domain.playback.ScreenshotDeleteFailure

class ScreenshotDeleteFailureTest {
    @Test
    fun permissionFailureUsesStableDiagnosticCode() {
        assertEquals(
            "SCREENSHOT_DELETE_PERMISSION_DENIED",
            ScreenshotDeleteFailure.PERMISSION_DENIED.code,
        )
    }

    @Test
    fun everyFailureCodeIsStableAndDistinct() {
        val codes = ScreenshotDeleteFailure.entries.map(ScreenshotDeleteFailure::code)
        assertEquals(codes.distinct(), codes)
        assertEquals(
            listOf("SCREENSHOT_URI_INVALID", "SCREENSHOT_DELETE_PERMISSION_DENIED", "SCREENSHOT_DELETE_FAILED"),
            codes,
        )
    }
}
