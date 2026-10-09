package seeyuer.yingli.player.data.recycle

import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.common.IdGenerator
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.library.LibraryPagingRepository
import seeyuer.yingli.player.domain.processing.ProcessingExecutionResult
import seeyuer.yingli.player.domain.processing.ProcessingExecutor
import seeyuer.yingli.player.domain.processing.ProcessingProgress
import seeyuer.yingli.player.domain.processing.ProcessingProject
import seeyuer.yingli.player.domain.processing.ProcessingProjectId
import seeyuer.yingli.player.domain.processing.ProcessingProjectType
import seeyuer.yingli.player.domain.processing.ProcessingRepository
import seeyuer.yingli.player.domain.processing.ProcessingTask
import seeyuer.yingli.player.domain.processing.ProcessingTaskId
import seeyuer.yingli.player.domain.recycle.RecycleAction
import seeyuer.yingli.player.domain.recycle.RecycleQueue
import seeyuer.yingli.player.domain.recycle.RecycleTarget
import seeyuer.yingli.player.domain.recycle.TrashOperationOutcome
import seeyuer.yingli.player.domain.recycle.TrashService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 回收站操作的策略载体（D4-b：**动作装在 `outputPolicy` 里**，不为四种动作新增四个 `ProcessingProjectType`）。
 *
 * 形如 `RECYCLE|MOVE|<locationId>:<mediaItemId>,<locationId>:<mediaItemId>`，沿用
 * `TranscodeCoordinator` / `ClipExportCoordinator` / `DeduplicatePolicy` 的「前缀 + 分隔符」范式。
 *
 * 位置与条目 id 一起编码是必需的：`processing_project_inputs` 只认 `mediaItemId`，
 * 而回收站的一切都以 `locationId` 为主键（§8.2）。
 */
object RecyclePolicy {
    const val PREFIX = "RECYCLE"
    const val SEPARATOR = "|"
    const val ENTRY_SEPARATOR = ","
    const val PAIR_SEPARATOR = ":"

    fun encode(action: RecycleAction, targets: List<RecycleTarget>): String {
        val body = targets.joinToString(ENTRY_SEPARATOR) { target ->
            target.locationId.value + PAIR_SEPARATOR + target.mediaItemId.value
        }
        return listOf(PREFIX, action.name, body).joinToString(SEPARATOR)
    }

    /** 解析失败一律返回 `null`：回收站不接受「猜出来的」策略，宁可让任务失败。 */
    fun decode(policy: String): Decoded? {
        val parts = policy.split(SEPARATOR)
        if (parts.size != 3 || parts[0] != PREFIX) return null
        val action = runCatching { RecycleAction.valueOf(parts[1]) }.getOrNull() ?: return null
        val targets = if (parts[2].isEmpty()) {
            emptyList()
        } else {
            parts[2].split(ENTRY_SEPARATOR).map { entry ->
                val pair = entry.split(PAIR_SEPARATOR)
                if (pair.size != 2 || pair[0].isEmpty() || pair[1].isEmpty()) return null
                RecycleTarget(MediaLocationId(pair[0]), MediaItemId(pair[1]))
            }
        }
        // MOVE/RESTORE/PURGE 必须带目标；CLEANUP 的语义是「全库到期项」，不得带目标（带了就是误用）。
        if (action == RecycleAction.CLEANUP && targets.isNotEmpty()) return null
        if (action != RecycleAction.CLEANUP && targets.isEmpty()) return null
        return Decoded(action, targets)
    }

    data class Decoded(val action: RecycleAction, val targets: List<RecycleTarget>)
}

/** 把批量回收站操作放进任务中心（§11.3）。 */
class RecycleCoordinator(
    private val repository: ProcessingRepository,
    private val idGenerator: IdGenerator,
    private val clock: AppClock,
) : RecycleQueue {
    override suspend fun enqueue(action: RecycleAction, targets: List<RecycleTarget>): ProcessingProjectId {
        require(action == RecycleAction.CLEANUP || targets.isNotEmpty()) {
            "$action requires at least one target"
        }
        val now = clock.now().toEpochMilli()
        val projectId = ProcessingProjectId(idGenerator.newId())
        val project = ProcessingProject(
            id = projectId,
            type = ProcessingProjectType.RECYCLE,
            // 位置才是回收站的主键，而 `processing_project_inputs` 只接受 `mediaItemId`。
            // 因此这里只登记条目（保持输入表有迹可循），**真正的范围在 outputPolicy 里**。
            inputMediaIds = targets.map(RecycleTarget::mediaItemId).distinct(),
            outputPolicy = RecyclePolicy.encode(action, targets),
            createdAtEpochMillis = now,
        )
        val task = ProcessingTask(
            ProcessingTaskId(idGenerator.newId()),
            projectId,
            operationKey = "$RECYCLE_OPERATION_KEY_PREFIX${action.name.lowercase()}",
            createdAtEpochMillis = now,
        )
        repository.create(project, listOf(task))
        return projectId
    }

    companion object {
        const val RECYCLE_OPERATION_KEY_PREFIX = "recycle-"
    }
}

