package seeyuer.yingli.player.feature.organize

import android.content.IntentSender
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
import seeyuer.yingli.player.domain.duplicates.DuplicateKeepRanking
import seeyuer.yingli.player.domain.duplicates.DuplicateMode
import seeyuer.yingli.player.domain.duplicates.DuplicateRepository
import seeyuer.yingli.player.domain.duplicates.DuplicateScanQueue
import seeyuer.yingli.player.domain.duplicates.DuplicateScanner
import seeyuer.yingli.player.domain.home.HomeRepository
import seeyuer.yingli.player.domain.processing.ProcessingProjectId
import seeyuer.yingli.player.domain.processing.ProcessingRepository
import seeyuer.yingli.player.domain.processing.ProcessingTaskState
import seeyuer.yingli.player.domain.recycle.RecycleAction
import seeyuer.yingli.player.domain.recycle.RecycleAuthorizationRequest
import seeyuer.yingli.player.domain.recycle.RecycleQueue
import seeyuer.yingli.player.domain.recycle.RecycleTarget
import seeyuer.yingli.player.domain.recycle.TrashEntry
import seeyuer.yingli.player.domain.recycle.TrashOperationOutcome
import seeyuer.yingli.player.domain.recycle.TrashRepository
import seeyuer.yingli.player.domain.recycle.TrashService
import seeyuer.yingli.player.domain.recycle.TrashState
import seeyuer.yingli.player.data.recycle.RecycleAuthorizationLauncher

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
    /**
     * 用户显式选定的「保留项」（§14.7 第 1 项）。
     *
     * 缺省值不在状态里现算：`DuplicateKeepRanking` 会在用户没有选择时提供默认保留项，
     * 界面只显示它，不把默认值写回这里 —— 否则「用户选过」与「系统推荐」就分不开了。
     */
    val duplicateKeepers: Map<DuplicateGroupId, MediaLocationId> = emptyMap(),
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
    /**
     * 正在等待用户授权的系统对话框（§8.4 第 9 步：**必须由前台发起**）。
     * 非空时页面负责把它交给系统并回报结果，见 `OrganizeScreen` 里的 launcher。
     */
    val pendingTrashAuthorization: RecycleAuthorizationRequest? = null,
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
    private val trashRepository: TrashRepository? = null,
    private val homeRepository: HomeRepository? = null,
    private val duplicateScanQueue: DuplicateScanQueue? = null,
    private val processingRepository: ProcessingRepository? = null,
    private val trashService: TrashService? = null,
    private val recycleQueue: RecycleQueue? = null,
    private val authorizationLauncher: RecycleAuthorizationLauncher? = null,
) : ViewModel() {
    private val editorKind = MutableStateFlow<OrganizeEditorKind?>(null)
    private val editorName = MutableStateFlow("")
    private val tagColor = MutableStateFlow(TagColor.NEUTRAL)
    private val mutationResult = MutableStateFlow<OrganizeMutationResult?>(null)
    private val duplicateMode = MutableStateFlow(DuplicateMode.EXACT)
    private val duplicateSelections = MutableStateFlow<Map<DuplicateGroupId, Set<MediaLocationId>>>(emptyMap())
    private val duplicateKeepers = MutableStateFlow<Map<DuplicateGroupId, MediaLocationId>>(emptyMap())
    private val duplicateStatusCode = MutableStateFlow<String?>(null)
    private val pendingScanProject = MutableStateFlow<ProcessingProjectId?>(null)
    private val pendingDeletion = MutableStateFlow<DuplicateGroupId?>(null)
    private val trashSheetOpen = MutableStateFlow(false)
    private val pendingPurge = MutableStateFlow<TrashEntry?>(null)
    private val clearTrashConfirmOpen = MutableStateFlow(false)
    private val trashStatusCode = MutableStateFlow<String?>(null)
    private val pendingAuthorization = MutableStateFlow<RecycleAuthorizationRequest?>(null)
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
    /** 「打算删哪些」与「保留哪一个」是一对意图，合并成一条流以免 combine 过载。 */
    private val duplicateIntent = combine(duplicateSelections, duplicateKeepers) { selections, keepers ->
        selections to keepers
    }
    private val duplicateState = combine(
        groups,
        duplicateMode,
        duplicateIntent,
        duplicateScanning,
        combine(duplicateStatusCode, pendingDeletion, ::DuplicateOperationState),
    ) { groupValues, mode, (selections, keepers), scanning, operation ->
        DuplicateState(
            groups = groupValues,
            mode = mode,
            selections = selections,
            keepers = keepers,
            scanning = scanning,
            statusCode = operation.statusCode,
            pendingDeletion = operation.pendingDeletion,
        )
    }

    private val trashState = combine(
        trashEntries,
        trashSheetOpen,
        combine(pendingPurge, clearTrashConfirmOpen, trashStatusCode, pendingAuthorization, ::TrashOperationState),
    ) { entries, sheetOpen, operation ->
        val now = nowEpochMillis()
        TrashState(
            items = entries.map { it.toItemUi(now) },
            sheetOpen = sheetOpen,
            pendingPurge = operation.pendingPurge?.toItemUi(now),
            clearConfirmOpen = operation.clearConfirmOpen,
            statusCode = operation.statusCode,
            pendingAuthorization = operation.pendingAuthorization,
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
        duplicateKeepers = duplicate.keepers,
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
        pendingTrashAuthorization = trash.pendingAuthorization,
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
            duplicateKeepers.value = emptyMap()
        }
    }

    fun cancelDuplicateScan() {
        viewModelScope.launch { duplicateScanner?.cancel() }
    }

    /**
     * 勾选「移入回收站」的候选。
     *
     * 两条不变量：**保留项不能被勾**（用户显式选的那一份，或排序器推荐的默认保留项），
     * 且**至少留一份**（`updated.size >= candidates.size` 时拒绝）。默认什么也不勾（§14.7）。
     */
    fun toggleDuplicateTrash(groupId: DuplicateGroupId, locationId: MediaLocationId) {
        val group = state.value.duplicateGroups.firstOrNull { it.id == groupId } ?: return
        if (locationId == effectiveKeeper(group)) return
        val current = duplicateSelections.value[groupId].orEmpty()
        val updated = if (locationId in current) current - locationId else current + locationId
        if (updated.size >= group.candidates.size) return
        duplicateSelections.value = duplicateSelections.value + (groupId to updated)
        duplicateStatusCode.value = null
    }

    /**
     * 显式选择保留项（§14.7 的「保留项选择」）。
     *
     * 选中的那份会**同时**从待删集合里移除 —— 这两件事必须一次做完，否则会短暂出现
     * 「保留项也准备删」的状态，而删除计划的校验要求 `trash ⊆ group \ keep`。
     */
    fun setDuplicateKeeper(groupId: DuplicateGroupId, locationId: MediaLocationId) {
        val group = state.value.duplicateGroups.firstOrNull { it.id == groupId } ?: return
        if (group.candidates.none { it.locationId == locationId }) return
        duplicateKeepers.value = duplicateKeepers.value + (groupId to locationId)
        val current = duplicateSelections.value[groupId].orEmpty()
        if (locationId in current) {
            duplicateSelections.value = duplicateSelections.value + (groupId to (current - locationId))
        }
        duplicateStatusCode.value = null
    }

    /**
     * 该组当前的保留项：用户显式选的优先，否则用 `DuplicateKeepRanking` 的推荐值。
     *
     * 读的是 `duplicateKeepers` 而不是 `state.value`：`state` 是 `WhileSubscribed` 的，
     * 没有订阅者时停在初始值，用它做守卫会在后台路径上失效。
     */
    private fun effectiveKeeper(group: DuplicateGroup): MediaLocationId? =
        duplicateKeepers.value[group.id] ?: DuplicateKeepRanking.best(group.candidates)?.locationId

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
        if (trash.isEmpty()) return
        val all = group.candidates.map { it.locationId }.toSet()
        // 保留项 = 用户选的那份，或排序器推荐的默认值。删除计划要求 `trash ⊆ group \ keep`，
        // 所以这里必须是**具体的哪一份**，不能用「剩下的都算保留」这种事后解释。
        val keeper = effectiveKeeper(group)
        val keep = when {
            keeper != null && keeper !in trash -> setOf(keeper)
            else -> all - trash
        }
        if (keep.isEmpty()) return
        val plan = DuplicateDeletionPlan(
            groupId = groupId,
            contentHash = group.contentHash,
            sizeBytes = group.sizeBytes,
            keepLocationIds = keep,
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
        val service = trashService ?: return
        pendingPurge.value = null
        pendingAuthorization.value = null
        trashStatusCode.value = null
        viewModelScope.launch {
            trashStatusCode.value = service.restore(entry.locationId).toTrashStatusCode(TRASH_RESTORED)
        }
    }

    /**
     * 重试物理删除（`CLEANUP_PENDING` → `RETRY_PURGE`）。
     *
     * 复用 [TrashService.purge]：它只拒绝过渡态，而 `CLEANUP_PENDING` 是稳定态 ——
     * 不需要为「重试」再造一条路径（再造一条就多一个可能与主路径行为不一致的分支）。
     */
    fun retryPurge(entry: TrashEntry) {
        val service = trashService ?: return
        pendingAuthorization.value = null
        trashStatusCode.value = null
        viewModelScope.launch {
            trashStatusCode.value = service.purge(entry.locationId).toTrashStatusCode(TRASH_PURGED)
        }
    }

    /**
     * 对账（`RECONCILIATION_REQUIRED` → `RECONCILE`）。
     *
     * 对账本身是全局幂等的启动动作，这里只是给用户一个「现在就查一遍」的入口，
     * 因此直接调 [TrashService.reconcile]，不为单条目造特殊路径。
     */
    fun reconcileTrash() {
        val service = trashService ?: return
        pendingAuthorization.value = null
        trashStatusCode.value = null
        viewModelScope.launch {
            val report = service.reconcile()
            trashStatusCode.value = if (report.needsReview > 0) {
                // 注意模板边界：常量名以 `_` 结尾，必须用 `${...}` 包起来，
                // 否则 `$NAME_` 会把尾下划线并进标识符（本仓已踩过）。
                "${TRASH_RECONCILED_REVIEW_PREFIX}${report.needsReview}"
            } else {
                TRASH_RECONCILED
            }
        }
    }

    fun requestPurge(entry: TrashEntry) {
        pendingPurge.value = entry
    }

    fun dismissPurge() {
        pendingPurge.value = null
    }

    /**
     * 放弃一条移入失败的记录（§8.3 的 `FAILED → 终止`）。
     *
     * 与 [confirmPurge] 的区别是对用户说的实话不同：这条记录代表的字节**从未离开源位置**，
     * 所以没有确认对话框（不需要警告不可恢复），反馈码也不是「已删除」。
     */
    fun discard(entry: TrashEntry) {
        val service = trashService ?: return
        pendingPurge.value = null
        pendingAuthorization.value = null
        trashStatusCode.value = null
        viewModelScope.launch {
            trashStatusCode.value = service.discard(entry.locationId).toTrashStatusCode(TRASH_PURGED)
        }
    }

    fun confirmPurge() {
        val service = trashService ?: return
        val entry = pendingPurge.value ?: return
        pendingPurge.value = null
        pendingAuthorization.value = null
        trashStatusCode.value = null
        viewModelScope.launch {
            trashStatusCode.value = service.purge(entry.locationId).toTrashStatusCode(TRASH_PURGED)
        }
    }

    fun requestClearTrash() {
        if (state.value.trashItems.isEmpty()) return
        clearTrashConfirmOpen.value = true
    }

    fun dismissClearTrash() {
        clearTrashConfirmOpen.value = false
    }

    /**
     * 清空回收站。
     *
     * 归属规则（§11.3）：**清空是批量操作，进任务中心**——逐项 `File.delete()` 与系统授权弹窗
     * 都可能拖很久，不能钉在前台的 `viewModelScope` 上。没有任务中心（单测）时才退回逐项直删。
     */
    fun confirmClearTrash() {
        val entries = state.value.trashItems.map { it.entry }
        clearTrashConfirmOpen.value = false
        if (entries.isEmpty()) return
        pendingAuthorization.value = null
        trashStatusCode.value = null
        val queue = recycleQueue
        if (queue == null) {
            val service = trashService ?: return
            viewModelScope.launch {
                var failed = 0
                for (entry in entries) {
                    when (val outcome = service.purge(entry.locationId)) {
                        // 部分成功要如实报数（§8.6 规则 4）：只报第一个错误会让用户以为一条都没删成，
                        // 只报成功又会盖住失败项 —— 这两个数字都要给。
                        is TrashOperationOutcome.Blocked, is TrashOperationOutcome.Failed -> failed++
                        else -> Unit
                    }
                }
                trashStatusCode.value = if (failed == 0) {
                    TRASH_CLEARED
                } else {
                    "$TRASH_CLEAR_PARTIAL_PREFIX${entries.size - failed}_$failed"
                }
            }
            return
        }
        viewModelScope.launch {
            trashStatusCode.value = runCatching {
                queue.enqueue(RecycleAction.PURGE, entries.map { RecycleTarget(it.locationId, it.mediaItemId) })
            }.fold(onSuccess = { TRASH_CLEAR_ENQUEUED }, onFailure = { "${TRASH_FAILED_PREFIX}ENQUEUE_FAILED" })
        }
    }

    /**
     * 用户从系统对话框回来后推进状态机（§8.4 第 9 步）。
     *
     * 授权**只能由前台发起**，所以请求先摆到 [OrganizeUiState.pendingTrashAuthorization]，
     * 页面把它交给系统再回报结果；拒绝是合法结局（源文件保持原样，副本退回 `recovery/`）。
     */
    fun resolveTrashAuthorization(granted: Boolean) {
        val service = trashService ?: return
        val request = pendingAuthorization.value ?: return
        pendingAuthorization.value = null
        viewModelScope.launch {
            val outcome = service.resolveAuthorization(request.token, granted)
            trashStatusCode.value = when (outcome) {
                null -> if (granted) TRASH_RESTORED else "$TRASH_FAILED_PREFIX$AUTHORIZATION_DENIED"
                else -> outcome.toTrashStatusCode(
                    when (request.action) {
                        RecycleAction.RESTORE -> TRASH_RESTORED
                        RecycleAction.MOVE -> TRASH_MOVED
                        RecycleAction.PURGE, RecycleAction.CLEANUP -> TRASH_PURGED
                    },
                )
            }
        }
    }

    /**
     * token → 系统授权对话框的 `IntentSender`（§8.4 第 9 步）。
     *
     * 授权**只能由前台拉起**，但「token 对应哪个 `PendingIntent`」是数据层的事实，
     * 所以页面只向 ViewModel 要 sender，不自己去找宿主容器（那会让 feature 反向依赖 app）。
     * 返回 `null` = token 已失效（进程重启过），调用方应当作「用户拒绝」处理。
     */
    fun authorizationIntent(token: String): IntentSender? =
        authorizationLauncher?.authorizationIntent(token)?.intentSender

    private fun nowEpochMillis(): Long = clock?.now()?.toEpochMilli() ?: 0L

    /**
     * 把存储层的逐项结果翻成页面用的状态码。
     *
     * 需要授权时**顺手把请求摆进状态**（页面据此拉起系统对话框）；成功码由调用点给，
     * 因为同一个 `Completed` 在恢复与删除两个入口下含义不同。
     */
    private fun TrashOperationOutcome.toTrashStatusCode(successCode: String): String = when (this) {
        is TrashOperationOutcome.Completed -> successCode
        is TrashOperationOutcome.Purged -> successCode
        // 放弃失败记录不是调用点给的任何一种成功：它的语义是「记录消失了，字节没动」。
        is TrashOperationOutcome.Discarded -> TRASH_DISCARDED
        is TrashOperationOutcome.AuthorizationRequired -> {
            pendingAuthorization.value = request
            TRASH_AUTHORIZATION_REQUIRED
        }
        is TrashOperationOutcome.Blocked -> "$TRASH_FAILED_PREFIX$code"
        is TrashOperationOutcome.Failed -> "$TRASH_FAILED_PREFIX$code"
    }

    private fun TrashEntry.toItemUi(nowEpochMillis: Long): TrashItemUi = TrashItemUi(
        entry = this,
        // 只有进入 ACTIVE 才有移入时刻；仍在对账中的条目退回到「最后更新时间」，不显示假的天数。
        ageDays = (nowEpochMillis - (trashedAtEpochMillis ?: updatedAtEpochMillis)).coerceAtLeast(0L) / MILLIS_PER_DAY,
        // 保留期按后端各自的期限算（R1 读系统 `DATE_EXPIRES`，R2 按应用设置）；算不出来时显示 0。
        remainingDays = remainingDays(nowEpochMillis) ?: 0L,
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
        val keepers: Map<DuplicateGroupId, MediaLocationId>,
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
        val pendingAuthorization: RecycleAuthorizationRequest?,
    )

    private data class TrashOperationState(
        val pendingPurge: TrashEntry?,
        val clearConfirmOpen: Boolean,
        val statusCode: String?,
        val pendingAuthorization: RecycleAuthorizationRequest?,
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

        /** 授权后移入成功的反馈码（R1/R2 都可能走到这里）。 */
        const val TRASH_MOVED = "TRASH_MOVED"

        /** 清空已排进任务中心（结果由回收站列表流自动送回来）。 */
        const val TRASH_CLEAR_ENQUEUED = "TRASH_CLEAR_ENQUEUED"

        /** 需要用户先通过系统对话框授权。 */
        const val TRASH_AUTHORIZATION_REQUIRED = "TRASH_AUTHORIZATION_REQUIRED"

        /** 用户拒绝授权。 */
        const val AUTHORIZATION_DENIED = "AUTHORIZATION_DENIED"

        /**
         * 放弃了一条移入失败的记录。
         *
         * 刻意**不**复用 `TRASH_PURGED`：那会让界面说出「文件被删了」这种错误的事实 ——
         * 放弃只撤销记录，字节还在源位置。
         */
        const val TRASH_DISCARDED = "TRASH_DISCARDED"

        /** 失败反馈码前缀，完整形式为 `TRASH_FAILED_<存储层错误码>`。 */
        const val TRASH_FAILED_PREFIX = "TRASH_FAILED_"

        /**
         * 部分清空的结果码，完整形式为 `TRASH_CLEAR_PARTIAL_<成功数>_<失败数>`。
         *
         * 数字写进状态码在这里是**对的**（与 `SCAN_COMPLETED` 相反）：清空是一次性动作，
         * 结果不来自任何流，没有「码先到、数据后到」的时序问题。
         */
        const val TRASH_CLEAR_PARTIAL_PREFIX = "TRASH_CLEAR_PARTIAL_"

        /** 对账结束且无需人工处理。 */
        const val TRASH_RECONCILED = "TRASH_RECONCILED"

        /** 对账结束但仍有条目需要人工确认，完整形式为 `TRASH_RECONCILED_REVIEW_<条数>`。 */
        const val TRASH_RECONCILED_REVIEW_PREFIX = "TRASH_RECONCILED_REVIEW_"

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
            trashRepository: TrashRepository? = null,
            homeRepository: HomeRepository? = null,
            duplicateScanQueue: DuplicateScanQueue? = null,
            processingRepository: ProcessingRepository? = null,
            trashService: TrashService? = null,
            recycleQueue: RecycleQueue? = null,
            authorizationLauncher: RecycleAuthorizationLauncher? = null,
        ) = viewModelFactory {
            initializer {
                OrganizeViewModel(
                    repository,
                    duplicateRepository,
                    duplicateScanner,
                    duplicateDeletionExecutor,
                    clock,
                    trashRepository,
                    homeRepository,
                    duplicateScanQueue,
                    processingRepository,
                    trashService,
                    recycleQueue,
                    authorizationLauncher,
                )
            }
        }
    }
}
