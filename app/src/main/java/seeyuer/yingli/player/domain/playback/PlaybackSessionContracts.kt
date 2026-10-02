package seeyuer.yingli.player.domain.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.ceil
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.security.VaultItemId

@JvmInline
value class PlaybackSessionId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class PlaybackCommandId(val value: Long) {
    init { require(value > 0) }
}

@JvmInline
value class SourceAccessHandleId(val value: String) {
    init { require(value.isNotBlank()) }
}

data class PlaybackSourceHandle(
    val mediaId: MediaItemId,
    val locationId: MediaLocationId,
    val accessHandleId: SourceAccessHandleId,
    val displayName: String,
    val durationMillis: Long?,
    val width: Int? = null,
    val height: Int? = null,
    val fileSizeBytes: Long? = null,
    val mimeType: String? = null,
) {
    init {
        require(displayName.isNotBlank())
        require(durationMillis == null || durationMillis >= 0)
    }
}

data class PlaybackOpenRequest(
    val sessionId: PlaybackSessionId,
    val mediaId: MediaItemId,
    val sourceContext: PlaybackSourceContext,
    val startPositionMillis: Long = 0,
    val incognito: Boolean = false,
) {
    init { require(startPositionMillis >= 0) }
}

fun interface PlaybackSourceResolver {
    suspend fun resolve(request: PlaybackOpenRequest): Result<PlaybackSourceHandle>
}

enum class PlaybackOrder {
    SEQUENCE,
    SHUFFLE,
    QUEUE_REPEAT,
    SINGLE_REPEAT,
}

data class PlaybackQueueSnapshot(
    val queueId: String,
    val mediaIds: List<MediaItemId>,
    val currentIndex: Int,
    val order: PlaybackOrder,
    val shuffleHistory: List<Int> = emptyList(),
) {
    init {
        require(queueId.isNotBlank())
        require(mediaIds.distinct().size == mediaIds.size)
        require(mediaIds.isEmpty() || currentIndex in mediaIds.indices)
        require(mediaIds.isNotEmpty() || currentIndex == -1)
        require(shuffleHistory.all { it in mediaIds.indices })
    }
}

sealed interface NavigationDecision {
    data class MoveTo(
        val index: Int,
        val mediaId: MediaItemId,
        val shuffleHistory: List<Int> = emptyList(),
    ) : NavigationDecision
    data object StopAtEnd : NavigationDecision
    data object NoCandidate : NavigationDecision
}

fun interface ShufflePicker {
    fun pick(candidates: List<Int>, currentIndex: Int): Int
}

class QueueNavigator(
    private val shufflePicker: ShufflePicker = ShufflePicker { candidates, _ -> candidates.first() },
) {
    fun next(queue: PlaybackQueueSnapshot, ended: Boolean): NavigationDecision {
        if (queue.mediaIds.isEmpty()) return NavigationDecision.NoCandidate
        if (queue.order == PlaybackOrder.SINGLE_REPEAT && ended) {
            return NavigationDecision.MoveTo(queue.currentIndex, queue.mediaIds[queue.currentIndex])
        }
        if (queue.order == PlaybackOrder.SHUFFLE) {
            val candidates = queue.mediaIds.indices.filter { it != queue.currentIndex }
            if (candidates.isEmpty()) return NavigationDecision.MoveTo(queue.currentIndex, queue.mediaIds[queue.currentIndex])
            val index = shufflePicker.pick(candidates, queue.currentIndex).takeIf(candidates::contains)
                ?: return NavigationDecision.NoCandidate
            return NavigationDecision.MoveTo(
                index,
                queue.mediaIds[index],
                (if (queue.shuffleHistory.lastOrNull() == queue.currentIndex) {
                    queue.shuffleHistory
                } else {
                    queue.shuffleHistory + queue.currentIndex
                }).takeLast(queue.mediaIds.size),
            )
        }
        val nextIndex = queue.currentIndex + 1
        if (nextIndex in queue.mediaIds.indices) {
            return NavigationDecision.MoveTo(nextIndex, queue.mediaIds[nextIndex])
        }
        return when (queue.order) {
            PlaybackOrder.QUEUE_REPEAT -> NavigationDecision.MoveTo(0, queue.mediaIds.first())
            PlaybackOrder.SINGLE_REPEAT -> NavigationDecision.StopAtEnd
            PlaybackOrder.SEQUENCE -> NavigationDecision.StopAtEnd
            PlaybackOrder.SHUFFLE -> error("handled above")
        }
    }

    fun previous(queue: PlaybackQueueSnapshot, currentPositionMillis: Long): NavigationDecision {
        if (queue.mediaIds.isEmpty()) return NavigationDecision.NoCandidate
        if (currentPositionMillis > PREVIOUS_RESTART_THRESHOLD_MILLIS) {
            return NavigationDecision.MoveTo(queue.currentIndex, queue.mediaIds[queue.currentIndex])
        }
        if (queue.order == PlaybackOrder.SHUFFLE) {
            val index = queue.shuffleHistory.dropLastWhile { it == queue.currentIndex }.lastOrNull()
                ?: return NavigationDecision.NoCandidate
            return NavigationDecision.MoveTo(index, queue.mediaIds[index], queue.shuffleHistory.dropLast(1))
        }
        val previousIndex = queue.currentIndex - 1
        if (previousIndex in queue.mediaIds.indices) {
            return NavigationDecision.MoveTo(previousIndex, queue.mediaIds[previousIndex])
        }
        return when (queue.order) {
            PlaybackOrder.QUEUE_REPEAT, PlaybackOrder.SINGLE_REPEAT -> {
                val index = queue.mediaIds.lastIndex
                NavigationDecision.MoveTo(index, queue.mediaIds[index])
            }
            PlaybackOrder.SEQUENCE -> NavigationDecision.NoCandidate
            PlaybackOrder.SHUFFLE -> error("handled above")
        }
    }

    private companion object {
        const val PREVIOUS_RESTART_THRESHOLD_MILLIS = 5_000L
    }
}

