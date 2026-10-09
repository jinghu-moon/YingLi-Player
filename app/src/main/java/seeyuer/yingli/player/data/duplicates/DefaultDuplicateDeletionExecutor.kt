package seeyuer.yingli.player.data.duplicates

import kotlinx.coroutines.flow.first
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.catalog.MediaContentHasher
import seeyuer.yingli.player.domain.duplicates.DUPLICATE_HASH_ALGORITHM_VERSION
import seeyuer.yingli.player.domain.duplicates.DuplicateCandidate
import seeyuer.yingli.player.domain.duplicates.DuplicateCandidateSnapshot
import seeyuer.yingli.player.domain.duplicates.DuplicateDeletionExecutor
import seeyuer.yingli.player.domain.duplicates.DuplicateDeletionPlan
import seeyuer.yingli.player.domain.duplicates.DuplicateDeletionResult
import seeyuer.yingli.player.domain.duplicates.DuplicateDeletionValidator
import seeyuer.yingli.player.domain.duplicates.DuplicateKeepRanking
import seeyuer.yingli.player.domain.duplicates.DuplicateMerge
import seeyuer.yingli.player.domain.duplicates.DuplicateMergeRepository
import seeyuer.yingli.player.domain.duplicates.DuplicateMergeResult
import seeyuer.yingli.player.domain.duplicates.DuplicatePlanValidation
import seeyuer.yingli.player.domain.duplicates.DuplicateRepository
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryMutationRepository
import seeyuer.yingli.player.domain.library.LibraryPagingRepository

/**
 * 去重处置的执行者（设计稿 §7.3）。
 *
 * 顺序固定为「**先归并引用，再移入回收站**」：
 *
 * 1. **L4 最终复核**（§7.1）：对计划涉及的每个位置**重新读文件**，用重算出的
 *    `sizeBytes` / `contentHash` 与扫描时记下的值比对。任一不一致 ⇒ 整体拒绝。
 *    这里刻意**不信任库里的哈希**——它是「上次扫描时的事实」，不是「现在的事实」。
 * 2. **归并**：把选中的、非保留项的内容实体的全部引用迁移到保留项上
 *    （§7.3 的整张表）。归并**不删除任何字节**。
 * 3. **回收**：把勾选的位置交给 `LibraryMutationRepository.trash`——它负责
 *    移动文件、校验完整性、写 `trash_entries`（文件级动作）。
 *
 * ### 一处刻意偏离「与归并同一事务」
 *
 * §7.3 写的是「在同一事务里完成 ① 引用迁移 ② 标记待回收 ③ 提交回收任务」。
 * 实现把它拆成**两个失败隔离的阶段**：归并是一个 Room 事务，回收是文件动作。
 * 原因是「同一事务」在这里会**增加**数据丢失风险：文件已经移动、事务却回滚，
 * 结果是磁盘上少了一份用户看不见的字节（比「归并已提交、回收没做」糟得多——后者
 * 只影响显示，重试即可）。用户在 UI 上仍然是一步确认、一次点击。
 *
 * 注：`trash()` 内部已经具备「移动成功但完整性校验失败 ⇒ 保留源文件」的语义，
 * 因此这里的部分失败是可恢复的，不需要额外补偿动作。
 */
class DefaultDuplicateDeletionExecutor(
    private val duplicateRepository: DuplicateRepository,
    private val mergeRepository: DuplicateMergeRepository,
    private val libraryRepository: LibraryPagingRepository,
    private val mutationRepository: LibraryMutationRepository,
    private val hasher: MediaContentHasher,
    private val algorithmVersion: Int = DUPLICATE_HASH_ALGORITHM_VERSION,
) : DuplicateDeletionExecutor {
    override suspend fun execute(plan: DuplicateDeletionPlan): DuplicateDeletionResult {
        val group = duplicateRepository.groups.first().firstOrNull { it.id == plan.groupId }
            ?: return DuplicateDeletionResult.Rejected(GROUP_NOT_FOUND)
        if (plan.algorithmVersion != algorithmVersion) {
            return DuplicateDeletionResult.Rejected(
                DuplicateDeletionValidator.EVIDENCE_VERSION_CHANGED,
            )
        }

        val members = group.candidates.associateBy(DuplicateCandidate::locationId)
        val selection = plan.keepLocationIds + plan.trashLocationIds
        val current = LinkedHashMap<MediaLocationId, DuplicateCandidateSnapshot>(selection.size)
        for (locationId in selection) {
            val member = members[locationId]
                ?: return DuplicateDeletionResult.Rejected(DuplicateDeletionValidator.GROUP_MISMATCH)
            val sizeBytes = hasher.size(member.uri)
                ?: return DuplicateDeletionResult.Rejected(FILE_UNREADABLE)
            val contentHash = hasher.sha256(member.uri)
                ?: return DuplicateDeletionResult.Rejected(FILE_UNREADABLE)
            current[locationId] = DuplicateCandidateSnapshot(
                locationId = locationId,
                sizeBytes = sizeBytes,
                modifiedEpochMillis = member.modifiedEpochMillis,
                contentHash = contentHash,
            )
        }
        when (val validation = DuplicateDeletionValidator.validate(group, plan, current)) {
            DuplicatePlanValidation.Valid -> Unit
            is DuplicatePlanValidation.Invalid -> {
                return DuplicateDeletionResult.Rejected(validation.code)
            }
        }

        // 保留项由排序准则挑出（用户可以在 UI 里覆盖，覆盖的结果就体现在 keepLocationIds 里）。
        val survivor = DuplicateKeepRanking.best(plan.keepLocationIds.mapNotNull(members::get))
            ?: return DuplicateDeletionResult.Rejected(
                DuplicateDeletionValidator.INCOMPLETE_SELECTION,
            )
        // 等价类模型要求「一个内容实体只留一行」：选中的其它实体全部并入保留项。
        // 没被选中的成员不动——「部分处置」是允许的，它们下次还会以重复的形式出现。
        val losers = selection
            .mapNotNull { members[it]?.mediaItemId }
            .distinct()
            .filter { it != survivor.mediaItemId }
        if (losers.isNotEmpty()) {
            val merge = mergeRepository.merge(
                listOf(DuplicateMerge(survivor.mediaItemId, survivor.locationId, losers)),
            )
            if (merge is DuplicateMergeResult.Rejected) {
                return DuplicateDeletionResult.Rejected(merge.code)
            }
        }

        // 归并之后这些位置都挂在保留项上，因此按保留项取回条目即可。
        val targets = libraryRepository
            .findByIds(setOf(survivor.mediaItemId))
            .associateBy(LibraryMedia::locationId)
            .let { byLocation -> plan.trashLocationIds.mapNotNull(byLocation::get) }
        if (targets.size != plan.trashLocationIds.size) {
            return DuplicateDeletionResult.Rejected(MEDIA_MISSING)
        }
        val result = mutationRepository.trash(targets)
        return when {
            result.failed == 0 -> DuplicateDeletionResult.Completed(result.succeeded)
            result.succeeded == 0 -> DuplicateDeletionResult.Rejected(TRASH_FAILED)
            else -> DuplicateDeletionResult.Partial(result.succeeded, result.failed)
        }
    }

    private companion object {
        const val GROUP_NOT_FOUND = "GROUP_NOT_FOUND"
        const val FILE_UNREADABLE = "FILE_UNREADABLE"
        const val MEDIA_MISSING = "MEDIA_MISSING"
        const val TRASH_FAILED = "TRASH_FAILED"
    }
}
