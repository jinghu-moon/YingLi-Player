package seeyuer.yingli.player.core.foundation

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticLogStoreTest {
    @Test
    fun `rolling store keeps only the newest bounded records`() {
        val store = RollingDiagnosticLogStore(maximumRecords = 2)

        listOf("ONE", "TWO", "THREE").forEach { code ->
            store.emit(AppLogRecord(AppLogLevel.INFO, code, code, emptyMap()))
        }

        assertEquals(listOf("TWO", "THREE"), store.snapshot().map(AppLogRecord::code))
    }
}