enum class AbPoint { A, B }

data class AbLoopState(
    val pointA: Long? = null,
    val pointB: Long? = null,
) {
    init {
        require(pointA == null || pointA >= 0)
        require(pointB == null || pointB >= 0)
        require(pointA == null || pointB == null || pointA < pointB)
    }

    val active: Boolean get() = pointA != null && pointB != null
}

class AbLoopLimiter(private val fallbackFrameRate: Float = 30f) {
    init { require(fallbackFrameRate > 0) }

    fun setPoint(
        state: AbLoopState,
        point: AbPoint,
        positionMillis: Long,
        durationMillis: Long,
        frameRate: Float?,
    ): Result<AbLoopState> {
        require(durationMillis >= 0)
        val position = positionMillis.coerceIn(0, durationMillis)
        val frameStep = frameStepMillis(frameRate)
        return when (point) {
            AbPoint.A -> Result.success(
                AbLoopState(position, state.pointB?.takeIf { it >= position + frameStep }),
            )
            AbPoint.B -> {
                val start = state.pointA ?: return Result.failure(IllegalStateException("A_NOT_SET"))
                if (position < start + frameStep) {
                    Result.failure(IllegalArgumentException("B_MUST_FOLLOW_A"))
                } else {
                    Result.success(AbLoopState(start, position))
                }
            }
        }
    }

    fun clear(): AbLoopState = AbLoopState()

    fun clamp(state: AbLoopState, positionMillis: Long, durationMillis: Long): Long {
        val position = positionMillis.coerceIn(0, durationMillis.coerceAtLeast(0))
        if (!state.active) return position
        val start = requireNotNull(state.pointA)
        val end = requireNotNull(state.pointB)
        return position.coerceIn(start, end)
    }

    fun seekBy(state: AbLoopState, positionMillis: Long, offsetMillis: Long, durationMillis: Long): Long =
        clamp(state, positionMillis + offsetMillis, durationMillis)

    fun loopPosition(state: AbLoopState, positionMillis: Long): Long? =
        if (state.active && positionMillis >= requireNotNull(state.pointB)) requireNotNull(state.pointA) else null

    fun frameStepMillis(frameRate: Float?): Long =
        ceil((1_000f / (frameRate?.takeIf { it > 0 } ?: fallbackFrameRate)).coerceAtLeast(1f)).toLong()
}

sealed interface AbLoopEvent {
    data class SetPoint(
        val point: AbPoint,
        val positionMillis: Long,
        val durationMillis: Long,
        val frameRate: Float?,
    ) : AbLoopEvent
    data object Clear : AbLoopEvent
    data object MediaChanged : AbLoopEvent
}

