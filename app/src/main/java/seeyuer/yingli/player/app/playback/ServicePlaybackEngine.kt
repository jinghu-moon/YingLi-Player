package seeyuer.yingli.player.app.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import seeyuer.yingli.player.domain.playback.BackendId
import seeyuer.yingli.player.domain.playback.BufferingReason
import seeyuer.yingli.player.domain.playback.EngineState
import seeyuer.yingli.player.domain.playback.PlaybackCapabilities
import seeyuer.yingli.player.domain.playback.PlaybackEngine
import seeyuer.yingli.player.domain.playback.PlaybackSourceHandle
import seeyuer.yingli.player.domain.playback.PlaybackOpenRequest
import seeyuer.yingli.player.domain.playback.PlaybackTimeline
import seeyuer.yingli.player.domain.playback.SourceAccessHandleId
import seeyuer.yingli.player.domain.playback.SurfaceBindRequest
import seeyuer.yingli.player.domain.playback.SurfaceLease
import seeyuer.yingli.player.domain.playback.SpeedControl
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.PlaybackCommandResult
import seeyuer.yingli.player.domain.playback.VideoTransformControl
import seeyuer.yingli.player.domain.playback.VideoScaleMode
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.playback.VideoSurfacePort
import seeyuer.yingli.player.domain.playback.PlaybackMediaInfo
import seeyuer.yingli.player.domain.playback.PlaybackMediaInfoProvider
import seeyuer.yingli.player.engine.media3.PlaybackMediaMetadata

/** Media3 adapter owned by the service; the UI never receives this type. */
class ServicePlaybackEngine(
    private val player: ExoPlayer,
    private val sourceRegistry: SourceHandleRegistry,
) : PlaybackEngine, PlaybackMediaInfoProvider, VideoSurfacePort, SpeedControl, VideoTransformControl {
    private val mutableState = MutableStateFlow<EngineState>(EngineState.Idle)
    private val mutableCapabilities = MutableStateFlow(
        PlaybackCapabilities(
            backend = BackendId.MEDIA3,
            supportsFrameCapture = true,
            supportsFrameStep = true,
            supportsPip = true,
            supportsAudioOnlyBackground = true,
            supportsSecureSource = true,
        ),
    )
    private val mutableMediaInfo = MutableStateFlow<PlaybackMediaInfo?>(null)
    private var currentLease: SurfaceLease? = null

    /**
     * 是否**曾经**进入过 READY。用于区分"首次准备"与"seek/缓冲不足导致的重缓冲"：
     * 旧实现按 `currentPosition > 0` 判断，快退到接近 0 或拖到开头时 position == 0，
     * seek 引发的 STATE_BUFFERING 会被误判成首次准备，UI 于是闪出全屏加载圈。
     */
    private var hasEverBeenReady = false
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            publish()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            publish()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            mutableState.value = EngineState.Failed(
                seeyuer.yingli.player.domain.playback.DefaultPlaybackErrorMapper.map(
                    seeyuer.yingli.player.domain.playback.PlaybackFailureSignal.OTHER,
                ),
                timeline(),
            )
        }
    }

    init {
        player.addListener(listener)
    }

    override val state: StateFlow<EngineState> = mutableState.asStateFlow()
    override val capabilities: StateFlow<PlaybackCapabilities> = mutableCapabilities.asStateFlow()
    override val mediaInfo: StateFlow<PlaybackMediaInfo?> = mutableMediaInfo.asStateFlow()

    override suspend fun prepare(source: PlaybackSourceHandle, startPositionMillis: Long) {
        val uri = sourceRegistry.resolve(source.accessHandleId.value)
            ?: error("SOURCE_HANDLE_EXPIRED")
        val extras = Bundle().apply {
            putString(PlaybackMediaMetadata.LOCATION_ID, source.locationId.value)
        }
        val item = MediaItem.Builder()
            .setMediaId(source.mediaId.value)
            .setUri(Uri.parse(uri))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(source.displayName).setExtras(extras).build())
            .build()
        mutableState.value = EngineState.Preparing
        // 新文件重新开始：下一次 BUFFERING 属于"首次准备"，不是重缓冲。
        hasEverBeenReady = false
        mutableMediaInfo.value = PlaybackMediaInfo(
            title = source.displayName,
            durationMillis = source.durationMillis,
            width = source.width,
            height = source.height,
            fileSizeBytes = source.fileSizeBytes,
        )
        player.setMediaItem(item, startPositionMillis)
        player.prepare()
    }

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun stop() { player.stop(); hasEverBeenReady = false; mutableState.value = EngineState.Idle }
    override fun seekTo(positionMillis: Long) = player.seekTo(positionMillis)
    override fun setSpeed(speed: PlaybackSpeed): PlaybackCommandResult {
        player.setPlaybackSpeed(speed.value)
        return PlaybackCommandResult.Accepted
    }

    override fun setScaleMode(mode: VideoScaleMode): PlaybackCommandResult = PlaybackCommandResult.Accepted

    override fun bindSurface(request: SurfaceBindRequest): Result<SurfaceLease> {
        val current = currentLease
        if (current != null && request.generation <= current.generation) {
            return Result.failure(IllegalStateException("STALE_SURFACE_GENERATION"))
        }
        return Result.success(
            SurfaceLease(request.owner, request.generation, request.surface.value).also { currentLease = it },
        )
    }

    override fun bind(request: SurfaceBindRequest): Result<SurfaceLease> = bindSurface(request)

    override fun unbindSurface(lease: SurfaceLease): Result<Unit> =
        if (currentLease == lease) Result.success(Unit).also { currentLease = null }
        else Result.failure(IllegalStateException("STALE_SURFACE_LEASE"))

    override fun unbind(lease: SurfaceLease): Result<Unit> = unbindSurface(lease)

    override fun release() {
        player.removeListener(listener)
        currentLease = null
        sourceRegistry.clear()
        mutableMediaInfo.value = null
    }

    private fun publish() {
        publishMediaInfo()
        val playbackState = player.playbackState
        if (playbackState == Player.STATE_READY) hasEverBeenReady = true
        mutableState.value = when (classifyEngineState(playbackState, player.isPlaying, hasEverBeenReady)) {
            EngineStateKind.REBUFFER -> EngineState.Buffering(timeline(), BufferingReason.REBUFFER)
            EngineStateKind.PREPARING -> EngineState.Preparing
            EngineStateKind.ENDED -> EngineState.Ended(timeline())
            EngineStateKind.PLAYING -> EngineState.Playing(timeline())
            EngineStateKind.PAUSED -> EngineState.Paused(timeline())
            EngineStateKind.IDLE -> EngineState.Idle
        }
    }

    /** 引擎状态种类，见 [classifyEngineState]。 */
    internal enum class EngineStateKind { REBUFFER, PREPARING, ENDED, PLAYING, PAUSED, IDLE }

    internal companion object {
        /**
         * 把 Media3 的播放状态分类成引擎状态种类（纯函数，便于单元测试）。
         *
         * **首次准备**与**重缓冲**必须分开：只有前者该显示加载指示；seek 造成的瞬时重缓冲
         * 若也显示全屏加载圈，快进/快退/拖进度条时画面就会一直闪加载圈。
         */
        fun classifyEngineState(
            playbackState: Int,
            isPlaying: Boolean,
            hasEverBeenReady: Boolean,
        ): EngineStateKind = when {
            playbackState == Player.STATE_BUFFERING && hasEverBeenReady -> EngineStateKind.REBUFFER
            playbackState == Player.STATE_BUFFERING -> EngineStateKind.PREPARING
            playbackState == Player.STATE_ENDED -> EngineStateKind.ENDED
            isPlaying -> EngineStateKind.PLAYING
            playbackState == Player.STATE_READY -> EngineStateKind.PAUSED
            else -> EngineStateKind.IDLE
        }
    }

    private fun publishMediaInfo() {
        val current = mutableMediaInfo.value ?: return
        val video = player.currentTracks.groups
            .firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
            ?.getTrackFormat(0)
        val audio = player.currentTracks.groups
            .firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
            ?.getTrackFormat(0)
        mutableMediaInfo.value = current.copy(
            width = video?.width?.takeIf { it > 0 } ?: current.width,
            height = video?.height?.takeIf { it > 0 } ?: current.height,
            videoCodec = video?.codecs ?: video?.sampleMimeType ?: current.videoCodec,
            audioCodec = audio?.codecs ?: audio?.sampleMimeType ?: current.audioCodec,
            frameRate = video?.frameRate?.takeIf { it > 0 } ?: current.frameRate,
            durationMillis = player.duration.takeUnless { it < 0 || it == androidx.media3.common.C.TIME_UNSET } ?: current.durationMillis,
        )
    }

    private fun timeline() = PlaybackTimeline(
        positionMillis = player.currentPosition.coerceAtLeast(0),
        durationMillis = player.duration.takeUnless { it < 0 },
        bufferedPositionMillis = player.bufferedPosition.coerceAtLeast(0),
        isSeekable = player.isCurrentMediaItemSeekable,
    )
}

