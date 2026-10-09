package seeyuer.yingli.player.domain.duplicates

import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaSourceMode
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.processing.ProcessingProjectId
import kotlinx.coroutines.flow.Flow

/**
 * 哈希算法版本。
 *
 * 设计稿 §7.1：缓存有效性的键是**三元组**
 * `(sizeBytes, modifiedEpochMillis, hashAlgorithmVersion)`。
 * 算法本身或采样方式改变时必须递增本常量，旧哈希随之整体失效。
 */
const val DUPLICATE_HASH_ALGORITHM_VERSION: Int = 1

/** `fastFingerprint` 的语义版本：8 字节大端 size + 头 64 KiB + 尾 64 KiB 的 SHA-256。 */
const val DUPLICATE_QUICK_FINGERPRINT_BYTES: Int = 64 * 1024

/** 判定模式。SIMILAR 维持关闭（D6），只有 EXACT 可执行。 */
enum class DuplicateMode {
    EXACT,
    SIMILAR,
}

/**
 * 重复判定的最小单位是**位置**（`media_locations` 的一行）而不是条目。
 *
 * 一个 `media_items` 行是内容等价类，可以有多个位置；判定「这份字节与那份字节相同」
 * 天然是在位置这一层。字段是 UI 与保留项排序所需要的全部信息。
 */
data class DuplicateCandidate(
    val locationId: MediaLocationId,
    val mediaItemId: MediaItemId,
    val uri: MediaUri,
    val fileName: String,
    val relativePath: String?,
    val sourceMode: MediaSourceMode,
    val sizeBytes: Long,
    val modifiedEpochMillis: Long,
    val durationMillis: Long?,
    val width: Int?,
    val height: Int?,
    val missingScanCount: Int,
    val lastSeenEpochMillis: Long,
) {
    init {
        require(fileName.isNotBlank())
        require(sizeBytes >= 0)
        require(modifiedEpochMillis >= 0)
        require(missingScanCount >= 0)
    }
}

/** 等价类的稳定身份：`"<contentHash>-<sizeBytes>"`。与 `duplicate_ignores` 主键一一对应。 */
@JvmInline
value class DuplicateGroupId(val value: String) {
    init {
        require(PATTERN.matches(value)) { "invalid duplicate group id: $value" }
    }

    companion object {
        private val PATTERN = Regex("[A-Za-z0-9_-]{1,128}")

        fun of(contentHash: String, sizeBytes: Long): DuplicateGroupId =
            DuplicateGroupId("$contentHash-$sizeBytes")
    }
}

/**
 * 一个 EXACT 等价类。
 *
 * 它不是实体：由 `GROUP BY contentHash, sizeBytes HAVING COUNT(*) >= 2` 直接给出
 * （设计稿 §4.7）。不变量：成员至少两个，且大小与哈希逐一相同。
 */
data class DuplicateGroup(
    val contentHash: String,
    val sizeBytes: Long,
    val candidates: List<DuplicateCandidate>,
) {
    init {
        require(contentHash.isNotBlank())
        require(sizeBytes >= 0)
        require(candidates.size >= 2) { "a duplicate group needs at least two members" }
        require(candidates.all { it.sizeBytes == sizeBytes })
        require(candidates.map(DuplicateCandidate::locationId).distinct().size == candidates.size)
    }

    val id: DuplicateGroupId get() = DuplicateGroupId.of(contentHash, sizeBytes)

    /** 除了保留项之外，全部成员占用的字节数——即回收后能释放的空间。 */
    fun reclaimableBytes(keepLocationIds: Set<MediaLocationId>): Long =
        candidates.filter { it.locationId !in keepLocationIds }.sumOf(DuplicateCandidate::sizeBytes)

    /** 等价类里的全部位置，按给定顺序无关的稳定顺序（文件名、位置 id）排列。 */
    fun sortedCandidates(): List<DuplicateCandidate> =
        candidates.sortedWith(compareBy({ it.fileName.lowercase() }, { it.locationId.value }))
}

