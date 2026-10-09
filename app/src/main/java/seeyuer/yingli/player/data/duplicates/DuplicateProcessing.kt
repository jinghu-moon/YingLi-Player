package seeyuer.yingli.player.data.duplicates

import kotlinx.coroutines.flow.first
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.common.IdGenerator
import seeyuer.yingli.player.domain.duplicates.DUPLICATE_HASH_ALGORITHM_VERSION
import seeyuer.yingli.player.domain.duplicates.DUPLICATE_SCAN_OPERATION_KEY_PREFIX
import seeyuer.yingli.player.domain.duplicates.DuplicateMode
import seeyuer.yingli.player.domain.duplicates.DuplicateScanQueue
import seeyuer.yingli.player.domain.duplicates.DuplicateScanResult
import seeyuer.yingli.player.domain.duplicates.DuplicateScanner
import seeyuer.yingli.player.domain.processing.ProcessingExecutionResult
import seeyuer.yingli.player.domain.processing.ProcessingExecutor
import seeyuer.yingli.player.domain.processing.ProcessingProgress
import seeyuer.yingli.player.domain.processing.ProcessingProject
import seeyuer.yingli.player.domain.processing.ProcessingProjectId
import seeyuer.yingli.player.domain.processing.ProcessingProjectType
import seeyuer.yingli.player.domain.processing.ProcessingRepository
import seeyuer.yingli.player.domain.processing.ProcessingTask
import seeyuer.yingli.player.domain.processing.ProcessingTaskId

/**
 * 去重扫描的策略载体（设计稿 §11.1）。
 *
 * `outputPolicy` 复用 `TranscodeCoordinator` / `ClipExportCoordinator` 已有的
 * 「分隔符 + 前缀」范式：`SCAN|EXACT|v1`。
 */
object DeduplicatePolicy {
    const val PREFIX = "SCAN"
    const val SEPARATOR = "|"

    fun encode(mode: DuplicateMode, algorithmVersion: Int = DUPLICATE_HASH_ALGORITHM_VERSION): String =
        listOf(PREFIX, mode.name, "v$algorithmVersion").joinToString(SEPARATOR)

    /** 解析失败一律返回 `null`——去重扫描不接受「猜出来的」策略。 */
    fun decode(policy: String): Decoded? {
        val parts = policy.split(SEPARATOR)
        if (parts.size != 3 || parts[0] != PREFIX) return null
        val mode = runCatching { DuplicateMode.valueOf(parts[1]) }.getOrNull() ?: return null
        val version = parts[2].removePrefix("v").toIntOrNull() ?: return null
        return Decoded(mode, version)
    }

    data class Decoded(val mode: DuplicateMode, val algorithmVersion: Int)
}

/**
 * 把去重扫描放进任务中心（修 G21）。
 *
 * 现状曾是 `OrganizeViewModel.scanDuplicates()` 在 `viewModelScope` 里直接跑，
 * 进度只有一个布尔量：离开整理页就丢进度，没有取消持久化、没有重试、没有跨重启恢复。
 * 走任务中心之后这些全部由既有调度器负责，去重算法也不再被 UI 直接调用
 * （`docs/06:588` 的硬要求）。
 */
class DeduplicateCoordinator(
    private val repository: ProcessingRepository,
    private val idGenerator: IdGenerator,
    private val clock: AppClock,
) : DuplicateScanQueue {
    override suspend fun enqueue(mode: DuplicateMode): ProcessingProjectId {
        val now = clock.now().toEpochMilli()
        val projectId = ProcessingProjectId(idGenerator.newId())
        val project = ProcessingProject(
            id = projectId,
            type = ProcessingProjectType.DEDUPLICATE,
            // 空集 = 全库。扫描作用域在拿到任务后才确定（L0 的 size 分桶就是全局查询）。
            inputMediaIds = emptyList(),
            outputPolicy = DeduplicatePolicy.encode(mode),
            createdAtEpochMillis = now,
        )
        val task = ProcessingTask(
            ProcessingTaskId(idGenerator.newId()),
            projectId,
            operationKey = operationKey(mode),
            createdAtEpochMillis = now,
        )
        repository.create(project, listOf(task))
        return projectId
    }

    private fun operationKey(mode: DuplicateMode): String =
        DUPLICATE_SCAN_OPERATION_KEY_PREFIX + mode.name.lowercase()
}

/**
 * 去重扫描的处理执行器（设计稿 §11.1）。
 *
 * **成功没有产物**：L0–L4 的结果直接写进 `media_locations` 的哈希列，
 * 磁盘上没有新文件，所以 [ProcessingExecutionResult.Success] 的 `output` 是 `null`
 * （`ProcessingTaskEvent.Succeed` 与 reducer 已同步放宽为可空）。
 *
 * **单实例、单扫描**：`DuplicateScanner.cancel()` 取消的是「当前正在跑的扫描」，
 * 因此同一时刻只应有一个去重任务。互斥由调度侧保证（§11.4 的互斥矩阵里
 * `DEDUPLICATE` 与自身冲突），这里不做第二份并发控制。
 */
class DeduplicateProcessingExecutor(
    private val processingRepository: ProcessingRepository,
    private val scanner: DuplicateScanner,
) : ProcessingExecutor {
    override suspend fun execute(
        task: ProcessingTask,
        onProgress: suspend (ProcessingProgress) -> Unit,
    ): ProcessingExecutionResult {
        val project = processingRepository.projects().firstOrNull { it.id == task.projectId }
            ?: return ProcessingExecutionResult.Failure(PROJECT_NOT_FOUND)
        val policy = DeduplicatePolicy.decode(project.outputPolicy)
            ?: return ProcessingExecutionResult.Failure(INVALID_POLICY)
        if (policy.algorithmVersion != DUPLICATE_HASH_ALGORITHM_VERSION) {
            // 算法版本是缓存三元组的一部分：按旧版本去扫会把新版本的哈希当成「已算过」。
            return ProcessingExecutionResult.Failure(HASH_VERSION_UNSUPPORTED)
        }
        val result = scanner.scan(policy.mode) { progress ->
            onProgress(
                ProcessingProgress(
                    stage = progress.stage,
                    processedUnits = progress.processedUnits.toLong(),
                    totalUnits = progress.totalUnits?.toLong(),
                ),
            )
        }
        return when (result) {
            is DuplicateScanResult.Completed -> ProcessingExecutionResult.Success(output = null)
            is DuplicateScanResult.Rejected -> ProcessingExecutionResult.Failure(result.code)
            DuplicateScanResult.Canceled -> ProcessingExecutionResult.Canceled
        }
    }

    override suspend fun cancel(taskId: ProcessingTaskId) = scanner.cancel()

    override suspend fun recover(task: ProcessingTask): ProcessingExecutionResult =
        ProcessingExecutionResult.Failure(NOT_RECOVERABLE)

    private companion object {
        const val PROJECT_NOT_FOUND = "PROJECT_NOT_FOUND"
        const val INVALID_POLICY = "INVALID_DUPLICATE_POLICY"
        const val HASH_VERSION_UNSUPPORTED = "HASH_VERSION_UNSUPPORTED"
        const val NOT_RECOVERABLE = "NOT_RECOVERABLE"
    }
}
