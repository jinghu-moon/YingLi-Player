package seeyuer.yingli.player.feature.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import seeyuer.yingli.player.domain.settings.BackupConflictStrategy
import seeyuer.yingli.player.domain.settings.BackupGateway
import seeyuer.yingli.player.domain.settings.BackupPreview
import seeyuer.yingli.player.domain.settings.BackupSelection
import seeyuer.yingli.player.domain.settings.BackupSnapshot
import seeyuer.yingli.player.domain.settings.DiagnosticReport
import seeyuer.yingli.player.domain.settings.DiagnosticsReporter
import seeyuer.yingli.player.domain.settings.SettingsDocumentGateway
import seeyuer.yingli.player.domain.settings.UpdateCheckResult
import seeyuer.yingli.player.domain.settings.UpdateSource
import seeyuer.yingli.player.testing.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `backup waits for system document destination and reports success`() = runTest {
        val documents = FakeDocuments()
        val viewModel = createViewModel(documents = documents)
        val effect = async { viewModel.effect.first() }

        viewModel.requestBackup()
        advanceUntilIdle()

        assertEquals("YingLi-backup.json", (effect.await() as SettingsEffect.CreateDocument).displayName)
        viewModel.onDocumentCreated("content://backup/1")
        advanceUntilIdle()
        assertEquals(SettingsOperationStatus.BACKUP_SAVED, viewModel.state.value.status)
        assertEquals(true, documents.written.startsWith("{"))
    }

    @Test
    fun `restore previews conflicts before applying selected strategy`() = runTest {
        val snapshot = BackupSnapshot(createdAtEpochMillis = 1, selection = BackupSelection(history = false))
        val documents = FakeDocuments(seeyuer.yingli.player.domain.settings.BackupCodec.encode(snapshot))
        val backup = FakeBackupGateway(snapshot)
        val viewModel = createViewModel(backup, documents)

        viewModel.onRestoreDocumentSelected("content://backup/1")
        advanceUntilIdle()
        assertEquals(2, viewModel.state.value.preview?.conflictCount)

        viewModel.confirmRestore(BackupConflictStrategy.KEEP_EXISTING)
        advanceUntilIdle()
        assertEquals(BackupConflictStrategy.KEEP_EXISTING, backup.restoredWith)
        assertEquals(SettingsOperationStatus.RESTORE_COMPLETED, viewModel.state.value.status)
    }

    private fun createViewModel(
        backup: FakeBackupGateway = FakeBackupGateway(),
        documents: FakeDocuments = FakeDocuments(),
    ) = SettingsViewModel(
        backup,
        object : DiagnosticsReporter {
            override suspend fun createReport() = DiagnosticReport("safe", 0)
        },
        documents,
        object : UpdateSource {
            override suspend fun check(currentVersion: String) = UpdateCheckResult.Current
        },
        "0.1.0",
    )

    private class FakeBackupGateway(
        private val snapshot: BackupSnapshot = BackupSnapshot(createdAtEpochMillis = 1),
    ) : BackupGateway {
        var restoredWith: BackupConflictStrategy? = null
        override suspend fun export(selection: BackupSelection) = snapshot.copy(selection = selection)
        override suspend fun preview(snapshot: BackupSnapshot) = BackupPreview(1, 1, 1, 1, 1, 1, 2)
        override suspend fun restore(snapshot: BackupSnapshot, strategy: BackupConflictStrategy): BackupPreview {
            restoredWith = strategy
            return preview(snapshot)
        }
    }

    private class FakeDocuments(private val readable: String? = null) : SettingsDocumentGateway {
        var written = ""
        override suspend fun read(uri: String): String? = readable
        override suspend fun write(uri: String, content: String): Boolean {
            written = content
            return true
        }
    }
}
