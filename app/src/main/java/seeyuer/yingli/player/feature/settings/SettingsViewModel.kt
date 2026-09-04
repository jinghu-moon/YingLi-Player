package seeyuer.yingli.player.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import seeyuer.yingli.player.domain.settings.BackupCodec
import seeyuer.yingli.player.domain.settings.BackupConflictStrategy
import seeyuer.yingli.player.domain.settings.BackupDecodeResult
import seeyuer.yingli.player.domain.settings.BackupGateway
import seeyuer.yingli.player.domain.settings.BackupPreview
import seeyuer.yingli.player.domain.settings.BackupSelection
import seeyuer.yingli.player.domain.settings.BackupSnapshot
import seeyuer.yingli.player.domain.settings.DiagnosticsReporter
import seeyuer.yingli.player.domain.settings.ReleaseInfo
import seeyuer.yingli.player.domain.settings.SettingsDocumentGateway
import seeyuer.yingli.player.domain.settings.UpdateCheckResult
import seeyuer.yingli.player.domain.settings.UpdateSource

enum class SettingsOperationStatus {
    IDLE,
    WORKING,
    BACKUP_SAVED,
    RESTORE_COMPLETED,
    DIAGNOSTICS_SAVED,
    INVALID_DOCUMENT,
    OPERATION_FAILED,
    UP_TO_DATE,
    UPDATE_UNAVAILABLE,
    UPDATE_RATE_LIMITED,
}

data class SettingsToolsState(
    val selection: BackupSelection = BackupSelection(),
    val status: SettingsOperationStatus = SettingsOperationStatus.IDLE,
    val preview: BackupPreview? = null,
    val release: ReleaseInfo? = null,
)

sealed interface SettingsEffect {
    data class CreateDocument(val displayName: String, val mimeType: String, val content: String) : SettingsEffect
    data object OpenBackupDocument : SettingsEffect
}

class SettingsViewModel(
    private val backupGateway: BackupGateway,
    private val diagnosticsReporter: DiagnosticsReporter,
    private val documentGateway: SettingsDocumentGateway,
    private val updateSource: UpdateSource,
    private val currentVersion: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsToolsState())
    val state: StateFlow<SettingsToolsState> = mutableState.asStateFlow()
    private val effects = Channel<SettingsEffect>(Channel.BUFFERED)
    val effect = effects.receiveAsFlow()
    private var pendingDocument: String? = null
    private var pendingDocumentSuccess = SettingsOperationStatus.BACKUP_SAVED
    private var pendingRestore: BackupSnapshot? = null

    fun setSelection(selection: BackupSelection) {
        mutableState.value = mutableState.value.copy(selection = selection)
    }

    fun requestBackup() {
        val selection = mutableState.value.selection
        if (selection.isEmpty) return
        runOperation {
            val content = BackupCodec.encode(backupGateway.export(selection))
            pendingDocument = content
            pendingDocumentSuccess = SettingsOperationStatus.BACKUP_SAVED
            effects.send(SettingsEffect.CreateDocument("YingLi-backup.json", JSON_MIME_TYPE, content))
        }
    }

    fun onDocumentCreated(uri: String?) {
        val content = pendingDocument ?: return
        val success = pendingDocumentSuccess
        pendingDocument = null
        if (uri == null) {
            mutableState.value = mutableState.value.copy(status = SettingsOperationStatus.IDLE)
            return
        }
        runOperation(success) {
            check(documentGateway.write(uri, content))
        }
    }

    fun requestRestore() {
        viewModelScope.launch { effects.send(SettingsEffect.OpenBackupDocument) }
    }

    fun onRestoreDocumentSelected(uri: String?) {
        if (uri == null) return
        runOperation {
            when (val decoded = documentGateway.read(uri)?.let(BackupCodec::decode)) {
                is BackupDecodeResult.Success -> {
                    pendingRestore = decoded.snapshot
                    mutableState.value = mutableState.value.copy(
                        status = SettingsOperationStatus.IDLE,
                        preview = backupGateway.preview(decoded.snapshot),
                    )
                }
                else -> mutableState.value = mutableState.value.copy(status = SettingsOperationStatus.INVALID_DOCUMENT)
            }
        }
    }

    fun confirmRestore(strategy: BackupConflictStrategy) {
        val snapshot = pendingRestore ?: return
        runOperation(SettingsOperationStatus.RESTORE_COMPLETED) {
            backupGateway.restore(snapshot, strategy)
            pendingRestore = null
            mutableState.value = mutableState.value.copy(preview = null)
        }
    }

    fun dismissRestorePreview() {
        pendingRestore = null
        mutableState.value = mutableState.value.copy(preview = null)
    }

    fun requestDiagnostics() {
        runOperation {
            val report = diagnosticsReporter.createReport()
            pendingDocument = report.content
            pendingDocumentSuccess = SettingsOperationStatus.DIAGNOSTICS_SAVED
            effects.send(SettingsEffect.CreateDocument("YingLi-diagnostics.txt", TEXT_MIME_TYPE, report.content))
        }
    }

    fun checkForUpdates() {
        runOperation {
            when (val result = updateSource.check(currentVersion)) {
                is UpdateCheckResult.Available -> mutableState.value = mutableState.value.copy(
                    status = SettingsOperationStatus.IDLE,
                    release = result.release,
                )
                UpdateCheckResult.Current -> setStatus(SettingsOperationStatus.UP_TO_DATE)
                UpdateCheckResult.RateLimited -> setStatus(SettingsOperationStatus.UPDATE_RATE_LIMITED)
                UpdateCheckResult.InvalidResponse,
                UpdateCheckResult.Unavailable,
                -> setStatus(SettingsOperationStatus.UPDATE_UNAVAILABLE)
            }
        }
    }

    fun clearRelease() {
        mutableState.value = mutableState.value.copy(release = null)
    }

    private fun runOperation(
        success: SettingsOperationStatus = SettingsOperationStatus.IDLE,
        block: suspend () -> Unit,
    ) {
        if (mutableState.value.status == SettingsOperationStatus.WORKING) return
        viewModelScope.launch {
            setStatus(SettingsOperationStatus.WORKING)
            runCatching { block() }
                .onSuccess {
                    if (mutableState.value.status == SettingsOperationStatus.WORKING) setStatus(success)
                }
                .onFailure { setStatus(SettingsOperationStatus.OPERATION_FAILED) }
        }
    }

    private fun setStatus(status: SettingsOperationStatus) {
        mutableState.value = mutableState.value.copy(status = status)
    }

    companion object {
        private const val JSON_MIME_TYPE = "application/json"
        private const val TEXT_MIME_TYPE = "text/plain"

        fun factory(
            backupGateway: BackupGateway,
            diagnosticsReporter: DiagnosticsReporter,
            documentGateway: SettingsDocumentGateway,
            updateSource: UpdateSource,
            currentVersion: String,
        ) = viewModelFactory {
            initializer {
                SettingsViewModel(
                    backupGateway,
                    diagnosticsReporter,
                    documentGateway,
                    updateSource,
                    currentVersion,
                )
            }
        }
    }
}
