package seeyuer.yingli.player.data.room

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
    val includeNomedia: Boolean = false,
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
        Index("sizeBytes"),
        Index(value = ["contentHash", "hashAlgorithmVersion"]),
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
    val relativePath: String? = null,
    /**
     * 写入 [fastFingerprint] / [contentHash] 时使用的哈希算法版本（设计稿 §7.1）。
     *
     * 缓存有效性的**三元组**之一是 `(sizeBytes, modifiedEpochMillis, hashAlgorithmVersion)`。
     * 算法版本升级后旧哈希必须整体失效，因此版本号必须与哈希**同事务**写入：
     * 只要哈希非空，本列就必须非空。若两者不一致（本列为空而哈希非空，
     * 或本列等于旧版本），扫描必须重算而不是复用。
     */
    val hashAlgorithmVersion: Int? = null,
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
