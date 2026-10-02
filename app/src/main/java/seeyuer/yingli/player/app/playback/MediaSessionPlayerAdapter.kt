package seeyuer.yingli.player.app.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.playback.PlaybackOpenRequest
import seeyuer.yingli.player.domain.playback.PlaybackOrder
import seeyuer.yingli.player.domain.playback.PlaybackQueueSnapshot
import seeyuer.yingli.player.domain.playback.PlaybackSessionCommand
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.SeekOrigin
import seeyuer.yingli.player.engine.media3.PlaybackMediaMetadata

/** Routes MediaSession transport mutations through the service-owned runtime. */
@OptIn(UnstableApi::class)
class MediaSessionPlayerAdapter(
    player: Player,
    private val runtime: PlaybackSessionRuntime,
    private val sourceRegistry: SourceHandleRegistry,
) : ForwardingPlayer(player) {
    override fun setMediaItem(mediaItem: MediaItem) = open(mediaItem, 0)
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = open(mediaItem, startPositionMs)
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) =
        open(mediaItem, if (resetPosition) 0 else currentPosition.coerceAtLeast(0))

    override fun setMediaItems(mediaItems: List<MediaItem>) = setMediaItems(mediaItems, true)

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        val start = if (resetPosition) 0 else currentPosition.coerceAtLeast(0)
        setMediaItems(mediaItems, 0, start)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        val ids = mediaItems.mapNotNull { item -> mediaId(item) }
        if (ids.isEmpty() || startIndex !in ids.indices) {
            runtime.dispatch(PlaybackSessionCommand.Stop)
            return
        }
        runtime.setQueue(
            PlaybackQueueSnapshot(
                queueId = "media-session",
                mediaIds = ids,
                currentIndex = startIndex,
                order = PlaybackOrder.SEQUENCE,
            ),
        )
        open(mediaItems[startIndex], startPositionMs)
    }

    override fun clearMediaItems() {
        runtime.setQueue(null)
        runtime.dispatch(PlaybackSessionCommand.Stop)
    }

    override fun prepare() = Unit
    override fun play() { runtime.dispatch(PlaybackSessionCommand.Play) }
    override fun pause() { runtime.dispatch(PlaybackSessionCommand.Pause) }
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        runtime.dispatch(if (playWhenReady) PlaybackSessionCommand.Play else PlaybackSessionCommand.Pause)
    }
    override fun stop() { runtime.dispatch(PlaybackSessionCommand.Stop) }
    override fun seekTo(positionMs: Long) {
        runtime.dispatch(PlaybackSessionCommand.Seek(positionMs, SeekOrigin.USER))
    }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex == currentMediaItemIndex) seekTo(positionMs)
        else runtime.dispatch(PlaybackSessionCommand.Next)
    }
    override fun seekBack() { runtime.dispatch(PlaybackSessionCommand.SeekBy(-seekBackIncrement)) }
    override fun seekForward() { runtime.dispatch(PlaybackSessionCommand.SeekBy(seekForwardIncrement)) }
    override fun seekToPreviousMediaItem() { runtime.dispatch(PlaybackSessionCommand.Previous) }
    override fun seekToPrevious() { runtime.dispatch(PlaybackSessionCommand.Previous) }
    override fun seekToNextMediaItem() { runtime.dispatch(PlaybackSessionCommand.Next) }
    override fun seekToNext() { runtime.dispatch(PlaybackSessionCommand.Next) }
    override fun setPlaybackSpeed(speed: Float) {
        val value = runCatching { PlaybackSpeed.of(speed) }.getOrNull() ?: return
        runtime.dispatch(PlaybackSessionCommand.SetSpeed(value))
    }
    override fun setRepeatMode(repeatMode: Int) {
        val order = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> PlaybackOrder.SINGLE_REPEAT
            Player.REPEAT_MODE_ALL -> PlaybackOrder.QUEUE_REPEAT
            else -> PlaybackOrder.SEQUENCE
        }
        runtime.dispatch(PlaybackSessionCommand.SetOrder(order))
    }

    private fun open(item: MediaItem, startPositionMs: Long) {
        val mediaId = mediaId(item) ?: return
        val sessionId = runtime.snapshot.value.sessionId ?: return
        sourceRegistry.register(item)
        val extras = item.mediaMetadata.extras
        val context = extras?.getString(PlaybackMediaMetadata.SOURCE_CONTEXT)
            ?.let { runCatching { PlaybackSourceContext.valueOf(it) }.getOrNull() }
            ?: PlaybackSourceContext.HOME
        runtime.dispatch(
            PlaybackSessionCommand.Open(
                PlaybackOpenRequest(
                    sessionId = sessionId,
                    mediaId = mediaId,
                    sourceContext = context,
                    startPositionMillis = startPositionMs.coerceAtLeast(0),
                    incognito = extras?.getBoolean(PlaybackMediaMetadata.INCOGNITO, false) == true,
                ),
            ),
        )
    }

    private fun mediaId(item: MediaItem): MediaItemId? =
        runCatching { MediaItemId(item.mediaId) }.getOrNull()
}
