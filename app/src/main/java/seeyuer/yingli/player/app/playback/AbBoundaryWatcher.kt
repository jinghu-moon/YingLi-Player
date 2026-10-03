package seeyuer.yingli.player.app.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import seeyuer.yingli.player.domain.playback.EngineAbLoop

/**
 * 把纯状态机 [AbBoundarySession] 接到真实播放器上：位置采样、定时边界消息、事件回调与回跳。
 *
 * ## 为什么边界检测必须在引擎里
 *
 * "播到 B"只有播放器自己知道。跑在引擎这一侧（service 主线程），UI 退出、页面销毁、锁屏、
 * 后台都不会让它停 —— 这正是 D4（后台/画中画/锁屏下循环继续生效）的前提。放在 ViewModel 里
 * 意味着循环的存活依赖 UI 存活，且位置只能靠推算。
 *
 * ## 为什么不用 Media3 `PlayerMessage` 定点消息
 *
 * 评估过 `PlayerMessage.setPosition(B)`，结论是**不可靠**（依据 Media3 1.10.1 源码
 * `ExoPlayerImplInternal.maybeTriggerPendingMessages` 与 `handlePositionDiscontinuity`）：
 * - 它只在**播放推进**时被检查（`doSomeWork` 里 `rendererPositionUs` 前进的那条分支），
 *   暂停/缓冲期间完全不被检查；
 * - 触发条件是"本次推进窗口跨过 B"（`resolvedTimeUs > oldPositionUs && <= newPositionUs`），
 *   而位置不连续会先把 `playbackInfo.positionUs` 更新为落点，于是用户从 B 之前 seek 到 B 之后时
 *   窗口里 `B > 落点` 不成立 → 消息**静默不投递**。语义上正好是我们要的"用户 seek 越界不算
 *   自然抵达"，但这也说明它完全依附于播放器内部调度时机，不能作为唯一检测手段；
 * - 消息的 media item index 是创建时的默认下标，`setMediaItem` 之后旧消息的归属只能靠
 *   `resolvePendingMessagePosition` 尽力修正。
 *
 * 因此改用"按当前播放位置与倍速算出到 B 的真实等待时间 → `Handler.postDelayed` → 到点再采一次
 * 位置确认"。判定的唯一依据是**播放器自己报告的位置**，与播放器内部调度细节解耦，
 * 暂停/缓冲/换源/后台都不改变判定规则；"是否自然抵达"由 [AbBoundarySession] 的纯规则决定。
 *
 * ## 为什么需要 generation
 *
 * 改 A/B、清除、切媒体都会产生新一轮配置，而旧配置的定时回调可能还在飞行中。没有 generation，
 * 晚到的旧回调会污染新配置的循环计数 —— 那等于把"位置猜测"从 ViewModel 搬到引擎。
 */
