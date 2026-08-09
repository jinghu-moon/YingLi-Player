package seeyuer.yingli.player.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaSourceDao {
    @Query("SELECT * FROM media_sources ORDER BY displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<MediaSourceEntity>>

    @Query("SELECT * FROM media_sources WHERE id = :id")
    suspend fun get(id: String): MediaSourceEntity?

    @Upsert
    suspend fun upsert(entity: MediaSourceEntity)

    @Query("UPDATE media_sources SET accessState = :state WHERE id = :id")
    suspend fun updateAccessState(id: String, state: String)

    @Query("UPDATE media_sources SET mediaCount = :count, lastSyncedEpochMillis = :syncedAt WHERE id = :id")
    suspend fun updateScanSummary(id: String, count: Int, syncedAt: Long)
}

@Dao
interface MediaCatalogDao {
    @Query("SELECT * FROM media_items ORDER BY title COLLATE NOCASE")
    fun observeItems(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_tags")
    fun observeTags(): Flow<List<MediaTagEntity>>

    @Query("SELECT * FROM media_tags")
    suspend fun tagsSnapshot(): List<MediaTagEntity>

    @Query("SELECT * FROM media_locations WHERE sourceId = :sourceId")
    suspend fun locationsForSource(sourceId: String): List<MediaLocationEntity>

    @Query("SELECT media_item_locations.* FROM media_item_locations INNER JOIN media_locations ON media_locations.id = media_item_locations.locationId WHERE media_locations.sourceId = :sourceId")
    suspend fun linksForSource(sourceId: String): List<MediaItemLocationEntity>

    @Query("SELECT media_items.* FROM media_items INNER JOIN media_item_locations ON media_item_locations.mediaItemId = media_items.id INNER JOIN media_locations ON media_locations.id = media_item_locations.locationId WHERE media_locations.sourceId = :sourceId")
    suspend fun itemsForSource(sourceId: String): List<MediaItemEntity>

    @Upsert
    suspend fun upsertItems(items: List<MediaItemEntity>)

    @Insert
    suspend fun insertLocations(locations: List<MediaLocationEntity>)

    @Update
    suspend fun updateLocations(locations: List<MediaLocationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLinks(links: List<MediaItemLocationEntity>)

    @Query("DELETE FROM media_tags WHERE mediaItemId IN (:mediaItemIds)")
    suspend fun deleteTags(mediaItemIds: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<MediaTagEntity>)

    @Query("SELECT COUNT(DISTINCT media_item_locations.mediaItemId) FROM media_item_locations INNER JOIN media_locations ON media_locations.id = media_item_locations.locationId WHERE media_locations.sourceId = :sourceId AND media_locations.missingScanCount = 0")
    suspend fun availableItemCount(sourceId: String): Int

    @Query("SELECT * FROM media_items WHERE id = :mediaItemId")
    suspend fun item(mediaItemId: String): MediaItemEntity?

    @Query("SELECT media_locations.* FROM media_locations INNER JOIN media_item_locations ON media_item_locations.locationId = media_locations.id WHERE media_item_locations.mediaItemId = :mediaItemId AND media_locations.missingScanCount = 0 ORDER BY media_locations.lastSeenEpochMillis DESC LIMIT 1")
    suspend fun playableLocation(mediaItemId: String): MediaLocationEntity?

    @Query("SELECT * FROM media_locations WHERE id = :locationId AND missingScanCount = 0")
    suspend fun playableLocationById(locationId: String): MediaLocationEntity?

    @Query("UPDATE media_items SET playbackPositionMillis = :positionMillis, completed = :completed WHERE id = :mediaItemId")
    suspend fun updatePlaybackProgress(mediaItemId: String, positionMillis: Long, completed: Boolean): Int
}
