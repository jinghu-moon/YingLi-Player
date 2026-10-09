package seeyuer.yingli.player.data.library

import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.library.BatchOperationSummary
import seeyuer.yingli.player.domain.library.FileOperationFailure
import seeyuer.yingli.player.domain.library.FileOperationGateway
import seeyuer.yingli.player.domain.library.FileOperationResult
import seeyuer.yingli.player.domain.library.FileOperationTarget
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryMutationRepository
import seeyuer.yingli.player.domain.library.TrashEntry
import seeyuer.yingli.player.domain.library.TrashRepository
import seeyuer.yingli.player.domain.library.TrashRetentionPolicy

class DefaultLibraryMutationRepository(
    private val gateway: FileOperationGateway,
    private val trashRepository: TrashRepository,
    private val clock: AppClock,
    private val retentionDays: suspend () -> Int = { TrashRetentionPolicy().retentionDays },
) : LibraryMutationRepository {
    override suspend fun trash(items: List<LibraryMedia>): BatchOperationSummary {
        var succeeded = 0
        val failures = linkedMapOf<MediaLocationId, FileOperationFailure>()
        // 按**位置**去重：条目的多个位置各自是一份字节，按 id 去重会漏掉后面的位置。
        items.distinctBy(LibraryMedia::locationId).forEach { item ->
            val result = gateway.trash(FileOperationTarget(item.id, item.locationId, item.uri))
            if (result is FileOperationResult.Success) {
                val now = clock.now().toEpochMilli()
                trashRepository.put(TrashEntry(
                    item.id,
                    item.locationId,
                    item.uri,
                    result.uri,
                    now,
                    TrashRetentionPolicy(retentionDays()).purgeAt(now),
                ))
                succeeded++
            } else {
                failures[item.locationId] = (result as? FileOperationResult.RecoverableFailure)?.reason
                    ?: FileOperationFailure.PARTIAL
            }
        }
        return BatchOperationSummary(succeeded, failures)
    }

    override suspend fun restore(entry: TrashEntry): FileOperationResult {
        val result = gateway.restore(entry)
        if (result is FileOperationResult.Success) trashRepository.remove(entry.locationId)
        return result
    }

    override suspend fun purge(entry: TrashEntry): FileOperationResult {
        val result = gateway.purge(entry)
        if (result is FileOperationResult.Success) trashRepository.remove(entry.locationId)
        return result
    }
}
