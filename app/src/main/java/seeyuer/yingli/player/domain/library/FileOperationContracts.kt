package seeyuer.yingli.player.domain.library

import kotlinx.coroutines.flow.Flow
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri

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

interface FileOperationGateway {
    suspend fun rename(target: FileOperationTarget, newName: String): FileOperationResult
    suspend fun move(target: FileOperationTarget, destination: MediaUri): FileOperationResult
    suspend fun trash(target: FileOperationTarget): FileOperationResult
    suspend fun restore(entry: TrashEntry): FileOperationResult
    suspend fun purge(entry: TrashEntry): FileOperationResult
}

enum class TrashState {
    TRASHED,
    RESTORING,
    PURGING,
    FAILED,
}

data class TrashEntry(
    val mediaId: MediaItemId,
    val locationId: MediaLocationId,
    val originalUri: MediaUri,
    val trashedUri: MediaUri?,
    val deletedAtEpochMillis: Long,
    val purgeAtEpochMillis: Long,
    val state: TrashState = TrashState.TRASHED,
) {
    init {
        require(deletedAtEpochMillis >= 0)
        require(purgeAtEpochMillis >= deletedAtEpochMillis)
    }
}

data class TrashRetentionPolicy(val retentionDays: Int = 30) {
    init {
        require(retentionDays in 1..365)
    }

    fun purgeAt(deletedAtEpochMillis: Long): Long =
        deletedAtEpochMillis + retentionDays * MILLIS_PER_DAY

    fun isExpired(entry: TrashEntry, nowEpochMillis: Long): Boolean = nowEpochMillis >= entry.purgeAtEpochMillis

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}

interface TrashRepository {
    fun observe(): Flow<List<TrashEntry>>
    suspend fun put(entry: TrashEntry)

    /**
     * 回收站条目的身份是**位置**而不是条目：一个 `MediaItem` 可以有多个位置，
     * 回收的是位置上的那份字节（设计稿 §14.4 第 6 项）。
     */
    suspend fun updateState(locationId: MediaLocationId, state: TrashState)
    suspend fun remove(locationId: MediaLocationId)
    suspend fun expired(nowEpochMillis: Long): List<TrashEntry>
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

interface LibraryMutationRepository {
    suspend fun trash(items: List<LibraryMedia>): BatchOperationSummary
    suspend fun trashByIds(ids: Set<MediaItemId>): BatchOperationSummary = BatchOperationSummary(0, emptyMap())
    suspend fun restore(entry: TrashEntry): FileOperationResult
    suspend fun purge(entry: TrashEntry): FileOperationResult
}
