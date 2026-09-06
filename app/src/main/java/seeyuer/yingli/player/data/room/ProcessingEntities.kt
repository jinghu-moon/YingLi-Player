package seeyuer.yingli.player.data.room

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "processing_projects", primaryKeys = ["id"], indices = [Index("createdAtEpochMillis")])
data class ProcessingProjectEntity(
    val id: String,
    val type: String,
    val outputPolicy: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "processing_project_inputs",
    primaryKeys = ["projectId", "mediaItemId"],
    foreignKeys = [ForeignKey(
        entity = ProcessingProjectEntity::class,
        parentColumns = ["id"],
        childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("projectId"), Index("mediaItemId")],
)
data class ProcessingProjectInputEntity(
    val projectId: String,
    val mediaItemId: String,
    val position: Int,
)

@Entity(
    tableName = "processing_tasks",
    primaryKeys = ["id"],
    foreignKeys = [ForeignKey(
        entity = ProcessingProjectEntity::class,
        parentColumns = ["id"],
        childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("projectId"), Index("state"), Index("updatedAtEpochMillis")],
)
data class ProcessingTaskEntity(
    val id: String,
    val projectId: String,
    val operationKey: String,
    val state: String,
    val stage: String?,
    val processedUnits: Long?,
    val totalUnits: Long?,
    val unitsPerSecond: Double?,
    val estimatedRemainingMillis: Long?,
    val priority: Int,
    val attempt: Int,
    val errorCode: String?,
    val outputDisplayName: String?,
    val outputToken: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "processing_task_events",
    primaryKeys = ["taskId", "sequence"],
    foreignKeys = [ForeignKey(
        entity = ProcessingTaskEntity::class,
        parentColumns = ["id"],
        childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("taskId"), Index("createdAtEpochMillis")],
)
data class ProcessingTaskEventEntity(
    val taskId: String,
    val sequence: Long,
    val eventType: String,
    val payload: String?,
    val createdAtEpochMillis: Long,
)
