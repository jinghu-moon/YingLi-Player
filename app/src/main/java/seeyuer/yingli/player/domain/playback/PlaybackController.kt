package seeyuer.yingli.player.domain.playback

import kotlinx.coroutines.flow.StateFlow

enum class PlaybackCommandRejection {
    NOT_CONNECTED,
    INVALID_STATE,
    SOURCE_UNAVAILABLE,
    NO_CANDIDATE,
    CAPABILITY_UNAVAILABLE,
    TRACK_UNAVAILABLE,
    INVALID_AB_RANGE,
    /** AB 在当前媒体上不可用：时长为未知值或当前媒体项不可 seek，循环区间无法定义。 */
    AB_UNAVAILABLE,
    STALE_COMMAND,
}

enum class PlaybackConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
}

sealed interface PlaybackCommandResult {
    data object Accepted : PlaybackCommandResult
    data object AlreadyApplied : PlaybackCommandResult
    data class Rejected(val reason: PlaybackCommandRejection) : PlaybackCommandResult
}

interface PlaybackStateRepository {
    val state: StateFlow<PlaybackState>
    val connectionState: StateFlow<PlaybackConnectionState>
}

interface PlaybackController : PlaybackStateRepository {
    fun prepare(request: PlaybackRequest): PlaybackCommandResult
    fun prepare(source: ResolvedPlaybackSource): PlaybackCommandResult = prepare(source.request)
    fun play(): PlaybackCommandResult
    fun pause(): PlaybackCommandResult
    fun seekTo(positionMillis: Long): PlaybackCommandResult
    fun stop(): PlaybackCommandResult
    fun retry(): PlaybackCommandResult

    /**
     * 读取**此刻**的播放位置（毫秒）。
     *
     * 为什么不能读 [PlaybackState] 里的 timeline 位置：那棵树只在播放状态发生跳变时重发，
     * 稳定播放期间它一直是上一次跳变时的位置（真机实测：静置播放 60s 后仍是 0）。
     * 凡"以当前位置为依据"的判定（上一项的 5 秒口径、相对跳转的基准）都必须问这里，
     * 否则判定会做在一个可能停留在几十秒之前的陈旧值上。
     *
     * 媒体尚未就绪时返回 0（与"没有位置"一致），实现不得返回缓存值。
     *
     * 刻意**不提供默认实现**：一个返回 0 或返回快照值的兜底会让"读陈旧位置做决策"这个
     * 缺陷在某个实现上悄悄复活，而调用方看不出区别。每个实现都必须明确回答"位置从哪来"。
     */
    fun currentPositionMillis(): Long
}
