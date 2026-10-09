package seeyuer.yingli.player.data.duplicates

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.domain.duplicates.DUPLICATE_HASH_ALGORITHM_VERSION
import seeyuer.yingli.player.domain.duplicates.DUPLICATE_SCAN_OPERATION_KEY_PREFIX
import seeyuer.yingli.player.domain.duplicates.DuplicateMode
import seeyuer.yingli.player.domain.duplicates.DuplicateScanProgress
import seeyuer.yingli.player.domain.duplicates.DuplicateScanResult
import seeyuer.yingli.player.domain.duplicates.DuplicateScanner
import seeyuer.yingli.player.domain.processing.ProcessingExecutionResult
import seeyuer.yingli.player.domain.processing.ProcessingProject
import seeyuer.yingli.player.domain.processing.ProcessingProjectId
import seeyuer.yingli.player.domain.processing.ProcessingProjectType
import seeyuer.yingli.player.domain.processing.ProcessingProgress
import seeyuer.yingli.player.domain.processing.ProcessingReduction
import seeyuer.yingli.player.domain.processing.ProcessingRepository
import seeyuer.yingli.player.domain.processing.ProcessingTask
import seeyuer.yingli.player.domain.processing.ProcessingTaskEvent
import seeyuer.yingli.player.domain.processing.ProcessingTaskId
import seeyuer.yingli.player.testing.FakeAppClock
import seeyuer.yingli.player.testing.SequenceIdGenerator

class DuplicateProcessingTest {
    @Test
    fun `policy round trips mode and algorithm version`() {
        val encoded = DeduplicatePolicy.encode(DuplicateMode.EXACT)

        assertEquals("SCAN|EXACT|v$DUPLICATE_HASH_ALGORITHM_VERSION", encoded)
        assertEquals(
            DeduplicatePolicy.Decoded(DuplicateMode.EXACT, DUPLICATE_HASH_ALGORITHM_VERSION),
            DeduplicatePolicy.decode(encoded),
        )
    }

    @Test
    fun `policy refuses to guess`() {
        assertNull(DeduplicatePolicy.decode(""))
        assertNull(DeduplicatePolicy.decode("SCAN|EXACT"))
        assertNull(DeduplicatePolicy.decode("TRANSCODE|EXACT|v1"))
        assertNull(DeduplicatePolicy.decode("SCAN|SOMETHING|v1"))
        assertNull(DeduplicatePolicy.decode("SCAN|EXACT|latest"))
    }

    @Test
    fun `enqueue creates a whole library project with a scan operation key`() = runTest {
        val repository = FakeProcessingRepository()
        val coordinator = DeduplicateCoordinator(repository, SequenceIdGenerator(), FakeAppClock())

        val projectId = coordinator.enqueue(DuplicateMode.EXACT)

        val project = repository.created.project
        assertEquals(ProcessingProjectType.DEDUPLICATE, project.type)
        assertEquals(projectId, project.id)
        assertTrue(project.inputMediaIds.isEmpty())
        assertEquals("SCAN|EXACT|v$DUPLICATE_HASH_ALGORITHM_VERSION", project.outputPolicy)
        assertEquals(
            DUPLICATE_SCAN_OPERATION_KEY_PREFIX + "exact",
            repository.created.tasks.single().operationKey,
        )
    }

    @Test
    fun `a completed scan succeeds without any artifact`() = runTest {
        val repository = FakeProcessingRepository(project(policy = DeduplicatePolicy.encode(DuplicateMode.EXACT)))
        val scanner = FakeScanner(DuplicateScanResult.Completed(0, 0, 0, 0, 0))

        val result = DeduplicateProcessingExecutor(repository, scanner).execute(task(), {})

        assertEquals(ProcessingExecutionResult.Success(output = null), result)
        assertEquals(DuplicateMode.EXACT, scanner.requestedMode)
    }

    @Test
    fun `scan progress is forwarded with widened units`() = runTest {
        val repository = FakeProcessingRepository(project(policy = DeduplicatePolicy.encode(DuplicateMode.EXACT)))
        val scanner = FakeScanner(
            result = DuplicateScanResult.Completed(0, 0, 0, 0, 0),
            progress = listOf(DuplicateScanProgress(stage = "quick_hash", processedUnits = 7, totalUnits = null)),
        )
        val reported = mutableListOf<ProcessingProgress>()

        DeduplicateProcessingExecutor(repository, scanner).execute(task()) { reported += it }

        assertEquals(7L, reported.single().processedUnits)
        assertNull(reported.single().totalUnits)
    }

