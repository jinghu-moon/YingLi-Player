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

/** 没有可用帧率时的保护间隔：取 30fps 的一帧（33.3ms 向上取整）。 */
const val AB_LOOP_FALLBACK_FRAME_RATE = 30f

/**
 * 名义帧率吸附：`frameIndex = round(ms * fps / 1000)` → `snappedMs = round(frameIndex * 1000 / fps)`。
 *
 * 为什么不能写成 `round(ms / frameDuration) * frameDuration`：非整数帧率（29.97、23.976）下
 * 帧时长本身就是个无限小数，先算时长再乘回去会把取整误差放大到整整一帧；按**帧索引**往返
 * 才能保证"落在第 N 帧的时间点上"。
 *
 * 这是**名义帧率吸附**（D13）：VFR 素材不承诺命中真实帧边界，只保证吸附值对得上声明的 fps。
 * 帧率缺失（null / 非正数）时原样返回：拿不到帧率还去猜一个间隔，只会把用户设的点挪走。
 */
fun snapAbMillisToFrame(positionMillis: Long, frameRate: Float?): Long {
    val fps = frameRate?.takeIf { it > 0f } ?: return positionMillis
    val frameIndex = Math.round(positionMillis.toDouble() * fps / 1_000.0)
    return Math.round(frameIndex.toDouble() * 1_000.0 / fps)
}

/** 吸附后是否至少有"一帧"的名义间隔；拿不到帧率时退化为"至少 1ms"（[AbLoopState] 的不变量）。 */
private fun hasFrameSeparation(startMillis: Long, endMillis: Long, frameRate: Float?): Boolean {
    val fps = frameRate?.takeIf { it > 0f }
    if (fps == null) return endMillis > startMillis
    return (endMillis - startMillis) * fps >= 1_000f
}

/**
 * A-B 循环的域规则（纯函数，JVM 可测）。
 *
 * ## B 侧：`max(播放头, A + 1s)` 兜底（本批口径）
 *
 * 在 A 之前（或离 A 不足 1 秒）按 B，**不再拒绝、也不再互换**，而是把 B 取到
 * `max(播放头, A + 1s)` —— demo 的事实口径（`docs/21` 口径 3）。理由是一个真实缺陷：
 * 用户把播放头停在 A 附近按 B 时，"拒绝"会让区间永远设不上，而用户完全不知道阈值在哪。
 * 1 秒的兜底把"按了 B 却什么也没发生"变成"B 落在 A 之后 1 秒处"，语义仍然正确。
 *
 * 兜底值随后按**名义帧率吸附**（[snapAbMillisToFrame]，D5/D13），再**夹到片长**
 * （[AbLoopLimiter.setPoint] 的 `durationMillis`），最后校验"至少一帧间距"（[hasFrameSeparation]）——
 * 夹完之后仍不足一帧（例如 A 已经贴在片尾）才**拒绝**，并由会话把拒绝码回流成用户可见的提示。
 *
 * ## A 侧：压过 B 就互换、恰好等于 B 就拒绝（保留既有口径）
 *
 * demo 未定义 A 侧（它只写了"重设 A 到播放头；若压过 B，B 回到待落点"），所以这条是本项目的补齐：
 * 按在 B 之后说明用户是在倒着划区间，此刻把新旧两点互换才符合"先设的那端当 B"的直觉；
 * **位置 == B 时拒绝**：互换会立刻产生 `A == B`，违反 [AbLoopState] 的 `pointA < pointB` 不变量，
 * 宁可不改状态也不制造非法区间。
 *
 * **不在这里做任何"把用户 seek 钳回区间内"的事**：D8-A 明确允许循环期间拖到区间外
 *（阶段 0 裁决保留），钳制属于被删掉的旧语义。
 */
class AbLoopLimiter(private val fallbackFrameRate: Float = AB_LOOP_FALLBACK_FRAME_RATE) {
    init { require(fallbackFrameRate > 0) }

    /**
     * 设点。[durationMillis] 是**片长**（未知时传 null）：只有 B 侧用它做"夹到片长"，
     * 因为 `A + 1s` 的兜底值在片尾附近可能越界 —— 越界的 B 会立刻触发循环回跳，
     * 那既不是用户要的区间，也会让引擎反复 seek 到片尾。
     */
    fun setPoint(
        state: AbLoopState,
        point: AbPoint,
        positionMillis: Long,
        frameRate: Float?,
        durationMillis: Long? = null,
    ): AbLoopSetPointResult {
        val position = snapAbMillisToFrame(positionMillis.coerceAtLeast(0), frameRate).coerceAtLeast(0)
        return when (point) {
            AbPoint.A -> setPointA(state, position, frameRate)
            AbPoint.B -> setPointB(state, position, frameRate, durationMillis)
        }
    }

