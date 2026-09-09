package seeyuer.yingli.player.feature.library

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import seeyuer.yingli.player.domain.library.BatchOperationSummary
import seeyuer.yingli.player.domain.library.FileOperationResult
import seeyuer.yingli.player.domain.library.LibraryDisplayPreference
import seeyuer.yingli.player.domain.library.LibraryMutationRepository
import seeyuer.yingli.player.domain.library.LibraryPagingRepository
import seeyuer.yingli.player.domain.library.LibraryPage
import seeyuer.yingli.player.domain.library.LibraryPageDirection
import seeyuer.yingli.player.domain.library.LibraryPreferenceRepository
import seeyuer.yingli.player.domain.library.LibraryQuery
import seeyuer.yingli.player.domain.library.LibraryResult
import seeyuer.yingli.player.domain.library.LibraryViewMode
import seeyuer.yingli.player.domain.library.BreadcrumbMode
import seeyuer.yingli.player.domain.library.LibraryPathSegment
import seeyuer.yingli.player.domain.library.LibraryBrowseMode
import seeyuer.yingli.player.domain.library.LibraryDisplayFields
import seeyuer.yingli.player.domain.library.SortSpec
import seeyuer.yingli.player.domain.library.LibraryFolder
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.core.model.media.MediaItemId
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
        repository.countQueries.clear()

        viewModel.setKeyword("a")
        viewModel.setKeyword("ab")
        viewModel.setKeyword("abc")
        advanceTimeBy(249)
        runCurrent()
        assertEquals(emptyList<LibraryQuery>(), repository.countQueries)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("abc"), repository.countQueries.map(LibraryQuery::normalizedKeyword))
        collectJob.cancel()
    }

    @Test
    fun `quick settings persist independent folder and video columns`() = runTest {
        val preferences = FakePreferenceRepository()
        val viewModel = LibraryViewModel(FakeLibraryRepository(), preferences, FakeMutationRepository(), FakeTrashRepository())

        viewModel.applyQuickSettings(
            LibraryBrowseMode.FOLDER,
            LibraryViewMode.GRID,
            SortSpec(),
            LibraryDisplayFields(),
            breadcrumbMode = BreadcrumbMode.SCROLL,
            folderColumns = 4,
            videoColumns = 5,
        )
        runCurrent()

        assertEquals(4, preferences.preference.value.folderColumns)
        assertEquals(5, preferences.preference.value.videoColumns)
        assertEquals(BreadcrumbMode.SCROLL, preferences.preference.value.breadcrumbMode)
    }

    @Test
    fun `folder header count includes current folder descendants`() = runTest {
        val repository = FakeLibraryRepository(folderTreeCount = 7)
        val viewModel = LibraryViewModel(repository, FakePreferenceRepository(), FakeMutationRepository(), FakeTrashRepository())

        viewModel.enterFolder(LibraryPathSegment("DCIM", "DCIM"))
        val state = viewModel.state.first { it.folderTreeVideoCount == 7 }

        assertEquals(listOf("DCIM"), repository.folderTreeCountPaths)
        assertEquals(7, state.folderTreeVideoCount)
    }

    @Test
    fun `all videos mode does not query folders`() = runTest {
        val repository = FakeLibraryRepository()
        val viewModel = LibraryViewModel(repository, FakePreferenceRepository(), FakeMutationRepository(), FakeTrashRepository())
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        runCurrent()
        repository.folderQueries.clear()

        viewModel.setBrowseMode(LibraryBrowseMode.ALL_VIDEOS)
        runCurrent()

        assertEquals(emptyList<LibraryQuery>(), repository.folderQueries)
        collectJob.cancel()
    }

    @Test
    fun `whitespace keyword still queries folders as empty search`() = runTest {
        val repository = FakeLibraryRepository()
        val viewModel = LibraryViewModel(repository, FakePreferenceRepository(), FakeMutationRepository(), FakeTrashRepository())
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        runCurrent()
        repository.folderQueries.clear()

        viewModel.setKeyword("   ")
        advanceTimeBy(250)
        runCurrent()

        assertEquals(1, repository.folderQueries.size)
        assertEquals("", repository.folderQueries.single().normalizedKeyword)
        collectJob.cancel()
    }

    private class FakeLibraryRepository(
        private val firstPage: LibraryPage = LibraryPage(emptyList(), null, 0),
        private val folderTreeCount: Int = 0,
    ) : LibraryPagingRepository {
        val pageQueries = mutableListOf<LibraryQuery>()
        val countQueries = mutableListOf<LibraryQuery>()
        val folderTreeCountPaths = mutableListOf<String>()
        val folderQueries = mutableListOf<LibraryQuery>()

        override fun observe(query: LibraryQuery): Flow<LibraryResult<LibraryPage>> {
            return flowOf(LibraryResult.Success(firstPage))
        }
        override suspend fun query(query: LibraryQuery): LibraryResult<LibraryPage> {
            return LibraryResult.Success(page(query))
        }
        override suspend fun page(query: LibraryQuery, direction: LibraryPageDirection): LibraryPage {
            pageQueries += query
            return firstPage
        }
        override fun observeCount(query: LibraryQuery): Flow<Int> {
            countQueries += query
            return flowOf(firstPage.totalCount)
        }
        override fun observeFolderTreeVideoCount(path: String): Flow<Int> {
            folderTreeCountPaths += path
            return flowOf(folderTreeCount)
        }
        override fun observeInvalidations(): Flow<Unit> = emptyFlow()
        override suspend fun folders(query: LibraryQuery): List<LibraryFolder> {
            folderQueries += query
            return emptyList()
        }
        override suspend fun findByIds(ids: Set<MediaItemId>): List<LibraryMedia> = emptyList()
    }

    private class FakePreferenceRepository : LibraryPreferenceRepository {
        override val preference = MutableStateFlow(LibraryDisplayPreference())
        override suspend fun setViewMode(mode: LibraryViewMode) {
            preference.value = preference.value.copy(viewMode = mode)
        }
        override suspend fun setBreadcrumbMode(mode: BreadcrumbMode) {
            preference.value = preference.value.copy(breadcrumbMode = mode)
        }
        override suspend fun setThumbnailScale(scale: Float) {
            preference.value = preference.value.copy(thumbnailScale = scale)
        }
        override suspend fun setSort(sort: seeyuer.yingli.player.domain.library.SortSpec) {
            preference.value = preference.value.copy(sort = sort)
        }
        override suspend fun setFolderColumns(columns: Int) {
            preference.value = preference.value.copy(folderColumns = columns)
        }
        override suspend fun setVideoColumns(columns: Int) {
            preference.value = preference.value.copy(videoColumns = columns)
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

}
