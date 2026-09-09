package seeyuer.yingli.player.feature.library

import androidx.paging.PagingSource
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.testing.TestPager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.library.LibraryCursor
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryPage
import seeyuer.yingli.player.domain.library.LibraryPageDirection
import seeyuer.yingli.player.domain.library.LibraryPagingRepository
import seeyuer.yingli.player.domain.library.LibraryQuery
import seeyuer.yingli.player.domain.library.LibraryResult
import seeyuer.yingli.player.domain.library.LibrarySortField
import seeyuer.yingli.player.domain.library.SortDirection
import seeyuer.yingli.player.domain.library.SortSpec
import seeyuer.yingli.player.domain.library.cursorFor

class LibraryPagingSourceTest {
    @Test
    fun `refresh and append use repository keyset cursor`() = runBlocking {
        val first = media(0)
        val second = media(1)
        val cursor = LibraryCursor(LibrarySortField.NAME, SortDirection.ASCENDING, first.id, textValue = first.title)
        val repository = FakeRepository(
            LibraryPage(listOf(first), cursor, 2),
            LibraryPage(listOf(second), null, 2),
        )
        val source = LibraryPagingSource(repository, LibraryQuery(sort = SortSpec(LibrarySortField.NAME, SortDirection.ASCENDING)), CoroutineScope(Dispatchers.Unconfined))

        val refresh = source.load(PagingSource.LoadParams.Refresh(null, 1, false)) as PagingSource.LoadResult.Page
        val append = source.load(PagingSource.LoadParams.Append(requireNotNull(refresh.nextKey), 1, false)) as PagingSource.LoadResult.Page

        assertTrue(repository.queries[0].cursor == null)
        assertTrue(repository.queries[1].cursor == cursor)
        assertEquals(listOf(LibraryPageDirection.REFRESH, LibraryPageDirection.APPEND), repository.directions)
        assertEquals(listOf(first), refresh.data)
        assertEquals(listOf(second), append.data)
        assertEquals(null, append.nextKey)
    }

    @Test
    fun `test pager walks keyset pages without exposing sentinel rows`() = runBlocking {
        val first = media(0)
        val second = media(1)
        val cursor = LibraryCursor(LibrarySortField.NAME, SortDirection.ASCENDING, first.id, textValue = first.title)
        val repository = FakeRepository(
            LibraryPage(listOf(first), cursor, 2),
            LibraryPage(listOf(second), null, 2),
        )
        val pager = TestPager(
            PagingConfig(pageSize = 1, enablePlaceholders = false),
            LibraryPagingSource(
                repository,
                LibraryQuery(sort = SortSpec(LibrarySortField.NAME, SortDirection.ASCENDING)),
                CoroutineScope(Dispatchers.Unconfined),
            ),
        )

        val refresh = pager.refresh() as PagingSource.LoadResult.Page
        val append = pager.append() as PagingSource.LoadResult.Page

        assertEquals(listOf(first), refresh.data)
        assertEquals(listOf(second), append.data)
        assertEquals(null, append.nextKey)
    }

    @Test
    fun `repository failure becomes paging error`() = runBlocking {
        val source = LibraryPagingSource(FailingRepository, LibraryQuery(), CoroutineScope(Dispatchers.Unconfined))
        assertTrue(source.load(PagingSource.LoadParams.Refresh(null, 1, false)) is PagingSource.LoadResult.Error)
    }

    @Test
    fun `prepend reloads a page dropped before the current window`() = runBlocking {
        val before = media(0)
        val current = media(1)
        val beforeCursor = before.cursorFor(SortSpec())
        val repository = FakeRepository(
            LibraryPage(listOf(current), null, 2, previousCursor = beforeCursor),
            LibraryPage(listOf(before), null, 2),
        )
        val source = LibraryPagingSource(
            repository,
            LibraryQuery(),
            CoroutineScope(Dispatchers.Unconfined),
        )

        val refresh = source.load(PagingSource.LoadParams.Refresh(beforeCursor, 1, false)) as PagingSource.LoadResult.Page
        val prepend = source.load(PagingSource.LoadParams.Prepend(requireNotNull(refresh.prevKey), 1, false)) as PagingSource.LoadResult.Page

        assertEquals(listOf(LibraryPageDirection.REFRESH, LibraryPageDirection.PREPEND), repository.directions)
        assertEquals(listOf(before), prepend.data)
        assertEquals(null, prepend.prevKey)
    }

