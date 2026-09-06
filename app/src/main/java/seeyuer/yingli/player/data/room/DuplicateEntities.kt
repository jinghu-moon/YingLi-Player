package seeyuer.yingli.player.data.room

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "duplicate_fingerprints", primaryKeys = ["mediaItemId"], indices = [Index("sizeBytes"), Index("fullHash")])
data class DuplicateFingerprintEntity(
    val mediaItemId: String,
    val sizeBytes: Long,
    val quickHash: String?,
    val fullHash: String?,
    val durationMillis: Long?,
    val width: Int?,
    val height: Int?,
    val perceptualHashes: String,
    val algorithmVersion: Int,
    val sourceModifiedEpochMillis: Long,
    val generatedAtEpochMillis: Long,
)

@Entity(tableName = "duplicate_groups", primaryKeys = ["id"], indices = [Index("mode"), Index("generatedAtEpochMillis")])
data class DuplicateGroupEntity(
    val id: String,
    val mode: String,
    val sizeBytes: Long?,
    val fullHash: String?,
    val visualScore: Double?,
    val durationScore: Double?,
    val dimensionScore: Double?,
    val overallScore: Double?,
    val algorithmVersion: Int,
    val generatedAtEpochMillis: Long,
)

@Entity(
    tableName = "duplicate_group_members",
    primaryKeys = ["groupId", "mediaItemId"],
    foreignKeys = [ForeignKey(
        entity = DuplicateGroupEntity::class,
        parentColumns = ["id"],
        childColumns = ["groupId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("groupId"), Index("mediaItemId")],
)
data class DuplicateGroupMemberEntity(
    val groupId: String,
    val mediaItemId: String,
    val position: Int,
)
