package seeyuer.yingli.player.data.library

import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.library.BatchOperationSummary
import seeyuer.yingli.player.domain.library.FileOperationFailure
import seeyuer.yingli.player.domain.library.FileOperationResult
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryMutationRepository
import seeyuer.yingli.player.domain.recycle.TrashEntry
import seeyuer.yingli.player.domain.recycle.TrashOperationOutcome
import seeyuer.yingli.player.domain.recycle.TrashService

/**
 * 媒体库破坏性动作 → 回收站的**唯一**入口（§13：不引入第二个回收站写入口）。
 *
 * 阶段 4 之前这里自己拿 `FileOperationGateway` 做 `renameTo` 并直接写 `trash_entries`——
 * 那就是 G17（先动文件再写库）与 R3（路径访问权根本不成立）的所在。
 * 现在它只剩翻译工作：把 [TrashService] 的领域结果翻成媒体库沿用的 [FileOperationResult]。
 *
 * 保留这层接口而不是让 UI 直接调 `TrashService`，是因为**去重处置**（`DuplicateDeletionExecutor`）
 * 也经这里移入回收站，两条路径必须共用同一段状态机。
 */
class DefaultLibraryMutationRepository(
    private val trashService: TrashService,
) : LibraryMutationRepository {

    override suspend fun trash(items: List<LibraryMedia>): BatchOperationSummary {
        if (items.isEmpty()) return BatchOperationSummary(0, emptyMap())
        val report = trashService.move(items)
        val failures = linkedMapOf<MediaLocationId, FileOperationFailure>()
        var succeeded = 0
        report.outcomes.forEach { (locationId, outcome) ->
            when (outcome) {
                is TrashOperationOutcome.Completed -> succeeded++
                else -> failures[locationId] = outcome.toFailure()
            }
        }
        return BatchOperationSummary(succeeded, failures)
    }

    override suspend fun restore(entry: TrashEntry): FileOperationResult {
        val outcome = trashService.restore(entry.locationId)
        return outcome.toFileOperationResult(fallback = entry)
    }

    override suspend fun purge(entry: TrashEntry): FileOperationResult {
        val outcome = trashService.purge(entry.locationId)
        return outcome.toFileOperationResult(fallback = entry)
    }

    /**
     * **`AuthorizationRequired` 一律翻成 [FileOperationFailure.PERMISSION_REQUIRED]**：
     * 需要用户授权的动作不能被当成成功，也不能被当成「未知错误」——上层要靠这个码去发起对话框。
     */
    private fun TrashOperationOutcome.toFailure(): FileOperationFailure = when (this) {
        is TrashOperationOutcome.AuthorizationRequired -> FileOperationFailure.PERMISSION_REQUIRED
        is TrashOperationOutcome.Blocked -> when (code) {
            "INSUFFICIENT_SPACE" -> FileOperationFailure.PARTIAL
            "BLOCKED_BY_RUNNING_TASK" -> FileOperationFailure.PARTIAL
            "SOURCE_NOT_FOUND", "ENTRY_NOT_FOUND" -> FileOperationFailure.SOURCE_MISSING
            else -> FileOperationFailure.UNKNOWN
        }
        is TrashOperationOutcome.Failed -> when (code) {
            "PERMISSION_DENIED" -> FileOperationFailure.PERMISSION_REQUIRED
            "SOURCE_MISSING" -> FileOperationFailure.SOURCE_MISSING
            else -> FileOperationFailure.UNKNOWN
        }
        is TrashOperationOutcome.Completed -> FileOperationFailure.UNKNOWN
        is TrashOperationOutcome.Purged -> FileOperationFailure.TARGET_MISSING
    }

    /**
     * [fallback] 是调用方手上的那条记录：`Purged` 只带回 `locationId`（记录可能已经被删掉），
     * 幂等成功时只能靠它回答「哪个 URI 当作成功结果」。
     */
    private fun TrashOperationOutcome.toFileOperationResult(fallback: TrashEntry): FileOperationResult = when (this) {
        is TrashOperationOutcome.Completed -> FileOperationResult.Success(entry.restoreUri ?: entry.originalUri)
        // 已经不存在 = 用户想要的结果已经达成，这不是失败（幂等语义，§8.7 规则 3）。
        is TrashOperationOutcome.Purged -> FileOperationResult.Success(fallback.originalUri)
        is TrashOperationOutcome.Failed,
        is TrashOperationOutcome.Blocked,
        is TrashOperationOutcome.AuthorizationRequired,
        -> FileOperationResult.RecoverableFailure(toFailure())
    }
}
