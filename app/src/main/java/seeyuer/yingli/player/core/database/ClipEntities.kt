package seeyuer.yingli.player.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "clip_projects",
    primaryKeys = ["id"],
    indices = [Index("sourceMediaId"), Index("updatedAtEpochMillis")],
)
data class ClipProjectEntity(
    val id: String,
    val sourceMediaId: String,
    val sourceLocationId: String,
    val sourceDurationMillis: Long,
    val exportMode: String,
    val preset: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "clip_segments",
    primaryKeys = ["id"],
    foreignKeys = [ForeignKey(
        entity = ClipProjectEntity::class,
        parentColumns = ["id"],
        childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("projectId"), Index(value = ["projectId", "position"], unique = true)],
)
data class ClipSegmentEntity(
    val id: String,
    val projectId: String,
    val startMillis: Long,
    val endMillis: Long,
    val name: String,
    val selected: Boolean,
    val position: Int,
)
