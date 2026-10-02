package seeyuer.yingli.player.engine.media3

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.playback.DefaultPlaybackErrorMapper
import seeyuer.yingli.player.domain.playback.PlaybackRequest
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackState
import seeyuer.yingli.player.domain.playback.PlaybackTimeline
import seeyuer.yingli.player.domain.playback.PlaybackFailureSignal
import seeyuer.yingli.player.domain.playback.TrackChoice

internal fun Player.toPlaybackTimeline(): PlaybackTimeline = PlaybackTimeline(
    positionMillis = currentPosition.coerceAtLeast(0),
    durationMillis = duration.takeUnless { it == C.TIME_UNSET || it < 0 },
    bufferedPositionMillis = bufferedPosition.coerceAtLeast(0),
)

internal fun PlaybackState.withPlayerState(
    player: Player,
    request: PlaybackRequest,
    hasEverBeenReady: Boolean,
): PlaybackState {
    val timeline = player.toPlaybackTimeline()
    return when (player.playbackState) {
        Player.STATE_IDLE -> this
        // 曾经就绪过 ⇒ 这次 BUFFERING 是"重缓冲"（seek/缓冲不足），不是首次准备。
        // 必须带上 isRebuffering：否则进度条跳转、快进快退都会让 UI 闪一个全屏加载圈。
        Player.STATE_BUFFERING -> PlaybackState.Preparing(
            request = request,
            timeline = timeline,
            isRebuffering = hasEverBeenReady,
        )
        Player.STATE_READY -> when {
            player.isPlaying -> PlaybackState.Playing(request, timeline)
            this is PlaybackState.Paused -> PlaybackState.Paused(request, timeline)
            else -> PlaybackState.Ready(request, timeline)
        }
        Player.STATE_ENDED -> PlaybackState.Ended(request, timeline)
        else -> this
    }
}

internal fun Player.trackChoices(type: Int, prefix: String): List<TrackChoice> = buildList {
    currentTracks.groups.filter { it.type == type }.forEachIndexed { groupIndex, group ->
        repeat(group.length) { trackIndex ->
            val format = group.getTrackFormat(trackIndex)
            add(
                TrackChoice(
                    id = format.id ?: "$prefix-$groupIndex-$trackIndex",
                    label = format.label ?: format.language ?: "$prefix ${size + 1}",
                    language = format.language,
                    selected = group.isTrackSelected(trackIndex),
                ),
            )
        }
    }
}

internal fun MediaItem.toPlaybackRequest(positionMillis: Long): PlaybackRequest? {
    val extras: Bundle = mediaMetadata.extras ?: return null
    val locationId = extras.getString(PlaybackMediaMetadata.LOCATION_ID) ?: return null
    val sourceContext = extras.getString(PlaybackMediaMetadata.SOURCE_CONTEXT)
        ?.let { value -> PlaybackSourceContext.entries.firstOrNull { it.name == value } }
        ?: PlaybackSourceContext.HOME
    return runCatching {
        PlaybackRequest(
            MediaItemId(mediaId),
            MediaLocationId(locationId),
            positionMillis.coerceAtLeast(0),
            sourceContext,
            extras.getBoolean(PlaybackMediaMetadata.INCOGNITO, false),
        )
    }.getOrNull()
}

internal fun PlaybackException.toPlaybackFailureSignal(): PlaybackFailureSignal = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> PlaybackFailureSignal.ACCESS_DENIED
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlaybackFailureSignal.NOT_FOUND
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> PlaybackFailureSignal.CONTAINER_UNSUPPORTED
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    -> PlaybackFailureSignal.DECODER_UNAVAILABLE
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> PlaybackFailureSignal.MALFORMED_MEDIA
    else -> PlaybackFailureSignal.OTHER
}

internal fun PlaybackException.toPlaybackError() = DefaultPlaybackErrorMapper.map(toPlaybackFailureSignal())