    private fun setPointA(state: AbLoopState, position: Long, frameRate: Float?): AbLoopSetPointResult {
        val end = state.pointB
        // 位置落在 B 之后（同一帧也算"之后"）：互换 —— 新点成为 B，旧的 B 成为 A。
        if (end != null && position >= end) {
            return if (hasFrameSeparation(end, position, frameRate)) {
                AbLoopSetPointResult.Applied(AbLoopState(pointA = end, pointB = position))
            } else {
                AbLoopSetPointResult.Rejected(PlaybackCommandRejection.INVALID_AB_RANGE)
            }
        }
        // 位置在 B 之前：A 换人，旧的 B 不再有"至少一帧"的余量时一并丢弃，避免留下非法区间。
        return AbLoopSetPointResult.Applied(
            AbLoopState(pointA = position, pointB = end?.takeIf { hasFrameSeparation(position, it, frameRate) }),
        )
    }

    /**
     * B 侧规则（见类文档）：兜底 `max(位置, A + 1s)` → 吸附到帧（已在 [setPoint] 里对位置做过，
     * 兜底值这里再做一次）→ 夹到片长 → 校验至少一帧间距，不满足就拒绝。
     */
    private fun setPointB(
        state: AbLoopState,
        position: Long,
        frameRate: Float?,
        durationMillis: Long?,
    ): AbLoopSetPointResult {
        val start = state.pointA
            ?: return AbLoopSetPointResult.Rejected(PlaybackCommandRejection.INVALID_AB_RANGE)
        // 兜底：不超过 A + 1s。用 max 而不是"拒绝/互换"，见类文档。
        val withFallback = maxOf(position, start + AB_POINT_B_MIN_GAP_MILLIS)
        // 兜底值也要吸附到帧：`A + 1s` 落在一个半帧的位置上时，区间长度就不再是整帧数，
        // 而"至少一帧"的校验必须在**吸附之后**做，否则会出现"校验通过但吸附后塌成一帧"。
        val snapped = snapAbMillisToFrame(withFallback, frameRate)
        // 夹到片长：B 越界会让引擎在片尾反复回跳。
        val clamped = if (durationMillis != null) snapped.coerceAtMost(durationMillis) else snapped
        if (!hasFrameSeparation(start, clamped, frameRate)) {
            // 夹完之后仍不足一帧（A 已经贴在片尾）：拒绝，并由会话回流成用户可见提示。
            return AbLoopSetPointResult.Rejected(PlaybackCommandRejection.INVALID_AB_RANGE)
        }
        return AbLoopSetPointResult.Applied(AbLoopState(pointA = start, pointB = clamped))
    }

    fun clear(): AbLoopState = AbLoopState()

    /** 名义一帧的毫秒数（向上取整）；帧率缺失时用 [fallbackFrameRate]。 */
    fun frameDurationMillis(frameRate: Float?): Long =
        ceil((1_000f / (frameRate?.takeIf { it > 0f } ?: fallbackFrameRate)).coerceAtLeast(1f)).toLong()
}

/**
 * 设 B 时"播放头早于 A"的兜底间隔：**1 秒**（`docs/21` 口径 3 的 `A + 1s`）。
 *
 * 为什么不是一个帧间隔：兜底要产生一个**用户看得出来**的区间。一帧（33ms）在进度条上不可见，
 * 用户按了 B 之后会以为"没生效"；1 秒既在进度条上看得见，又在读数条上读得出（`Δ 00:01`）。
 * 它是**下限**而不是目标值：播放头在 A + 1s 之后时，B 就落在播放头上。
 */
const val AB_POINT_B_MIN_GAP_MILLIS = 1_000L

sealed interface AbLoopSetPointResult {
    data class Applied(val state: AbLoopState) : AbLoopSetPointResult
    data class Rejected(val rejection: PlaybackCommandRejection) : AbLoopSetPointResult
}

/**
 * AB 循环是否可用。**判定时机是播放器准备完成之后**：`PlaybackTimeline` 来自引擎 timeline
 *（`durationMillis` = `player.duration`、`isSeekable` = `isCurrentMediaItemSeekable`），
 * **不是**来源类型或最初 metadata —— 保险库媒体的 handle 时长可能是未知值，准备完成后才有真值。
 */
enum class AbLoopAvailability { ENABLED, UNKNOWN_DURATION, NOT_SEEKABLE }

fun abLoopAvailability(durationMillis: Long?, isSeekable: Boolean): AbLoopAvailability = when {
    durationMillis == null || durationMillis <= 0L -> AbLoopAvailability.UNKNOWN_DURATION
    !isSeekable -> AbLoopAvailability.NOT_SEEKABLE
    else -> AbLoopAvailability.ENABLED
}

