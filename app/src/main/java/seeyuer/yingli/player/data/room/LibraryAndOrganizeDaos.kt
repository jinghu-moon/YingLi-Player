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

    /**
     * 回收站列表。
     *
     * **不只是 `ACTIVE`**：`CLEANUP_PENDING` / `RECONCILIATION_REQUIRED` / `FAILED` 都必须出现在
     * 列表里 —— 否则 §14.7 要求的「异常状态说明与恢复路径」根本没有入口，用户会看到文件凭空消失。
     * 排序按**状态优先级**（ACTIVE 在最前，异常的在后），同组内按移入时间倒序；用 `CASE` 而不是
     * 在 Kotlin 里再排一次，避免出现第二份优先级真源。
     */
    @Query(
        """
        SELECT * FROM trash_entries
        WHERE state IN ('ACTIVE', 'CLEANUP_PENDING', 'RECONCILIATION_REQUIRED', 'FAILED')
        ORDER BY CASE state
            WHEN 'ACTIVE' THEN 0
            WHEN 'CLEANUP_PENDING' THEN 1
            WHEN 'RECONCILIATION_REQUIRED' THEN 2
            ELSE 3
        END, trashedAtEpochMillis DESC
        """,
    )
    fun observeTrash(): Flow<List<TrashEntryEntity>>

    @Query("SELECT * FROM trash_entries WHERE locationId = :locationId LIMIT 1")
    suspend fun trashEntry(locationId: String): TrashEntryEntity?

    @Query("SELECT * FROM trash_entries WHERE locationId IN (:locationIds)")
    suspend fun trashEntries(locationIds: List<String>): List<TrashEntryEntity>

    @Query("SELECT * FROM trash_entries WHERE state IN (:states)")
    suspend fun trashEntriesInStates(states: List<String>): List<TrashEntryEntity>

    /**
     * 到期判定的两个后端各有自己的期限列（§8.7）：R2 用应用期限，R1 用 `DATE_EXPIRES` 快照。
     * 期限未知时**不**算到期（「只有数据来源能够可靠提供时才展示」）。
     */
    @Query(
        """
        SELECT * FROM trash_entries
        WHERE state = 'ACTIVE' AND (
            (backend = 'R2_APP_COPY' AND expiresAtEpochMillis IS NOT NULL AND expiresAtEpochMillis <= :nowEpochMillis)
            OR (backend = 'R1_SYSTEM' AND systemExpiresAtEpochMillis IS NOT NULL AND systemExpiresAtEpochMillis <= :nowEpochMillis)
        )
        ORDER BY updatedAtEpochMillis
        """,
    )
    suspend fun expiredTrash(nowEpochMillis: Long): List<TrashEntryEntity>

    /**
     * **`IGNORE` 而不是 `Upsert`**：重复点击 / 重试不得覆盖已存在的条目
     * （那会把一个 `ACTIVE` 条目打回 `STAGING`，等于丢掉已确认的移入结果）。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrash(entry: TrashEntryEntity)

    @Query("UPDATE trash_entries SET state = :state WHERE locationId = :locationId")
    suspend fun updateTrashState(locationId: String, state: String)

    /** 状态机需要整行更新（副本信息、期限、错误详情都在行上）。 */
    @Upsert
    suspend fun updateTrash(entry: TrashEntryEntity)

    @Query("DELETE FROM trash_entries WHERE locationId = :locationId")
    suspend fun deleteTrash(locationId: String)

    /**
     * 恢复成功后把位置行指到新 URI（§8.5 [调整]）。
     *
     * 三个字段一起重置不是顺手：`fastFingerprint` 是**便宜指纹缓存**，新文件的字节
     * 虽然与副本相同，但它在 MediaStore 里是一个新条目，重新算一次的成本远低于
     * 「信任一个可能对不上的缓存」；`missingScanCount`/`lastSeenEpochMillis`
     * 归零则让下一轮扫描立刻把它当成在线文件，而不是等它熬过缺失阈值。
     */
    @Query(
        """
        UPDATE media_locations
        SET uri = :uri, contentHash = :contentHash, hashAlgorithmVersion = :hashAlgorithmVersion,
            fastFingerprint = NULL, missingScanCount = 0, lastSeenEpochMillis = :nowEpochMillis
        WHERE id = :locationId
        """,
    )
    suspend fun rebindLocation(
        locationId: String,
        uri: String,
        contentHash: String?,
        hashAlgorithmVersion: Int?,
        nowEpochMillis: Long,
    )

    @Query("DELETE FROM media_locations WHERE id = :locationId")
    suspend fun deleteLocation(locationId: String)

    /** 条目还剩下别的位置时不动它（多位置媒体删掉一个位置不该整条消失）。 */
    @Query(
        """
        DELETE FROM media_items WHERE id = :mediaItemId
        AND NOT EXISTS (SELECT 1 FROM media_item_locations WHERE mediaItemId = :mediaItemId)
        """,
    )
    suspend fun deleteOrphanItem(mediaItemId: String)

    @Query(
        """
        DELETE FROM media_items
        WHERE NOT EXISTS (SELECT 1 FROM media_item_locations WHERE media_item_locations.mediaItemId = media_items.id)
        """,
    )
    suspend fun deleteItemsWithoutLocations(): Int
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
        WHERE """ + LibrarySql.VISIBLE_ITEM + """
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
        WHERE """ + LibrarySql.VISIBLE_ITEM + """
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
        WHERE """ + LibrarySql.VISIBLE_ITEM + """
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
        WHERE """ + LibrarySql.VISIBLE_ITEM + """
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
