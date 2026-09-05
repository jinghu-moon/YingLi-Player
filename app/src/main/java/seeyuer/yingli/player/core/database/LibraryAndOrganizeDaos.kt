package seeyuer.yingli.player.core.database

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
    @RawQuery(observedEntities = [MediaItemEntity::class, MediaLocationEntity::class, MediaItemLocationEntity::class, MediaSourceEntity::class, PlaybackHistoryEntity::class, TrashEntryEntity::class, MediaTagEntity::class])
    fun observePage(query: SupportSQLiteQuery): Flow<List<LibraryMediaRow>>

    @RawQuery(observedEntities = [MediaItemEntity::class, MediaLocationEntity::class, MediaItemLocationEntity::class, MediaSourceEntity::class, PlaybackHistoryEntity::class, TrashEntryEntity::class, MediaTagEntity::class])
    fun observeCount(query: SupportSQLiteQuery): Flow<Int>

    @RawQuery
    suspend fun page(query: SupportSQLiteQuery): List<LibraryMediaRow>

    @RawQuery
    suspend fun count(query: SupportSQLiteQuery): Int

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
