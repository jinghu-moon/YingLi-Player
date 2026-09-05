package seeyuer.yingli.player.feature.library

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import seeyuer.yingli.player.domain.library.LibraryCursor
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryPageDirection
import seeyuer.yingli.player.domain.library.LibraryPagingRepository
import seeyuer.yingli.player.domain.library.LibraryQuery
import seeyuer.yingli.player.domain.library.cursorFor

/** Paging adapter over the repository's database-level keyset query. */
class LibraryPagingSource(
    private val repository: LibraryPagingRepository,
    private val baseQuery: LibraryQuery,
    scope: CoroutineScope,
) : PagingSource<LibraryCursor, LibraryMedia>() {
    private val invalidationJob: Job = scope.launch {
        repository.observeInvalidations().collect {
            invalidate()
        }
    }

    init {
        registerInvalidatedCallback { invalidationJob.cancel() }
    }

    override suspend fun load(params: LoadParams<LibraryCursor>): LoadResult<LibraryCursor, LibraryMedia> {
        return try {
            val cursor = params.key
            val direction = when (params) {
                is LoadParams.Refresh -> LibraryPageDirection.REFRESH
                is LoadParams.Append -> LibraryPageDirection.APPEND
                is LoadParams.Prepend -> LibraryPageDirection.PREPEND
            }
            val page = repository.page(baseQuery.copy(cursor = cursor), direction)
            LoadResult.Page(
                data = page.items,
                prevKey = page.previousCursor,
                nextKey = page.nextCursor,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            LoadResult.Error(error)
        }
    }

    override fun getRefreshKey(state: PagingState<LibraryCursor, LibraryMedia>): LibraryCursor? {
        val anchor = state.anchorPosition ?: return null
        val page = state.closestPageToPosition(anchor)
        // The page's prevKey is its first item cursor. The repository treats a
        // keyed refresh as inclusive at that boundary, so the anchored page is
        // restored without skipping its first item. A first page has no key and
        // must refresh from the beginning; using nextKey would skip it.
        page?.prevKey?.let { return it }
        // A null prevKey means the first page; refreshing from nextKey would
        // skip the first page entirely.
        return if (page == null) state.closestItemToPosition(anchor)?.cursorFor(baseQuery.sort) else null
    }
}
