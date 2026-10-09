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
import seeyuer.yingli.player.domain.library.BatchOperationSummary
import seeyuer.yingli.player.domain.library.FileOperationFailure
import seeyuer.yingli.player.domain.library.FileOperationResult
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryMutationRepository
import seeyuer.yingli.player.domain.library.TrashEntry
import seeyuer.yingli.player.domain.library.TrashRepository
import seeyuer.yingli.player.domain.library.TrashState
import seeyuer.yingli.player.domain.organize.OrganizeMutationResult
import seeyuer.yingli.player.domain.organize.OrganizeRepository
import seeyuer.yingli.player.domain.organize.OrganizeSnapshot
import seeyuer.yingli.player.domain.organize.Tag
import seeyuer.yingli.player.domain.organize.TagColor
import seeyuer.yingli.player.domain.organize.TagId
import seeyuer.yingli.player.domain.library.FilterExpression
import seeyuer.yingli.player.testing.MainDispatcherRule

/**
 * 回收站从视频页面迁移到整理页面后，整理页承担的全部回收站行为。
 * 这里刻意只经过 `LibraryMutationRepository` / `TrashRepository` 两个既有域接口，
 * 因为迁移的前提就是「数据层零改动」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrganizeViewModelTrashTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `trash items expose age and remaining retention from the clock`() = runTest {
        val trash = FakeTrashRepository(listOf(entry(mediaId = "a", deletedAt = NOW - 3 * DAY, purgeAt = NOW + 27 * DAY)))
        val viewModel = viewModel(trash = trash)
        val collect = subscribe(viewModel)

        runCurrent()

        val item = viewModel.state.value.trashItems.single()
        assertEquals("a", item.entry.mediaId.value)
        assertEquals(3L, item.ageDays)
        assertEquals(27L, item.remainingDays)
        collect.cancel()
    }

    @Test
    fun `restore reports success and keeps the sheet open`() = runTest {
        val mutations = FakeMutationRepository()
        val viewModel = viewModel(mutations = mutations)
        val collect = subscribe(viewModel)
        viewModel.openTrash()
        runCurrent()

        viewModel.restore(entry())
        runCurrent()

        assertEquals(listOf("a"), mutations.restored.map { it.mediaId.value })
        assertEquals(OrganizeViewModel.TRASH_RESTORED, viewModel.state.value.trashStatusCode)
        assertTrue(viewModel.state.value.trashSheetOpen)
        collect.cancel()
    }

    @Test
    fun `restore failure surfaces the underlying reason code`() = runTest {
        val mutations = FakeMutationRepository(restoreResult = FileOperationResult.RecoverableFailure(FileOperationFailure.SOURCE_MISSING))
        val viewModel = viewModel(mutations = mutations)
        val collect = subscribe(viewModel)

        viewModel.restore(entry())
        runCurrent()

        assertEquals("${OrganizeViewModel.TRASH_FAILED_PREFIX}SOURCE_MISSING", viewModel.state.value.trashStatusCode)
        collect.cancel()
    }

    @Test
    fun `purging needs an explicit confirmation before anything is deleted`() = runTest {
        val mutations = FakeMutationRepository()
        val viewModel = viewModel(mutations = mutations)
        val collect = subscribe(viewModel)
        viewModel.openTrash()
        runCurrent()

        viewModel.requestPurge(entry())
        runCurrent()
        assertEquals("a", viewModel.state.value.pendingPurge?.entry?.mediaId?.value)
        assertTrue(mutations.purged.isEmpty())

        viewModel.confirmPurge()
        runCurrent()
        assertEquals(listOf("a"), mutations.purged.map { it.mediaId.value })
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
    fun `clearing purges every entry and reports the first failure`() = runTest {
        val mutations = FakeMutationRepository(
            purgeResults = mapOf(
                "a" to FileOperationResult.Success(MediaUri("content://media/a")),
                "b" to FileOperationResult.RecoverableFailure(FileOperationFailure.PERMISSION_REQUIRED),
            ),
        )
        val trash = FakeTrashRepository(listOf(entry(mediaId = "a"), entry(mediaId = "b")))
        val viewModel = viewModel(mutations = mutations, trash = trash)
        val collect = subscribe(viewModel)
        viewModel.openTrash()
        runCurrent()

        viewModel.requestClearTrash()
        runCurrent()
        assertTrue(viewModel.state.value.clearTrashConfirmOpen)

        viewModel.confirmClearTrash()
        runCurrent()

        assertEquals(listOf("a", "b"), mutations.purged.map { it.mediaId.value })
        assertEquals("${OrganizeViewModel.TRASH_FAILED_PREFIX}PERMISSION_REQUIRED", viewModel.state.value.trashStatusCode)
        assertFalse(viewModel.state.value.clearTrashConfirmOpen)
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

    private fun TestScope.subscribe(viewModel: OrganizeViewModel) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }

    private fun viewModel(
        mutations: LibraryMutationRepository = FakeMutationRepository(),
        trash: TrashRepository = FakeTrashRepository(emptyList()),
    ): OrganizeViewModel = OrganizeViewModel(
        repository = FakeOrganizeRepository(),
        libraryMutationRepository = mutations,
        trashRepository = trash,
        clock = AppClock { Instant.ofEpochMilli(NOW) },
    )

    private fun entry(
        mediaId: String = "a",
        deletedAt: Long = NOW - DAY,
        purgeAt: Long = NOW + 29 * DAY,
    ): TrashEntry = TrashEntry(
        mediaId = MediaItemId(mediaId),
        locationId = MediaLocationId("storage"),
        originalUri = MediaUri("content://media/$mediaId"),
        trashedUri = MediaUri("content://media/trash/$mediaId"),
        deletedAtEpochMillis = deletedAt,
        purgeAtEpochMillis = purgeAt,
    )

    private class FakeMutationRepository(
        private val restoreResult: FileOperationResult = FileOperationResult.Success(MediaUri("content://media/a")),
        private val purgeResults: Map<String, FileOperationResult> = emptyMap(),
    ) : LibraryMutationRepository {
        val restored = mutableListOf<TrashEntry>()
        val purged = mutableListOf<TrashEntry>()

        override suspend fun trash(items: List<LibraryMedia>): BatchOperationSummary =
            BatchOperationSummary(items.size, emptyMap())

        override suspend fun restore(entry: TrashEntry): FileOperationResult {
            restored += entry
            return restoreResult
        }

        override suspend fun purge(entry: TrashEntry): FileOperationResult {
            purged += entry
            return purgeResults[entry.mediaId.value] ?: FileOperationResult.Success(entry.originalUri)
        }
    }

    private class FakeTrashRepository(entries: List<TrashEntry>) : TrashRepository {
        private val state = MutableStateFlow(entries)
        override fun observe(): Flow<List<TrashEntry>> = state
        override suspend fun put(entry: TrashEntry) {
            state.value = state.value + entry
        }
        override suspend fun updateState(locationId: MediaLocationId, state: TrashState) = Unit

        override suspend fun remove(locationId: MediaLocationId) {
            state.value = state.value.filterNot { it.locationId == locationId }
        }
        override suspend fun expired(nowEpochMillis: Long): List<TrashEntry> =
            state.value.filter { it.purgeAtEpochMillis <= nowEpochMillis }
    }

    private class FakeOrganizeRepository : OrganizeRepository {
        override val snapshot = MutableStateFlow(OrganizeSnapshot())
        override suspend fun createTag(name: String, color: TagColor): OrganizeMutationResult = OrganizeMutationResult.Success
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
    }
}
