package seeyuer.yingli.player.data.processing.clips

import java.io.File
import kotlinx.coroutines.CancellationException
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.common.IdGenerator
import seeyuer.yingli.player.domain.clips.ClipExportMode
import seeyuer.yingli.player.domain.clips.ClipExportPlan
import seeyuer.yingli.player.domain.clips.ClipExportQueue
import seeyuer.yingli.player.domain.clips.ClipProject
import seeyuer.yingli.player.domain.clips.ClipProjectId
import seeyuer.yingli.player.domain.clips.ClipProjectRepository
import seeyuer.yingli.player.domain.clips.ClipSegmentId
import seeyuer.yingli.player.domain.playback.PlaybackRequest
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackSourceRepository
import seeyuer.yingli.player.domain.processing.DefaultProcessingPlanner
import seeyuer.yingli.player.domain.processing.MediaCapabilityProbe
import seeyuer.yingli.player.domain.processing.MediaRange
import seeyuer.yingli.player.domain.processing.OutputTarget
import seeyuer.yingli.player.domain.processing.OutputTargetId
import seeyuer.yingli.player.domain.processing.OutputVerifier
import seeyuer.yingli.player.domain.processing.ProcessingArtifactStore
import seeyuer.yingli.player.domain.processing.ProcessingEngine
import seeyuer.yingli.player.domain.processing.ProcessingEngineResult
import seeyuer.yingli.player.domain.processing.ProcessingExecutionResult
import seeyuer.yingli.player.domain.processing.ProcessingExecutor
import seeyuer.yingli.player.domain.processing.ProcessingPlanningResult
import seeyuer.yingli.player.domain.processing.ProcessingProgress
import seeyuer.yingli.player.domain.processing.ProcessingProject
import seeyuer.yingli.player.domain.processing.ProcessingProjectId
import seeyuer.yingli.player.domain.processing.ProcessingProjectType
import seeyuer.yingli.player.domain.processing.ProcessingRepository
import seeyuer.yingli.player.domain.processing.ProcessingTask
import seeyuer.yingli.player.domain.processing.ProcessingTaskId

class ClipExportCoordinator(
    private val repository: ProcessingRepository,
    private val idGenerator: IdGenerator,
    private val clock: AppClock,
) : ClipExportQueue {
    override suspend fun enqueue(project: ClipProject): ProcessingProjectId {
        val plan = ClipExportPlan.from(project)
        val now = clock.now().toEpochMilli()
        val processingProjectId = ProcessingProjectId(idGenerator.newId())
        val processingProject = ProcessingProject(
            processingProjectId,
            ProcessingProjectType.CLIP,
            listOf(project.sourceMediaId),
            "$CLIP_POLICY_PREFIX${project.id.value}",
            now,
        )
        val tasks = plan.items.map { item ->
            ProcessingTask(
                id = ProcessingTaskId(idGenerator.newId()),
                projectId = processingProjectId,
                operationKey = "${item.segment.id.value}$OPERATION_SEPARATOR${item.displayName}",
                createdAtEpochMillis = now,
            )
        }
        repository.create(processingProject, tasks)
        return processingProjectId
    }

    companion object {
        const val CLIP_POLICY_PREFIX = "CLIP_EXPORT|"
        const val OPERATION_SEPARATOR = "|"
    }
}