    @Test
    fun `database invalidation invalidates the paging source`() = runBlocking {
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val repository = FakeRepository(
            LibraryPage(emptyList(), null, 0),
            invalidations = invalidations,
        )
        val source = LibraryPagingSource(repository, LibraryQuery(), CoroutineScope(Dispatchers.Unconfined))

        invalidations.emit(Unit)

        assertTrue(source.invalid)
    }

    @Test
    fun `refresh key keeps first page at the beginning`() {
        val first = media(0)
        val second = media(1)
        val source = LibraryPagingSource(
            FakeRepository(LibraryPage(emptyList(), null, 0)),
            LibraryQuery(),
            CoroutineScope(Dispatchers.Unconfined),
        )
        val state = PagingState(
            pages = listOf(
                PagingSource.LoadResult.Page(listOf(first), null, LibraryCursor(
                    LibrarySortField.RECENTLY_ADDED,
                    SortDirection.DESCENDING,
                    first.id,
                    longValue = first.modifiedEpochMillis,
                )),
                PagingSource.LoadResult.Page(listOf(second), LibraryCursor(
                    LibrarySortField.RECENTLY_ADDED,
                    SortDirection.DESCENDING,
                    first.id,
                    longValue = first.modifiedEpochMillis,
                ), null),
            ),
            anchorPosition = 0,
            config = PagingConfig(pageSize = 1),
            leadingPlaceholderCount = 0,
        )
        assertEquals(null, source.getRefreshKey(state))
    }

    private class FakeRepository(
        private vararg val pages: LibraryPage,
        private val invalidations: kotlinx.coroutines.flow.Flow<Unit> = emptyFlow(),
    ) : LibraryPagingRepository {
        val queries = mutableListOf<LibraryQuery>()
        val directions = mutableListOf<LibraryPageDirection>()
        override fun observe(query: LibraryQuery) = flowOf(LibraryResult.Success(pages.first()))
        override suspend fun query(query: LibraryQuery) = LibraryResult.Success(page(query))
        override suspend fun page(query: LibraryQuery, direction: LibraryPageDirection): LibraryPage {
            queries += query
            directions += direction
            return pages[queries.size - 1]
        }
        override fun observeCount(query: LibraryQuery) = flowOf(2)
        override fun observeFolderTreeVideoCount(path: String) = flowOf(0)
        override fun observeInvalidations() = invalidations
    }

    private object FailingRepository : LibraryPagingRepository {
        override fun observe(query: LibraryQuery) = emptyFlow<LibraryResult<LibraryPage>>()
        override suspend fun query(query: LibraryQuery) = LibraryResult.RetryableFailure
        override suspend fun page(query: LibraryQuery, direction: LibraryPageDirection): LibraryPage = error("database unavailable")
        override fun observeCount(query: LibraryQuery) = flowOf(0)
        override fun observeFolderTreeVideoCount(path: String) = flowOf(0)
        override fun observeInvalidations() = emptyFlow<Unit>()
    }

    private companion object {
        fun media(index: Int) = LibraryMedia(
            id = MediaItemId("media_$index"), locationId = MediaLocationId("location_$index"),
            uri = MediaUri("content://media/$index"), title = "movie_$index", fileName = "movie_$index.mp4",
            folderAlias = "设备存储", extension = "mp4", durationMillis = 60_000, width = 1_920, height = 1_080,
            modifiedEpochMillis = index.toLong(), playbackPositionMillis = 0, completed = false,
        )
    }
}
