package seeyuer.yingli.player.feature.organize

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import seeyuer.yingli.player.domain.library.FilterExpression
import seeyuer.yingli.player.domain.organize.OrganizeMutationResult
import seeyuer.yingli.player.domain.organize.OrganizeRepository
import seeyuer.yingli.player.domain.organize.OrganizeSnapshot
import seeyuer.yingli.player.domain.organize.TagColor
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.duplicates.DuplicateDeletionExecutor
import seeyuer.yingli.player.domain.duplicates.DuplicateDeletionPlan
import seeyuer.yingli.player.domain.duplicates.DuplicateDeletionResult
import seeyuer.yingli.player.domain.duplicates.DuplicateGroup
import seeyuer.yingli.player.domain.duplicates.DuplicateGroupId
import seeyuer.yingli.player.domain.duplicates.DuplicateMode
import seeyuer.yingli.player.domain.duplicates.DuplicateRepository
import seeyuer.yingli.player.domain.duplicates.DuplicateScanResult
import seeyuer.yingli.player.domain.duplicates.DuplicateScanner

enum class OrganizeEditorKind {
    TAG,
    PLAYLIST,
    SMART_COLLECTION,
}

data class OrganizeUiState(
    val snapshot: OrganizeSnapshot = OrganizeSnapshot(),
    val editorKind: OrganizeEditorKind? = null,
    val editorName: String = "",
    val tagColor: TagColor = TagColor.NEUTRAL,
    val mutationResult: OrganizeMutationResult? = null,
    val duplicateMode: DuplicateMode = DuplicateMode.EXACT,
    val duplicateGroups: List<DuplicateGroup> = emptyList(),
    val duplicateSelections: Map<DuplicateGroupId, Set<MediaItemId>> = emptyMap(),
    val duplicateScanning: Boolean = false,
    val duplicateStatusCode: String? = null,
    val pendingDeletion: DuplicateGroupId? = null,
)

