package seeyuer.yingli.player.core.foundation

import seeyuer.yingli.player.testing.CapturingLogSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveValueRedactorTest {
    private val redactor = DefaultSensitiveValueRedactor()

    @Test
    fun `redacts paths uris queries and key material`() {
        val unsafe =
            "Open C:\\Users\\mei\\Movies\\family.mp4 or /storage/emulated/0/Movies/family.mp4 " +
                "from content://media/external/video/42?token=plain-secret password=hunter2"

        val safe = redactor.redact(unsafe)

        assertFalse(safe.contains("family.mp4"))
        assertFalse(safe.contains("plain-secret"))
        assertFalse(safe.contains("hunter2"))
        assertTrue(safe.contains("[REDACTED_PATH]"))
        assertTrue(safe.contains("[REDACTED_URI]"))
        assertTrue(safe.contains("password=[REDACTED]"))
    }

    @Test
    fun `logger preserves diagnostic code and hides sensitive attributes`() {
        val sink = CapturingLogSink()
        val logger = RedactingAppLogger(redactor, sink)

        logger.log(
            level = AppLogLevel.ERROR,
            event = AppLogEvent(
                code = "MEDIA_OPEN_FAILED",
                message = "Unable to open content://vault/items/9?key=secret",
                attributes = mapOf(
                    "operation" to LogValue.Public("open_media"),
                    "vault_title" to LogValue.Sensitive("Family archive"),
                ),
            ),
        )

        val record = sink.records.single()
        assertEquals("MEDIA_OPEN_FAILED", record.code)
        assertEquals("open_media", record.attributes["operation"])
        assertEquals("[REDACTED]", record.attributes["vault_title"])
        assertFalse(record.message.contains("secret"))
        assertFalse(record.toString().contains("Family archive"))
    }
}
