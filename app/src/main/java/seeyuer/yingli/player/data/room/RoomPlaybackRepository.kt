package seeyuer.yingli.player.data.room

import androidx.room.withTransaction
import seeyuer.yingli.player.data.room.YingLiDatabase
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.playback.PlaybackProgressRepository
import seeyuer.yingli.player.domain.playback.PlaybackRequest
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackSourceRepository
import seeyuer.yingli.player.domain.playback.ResolvedPlaybackSource
import seeyuer.yingli.player.domain.playback.ResumePlaybackPolicy

class RoomPlaybackRepository(
    private val database: YingLiDatabase,
    private val resumePolicy: ResumePlaybackPolicy = ResumePlaybackPolicy(),
) : PlaybackSourceRepository, PlaybackProgressRepository {
    private val dao = database.mediaCatalogDao()

    override suspend fun resolve(
        mediaId: MediaItemId,
        sourceContext: PlaybackSourceContext,
        incognito: Boolean,
    ): ResolvedPlaybackSource? = database.withTransaction {
        val item = dao.item(mediaId.value) ?: return@withTransaction null
        val location = dao.playableLocation(mediaId.value) ?: return@withTransaction null
        val request = PlaybackRequest(
            mediaId = mediaId,
            locationId = seeyuer.yingli.player.core.model.media.MediaLocationId(location.id),
            startPositionMillis = resumePolicy.startPosition(
                item.playbackPositionMillis,
                location.durationMillis,
                item.completed,
            ),
            sourceContext = sourceContext,
            incognito = incognito,
        )
        ResolvedPlaybackSource(request, location.uri, item.title)
    }

    override suspend fun resolve(request: PlaybackRequest): ResolvedPlaybackSource? = database.withTransaction {
        val item = dao.item(request.mediaId.value) ?: return@withTransaction null
        val location = dao.playableLocationById(request.locationId.value) ?: return@withTransaction null
        ResolvedPlaybackSource(request, location.uri, item.title)
    }

    override suspend fun saveProgress(mediaId: MediaItemId, positionMillis: Long, completed: Boolean) {
        dao.updatePlaybackProgress(mediaId.value, positionMillis.coerceAtLeast(0), completed)
    }
}