class SourceHandleRegistry {
    private val handles = ConcurrentHashMap<String, String>()
    private val pending = ConcurrentHashMap<MediaItemId, PendingSource>()

    fun put(id: String, uri: String) { handles[id] = uri }
    fun resolve(id: String): String? = handles[id]
    fun register(item: MediaItem) {
        val mediaId = runCatching { MediaItemId(item.mediaId) }.getOrNull() ?: return
        val uri = item.localConfiguration?.uri?.toString() ?: return
        val extras = item.mediaMetadata.extras
        val locationId = extras?.getString(PlaybackMediaMetadata.LOCATION_ID)
            ?.let { runCatching { seeyuer.yingli.player.core.model.media.MediaLocationId(it) }.getOrNull() }
            ?: seeyuer.yingli.player.core.model.media.MediaLocationId(mediaId.value)
        pending[mediaId] = PendingSource(uri, item.mediaMetadata.title?.toString() ?: mediaId.value, locationId)
    }

    fun resolvePending(request: PlaybackOpenRequest): PlaybackSourceHandle? {
        val source = pending.remove(request.mediaId) ?: return null
        val accessId = "${request.sessionId.value}:${request.mediaId.value}"
        put(accessId, source.uri)
        return PlaybackSourceHandle(
            mediaId = request.mediaId,
            locationId = source.locationId,
            accessHandleId = SourceAccessHandleId(accessId),
            displayName = source.title,
            durationMillis = null,
        )
    }

    fun clear() {
        handles.clear()
        pending.clear()
    }

    private data class PendingSource(
        val uri: String,
        val title: String,
        val locationId: seeyuer.yingli.player.core.model.media.MediaLocationId,
    )
}