internal class AbBoundaryWatcher(
    private val player: ExoPlayer,
    private val onBoundaryReached: (generation: Long, positionMillis: Long) -> Unit,
    private val onSeekToPointA: (positionMillis: Long) -> Unit,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : Player.Listener {
    private val session = AbBoundarySession()
    private var configured: EngineAbLoop? = null

    private val timer = Runnable { onTimerFired() }

    init {
        player.addListener(this)
    }

    /**
     * 只换配置（**不移动位置**）。
     *
     * 这里是**唯一**由"读位置"推出"是否已越过 B"的地方，语义是：
     * **配置生效那一刻，播放器位置是否已经在 B 或 B 之后**。
     * 所以它必须在"区间刚被设全且位置还停在 B 上"的那种调用之前被 [activate] 取代 ——
     * 否则这一轮会被判成已越过、定时器永不武装（用户按正常流程设完 B 就得 0 次回跳）。
     *
     * 这条路用于：区间只设了一端、清除、切媒体，以及不涉及跳转的重新武装。
     */
    fun configure(loop: EngineAbLoop?) {
        handler.removeCallbacks(timer)
        configured = loop
        val position = position()
        session.configure(
            loop = loop,
            positionMillis = position,
            // 位置真的在 B 之后才配置：这一轮没有可行的自然抵达点（见 AbBoundarySession.configure）。
            alreadyPastBoundary = loop != null && position >= loop.pointBMillis,
        )
        // 仍然无条件排一次定时消息（与改动前一致）：已越过 B 时它到点后只会重采一次位置、
        // 不做任何判定，代价可以忽略；而 `configure` 只负责"换配置"，不额外承担
        // "要不要武装"的判断 —— 那个判断归状态机。把两件事分开，才不会在
        // "已经越过 B 之后又发生一次位置回退"这类路径上漏掉重新武装。
        if (loop != null) scheduleTimer()
    }

    /**
     * 激活（`PlaybackEngine.activateAbLoop`）：**位置已经被精确跳到 A，本方法只负责武装**。
     *
     * 为什么 `alreadyPastBoundary` 在这里必须是 `false`，而不是"再读一次位置"：激活的定义就是
     * "位置被放到 A"，而 `EngineAbLoop` 的不变量保证 `A < B`，所以激活之后位置**必然**在 B 之前。
     * 读位置作为依据会在两种情况下给出错误答案：位置尚未异步反映跳转结果时读到跳转前的值 ——
     * 而用户恰恰就是把 B 设在当前位置上，那正是"设完 B 就永不武装"的成因。
     * 因此这里按调用方刚做的事直接给答案，不做推断。
     */
    fun activate(loop: EngineAbLoop) {
        handler.removeCallbacks(timer)
        configured = loop
        session.configure(loop = loop, positionMillis = position(), alreadyPastBoundary = false)
        scheduleTimer()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (configured == null) return
        handler.removeCallbacks(timer)
        handleAction(session.onDiscontinuity(newPosition.positionMs.coerceAtLeast(0), speed()))
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        if (configured == null) return
        handleAction(session.onPlaybackSpeedChanged(playbackParameters.speed))
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (configured == null) return
        handler.removeCallbacks(timer)
        if (!isPlaying) return
        // 恢复播放：补一次采样（暂停期间位置可能被 seek 改过，但那条路径由不连续回调处理过），
        // 再重排定时消息。
        handleAction(session.onPositionSample(position(), speed()))
        scheduleTimer()
    }

    fun close() {
        handler.removeCallbacks(timer)
        player.removeListener(this)
        configured = null
        // 关闭没有"是否已越过"的问题：loop == null 时该标记不参与任何判定。
        session.configure(null, position(), alreadyPastBoundary = false)
    }

    /**
     * 定时回调：到点后再采一次位置确认，绝不直接"到点即判定跨过 B"。
     *
     * 原因：估算延迟只按当前倍速线性外推，缓冲/卡顿会让真实位置落后于估算，
     * 直接判定会在还没到 B 的时候就回跳（表现为循环点提前）。
     */
    private fun onTimerFired() {
        val action = session.onTimerFired(position(), speed())
        handleAction(action)
        // 还没到 B：继续按剩余距离排下一次（`isTimerArmed` 表达的是"这一轮配置还需要定时确认"，
        // 由状态机自己维护，不在回调里重置）。
        if (action is AbBoundarySession.SampleAction.NONE && session.isTimerArmed) scheduleTimer()
    }

    private fun handleAction(action: AbBoundarySession.SampleAction) {
        when (action) {
            AbBoundarySession.SampleAction.NONE -> Unit
            AbBoundarySession.SampleAction.ARM -> scheduleTimer()
            is AbBoundarySession.SampleAction.REPORT_NOW -> handleBoundary(action.signal)
        }
    }

    /** 自然抵达 B：先上报事件（只有当前 generation 的自然事件才允许计数），再由引擎执行回跳。 */
    private fun handleBoundary(signal: AbBoundarySignal) {
        val loop = configured ?: return
        if (loop.generation != signal.generation) return
        handler.removeCallbacks(timer)
        onBoundaryReached(signal.generation, signal.positionMillis)
        onSeekToPointA(signal.pointAMillis)
    }

    private fun scheduleTimer() {
        configured ?: return
        handler.removeCallbacks(timer)
        handler.postDelayed(timer, session.delayToBoundaryMillis(position(), speed()))
    }

    private fun position(): Long = player.currentPosition.coerceAtLeast(0)

    private fun speed(): Float = player.playbackParameters.speed
}
