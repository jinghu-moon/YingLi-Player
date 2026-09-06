package seeyuer.yingli.player.data.library

import seeyuer.yingli.player.core.common.AppClock
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
        val failures = linkedMapOf<seeyuer.yingli.player.core.model.media.MediaItemId, FileOperationFailure>()
        items.distinctBy(LibraryMedia::id).forEach { item ->
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
                failures[item.id] = (result as? FileOperationResult.RecoverableFailure)?.reason
                    ?: FileOperationFailure.PARTIAL
            }
        }
        return BatchOperationSummary(succeeded, failures)
    }

    override suspend fun restore(entry: TrashEntry): FileOperationResult {
        val result = gateway.restore(entry)
        if (result is FileOperationResult.Success) trashRepository.remove(entry.mediaId)
        return result
    }

    override suspend fun purge(entry: TrashEntry): FileOperationResult {
        val result = gateway.purge(entry)
        if (result is FileOperationResult.Success) trashRepository.remove(entry.mediaId)
        return result
    }
}
