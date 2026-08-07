package seeyuer.yingli.player.core.model

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFailureMapperTest {
    @Test
    fun `known technical exceptions map to closed failure types`() {
        val cases = listOf(
            SecurityException() to AppFailure.PermissionDenied,
            IOException() to AppFailure.StorageUnavailable,
            DatabaseAccessException() to AppFailure.DatabaseUnavailable,
            UnsupportedMediaException() to AppFailure.UnsupportedMediaFormat,
            MediaDecodingException() to AppFailure.DecodingFailed,
            CancellationException() to AppFailure.Cancelled,
        )

        cases.forEach { (cause, expected) ->
            assertEquals(expected, DefaultAppFailureMapper.map(cause))
        }
    }

    @Test
    fun `unknown exceptions remain diagnosable without retaining their message`() {
        val failure = DefaultAppFailureMapper.map(
            IllegalStateException("/storage/emulated/0/private-title.mp4"),
        )

        assertTrue(failure is AppFailure.Unknown)
        assertEquals("java.lang.IllegalStateException", (failure as AppFailure.Unknown).causeType)
        assertFalseContainsSensitiveData(failure.toString())
    }

    private fun assertFalseContainsSensitiveData(value: String) {
        check(!value.contains("private-title"))
        check(!value.contains("storage/emulated"))
    }
}