/**
 * 回收站任务的执行器。
 *
 * **它只做分派与聚合**：状态机、事务边界、文件操作全在 [TrashService]。
 * 这样「批量移入」与「从整理页单个移入」走的是同一段代码，不会出现两条语义不同的路径。
 *
 * **成功没有产物**：回收站操作不产出新文件，`Success(output = null)`。
 * **取消是协作式的**：`TrashService` 的每一步内部都无法中断（复制循环由存储层自己检查），
 * 因此这里只在**条目之间**检查取消标志——这让「按住 100 个条目的批量任务」能停下来，
 * 且停下来时不会留下半成品（每个条目自身要么完成要么回滚）。
 */
class RecycleProcessingExecutor(
    private val processingRepository: ProcessingRepository,
    private val trashService: TrashService,
    private val libraryRepository: LibraryPagingRepository,
    private val clock: AppClock,
) : ProcessingExecutor {

    private val cancelled = AtomicBoolean(false)

    override suspend fun execute(
        task: ProcessingTask,
        onProgress: suspend (ProcessingProgress) -> Unit,
    ): ProcessingExecutionResult {
        val project = processingRepository.projects().firstOrNull { it.id == task.projectId }
            ?: return ProcessingExecutionResult.Failure(PROJECT_NOT_FOUND)
        val policy = RecyclePolicy.decode(project.outputPolicy)
            ?: return ProcessingExecutionResult.Failure(INVALID_POLICY)

        if (policy.action == RecycleAction.CLEANUP) {
            val report = trashService.cleanupExpired(clock.now().toEpochMilli())
            onProgress(ProcessingProgress(stage = "cleanup", processedUnits = 1, totalUnits = 1))
            return if (report.errors > 0) {
                ProcessingExecutionResult.Failure(CLEANUP_FAILED)
            } else {
                ProcessingExecutionResult.Success(output = null)
            }
        }

        // MOVE 需要完整的 `LibraryMedia`（存储层要 uri/size/时长等快照字段），
        // RESTORE/PURGE 只需要 locationId。**只查一次**，按 locationId 建索引。
        val mediaByLocation = if (policy.action == RecycleAction.MOVE) {
            libraryRepository.findByIds(policy.targets.map(RecycleTarget::mediaItemId).toSet())
                .associateBy { it.locationId }
        } else {
            emptyMap()
        }

        val failures = mutableListOf<String>()
        policy.targets.forEachIndexed { index, target ->
            if (cancelled.get()) return ProcessingExecutionResult.Canceled
            val outcome = when (policy.action) {
                RecycleAction.MOVE -> {
                    val item = mediaByLocation[target.locationId]
                    if (item == null) {
                        failures += "$SOURCE_NOT_FOUND:${target.locationId.value}"
                        null
                    } else {
                        trashService.move(item)
                    }
                }
                RecycleAction.RESTORE -> trashService.restore(target.locationId)
                RecycleAction.PURGE -> trashService.purge(target.locationId)
                RecycleAction.CLEANUP -> null
            }
            when (outcome) {
                null,
                is TrashOperationOutcome.Completed,
                is TrashOperationOutcome.Purged,
                // 目标是「记录消失」，放弃失败记录同样达成了这一点，算这一项成功。
                is TrashOperationOutcome.Discarded,
                -> Unit
                is TrashOperationOutcome.AuthorizationRequired -> {
                    // 授权对话框必须由前台发起。任务在这里停住并把 token 交回，
                    // UI 授权后再调 `TrashService.resolveAuthorization` 接着推进——**不伪造成功**。
                    return ProcessingExecutionResult.Failure(AUTHORIZATION_REQUIRED)
                }
                is TrashOperationOutcome.Blocked ->
                    failures += "${outcome.code}:${target.locationId.value}"
                is TrashOperationOutcome.Failed ->
                    failures += "${outcome.code}:${target.locationId.value}"
            }
            onProgress(
                ProcessingProgress(
                    stage = policy.action.name.lowercase(),
                    processedUnits = (index + 1).toLong(),
                    totalUnits = policy.targets.size.toLong(),
                ),
            )
        }

        // 只有**全部**目标都失败时任务才失败。部分成功必须如实报告为成功：
        // 用户若看到「整批失败」，会以为什么都没发生，而实际上有些文件已经进了回收站。
        return if (failures.size == policy.targets.size) {
            ProcessingExecutionResult.Failure(failures.first().substringBefore(':'))
        } else {
            ProcessingExecutionResult.Success(output = null)
        }
    }

    override suspend fun cancel(taskId: ProcessingTaskId) {
        cancelled.set(true)
    }

    /**
     * 进程被杀后重启：`--` 不「续跑」。
     *
     * §11.5 的裁决是「重做」不是「续跑」，但回收站的重做有一个额外前提：
     * **状态机自己已经记录了每一步**（`STAGING` / `WAITING_SOURCE_DELETE_AUTH` / `RESTORING` / …），
     * 因此启动时的 `TrashService.reconcile()` 才是真正的恢复入口，这里**绝不重复移入**。
     */
    override suspend fun recover(task: ProcessingTask): ProcessingExecutionResult =
        ProcessingExecutionResult.Failure(NOT_RECOVERABLE)

    private companion object {
        const val PROJECT_NOT_FOUND = "PROJECT_NOT_FOUND"
        const val INVALID_POLICY = "INVALID_RECYCLE_POLICY"
        const val SOURCE_NOT_FOUND = "SOURCE_NOT_FOUND"
        const val AUTHORIZATION_REQUIRED = "AUTHORIZATION_REQUIRED"
        const val CLEANUP_FAILED = "CLEANUP_FAILED"
        const val NOT_RECOVERABLE = "NOT_RECOVERABLE"
    }
}
