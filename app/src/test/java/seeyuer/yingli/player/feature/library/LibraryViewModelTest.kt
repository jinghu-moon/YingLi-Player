package seeyuer.yingli.player.feature.library

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.library.BatchOperationSummary
import seeyuer.yingli.player.domain.library.FileOperationResult
import seeyuer.yingli.player.domain.library.LibraryCursor
import seeyuer.yingli.player.domain.library.LibraryDisplayPreference
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryMutationRepository
import seeyuer.yingli.player.domain.library.LibraryPage
import seeyuer.yingli.player.domain.library.LibraryPreferenceRepository
import seeyuer.yingli.player.domain.library.LibraryQuery
import seeyuer.yingli.player.domain.library.LibraryRepository
import seeyuer.yingli.player.domain.library.LibraryResult
import seeyuer.yingli.player.domain.library.LibrarySortField
import seeyuer.yingli.player.domain.library.LibraryViewMode
import seeyuer.yingli.player.domain.library.SortDirection
import seeyuer.yingli.player.domain.library.TrashEntry
import seeyuer.yingli.player.testing.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `rapid search only executes final debounced keyword`() = runTest {
        val repository = FakeLibraryRepository()
        val viewModel = LibraryViewModel(repository, FakePreferenceRepository(), FakeMutationRepository(), FakeTrashRepository())
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        advanceTimeBy(250)
        runCurrent()
        repository.queries.clear()

        viewModel.setKeyword("a")
        viewModel.setKeyword("ab")
        viewModel.setKeyword("abc")
        advanceTimeBy(249)
        runCurrent()
        assertEquals(emptyList<LibraryQuery>(), repository.queries)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("abc"), repository.queries.map(LibraryQuery::normalizedKeyword))
        collectJob.cancel()
    }

    @Test
    fun `load more appends pages until the final cursor`() = runTest {
        val firstItems = (0 until 60).map(::media)
        val nextItems = (60 until 75).map(::media)
        val nextCursor = LibraryCursor(
            LibrarySortField.RECENTLY_ADDED,
            SortDirection.DESCENDING,
            firstItems.last().id,
            longValue = 59L,
        )
        val repository = FakeLibraryRepository(
            firstPage = LibraryPage(firstItems, nextCursor, 75),
            nextPage = LibraryPage(nextItems, null, 75),
        )
        val viewModel = LibraryViewModel(
            repository,
            FakePreferenceRepository(),
            FakeMutationRepository(),
            FakeTrashRepository(),
        )
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        advanceTimeBy(250)
        advanceUntilIdle()

        assertEquals(60, viewModel.state.value.items.size)
        assertEquals(true, viewModel.state.value.hasMore)

        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(75, viewModel.state.value.items.size)
        assertEquals(false, viewModel.state.value.hasMore)
        assertEquals(listOf(nextCursor), repository.pageQueries.map(LibraryQuery::cursor))

        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(1, repository.pageQueries.size)
        collectJob.cancel()
    }

    private class FakeLibraryRepository(
        firstPage: LibraryPage = LibraryPage(emptyList(), null, 0),
        private val nextPage: LibraryPage = LibraryPage(emptyList(), null, 0),
    ) : LibraryRepository {
        val queries = mutableListOf<LibraryQuery>()
        val pageQueries = mutableListOf<LibraryQuery>()
        private val observed = MutableStateFlow<LibraryResult<LibraryPage>>(LibraryResult.Success(firstPage))

        override fun observe(query: LibraryQuery): Flow<LibraryResult<LibraryPage>> {
            queries += query
            return observed
        }
        override suspend fun query(query: LibraryQuery): LibraryResult<LibraryPage> {
            pageQueries += query
            return LibraryResult.Success(nextPage)
        }
    }

    private class FakePreferenceRepository : LibraryPreferenceRepository {
        override val preference = MutableStateFlow(LibraryDisplayPreference())
        override suspend fun setViewMode(mode: LibraryViewMode) {
            preference.value = preference.value.copy(viewMode = mode)
        }
        override suspend fun setThumbnailScale(scale: Float) {
            preference.value = preference.value.copy(thumbnailScale = scale)
        }
        override suspend fun setSort(sort: seeyuer.yingli.player.domain.library.SortSpec) {
            preference.value = preference.value.copy(sort = sort)
        }
    }

    private class FakeMutationRepository : LibraryMutationRepository {
        override suspend fun trash(items: List<seeyuer.yingli.player.domain.library.LibraryMedia>) =
            BatchOperationSummary(items.size, emptyMap())
        override suspend fun restore(entry: TrashEntry): FileOperationResult = FileOperationResult.Success(entry.originalUri)
        override suspend fun purge(entry: TrashEntry): FileOperationResult = FileOperationResult.Success(entry.originalUri)
    }

    private class FakeTrashRepository : seeyuer.yingli.player.domain.library.TrashRepository {
        override fun observe() = MutableStateFlow<List<TrashEntry>>(emptyList())
        override suspend fun put(entry: TrashEntry) = Unit
        override suspend fun updateState(mediaId: seeyuer.yingli.player.core.model.media.MediaItemId, state: seeyuer.yingli.player.domain.library.TrashState) = Unit
        override suspend fun remove(mediaId: seeyuer.yingli.player.core.model.media.MediaItemId) = Unit
        override suspend fun expired(nowEpochMillis: Long) = emptyList<TrashEntry>()
    }

    private companion object {
        fun media(index: Int) = LibraryMedia(
            id = MediaItemId("media_$index"),
            locationId = MediaLocationId("location_$index"),
            uri = MediaUri("content://media/external/video/media/$index"),
            title = "movie_$index",
            fileName = "movie_$index.mp4",
            folderAlias = "设备存储",
            extension = "mp4",
            durationMillis = 60_000,
            width = 1_920,
            height = 1_080,
            modifiedEpochMillis = index.toLong(),
            playbackPositionMillis = 0,
            completed = false,
        )
    }
}
