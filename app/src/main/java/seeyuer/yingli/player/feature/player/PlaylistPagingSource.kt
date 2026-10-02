package seeyuer.yingli.player.feature.player

import androidx.paging.PagingState
import androidx.paging.PagingSource
import kotlinx.coroutines.CancellationException
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryPagingRepository

data class PlaylistMediaItem(
    val index: Int,
    val mediaId: MediaItemId,
    val media: LibraryMedia?,
)

/** 按播放队列索引增量加载媒体详情，避免一次查询整个播放列表。 */
internal class PlaylistPagingSource(
    private val queueIds: List<MediaItemId>,
    private val repository: LibraryPagingRepository,
) : PagingSource<Int, PlaylistMediaItem>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, PlaylistMediaItem> {
        val start = params.key ?: 0
        if (start >= queueIds.size) return LoadResult.Page(emptyList(), null, null)
        val end = (start + params.loadSize).coerceAtMost(queueIds.size)
        return try {
            val ids = queueIds.subList(start, end)
            val mediaById = repository.findByIds(ids.toSet()).associateBy { it.id }
            LoadResult.Page(
                data = ids.mapIndexed { offset, id -> PlaylistMediaItem(start + offset, id, mediaById[id]) },
                prevKey = if (start == 0) null else (start - params.loadSize).coerceAtLeast(0),
                nextKey = end.takeIf { it < queueIds.size },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            LoadResult.Error(error)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, PlaylistMediaItem>): Int? =
        state.anchorPosition?.let { position ->
            state.closestPageToPosition(position)?.prevKey
                ?: state.closestPageToPosition(position)?.nextKey
        }
}
