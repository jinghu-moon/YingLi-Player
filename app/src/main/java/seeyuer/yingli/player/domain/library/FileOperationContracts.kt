package seeyuer.yingli.player.domain.library

import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.recycle.TrashEntry

enum class FileOperationFailure {
    NAME_CONFLICT,
    PERMISSION_REQUIRED,
    READ_ONLY,
    SOURCE_MISSING,
    TARGET_MISSING,
    VOLUME_OFFLINE,
    PARTIAL,
    UNKNOWN,
}

sealed interface FileOperationResult {
    data class Success(val uri: MediaUri) : FileOperationResult
    data class RecoverableFailure(val reason: FileOperationFailure) : FileOperationResult
    data class PartialSuccess(val succeeded: Int, val failed: Int) : FileOperationResult
}

data class FileOperationTarget(
    val mediaId: MediaItemId,
    val locationId: MediaLocationId,
    val sourceUri: MediaUri,
)

/**
 * 通用文件操作网关。
 *
 * **`trash` / `restore` / `purge` 已于阶段 4 删除**（设计稿 §14.5 第 4 项：删除 R3）。
 * 原因（F27）：应用对其他应用创建的共享媒体**没有路径访问权**，`renameTo` 根本不成立；
 * 而它在 `file://` 上「成功」时也只是把文件搬进一个既不受系统保护、也不受应用期限管理的隐藏目录，
 * 并让原 URI 失效。回收站的三个动作现在由 `RecycleBinStorage` 承担。
 */
interface FileOperationGateway {
    suspend fun rename(target: FileOperationTarget, newName: String): FileOperationResult
    suspend fun move(target: FileOperationTarget, destination: MediaUri): FileOperationResult
}

data class BatchOperationSummary(
    val succeeded: Int,
    /**
     * 键是**位置**：同一条目可被多次操作（多个位置），键成条目会让失败互相覆盖。
     */
    val failures: Map<MediaLocationId, FileOperationFailure>,
) {
    val failed: Int get() = failures.size
}

/**
 * 媒体库的破坏性动作入口。**实现必须经 `TrashService`**（状态机与后端分派都在那里），
 * 不得直接写 `trash_entries`（设计稿 §13：不引入第二个回收站写入口）。
 */
interface LibraryMutationRepository {
    suspend fun trash(items: List<LibraryMedia>): BatchOperationSummary
    suspend fun trashByIds(ids: Set<MediaItemId>): BatchOperationSummary = BatchOperationSummary(0, emptyMap())
    suspend fun restore(entry: TrashEntry): FileOperationResult
    suspend fun purge(entry: TrashEntry): FileOperationResult
}