data class AbLoopUpdate(val state: AbLoopState, val rejection: PlaybackCommandRejection? = null)

class AbLoopReducer(private val limiter: AbLoopLimiter = AbLoopLimiter()) {
    fun reduce(state: AbLoopState, event: AbLoopEvent): AbLoopUpdate = when (event) {
        is AbLoopEvent.SetPoint -> limiter.setPoint(
            state,
            event.point,
            event.positionMillis,
            event.durationMillis,
            event.frameRate,
        ).fold(
            onSuccess = { AbLoopUpdate(it) },
            onFailure = { AbLoopUpdate(state, PlaybackCommandRejection.INVALID_AB_RANGE) },
        )
        AbLoopEvent.Clear, AbLoopEvent.MediaChanged -> AbLoopUpdate(limiter.clear())
    }
}

enum class BackendId { MEDIA3, MPV }
enum class SurfaceOwner { REGULAR_PLAYER, SHORTS, PICTURE_IN_PICTURE }
enum class RequestedOrientation { SENSOR, PORTRAIT, LANDSCAPE }
enum class FrameStepDirection { PREVIOUS, NEXT }
enum class SeekOrigin { USER, DRAG_END, FRAME_STEP, AB_LOOP }
enum class TrackType { AUDIO, SUBTITLE }
enum class PauseReason { USER, AUDIO_FOCUS, BACKGROUND, ENDED }
enum class BufferingReason { INITIAL, REBUFFER, SOURCE_READ }

data class TrackFingerprint(
    val type: TrackType,
    val language: String?,
    val roleFlags: Int,
    val codec: String?,
    val channelCount: Int?,
    val label: String?,
)

data class TrackSelection(val type: TrackType, val fingerprint: TrackFingerprint?)

data class SurfaceBindRequest(
    val owner: SurfaceOwner,
    val generation: Long,
    val surface: VideoSurfaceToken,
) {
    init { require(generation >= 0) }
}

@JvmInline
value class VideoSurfaceToken(val value: String) {
    init { require(value.isNotBlank()) }
}

data class SurfaceLease(val owner: SurfaceOwner, val generation: Long, val token: String) {
    init {
        require(generation >= 0)
        require(token.isNotBlank())
    }
}

data class PlaybackCapabilities(
    val backend: BackendId,
    val supportsFrameCapture: Boolean = false,
    val supportsFrameStep: Boolean = false,
    val supportsPip: Boolean = false,
    val supportsAudioOnlyBackground: Boolean = false,
    val supportsSecureSource: Boolean = false,
)

data class BackendSnapshot(val id: BackendId, val reason: String? = null)
data class VideoOutputState(val boundLease: SurfaceLease? = null, val isSecure: Boolean = false)

sealed interface PlaybackPhase {
    data object Idle : PlaybackPhase
    data class Resolving(val commandId: PlaybackCommandId) : PlaybackPhase
    data class Preparing(val backend: BackendId) : PlaybackPhase
    data class Ready(val canPlay: Boolean) : PlaybackPhase
    data class Playing(val startedAtElapsedMillis: Long) : PlaybackPhase
    data class Paused(val reason: PauseReason) : PlaybackPhase
    data class Buffering(val reason: BufferingReason) : PlaybackPhase
    data class Ended(val next: MediaItemId?) : PlaybackPhase
    data class Failed(val error: PlaybackError) : PlaybackPhase
}

data class PlaybackSessionSnapshot(
    val sessionId: PlaybackSessionId?,
    val mediaId: MediaItemId?,
    val title: String?,
    val phase: PlaybackPhase,
    val timeline: PlaybackTimeline = PlaybackTimeline(),
    val queue: PlaybackQueueSnapshot? = null,
    val abLoop: AbLoopState = AbLoopState(),
    val backend: BackendSnapshot? = null,
    val output: VideoOutputState = VideoOutputState(),
    val capabilities: PlaybackCapabilities? = null,
    val audioTracks: List<TrackChoice> = emptyList(),
    val subtitleTracks: List<TrackChoice> = emptyList(),
    val speed: PlaybackSpeed = PlaybackSpeed.Normal,
    val scaleMode: VideoScaleMode = VideoScaleMode.FIT,
    val connectionState: PlaybackConnectionState = PlaybackConnectionState.CONNECTING,
    val mediaInfo: PlaybackMediaInfo? = null,
    /**
     * 当前媒体的帧号校准状态（截图模式用）。随会话/媒体切换复位：
     * 它描述的是"这一段媒体"，换成另一个文件就必须重新校准，绝不能让旧值跟着走。
     */
    val frameCalibration: FrameCalibrationResult? = null,
)

