package seeyuer.yingli.player.domain.recycle

import kotlinx.coroutines.flow.Flow
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.processing.ProcessingProjectId
import seeyuer.yingli.player.domain.processing.ProcessingProjectType

/**
 * 回收站的存储后端（设计稿 §8.1 的 D2-a 裁决：**R1 为主、R2 为受控降级、R3 已删除、R4 兜底**）。
 *
 * - [R1_SYSTEM]：走 MediaStore 的系统回收站（自有媒体直接置 `IS_TRASHED`；其他应用的媒体用
 *   `MediaStore.createTrashRequest()`）。**零额外空间**，但期限由系统掌握（`DATE_EXPIRES`）。
 * - [R2_APP_COPY]：复制到 `filesDir/recycle-bin/items/<uuid>`，验证副本完整后再请求删除源。
 *   **严格按应用保留期**（`expiresAt = trashedAt + retentionDays`），代价是副本占用与源文件相近的空间，
 *   且**卸载应用会连同副本一起删除**。
 *
 * 两类后端必须原样披露各自的期限语义（§8.1 的表格），不允许把它们说成同一种「30 天」。
 */
enum class TrashBackend {
    R1_SYSTEM,
    R2_APP_COPY,
}

/**
 * 回收站条目的状态机（设计稿 §8.3）。
 *
 * **稳定态与过渡态必须分开**：只有 [ACTIVE] 允许用户恢复或永久删除；所有过渡态在进程重启后
 * 都要由启动对账（[TrashService.reconcile]）收尾，并能退回某个稳定态。
 */
enum class TrashState {
    /** 正在复制/校验副本，尚未移入。任何操作都禁止。 */
    STAGING,

    /** 副本已验证，等待系统删除授权。用户拒绝 → [FAILED]（源文件保持原样）。 */
    WAITING_SOURCE_DELETE_AUTH,

    /** 移入成功，处于保留期。**只有到这一步才开始计时**。 */
    ACTIVE,

    /** 正在恢复。 */
    RESTORING,

    /** 已过期或已请求删除，物理清理失败，后台重试。此时**禁止恢复**。 */
    CLEANUP_PENDING,

    /** 移入失败，源文件未动。 */
    FAILED,

    /** 上次操作结果不确定，无法确认唯一副本归属。**绝不能自动删除任何副本**。 */
    RECONCILIATION_REQUIRED,
    ;

    /** 稳定态：可以由用户或对账重新驱动的状态。 */
    val isStable: Boolean
        get() = this == ACTIVE || this == FAILED || this == CLEANUP_PENDING || this == RECONCILIATION_REQUIRED

    /** 过渡态：进程重启时必然需要对账收尾。 */
    val isTransitional: Boolean get() = !isStable

    /**
     * 用户在这个状态下可以做的事（§8.3 的「允许的操作」一列）。
     *
     * **放在领域层**：它不是界面偏好，而是状态机的一部分 —— 界面只是把这张表画出来，
     * 任何越权的请求仍会被 [TrashService] 的守卫拒掉（§14.7 的退出条件）。
     */
    val allowedActions: Set<TrashAction>
        get() = when (this) {
            // 移入成功且在保留期内：可以恢复，也可以永久删除。
            ACTIVE -> setOf(TrashAction.RESTORE, TrashAction.PURGE)
            // 物理清理失败：**禁止恢复**（副本可能已经删了一半），只能重试删除或看诊断。
            CLEANUP_PENDING -> setOf(TrashAction.RETRY_PURGE)
            // 结果不确定：只能对账，且不得自动删除任何副本。
            RECONCILIATION_REQUIRED -> setOf(TrashAction.RECONCILE)
            // 移入失败、源文件未动：放弃这条记录（清理隔离副本），或回媒体库重新移入。
            FAILED -> setOf(TrashAction.DISCARD)
            // 过渡态：无用户可做的事，等对账收尾。
            STAGING, WAITING_SOURCE_DELETE_AUTH, RESTORING -> emptySet()
        }
}

/** 用户在回收站条目上可以发起的动作（§8.3）。 */
enum class TrashAction {
    /** 恢复（只有 R2 在保留期内、R1 在系统期限内可达）。 */
    RESTORE,

    /** 永久删除。 */
    PURGE,

    /** [TrashState.CLEANUP_PENDING] 上的重试删除。 */
    RETRY_PURGE,

    /** 启动对账，把「结果不确定」的条目收敛回稳定态。 */
    RECONCILE,

    /** [TrashState.FAILED] 上的放弃：删记录并清理隔离副本（**要求源文件仍在**）。 */
    DISCARD,
}

