package seeyuer.yingli.player.data.settings

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import seeyuer.yingli.player.data.room.CollectionEntity
import seeyuer.yingli.player.data.room.CollectionItemEntity
import seeyuer.yingli.player.data.room.FavoriteEntity
import seeyuer.yingli.player.data.room.PlaybackHistoryEntity
import seeyuer.yingli.player.data.room.PlaylistEntity
import seeyuer.yingli.player.data.room.PlaylistItemEntity
import seeyuer.yingli.player.data.room.TagDefinitionEntity
import seeyuer.yingli.player.data.room.MediaTagRefEntity
import seeyuer.yingli.player.data.room.YingLiDatabase
import seeyuer.yingli.player.data.preferences.ExportDirectoryPreference
import seeyuer.yingli.player.data.preferences.LibraryLayoutPreference
import seeyuer.yingli.player.data.preferences.LibrarySortPreference
import seeyuer.yingli.player.data.preferences.SortDirectionPreference
import seeyuer.yingli.player.data.preferences.ThemeRepository
import seeyuer.yingli.player.data.preferences.UserPreferences
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.domain.settings.BackupCollection
import seeyuer.yingli.player.domain.settings.BackupConflictStrategy
import seeyuer.yingli.player.domain.settings.BackupGateway
import seeyuer.yingli.player.domain.settings.BackupHistory
import seeyuer.yingli.player.domain.settings.BackupPlaylist
import seeyuer.yingli.player.domain.settings.BackupPreview
import seeyuer.yingli.player.domain.settings.BackupSelection
import seeyuer.yingli.player.domain.settings.BackupSnapshot
import seeyuer.yingli.player.domain.settings.BackupTag
import seeyuer.yingli.player.domain.settings.BackupTagAssignment

