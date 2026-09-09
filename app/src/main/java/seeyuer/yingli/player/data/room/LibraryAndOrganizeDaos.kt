package seeyuer.yingli.player.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @RawQuery(observedEntities = [MediaItemEntity::class, MediaLocationEntity::class, MediaItemLocationEntity::class, MediaSourceEntity::class, PlaybackHistoryEntity::class, TrashEntryEntity::class, MediaTagEntity::class, CollectionEntity::class, CollectionItemEntity::class])
    fun observePage(query: SupportSQLiteQuery): Flow<List<LibraryMediaRow>>

    @RawQuery(observedEntities = [MediaItemEntity::class, MediaLocationEntity::class, MediaItemLocationEntity::class, MediaSourceEntity::class, PlaybackHistoryEntity::class, TrashEntryEntity::class, MediaTagEntity::class, CollectionEntity::class, CollectionItemEntity::class])
    fun observeCount(query: SupportSQLiteQuery): Flow<Int>

    @RawQuery
    suspend fun page(query: SupportSQLiteQuery): List<LibraryMediaRow>

    @RawQuery
    suspend fun count(query: SupportSQLiteQuery): Int

    @RawQuery
    suspend fun folders(query: SupportSQLiteQuery): List<LibraryFolderRow>

    @Query("SELECT * FROM trash_entries ORDER BY deletedAtEpochMillis DESC")
    fun observeTrash(): Flow<List<TrashEntryEntity>>

    @Query("SELECT * FROM trash_entries WHERE purgeAtEpochMillis <= :nowEpochMillis ORDER BY purgeAtEpochMillis")
    suspend fun expiredTrash(nowEpochMillis: Long): List<TrashEntryEntity>

    @Upsert
    suspend fun upsertTrash(entry: TrashEntryEntity)

    @Query("UPDATE trash_entries SET state = :state WHERE mediaItemId = :mediaItemId")
    suspend fun updateTrashState(mediaItemId: String, state: String)

    @Query("DELETE FROM trash_entries WHERE mediaItemId = :mediaItemId")
    suspend fun deleteTrash(mediaItemId: String)
}

@Dao
interface HomeDao {
    @Query(
        """
        WITH latest_location AS (
            SELECT media_item_locations.mediaItemId, media_locations.id AS locationId,
                ROW_NUMBER() OVER (
                    PARTITION BY media_item_locations.mediaItemId
                    ORDER BY media_locations.lastSeenEpochMillis DESC, media_locations.id DESC
                ) AS rowNumber
            FROM media_item_locations
            INNER JOIN media_locations ON media_locations.id = media_item_locations.locationId
            WHERE media_locations.missingScanCount = 0
        )
        SELECT COUNT(*) AS videoCount, COALESCE(SUM(media_locations.sizeBytes), 0) AS videoBytes
        FROM media_items
        INNER JOIN latest_location ON latest_location.mediaItemId = media_items.id AND latest_location.rowNumber = 1
        INNER JOIN media_locations ON media_locations.id = latest_location.locationId
        LEFT JOIN trash_entries ON trash_entries.mediaItemId = media_items.id
        WHERE trash_entries.mediaItemId IS NULL
        """,
    )
    fun observeStats(): Flow<HomeStatsRow>

