package seeyuer.yingli.player.feature.organize

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.library.FilterExpression
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.organize.OrganizeMutationResult
import seeyuer.yingli.player.domain.organize.OrganizeRepository
import seeyuer.yingli.player.domain.organize.OrganizeSnapshot
import seeyuer.yingli.player.domain.organize.Tag
import seeyuer.yingli.player.domain.organize.TagColor
import seeyuer.yingli.player.domain.organize.TagId
import seeyuer.yingli.player.domain.processing.ProcessingProjectId
import seeyuer.yingli.player.domain.processing.ProcessingProjectType
import seeyuer.yingli.player.domain.recycle.ReconcileReport
import seeyuer.yingli.player.domain.recycle.RecycleAction
import seeyuer.yingli.player.domain.recycle.RecycleAuthorizationRequest
import seeyuer.yingli.player.domain.recycle.RecycleQueue
import seeyuer.yingli.player.domain.recycle.RecycleTarget
import seeyuer.yingli.player.domain.recycle.TrashBackend
import seeyuer.yingli.player.domain.recycle.TrashEntry
import seeyuer.yingli.player.domain.recycle.TrashOperationOutcome
import seeyuer.yingli.player.domain.recycle.TrashOperationReport
import seeyuer.yingli.player.domain.recycle.TrashRepository
import seeyuer.yingli.player.domain.recycle.TrashService
import seeyuer.yingli.player.domain.recycle.TrashState
import seeyuer.yingli.player.testing.MainDispatcherRule

