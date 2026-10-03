package seeyuer.yingli.player.app.playback

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import seeyuer.yingli.player.domain.playback.BackendId
import seeyuer.yingli.player.domain.playback.BufferingReason
import seeyuer.yingli.player.domain.playback.EngineAbLoop
import seeyuer.yingli.player.domain.playback.EngineState
import seeyuer.yingli.player.domain.playback.PlaybackCapabilities
import seeyuer.yingli.player.domain.playback.PlaybackEngine
import seeyuer.yingli.player.domain.playback.PlaybackEngineEvent
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
    private val mutableEvents = MutableSharedFlow<PlaybackEngineEvent>(extraBufferCapacity = 8)
    private var currentLease: SurfaceLease? = null

    /**
     * AB 循环的边界检测器。**归引擎**：它跑在服务主线程上，不依赖 UI 存活，
     * 因此后台/锁屏/画中画下循环仍然生效（D4）。
     */
    private val abBoundaryWatcher = AbBoundaryWatcher(
        player = player,
        onBoundaryReached = { generation, position -> mutableEvents.tryEmit(PlaybackEngineEvent.AbBoundaryReached(generation, position)) },
        // 回跳目标用信号里的 A（生成信号时的配置快照），避免"晚到信号拿新配置回跳"。
        onSeekToPointA = { pointAMillis -> player.seekTo(pointAMillis) },
    )

    /** 当前生效的 AB 循环配置；`null` = 未激活。AB 激活期间跳转精度强制精确。 */
    private val mutableAbLoop = MutableStateFlow<EngineAbLoop?>(null)

    /**
     * 当前媒体的声明时长。跳转精度按它选择，与旧实现取 `source.durationMillis` 完全一致：
     * 改用 `player.duration` 会让"时长未知"的媒体在 READY 之后被重新判成短视频，
     * 那属于行为漂移，不是本次要改的东西。
     */
    private var currentDurationMillis: Long? = null

    /**
     * 上一次真正下发给播放器的**有效精度**；用于把 `setSeekParameters` 的重复下发压掉。
     *
     * 存精度而不是 `SeekParameters` 本身：两者是一一对应的（见 [applySeekPrecision]），
     * 判定完全等价，但这份状态里就不必出现 Media3 的不稳定类型了。
     */
    private var appliedSeekPrecision: SeekPrecision? = null

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
        // 合并三路输入是因为"有效精度"由它们共同决定：
        // - 用户/UI 切换的帧精确开关；
        // - 引擎是否已经就绪：`prepare` 之后要先 `setMediaItem`，此刻下发会被后续 setMediaItem 丢掉，
        //   必须等真正 READY 再对齐一次，否则截图模式下第一次步进会退回关键帧跳转；
        // - AB 是否生效：生效期间必须精确（循环回跳要落在 A 上），退出后要回到时长默认档。
        //   AB 单独作为一路输入，是因为"精度开关没变但 AB 开了/关了"同样需要重新下发。
        engineScope.launch {
            combine(
                seekPrecisionControl.precision,
                mutableState,
                mutableAbLoop,
            ) { precision, state, loop ->
                Triple(precision, isPlayerReadyForSeekConfiguration(state), loop != null)
            }.collect { (precision, isReady, abActive) ->
                if (isReady) applySeekPrecision(precision, abActive)
            }
        }
    }

    override val state: StateFlow<EngineState> = mutableState.asStateFlow()
    override val capabilities: StateFlow<PlaybackCapabilities> = mutableCapabilities.asStateFlow()
    override val mediaInfo: StateFlow<PlaybackMediaInfo?> = mutableMediaInfo.asStateFlow()
    override val events: Flow<PlaybackEngineEvent> = mutableEvents.asSharedFlow()

    /**
     * 下发 A-B 循环配置。
     *
     * 这里只做两件事：换掉边界检测的配置（旧 generation 的定时回调随之作废）、
     * 把 AB 是否生效告诉精度那条链（它自己会重新计算并下发）。
     * **不做任何 A/B 业务校验**：设点合法性、互换、相等边界、计数条件全在会话 runtime。
     *
     * 为什么必须让 AB 生效期间**一律精确**（`SeekParameters.EXACT`）：方案 A 的帧精确落点完全来自
     * 这一次精确 seek（阶段 0 §T0.2：50/50 次落点误差 0 µs）。用默认的长视频关键帧跳转会让每次
     * 回跳偏若干帧，偏差还会随循环次数累积；设 A/B 时也要落在用户看到的那一帧上。
     * 精度仍然只有一个入口（[applySeekPrecision]），不为 AB 另起一套通道。
     */
    override fun configureAbLoop(loop: EngineAbLoop?) {
        mutableAbLoop.value = loop
        abBoundaryWatcher.configure(loop)
    }

    /**
     * 激活 AB 区间：**先精确跳回 A，再武装边界检测**。
     *
     * 顺序不可交换，原因见 [PlaybackEngine.activateAbLoop]：边界检测器在武装那一刻要读播放器
     * 位置来判断"这一轮是否已经越过 B"，而跳回 A 必须在它之前落地，读到 A（< B）才会武装。
     * 这一跳走的是引擎自己的播放器，因此精度就是当前生效的精度 —— AB 生效期间
     * [applySeekPrecision] 已把它锁到 `SeekParameters.EXACT`（与循环回跳同一条入口）。
     *
     * `mutableAbLoop` 与边界检测必须一起换：只换其中一个会让"精度链"与"检测链"对
     * "当前生效配置"给出两种答案。
     */
    override fun activateAbLoop(loop: EngineAbLoop) {
        mutableAbLoop.value = loop
        player.seekTo(loop.pointAMillis)
        abBoundaryWatcher.activate(loop)
    }

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
        // 换媒体：旧媒体的 AB 边界检测必须先停（新媒体的时间轴与旧 B 毫无关系）。
        mutableAbLoop.value = null
        abBoundaryWatcher.configure(null)
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
        appliedSeekPrecision = null
        player.prepare()
    }

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun stop() {
        player.stop()
        hasEverBeenReady = false
        // 媒体已经清掉：旧时长与已下发的参数都不再代表任何东西，下次 prepare 重新判定。
        currentDurationMillis = null
        appliedSeekPrecision = null
        // AB 的边界检测挂在"某个媒体的时间轴"上：媒体没了，检测必须一起停，
        // 否则定时回调会拿旧 B 去比对新媒体的位置。
        mutableAbLoop.value = null
        abBoundaryWatcher.configure(null)
        mutableState.value = EngineState.Idle
    }
    override fun seekTo(positionMillis: Long) = player.seekTo(positionMillis)

    /**
     * 直读播放器的当前位置（见 [PlaybackEngine.currentPositionMillis]）。
     *
     * 这里不做任何缓存或插值：引擎 `state` 的 timeline 只在状态跳变时发布，稳定播放期间会
     * 停在旧位置（真机实测 60s 后仍是 0），而设点、截图时间戳、上一项判定都要用真值。
     */
    override fun currentPositionMillis(): Long = player.currentPosition.coerceAtLeast(0)

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
        abBoundaryWatcher.close()
        mutableAbLoop.value = null
        engineScope.cancel()
        currentLease = null
        sourceRegistry.clear()
        mutableMediaInfo.value = null
    }

    /**
     * 把当前"有效精度"下发到播放器。
     *
     * 幂等：有效精度与上次下发相同就跳过——`setSeekParameters` 会让播放器内部重算，
     * 而引擎状态每次跳动都会驱动到这里，没必要反复下发同一个值。
     *
     * **AB 生效期间一律精确**：循环回跳要落在 A 上（不是 A 之前的关键帧），
     * 设 A/B 也要落在用户看到的那一帧上；两者都走同一个 [seekPrecisionFor] 入口，
     * 不额外引入一套"AB 专用精度"。
     *
     * `SeekParameters`（以及 `setSeekParameters`）在 Media3 里标着 `@UnstableApi`：
     * 精确跳转只有这一条入口，所以按官方方式就近 opt-in，不把整个引擎都变成"接受不稳定 API"。
     */
    @OptIn(UnstableApi::class)
    private fun applySeekPrecision(precision: SeekPrecision, abLoopActive: Boolean) {
        val frameAccurate = precision == SeekPrecision.FRAME_ACCURATE || abLoopActive
        val effective = seekPrecisionFor(currentDurationMillis, frameAccurate)
        if (appliedSeekPrecision == effective) return
        appliedSeekPrecision = effective
        player.setSeekParameters(
            when (effective) {
                SeekPrecision.FRAME_ACCURATE -> SeekParameters.EXACT
                SeekPrecision.CLOSEST_SYNC -> SeekParameters.CLOSEST_SYNC
            },
        )
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