class OrganizeViewModel(
    private val repository: OrganizeRepository,
    private val duplicateRepository: DuplicateRepository? = null,
    private val duplicateScanner: DuplicateScanner? = null,
    private val duplicateDeletionExecutor: DuplicateDeletionExecutor? = null,
    private val clock: AppClock? = null,
) : ViewModel() {
    private val editorKind = MutableStateFlow<OrganizeEditorKind?>(null)
    private val editorName = MutableStateFlow("")
    private val tagColor = MutableStateFlow(TagColor.NEUTRAL)
    private val mutationResult = MutableStateFlow<OrganizeMutationResult?>(null)
    private val duplicateMode = MutableStateFlow(DuplicateMode.EXACT)
    private val duplicateSelections = MutableStateFlow<Map<DuplicateGroupId, Set<MediaItemId>>>(emptyMap())
    private val duplicateScanning = MutableStateFlow(false)
    private val duplicateStatusCode = MutableStateFlow<String?>(null)
    private val pendingDeletion = MutableStateFlow<DuplicateGroupId?>(null)
    private val groups = duplicateRepository?.groups ?: flowOf(emptyList())

    private val editorState = combine(editorKind, editorName, tagColor, mutationResult, ::EditorState)
    private val duplicateState = combine(
        groups,
        duplicateMode,
        duplicateSelections,
        duplicateScanning,
        combine(duplicateStatusCode, pendingDeletion, ::DuplicateOperationState),
    ) { groupValues, mode, selections, scanning, operation ->
        DuplicateState(groupValues, mode, selections, scanning, operation.statusCode, operation.pendingDeletion)
    }

    val state: StateFlow<OrganizeUiState> = combine(
        repository.snapshot,
        editorState,
        duplicateState,
    ) { snapshot, editor, duplicate -> OrganizeUiState(
        snapshot = snapshot,
        editorKind = editor.kind,
        editorName = editor.name,
        tagColor = editor.color,
        mutationResult = editor.result,
        duplicateMode = duplicate.mode,
        duplicateGroups = duplicate.groups,
        duplicateSelections = duplicate.selections,
        duplicateScanning = duplicate.scanning,
        duplicateStatusCode = duplicate.statusCode,
        pendingDeletion = duplicate.pendingDeletion,
    ) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), OrganizeUiState())

    fun openEditor(kind: OrganizeEditorKind) {
        editorKind.value = kind
        editorName.value = ""
        tagColor.value = TagColor.NEUTRAL
        mutationResult.value = null
    }

    fun closeEditor() {
        editorKind.value = null
        mutationResult.value = null
    }

    fun setName(value: String) {
        editorName.value = value.take(MAX_NAME_LENGTH)
        mutationResult.value = null
    }

    fun setTagColor(value: TagColor) {
        tagColor.value = value
    }

    fun save() {
        val kind = editorKind.value ?: return
        viewModelScope.launch {
            val result = when (kind) {
                OrganizeEditorKind.TAG -> repository.createTag(editorName.value, tagColor.value)
                OrganizeEditorKind.PLAYLIST -> repository.createPlaylist(editorName.value, emptyList())
                OrganizeEditorKind.SMART_COLLECTION -> repository.createSmartCollection(editorName.value, FilterExpression())
            }
            mutationResult.value = result
            if (result == OrganizeMutationResult.Success) closeEditor()
        }
    }

    fun setDuplicateMode(mode: DuplicateMode) {
        duplicateMode.value = mode
        duplicateStatusCode.value = null
    }

    fun scanDuplicates() {
        val scanner = duplicateScanner ?: return
        if (duplicateScanning.value) return
        viewModelScope.launch {
            duplicateScanning.value = true
            duplicateStatusCode.value = null
            when (val result = scanner.scan(duplicateMode.value)) {
                is DuplicateScanResult.Completed -> {
                    duplicateSelections.value = emptyMap()
                    duplicateStatusCode.value = "SCAN_COMPLETED_${result.groups.size}"
                }
                is DuplicateScanResult.Rejected -> duplicateStatusCode.value = result.code
                DuplicateScanResult.Canceled -> duplicateStatusCode.value = "SCAN_CANCELED"
            }
            duplicateScanning.value = false
        }
    }

    fun cancelDuplicateScan() {
        viewModelScope.launch { duplicateScanner?.cancel() }
    }

    fun toggleDuplicateTrash(groupId: DuplicateGroupId, mediaId: MediaItemId) {
        val group = state.value.duplicateGroups.firstOrNull { it.id == groupId } ?: return
        val current = duplicateSelections.value[groupId].orEmpty()
        val updated = if (mediaId in current) current - mediaId else current + mediaId
        if (updated.size >= group.candidates.size) return
        duplicateSelections.value = duplicateSelections.value + (groupId to updated)
        duplicateStatusCode.value = null
    }

    fun ignoreDuplicateGroup(groupId: DuplicateGroupId) {
        viewModelScope.launch { duplicateRepository?.ignore(groupId) }
    }

    fun requestDuplicateDeletion(groupId: DuplicateGroupId) {
        if (duplicateSelections.value[groupId].isNullOrEmpty()) return
        pendingDeletion.value = groupId
    }

    fun dismissDuplicateDeletion() {
        pendingDeletion.value = null
    }

    fun confirmDuplicateDeletion() {
        val executor = duplicateDeletionExecutor ?: return
        val groupId = pendingDeletion.value ?: return
        val group = state.value.duplicateGroups.firstOrNull { it.id == groupId } ?: return
        val trash = duplicateSelections.value[groupId].orEmpty()
        if (trash.isEmpty() || trash.size >= group.candidates.size) return
        val all = group.candidates.map { it.mediaId }.toSet()
        val plan = DuplicateDeletionPlan(
            groupId,
            keepMediaIds = all - trash,
            trashMediaIds = trash,
            evidenceAlgorithmVersion = group.evidence.algorithmVersion,
            createdAtEpochMillis = clock?.now()?.toEpochMilli() ?: 0,
        )
        pendingDeletion.value = null
        viewModelScope.launch {
            duplicateStatusCode.value = when (val result = executor.execute(plan)) {
                is DuplicateDeletionResult.Completed -> "TRASH_COMPLETED_${result.trashedCount}"
                is DuplicateDeletionResult.Partial -> "TRASH_PARTIAL_${result.trashedCount}_${result.failedCount}"
                is DuplicateDeletionResult.Rejected -> result.code
            }
            duplicateSelections.value = duplicateSelections.value - groupId
        }
    }

    private data class EditorState(
        val kind: OrganizeEditorKind?,
        val name: String,
        val color: TagColor,
        val result: OrganizeMutationResult?,
    )

    private data class DuplicateOperationState(val statusCode: String?, val pendingDeletion: DuplicateGroupId?)
    private data class DuplicateState(
        val groups: List<DuplicateGroup>,
        val mode: DuplicateMode,
        val selections: Map<DuplicateGroupId, Set<MediaItemId>>,
        val scanning: Boolean,
        val statusCode: String?,
        val pendingDeletion: DuplicateGroupId?,
    )

    companion object {
        private const val STOP_TIMEOUT = 5_000L
        private const val MAX_NAME_LENGTH = 80
        fun factory(
            repository: OrganizeRepository,
            duplicateRepository: DuplicateRepository? = null,
            duplicateScanner: DuplicateScanner? = null,
            duplicateDeletionExecutor: DuplicateDeletionExecutor? = null,
            clock: AppClock? = null,
        ) = viewModelFactory {
            initializer {
                OrganizeViewModel(
                    repository,
                    duplicateRepository,
                    duplicateScanner,
                    duplicateDeletionExecutor,
                    clock,
                )
            }
        }
    }
}
