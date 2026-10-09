package seeyuer.yingli.player.data.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.data.room.TrashEntryEntity
import seeyuer.yingli.player.data.room.YingLiDatabase
import seeyuer.yingli.player.domain.recycle.TrashBackend
import seeyuer.yingli.player.domain.recycle.TrashEntry
import seeyuer.yingli.player.domain.recycle.TrashRepository
import seeyuer.yingli.player.domain.recycle.TrashState

class RoomTrashRepository(database: YingLiDatabase) : TrashRepository {
    private val dao = database.libraryDao()

    override fun observe(): Flow<List<TrashEntry>> =
        dao.observeTrash().map { entries -> entries.map { it.toModel() } }

    override suspend fun byLocation(locationId: MediaLocationId): TrashEntry? =
        dao.trashEntry(locationId.value)?.toModel()

    override suspend fun byLocations(locationIds: Collection<MediaLocationId>): List<TrashEntry> {
        if (locationIds.isEmpty()) return emptyList()
        return dao.trashEntries(locationIds.map(MediaLocationId::value)).map { it.toModel() }
    }

    override suspend fun insert(entry: TrashEntry) = dao.insertTrash(entry.toEntity())

    override suspend fun update(entry: TrashEntry) = dao.updateTrash(entry.toEntity())

    override suspend fun remove(locationId: MediaLocationId) = dao.deleteTrash(locationId.value)

    override suspend fun inStates(states: Set<TrashState>): List<TrashEntry> {
        if (states.isEmpty()) return emptyList()
        return dao.trashEntriesInStates(states.map(TrashState::name)).map { it.toModel() }
    }

    override suspend fun expired(nowEpochMillis: Long): List<TrashEntry> =
        dao.expiredTrash(nowEpochMillis).map { it.toModel() }

    private fun TrashEntry.toEntity() = TrashEntryEntity(
        locationId = locationId.value,
        mediaItemId = mediaItemId.value,
        sourceId = sourceId,
        backend = backend.name,
        state = state.name,
        originalUri = originalUri.value,
        originalVolumeId = originalVolumeId,
        originalDocumentId = originalDocumentId,
        originalDisplayName = originalDisplayName,
        originalRelativePath = originalRelativePath,
        originalMimeType = originalMimeType,
        originalSizeBytes = originalSizeBytes,
        originalModifiedEpochMillis = originalModifiedEpochMillis,
        originalDurationMillis = originalDurationMillis,
        originalWidth = originalWidth,
        originalHeight = originalHeight,
        contentHash = contentHash,
        hashAlgorithmVersion = hashAlgorithmVersion,
        copyRelativePath = copyRelativePath,
        copySizeBytes = copySizeBytes,
        copyVerifiedAtEpochMillis = copyVerifiedAtEpochMillis,
        trashedAtEpochMillis = trashedAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis,
        systemExpiresAtEpochMillis = systemExpiresAtEpochMillis,
        restoreUri = restoreUri?.value,
        restoredAtEpochMillis = restoredAtEpochMillis,
        lastErrorCode = lastErrorCode,
        lastErrorDetail = lastErrorDetail,
        retryCount = retryCount,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    private fun TrashEntryEntity.toModel() = TrashEntry(
        locationId = MediaLocationId(locationId),
        mediaItemId = MediaItemId(mediaItemId),
        backend = TrashBackend.valueOf(backend),
        state = TrashState.valueOf(state),
        originalUri = MediaUri(originalUri),
        originalDisplayName = originalDisplayName,
        originalSizeBytes = originalSizeBytes,
        updatedAtEpochMillis = updatedAtEpochMillis,
        sourceId = sourceId,
        originalVolumeId = originalVolumeId,
        originalDocumentId = originalDocumentId,
        originalRelativePath = originalRelativePath,
        originalMimeType = originalMimeType,
        originalModifiedEpochMillis = originalModifiedEpochMillis,
        originalDurationMillis = originalDurationMillis,
        originalWidth = originalWidth,
        originalHeight = originalHeight,
        contentHash = contentHash,
        hashAlgorithmVersion = hashAlgorithmVersion,
        copyRelativePath = copyRelativePath,
        copySizeBytes = copySizeBytes,
        copyVerifiedAtEpochMillis = copyVerifiedAtEpochMillis,
        trashedAtEpochMillis = trashedAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis,
        systemExpiresAtEpochMillis = systemExpiresAtEpochMillis,
        restoreUri = restoreUri?.let(::MediaUri),
        restoredAtEpochMillis = restoredAtEpochMillis,
        lastErrorCode = lastErrorCode,
        lastErrorDetail = lastErrorDetail,
        retryCount = retryCount,
    )
}
