package seeyuer.yingli.player.data.processing

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.common.AppLogEvent
import seeyuer.yingli.player.core.common.AppLogLevel
import seeyuer.yingli.player.core.common.AppLogger
import seeyuer.yingli.player.core.common.LogValue
import seeyuer.yingli.player.domain.processing.ProcessingExecutionResult
import seeyuer.yingli.player.domain.processing.ProcessingExecutor
import seeyuer.yingli.player.domain.processing.ProcessingController
import seeyuer.yingli.player.domain.processing.ProcessingRepository
import seeyuer.yingli.player.domain.processing.ProcessingSchedulerPolicy
import seeyuer.yingli.player.domain.processing.ProcessingTask
import seeyuer.yingli.player.domain.processing.ProcessingTaskEvent
import seeyuer.yingli.player.domain.processing.ProcessingTaskId
import seeyuer.yingli.player.domain.processing.SchedulerConditions

class InAppProcessingScheduler(
    private val scope: CoroutineScope,
    private val repository: ProcessingRepository,
    private val executor: ProcessingExecutor,
    private val clock: AppClock,
    private val logger: AppLogger,
    private val conditions: () -> SchedulerConditions,
    private val onActiveChanged: (Boolean) -> Unit = {},
    private val maximumConcurrent: Int = 1,
) : ProcessingController, AutoCloseable {
    private val active = ConcurrentHashMap<ProcessingTaskId, Job>()
    private var observer: Job? = null

    init {
        require(maximumConcurrent > 0)
    }

    fun start() {
        if (observer != null) return
        observer = scope.launch {
            repository.recoverInterrupted()
            repository.tasks.collectLatest(::schedule)
        }
    }

    override fun pause(taskId: ProcessingTaskId) {
        active.remove(taskId)?.cancel()
        scope.launch { repository.apply(taskId, ProcessingTaskEvent.Pause(now())) }
    }

    override fun resume(taskId: ProcessingTaskId) {
        scope.launch { repository.apply(taskId, ProcessingTaskEvent.Resume(now())) }
    }

    override fun cancel(taskId: ProcessingTaskId) {
        scope.launch {
            repository.apply(taskId, ProcessingTaskEvent.RequestCancel(now()))
            active.remove(taskId)?.cancel()
            executor.cancel(taskId)
            repository.apply(taskId, ProcessingTaskEvent.FinishCancel(now()))
        }
    }

    override fun retry(taskId: ProcessingTaskId) {
        scope.launch { repository.apply(taskId, ProcessingTaskEvent.Retry(now())) }
    }

    private fun schedule(tasks: List<ProcessingTask>) {
        while (active.size < maximumConcurrent) {
            val available = tasks.filterNot { active.containsKey(it.id) }
            val next = ProcessingSchedulerPolicy.next(available, active.size, maximumConcurrent, conditions()) ?: return
            if (active.containsKey(next.id)) return
            val job = scope.launch { execute(next) }
            active[next.id] = job
            onActiveChanged(true)
            job.invokeOnCompletion {
                active.remove(next.id)
                if (active.isEmpty()) onActiveChanged(false)
            }
        }
    }

    private suspend fun execute(task: ProcessingTask) {
        repository.apply(task.id, ProcessingTaskEvent.Prepare(now()))
        repository.apply(task.id, ProcessingTaskEvent.Start(now()))
        val result = try {
            executor.execute(task) { progress ->
                repository.apply(task.id, ProcessingTaskEvent.Progressed(progress, now()))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ProcessingExecutionResult.Failure("EXECUTOR_FAILURE")
        }
        when (result) {
            is ProcessingExecutionResult.Success -> {
                repository.apply(task.id, ProcessingTaskEvent.Succeed(result.output, now()))
                logger.log(
                    AppLogLevel.INFO,
                    AppLogEvent(
                        "PROCESSING_TASK_SUCCEEDED",
                        "A processing task completed.",
                        mapOf("taskId" to LogValue.Public(task.id.value)),
                    ),
                )
            }
            is ProcessingExecutionResult.Failure -> {
                repository.apply(task.id, ProcessingTaskEvent.Fail(result.errorCode, now()))
                logger.log(
                    AppLogLevel.ERROR,
                    AppLogEvent(
                        "PROCESSING_TASK_FAILED",
                        "A processing task failed.",
                        mapOf(
                            "taskId" to LogValue.Public(task.id.value),
                            "errorCode" to LogValue.Public(result.errorCode),
                        ),
                    ),
                )
            }
            ProcessingExecutionResult.Canceled -> {
                repository.apply(task.id, ProcessingTaskEvent.RequestCancel(now()))
                repository.apply(task.id, ProcessingTaskEvent.FinishCancel(now()))
            }
        }
    }

    private fun now(): Long = clock.now().toEpochMilli()

    override fun close() {
        observer?.cancel()
        observer = null
        active.values.forEach(Job::cancel)
        active.clear()
    }
}