/**
 * 回收站条目（设计稿 §8.2 的数据模型，持久化在 `trash_entries`）。
 *
 * **主键是 `locationId`**（修 G16）：回收的是位置上的那份字节，而一个 [MediaItemId] 可以有多个位置。
 * `mediaItemId` 是冗余列，便于按条目查询与对账。
 *
 * 「源文件引用快照」字段在移入时写入一次，此后**不能假设它们仍然有效**（源文件可能已被删除）。
 */
data class TrashEntry(
    val locationId: MediaLocationId,
    val mediaItemId: MediaItemId,
    val backend: TrashBackend,
    val state: TrashState,
    val originalUri: MediaUri,
    val originalDisplayName: String,
    val originalSizeBytes: Long,
    val updatedAtEpochMillis: Long,
    val sourceId: String? = null,
    val originalVolumeId: String? = null,
    val originalDocumentId: String? = null,
    val originalRelativePath: String? = null,
    val originalMimeType: String? = null,
    val originalModifiedEpochMillis: Long? = null,
    val originalDurationMillis: Long? = null,
    val originalWidth: Int? = null,
    val originalHeight: Int? = null,
    val contentHash: String? = null,
    val hashAlgorithmVersion: Int? = null,
    val copyRelativePath: String? = null,
    val copySizeBytes: Long? = null,
    val copyVerifiedAtEpochMillis: Long? = null,
    val trashedAtEpochMillis: Long? = null,
    val expiresAtEpochMillis: Long? = null,
    val systemExpiresAtEpochMillis: Long? = null,
    val restoreUri: MediaUri? = null,
    val restoredAtEpochMillis: Long? = null,
    val lastErrorCode: String? = null,
    val lastErrorDetail: String? = null,
    val retryCount: Int = 0,
) {
    init {
        require(originalSizeBytes >= 0)
        require(retryCount >= 0)
        require(updatedAtEpochMillis >= 0)
        // R2 的期限只在进入 ACTIVE 时才写；写了就必须与 trashedAt 同时存在。
        require((expiresAtEpochMillis == null) == (trashedAtEpochMillis == null) || backend == TrashBackend.R1_SYSTEM)
        require(copyRelativePath == null || backend == TrashBackend.R2_APP_COPY)
    }

    /**
     * 「恢复资格严格」（§8.7 的第 1 个独立指标）：**纯 DB 判定**，不依赖物理清理是否已经执行。
     * - [TrashBackend.R2_APP_COPY]：`now >= expiresAt` 立即拒绝。
     * - [TrashBackend.R1_SYSTEM]：`DATE_EXPIRES` 快照已过则拒绝；未知（`null`）时不拒绝
     *   （「只有数据来源能够可靠提供时才展示」）。
     */
    fun canRestore(nowEpochMillis: Long): Boolean {
        if (state != TrashState.ACTIVE) return false
        return when (backend) {
            TrashBackend.R2_APP_COPY -> expiresAtEpochMillis?.let { nowEpochMillis < it } ?: false
            TrashBackend.R1_SYSTEM -> systemExpiresAtEpochMillis?.let { nowEpochMillis < it } ?: true
        }
    }

    /** 剩余保留天数，用于 UI；期限未知时返回 `null`（不显示）。 */
    fun remainingDays(nowEpochMillis: Long): Long? {
        val expiresAt = when (backend) {
            TrashBackend.R2_APP_COPY -> expiresAtEpochMillis
            TrashBackend.R1_SYSTEM -> systemExpiresAtEpochMillis
        } ?: return null
        val remaining = expiresAt - nowEpochMillis
        return if (remaining <= 0L) 0L else (remaining + MILLIS_PER_DAY - 1) / MILLIS_PER_DAY
    }

    /** 该条目是否已经到期（两个后端各自的判据，供清理任务使用）。 */
    fun isExpired(nowEpochMillis: Long): Boolean = when (backend) {
        TrashBackend.R2_APP_COPY -> expiresAtEpochMillis?.let { nowEpochMillis >= it } ?: false
        TrashBackend.R1_SYSTEM -> systemExpiresAtEpochMillis?.let { nowEpochMillis >= it } ?: false
    }

    /** 已过保留期、且不是任何过渡态时，不允许再恢复。 */
    val isRecoverable: Boolean get() = state == TrashState.ACTIVE

    companion object {
        const val MILLIS_PER_DAY = 86_400_000L

        /**
         * 保留期策略（D2-a）：**只对 [TrashBackend.R2_APP_COPY] 条目生效**。
         * R1 入口在 UI 上必须显示「由系统管理，通常约 30 天」。
         */
        fun expiresAt(trashedAtEpochMillis: Long, retentionDays: Int): Long =
            trashedAtEpochMillis + retentionDays * MILLIS_PER_DAY
    }
}

