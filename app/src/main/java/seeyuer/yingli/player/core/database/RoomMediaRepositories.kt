package seeyuer.yingli.player.core.database

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import seeyuer.yingli.player.core.media.CatalogMutation
import seeyuer.yingli.player.core.media.CatalogSnapshot
import seeyuer.yingli.player.core.media.MediaCatalogRepository
import seeyuer.yingli.player.core.media.MediaSourceRepository
import seeyuer.yingli.player.core.model.media.*

class RoomMediaSourceRepository(private val dao: MediaSourceDao) : MediaSourceRepository {
    override fun observeSources(): Flow<List<MediaSource>> = dao.observeAll().map { list -> list.map { it.toModel() } }
    override suspend fun get(sourceId: MediaSourceId): MediaSource? = dao.get(sourceId.value)?.toModel()
    override suspend fun upsert(source: MediaSource) = dao.upsert(source.toEntity())
    override suspend fun markAccessState(sourceId: MediaSourceId, state: MediaSourceAccessState) =
        dao.updateAccessState(sourceId.value, state.name)
}

class RoomMediaCatalogRepository(private val database: YingLiDatabase) : MediaCatalogRepository {
    private val dao = database.mediaCatalogDao()

    override fun observeItems(): Flow<List<MediaItem>> = combine(dao.observeItems(), dao.observeTags()) { items, tags ->
        val byItem = tags.groupBy(MediaTagEntity::mediaItemId)
        items.map { it.toModel(byItem[it.id].orEmpty().map(MediaTagEntity::tag).toSet()) }
    }

    override suspend fun snapshot(sourceId: MediaSourceId): CatalogSnapshot = database.withTransaction {
        val tags = dao.tagsSnapshot().groupBy(MediaTagEntity::mediaItemId)
        CatalogSnapshot(
            items = dao.itemsForSource(sourceId.value).distinctBy(MediaItemEntity::id).map {
                it.toModel(tags[it.id].orEmpty().map(MediaTagEntity::tag).toSet())
            },
            locations = dao.locationsForSource(sourceId.value).map { it.toModel() },
            itemByLocation = dao.linksForSource(sourceId.value).associate {
                MediaLocationId(it.locationId) to MediaItemId(it.mediaItemId)
            },
        )
    }

    override suspend fun applyMutation(sourceId: MediaSourceId, mutation: CatalogMutation): Pair<Int, Int> =
        database.withTransaction {
            require(mutation.upsertLocations.all { it.sourceId == sourceId }) {
                "Catalog mutation contains a location owned by another source."
            }
            require(mutation.evidenceUpdates.all { it.sourceId == sourceId }) {
                "Catalog mutation contains evidence owned by another source."
            }
            val existing = dao.locationsForSource(sourceId.value)
            val existingIds = existing.map(MediaLocationEntity::id).toSet()
            val incoming = mutation.upsertLocations.map { it.copy(missingScanCount = 0).toEntity() }
            val evidenceById = mutation.evidenceUpdates
                .filter { it.id.value in existingIds }
                .associate { it.id.value to it.toEntity() }
            val missed = if (mutation.markMissing) {
                existing.filterNot { MediaLocationId(it.id) in mutation.seenLocationIds }
                    .map { entity ->
                        (evidenceById[entity.id] ?: entity).copy(missingScanCount = entity.missingScanCount + 1)
                    }
            } else {
                emptyList()
            }
            val items = mutation.upsertItems.map { it.toEntity() }
            if (items.isNotEmpty()) dao.upsertItems(items)
            val (locationUpdates, locationInserts) = incoming.partition { it.id in existingIds }
            if (locationInserts.isNotEmpty()) dao.insertLocations(locationInserts)
            val updatedIds = (locationUpdates + missed).map(MediaLocationEntity::id).toSet()
            val evidenceOnly = evidenceById.values.filterNot { it.id in updatedIds }
            if (locationUpdates.isNotEmpty() || missed.isNotEmpty() || evidenceOnly.isNotEmpty()) {
                dao.updateLocations(locationUpdates + missed + evidenceOnly)
            }
            val links = mutation.links.map { (location, item) -> MediaItemLocationEntity(item.value, location.value) }
            if (links.isNotEmpty()) dao.upsertLinks(links)
            if (items.isNotEmpty()) {
                dao.deleteTags(items.map(MediaItemEntity::id))
                val tags = mutation.upsertItems.flatMap { item -> item.tags.map { MediaTagEntity(item.id.value, it) } }
                if (tags.isNotEmpty()) dao.insertTags(tags)
            }
            database.mediaSourceDao().updateScanSummary(
                sourceId.value,
                dao.availableItemCount(sourceId.value),
                mutation.scanCompletedAtEpochMillis,
            )
            val added = incoming.count { it.id !in existingIds }
            added to (incoming.size - added)
        }
}

private fun MediaSource.toEntity() = MediaSourceEntity(
    id.value, displayName, rootUri.value, mode.name, volumeId?.value, accessState.name,
    includeHidden, lastSyncedEpochMillis, mediaCount,
)

private fun MediaSourceEntity.toModel() = MediaSource(
    MediaSourceId(id), displayName, MediaUri(rootUri), MediaSourceMode.valueOf(mode),
    volumeId?.let(::VolumeId), MediaSourceAccessState.valueOf(accessState), includeHidden,
    lastSyncedEpochMillis, mediaCount,
)

private fun MediaItem.toEntity() = MediaItemEntity(id.value, title, playbackPositionMillis, completed)
private fun MediaItemEntity.toModel(tags: Set<String>) = MediaItem(MediaItemId(id), title, playbackPositionMillis, completed, tags)

private fun MediaLocation.toEntity() = MediaLocationEntity(
    id.value, sourceId.value, uri.value, volumeId?.value, documentId, fileName, mimeType,
    sizeBytes, modifiedEpochMillis, durationMillis, width, height, missingScanCount, lastSeenEpochMillis,
    fastFingerprint, contentHash,
)

private fun MediaLocationEntity.toModel() = MediaLocation(
    MediaLocationId(id), MediaSourceId(sourceId), MediaUri(uri), volumeId?.let(::VolumeId),
    documentId, fileName, mimeType, sizeBytes, modifiedEpochMillis, durationMillis, width, height,
    missingScanCount, lastSeenEpochMillis, fastFingerprint, contentHash,
)
