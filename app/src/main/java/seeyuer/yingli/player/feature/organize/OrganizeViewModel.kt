package seeyuer.yingli.player.feature.organize

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import seeyuer.yingli.player.domain.library.FilterExpression
import seeyuer.yingli.player.domain.organize.OrganizeMutationResult
import seeyuer.yingli.player.domain.organize.OrganizeRepository
import seeyuer.yingli.player.domain.organize.OrganizeSnapshot
import seeyuer.yingli.player.domain.organize.TagColor
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.duplicates.DUPLICATE_HASH_ALGORITHM_VERSION
import seeyuer.yingli.player.domain.duplicates.DUPLICATE_SCAN_OPERATION_KEY_PREFIX
import seeyuer.yingli.player.domain.duplicates.DuplicateDeletionExecutor
import seeyuer.yingli.player.domain.duplicates.DuplicateDeletionPlan
import seeyuer.yingli.player.domain.duplicates.DuplicateDeletionResult
import seeyuer.yingli.player.domain.duplicates.DuplicateGroup
import seeyuer.yingli.player.domain.duplicates.DuplicateGroupId
import seeyuer.yingli.player.domain.duplicates.DuplicateMode
import seeyuer.yingli.player.domain.duplicates.DuplicateRepository
import seeyuer.yingli.player.domain.duplicates.DuplicateScanQueue
import seeyuer.yingli.player.domain.duplicates.DuplicateScanner
import seeyuer.yingli.player.domain.home.HomeRepository
import seeyuer.yingli.player.domain.library.FileOperationFailure
import seeyuer.yingli.player.domain.library.FileOperationResult
import seeyuer.yingli.player.domain.library.LibraryMutationRepository
import seeyuer.yingli.player.domain.library.TrashEntry
import seeyuer.yingli.player.domain.library.TrashRepository
import seeyuer.yingli.player.domain.processing.ProcessingProjectId
import seeyuer.yingli.player.domain.processing.ProcessingRepository
import seeyuer.yingli.player.domain.processing.ProcessingTaskState

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
    val duplicateSelections: Map<DuplicateGroupId, Set<MediaLocationId>> = emptyMap(),
    val duplicateScanning: Boolean = false,
    val duplicateStatusCode: String? = null,
    val pendingDeletion: DuplicateGroupId? = null,
    val libraryVideoCount: Int = 0,
    val libraryVideoBytes: Long = 0,
    val trashItems: List<TrashItemUi> = emptyList(),
    val trashSheetOpen: Boolean = false,
    val pendingPurge: TrashItemUi? = null,
    val clearTrashConfirmOpen: Boolean = false,
    val trashStatusCode: String? = null,
)

/**
 * 回收站面板的一行。
 *
 * [TrashEntry] 本身只有媒体 id、位置与时间戳，没有文件名也没有体积，因此能对齐设计稿的
 * 只有「移入多久」与「还剩多少保留期」两项；两者都由折叠时刻的时钟算出，不在 composable 里现算
 * （那里拿不到时钟，也测不到）。
 */
data class TrashItemUi(
    val entry: TrashEntry,
    val ageDays: Long,
    val remainingDays: Long,
)