class ClipProcessingExecutor(
    private val processingRepository: ProcessingRepository,
    private val clipRepository: ClipProjectRepository,
    private val sourceRepository: PlaybackSourceRepository,
    private val probe: MediaCapabilityProbe,
    private val engine: ProcessingEngine,
    private val verifier: OutputVerifier,
    private val artifacts: ProcessingArtifactStore,
    private val availableBytes: () -> Long,
) : ProcessingExecutor {
    override suspend fun execute(
        task: ProcessingTask,
        onProgress: suspend (ProcessingProgress) -> Unit,
    ): ProcessingExecutionResult {
        val processingProject = processingRepository.projects().firstOrNull { it.id == task.projectId }
            ?: return ProcessingExecutionResult.Failure("PROJECT_NOT_FOUND")
        val clipProjectId = processingProject.outputPolicy
            .takeIf { it.startsWith(ClipExportCoordinator.CLIP_POLICY_PREFIX) }
            ?.removePrefix(ClipExportCoordinator.CLIP_POLICY_PREFIX)
            ?.let(::ClipProjectId)
            ?: return ProcessingExecutionResult.Failure("INVALID_CLIP_POLICY")
        val clipProject = clipRepository.project(clipProjectId)
            ?: return ProcessingExecutionResult.Failure("CLIP_PROJECT_NOT_FOUND")
        val operation = task.operationKey.split(ClipExportCoordinator.OPERATION_SEPARATOR, limit = 2)
        val segmentId = operation.getOrNull(0)?.let(::ClipSegmentId)
            ?: return ProcessingExecutionResult.Failure("INVALID_CLIP_OPERATION")
        val displayName = operation.getOrNull(1)?.takeIf(String::isNotBlank)
            ?: return ProcessingExecutionResult.Failure("INVALID_CLIP_OPERATION")
        val segment = clipProject.segments.firstOrNull { it.id == segmentId }
            ?: return ProcessingExecutionResult.Failure("CLIP_SEGMENT_NOT_FOUND")
        val resolved = sourceRepository.resolve(PlaybackRequest(
            clipProject.sourceMediaId,
            clipProject.sourceLocationId,
            0,
            PlaybackSourceContext.DETAIL,
        )) ?: return ProcessingExecutionResult.Failure("SOURCE_UNAVAILABLE")

        onProgress(ProcessingProgress("probe", 0))
        val source = probe.source(clipProject.sourceMediaId, resolved.uri, displayName)
            ?: return ProcessingExecutionResult.Failure("PROBE_FAILED")

        // 切片不是第四种任务：它就是「目标容器 MP4、codec 同源、带时间区间」的一个计划。
        // 快速与精确的差别只是 `frameAccurateCut`（用户显式二选一，没有默认），
        // 由 planner 决定它是 REMUX 还是 TRANSCODE，调用方不选引擎（设计稿 §4.3、§6.2）。
        val target = OutputTarget(
            id = OutputTargetId(
                if (clipProject.exportMode == ClipExportMode.FAST) FAST_TARGET_ID else ACCURATE_TARGET_ID,
            ),
            containerMimeType = MP4_CONTAINER_MIME_TYPE,
            videoCodecMimeType = null,
            audioCodecMimeType = null,
            frameAccurateCut = clipProject.exportMode == ClipExportMode.ACCURATE,
        )
        val plan = when (
            val planning = DefaultProcessingPlanner.plan(
                source = source,
                capabilities = probe.deviceCapabilities(),
                target = target,
                availableBytes = availableBytes(),
                range = MediaRange(segment.startMillis, segment.endMillis),
            )
        ) {
            is ProcessingPlanningResult.Rejected -> return ProcessingExecutionResult.Failure(planning.code)
            is ProcessingPlanningResult.Ready -> planning.plan
        }

        val artifact = artifacts.allocate(task.id, displayName)
        return try {
            onProgress(ProcessingProgress("cut", 0, segment.durationMillis))
            val result = engine.process(plan, artifact.temporaryPath) { fraction ->
                onProgress(
                    ProcessingProgress("cut", (fraction * segment.durationMillis).toLong(), segment.durationMillis),
                )
            }
            when (result) {
                is ProcessingEngineResult.Completed -> {
                    // 引擎在运行期报告的回退（例如请求的 codec 被库静默替换）与计划里的后果一样，
                    // 都必须先确认再提交。见 `ProcessingChangeCode.requiresConfirmation()`。
                    if (result.requiresConfirmation) {
                        artifacts.abort(artifact)
                        return ProcessingExecutionResult.Failure("DEGRADATION_CONFIRMATION_REQUIRED")
                    }
                    val verification = verifier.verify(artifact.temporaryPath, plan)
                    if (!verification.valid) {
                        artifacts.abort(artifact)
                        return ProcessingExecutionResult.Failure(
                            verification.errorCodes.firstOrNull() ?: "OUTPUT_INVALID",
                        )
                    }
                    onProgress(ProcessingProgress("verify", segment.durationMillis, segment.durationMillis))
                    ProcessingExecutionResult.Success(artifacts.commit(artifact))
                }
                is ProcessingEngineResult.Failed -> {
                    artifacts.abort(artifact)
                    ProcessingExecutionResult.Failure(result.errorCode)
                }
                ProcessingEngineResult.Canceled -> {
                    artifacts.abort(artifact)
                    ProcessingExecutionResult.Canceled
                }
            }
        } catch (cancelled: CancellationException) {
            artifacts.abort(artifact)
            throw cancelled
        } catch (_: Exception) {
            artifacts.abort(artifact)
            ProcessingExecutionResult.Failure("CLIP_EXPORT_FAILED")
        }
    }

    override suspend fun cancel(taskId: ProcessingTaskId) = engine.cancel()

    override suspend fun recover(task: ProcessingTask): ProcessingExecutionResult =
        execute(task) { }

    private companion object {
        const val MP4_CONTAINER_MIME_TYPE = "video/mp4"
        const val FAST_TARGET_ID = "clip_fast"
        const val ACCURATE_TARGET_ID = "clip_accurate"
    }
}
