package seeyuer.yingli.player.app.processing

import seeyuer.yingli.player.domain.processing.ProcessingExecutionResult
import seeyuer.yingli.player.domain.processing.ProcessingExecutor
import seeyuer.yingli.player.domain.processing.ProcessingProgress
import seeyuer.yingli.player.domain.processing.ProcessingProjectType
import seeyuer.yingli.player.domain.processing.ProcessingRepository
import seeyuer.yingli.player.domain.processing.ProcessingTask
import seeyuer.yingli.player.domain.processing.ProcessingTaskId

class RoutingProcessingExecutor(
    private val repository: ProcessingRepository,
    private val clipExecutor: ProcessingExecutor,
    private val transcodeExecutor: ProcessingExecutor,
) : ProcessingExecutor {
    override suspend fun execute(
        task: ProcessingTask,
        onProgress: suspend (ProcessingProgress) -> Unit,
    ): ProcessingExecutionResult = executor(task)?.execute(task, onProgress)
        ?: ProcessingExecutionResult.Failure("PROCESSING_TYPE_UNSUPPORTED")

    override suspend fun cancel(taskId: ProcessingTaskId) {
        clipExecutor.cancel(taskId)
        transcodeExecutor.cancel(taskId)
    }

    override suspend fun recover(task: ProcessingTask): ProcessingExecutionResult = executor(task)?.recover(task)
        ?: ProcessingExecutionResult.Failure("PROCESSING_TYPE_UNSUPPORTED")

    private suspend fun executor(task: ProcessingTask): ProcessingExecutor? =
        when (repository.projects().firstOrNull { it.id == task.projectId }?.type) {
            ProcessingProjectType.CLIP -> clipExecutor
            ProcessingProjectType.COMPRESS, ProcessingProjectType.CONVERT -> transcodeExecutor
            else -> null
        }
}
