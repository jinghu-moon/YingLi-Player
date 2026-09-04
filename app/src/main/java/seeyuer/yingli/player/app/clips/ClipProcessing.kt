package seeyuer.yingli.player.app.clips

import java.io.File
import kotlinx.coroutines.CancellationException
import seeyuer.yingli.player.core.foundation.AppClock
import seeyuer.yingli.player.core.foundation.IdGenerator
import seeyuer.yingli.player.domain.clips.ClipEngine
import seeyuer.yingli.player.domain.clips.ClipEngineResult
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
import seeyuer.yingli.player.domain.processing.ProcessingArtifactStore
import seeyuer.yingli.player.domain.processing.ProcessingExecutionResult
import seeyuer.yingli.player.domain.processing.ProcessingExecutor
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
    private val engine: ClipEngine,
    private val artifacts: ProcessingArtifactStore,
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
        val probe = engine.probe(resolved.uri) ?: return ProcessingExecutionResult.Failure("PROBE_FAILED")
        val (source, capabilities) = probe
        if (clipProject.exportMode == ClipExportMode.FAST && !capabilities.fastCut) {
            return ProcessingExecutionResult.Failure(capabilities.diagnosticCode ?: "FAST_CUT_UNSUPPORTED")
        }
        if (clipProject.exportMode == ClipExportMode.ACCURATE && !capabilities.accurateCut) {
            return ProcessingExecutionResult.Failure(capabilities.diagnosticCode ?: "ACCURATE_CUT_UNSUPPORTED")
        }
        val artifact = artifacts.allocate(task.id, displayName)
        return try {
            onProgress(ProcessingProgress("cut", 0, segment.durationMillis))
            val result = when (clipProject.exportMode) {
                ClipExportMode.FAST -> engine.fastCut(source, segment, artifact.temporaryPath)
                ClipExportMode.ACCURATE -> engine.accurateCut(source, segment, artifact.temporaryPath)
            }
            when (result) {
                is ClipEngineResult.Success -> {
                    val verified = engine.probe(File(artifact.temporaryPath).toURI().toString())
                    if (verified == null) {
                        artifacts.abort(artifact)
                        ProcessingExecutionResult.Failure("OUTPUT_VERIFICATION_FAILED")
                    } else {
                        onProgress(ProcessingProgress("verify", segment.durationMillis, segment.durationMillis))
                        ProcessingExecutionResult.Success(artifacts.commit(artifact))
                    }
                }
                is ClipEngineResult.Unsupported -> {
                    artifacts.abort(artifact)
                    ProcessingExecutionResult.Failure(result.diagnosticCode)
                }
                is ClipEngineResult.Failed -> {
                    artifacts.abort(artifact)
                    ProcessingExecutionResult.Failure(result.diagnosticCode)
                }
                ClipEngineResult.Canceled -> {
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

    override suspend fun cancel(taskId: ProcessingTaskId) = Unit

    override suspend fun recover(task: ProcessingTask): ProcessingExecutionResult =
        execute(task) { }
}
