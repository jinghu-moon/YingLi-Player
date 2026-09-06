package seeyuer.yingli.player.data.organize

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import seeyuer.yingli.player.data.room.CollectionEntity
import seeyuer.yingli.player.data.room.CollectionItemEntity
import seeyuer.yingli.player.data.room.FavoriteEntity
import seeyuer.yingli.player.data.room.MediaTagEntity
import seeyuer.yingli.player.data.room.MediaTagRefEntity
import seeyuer.yingli.player.data.room.PlaybackHistoryEntity
import seeyuer.yingli.player.data.room.PlaylistEntity
import seeyuer.yingli.player.data.room.PlaylistItemEntity
import seeyuer.yingli.player.data.room.RecentlyOrganizedEntity
import seeyuer.yingli.player.data.room.TagDefinitionEntity
import seeyuer.yingli.player.data.room.YingLiDatabase
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.common.IdGenerator
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.library.FilterExpression
import seeyuer.yingli.player.domain.organize.Collection
import seeyuer.yingli.player.domain.organize.CollectionId
import seeyuer.yingli.player.domain.organize.CollectionKind
import seeyuer.yingli.player.domain.organize.Favorite
import seeyuer.yingli.player.domain.organize.HistoryEntry
import seeyuer.yingli.player.domain.organize.HistoryRepository
import seeyuer.yingli.player.domain.organize.OrganizeMutationResult
import seeyuer.yingli.player.domain.organize.OrganizeRepository
import seeyuer.yingli.player.domain.organize.OrganizeSnapshot
import seeyuer.yingli.player.domain.organize.OrganizedAction
import seeyuer.yingli.player.domain.organize.Playlist
import seeyuer.yingli.player.domain.organize.PlaylistId
import seeyuer.yingli.player.domain.organize.RecentlyOrganizedEntry
import seeyuer.yingli.player.domain.organize.Tag
import seeyuer.yingli.player.domain.organize.TagColor
import seeyuer.yingli.player.domain.organize.TagId

