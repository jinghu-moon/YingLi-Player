package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackErrorMapperTest {
    @Test
    fun `every technical signal maps to a stable user-facing category`() {
        val expected = mapOf(
            PlaybackFailureSignal.ACCESS_DENIED to PlaybackErrorKind.PERMISSION,
            PlaybackFailureSignal.NOT_FOUND to PlaybackErrorKind.FILE_MISSING,
            PlaybackFailureSignal.CONTAINER_UNSUPPORTED to PlaybackErrorKind.UNSUPPORTED_CONTAINER,
            PlaybackFailureSignal.DECODER_UNAVAILABLE to PlaybackErrorKind.UNSUPPORTED_DECODER,
            PlaybackFailureSignal.MALFORMED_MEDIA to PlaybackErrorKind.CORRUPT_MEDIA,
            PlaybackFailureSignal.OTHER to PlaybackErrorKind.UNKNOWN,
        )

        expected.forEach { (signal, kind) ->
            val error = DefaultPlaybackErrorMapper.map(signal)
            assertEquals(kind, error.kind)
            assertEquals("Error diagnostics must not contain a URI", false, error.diagnosticCode.contains("://"))
        }
    }
}
