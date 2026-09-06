package seeyuer.yingli.player.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BackupDao {
    @Query("SELECT * FROM tag_definitions ORDER BY id")
    suspend fun tags(): List<TagDefinitionEntity>

    @Query("SELECT * FROM media_tag_refs ORDER BY mediaItemId, tagId")
    suspend fun tagRefs(): List<MediaTagRefEntity>

    @Query("SELECT * FROM favorites ORDER BY mediaItemId")
    suspend fun favorites(): List<FavoriteEntity>

    @Query("SELECT * FROM playlists ORDER BY id")
    suspend fun playlists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_items ORDER BY playlistId, position")
    suspend fun playlistItems(): List<PlaylistItemEntity>

    @Query("SELECT * FROM collections ORDER BY id")
    suspend fun collections(): List<CollectionEntity>

    @Query("SELECT * FROM collection_items ORDER BY collectionId, mediaItemId")
    suspend fun collectionItems(): List<CollectionItemEntity>

    @Query("SELECT * FROM playback_history ORDER BY mediaItemId")
    suspend fun history(): List<PlaybackHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTagsKeepingExisting(values: List<TagDefinitionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTagsReplacing(values: List<TagDefinitionEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTagRefs(values: List<MediaTagRefEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFavoritesKeepingExisting(values: List<FavoriteEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavoritesReplacing(values: List<FavoriteEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistsKeepingExisting(values: List<PlaylistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistsReplacing(values: List<PlaylistEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistItemsKeepingExisting(values: List<PlaylistItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistItemsReplacing(values: List<PlaylistItemEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCollectionsKeepingExisting(values: List<CollectionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollectionsReplacing(values: List<CollectionEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCollectionItemsKeepingExisting(values: List<CollectionItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollectionItemsReplacing(values: List<CollectionItemEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHistoryKeepingExisting(values: List<PlaybackHistoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryReplacing(values: List<PlaybackHistoryEntity>)

    @Query("DELETE FROM media_tag_refs")
    suspend fun clearTagRefs()

    @Query("DELETE FROM tag_definitions")
    suspend fun clearTags()

    @Query("DELETE FROM favorites")
    suspend fun clearFavorites()

    @Query("DELETE FROM playlist_items")
    suspend fun clearPlaylistItems()

    @Query("DELETE FROM playlists")
    suspend fun clearPlaylists()

    @Query("DELETE FROM collection_items")
    suspend fun clearCollectionItems()

    @Query("DELETE FROM collections")
    suspend fun clearCollections()

    @Query("DELETE FROM playback_history")
    suspend fun clearHistory()
}
