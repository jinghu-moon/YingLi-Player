package seeyuer.yingli.player.app.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import seeyuer.yingli.player.domain.playback.BackendId
import seeyuer.yingli.player.domain.playback.BufferingReason
import seeyuer.yingli.player.domain.playback.EngineState
import seeyuer.yingli.player.domain.playback.PlaybackCapabilities
import seeyuer.yingli.player.domain.playback.PlaybackEngine
import seeyuer.yingli.player.domain.playback.PlaybackSourceHandle
import seeyuer.yingli.player.domain.playback.PlaybackOpenRequest
import seeyuer.yingli.player.domain.playback.PlaybackTimeline
import seeyuer.yingli.player.domain.playback.SeekPrecision
import seeyuer.yingli.player.domain.playback.SeekPrecisionState
import seeyuer.yingli.player.domain.playback.MutableSeekPrecisionControl
import seeyuer.yingli.player.domain.playback.seekPrecisionFor
import seeyuer.yingli.player.domain.playback.SourceAccessHandleId
import seeyuer.yingli.player.domain.playback.SurfaceBindRequest
import seeyuer.yingli.player.domain.playback.SurfaceLease
import seeyuer.yingli.player.domain.playback.SpeedControl
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.PlaybackCommandResult
import seeyuer.yingli.player.domain.playback.VideoTransformControl
import seeyuer.yingli.player.domain.playback.VideoScaleMode
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.domain.playback.VideoSurfacePort
import seeyuer.yingli.player.domain.playback.PlaybackMediaInfo
import seeyuer.yingli.player.domain.playback.PlaybackMediaInfoProvider
import seeyuer.yingli.player.engine.media3.PlaybackMediaMetadata

/** Media3 adapter owned by the service; the UI never receives this type. */
class ServicePlaybackEngine(
    private val player: ExoPlayer,
    private val sourceRegistry: SourceHandleRegistry,
    private val dispatchers: AppDispatchers,
    /**
     * 跳转精度策略的来源（进程内共享，见 [MutableSeekPrecisionControl]）。
     * 默认值让没有接入播放页的宿主也能工作：按"长视频"档走关键帧跳转。
     */
    private val seekPrecisionControl: SeekPrecisionState = MutableSeekPrecisionControl(),
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
     * 当前媒体的声明时长。跳转精度按它选择，与旧实现取 `source.durationMillis` 完全一致：
     * 改用 `player.duration` 会让"时长未知"的媒体在 READY 之后被重新判成短视频，
     * 那属于行为漂移，不是本次要改的东西。
     */
    private var currentDurationMillis: Long? = null

    /** 上一次真正下发给播放器的参数；用于把 `setSeekParameters` 的重复下发压掉。 */
    private var appliedSeekParameters: SeekParameters? = null

    /** 引擎自己的协程作用域：只服务于"策略变化 → 应用到播放器"这一条链，随 [release] 收掉。 */
    private val engineScope = CoroutineScope(SupervisorJob() + dispatchers.main)

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
        // 精度切换只改 `SeekParameters`，不重新 prepare、不重设媒体：截图工具进出时播放不该被打断。
        // 合并两路输入是因为"有效精度"由两者共同决定：
        // - 用户/UI 切换的帧精确开关；
        // - 引擎是否已经就绪：`prepare` 之后要先 `setMediaItem`，此刻下发会被后续 setMediaItem 丢掉，
        //   必须等真正 READY 再对齐一次，否则截图模式下第一次步进会退回关键帧跳转。
        engineScope.launch {
            combine(seekPrecisionControl.precision, mutableState) { precision, state ->
                precision to isPlayerReadyForSeekConfiguration(state)
            }.collect { (precision, isReady) -> if (isReady) applySeekPrecision(precision) }
        }
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
        // 跳转精度（REX 同构：默认 `absolute+keyframes`，短视频/需要精确落点时才 exact）：
        // Media3 默认是 EXACT，精确跳转要从目标前的关键帧解码到目标位置，大文件上会明显冻结；
        // 拖动进度条要"画面跟手"就必须用关键帧跳转。
        // 这里先按新媒体的时长记下"目标精度"，真正下发留给状态变 READY 之后的对齐
        //（此刻下发会被随后的 `setMediaItem`/`clearMediaItems` 覆盖掉，等于没设）。
        currentDurationMillis = source.durationMillis
        // 新的时长意味着"有效精度"可能变（短视频 EXACT / 长视频 CLOSEST_SYNC），清掉已下发记录，
        // 让下一次对齐重新计算并下发。
        appliedSeekParameters = null
        player.prepare()
    }

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun stop() {
        player.stop()
        hasEverBeenReady = false
        // 媒体已经清掉：旧时长与已下发的参数都不再代表任何东西，下次 prepare 重新判定。
        currentDurationMillis = null
        appliedSeekParameters = null
        mutableState.value = EngineState.Idle
    }
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
        engineScope.cancel()
        currentLease = null
        sourceRegistry.clear()
        mutableMediaInfo.value = null
    }

    /**
     * 把当前"有效精度"下发到播放器。
     *
     * 幂等：目标 `SeekParameters` 与上次下发相同就跳过——`setSeekParameters` 会让播放器内部重算，
     * 而引擎状态每次跳动都会驱动到这里，没必要反复下发同一个值。
     */
    private fun applySeekPrecision(precision: SeekPrecision) {
        val parameters = when (seekPrecisionFor(currentDurationMillis, precision == SeekPrecision.FRAME_ACCURATE)) {
            SeekPrecision.FRAME_ACCURATE -> SeekParameters.EXACT
            SeekPrecision.CLOSEST_SYNC -> SeekParameters.CLOSEST_SYNC
        }
        if (appliedSeekParameters == parameters) return
        appliedSeekParameters = parameters
        player.setSeekParameters(parameters)
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
         * 播放器是否已经"有媒体"到可以接受 `setSeekParameters`：只有经过一次成功准备之后的
         * READY/PLAYING/PAUSED/ENDED 才算。IDLE/PREPARING 阶段下发会被随后的 `setMediaItem`
         * 或 `clearMediaItems` 覆盖掉，等于没设——截图模式下第一次步进就会退回关键帧跳转。
         */
        fun isPlayerReadyForSeekConfiguration(state: EngineState): Boolean = when (state) {
            is EngineState.Ready,
            is EngineState.Playing,
            is EngineState.Paused,
            is EngineState.Ended,
            -> true
            EngineState.Idle,
            EngineState.Preparing,
            is EngineState.Buffering,
            is EngineState.Failed,
            -> false
        }

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