/**
 * 保留期设置。默认 30 天，来源是 `settings_trash_retention`（U5 已闭合）。
 * **[调整]** 它只影响 R2 条目：R1 的期限由系统的 `DATE_EXPIRES` 决定，应用改不了。
 */
data class TrashRetentionPolicy(val retentionDays: Int = 30) {
    init {
        require(retentionDays in 1..365)
    }

    fun expiresAt(trashedAtEpochMillis: Long): Long =
        TrashEntry.expiresAt(trashedAtEpochMillis, retentionDays)
}

/**
 * 一次回收站操作的结果。**每个位置独立记录**（§8.4 规则 5：批量移入允许部分成功，
 * 不得把整批的单一布尔值套给所有文件）。
 */
sealed interface TrashOperationOutcome {
    /** 操作完成，携带操作后的条目快照（可能是 `ACTIVE`、`CLEANUP_PENDING` 等）。 */
    data class Completed(val entry: TrashEntry) : TrashOperationOutcome

    /** 需要用户授权才能继续（`createTrashRequest()` / `createDeleteRequest()`）。 */
    data class AuthorizationRequired(val request: RecycleAuthorizationRequest) : TrashOperationOutcome

    /** 阻止并解释原因（§8.4 的两个「否」分支）：**不降级继续，也不改动任何文件**。 */
    data class Blocked(val code: String, val detail: String? = null) : TrashOperationOutcome

    /** 可重试的失败：条目已持久化并把状态推进到某个稳定态。 */
    data class Failed(val entry: TrashEntry, val code: String, val detail: String? = null) : TrashOperationOutcome

    /**
     * 物理删除完成，记录已经不存在（§8.6 规则 7：**物理删除成功后再删数据库记录**）。
     * 这是唯一一个没有条目可返回的终态，因此单独成一个分支。
     */
    data class Purged(val locationId: MediaLocationId) : TrashOperationOutcome

    /**
     * 放弃一条 [TrashState.FAILED] 记录：源文件从未被动过，隔离副本被删掉，记录消失。
     *
     * 与 [Purged] 分开是因为语义完全不同：`Purged` 意味着「那份字节被删了」，
     * `Discarded` 意味着「这条失败记录被撤销了，字节仍在源位置」。合成一个分支会让界面
     * 对用户说出错误的事实。
     */
    data class Discarded(val locationId: MediaLocationId) : TrashOperationOutcome
}

/**
 * 系统授权请求的**领域侧句柄**：`PendingIntent` 是 Android 类型，不能进 domain 层，
 * 因此这里只给出一次性 [token] 与用户可见的 [uris]；真正的 `IntentSender` 由 data 层按 token 持有。
 */
data class RecycleAuthorizationRequest(
    val token: String,
    val action: RecycleAction,
    val uris: List<MediaUri>,
    val reason: String,
)

/** 回收站动作（D4-b：只新增一个 `RECYCLE` 任务类型，动作编码在 `outputPolicy` 里）。 */
enum class RecycleAction {
    MOVE,
    RESTORE,
    PURGE,
    CLEANUP,
}

/** 批量操作的汇总报告：逐位置结果 + 汇总计数。 */
data class TrashOperationReport(
    val outcomes: Map<MediaLocationId, TrashOperationOutcome>,
) {
    val succeeded: Int get() = outcomes.values.count { it is TrashOperationOutcome.Completed }
    val failed: Int get() = outcomes.values.count { it is TrashOperationOutcome.Failed || it is TrashOperationOutcome.Blocked }
    val authorizationRequired: List<RecycleAuthorizationRequest> get() =
        outcomes.values.filterIsInstance<TrashOperationOutcome.AuthorizationRequired>().map { it.request }
}

/** 启动对账 / 到期清理的结果（§10.2、§8.7）。 */
data class ReconcileReport(
    val repaired: Int,
    val needsReview: Int,
    val errors: Int,
    val details: List<String> = emptyList(),
) {
    companion object {
        val Empty = ReconcileReport(0, 0, 0)
    }
}

/** 回收站数据访问（重写既有 `TrashRepository`，按 §8.2/§8.3 扩展）。 */
interface TrashRepository {
    fun observe(): Flow<List<TrashEntry>>