/**
 * 处置计划。
 *
 * `keep ∪ trash` 只需要是等价类的**子集**（D1-d 放宽了 `INCOMPLETE_SELECTION`）：
 * 用户可以只处理组里的部分成员，剩余成员留到下次再决定。
 */
data class DuplicateDeletionPlan(
    val groupId: DuplicateGroupId,
    val contentHash: String,
    val sizeBytes: Long,
    val keepLocationIds: Set<MediaLocationId>,
    val trashLocationIds: Set<MediaLocationId>,
    val algorithmVersion: Int,
    val createdAtEpochMillis: Long,
) {
    init {
        require(keepLocationIds.isNotEmpty()) { "a deletion plan must keep at least one location" }
        require(trashLocationIds.isNotEmpty()) { "a deletion plan must trash at least one location" }
        require(keepLocationIds.intersect(trashLocationIds).isEmpty())
        require(createdAtEpochMillis >= 0)
    }
}

/** 复核用快照：提交处置之前重算出来的当前内容。 */
data class DuplicateCandidateSnapshot(
    val locationId: MediaLocationId,
    val sizeBytes: Long,
    val modifiedEpochMillis: Long,
    val contentHash: String?,
)

sealed interface DuplicatePlanValidation {
    data object Valid : DuplicatePlanValidation
    data class Invalid(val code: String) : DuplicatePlanValidation
}

/**
 * L4：提交处置前的最终复核。
 *
 * 失败即**整体不执行**（设计稿 §7.3）：宁可让用户再点一次，也不能在文件已变的情况下
 * 按旧结论删掉可能是唯一副本的字节。
 */
object DuplicateDeletionValidator {
    const val GROUP_MISMATCH = "GROUP_MISMATCH"
    const val INCOMPLETE_SELECTION = "INCOMPLETE_SELECTION"
    const val EVIDENCE_VERSION_CHANGED = "EVIDENCE_VERSION_CHANGED"
    const val FINGERPRINT_MISSING = "FINGERPRINT_MISSING"
    const val FILE_CHANGED = "FILE_CHANGED"

    fun validate(
        group: DuplicateGroup,
        plan: DuplicateDeletionPlan,
        current: Map<MediaLocationId, DuplicateCandidateSnapshot>,
    ): DuplicatePlanValidation {
        if (plan.groupId != group.id) return invalid(GROUP_MISMATCH)
        if (plan.contentHash != group.contentHash || plan.sizeBytes != group.sizeBytes) {
            return invalid(GROUP_MISMATCH)
        }
        if (plan.algorithmVersion != DUPLICATE_HASH_ALGORITHM_VERSION) {
            return invalid(EVIDENCE_VERSION_CHANGED)
        }
        val members = group.candidates.associateBy(DuplicateCandidate::locationId)
        val selection = plan.keepLocationIds + plan.trashLocationIds
        if (!members.keys.containsAll(selection)) return invalid(GROUP_MISMATCH)

        for (locationId in selection) {
            val member = members.getValue(locationId)
            val snapshot = current[locationId] ?: return invalid(FINGERPRINT_MISSING)
            if (snapshot.sizeBytes != member.sizeBytes) return invalid(FILE_CHANGED)
            if (snapshot.modifiedEpochMillis != member.modifiedEpochMillis) return invalid(FILE_CHANGED)
            val hash = snapshot.contentHash ?: return invalid(FINGERPRINT_MISSING)
            if (hash != group.contentHash) return invalid(FILE_CHANGED)
        }
        return DuplicatePlanValidation.Valid
    }

    private fun invalid(code: String) = DuplicatePlanValidation.Invalid(code)
}

sealed interface DuplicateDeletionResult {
    data class Completed(val trashedCount: Int) : DuplicateDeletionResult
    data class Rejected(val code: String) : DuplicateDeletionResult
    data class Partial(val trashedCount: Int, val failedCount: Int) : DuplicateDeletionResult
}

