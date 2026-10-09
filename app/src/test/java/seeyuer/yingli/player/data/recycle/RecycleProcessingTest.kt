package seeyuer.yingli.player.data.recycle

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.common.IdGenerator
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryPagingRepository
import seeyuer.yingli.player.domain.library.LibraryPage
import seeyuer.yingli.player.domain.library.LibraryPageDirection
import seeyuer.yingli.player.domain.library.LibraryQuery
import seeyuer.yingli.player.domain.library.LibraryResult
import seeyuer.yingli.player.domain.processing.ProcessingExecutionResult
import seeyuer.yingli.player.domain.processing.ProcessingProject
import seeyuer.yingli.player.domain.processing.ProcessingProjectId
import seeyuer.yingli.player.domain.processing.ProcessingProjectType
import seeyuer.yingli.player.domain.processing.ProcessingReduction
import seeyuer.yingli.player.domain.processing.ProcessingRepository
import seeyuer.yingli.player.domain.processing.ProcessingTask
import seeyuer.yingli.player.domain.processing.ProcessingTaskEvent
import seeyuer.yingli.player.domain.processing.ProcessingTaskId
import seeyuer.yingli.player.domain.processing.ProcessingTaskState
import seeyuer.yingli.player.domain.recycle.ReconcileReport
import seeyuer.yingli.player.domain.recycle.RecycleAction
import seeyuer.yingli.player.domain.recycle.RecycleAuthorizationRequest
import seeyuer.yingli.player.domain.recycle.RecycleTarget
import seeyuer.yingli.player.domain.recycle.TrashBackend
import seeyuer.yingli.player.domain.recycle.TrashEntry
import seeyuer.yingli.player.domain.recycle.TrashOperationOutcome
import seeyuer.yingli.player.domain.recycle.TrashOperationReport
import seeyuer.yingli.player.domain.recycle.TrashService
import seeyuer.yingli.player.domain.recycle.TrashState

private val A = RecycleTarget(MediaLocationId("loc-a"), MediaItemId("item-a"))
private val B = RecycleTarget(MediaLocationId("loc-b"), MediaItemId("item-b"))
private const val NOW = 1_700_000_000_000L

class RecyclePolicyTest {

    @Test
    fun `a batch round trips through the policy string`() {
        val encoded = RecyclePolicy.encode(RecycleAction.PURGE, listOf(A, B))

        assertEquals("RECYCLE|PURGE|loc-a:item-a,loc-b:item-b", encoded)
        assertEquals(RecyclePolicy.Decoded(RecycleAction.PURGE, listOf(A, B)), RecyclePolicy.decode(encoded))
    }

    @Test
    fun `cleanup carries no targets and refuses to`() {
        assertNull(RecyclePolicy.decode(RecyclePolicy.encode(RecycleAction.CLEANUP, listOf(A))))
        assertEquals(
            emptyList<RecycleTarget>(),
            RecyclePolicy.decode("RECYCLE|CLEANUP|")?.targets,
        )
    }

    @Test
    fun `actions that need targets reject an empty list`() {
        // 「批量移入 0 个文件」是调用方 bug，不是合法的空操作；猜出意图比失败更危险。
        assertNull(RecyclePolicy.decode("RECYCLE|MOVE|"))
        assertNull(RecyclePolicy.decode("RECYCLE|PURGE|"))
    }

    @Test
    fun `malformed policies are rejected instead of guessed`() {
        assertNull(RecyclePolicy.decode("TRANSCODE|MOVE|loc-a:item-a"))
        assertNull(RecyclePolicy.decode("RECYCLE|NOT_AN_ACTION|loc-a:item-a"))
        assertNull(RecyclePolicy.decode("RECYCLE|MOVE|loc-a"))
        assertNull(RecyclePolicy.decode("RECYCLE|MOVE|loc-a:"))
    }
}

/**
 * `RecycleProcessingExecutor` 是任务中心里回收站动作的唯一入口，这里断言的是
 * **成功/失败的判定边界**（部分成功算成功、授权必须交回前台）。
 */
class RecycleProcessingExecutorTest {

    private val processing = FakeProcessingRepository()
    private val trash = FakeTrashService()
    private val library = FakeLibraryRepository()
    private val clock = AppClock { Instant.ofEpochMilli(NOW) }
    private val executor = RecycleProcessingExecutor(processing, trash, library, clock)

