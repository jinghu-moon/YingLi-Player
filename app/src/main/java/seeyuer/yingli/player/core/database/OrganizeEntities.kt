package seeyuer.yingli.player.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "tag_definitions",
    primaryKeys = ["id"],
    indices = [Index(value = ["name"], unique = true)],
)
data class TagDefinitionEntity(
    val id: String,
    val name: String,
    val color: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "media_tag_refs",
    primaryKeys = ["mediaItemId", "tagId"],
    foreignKeys = [ForeignKey(
        entity = TagDefinitionEntity::class,
        parentColumns = ["id"],
        childColumns = ["tagId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("mediaItemId"), Index("tagId")],
)
data class MediaTagRefEntity(val mediaItemId: String, val tagId: String)

@Entity(tableName = "favorites", primaryKeys = ["mediaItemId"], indices = [Index("createdAtEpochMillis")])
data class FavoriteEntity(val mediaItemId: String, val createdAtEpochMillis: Long)

@Entity(
    tableName = "playlists",
    primaryKeys = ["id"],
    indices = [Index(value = ["name"], unique = true)],
)
data class PlaylistEntity(
    val id: String,
    val name: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlistId", "mediaItemId"],
    foreignKeys = [ForeignKey(
        entity = PlaylistEntity::class,
        parentColumns = ["id"],
        childColumns = ["playlistId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("playlistId"), Index("mediaItemId")],
)
data class PlaylistItemEntity(
    val playlistId: String,
    val mediaItemId: String,
    val position: Int,
    val addedAtEpochMillis: Long,
)

@Entity(
    tableName = "collections",
    primaryKeys = ["id"],
    indices = [Index(value = ["name"], unique = true)],
)
data class CollectionEntity(
    val id: String,
    val name: String,
    val kind: String,
    val serializedFilter: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "collection_items",
    primaryKeys = ["collectionId", "mediaItemId"],
    foreignKeys = [ForeignKey(
        entity = CollectionEntity::class,
        parentColumns = ["id"],
        childColumns = ["collectionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("collectionId"), Index("mediaItemId")],
)
data class CollectionItemEntity(
    val collectionId: String,
    val mediaItemId: String,
    val addedAtEpochMillis: Long,
)

@Entity(tableName = "playback_history", primaryKeys = ["mediaItemId"], indices = [Index("lastPlayedAtEpochMillis")])
data class PlaybackHistoryEntity(
    val mediaItemId: String,
    val playCount: Int,
    val lastPlayedAtEpochMillis: Long,
    val lastPositionMillis: Long,
)

@Entity(tableName = "recently_organized", primaryKeys = ["mediaItemId"], indices = [Index("organizedAtEpochMillis")])
data class RecentlyOrganizedEntity(
    val mediaItemId: String,
    val organizedAtEpochMillis: Long,
    val action: String,
)
