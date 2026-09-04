package seeyuer.yingli.player.domain.processing

import kotlinx.coroutines.flow.Flow
import seeyuer.yingli.player.core.model.media.MediaItemId

private val STABLE_ID = Regex("[A-Za-z0-9_-]{1,128}")

@JvmInline
value class ProcessingProjectId(val value: String) {
    init { require(value.matches(STABLE_ID)) }
}

@JvmInline
value class ProcessingTaskId(val value: String) {
    init { require(value.matches(STABLE_ID)) }
}

enum class ProcessingProjectType {
    CLIP,
    COMPRESS,
    CONVERT,
    DEDUPLICATE,
}

data class ProcessingProject(
    val id: ProcessingProjectId,
    val type: ProcessingProjectType,
    val inputMediaIds: List<MediaItemId>,
    val outputPolicy: String,
    val createdAtEpochMillis: Long,
) {
    init {
        require(inputMediaIds.isNotEmpty())
        require(inputMediaIds.distinct().size == inputMediaIds.size)
        require(outputPolicy.isNotBlank())
        require(createdAtEpochMillis >= 0)
    }
}

enum class ProcessingTaskState {
    QUEUED,
    PREPARING,
    RUNNING,
    PAUSED,
    CANCELING,
    SUCCEEDED,
    FAILED,
    CANCELED;

    val terminal: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELED
}

