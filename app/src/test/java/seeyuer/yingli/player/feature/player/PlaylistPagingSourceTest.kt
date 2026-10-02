package seeyuer.yingli.player.feature.player

import androidx.paging.PagingSource
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryPage
import seeyuer.yingli.player.domain.library.LibraryPageDirection
import seeyuer.yingli.player.domain.library.LibraryPagingRepository
import seeyuer.yingli.player.domain.library.LibraryQuery
import seeyuer.yingli.player.domain.library.LibraryResult

class PlaylistPagingSourceTest {
    @Test
    fun `load keeps queue order and only queries the requested page`() = runBlocking {
        val first = media("first")
        val second = media("second")
        val third = media("third")
        val repository = FakeRepository(listOf(first, second, third))
        val source = PlaylistPagingSource(
            queueIds = listOf(third.id, first.id, second.id),
            repository = repository,
        )

        val refresh = source.load(PagingSource.LoadParams.Refresh(null, 2, false))
            as PagingSource.LoadResult.Page
        val append = source.load(
            PagingSource.LoadParams.Append(requireNotNull(refresh.nextKey), 2, false),
        ) as PagingSource.LoadResult.Page

        assertEquals(listOf("third.mp4", "first.mp4"), refresh.data.map { it.media?.fileName })
        assertEquals(listOf("second.mp4"), append.data.map { it.media?.fileName })
        assertEquals(listOf(setOf(third.id, first.id), setOf(second.id)), repository.requests)
        assertEquals(null, append.nextKey)
    }

    @Test
    fun `missing media remains in its queue position as an unavailable item`() = runBlocking {
        val available = media("available")
        val missingId = MediaItemId("missing")
        val source = PlaylistPagingSource(
            queueIds = listOf(missingId, available.id),
            repository = FakeRepository(listOf(available)),
        )

        val result = source.load(PagingSource.LoadParams.Refresh(null, 10, false))
            as PagingSource.LoadResult.Page

        assertEquals(listOf(0, 1), result.data.map { it.index })
        assertEquals(missingId, result.data[0].mediaId)
        assertNull(result.data[0].media)
        assertEquals(available, result.data[1].media)
    }

    private class FakeRepository(
        private val media: List<LibraryMedia>,
    ) : LibraryPagingRepository {
        val requests = mutableListOf<Set<MediaItemId>>()

        override fun observe(query: LibraryQuery) = emptyFlow<LibraryResult<LibraryPage>>()

        override suspend fun query(query: LibraryQuery) = LibraryResult.Success(
            LibraryPage(emptyList(), null, 0),
        )

        override suspend fun page(query: LibraryQuery, direction: LibraryPageDirection) =
            LibraryPage(emptyList(), null, 0)

        override fun observeCount(query: LibraryQuery) = flowOf(0)

        override fun observeFolderTreeVideoCount(path: String) = flowOf(0)

        override fun observeInvalidations() = emptyFlow<Unit>()

        override suspend fun findByIds(ids: Set<MediaItemId>): List<LibraryMedia> {
            requests += ids
            return media.filter { it.id in ids }
        }
    }

    private companion object {
        fun media(id: String) = LibraryMedia(
            id = MediaItemId(id),
            locationId = MediaLocationId("location_$id"),
            uri = MediaUri("content://media/$id"),
            title = "$id title",
            fileName = "$id.mp4",
            folderAlias = "Movies",
            extension = "mp4",
            durationMillis = 60_000,
            width = 1_920,
            height = 1_080,
            modifiedEpochMillis = 1L,
            playbackPositionMillis = 0,
            completed = false,
            sizeBytes = 100L,
        )
    }
}
