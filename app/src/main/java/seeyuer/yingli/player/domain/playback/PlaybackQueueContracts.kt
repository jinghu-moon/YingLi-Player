package seeyuer.yingli.player.domain.playback

import kotlinx.coroutines.flow.Flow
import seeyuer.yingli.player.core.model.media.MediaItemId

data class PlaybackQueue(
    val mediaIds: List<MediaItemId>,
    val currentIndex: Int,
    val continuousPlayback: Boolean = false,
) {
    init {
        require(mediaIds.isNotEmpty())
        require(mediaIds.distinct().size == mediaIds.size)
        require(currentIndex in mediaIds.indices)
    }

    val current: MediaItemId get() = mediaIds[currentIndex]
    fun next(): MediaItemId? = if (continuousPlayback) mediaIds.getOrNull(currentIndex + 1) else null
}

interface PlaybackQueueRepository {
    val queue: Flow<PlaybackQueue?>
    suspend fun setQueue(queue: PlaybackQueue?)
}