data class PlaybackMediaInfo(
    val title: String,
    val durationMillis: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val frameRate: Float? = null,
    val fileSizeBytes: Long? = null,
) {
    init { require(title.isNotBlank()) }
}

sealed interface PlaybackSessionCommand {
    data class Open(val request: PlaybackOpenRequest) : PlaybackSessionCommand
    data class OpenVault(val itemId: VaultItemId, val displayTitle: String) : PlaybackSessionCommand {
        init { require(displayTitle.isNotBlank()) }
    }
    data object Play : PlaybackSessionCommand
    data object Pause : PlaybackSessionCommand
    data class Seek(val positionMillis: Long, val origin: SeekOrigin = SeekOrigin.USER) : PlaybackSessionCommand
    data class SeekBy(val offsetMillis: Long) : PlaybackSessionCommand
    data object Stop : PlaybackSessionCommand
    data object Retry : PlaybackSessionCommand
    data object Next : PlaybackSessionCommand
    data object Previous : PlaybackSessionCommand
    data class SetOrder(val order: PlaybackOrder) : PlaybackSessionCommand
    data class SetSpeed(val speed: PlaybackSpeed) : PlaybackSessionCommand
    data class SelectTrack(val selection: TrackSelection) : PlaybackSessionCommand
    data class SetScale(val mode: VideoScaleMode) : PlaybackSessionCommand
    data class SetAbPoint(val point: AbPoint) : PlaybackSessionCommand
    data object ClearAb : PlaybackSessionCommand
    data class BindSurface(val request: SurfaceBindRequest) : PlaybackSessionCommand
    data class UnbindSurface(val lease: SurfaceLease) : PlaybackSessionCommand
    data object CaptureFrame : PlaybackSessionCommand
}

sealed interface PlaybackSessionEvent {
    data class StateChanged(val snapshot: PlaybackSessionSnapshot) : PlaybackSessionEvent
    data class OneShotFeedback(val code: String) : PlaybackSessionEvent
    data class ScreenshotReady(val artifact: ScreenshotArtifact) : PlaybackSessionEvent
}

data class ScreenshotArtifact(val id: String, val displayName: String, val positionMillis: Long) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
        require(positionMillis >= 0)
    }
}

data class FrameCaptureRequest(val mediaId: MediaItemId, val positionMillis: Long) {
    init { require(positionMillis >= 0) }
}

fun interface FrameStepControl {
    fun stepFrame(direction: FrameStepDirection): PlaybackCommandResult
}

fun interface SnapshotControl {
    suspend fun captureFrame(request: FrameCaptureRequest): Result<ScreenshotArtifact>
}

interface TrackControl {
    val tracks: StateFlow<List<TrackFingerprint>>
    fun select(selection: TrackSelection): PlaybackCommandResult
}

fun interface VideoTransformControl {
    fun setScaleMode(mode: VideoScaleMode): PlaybackCommandResult
}

fun interface SpeedControl {
    fun setSpeed(speed: PlaybackSpeed): PlaybackCommandResult
}

interface VideoSurfacePort {
    fun bind(request: SurfaceBindRequest): Result<SurfaceLease>
    fun unbind(lease: SurfaceLease): Result<Unit>
}

interface PlaybackEngine {
    val state: StateFlow<EngineState>
    val capabilities: StateFlow<PlaybackCapabilities>
    suspend fun prepare(source: PlaybackSourceHandle, startPositionMillis: Long)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMillis: Long)
    fun bindSurface(request: SurfaceBindRequest): Result<SurfaceLease>
    fun unbindSurface(lease: SurfaceLease): Result<Unit>
    fun release()
}

interface PlaybackMediaInfoProvider {
    val mediaInfo: StateFlow<PlaybackMediaInfo?>
}

sealed interface EngineState {
    data object Idle : EngineState
    data object Preparing : EngineState
    data class Ready(val timeline: PlaybackTimeline) : EngineState
    data class Playing(val timeline: PlaybackTimeline) : EngineState
    data class Paused(val timeline: PlaybackTimeline) : EngineState
    data class Buffering(val timeline: PlaybackTimeline, val reason: BufferingReason) : EngineState
    data class Ended(val timeline: PlaybackTimeline) : EngineState
    data class Failed(val error: PlaybackError, val timeline: PlaybackTimeline) : EngineState
}

