package seeyuer.yingli.player.domain.playback

import kotlinx.coroutines.flow.Flow
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.library.LibraryBrowseMode
import seeyuer.yingli.player.domain.library.LibraryQuery

data class PlaybackQueue(
    val mediaIds: List<MediaItemId>,
    val currentIndex: Int,
    val continuousPlayback: Boolean = false,
    val order: PlaybackOrder = PlaybackOrder.SEQUENCE,
    val shuffleHistory: List<Int> = emptyList(),
) {
    init {
        require(mediaIds.isNotEmpty())
        require(mediaIds.distinct().size == mediaIds.size)
        require(currentIndex in mediaIds.indices)
        require(shuffleHistory.all { it in mediaIds.indices })
    }

    val current: MediaItemId get() = mediaIds[currentIndex]
    fun next(): MediaItemId? = if (continuousPlayback) mediaIds.getOrNull(currentIndex + 1) else null
}

interface PlaybackQueueRepository {
    val queue: Flow<PlaybackQueue?>
    suspend fun setQueue(queue: PlaybackQueue?)
}

/** Describes the library scope used to build a player's queue. */
data class PlaybackQueueSource(
    val browseMode: LibraryBrowseMode,
    val currentPath: String = "",
) {
    init {
        require(browseMode == LibraryBrowseMode.ALL_VIDEOS || currentPath.isNotBlank())
    }

    fun toQuery(): LibraryQuery = LibraryQuery(
        pageSize = LibraryQuery.MAX_PAGE_SIZE,
        browseMode = browseMode,
        currentPath = currentPath,
        includeDescendants = browseMode == LibraryBrowseMode.FOLDER,
    )

    companion object {
        fun allVideos() = PlaybackQueueSource(LibraryBrowseMode.ALL_VIDEOS)
        fun folderTree(path: String) = PlaybackQueueSource(LibraryBrowseMode.FOLDER, path.trim('/'))
            .also { require(it.currentPath.isNotBlank()) }
    }
}
