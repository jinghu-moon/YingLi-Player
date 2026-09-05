package seeyuer.yingli.player.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "media_sources", indices = [Index(value = ["rootUri"], unique = true)], primaryKeys = ["id"])
data class MediaSourceEntity(
    val id: String,
    val displayName: String,
    val rootUri: String,
    val mode: String,
    val volumeId: String?,
    val accessState: String,
    val includeHidden: Boolean,
    val lastSyncedEpochMillis: Long?,
    val mediaCount: Int,
)

@Entity(
    tableName = "media_items",
    primaryKeys = ["id"],
    indices = [Index("title"), Index("completed"), Index("playbackPositionMillis")],
)
data class MediaItemEntity(
    val id: String,
    val title: String,
    val playbackPositionMillis: Long,
    val completed: Boolean,
)

@Entity(
    tableName = "media_locations",
    foreignKeys = [ForeignKey(
        entity = MediaSourceEntity::class,
        parentColumns = ["id"],
        childColumns = ["sourceId"],
        onDelete = ForeignKey.NO_ACTION,
    )],
    indices = [
        Index("sourceId"),
        Index(value = ["uri"], unique = true),
        Index(value = ["volumeId", "documentId"]),
        Index("modifiedEpochMillis"),
        Index("durationMillis"),
        Index("width"),
        Index("missingScanCount"),
    ],
    primaryKeys = ["id"],
)
data class MediaLocationEntity(
    val id: String,
    val sourceId: String,
    val uri: String,
    val volumeId: String?,
    val documentId: String?,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedEpochMillis: Long,
    val durationMillis: Long?,
    val width: Int?,
    val height: Int?,
    val missingScanCount: Int,
    val lastSeenEpochMillis: Long,
    val fastFingerprint: String?,
    val contentHash: String?,
)

@Entity(
    tableName = "media_item_locations",
    primaryKeys = ["mediaItemId", "locationId"],
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaItemId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MediaLocationEntity::class,
            parentColumns = ["id"],
            childColumns = ["locationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("mediaItemId"), Index(value = ["locationId"], unique = true)],
)
data class MediaItemLocationEntity(val mediaItemId: String, val locationId: String)

@Entity(
    tableName = "media_tags",
    primaryKeys = ["mediaItemId", "tag"],
    foreignKeys = [ForeignKey(
        entity = MediaItemEntity::class,
        parentColumns = ["id"],
        childColumns = ["mediaItemId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("mediaItemId")],
)
data class MediaTagEntity(val mediaItemId: String, val tag: String)
