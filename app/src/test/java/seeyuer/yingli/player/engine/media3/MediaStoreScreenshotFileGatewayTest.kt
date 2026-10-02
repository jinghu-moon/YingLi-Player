package seeyuer.yingli.player.engine.media3

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import seeyuer.yingli.player.testing.TestAppDispatchers

class MediaStoreScreenshotFileGatewayTest {
    @Test
    fun securityExceptionIsMappedToPermissionDiagnostic() = runTest {
        val result = MediaStoreScreenshotFileGateway(
            dispatchers = TestAppDispatchers(testScheduler),
            deleteOperation = { throw SecurityException("permission denied") },
        ).delete(
            "content://media/external/images/1",
        )

        assertFalse(result.isSuccess)
        assertEquals(
            "SCREENSHOT_DELETE_PERMISSION_DENIED",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun zeroDeletedRowsRemainAStableFailure() = runTest {
        val result = MediaStoreScreenshotFileGateway(
            dispatchers = TestAppDispatchers(testScheduler),
            deleteOperation = { 0 },
        ).delete(
            "content://media/external/images/missing",
        )

        assertFalse(result.isSuccess)
        assertEquals("SCREENSHOT_DELETE_FAILED", result.exceptionOrNull()?.message)
    }
}