    suspend fun byLocation(locationId: MediaLocationId): TrashEntry?

    suspend fun byLocations(locationIds: Collection<MediaLocationId>): List<TrashEntry>

    /** 插入时用 `IGNORE`：重复点击 / 重试不生成无法识别的重复记录（§8.4 规则 6）。 */
    suspend fun insert(entry: TrashEntry)

    suspend fun update(entry: TrashEntry)

    suspend fun remove(locationId: MediaLocationId)

    suspend fun inStates(states: Set<TrashState>): List<TrashEntry>

    /** 已到期且仍处于 [TrashState.ACTIVE] 的条目。 */
    suspend fun expired(nowEpochMillis: Long): List<TrashEntry>
}

/**
 * 回收站操作在**媒体目录**上留下的痕量。
 *
 * 它单独存在是因为回收站的其余部分只碰 `trash_entries` 与文件；只有下面三件事
 * 必须改 `media_locations` / `media_items`，而这两张表的写法属于目录侧。
 *
 * 为什么恢复之后必须回写位置行（§8.5 的 [调整]）：恢复通常**创建新的媒体行**
 * （不假设原 `_ID` 还能用），如果目录里的 `media_locations.uri` 还指着旧 URI，
 * 用户从媒体库里点开的就是一个已经不存在的地址 —— 「恢复了但打不开」。
 */
interface RecycleCatalogGateway {
    /**
     * 把位置行指到恢复出来的新 URI。
     *
     * `contentHash` 传 [TrashEntry.contentHash]：恢复流程在写入前**校验过**内容一致
     * （不一致会以 `RESTORE_HASH_MISMATCH` 失败），所以这里的哈希仍然是可信的；
     * 条目从未算过哈希时传 `null`，让它在下次扫描时重算。
     */
    suspend fun markRestored(
        locationId: MediaLocationId,
        restoreUri: MediaUri,
        contentHash: String?,
        hashAlgorithmVersion: Int?,
        nowEpochMillis: Long,
    )

    /** 永久删除之后清理派生数据（§8.8）：位置行 → 级联 → 条目再无位置则删条目。 */
    suspend fun detachLocation(locationId: MediaLocationId, mediaItemId: MediaItemId)

    /** 启动对账（§10.2 最后一行）：删掉没有任何位置行的媒体条目，返回删除条数。 */
    suspend fun deleteItemsWithoutLocations(): Int
}

/**
 * 后端动作的抽象（Android 侧实现）。
 *
 * 这**不是**第二个 `FileOperationGateway`：它只做回收站独有的三件事 ——
 * 移入（复制或系统标记）、恢复、物理删除 —— 并负责授权请求的取得与兑现。
 */
interface RecycleBinStorage {
    /** 该位置能否走 R1（系统回收站）。不能则降级为 R2；R2 也不可用时由调用方阻止并解释。 */
    suspend fun systemTrashSupported(item: LibraryMedia): Boolean

    /** 第 6–8 步：执行后端动作并（R2 时）提交副本。返回的条目是**中间态**，尚未 `ACTIVE`。 */
    suspend fun stage(entry: TrashEntry): TrashOperationOutcome

    /** 第 9 步：确认源文件已删除/已被系统接管。 */
    suspend fun deleteSource(entry: TrashEntry): TrashOperationOutcome

    /** 恢复流程 §8.5 的第 4–8 步。 */
    suspend fun restore(entry: TrashEntry): TrashOperationOutcome

    /** 物理删除；返回的条目可以带着 `CLEANUP_PENDING`。 */
    suspend fun purge(entry: TrashEntry): TrashOperationOutcome

    /** 兑现一次性授权 token；`null` 表示 token 已失效（进程重启后属正常情况）。 */
    suspend fun resolveAuthorization(token: String, entry: TrashEntry, granted: Boolean): TrashOperationOutcome?

    /** 丢弃一个未兑现的授权 token（用户取消 / 授权后发现源已变）。 */
    fun discardAuthorization(token: String)

    /** 应用私有存储的可用字节数。R2 的空间检查（§8.4 第 4 步）在 Service 侧做，只取这个数。 */
    suspend fun availableBytes(): Long

    /** 某个 URI 现在还能不能读写（启动对账用：判断「源是否仍在」「恢复是否已落地」）。 */
    suspend fun isReadable(uri: MediaUri): Boolean

    /** 启动对账用：把 `items/<name>` 退回 `recovery/`（**副本不删**）。 */
    suspend fun quarantineCopy(entry: TrashEntry): Boolean

