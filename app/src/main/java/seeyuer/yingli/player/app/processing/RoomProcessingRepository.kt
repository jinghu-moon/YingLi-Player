package seeyuer.yingli.player.app.processing

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import seeyuer.yingli.player.core.database.ProcessingProjectEntity
import seeyuer.yingli.player.core.database.ProcessingProjectInputEntity
import seeyuer.yingli.player.core.database.ProcessingTaskEntity
import seeyuer.yingli.player.core.database.ProcessingTaskEventEntity
import seeyuer.yingli.player.core.database.YingLiDatabase
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.processing.ProcessingProgress
import seeyuer.yingli.player.domain.processing.ProcessingProject
import seeyuer.yingli.player.domain.processing.ProcessingProjectId
import seeyuer.yingli.player.domain.processing.ProcessingProjectType
import seeyuer.yingli.player.domain.processing.ProcessingReduction
import seeyuer.yingli.player.domain.processing.ProcessingRepository
import seeyuer.yingli.player.domain.processing.ProcessingTask
import seeyuer.yingli.player.domain.processing.ProcessingTaskEvent
import seeyuer.yingli.player.domain.processing.ProcessingTaskId
import seeyuer.yingli.player.domain.processing.ProcessingTaskReducer
import seeyuer.yingli.player.domain.processing.ProcessingTaskState

class RoomProcessingRepository(
    private val database: YingLiDatabase,
) : ProcessingRepository {
    private val dao = database.processingDao()

    override val tasks: Flow<List<ProcessingTask>> = dao.observeTasks().map { entities -> entities.map { it.toDomain() } }

    override suspend fun projects(): List<ProcessingProject> {
        val inputs = dao.projectInputs().groupBy(ProcessingProjectInputEntity::projectId)
        return dao.projects().map { project ->
            ProcessingProject(
                ProcessingProjectId(project.id),
                ProcessingProjectType.valueOf(project.type),
                inputs[project.id].orEmpty().sortedBy(ProcessingProjectInputEntity::position)
                    .map { MediaItemId(it.mediaItemId) },
                project.outputPolicy,
                project.createdAtEpochMillis,
            )
        }
    }

    override suspend fun create(project: ProcessingProject, tasks: List<ProcessingTask>) {
        require(tasks.isNotEmpty() && tasks.all { it.projectId == project.id })
        database.withTransaction {
            dao.insertProject(ProcessingProjectEntity(
                project.id.value,
                project.type.name,
                project.outputPolicy,
                project.createdAtEpochMillis,
            ))
            dao.insertProjectInputs(project.inputMediaIds.mapIndexed { index, mediaId ->
                ProcessingProjectInputEntity(project.id.value, mediaId.value, index)
            })
            dao.insertTasks(tasks.map { it.toEntity() })
        }
    }

    override suspend fun apply(taskId: ProcessingTaskId, event: ProcessingTaskEvent): ProcessingReduction =
        database.withTransaction {
            val current = dao.task(taskId.value)?.toDomain()
                ?: return@withTransaction ProcessingReduction.Rejected(missingTask(taskId, event.atEpochMillis))
            val reduction = ProcessingTaskReducer.reduce(current, event)
            if (reduction is ProcessingReduction.Applied && shouldCheckpoint(current, reduction.task)) {
                dao.upsertTask(reduction.task.toEntity())
                dao.insertEvent(ProcessingTaskEventEntity(
                    taskId.value,
                    dao.nextEventSequence(taskId.value),
                    event::class.simpleName.orEmpty(),
                    event.payload(),
                    event.atEpochMillis,
                ))
            }
            reduction
        }

    override suspend fun recoverInterrupted() {
        dao.interruptedTasks().forEach { entity ->
            apply(ProcessingTaskId(entity.id), ProcessingTaskEvent.Recover(entity.updatedAtEpochMillis + 1))
        }
    }

    override suspend fun clearTerminal() {
        dao.clearTerminalTasks()
    }

    private fun shouldCheckpoint(before: ProcessingTask, after: ProcessingTask): Boolean {
        if (before.state != after.state) return true
        val previous = before.progress ?: return true
        val current = after.progress ?: return true
        val total = current.totalUnits
        val enoughProgress = total != null && total > 0 && current.processedUnits - previous.processedUnits >= total / 100
        val enoughTime = after.updatedAtEpochMillis - before.updatedAtEpochMillis >= CHECKPOINT_INTERVAL_MILLIS
        return enoughProgress || enoughTime || current.fraction == 1f
    }

    private fun ProcessingTask.toEntity() = ProcessingTaskEntity(
        id.value,
        projectId.value,
        operationKey,
        state.name,
        progress?.stage,
        progress?.processedUnits,
        progress?.totalUnits,
        progress?.unitsPerSecond,
        progress?.estimatedRemainingMillis,
        priority,
        attempt,
        errorCode,
        outputDisplayName,
        outputToken,
        createdAtEpochMillis,
        updatedAtEpochMillis,
    )

    private fun ProcessingTaskEntity.toDomain() = ProcessingTask(
        ProcessingTaskId(id),
        ProcessingProjectId(projectId),
        operationKey,
        ProcessingTaskState.valueOf(state),
        stage?.let { ProcessingProgress(it, processedUnits ?: 0, totalUnits, unitsPerSecond, estimatedRemainingMillis) },
        priority,
        attempt,
        errorCode,
        outputDisplayName,
        outputToken,
        createdAtEpochMillis,
        updatedAtEpochMillis,
    )

    private fun ProcessingTaskEvent.payload(): String? = when (this) {
        is ProcessingTaskEvent.Progressed -> listOf(
            progress.stage,
            progress.processedUnits,
            progress.totalUnits.orEmpty(),
        ).joinToString("|")
        is ProcessingTaskEvent.Succeed -> output.displayName
        is ProcessingTaskEvent.Fail -> errorCode
        is ProcessingTaskEvent.Retry -> null
        else -> null
    }

    private fun missingTask(id: ProcessingTaskId, now: Long) = ProcessingTask(
        id,
        ProcessingProjectId("missing"),
        operationKey = "missing",
        state = ProcessingTaskState.FAILED,
        errorCode = "TASK_NOT_FOUND",
        createdAtEpochMillis = now.coerceAtLeast(0),
    )

    private fun Number?.orEmpty(): String = this?.toString().orEmpty()

    private companion object {
        const val CHECKPOINT_INTERVAL_MILLIS = 2_000L
    }
}