class RoomBackupGateway(
    private val database: YingLiDatabase,
    private val preferencesRepository: ThemeRepository,
    private val clock: AppClock,
) : BackupGateway {
    override suspend fun export(selection: BackupSelection): BackupSnapshot {
        require(!selection.isEmpty)
        val dao = database.backupDao()
        val playlists = if (selection.organize) dao.playlists() else emptyList()
        val playlistItems = if (selection.organize) dao.playlistItems().groupBy(PlaylistItemEntity::playlistId) else emptyMap()
        val collections = if (selection.organize) dao.collections() else emptyList()
        val collectionItems = if (selection.organize) dao.collectionItems().groupBy(CollectionItemEntity::collectionId) else emptyMap()
        return BackupSnapshot(
            createdAtEpochMillis = clock.now().toEpochMilli(),
            selection = selection,
            preferences = if (selection.preferences) preferencesRepository.settings.first().toBackupMap() else emptyMap(),
            tags = if (selection.organize) dao.tags().map { BackupTag(it.id, it.name, it.color) } else emptyList(),
            tagAssignments = if (selection.organize) dao.tagRefs().map {
                BackupTagAssignment(it.mediaItemId, it.tagId)
            } else emptyList(),
            favorites = if (selection.organize) dao.favorites().map(FavoriteEntity::mediaItemId) else emptyList(),
            playlists = playlists.map { entity ->
                BackupPlaylist(
                    entity.id,
                    entity.name,
                    playlistItems[entity.id].orEmpty().sortedBy(PlaylistItemEntity::position).map(PlaylistItemEntity::mediaItemId),
                )
            },
            collections = collections.map { entity ->
                BackupCollection(
                    entity.id,
                    entity.name,
                    entity.kind,
                    entity.serializedFilter,
                    collectionItems[entity.id].orEmpty().map(CollectionItemEntity::mediaItemId),
                )
            },
            history = if (selection.history) dao.history().map {
                BackupHistory(it.mediaItemId, it.playCount, it.lastPlayedAtEpochMillis, it.lastPositionMillis)
            } else emptyList(),
        )
    }

    override suspend fun preview(snapshot: BackupSnapshot): BackupPreview {
        val dao = database.backupDao()
        val existingIds = buildSet {
            if (snapshot.selection.organize) {
                addAll(dao.tags().map(TagDefinitionEntity::id))
                addAll(dao.favorites().map(FavoriteEntity::mediaItemId))
                addAll(dao.playlists().map(PlaylistEntity::id))
                addAll(dao.collections().map(CollectionEntity::id))
            }
            if (snapshot.selection.history) addAll(dao.history().map(PlaybackHistoryEntity::mediaItemId))
        }
        val incomingIds = snapshot.tags.map(BackupTag::id) + snapshot.favorites +
            snapshot.playlists.map(BackupPlaylist::id) + snapshot.collections.map(BackupCollection::id) +
            snapshot.history.map(BackupHistory::mediaId)
        return snapshot.toPreview(incomingIds.count(existingIds::contains))
    }

    override suspend fun restore(snapshot: BackupSnapshot, strategy: BackupConflictStrategy): BackupPreview {
        val preview = preview(snapshot)
        val previousPreferences = preferencesRepository.settings.first()
        if (snapshot.selection.preferences) preferencesRepository.update { snapshot.preferences.toUserPreferences(it) }
        try {
            database.withTransaction { restoreDatabase(snapshot, strategy) }
        } catch (failure: Exception) {
            if (snapshot.selection.preferences) preferencesRepository.update { previousPreferences }
            throw failure
        }
        return preview
    }

    private suspend fun restoreDatabase(snapshot: BackupSnapshot, strategy: BackupConflictStrategy) {
        val dao = database.backupDao()
        val now = clock.now().toEpochMilli()
        if (strategy == BackupConflictStrategy.REPLACE) {
            if (snapshot.selection.organize) {
                dao.clearTagRefs()
                dao.clearTags()
                dao.clearFavorites()
                dao.clearPlaylistItems()
                dao.clearPlaylists()
                dao.clearCollectionItems()
                dao.clearCollections()
            }
            if (snapshot.selection.history) dao.clearHistory()
        }
        if (snapshot.selection.organize) {
            val tags = snapshot.tags.map { TagDefinitionEntity(it.id, it.name, it.color, now, now) }
            val favorites = snapshot.favorites.map { FavoriteEntity(it, now) }
            val playlists = snapshot.playlists.map { PlaylistEntity(it.id, it.name, now, now) }
            val playlistItems = snapshot.playlists.flatMap { playlist ->
                playlist.mediaIds.mapIndexed { index, mediaId -> PlaylistItemEntity(playlist.id, mediaId, index, now) }
            }
            val collections = snapshot.collections.map {
                CollectionEntity(it.id, it.name, it.kind, it.filter, now, now)
            }
            val collectionItems = snapshot.collections.flatMap { collection ->
                collection.mediaIds.map { mediaId -> CollectionItemEntity(collection.id, mediaId, now) }
            }
            if (strategy == BackupConflictStrategy.REPLACE) {
                dao.insertTagsReplacing(tags)
                dao.insertFavoritesReplacing(favorites)
                dao.insertPlaylistsReplacing(playlists)
                dao.insertPlaylistItemsReplacing(playlistItems)
                dao.insertCollectionsReplacing(collections)
                dao.insertCollectionItemsReplacing(collectionItems)
            } else {
                dao.insertTagsKeepingExisting(tags)
                dao.insertFavoritesKeepingExisting(favorites)
                dao.insertPlaylistsKeepingExisting(playlists)
                dao.insertPlaylistItemsKeepingExisting(playlistItems)
                dao.insertCollectionsKeepingExisting(collections)
                dao.insertCollectionItemsKeepingExisting(collectionItems)
            }
            val existingTagIds = dao.tags().map(TagDefinitionEntity::id).toSet()
            dao.insertTagRefs(snapshot.tagAssignments.filter { it.tagId in existingTagIds }.map {
                MediaTagRefEntity(it.mediaId, it.tagId)
            })
        }
        if (snapshot.selection.history) {
            val history = snapshot.history.map {
                PlaybackHistoryEntity(it.mediaId, it.playCount, it.lastPlayedAtEpochMillis, it.lastPositionMillis)
            }
            if (strategy == BackupConflictStrategy.REPLACE) {
                dao.insertHistoryReplacing(history)
            } else {
                dao.insertHistoryKeepingExisting(history)
            }
        }
    }

    private fun BackupSnapshot.toPreview(conflicts: Int): BackupPreview = BackupPreview(
        preferenceCount = preferences.size,
        tagCount = tags.size,
        favoriteCount = favorites.size,
        playlistCount = playlists.size,
        collectionCount = collections.size,
        historyCount = history.size,
        conflictCount = conflicts,
        tagAssignmentCount = tagAssignments.size,
    )

    private fun UserPreferences.toBackupMap(): Map<String, String> = mapOf(
        "libraryLayout" to libraryLayout.name,
        "libraryBreadcrumb" to libraryBreadcrumb.name,
        "thumbnailScale" to thumbnailScale.toString(),
        "librarySort" to librarySort.name,
        "librarySortDirection" to librarySortDirection.name,
        "trashRetentionDays" to trashRetentionDays.toString(),
        "miniPlayer" to miniPlayerEnabled.toString(),
        "autoPip" to autoPictureInPicture.toString(),
        "exportDirectory" to exportDirectory.name,
    )

    private fun Map<String, String>.toUserPreferences(fallback: UserPreferences): UserPreferences = UserPreferences.sanitize(
        schemaVersion = UserPreferences.CURRENT_SCHEMA_VERSION,
        libraryLayout = get("libraryLayout") ?: fallback.libraryLayout.name,
        libraryBreadcrumb = get("libraryBreadcrumb") ?: fallback.libraryBreadcrumb.name,
        thumbnailScale = get("thumbnailScale")?.toFloatOrNull() ?: fallback.thumbnailScale,
        librarySort = get("librarySort") ?: fallback.librarySort.name,
        librarySortDirection = get("librarySortDirection") ?: fallback.librarySortDirection.name,
        trashRetentionDays = get("trashRetentionDays")?.toIntOrNull() ?: fallback.trashRetentionDays,
        miniPlayerEnabled = get("miniPlayer")?.toBooleanStrictOrNull() ?: fallback.miniPlayerEnabled,
        autoPictureInPicture = get("autoPip")?.toBooleanStrictOrNull() ?: fallback.autoPictureInPicture,
        exportDirectory = get("exportDirectory") ?: ExportDirectoryPreference.YINGLI_OUTPUT.name,
        customExportTreeUri = null,
    )
}