/**
 * 回收站从视频页面迁移到整理页面后，整理页承担的全部回收站行为。
 *
 * 阶段 4 之后写操作**全部经 `TrashService`**（页面不再直接碰文件或 `LibraryMutationRepository`）：
 * 这里注入的假 Service 既记录「调了哪一条」，也负责伪造三种需要页面区分的结果 ——
 * 成功、需要系统授权、以及可解释的失败。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrganizeViewModelTrashTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `trash items expose age and remaining retention from the clock`() = runTest {
        val trash = FakeTrashRepository(
            listOf(entry(mediaId = "a", trashedAt = NOW - 3 * DAY, expiresAt = NOW + 27 * DAY)),
        )
        val viewModel = viewModel(trash = trash)
        val collect = subscribe(viewModel)

        runCurrent()

        val item = viewModel.state.value.trashItems.single()
        assertEquals("a", item.entry.mediaItemId.value)
        assertEquals(3L, item.ageDays)
        assertEquals(27L, item.remainingDays)
        collect.cancel()
    }

    @Test
    fun `restore reports success and keeps the sheet open`() = runTest {
        val service = FakeTrashService()
        val viewModel = viewModel(service = service)
        val collect = subscribe(viewModel)
        viewModel.openTrash()
        runCurrent()

        viewModel.restore(entry())
        runCurrent()

        assertEquals(listOf("loc-a"), service.restored)
        assertEquals(OrganizeViewModel.TRASH_RESTORED, viewModel.state.value.trashStatusCode)
        assertTrue(viewModel.state.value.trashSheetOpen)
        collect.cancel()
    }

    @Test
    fun `restore failure surfaces the underlying reason code`() = runTest {
        val service = FakeTrashService(
            restoreOutcome = TrashOperationOutcome.Failed(entry(), code = "SOURCE_MISSING"),
        )
        val viewModel = viewModel(service = service)
        val collect = subscribe(viewModel)

        viewModel.restore(entry())
        runCurrent()

        assertEquals("${OrganizeViewModel.TRASH_FAILED_PREFIX}SOURCE_MISSING", viewModel.state.value.trashStatusCode)
        collect.cancel()
    }

    @Test
    fun `restore needing system authorization parks the request instead of reporting success`() = runTest {
        val service = FakeTrashService(restoreOutcome = TrashOperationOutcome.AuthorizationRequired(REQUEST))
        val viewModel = viewModel(service = service)
        val collect = subscribe(viewModel)

        viewModel.restore(entry())
        runCurrent()

        assertEquals(OrganizeViewModel.TRASH_AUTHORIZATION_REQUIRED, viewModel.state.value.trashStatusCode)
        assertEquals(REQUEST.token, viewModel.state.value.pendingTrashAuthorization?.token)
        collect.cancel()
    }

    @Test
    fun `resolving a granted authorization advances the state machine`() = runTest {
        val service = FakeTrashService(
            restoreOutcome = TrashOperationOutcome.AuthorizationRequired(REQUEST),
            resolveOutcome = TrashOperationOutcome.Completed(entry()),
        )
        val viewModel = viewModel(service = service)
        val collect = subscribe(viewModel)
        viewModel.restore(entry())
        runCurrent()

        viewModel.resolveTrashAuthorization(granted = true)
        runCurrent()

        assertEquals(listOf(REQUEST.token to true), service.resolved)
        assertEquals(OrganizeViewModel.TRASH_RESTORED, viewModel.state.value.trashStatusCode)
        assertNull(viewModel.state.value.pendingTrashAuthorization)
        collect.cancel()
    }

    @Test
    fun `purging needs an explicit confirmation before anything is deleted`() = runTest {
        val service = FakeTrashService()
        val viewModel = viewModel(service = service)
        val collect = subscribe(viewModel)
        viewModel.openTrash()
        runCurrent()

        viewModel.requestPurge(entry())
        runCurrent()
        assertEquals("a", viewModel.state.value.pendingPurge?.entry?.mediaItemId?.value)
        assertTrue(service.purged.isEmpty())

        viewModel.confirmPurge()
        runCurrent()
        assertEquals(listOf("loc-a"), service.purged)
        assertNull(viewModel.state.value.pendingPurge)
        assertEquals(OrganizeViewModel.TRASH_PURGED, viewModel.state.value.trashStatusCode)
        collect.cancel()
    }

    @Test
    fun `clearing an empty trash does not open the confirmation`() = runTest {
        val viewModel = viewModel(trash = FakeTrashRepository(emptyList()))
        val collect = subscribe(viewModel)
        viewModel.openTrash()
        runCurrent()

        viewModel.requestClearTrash()
        runCurrent()

        assertFalse(viewModel.state.value.clearTrashConfirmOpen)
        collect.cancel()
    }

    @Test
    fun `clearing reports partial success and keeps the confirmation closed`() = runTest {
        val service = FakeTrashService(
            purgeOutcomes = mapOf(
                "loc-a" to TrashOperationOutcome.Purged(MediaLocationId("loc-a")),
                "loc-b" to TrashOperationOutcome.Failed(entry(mediaId = "b"), code = "PERMISSION_REQUIRED"),
            ),
        )
        val trash = FakeTrashRepository(listOf(entry(mediaId = "a"), entry(mediaId = "b")))
        val viewModel = viewModel(service = service, trash = trash)
        val collect = subscribe(viewModel)
        viewModel.openTrash()
        runCurrent()

        viewModel.requestClearTrash()
        runCurrent()
        assertTrue(viewModel.state.value.clearTrashConfirmOpen)

        viewModel.confirmClearTrash()
        runCurrent()

        assertEquals(listOf("loc-a", "loc-b"), service.purged)
        // 逐项都试过了，所以状态码反映的是「1 成功 1 失败」而不是第一个失败原因：
        // 只报第一个失败会让用户以为整批都没删掉。
        assertEquals("${OrganizeViewModel.TRASH_CLEAR_PARTIAL_PREFIX}1_1", viewModel.state.value.trashStatusCode)
        assertFalse(viewModel.state.value.clearTrashConfirmOpen)
        collect.cancel()
    }

    @Test
    fun `clearing hands the batch to the task centre when one is available`() = runTest {
        val queue = FakeRecycleQueue()
        val trash = FakeTrashRepository(listOf(entry(mediaId = "a"), entry(mediaId = "b")))
        val viewModel = viewModel(service = FakeTrashService(), trash = trash, queue = queue)
        val collect = subscribe(viewModel)
        viewModel.openTrash()
        runCurrent()

        viewModel.confirmClearTrash()
        runCurrent()

        assertEquals(RecycleAction.PURGE, queue.enqueuedAction)
        assertEquals(listOf("loc-a", "loc-b"), queue.enqueuedTargets.map { it.locationId.value })
        assertEquals(OrganizeViewModel.TRASH_CLEAR_ENQUEUED, viewModel.state.value.trashStatusCode)
        collect.cancel()
    }

    @Test
    fun `closing the sheet discards the pending confirmation`() = runTest {
        val viewModel = viewModel()
        val collect = subscribe(viewModel)

        viewModel.openTrash()
        viewModel.requestPurge(entry())
        runCurrent()
        assertTrue(viewModel.state.value.trashSheetOpen)

        viewModel.closeTrash()
        runCurrent()

        assertFalse(viewModel.state.value.trashSheetOpen)
        assertNull(viewModel.state.value.pendingPurge)
        collect.cancel()
    }

    @Test
    fun `retrying a pending cleanup purges again instead of restoring`() = runTest {
        val service = FakeTrashService()
        val viewModel = viewModel(service = service)
        val collect = subscribe(viewModel)

        viewModel.retryPurge(entry(mediaId = "a"))
        runCurrent()

        assertEquals(listOf("loc-a"), service.purged)
        assertTrue("重试不是恢复", service.restored.isEmpty())
        assertEquals(OrganizeViewModel.TRASH_PURGED, viewModel.state.value.trashStatusCode)
        collect.cancel()
    }

    @Test
    fun `discarding a failed entry keeps the file and reports the new code`() = runTest {
        val service = FakeTrashService()
        val viewModel = viewModel(service = service)
        val collect = subscribe(viewModel)

        viewModel.discard(entry(mediaId = "a"))
        runCurrent()

        assertEquals(listOf("loc-a"), service.discarded)
        assertTrue(service.purged.isEmpty())
        assertEquals(OrganizeViewModel.TRASH_DISCARDED, viewModel.state.value.trashStatusCode)
        collect.cancel()
    }

    @Test
    fun `reconcile surfaces how many entries still need a human`() = runTest {
        val service = FakeTrashService(reconcileReport = ReconcileReport(repaired = 2, needsReview = 3, errors = 0))
        val viewModel = viewModel(service = service)
        val collect = subscribe(viewModel)

        viewModel.reconcileTrash()
        runCurrent()

        assertEquals(1, service.reconcileCalls)
        assertEquals("${OrganizeViewModel.TRASH_RECONCILED_REVIEW_PREFIX}3", viewModel.state.value.trashStatusCode)
        collect.cancel()
    }

    private fun TestScope.subscribe(viewModel: OrganizeViewModel) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }

    private fun viewModel(
        trash: TrashRepository = FakeTrashRepository(emptyList()),
        service: TrashService = FakeTrashService(),
        queue: RecycleQueue? = null,
    ): OrganizeViewModel = OrganizeViewModel(
        repository = FakeOrganizeRepository(),
        trashRepository = trash,
        trashService = service,
        recycleQueue = queue,
        clock = AppClock { Instant.ofEpochMilli(NOW) },
    )

    private fun entry(
        mediaId: String = "a",
        trashedAt: Long = NOW - DAY,
        expiresAt: Long = NOW + 29 * DAY,
    ): TrashEntry = TrashEntry(
        locationId = MediaLocationId("loc-$mediaId"),
        mediaItemId = MediaItemId(mediaId),
        backend = TrashBackend.R2_APP_COPY,
        state = TrashState.ACTIVE,
        originalUri = MediaUri("content://media/$mediaId"),
        originalDisplayName = "$mediaId.mp4",
        originalSizeBytes = 100,
        updatedAtEpochMillis = NOW,
        copyRelativePath = "items/copy-$mediaId",
        copySizeBytes = 100,
        trashedAtEpochMillis = trashedAt,
        expiresAtEpochMillis = expiresAt,
    )

    private class FakeTrashService(
        private val restoreOutcome: TrashOperationOutcome? = null,
        private val purgeOutcomes: Map<String, TrashOperationOutcome> = emptyMap(),
        private val resolveOutcome: TrashOperationOutcome? = null,
        private val reconcileReport: ReconcileReport = ReconcileReport.Empty,
    ) : TrashService {
        val restored = mutableListOf<String>()
        val purged = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val resolved = mutableListOf<Pair<String, Boolean>>()
        var reconcileCalls = 0
            private set

        override fun observe(): Flow<List<TrashEntry>> = flowOf(emptyList())

        override suspend fun move(item: LibraryMedia): TrashOperationOutcome =
            TrashOperationOutcome.Blocked("UNUSED")

        override suspend fun move(items: List<LibraryMedia>): TrashOperationReport =
            TrashOperationReport(emptyMap())

        override suspend fun restore(locationId: MediaLocationId): TrashOperationOutcome {
            restored += locationId.value
            return restoreOutcome ?: TrashOperationOutcome.Completed(entryFor(locationId))
        }

        override suspend fun purge(locationId: MediaLocationId): TrashOperationOutcome {
            purged += locationId.value
            return purgeOutcomes[locationId.value] ?: TrashOperationOutcome.Purged(locationId)
        }

        override suspend fun purgeAll(): TrashOperationReport = TrashOperationReport(emptyMap())

        override suspend fun resolveAuthorization(token: String, granted: Boolean): TrashOperationOutcome? {
            resolved += token to granted
            return resolveOutcome
        }

        override suspend fun reconcile(): ReconcileReport {
            reconcileCalls += 1
            return reconcileReport
        }

        override suspend fun cleanupExpired(nowEpochMillis: Long): ReconcileReport = ReconcileReport.Empty

        override suspend fun discard(locationId: MediaLocationId): TrashOperationOutcome {
            discarded += locationId.value
            return TrashOperationOutcome.Discarded(locationId)
        }

        override suspend fun activeOperations(item: LibraryMedia): Set<ProcessingProjectType> = emptySet()

        private fun entryFor(locationId: MediaLocationId) = TrashEntry(
            locationId = locationId,
            mediaItemId = MediaItemId(locationId.value.removePrefix("loc-")),
            backend = TrashBackend.R2_APP_COPY,
            state = TrashState.ACTIVE,
            originalUri = MediaUri("content://media/${locationId.value}"),
            originalDisplayName = "${locationId.value}.mp4",
            originalSizeBytes = 100,
            updatedAtEpochMillis = NOW,
        )
    }

    private class FakeRecycleQueue : RecycleQueue {
        var enqueuedAction: RecycleAction? = null
        var enqueuedTargets: List<RecycleTarget> = emptyList()

        override suspend fun enqueue(
            action: RecycleAction,
            targets: List<RecycleTarget>,
        ): ProcessingProjectId {
            enqueuedAction = action
            enqueuedTargets = targets
            return ProcessingProjectId("recycle-1")
        }
    }

    private class FakeTrashRepository(entries: List<TrashEntry>) : TrashRepository {
        private val state = MutableStateFlow(entries)

        override fun observe(): Flow<List<TrashEntry>> = state

        override suspend fun byLocation(locationId: MediaLocationId): TrashEntry? =
            state.value.firstOrNull { it.locationId == locationId }

        override suspend fun byLocations(locationIds: Collection<MediaLocationId>): List<TrashEntry> =
            state.value.filter { it.locationId in locationIds }

        override suspend fun insert(entry: TrashEntry) {
            state.value = state.value + entry
        }

        override suspend fun update(entry: TrashEntry) {
            state.value = state.value.map { if (it.locationId == entry.locationId) entry else it }
        }

        override suspend fun remove(locationId: MediaLocationId) {
            state.value = state.value.filterNot { it.locationId == locationId }
        }

        override suspend fun inStates(states: Set<TrashState>): List<TrashEntry> =
            state.value.filter { it.state in states }

        override suspend fun expired(nowEpochMillis: Long): List<TrashEntry> =
            state.value.filter { (it.expiresAtEpochMillis ?: Long.MAX_VALUE) <= nowEpochMillis }
    }

    private class FakeOrganizeRepository : OrganizeRepository {
        override val snapshot = MutableStateFlow(OrganizeSnapshot())

        override suspend fun createTag(name: String, color: TagColor): OrganizeMutationResult =
            OrganizeMutationResult.Success

        override suspend fun updateTag(tag: Tag) = OrganizeMutationResult.Success
        override suspend fun deleteTag(id: TagId) = OrganizeMutationResult.Success
        override suspend fun addTags(mediaIds: Set<MediaItemId>, tagIds: Set<TagId>) = OrganizeMutationResult.Success
        override suspend fun setFavorite(mediaIds: Set<MediaItemId>, favorite: Boolean) = OrganizeMutationResult.Success
        override suspend fun createPlaylist(name: String, mediaIds: List<MediaItemId>) = OrganizeMutationResult.Success
        override suspend fun createCollection(name: String, mediaIds: Set<MediaItemId>) = OrganizeMutationResult.Success
        override suspend fun createSmartCollection(name: String, filter: FilterExpression) = OrganizeMutationResult.Success
    }

    private companion object {
        const val DAY = 86_400_000L
        const val NOW = 1_770_000_000_000L
        val REQUEST = RecycleAuthorizationRequest(
            token = "token-1",
            action = RecycleAction.RESTORE,
            uris = listOf(MediaUri("content://media/a")),
            reason = "需要确认",
        )
    }
}