data class ProcessingProgress(
    val stage: String,
    val processedUnits: Long,
    val totalUnits: Long? = null,
    val unitsPerSecond: Double? = null,
    val estimatedRemainingMillis: Long? = null,
) {
    init {
        require(stage.isNotBlank())
        require(processedUnits >= 0)
        require(totalUnits == null || totalUnits >= 0)
        require(totalUnits == null || processedUnits <= totalUnits)
        require(unitsPerSecond == null || unitsPerSecond >= 0)
        require(estimatedRemainingMillis == null || estimatedRemainingMillis >= 0)
    }

    val fraction: Float? = totalUnits?.takeIf { it > 0 }
        ?.let { (processedUnits.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

data class ProcessingTask(
    val id: ProcessingTaskId,
    val projectId: ProcessingProjectId,
    val operationKey: String = "default",
    val state: ProcessingTaskState = ProcessingTaskState.QUEUED,
    val progress: ProcessingProgress? = null,
    val priority: Int = 0,
    val attempt: Int = 0,
    val errorCode: String? = null,
    val outputDisplayName: String? = null,
    val outputToken: String? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long = createdAtEpochMillis,
) {
    init {
        require(priority in MIN_PRIORITY..MAX_PRIORITY)
        require(operationKey.isNotBlank() && operationKey.length <= 512)
        require(attempt >= 0)
        require(createdAtEpochMillis >= 0)
        require(updatedAtEpochMillis >= createdAtEpochMillis)
        require(errorCode == null || errorCode.matches(Regex("[A-Z][A-Z0-9_]+")))
        require(state == ProcessingTaskState.FAILED || errorCode == null)
        require(state == ProcessingTaskState.SUCCEEDED || outputDisplayName == null)
        require(state == ProcessingTaskState.SUCCEEDED || outputToken == null)
    }

    companion object {
        const val MIN_PRIORITY = -10
        const val MAX_PRIORITY = 10
    }
}

sealed interface ProcessingTaskEvent {
    val atEpochMillis: Long

    data class Prepare(override val atEpochMillis: Long) : ProcessingTaskEvent
    data class Start(override val atEpochMillis: Long) : ProcessingTaskEvent
    data class Progressed(val progress: ProcessingProgress, override val atEpochMillis: Long) : ProcessingTaskEvent
    data class Pause(override val atEpochMillis: Long) : ProcessingTaskEvent
    data class Resume(override val atEpochMillis: Long) : ProcessingTaskEvent
    data class RequestCancel(override val atEpochMillis: Long) : ProcessingTaskEvent
    data class FinishCancel(override val atEpochMillis: Long) : ProcessingTaskEvent
    data class Succeed(val output: ProcessingOutput, override val atEpochMillis: Long) : ProcessingTaskEvent
    data class Fail(val errorCode: String, override val atEpochMillis: Long) : ProcessingTaskEvent
    data class Recover(override val atEpochMillis: Long) : ProcessingTaskEvent
    data class Retry(override val atEpochMillis: Long) : ProcessingTaskEvent
}

sealed interface ProcessingReduction {
    data class Applied(val task: ProcessingTask) : ProcessingReduction
    data class Ignored(val task: ProcessingTask) : ProcessingReduction
    data class Rejected(val task: ProcessingTask) : ProcessingReduction
}

object ProcessingTaskReducer {
    fun reduce(task: ProcessingTask, event: ProcessingTaskEvent): ProcessingReduction {
        val timestamp = event.atEpochMillis.coerceAtLeast(task.updatedAtEpochMillis)
        fun applied(
            state: ProcessingTaskState,
            progress: ProcessingProgress? = task.progress,
            attempt: Int = task.attempt,
            errorCode: String? = null,
            outputDisplayName: String? = null,
            outputToken: String? = null,
        ) = ProcessingReduction.Applied(task.copy(
            state = state,
            progress = progress,
            attempt = attempt,
            errorCode = errorCode,
            outputDisplayName = outputDisplayName,
            outputToken = outputToken,
            updatedAtEpochMillis = timestamp,
        ))
        fun same(expected: ProcessingTaskState) = if (task.state == expected) {
            ProcessingReduction.Ignored(task)
        } else {
            ProcessingReduction.Rejected(task)
        }

        return when (event) {
            is ProcessingTaskEvent.Prepare -> when (task.state) {
                ProcessingTaskState.QUEUED -> applied(ProcessingTaskState.PREPARING)
                else -> same(ProcessingTaskState.PREPARING)
            }
            is ProcessingTaskEvent.Start -> when (task.state) {
                ProcessingTaskState.PREPARING -> applied(ProcessingTaskState.RUNNING, attempt = task.attempt + 1)
                else -> same(ProcessingTaskState.RUNNING)
            }
            is ProcessingTaskEvent.Progressed -> when {
                task.state != ProcessingTaskState.RUNNING -> ProcessingReduction.Rejected(task)
                task.progress?.processedUnits?.let { event.progress.processedUnits < it } == true ->
                    ProcessingReduction.Rejected(task)
                task.progress == event.progress -> ProcessingReduction.Ignored(task)
                else -> applied(ProcessingTaskState.RUNNING, event.progress)
            }
            is ProcessingTaskEvent.Pause -> when (task.state) {
                ProcessingTaskState.RUNNING -> applied(ProcessingTaskState.PAUSED)
                else -> same(ProcessingTaskState.PAUSED)
            }
            is ProcessingTaskEvent.Resume -> when (task.state) {
                ProcessingTaskState.PAUSED -> applied(ProcessingTaskState.QUEUED)
                else -> same(ProcessingTaskState.QUEUED)
            }
            is ProcessingTaskEvent.RequestCancel -> when {
                task.state.terminal -> ProcessingReduction.Rejected(task)
                task.state == ProcessingTaskState.CANCELING -> ProcessingReduction.Ignored(task)
                else -> applied(ProcessingTaskState.CANCELING)
            }
            is ProcessingTaskEvent.FinishCancel -> when (task.state) {
                ProcessingTaskState.CANCELING -> applied(ProcessingTaskState.CANCELED)
                else -> same(ProcessingTaskState.CANCELED)
            }
            is ProcessingTaskEvent.Succeed -> when {
                event.output.displayName.isBlank() || event.output.token.isBlank() -> ProcessingReduction.Rejected(task)
                task.state == ProcessingTaskState.RUNNING -> applied(
                    ProcessingTaskState.SUCCEEDED,
                    outputDisplayName = event.output.displayName,
                    outputToken = event.output.token,
                )
                else -> same(ProcessingTaskState.SUCCEEDED)
            }
            is ProcessingTaskEvent.Fail -> when {
                !event.errorCode.matches(Regex("[A-Z][A-Z0-9_]+")) -> ProcessingReduction.Rejected(task)
                task.state.terminal -> same(ProcessingTaskState.FAILED)
                else -> applied(ProcessingTaskState.FAILED, errorCode = event.errorCode)
            }
            is ProcessingTaskEvent.Recover -> when (task.state) {
                ProcessingTaskState.PREPARING,
                ProcessingTaskState.RUNNING,
                -> applied(ProcessingTaskState.QUEUED)
                ProcessingTaskState.CANCELING -> applied(ProcessingTaskState.CANCELED)
                else -> ProcessingReduction.Ignored(task)
            }
            is ProcessingTaskEvent.Retry -> when (task.state) {
                ProcessingTaskState.FAILED -> applied(ProcessingTaskState.QUEUED, progress = null)
                else -> same(ProcessingTaskState.QUEUED)
            }
        }
    }

    fun replay(initial: ProcessingTask, events: Iterable<ProcessingTaskEvent>): ProcessingTask =
        events.fold(initial) { task, event ->
            when (val result = reduce(task, event)) {
                is ProcessingReduction.Applied -> result.task
                is ProcessingReduction.Ignored -> result.task
                is ProcessingReduction.Rejected -> result.task
            }
        }
}

enum class ProcessingTone {
    STEEL_BLUE,
    AMBER,
    GREEN,
    RED,
    NEUTRAL,
}

enum class ProcessingAction {
    PAUSE,
    RESUME,
    CANCEL,
    RETRY,
    OPEN_OUTPUT,
}

data class ProcessingPresentation(
    val tone: ProcessingTone,
    val determinate: Boolean,
    val actions: Set<ProcessingAction>,
)

object ProcessingPresentationMapper {
    fun map(task: ProcessingTask): ProcessingPresentation = when (task.state) {
        ProcessingTaskState.QUEUED,
        ProcessingTaskState.PREPARING,
        ProcessingTaskState.CANCELING,
        -> ProcessingPresentation(ProcessingTone.AMBER, false, setOf(ProcessingAction.CANCEL))
        ProcessingTaskState.RUNNING -> ProcessingPresentation(
            ProcessingTone.STEEL_BLUE,
            task.progress?.fraction != null,
            setOf(ProcessingAction.PAUSE, ProcessingAction.CANCEL),
        )
        ProcessingTaskState.PAUSED -> ProcessingPresentation(
            ProcessingTone.NEUTRAL,
            task.progress?.fraction != null,
            setOf(ProcessingAction.RESUME, ProcessingAction.CANCEL),
        )
        ProcessingTaskState.SUCCEEDED -> ProcessingPresentation(
            ProcessingTone.GREEN,
            true,
            setOf(ProcessingAction.OPEN_OUTPUT),
        )
        ProcessingTaskState.FAILED -> ProcessingPresentation(
            ProcessingTone.RED,
            false,
            setOf(ProcessingAction.RETRY),
        )
        ProcessingTaskState.CANCELED -> ProcessingPresentation(ProcessingTone.NEUTRAL, false, emptySet())
    }
}

data class SchedulerConditions(
    val batteryLow: Boolean = false,
    val storageAvailable: Boolean = true,
)

object ProcessingSchedulerPolicy {
    fun next(
        tasks: List<ProcessingTask>,
        runningCount: Int,
        maximumConcurrent: Int,
        conditions: SchedulerConditions,
    ): ProcessingTask? {
        require(runningCount >= 0)
        require(maximumConcurrent > 0)
        if (runningCount >= maximumConcurrent || conditions.batteryLow || !conditions.storageAvailable) return null
        return tasks.asSequence()
            .filter { it.state == ProcessingTaskState.QUEUED }
            .sortedWith(compareByDescending<ProcessingTask>(ProcessingTask::priority).thenBy(ProcessingTask::createdAtEpochMillis))
            .firstOrNull()
    }
}

interface ProcessingExecutor {
    suspend fun execute(task: ProcessingTask, onProgress: suspend (ProcessingProgress) -> Unit): ProcessingExecutionResult
    suspend fun cancel(taskId: ProcessingTaskId)
    suspend fun recover(task: ProcessingTask): ProcessingExecutionResult
}

sealed interface ProcessingExecutionResult {
    data class Success(val output: ProcessingOutput) : ProcessingExecutionResult
    data class Failure(val errorCode: String) : ProcessingExecutionResult
    data object Canceled : ProcessingExecutionResult
}

interface ProcessingRepository {
    val tasks: Flow<List<ProcessingTask>>
    suspend fun projects(): List<ProcessingProject>
    suspend fun create(project: ProcessingProject, tasks: List<ProcessingTask>)
    suspend fun apply(taskId: ProcessingTaskId, event: ProcessingTaskEvent): ProcessingReduction
    suspend fun recoverInterrupted()
    suspend fun clearTerminal()
}

interface ProcessingController {
    fun pause(taskId: ProcessingTaskId)
    fun resume(taskId: ProcessingTaskId)
    fun cancel(taskId: ProcessingTaskId)
    fun retry(taskId: ProcessingTaskId)
}

data class ProcessingArtifact(
    val id: String,
    val temporaryPath: String,
    val displayName: String,
)

data class ProcessingOutput(val displayName: String, val token: String) {
    init {
        require(displayName.isNotBlank())
        require(token.isNotBlank())
    }
}

interface ProcessingArtifactStore {
    suspend fun allocate(taskId: ProcessingTaskId, displayName: String): ProcessingArtifact
    suspend fun commit(artifact: ProcessingArtifact): ProcessingOutput
    suspend fun abort(artifact: ProcessingArtifact)
    suspend fun cleanupExpired(nowEpochMillis: Long): Int
}
