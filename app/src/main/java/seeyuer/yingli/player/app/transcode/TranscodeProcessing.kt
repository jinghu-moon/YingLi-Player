package seeyuer.yingli.player.app.transcode

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import seeyuer.yingli.player.core.foundation.AppClock
import seeyuer.yingli.player.core.foundation.IdGenerator
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
import seeyuer.yingli.player.domain.transcode.DefaultTranscodePlanner
import seeyuer.yingli.player.domain.transcode.MediaCapabilityProbe
import seeyuer.yingli.player.domain.transcode.OutputVerifier
import seeyuer.yingli.player.domain.transcode.TranscodeEngine
import seeyuer.yingli.player.domain.transcode.TranscodeEngineResult
import seeyuer.yingli.player.domain.transcode.TranscodePlan
import seeyuer.yingli.player.domain.transcode.TranscodePlanningResult
import seeyuer.yingli.player.domain.transcode.TranscodePresets
import seeyuer.yingli.player.domain.transcode.TranscodeQueue

class TranscodeCoordinator(
    private val repository: ProcessingRepository,
    private val idGenerator: IdGenerator,
    private val clock: AppClock,
) : TranscodeQueue {
    private val mutablePlans = MutableStateFlow<Map<ProcessingProjectId, TranscodePlan>>(emptyMap())
    override val pendingPlans = mutablePlans.asStateFlow()

    override suspend fun enqueue(
        plan: TranscodePlan,
        destructiveChangesConfirmed: Boolean,
    ): ProcessingProjectId {
        require(!plan.requiresConfirmation || destructiveChangesConfirmed)
        val now = clock.now().toEpochMilli()
        val projectId = ProcessingProjectId(idGenerator.newId())
        val project = ProcessingProject(
            id = projectId,
            type = if (plan.changes.isEmpty()) ProcessingProjectType.COMPRESS else ProcessingProjectType.CONVERT,
            inputMediaIds = listOf(plan.source.mediaId),
            outputPolicy = listOf(
                POLICY_PREFIX,
                plan.preset.id.value,
                encode(plan.source.uri),
                if (destructiveChangesConfirmed) "1" else "0",
            ).joinToString(SEPARATOR),
            createdAtEpochMillis = now,
        )
        val task = ProcessingTask(
            ProcessingTaskId(idGenerator.newId()),
            projectId,
            operationKey = plan.outputDisplayName,
            createdAtEpochMillis = now,
        )
        repository.create(project, listOf(task))
        mutablePlans.value += projectId to plan
        return projectId
    }

    override suspend fun plan(projectId: ProcessingProjectId): TranscodePlan? = mutablePlans.value[projectId]

    companion object {
        const val POLICY_PREFIX = "TRANSCODE"
        const val SEPARATOR = "|"

        fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

        fun decode(value: String): String? = runCatching {
            String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
        }.getOrNull()
    }
}

class TranscodeProcessingExecutor(
    private val processingRepository: ProcessingRepository,
    private val probe: MediaCapabilityProbe,
    private val engine: TranscodeEngine,
    private val verifier: OutputVerifier,
    private val artifacts: ProcessingArtifactStore,
    private val availableBytes: () -> Long,
) : ProcessingExecutor {
    override suspend fun execute(
        task: ProcessingTask,
        onProgress: suspend (ProcessingProgress) -> Unit,
    ): ProcessingExecutionResult {
        val project = processingRepository.projects().firstOrNull { it.id == task.projectId }
            ?: return ProcessingExecutionResult.Failure("PROJECT_NOT_FOUND")
        val policy = project.outputPolicy.split(TranscodeCoordinator.SEPARATOR, limit = 4)
        if (policy.size != 4 || policy[0] != TranscodeCoordinator.POLICY_PREFIX) {
            return ProcessingExecutionResult.Failure("INVALID_TRANSCODE_POLICY")
        }
        val preset = TranscodePresets.all.firstOrNull { it.id.value == policy[1] }
            ?: return ProcessingExecutionResult.Failure("PRESET_NOT_FOUND")
        val sourceUri = TranscodeCoordinator.decode(policy[2])
            ?: return ProcessingExecutionResult.Failure("INVALID_TRANSCODE_SOURCE")
        val confirmed = policy[3] == "1"
        val mediaId = project.inputMediaIds.singleOrNull()
            ?: return ProcessingExecutionResult.Failure("INVALID_TRANSCODE_INPUT")
        onProgress(ProcessingProgress("probe", 0))
        val source = probe.source(mediaId, sourceUri, task.operationKey)
            ?: return ProcessingExecutionResult.Failure("PROBE_FAILED")
        val planned = DefaultTranscodePlanner.plan(source, probe.deviceCapabilities(), preset, availableBytes())
        val plan = (planned as? TranscodePlanningResult.Ready)?.plan
            ?: return ProcessingExecutionResult.Failure((planned as TranscodePlanningResult.Rejected).code)
        if (plan.requiresConfirmation && !confirmed) {
            return ProcessingExecutionResult.Failure("DEGRADATION_CONFIRMATION_REQUIRED")
        }
        val artifact = artifacts.allocate(task.id, plan.outputDisplayName)
        return try {
            val result = engine.transcode(plan, artifact.temporaryPath) { fraction ->
                onProgress(ProcessingProgress("transcode", (fraction * PROGRESS_TOTAL).toLong(), PROGRESS_TOTAL))
            }
            when (result) {
                TranscodeEngineResult.Completed -> {
                    onProgress(ProcessingProgress("verify", PROGRESS_TOTAL, PROGRESS_TOTAL))
                    val verification = verifier.verify(artifact.temporaryPath, plan)
                    if (!verification.valid) {
                        artifacts.abort(artifact)
                        ProcessingExecutionResult.Failure(verification.errorCodes.firstOrNull() ?: "OUTPUT_INVALID")
                    } else {
                        ProcessingExecutionResult.Success(artifacts.commit(artifact))
                    }
                }
                is TranscodeEngineResult.Failed -> {
                    artifacts.abort(artifact)
                    ProcessingExecutionResult.Failure(result.errorCode)
                }
                TranscodeEngineResult.Canceled -> {
                    artifacts.abort(artifact)
                    ProcessingExecutionResult.Canceled
                }
            }
        } catch (error: Exception) {
            artifacts.abort(artifact)
            throw error
        }
    }

    override suspend fun cancel(taskId: ProcessingTaskId) = engine.cancel()

    override suspend fun recover(task: ProcessingTask): ProcessingExecutionResult = execute(task) {}

    private companion object { const val PROGRESS_TOTAL = 1_000L }
}