/** 扫描进度。stage ∈ `BUCKETING` / `QUICK_HASH` / `FULL_HASH` / `GROUPING`。 */
data class DuplicateScanProgress(
    val stage: String,
    val processedUnits: Int,
    val totalUnits: Int?,
) {
    companion object {
        const val BUCKETING = "BUCKETING"
        const val QUICK_HASH = "QUICK_HASH"
        const val FULL_HASH = "FULL_HASH"
        const val GROUPING = "GROUPING"
    }
}

/**
 * 扫描结果**不是**产物：结果落在 `media_locations` 的哈希列上，UI 从
 * [DuplicateRepository.groups] 读取等价类（设计稿 §11.1）。
 */
sealed interface DuplicateScanResult {
    data class Completed(
        val candidateCount: Int,
        val quickHashedCount: Int,
        val fullHashedCount: Int,
        val failureCount: Int,
        val groupCount: Int,
    ) : DuplicateScanResult

    data class Rejected(val code: String) : DuplicateScanResult
    data object Canceled : DuplicateScanResult
}

/**
 * 归并请求：把 [loserMediaIds] 的全部引用迁移到 [survivorMediaId]，然后删除 loser 行。
 *
 * [survivorLocationId] 是**用户选择保留的那个位置**，不是「survivor 名下随便一个位置」。
 * 归并时 survivor 往往同时持有多个位置，而这次处置会把其中的一部分移入回收站
 * （`VISIBLE_LOCATION` 让它们从媒体库里消失）。`clip_projects` 这类必须继续可用的引用
 * 只能重指向保留位置，否则切片项目会在回收站搬家之后指向一个不可见的位置。
 */
data class DuplicateMerge(
    val survivorMediaId: MediaItemId,
    val survivorLocationId: MediaLocationId,
    val loserMediaIds: List<MediaItemId>,
) {
    init {
        require(loserMediaIds.isNotEmpty())
        require(loserMediaIds.none { it == survivorMediaId })
    }
}

sealed interface DuplicateMergeResult {
    data class Completed(val mergedItemCount: Int) : DuplicateMergeResult
    data class Rejected(val code: String) : DuplicateMergeResult
}

/**
 * 等价类读模型。
 *
 * [groups] 只返回**尚未归并**的等价类：同一 `contentHash` 下存在多于一个
 * `media_items` 行时，说明这次重复还没有被处理（不变量 3 被打破 = 有重复待处理）。
 * 已被用户忽略且成员数未变的组不出现。
 */
interface DuplicateRepository {
    val groups: Flow<List<DuplicateGroup>>

    /** 忽略一个等价类；[memberCount] 用于在成员数变化时让该组重新出现。 */
    suspend fun ignore(group: DuplicateGroup, ignoredAtEpochMillis: Long)
}

/**
 * 引用迁移（设计稿 §7.3）。**整个归并必须在一个事务内完成**，
 * 任何一步失败都不允许留下半迁移状态。
 */
interface DuplicateMergeRepository {
    suspend fun merge(merges: List<DuplicateMerge>): DuplicateMergeResult
}

interface DuplicateScanner {
    suspend fun scan(
        mode: DuplicateMode,
        onProgress: suspend (DuplicateScanProgress) -> Unit = {},
    ): DuplicateScanResult

    fun cancel()
}

/**
 * 去重扫描任务的操作键前缀。
 *
 * UI 靠它识别「是否有去重扫描在跑」——`ProcessingTask` 只带 `projectId`，
 * 不反查项目类型就无法知道一个任务是不是去重扫描。
 */
const val DUPLICATE_SCAN_OPERATION_KEY_PREFIX: String = "duplicate-scan-"

/**
 * 发起一次去重扫描（设计稿 §11.1）：扫描**必须**经任务中心，不能被 UI 直接调用
 * （`docs/06:588`）。UI 拿到的是可持久化的进度、取消与重试。
 */
interface DuplicateScanQueue {
    suspend fun enqueue(mode: DuplicateMode = DuplicateMode.EXACT): ProcessingProjectId
}

interface DuplicateDeletionExecutor {
    suspend fun execute(plan: DuplicateDeletionPlan): DuplicateDeletionResult
}