    @Query(
        """
        WITH latest_location AS (
            SELECT media_item_locations.mediaItemId, media_locations.id AS locationId,
                ROW_NUMBER() OVER (
                    PARTITION BY media_item_locations.mediaItemId
                    ORDER BY media_locations.lastSeenEpochMillis DESC, media_locations.id DESC
                ) AS rowNumber
            FROM media_item_locations
            INNER JOIN media_locations ON media_locations.id = media_item_locations.locationId
            WHERE media_locations.missingScanCount = 0
        )
        SELECT media_items.id AS mediaId, media_locations.id AS locationId, media_locations.uri,
            media_items.title, media_sources.displayName AS folderAlias, media_locations.sizeBytes,
            media_locations.durationMillis, media_locations.width, media_locations.height,
            media_locations.modifiedEpochMillis, media_items.playbackPositionMillis
        FROM media_items
        INNER JOIN latest_location ON latest_location.mediaItemId = media_items.id AND latest_location.rowNumber = 1
        INNER JOIN media_locations ON media_locations.id = latest_location.locationId
        INNER JOIN media_sources ON media_sources.id = media_locations.sourceId
        INNER JOIN playback_history ON playback_history.mediaItemId = media_items.id
        LEFT JOIN trash_entries ON trash_entries.mediaItemId = media_items.id
        WHERE trash_entries.mediaItemId IS NULL
            AND media_items.completed = 0
            AND media_items.playbackPositionMillis >= :minimumPlaybackMillis
            AND (
                media_locations.durationMillis IS NULL OR
                media_locations.durationMillis - MIN(media_items.playbackPositionMillis, media_locations.durationMillis) > :nearEndMillis
            )
        ORDER BY playback_history.lastPlayedAtEpochMillis DESC, media_items.id DESC
        LIMIT :limit
        """,
    )
    fun observeContinueWatching(
        limit: Int,
        minimumPlaybackMillis: Long,
        nearEndMillis: Long,
    ): Flow<List<HomeMediaRow>>

    @Query(
        """
        WITH latest_location AS (
            SELECT media_item_locations.mediaItemId, media_locations.id AS locationId,
                ROW_NUMBER() OVER (
                    PARTITION BY media_item_locations.mediaItemId
                    ORDER BY media_locations.lastSeenEpochMillis DESC, media_locations.id DESC
                ) AS rowNumber
            FROM media_item_locations
            INNER JOIN media_locations ON media_locations.id = media_item_locations.locationId
            WHERE media_locations.missingScanCount = 0
        )
        SELECT media_items.id AS mediaId, media_locations.id AS locationId, media_locations.uri,
            media_items.title, media_sources.displayName AS folderAlias, media_locations.sizeBytes,
            media_locations.durationMillis, media_locations.width, media_locations.height,
            media_locations.modifiedEpochMillis, media_items.playbackPositionMillis
        FROM media_items
        INNER JOIN latest_location ON latest_location.mediaItemId = media_items.id AND latest_location.rowNumber = 1
        INNER JOIN media_locations ON media_locations.id = latest_location.locationId
        INNER JOIN media_sources ON media_sources.id = media_locations.sourceId
        LEFT JOIN trash_entries ON trash_entries.mediaItemId = media_items.id
        WHERE trash_entries.mediaItemId IS NULL
        ORDER BY media_locations.modifiedEpochMillis DESC, media_items.id DESC
        LIMIT :limit
        """,
    )
    fun observeRecentlyAdded(limit: Int): Flow<List<HomeMediaRow>>

    @Query(
        """
        SELECT collections.id AS collectionId, collections.name,
            COUNT(collection_items.mediaItemId) AS itemCount, collections.updatedAtEpochMillis
        FROM collections
        LEFT JOIN collection_items ON collection_items.collectionId = collections.id
        GROUP BY collections.id
        ORDER BY collections.updatedAtEpochMillis DESC, collections.id DESC
        LIMIT :limit
        """,
    )
    fun observeCollections(limit: Int): Flow<List<HomeCollectionRow>>