interface PlaybackSessionClient {
    val snapshot: StateFlow<PlaybackSessionSnapshot>
    val events: Flow<PlaybackSessionEvent>
    fun dispatch(command: PlaybackSessionCommand): PlaybackCommandHandle

    /**
     * 触发"当前媒体真实总帧数"的后台校准（结果回流到 [snapshot] 的 `frameCalibration`）。
     *
     * 由会话负责这件事，是因为**只有会话知道当前媒体的真实来源**（URI 解析在会话这一层）。
     * 让 UI 层自己再解析一次 URI 会多一条重复的解析链，还会绕过会话对媒体切换的判定。
     * 实现必须：只在本地源上真的去扫、在后台线程、可取消。
     */
    fun calibrateFrames() = Unit

    /** 退出截图模式/销毁：取消在跑的校准并清空结果。 */
    fun stopFrameCalibration() = Unit
}

data class PlaybackCommandHandle(val id: PlaybackCommandId)

interface WindowPlaybackGateway {
    val state: StateFlow<WindowPlaybackState>
    fun setFullscreen(enabled: Boolean): Result<Unit>
    fun requestOrientation(orientation: RequestedOrientation): Result<Unit>
    fun enterPictureInPicture(): Result<Unit>
}

data class WindowPlaybackState(
    val isFullscreen: Boolean = false,
    val orientation: RequestedOrientation = RequestedOrientation.SENSOR,
    /** 窗口当前真实的竖屏状态，来自 Activity 的配置变化回传，不是请求值。 */
    val isPortrait: Boolean = true,
    val isInPictureInPicture: Boolean = false,
)

/**
 * 画面手势要调整的设备状态：音量走系统媒体流（与音量键同一套），亮度走窗口级
 * `screenBrightness`（不改系统设置、不需要 WRITE_SETTINGS）。
 * 读数一律归一化到 `0f..1f`，便于手势按比例调整与显示。
 */
interface DeviceControlGateway {
    /** 当前系统媒体音量（0f..1f）。 */
    fun currentVolume(): Float
    /** 设置系统媒体音量；被系统策略（如勿扰）拒绝时返回 failure。 */
    fun setVolume(fraction: Float): Result<Unit>
    /** 当前窗口亮度（0f..1f）；处于"跟随系统"时用系统当前亮度作为起点。 */
    fun currentBrightness(): Float
    /** true 表示当前是"跟随系统亮度"，尚未被本页手势改过。 */
    fun isFollowingSystemBrightness(): Boolean
    /** 设置窗口亮度（0f..1f），只影响本页面。 */
    fun setBrightness(fraction: Float): Result<Unit>
    /** 退出播放页时恢复正常亮度（跟随系统）。 */
    fun resetBrightness()
}

data class BackgroundPlaybackPolicy(
    val continueAudioWhenPageLeaves: Boolean,
    val pauseWhenAudioFocusLost: Boolean,
    val autoPictureInPicture: Boolean,
    val keepSessionWhenMiniPlayerDisabled: Boolean,
)

fun interface ElapsedTimeSource {
    fun nowMillis(): Long
}

enum class HistoryEligibility { ELIGIBLE, INCOGNITO, SECURE_SOURCE, TOO_EARLY, COMPLETED }

class HistoryEligibilityPolicy(
    private val completionFraction: Double = 0.95,
) {
    init { require(completionFraction in 0.0..1.0) }

    fun evaluate(
        positionMillis: Long,
        durationMillis: Long?,
        incognito: Boolean,
        secureSource: Boolean = false,
        naturallyEnded: Boolean = false,
    ): HistoryEligibility {
        if (incognito) return HistoryEligibility.INCOGNITO
        if (secureSource) return HistoryEligibility.SECURE_SOURCE
        if (naturallyEnded || (durationMillis != null && durationMillis > 0 && positionMillis >= durationMillis * completionFraction)) {
            return HistoryEligibility.COMPLETED
        }
        val threshold = minOf(30_000L, ((durationMillis ?: 300_000L) * 0.10).toLong())
        return if (positionMillis >= threshold) HistoryEligibility.ELIGIBLE else HistoryEligibility.TOO_EARLY
    }
}
