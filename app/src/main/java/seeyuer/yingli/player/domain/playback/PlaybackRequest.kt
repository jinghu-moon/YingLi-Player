package seeyuer.yingli.player.domain.playback

import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId

enum class PlaybackSourceContext {
    HOME,
    LIBRARY,
    DETAIL,
}

data class PlaybackRequest(
    val mediaId: MediaItemId,
    val locationId: MediaLocationId,
    val startPositionMillis: Long,
    val sourceContext: PlaybackSourceContext,
    val incognito: Boolean = false,
) {
    init {
        require(startPositionMillis >= 0)
    }
}

data class ResolvedPlaybackSource(
    val request: PlaybackRequest,
    val uri: String,
    val title: String,
    val durationMillis: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fileSizeBytes: Long? = null,
    val mimeType: String? = null,
)

interface PlaybackSourceRepository {
    suspend fun resolve(
        mediaId: MediaItemId,
        sourceContext: PlaybackSourceContext,
        incognito: Boolean = false,
    ): ResolvedPlaybackSource?

    suspend fun resolve(request: PlaybackRequest): ResolvedPlaybackSource?
}

interface PlaybackProgressRepository {
    suspend fun saveProgress(
        mediaId: MediaItemId,
        positionMillis: Long,
        completed: Boolean,
    )
}