    @Test
    fun `enqueueing a batch records the action and the location ids in the project`() = runBlocking {
        val coordinator = RecycleCoordinator(processing, IdGenerator { "id-1" }, clock)

        coordinator.enqueue(RecycleAction.MOVE, listOf(A, B))

        val project = processing.projects().single()
        assertEquals(ProcessingProjectType.RECYCLE, project.type)
        // 条目 id 去重后进 `inputMediaIds`（任务中心只认它），位置 id 只活在策略串里。
        assertEquals(listOf(A.mediaItemId, B.mediaItemId), project.inputMediaIds)
        assertEquals(
            RecyclePolicy.Decoded(RecycleAction.MOVE, listOf(A, B)),
            RecyclePolicy.decode(project.outputPolicy),
        )
    }

    @Test
    fun `enqueueing with no targets is rejected outright`() = runBlocking {
        val coordinator = RecycleCoordinator(processing, IdGenerator { "id-1" }, clock)

        val failure = runCatching { coordinator.enqueue(RecycleAction.MOVE, emptyList()) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a partial batch reports success because some files did move`() = runBlocking {
        processing.project = project(RecycleAction.PURGE, listOf(A, B))
        trash.purgeOutcomes[A.locationId] = TrashOperationOutcome.Purged(A.locationId)
        trash.purgeOutcomes[B.locationId] = TrashOperationOutcome.Blocked("ENTRY_NOT_FOUND")

        val result = executor.execute(task(), {})

        assertEquals(ProcessingExecutionResult.Success(output = null), result)
    }

    @Test
    fun `a wholly failed batch reports the first failure code`() = runBlocking {
        processing.project = project(RecycleAction.PURGE, listOf(A, B))
        trash.purgeOutcomes[A.locationId] = TrashOperationOutcome.Blocked("ENTRY_NOT_FOUND")
        trash.purgeOutcomes[B.locationId] = TrashOperationOutcome.Blocked("ENTRY_NOT_FOUND")

        val result = executor.execute(task(), {})

        assertEquals(ProcessingExecutionResult.Failure("ENTRY_NOT_FOUND"), result)
    }

    @Test
    fun `a move whose source vanished fails rather than silently succeeding`() = runBlocking {
        processing.project = project(RecycleAction.MOVE, listOf(A))

        val result = executor.execute(task(), {})

        assertEquals(ProcessingExecutionResult.Failure("SOURCE_NOT_FOUND"), result)
    }

    @Test
    fun `a required authorization stops the task instead of faking success`() = runBlocking {
        processing.project = project(RecycleAction.PURGE, listOf(A))
        trash.purgeOutcomes[A.locationId] = TrashOperationOutcome.AuthorizationRequired(
            RecycleAuthorizationRequest("token-1", RecycleAction.PURGE, listOf(MediaUri("content://media/1")), "确认"),
        )

        val result = executor.execute(task(), {})

        assertEquals(ProcessingExecutionResult.Failure("AUTHORIZATION_REQUIRED"), result)
    }

    @Test
    fun `cleanup succeeds unless it reported errors`() = runBlocking {
        processing.project = project(RecycleAction.CLEANUP, emptyList())

        assertEquals(ProcessingExecutionResult.Success(output = null), executor.execute(task(), {}))

        trash.cleanupReport = ReconcileReport(repaired = 0, needsReview = 0, errors = 1)
        assertEquals(
            ProcessingExecutionResult.Failure("CLEANUP_FAILED"),
            executor.execute(task(), {}),
        )
    }

    @Test
    fun `an unparsable policy fails the task`() = runBlocking {
        processing.project = ProcessingProject(
            ProcessingProjectId("p1"),
            ProcessingProjectType.RECYCLE,
            listOf(A.mediaItemId),
            "RECYCLE|NOPE|loc-a:item-a",
            NOW,
        )

        val result = executor.execute(task(), {})

        assertEquals(ProcessingExecutionResult.Failure("INVALID_RECYCLE_POLICY"), result)
    }

    @Test
    fun `recovery never replays a move`() = runBlocking {
        // 真正的恢复入口是启动时的 `TrashService.reconcile()`；任务重做会重复移入。
        val result = executor.recover(task())

        assertEquals(ProcessingExecutionResult.Failure("NOT_RECOVERABLE"), result)
    }

    // ---- fixtures ----------------------------------------------------------

    private fun project(action: RecycleAction, targets: List<RecycleTarget>): ProcessingProject =
        ProcessingProject(
            ProcessingProjectId("p1"),
            ProcessingProjectType.RECYCLE,
            targets.map(RecycleTarget::mediaItemId),
            RecyclePolicy.encode(action, targets),
            NOW,
        )

    private fun task(): ProcessingTask = ProcessingTask(
        id = ProcessingTaskId("t1"),
        projectId = ProcessingProjectId("p1"),
        createdAtEpochMillis = NOW,
    )

    private class FakeProcessingRepository : ProcessingRepository {
        var project: ProcessingProject? = null

        override val tasks: Flow<List<ProcessingTask>> = flowOf(emptyList())
        override suspend fun projects(): List<ProcessingProject> = listOfNotNull(project)
        override suspend fun create(project: ProcessingProject, tasks: List<ProcessingTask>) {
            this.project = project
        }

        override suspend fun apply(taskId: ProcessingTaskId, event: ProcessingTaskEvent): ProcessingReduction =
            ProcessingReduction.Rejected(
                ProcessingTask(
                    id = taskId,
                    projectId = ProcessingProjectId("p1"),
                    state = ProcessingTaskState.FAILED,
                    errorCode = "NOT_SUPPORTED",
                    createdAtEpochMillis = NOW,
                ),
            )

        override suspend fun recoverInterrupted() = Unit
        override suspend fun clearTerminal() = Unit
    }

    private class FakeLibraryRepository : LibraryPagingRepository {
        override fun observe(query: LibraryQuery): Flow<LibraryResult<LibraryPage>> =
            flowOf(LibraryResult.Success(emptyPage()))

        override suspend fun query(query: LibraryQuery): LibraryResult<LibraryPage> =
            LibraryResult.Success(emptyPage())

        override suspend fun page(query: LibraryQuery, direction: LibraryPageDirection): LibraryPage = emptyPage()
        override fun observeCount(query: LibraryQuery): Flow<Int> = flowOf(0)
        override fun observeFolderTreeVideoCount(path: String): Flow<Int> = flowOf(0)
        override fun observeInvalidations(): Flow<Unit> = flowOf(Unit)
        override suspend fun findByIds(ids: Set<MediaItemId>): List<LibraryMedia> = emptyList()

        private fun emptyPage() = LibraryPage(emptyList(), null, 0)
    }

    private class FakeTrashService : TrashService {
        val purgeOutcomes = mutableMapOf<MediaLocationId, TrashOperationOutcome>()
        var cleanupReport = ReconcileReport(repaired = 0, needsReview = 0, errors = 0)

        override fun observe(): Flow<List<TrashEntry>> = flowOf(emptyList())
        override suspend fun move(item: LibraryMedia): TrashOperationOutcome =
            TrashOperationOutcome.Completed(entry(item.locationId, item.id))

        override suspend fun move(items: List<LibraryMedia>): TrashOperationReport =
            TrashOperationReport(items.associate { it.locationId to move(it) })

        override suspend fun restore(locationId: MediaLocationId): TrashOperationOutcome =
            TrashOperationOutcome.Completed(entry(locationId, MediaItemId("item")))

        override suspend fun purge(locationId: MediaLocationId): TrashOperationOutcome =
            purgeOutcomes[locationId] ?: TrashOperationOutcome.Purged(locationId)

        override suspend fun purgeAll(): TrashOperationReport = TrashOperationReport(emptyMap())
        override suspend fun discard(locationId: MediaLocationId): TrashOperationOutcome =
            TrashOperationOutcome.Discarded(locationId)
        override suspend fun resolveAuthorization(token: String, granted: Boolean): TrashOperationOutcome? = null
        override suspend fun reconcile(): ReconcileReport = ReconcileReport(0, 0, 0)
        override suspend fun cleanupExpired(nowEpochMillis: Long): ReconcileReport = cleanupReport
        override suspend fun activeOperations(item: LibraryMedia): Set<ProcessingProjectType> = emptySet()

        private fun entry(locationId: MediaLocationId, mediaItemId: MediaItemId) = TrashEntry(
            locationId = locationId,
            mediaItemId = mediaItemId,
            backend = TrashBackend.R1_SYSTEM,
            state = TrashState.ACTIVE,
            originalUri = MediaUri("content://media/${locationId.value}"),
            originalDisplayName = "a.mp4",
            originalSizeBytes = 1_000,
            updatedAtEpochMillis = NOW,
        )
    }
}