    @Test
    fun `a rejected scan is reported with its code`() = runTest {
        val repository = FakeProcessingRepository(project(policy = DeduplicatePolicy.encode(DuplicateMode.EXACT)))
        val scanner = FakeScanner(DuplicateScanResult.Rejected("SIMILAR_EXPERIMENT_DISABLED"))

        val result = DeduplicateProcessingExecutor(repository, scanner).execute(task(), {})

        assertEquals(ProcessingExecutionResult.Failure("SIMILAR_EXPERIMENT_DISABLED"), result)
    }

    @Test
    fun `a canceled scan stays canceled`() = runTest {
        val repository = FakeProcessingRepository(project(policy = DeduplicatePolicy.encode(DuplicateMode.EXACT)))
        val scanner = FakeScanner(DuplicateScanResult.Canceled)

        val result = DeduplicateProcessingExecutor(repository, scanner).execute(task(), {})

        assertEquals(ProcessingExecutionResult.Canceled, result)
    }

    @Test
    fun `a policy written by an older algorithm version is refused before scanning`() = runTest {
        val repository = FakeProcessingRepository(
            project(policy = DeduplicatePolicy.encode(DuplicateMode.EXACT, algorithmVersion = DUPLICATE_HASH_ALGORITHM_VERSION - 1)),
        )
        val scanner = FakeScanner(DuplicateScanResult.Completed(0, 0, 0, 0, 0))

        val result = DeduplicateProcessingExecutor(repository, scanner).execute(task(), {})

        assertEquals(ProcessingExecutionResult.Failure("HASH_VERSION_UNSUPPORTED"), result)
        assertNull(scanner.requestedMode)
    }

    @Test
    fun `an unparseable policy is refused before scanning`() = runTest {
        val repository = FakeProcessingRepository(project(policy = "not-a-policy"))
        val scanner = FakeScanner(DuplicateScanResult.Completed(0, 0, 0, 0, 0))

        val result = DeduplicateProcessingExecutor(repository, scanner).execute(task(), {})

        assertEquals(ProcessingExecutionResult.Failure("INVALID_DUPLICATE_POLICY"), result)
        assertNull(scanner.requestedMode)
    }

    // ---- fixtures ----------------------------------------------------------

    private fun project(policy: String) = ProcessingProject(
        id = PROJECT_ID,
        type = ProcessingProjectType.DEDUPLICATE,
        inputMediaIds = emptyList(),
        outputPolicy = policy,
        createdAtEpochMillis = 1,
    )

    private class FakeScanner(
        private val result: DuplicateScanResult,
        private val progress: List<DuplicateScanProgress> = emptyList(),
    ) : DuplicateScanner {
        var requestedMode: DuplicateMode? = null
        var cancelled = false

        override suspend fun scan(
            mode: DuplicateMode,
            onProgress: suspend (DuplicateScanProgress) -> Unit,
        ): DuplicateScanResult {
            requestedMode = mode
            progress.forEach { onProgress(it) }
            return result
        }

        override fun cancel() {
            cancelled = true
        }
    }

    private class FakeProcessingRepository(private val project: ProcessingProject? = null) : ProcessingRepository {
        class Created(val project: ProcessingProject, val tasks: List<ProcessingTask>)

        lateinit var created: Created
        override val tasks: Flow<List<ProcessingTask>> = MutableStateFlow(emptyList())

        override suspend fun projects(): List<ProcessingProject> = listOfNotNull(project)

        override suspend fun create(project: ProcessingProject, tasks: List<ProcessingTask>) {
            created = Created(project, tasks)
        }

        override suspend fun apply(taskId: ProcessingTaskId, event: ProcessingTaskEvent): ProcessingReduction =
            ProcessingReduction.Ignored(ProcessingTask(TASK_ID, PROJECT_ID, createdAtEpochMillis = 1))

        override suspend fun recoverInterrupted() = Unit
        override suspend fun clearTerminal() = Unit
    }

    /** 执行器只按 `projectId` 找项目，因此任务里只需要一个与 `project(...)` 一致的 id。 */
    private fun task() = ProcessingTask(TASK_ID, PROJECT_ID, createdAtEpochMillis = 1)

    private companion object {
        val PROJECT_ID = ProcessingProjectId("project")
        val TASK_ID = ProcessingTaskId("task")
    }
}