class RoomOrganizeRepository(
    private val database: YingLiDatabase,
    private val clock: AppClock,
    private val idGenerator: IdGenerator,
) : OrganizeRepository, HistoryRepository {
    private val dao = database.organizeDao()
    private val playlistState = combine(dao.observePlaylists(), dao.observePlaylistItems(), ::Pair)
    private val collectionState = combine(dao.observeCollections(), dao.observeCollectionItems(), ::Pair)
    private val organizeState = combine(
        dao.observeTags(),
        dao.observeFavorites(),
        playlistState,
        collectionState,
        dao.observeRecentlyOrganized(20),
    ) { tags, favorites, playlists, collections, recent ->
        OrganizeSnapshot(
            tags = tags.map { it.toModel() },
            favorites = favorites.map { Favorite(MediaItemId(it.mediaItemId), it.createdAtEpochMillis) },
            playlists = playlists.first.map { playlist ->
                playlist.toModel(playlists.second.filter { it.playlistId == playlist.id })
            },
            collections = collections.first.map { collection ->
                collection.toModel(collections.second.filter { it.collectionId == collection.id })
            },
            recentlyOrganized = recent.map { it.toModel() },
        )
    }
    override val snapshot: Flow<OrganizeSnapshot> = organizeState
    override val history: Flow<List<HistoryEntry>> = dao.observeHistory().map { values ->
        values.map { it.toModel() }
    }

    override suspend fun createTag(name: String, color: TagColor): OrganizeMutationResult {
        val safeName = name.trim()
        if (safeName.isBlank() || safeName.length > 40) return OrganizeMutationResult.InvalidInput
        return mutate {
            val id = "tag_${idGenerator.newId()}".stableStorageId()
            if (dao.tagNameCount(safeName, id) > 0) return@mutate OrganizeMutationResult.NameConflict
            val now = clock.now().toEpochMilli()
            dao.upsertTag(TagDefinitionEntity(id, safeName, color.name, now, now))
            OrganizeMutationResult.Success
        }
    }

    override suspend fun updateTag(tag: Tag): OrganizeMutationResult = mutate {
        if (dao.tagNameCount(tag.name, tag.id.value) > 0) return@mutate OrganizeMutationResult.NameConflict
        dao.upsertTag(tag.toEntity())
        OrganizeMutationResult.Success
    }

    override suspend fun deleteTag(id: TagId): OrganizeMutationResult = mutate {
        database.withTransaction {
            dao.tag(id.value)?.let { database.mediaCatalogDao().deleteTagValue(it.name) }
            dao.deleteTag(id.value)
        }
        OrganizeMutationResult.Success
    }

    override suspend fun addTags(mediaIds: Set<MediaItemId>, tagIds: Set<TagId>): OrganizeMutationResult {
        if (mediaIds.isEmpty() || tagIds.isEmpty()) return OrganizeMutationResult.InvalidInput
        return mutate {
            database.withTransaction {
                val tagEntities = dao.tags(tagIds.map(TagId::value))
                if (tagEntities.size != tagIds.size) return@withTransaction
                dao.insertTagRefs(mediaIds.flatMap { media -> tagIds.map { MediaTagRefEntity(media.value, it.value) } })
                database.mediaCatalogDao().insertTags(mediaIds.flatMap { media ->
                    tagEntities.map { tag -> MediaTagEntity(media.value, tag.name) }
                })
                recordRecent(mediaIds, OrganizedAction.TAGGED)
            }
            OrganizeMutationResult.Success
        }
    }

    override suspend fun setFavorite(mediaIds: Set<MediaItemId>, favorite: Boolean): OrganizeMutationResult {
        if (mediaIds.isEmpty()) return OrganizeMutationResult.InvalidInput
        return mutate {
            database.withTransaction {
                if (favorite) {
                    val now = clock.now().toEpochMilli()
                    dao.insertFavorites(mediaIds.map { FavoriteEntity(it.value, now) })
                    recordRecent(mediaIds, OrganizedAction.FAVORITED)
                } else {
                    dao.deleteFavorites(mediaIds.map(MediaItemId::value))
                }
            }
            OrganizeMutationResult.Success
        }
    }

    override suspend fun createPlaylist(name: String, mediaIds: List<MediaItemId>): OrganizeMutationResult {
        val safeName = name.trim()
        if (safeName.isBlank() || mediaIds.distinct().size != mediaIds.size) return OrganizeMutationResult.InvalidInput
        return mutate {
            val id = "playlist_${idGenerator.newId()}".stableStorageId()
            val now = clock.now().toEpochMilli()
            database.withTransaction {
                dao.upsertPlaylist(PlaylistEntity(id, safeName, now, now))
                dao.insertPlaylistItems(mediaIds.mapIndexed { index, media -> PlaylistItemEntity(id, media.value, index, now) })
                recordRecent(mediaIds.toSet(), OrganizedAction.PLAYLISTED)
            }
            OrganizeMutationResult.Success
        }
    }

    override suspend fun createCollection(name: String, mediaIds: Set<MediaItemId>): OrganizeMutationResult =
        createCollectionInternal(name, CollectionKind.MANUAL, mediaIds, null)

    override suspend fun createSmartCollection(name: String, filter: FilterExpression): OrganizeMutationResult =
        createCollectionInternal(name, CollectionKind.SMART, emptySet(), filter)

    override suspend fun recordPlayback(
        mediaId: MediaItemId,
        positionMillis: Long,
        nowEpochMillis: Long,
        incognito: Boolean,
    ) {
        if (incognito || positionMillis < MINIMUM_COUNTED_PLAYBACK_MILLIS) return
        val existing = dao.history(mediaId.value)
        dao.upsertHistory(PlaybackHistoryEntity(
            mediaItemId = mediaId.value,
            playCount = (existing?.playCount ?: 0) + 1,
            lastPlayedAtEpochMillis = nowEpochMillis,
            lastPositionMillis = positionMillis.coerceAtLeast(0),
        ))
    }

    private suspend fun createCollectionInternal(
        name: String,
        kind: CollectionKind,
        mediaIds: Set<MediaItemId>,
        filter: FilterExpression?,
    ): OrganizeMutationResult {
        val safeName = name.trim()
        if (safeName.isBlank()) return OrganizeMutationResult.InvalidInput
        return mutate {
            val id = "collection_${idGenerator.newId()}".stableStorageId()
            val now = clock.now().toEpochMilli()
            database.withTransaction {
                dao.upsertCollection(CollectionEntity(id, safeName, kind.name, filter?.serialize(), now, now))
                if (mediaIds.isNotEmpty()) {
                    dao.insertCollectionItems(mediaIds.map { CollectionItemEntity(id, it.value, now) })
                    recordRecent(mediaIds, OrganizedAction.COLLECTED)
                }
            }
            OrganizeMutationResult.Success
        }
    }

    private suspend fun recordRecent(mediaIds: Set<MediaItemId>, action: OrganizedAction) {
        val now = clock.now().toEpochMilli()
        mediaIds.forEach { dao.upsertRecentlyOrganized(RecentlyOrganizedEntity(it.value, now, action.name)) }
    }

    private suspend fun mutate(block: suspend () -> OrganizeMutationResult): OrganizeMutationResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        OrganizeMutationResult.RetryableFailure
    }

    private fun String.stableStorageId(): String = replace(Regex("[^A-Za-z0-9_-]"), "_").take(128)

    private fun Tag.toEntity() = TagDefinitionEntity(id.value, name, color.name, createdAtEpochMillis, updatedAtEpochMillis)
    private fun TagDefinitionEntity.toModel() = Tag(TagId(id), name, TagColor.valueOf(color), createdAtEpochMillis, updatedAtEpochMillis)
    private fun PlaylistEntity.toModel(items: List<PlaylistItemEntity>) = Playlist(
        PlaylistId(id), name, items.sortedBy(PlaylistItemEntity::position).map { MediaItemId(it.mediaItemId) },
        createdAtEpochMillis, updatedAtEpochMillis,
    )
    private fun CollectionEntity.toModel(items: List<CollectionItemEntity>) = Collection(
        CollectionId(id), name, CollectionKind.valueOf(kind), items.map { MediaItemId(it.mediaItemId) }.toSet(),
        serializedFilter?.let(FilterExpression::deserialize), createdAtEpochMillis, updatedAtEpochMillis,
    )
    private fun PlaybackHistoryEntity.toModel() = HistoryEntry(
        MediaItemId(mediaItemId), playCount, lastPlayedAtEpochMillis, lastPositionMillis,
    )
    private fun RecentlyOrganizedEntity.toModel() = RecentlyOrganizedEntry(
        MediaItemId(mediaItemId), organizedAtEpochMillis, OrganizedAction.valueOf(action),
    )

    private companion object {
        const val MINIMUM_COUNTED_PLAYBACK_MILLIS = 10_000L
    }
}