sealed interface AbLoopEvent {
    data class SetPoint(
        val point: AbPoint,
        val positionMillis: Long,
        val frameRate: Float?,
        /**
         * 片长（未知时 null）：**只有 B 侧用它做"夹到片长"**——`max(播放头, A + 1s)` 的兜底值
         * 在片尾附近可能越界，越界的 B 会让引擎在片尾反复回跳（见 [AbLoopLimiter.setPoint]）。
         */
        val durationMillis: Long? = null,
    ) : AbLoopEvent
    data object Clear : AbLoopEvent
    data object MediaChanged : AbLoopEvent
}

data class AbLoopUpdate(val state: AbLoopState, val rejection: PlaybackCommandRejection? = null)

class AbLoopReducer(private val limiter: AbLoopLimiter = AbLoopLimiter()) {
    fun reduce(state: AbLoopState, event: AbLoopEvent): AbLoopUpdate = when (event) {
        is AbLoopEvent.SetPoint -> when (
            val result = limiter.setPoint(
                state,
                event.point,
                event.positionMillis,
                event.frameRate,
                event.durationMillis,
            )
        ) {
            is AbLoopSetPointResult.Applied -> AbLoopUpdate(result.state)
            is AbLoopSetPointResult.Rejected -> AbLoopUpdate(state, result.rejection)
        }
        AbLoopEvent.Clear, AbLoopEvent.MediaChanged -> AbLoopUpdate(limiter.clear())
    }
}

enum class BackendId { MEDIA3, MPV }
enum class SurfaceOwner { REGULAR_PLAYER, SHORTS, PICTURE_IN_PICTURE }
enum class RequestedOrientation { SENSOR, PORTRAIT, LANDSCAPE }
enum class FrameStepDirection { PREVIOUS, NEXT }

/**
 * 跳转来源。
 *
 * **`AB_LOOP` 只描述"引擎内部的循环回跳"**：它不经过命令层，是引擎在
 * [PlaybackEngineEvent.AbBoundaryReached] 之后对自己的播放器直接做的一次 seek
 *（见 `AbBoundaryWatcher`），因此**没有任何客户端命令会带上它**。
 *
 * 反过来说，用户发起的跳转（`USER` / `DRAG_END` / `FRAME_STEP`）永远不会冒充循环回跳 ——
 * 这正是"循环计数只认引擎自然边界事件"的前提：如果把回跳表达成用户 seek，
 * 或者让用户 seek 看起来像回跳，计数就变成了按位置猜测。
 *
 * **`AB_ACTIVATION` 描述"会话把 AB 区间激活时那一次回到 A"**（两者都是引擎侧发起的跳转）：
 * 它与 `AB_LOOP` 的区别是**归属与时机不同**，因此不能共用取值 ——
 * - `AB_LOOP` 是引擎在**自然抵达 B** 之后对自己的播放器做的循环回跳（周期性的、由边界事件触发）；
 * - `AB_ACTIVATION` 是会话在**区间刚被设全**时发起的唯一一次跳转（一次性的、由设点命令触发），
 *   它由 [PlaybackEngine.activateAbLoop] 执行。
 * 写死在两个取值上，是为了让日志与后续行为（例如"用户 seek 作废旧边界消息"）能区分
 * "这一跳是循环在跑"与"这一跳是用户刚把区间设全"，而不是把两件事都读成同一个原因。
 * 两者都**不是**用户跳转：用户跳转一律带 `USER` / `DRAG_END` / `FRAME_STEP`。
 */
enum class SeekOrigin { USER, DRAG_END, FRAME_STEP, AB_LOOP, AB_ACTIVATION }
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
    /**
     * 当前 AB 区间的自然边界循环次数（D9）。
     *
     * **只由 runtime 在该 generation 的自然边界事件上递增**，随清除/切媒体归零；
     * 客户端（bridge / ViewModel）不得自行累计：它一旦被第二处写入，就再也说不清
     * "这个数字是不是按位置猜出来的"。
     */
    val loopCount: Long = 0,
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

/**
 * A-B 循环的下发配置。`generation` 由会话 runtime 生成并持有（见 `PlaybackSessionRuntime`），
 * 引擎只消费：区间换了 generation 就一定要换，引擎据此作废旧 generation 的边界检测。
 *
 * 坐标一律是**引擎 timeline 的绝对媒体毫秒**。方案 A 不裁剪媒体源（阶段 0 裁决），
 * 因此不存在"窗口坐标 ↔ 媒体时间坐标"的映射问题；该坐标系事实仍记录在此以免后续重新引入裁剪。
 */