    @Query(
        """
        WITH latest_location AS (
            SELECT media_item_locations.mediaItemId, media_locations.id AS locationId,
                ROW_NUMBER() OVER (
                    PARTITION BY media_item_locations.mediaItemId
                    ORDER BY media_locations.lastSeenEpochMillis DESC, media_locations.id DESC
                ) AS rowNumber
            FROM media_item_locations
            INNER JOIN media_locations ON media_locations.id = media_item_locations.locationId
            WHERE media_locations.missingScanCount = 0
        )
        SELECT media_sources.id AS sourceId, media_sources.displayName AS name,
            COUNT(*) AS itemCount, COALESCE(SUM(media_locations.sizeBytes), 0) AS sizeBytes
        FROM media_items
        INNER JOIN latest_location ON latest_location.mediaItemId = media_items.id AND latest_location.rowNumber = 1
        INNER JOIN media_locations ON media_locations.id = latest_location.locationId
        INNER JOIN media_sources ON media_sources.id = media_locations.sourceId
        LEFT JOIN trash_entries ON trash_entries.mediaItemId = media_items.id
        WHERE trash_entries.mediaItemId IS NULL
        GROUP BY media_sources.id
        ORDER BY itemCount DESC, sizeBytes DESC, media_sources.id
        LIMIT :limit
        """,
    )
    fun observeFrequentFolders(limit: Int): Flow<List<HomeFolderRow>>
}

@Dao
interface OrganizeDao {
    @Query("SELECT * FROM tag_definitions ORDER BY name COLLATE NOCASE")
    fun observeTags(): Flow<List<TagDefinitionEntity>>

    @Query("SELECT * FROM tag_definitions WHERE id = :tagId")
    suspend fun tag(tagId: String): TagDefinitionEntity?

    @Query("SELECT * FROM tag_definitions WHERE id IN (:tagIds)")
    suspend fun tags(tagIds: List<String>): List<TagDefinitionEntity>

    @Upsert
    suspend fun upsertTag(tag: TagDefinitionEntity)

    @Query("SELECT COUNT(*) FROM tag_definitions WHERE name = :name COLLATE NOCASE AND id != :exceptId")
    suspend fun tagNameCount(name: String, exceptId: String): Int

    @Query("DELETE FROM tag_definitions WHERE id = :tagId")
    suspend fun deleteTag(tagId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTagRefs(refs: List<MediaTagRefEntity>)

    @Query("DELETE FROM media_tag_refs WHERE mediaItemId IN (:mediaItemIds) AND tagId = :tagId")
    suspend fun removeTagRefs(mediaItemIds: List<String>, tagId: String)

    @Query("SELECT * FROM favorites ORDER BY createdAtEpochMillis DESC")
    fun observeFavorites(): Flow<List<FavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorites(favorites: List<FavoriteEntity>)

    @Query("DELETE FROM favorites WHERE mediaItemId IN (:mediaItemIds)")
    suspend fun deleteFavorites(mediaItemIds: List<String>)

    @Query("SELECT * FROM playlists ORDER BY updatedAtEpochMillis DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist_items ORDER BY playlistId, position")
    fun observePlaylistItems(): Flow<List<PlaylistItemEntity>>

    @Upsert
    suspend fun upsertPlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistItems(items: List<PlaylistItemEntity>)

    @Query("SELECT COUNT(*) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun playlistItemCount(playlistId: String): Int

    @Query("SELECT * FROM collections ORDER BY updatedAtEpochMillis DESC")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collection_items ORDER BY addedAtEpochMillis")
    fun observeCollectionItems(): Flow<List<CollectionItemEntity>>

    @Upsert
    suspend fun upsertCollection(collection: CollectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollectionItems(items: List<CollectionItemEntity>)

    @Query("SELECT * FROM playback_history ORDER BY lastPlayedAtEpochMillis DESC")
    fun observeHistory(): Flow<List<PlaybackHistoryEntity>>

    @Query("SELECT * FROM playback_history WHERE mediaItemId = :mediaItemId")
    suspend fun history(mediaItemId: String): PlaybackHistoryEntity?

    @Upsert
    suspend fun upsertHistory(history: PlaybackHistoryEntity)

    @Query("SELECT * FROM recently_organized ORDER BY organizedAtEpochMillis DESC LIMIT :limit")
    fun observeRecentlyOrganized(limit: Int): Flow<List<RecentlyOrganizedEntity>>

    @Upsert
    suspend fun upsertRecentlyOrganized(entry: RecentlyOrganizedEntity)
}