class OrganizeViewModel(
    private val repository: OrganizeRepository,
    private val duplicateRepository: DuplicateRepository? = null,
    private val duplicateScanner: DuplicateScanner? = null,
    private val duplicateDeletionExecutor: DuplicateDeletionExecutor? = null,
    private val clock: AppClock? = null,
    private val libraryMutationRepository: LibraryMutationRepository? = null,
    private val trashRepository: TrashRepository? = null,
    private val homeRepository: HomeRepository? = null,
    private val duplicateScanQueue: DuplicateScanQueue? = null,
    private val processingRepository: ProcessingRepository? = null,
) : ViewModel() {
    private val editorKind = MutableStateFlow<OrganizeEditorKind?>(null)
    private val editorName = MutableStateFlow("")
    private val tagColor = MutableStateFlow(TagColor.NEUTRAL)
    private val mutationResult = MutableStateFlow<OrganizeMutationResult?>(null)
    private val duplicateMode = MutableStateFlow(DuplicateMode.EXACT)
    private val duplicateSelections = MutableStateFlow<Map<DuplicateGroupId, Set<MediaLocationId>>>(emptyMap())
    private val duplicateStatusCode = MutableStateFlow<String?>(null)
    private val pendingScanProject = MutableStateFlow<ProcessingProjectId?>(null)
    private val pendingDeletion = MutableStateFlow<DuplicateGroupId?>(null)
    private val trashSheetOpen = MutableStateFlow(false)
    private val pendingPurge = MutableStateFlow<TrashEntry?>(null)
    private val clearTrashConfirmOpen = MutableStateFlow(false)
    private val trashStatusCode = MutableStateFlow<String?>(null)
    private val groups = duplicateRepository?.groups ?: flowOf(emptyList())
    private val trashEntries = trashRepository?.observe() ?: flowOf(emptyList())
    private val libraryStats = homeRepository?.observeStats() ?: flowOf(0 to 0L)

    /**
     * 扫描是否在跑由**任务中心**回答，而不是由页面自己记一个布尔量
     * （G21：页面记的布尔量离开页面就没了，任务里的状态才是真的）。
     */
    private val duplicateScanning = (processingRepository?.tasks ?: flowOf(emptyList())).map { tasks ->
        tasks.any { task ->
            task.operationKey.startsWith(DUPLICATE_SCAN_OPERATION_KEY_PREFIX) && !task.state.terminal
        }
    }

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

    private val trashState = combine(
        trashEntries,
        trashSheetOpen,
        pendingPurge,
        clearTrashConfirmOpen,
        trashStatusCode,
    ) { entries, sheetOpen, purgeCandidate, clearConfirm, status ->
        val now = nowEpochMillis()
        TrashState(
            items = entries.map { it.toItemUi(now) },
            sheetOpen = sheetOpen,
            pendingPurge = purgeCandidate?.toItemUi(now),
            clearConfirmOpen = clearConfirm,
            statusCode = status,
        )
    }

    val state: StateFlow<OrganizeUiState> = combine(
        repository.snapshot,
        editorState,
        duplicateState,
        trashState,
        libraryStats,
    ) { snapshot, editor, duplicate, trash, stats -> OrganizeUiState(
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
        libraryVideoCount = stats.first,
        libraryVideoBytes = stats.second,
        trashItems = trash.items,
        trashSheetOpen = trash.sheetOpen,
        pendingPurge = trash.pendingPurge,
        clearTrashConfirmOpen = trash.clearConfirmOpen,
        trashStatusCode = trash.statusCode,
    ) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), OrganizeUiState())

    init {
        // 扫描跑在任务中心，页面拿不到它的返回值；终态只能由任务流回报。
        // 只认**本次入队**的那个项目：否则历史里已成功的扫描会让页面一进来就报「扫描完成」。
        viewModelScope.launch {
            combine(
                processingRepository?.tasks ?: flowOf(emptyList()),
                pendingScanProject,
            ) { tasks, projectId -> projectId?.let { id -> tasks.firstOrNull { it.projectId == id } } }
                .filterNotNull()
                .collect { task ->
                    when {
                        task.state == ProcessingTaskState.SUCCEEDED -> {
                            pendingScanProject.value = null
                            duplicateStatusCode.value = SCAN_COMPLETED
                        }

                        task.state == ProcessingTaskState.CANCELED -> {
                            pendingScanProject.value = null
                            duplicateStatusCode.value = SCAN_CANCELED
                        }

                        task.state == ProcessingTaskState.FAILED -> {
                            pendingScanProject.value = null
                            duplicateStatusCode.value = task.errorCode ?: SCAN_ENQUEUE_FAILED
                        }
                    }
                }
        }
    }

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
        val queue = duplicateScanQueue ?: return
        duplicateStatusCode.value = null
        viewModelScope.launch {
            // 扫描交给任务中心（设计稿 §11.1）：这里只负责入队与回报入队结果，
            // 进度、取消、重试都在任务中心。结果由 groups 这条流自己送回来。
            val projectId = runCatching { queue.enqueue(duplicateMode.value) }.getOrNull()
            duplicateStatusCode.value = if (projectId == null) SCAN_ENQUEUE_FAILED else SCAN_ENQUEUED
            if (projectId != null) pendingScanProject.value = projectId
            duplicateSelections.value = emptyMap()
        }
    }

    fun cancelDuplicateScan() {
        viewModelScope.launch { duplicateScanner?.cancel() }
    }

    fun toggleDuplicateTrash(groupId: DuplicateGroupId, locationId: MediaLocationId) {
        val group = state.value.duplicateGroups.firstOrNull { it.id == groupId } ?: return
        val current = duplicateSelections.value[groupId].orEmpty()
        val updated = if (locationId in current) current - locationId else current + locationId
        if (updated.size >= group.candidates.size) return
        duplicateSelections.value = duplicateSelections.value + (groupId to updated)
        duplicateStatusCode.value = null
    }

    fun ignoreDuplicateGroup(groupId: DuplicateGroupId) {
        val group = state.value.duplicateGroups.firstOrNull { it.id == groupId } ?: return
        viewModelScope.launch { duplicateRepository?.ignore(group, nowEpochMillis()) }
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
        val all = group.candidates.map { it.locationId }.toSet()
        val plan = DuplicateDeletionPlan(
            groupId = groupId,
            contentHash = group.contentHash,
            sizeBytes = group.sizeBytes,
            keepLocationIds = all - trash,
            trashLocationIds = trash,
            algorithmVersion = DUPLICATE_HASH_ALGORITHM_VERSION,
            createdAtEpochMillis = nowEpochMillis(),
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

    fun openTrash() {
        pendingPurge.value = null
        clearTrashConfirmOpen.value = false
        trashStatusCode.value = null
        trashSheetOpen.value = true
    }

    fun closeTrash() {
        trashSheetOpen.value = false
        pendingPurge.value = null
        clearTrashConfirmOpen.value = false
    }

    fun restore(entry: TrashEntry) {
        val mutations = libraryMutationRepository ?: return
        pendingPurge.value = null
        trashStatusCode.value = null
        viewModelScope.launch {
            trashStatusCode.value = when (val result = mutations.restore(entry)) {
                is FileOperationResult.Success -> TRASH_RESTORED
                is FileOperationResult.RecoverableFailure -> "${TRASH_FAILED_PREFIX}${result.reason.name}"
                is FileOperationResult.PartialSuccess -> "${TRASH_FAILED_PREFIX}${FileOperationFailure.PARTIAL.name}"
            }
        }
    }

    fun requestPurge(entry: TrashEntry) {
        pendingPurge.value = entry
    }

    fun dismissPurge() {
        pendingPurge.value = null
    }

    fun confirmPurge() {
        val mutations = libraryMutationRepository ?: return
        val entry = pendingPurge.value ?: return
        pendingPurge.value = null
        trashStatusCode.value = null
        viewModelScope.launch {
            trashStatusCode.value = when (val result = mutations.purge(entry)) {
                is FileOperationResult.Success -> TRASH_PURGED
                is FileOperationResult.RecoverableFailure -> "${TRASH_FAILED_PREFIX}${result.reason.name}"
                is FileOperationResult.PartialSuccess -> "${TRASH_FAILED_PREFIX}${FileOperationFailure.PARTIAL.name}"
            }
        }
    }

    fun requestClearTrash() {
        if (state.value.trashItems.isEmpty()) return
        clearTrashConfirmOpen.value = true
    }

    fun dismissClearTrash() {
        clearTrashConfirmOpen.value = false
    }

    fun confirmClearTrash() {
        val mutations = libraryMutationRepository ?: return
        val entries = state.value.trashItems.map { it.entry }
        clearTrashConfirmOpen.value = false
        if (entries.isEmpty()) return
        trashStatusCode.value = null
        viewModelScope.launch {
            // 数据层只提供单项 purge，清空就是逐项调用；任一项失败即报告第一个失败原因，
            // 已成功的那些不会回滚（它们确实已经被永久删除了，回滚反而是在撒谎）。
            val failure = entries.firstNotNullOfOrNull { entry ->
                (mutations.purge(entry) as? FileOperationResult.RecoverableFailure)?.reason
            }
            trashStatusCode.value = failure?.let { "${TRASH_FAILED_PREFIX}${it.name}" } ?: TRASH_CLEARED
        }
    }

    private fun nowEpochMillis(): Long = clock?.now()?.toEpochMilli() ?: 0L

    private fun TrashEntry.toItemUi(nowEpochMillis: Long): TrashItemUi = TrashItemUi(
        entry = this,
        ageDays = (nowEpochMillis - deletedAtEpochMillis).coerceAtLeast(0L) / MILLIS_PER_DAY,
        remainingDays = (purgeAtEpochMillis - nowEpochMillis).coerceAtLeast(0L) / MILLIS_PER_DAY,
    )

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
        val selections: Map<DuplicateGroupId, Set<MediaLocationId>>,
        val scanning: Boolean,
        val statusCode: String?,
        val pendingDeletion: DuplicateGroupId?,
    )

    private data class TrashState(
        val items: List<TrashItemUi>,
        val sheetOpen: Boolean,
        val pendingPurge: TrashItemUi?,
        val clearConfirmOpen: Boolean,
        val statusCode: String?,
    )

    companion object {
        private const val STOP_TIMEOUT = 5_000L
        private const val MAX_NAME_LENGTH = 80
        private const val MILLIS_PER_DAY = 86_400_000L

        /** 恢复成功的反馈码（面板与页面共用，和去重的 `*_COMPLETED_*` 同一套约定）。 */
        const val TRASH_RESTORED = "TRASH_RESTORED"

        /** 单项永久删除成功的反馈码。 */
        const val TRASH_PURGED = "TRASH_PURGED"

        /** 清空成功的反馈码。 */
        const val TRASH_CLEARED = "TRASH_CLEARED"

        /** 失败反馈码前缀，完整形式为 `TRASH_FAILED_<FileOperationFailure>`。 */
        const val TRASH_FAILED_PREFIX = "TRASH_FAILED_"

        /** 去重扫描已排进任务中心。结果由 `groups` 流自动送回来，页面不再自报完成。 */
        const val SCAN_ENQUEUED = "SCAN_ENQUEUED"

        /** 去重扫描入队失败。 */
        const val SCAN_ENQUEUE_FAILED = "SCAN_ENQUEUE_FAILED"

        /**
         * 本次入队的去重扫描已成功结束。
         *
         * 组数**不写进状态码**：状态码要经过若干个流才到 UI，写死数字会在结果流刷新之前
         * 定下个数（典型是报「找到 0 组」）。UI 在渲染时读当前 state 的组数，永远是最新的。
         */
        const val SCAN_COMPLETED = "SCAN_COMPLETED"

        /** 本次入队的去重扫描被取消。 */
        const val SCAN_CANCELED = "SCAN_CANCELED"

        fun factory(
            repository: OrganizeRepository,
            duplicateRepository: DuplicateRepository? = null,
            duplicateScanner: DuplicateScanner? = null,
            duplicateDeletionExecutor: DuplicateDeletionExecutor? = null,
            clock: AppClock? = null,
            libraryMutationRepository: LibraryMutationRepository? = null,
            trashRepository: TrashRepository? = null,
            homeRepository: HomeRepository? = null,
            duplicateScanQueue: DuplicateScanQueue? = null,
            processingRepository: ProcessingRepository? = null,
        ) = viewModelFactory {
            initializer {
                OrganizeViewModel(
                    repository,
                    duplicateRepository,
                    duplicateScanner,
                    duplicateDeletionExecutor,
                    clock,
                    libraryMutationRepository,
                    trashRepository,
                    homeRepository,
                    duplicateScanQueue,
                    processingRepository,
                )
            }
        }
    }
}