    /** 启动对账用：`items/` 里没有任何记录引用的残留文件，同样只退回 `recovery/`。 */
    suspend fun quarantineOrphanCopy(name: String): Boolean

    /** 当前 R2 目录下的 `items/` 文件名（启动对账用；R1 条目没有副本）。 */
    suspend fun copyFileNames(): Set<String>

    /** 删除一个不再被任何记录引用的 `items/` 文件；不存在时按成功处理（幂等）。 */
    suspend fun removeCopyFile(name: String): Boolean

    /** 删除一个 `staging/` 残留文件（未验证的副本不安全，只能丢弃）。 */
    suspend fun removeStagingFile(name: String): Boolean
}

/**
 * 回收站业务编排：状态机守卫 + 事务边界 + 后端分派。
 * 它**不是** Repository：Repository 只管行，Service 管「什么时候允许写哪一行」。
 */
interface TrashService {
    fun observe(): Flow<List<TrashEntry>>

    /** 单个位置移入（R1 秒级；R2 视文件大小）。 */
    suspend fun move(item: LibraryMedia): TrashOperationOutcome

    /**
     * 批量移入；逐位置独立记录结果（`§8.4` 规则 5）。
     * 去重处置也走这里，并在同一个 Room 事务内先做引用迁移（§8.4 的 [调整]）。
     */
    suspend fun move(items: List<LibraryMedia>): TrashOperationReport

    suspend fun restore(locationId: MediaLocationId): TrashOperationOutcome

    suspend fun purge(locationId: MediaLocationId): TrashOperationOutcome

    /** 「清空」：逐项执行，**每个文件确认删除成功后才删元数据**（§8.6 规则 2）。 */
    suspend fun purgeAll(): TrashOperationReport

    /**
     * 放弃一条移入失败的记录（§8.3 的 `FAILED → 终止`）。
     *
     * **守卫**：只有 `FAILED` 态可放弃；放弃前必须确认源文件仍在（`isReadable(originalUri)`），
     * 否则源已经不存在、隔离副本就是唯一副本 —— 那种情况必须走对账而不是让用户把它删掉。
     */
    suspend fun discard(locationId: MediaLocationId): TrashOperationOutcome

    suspend fun resolveAuthorization(token: String, granted: Boolean): TrashOperationOutcome?

    /** 启动对账（§10.2）；必须幂等。 */
    suspend fun reconcile(): ReconcileReport

    /**
     * 到期判定 + 物理删除（D3-C2：挂在启动/进前台时执行）。
     * **「恢复资格严格」不依赖它**：过期条目在 [TrashEntry.canRestore] 处立即被拒。
     */
    suspend fun cleanupExpired(nowEpochMillis: Long): ReconcileReport

    /** 该位置当前是否被某个非终态任务占用（占用检查，§8.4 第 2 步）。 */
    suspend fun activeOperations(item: LibraryMedia): Set<ProcessingProjectType>
}

/** 一个待处置的位置：任务中心的输入必须自带 `mediaItemId`（`processing_project_inputs` 需要它）。 */
data class RecycleTarget(
    val locationId: MediaLocationId,
    val mediaItemId: MediaItemId,
)

/**
 * 把「会跑很久的回收站操作」交给任务中心（§11.3）。
 *
 * 判断谁该进来的规则很明确：
 * - 单个 R1 移入/恢复 = **不进**（秒级，直接返回结果）；
 * - 单个 R2 移入（复制大文件）= 进；
 * - 批量移入 / 批量删除（清空）= 进；
 * - 到期清理按 D3-C2 **不进**（启动时同步跑）。
 */
interface RecycleQueue {
    suspend fun enqueue(action: RecycleAction, targets: List<RecycleTarget>): ProcessingProjectId
}

/**
 * 互斥矩阵（§11.4，D5-b）：
 * **去重扫描只读哈希，与压缩/转码/切片不冲突**，只与「对同一位置的处置」互斥。
 */
interface MediaOperationGuard {
    suspend fun activeOperations(mediaItemId: MediaItemId, locationId: MediaLocationId): Set<ProcessingProjectType>

    fun conflicts(left: ProcessingProjectType, right: ProcessingProjectType): Boolean

    /** 返回第一个与 [type] 冲突的在跑类型；没有冲突时返回 `null`。 */
    suspend fun firstConflict(
        type: ProcessingProjectType,
        mediaItemId: MediaItemId,
        locationId: MediaLocationId,
    ): ProcessingProjectType? {
        val active = activeOperations(mediaItemId, locationId)
        return active.firstOrNull { conflicts(it, type) }
    }
}
