package seeyuer.yingli.player.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import seeyuer.yingli.player.core.database.TrashEntryEntity
import seeyuer.yingli.player.core.database.YingLiDatabase
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.library.TrashEntry
import seeyuer.yingli.player.domain.library.TrashRepository
import seeyuer.yingli.player.domain.library.TrashState

class RoomTrashRepository(database: YingLiDatabase) : TrashRepository {
    private val dao = database.libraryDao()
    override fun observe(): Flow<List<TrashEntry>> = dao.observeTrash().map { entries -> entries.map { it.toModel() } }
    override suspend fun put(entry: TrashEntry) = dao.upsertTrash(entry.toEntity())
    override suspend fun updateState(mediaId: MediaItemId, state: TrashState) =
        dao.updateTrashState(mediaId.value, state.name)
    override suspend fun remove(mediaId: MediaItemId) = dao.deleteTrash(mediaId.value)
    override suspend fun expired(nowEpochMillis: Long): List<TrashEntry> =
        dao.expiredTrash(nowEpochMillis).map { it.toModel() }

    private fun TrashEntry.toEntity() = TrashEntryEntity(
        mediaId.value, locationId.value, originalUri.value, trashedUri?.value,
        deletedAtEpochMillis, purgeAtEpochMillis, state.name,
    )

    private fun TrashEntryEntity.toModel() = TrashEntry(
        MediaItemId(mediaItemId), MediaLocationId(locationId), MediaUri(originalUri),
        trashedUri?.let(::MediaUri), deletedAtEpochMillis, purgeAtEpochMillis, TrashState.valueOf(state),
    )
}
