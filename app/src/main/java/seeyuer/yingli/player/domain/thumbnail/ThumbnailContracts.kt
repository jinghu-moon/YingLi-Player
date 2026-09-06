package seeyuer.yingli.player.domain.thumbnail

import kotlinx.coroutines.flow.Flow
import seeyuer.yingli.player.core.model.media.ScrollDirection
import seeyuer.yingli.player.core.model.media.ThumbnailKey
import seeyuer.yingli.player.core.model.media.ThumbnailRequest

sealed interface ThumbnailState {
    data object Queued : ThumbnailState
    data object Loading : ThumbnailState
    data class Ready(val key: ThumbnailKey) : ThumbnailState
    data object Failed : ThumbnailState
}

interface ThumbnailRepository {
    fun observe(key: ThumbnailKey): Flow<ThumbnailState>
    fun enqueue(request: ThumbnailRequest)
    fun cancel(key: ThumbnailKey)
}

interface ThumbnailLoader {
    fun observe(request: ThumbnailRequest): Flow<ThumbnailState>
    fun request(request: ThumbnailRequest)
    fun cancel(request: ThumbnailRequest)
    fun requestVisible(requests: List<ThumbnailRequest>)
    fun prefetch(requests: List<ThumbnailRequest>, direction: ScrollDirection, generation: Long)
    fun cancelPrefetch(generation: Long)
}