data class EngineAbLoop(
    val generation: Long,
    val pointAMillis: Long,
    val pointBMillis: Long,
) {
    init {
        require(generation >= 0)
        require(pointAMillis >= 0)
        require(pointBMillis > pointAMillis)
    }
}

/** 引擎主动上报的事件。只放"只有引擎知道"的事实，不塞 UI 关注的东西。 */
sealed interface PlaybackEngineEvent {
    /**
     * 播放**自然**抵达 B：位置在连续播放中从 B 之前推进到 B 或越过 B。
     *
     * 只有当前 generation 的该事件才允许被 runtime 计入循环次数：用户 seek 越过 B、
     * 重缓冲造成的位置回退、以及旧 generation 的晚到事件都会由引擎或 runtime 作废
     *（否则计数就退化成"按位置猜测"，正是本次重构要修掉的根因）。
     */
    data class AbBoundaryReached(val generation: Long, val positionMillis: Long) : PlaybackEngineEvent
}

interface PlaybackEngine {
    val state: StateFlow<EngineState>
    val capabilities: StateFlow<PlaybackCapabilities>

    /**
     * 引擎主动上报的事件流。
     *
     * 为什么引擎必须有这条通道：边界检测归引擎（"播到 B"这件事只有引擎自己知道），
     * 删掉 UI 轮询之后若没有事件流，循环就再也没有任何通道能触发回跳。
     */
    val events: Flow<PlaybackEngineEvent>

    /**
     * 下发 A-B 循环配置；`null` 表示关闭循环。
     *
     * 调用后引擎必须满足：旧配置的边界检测一律作废（不得再上报旧 generation 的事件），
     * 且在位置发生不连续（用户 seek / 拖动 / 逐帧 / 队列切换）时同样取消或重置旧边界检测 ——
     * 只做"把边界检测取消掉"，不做任何 A/B 业务校验（校验全在 runtime）。
     *
     * **配置生效的那一刻，若播放位置已经在 B 或之后，这一轮不得产生自然边界事件**：
     * 用户先拖到区间之后、再打开/调整 AB 就会走到这条路（见 `AbBoundarySession.configure`）。
     */
    fun configureAbLoop(loop: EngineAbLoop?)

    /**
     * 发起一次 AB 激活：**先精确跳回 A，再武装边界检测**。
     *
     * 为什么必须是一个原子入口，而不是"调用方自己 `seekTo(A)` 再 `configureAbLoop(loop)`"：
     * 边界检测器是在**配置生效那一刻**读播放器位置来决定"这一轮是否已经越过 B"的
     * （见 `AbBoundarySession.configure`）。若由调用方先配置再跳转，配置读到的还是"设在 B 上"
     * 的那个位置，于是这一轮被判定成已越过 B、定时器永不武装 —— 用户按正常流程设完 B
     * 之后循环根本不启动。把"跳回 A"放进引擎、并保证它排在武装之前，这个竞态从根上消失。
     *
     * 跳转一律用**当前生效的跳转精度**（AB 生效期间引擎强制 `EXACT`，见 `configureAbLoop`），
     * 不在这里另开一条精度通道。
     */
    fun activateAbLoop(loop: EngineAbLoop)

    suspend fun prepare(source: PlaybackSourceHandle, startPositionMillis: Long)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMillis: Long)

    /**
     * 读取**此刻**的播放位置（毫秒），供"以当前位置为依据"的决策使用。
     *
     * 为什么这是一个独立能力而不是读 `state.timeline`：引擎状态只在
     * `onPlaybackStateChanged` / `onIsPlayingChanged` 时发布，稳定播放期间 timeline 的
     * `positionMillis` 会长时间停在上一次状态变化的位置上。拿它当"当前位置"用，就会把
     * 设点、截图时间戳、上一项判定全部做在陈旧值上（真机实测：静置播放 60s 后该值仍为 0）。
     *
     * 实现必须直读播放器自己的当前位置，**不得**返回任何缓存/插值/快照值。
     * 线程约束：实现访问的是播放器，调用方必须在该播放器的应用线程（服务主线程）上调用。
     */
    fun currentPositionMillis(): Long
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
     * 读取**此刻**的播放位置（毫秒），供 UI 侧"以当前位置为依据"的判定使用。
     *
     * 与 [PlaybackEngine.currentPositionMillis] 是同一条规矩的两端：快照的
     * `timeline.positionMillis` 只在状态跳变时刷新，稳定播放期间是陈旧值，
     * 因此"按上一项要不要先回本集开头""相对跳转的基准"这类判定必须问这里。
     *
     * 客户端拿到的实时位置来自 `MediaController.currentPosition`（服务侧引擎的同一份事实，
     * 只经过一次会话边界），不是本地插值。
     */
    fun currentPositionMillis(): Long

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
