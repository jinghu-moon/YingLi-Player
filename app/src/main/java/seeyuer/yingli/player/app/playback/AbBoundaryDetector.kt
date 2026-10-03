package seeyuer.yingli.player.app.playback

import seeyuer.yingli.player.domain.playback.EngineAbLoop
import seeyuer.yingli.player.domain.playback.PlaybackEngineEvent

/**
 * 引擎内部边界检测的**纯状态机**（不碰 Android / Media3，JVM 可测）。
 *
 * 为什么把判定抽成纯函数：边界检测的难点全在"哪些位置变化算自然抵达 B"这一组判定上，
 * 而它恰好是错的代价最高的地方（错了就变成按位置猜测计数）。把它和 Handler/PlayerMessage
 * 分开之后，用户 seek、暂停、重缓冲回退、旧 generation 晚到这些场景都能在 JVM 单测里逐条钉死。
 */
internal class AbBoundarySession {
    private var loop: EngineAbLoop? = null
    private var boundaryVisited = false
    private var lastSampleMillis = 0L
    private var sampled = false
    private var timerArmed = false
    private var lastPlaybackSpeed = 1f

    /**
     * 换配置，并**显式**告知"这一轮配置是否已经越过 B"。
     *
     * `alreadyPastBoundary` 的定义（唯一权威，调用方与测试都按这一条）：
     * **本次配置生效的那一刻，播放器位置是否已经在 B 或 B 之后。**
     *
     * 为什么由调用方传进来、而不是在这里自己判断：这个布尔值的语义取决于**调用方刚刚做了什么**。
     * [AbBoundaryWatcher.configure] 是"只换配置"（此时位置真值就是唯一依据），而
     * [AbBoundaryWatcher.activate] 是"刚刚把位置跳到 A 再武装"（此时位置**必然**在 B 之前，
     * 与跳转前的位置无关）。把两种情形都压成"读一次位置"正是上一版永不武装的成因：
     * 用户就是把 B 设在当前位置上，配置生效时播放已越过 B，于是这一轮被判成已越过、
     * 定时器不再武装，循环再也不启动。现在由调用方按自己刚做的事给出答案，不再有隐含推断。
     *
     * 语义上的动机保持不变：位置真的在 B 之后才配置（用户先拖到区间之后、再打开/调整 AB），
     * 这一轮没有可行的自然抵达点，必须直接算作已越过 —— 否则第一次采样会把它当成
     * "自然跨过 B"补报一次，用户只是拖了下进度条却凭空多出一次循环。
     */
    fun configure(
        loop: EngineAbLoop?,
        positionMillis: Long,
        alreadyPastBoundary: Boolean,
    ) {
        this.loop = loop
        sampled = true
        lastSampleMillis = positionMillis
        lastPlaybackSpeed = 1f
        if (loop == null) {
            boundaryVisited = false
            timerArmed = false
            return
        }
        boundaryVisited = alreadyPastBoundary
        timerArmed = !boundaryVisited
    }


    /** 位置采样。返回需要做的一次动作（立即判定跨过 B / 重新武装定时消息）。 */
    fun onPositionSample(positionMillis: Long, playbackSpeed: Float): SampleAction {
        val target = loop ?: return SampleAction.NONE
        val previous = lastSampleMillis
        val hadSample = sampled
        lastSampleMillis = positionMillis
        sampled = true
        if (hadSample && positionMillis < previous) {
            // 位置回退（用户往回拖、或重缓冲回退）：又回到 B 之前，重新武装边界消息。
            boundaryVisited = false
            timerArmed = true
            return SampleAction.ARM
        }
        if (boundaryVisited) return SampleAction.NONE
        if (positionMillis < target.pointBMillis) return SampleAction.NONE
        // 位置从 B 之前推进到 B 或越过 B。
        //
        // 为什么这里不需要"必须正在播放"这个条件：`player.currentPosition` 是播放器**已上报**的位置，
        // 暂停/缓冲期间它被冻住（`ExoPlayerImplInternal.updatePlaybackPositions` 只在渲染位置推进时
        // 才更新它），所以"暂停时位置 >= B"根本不可能由自然播放产生；而用户 seek 造成的位置跳变
        // 一定伴随一次位置不连续，由 [onDiscontinuity] 先一步把 [boundaryVisited] 置位。
        // 反过来，如果这里额外要求 isPlaying，反而会漏掉"跨过 B 与缓冲开始发生在同一拍"的情形。
        boundaryVisited = true
        timerArmed = false
        return SampleAction.REPORT_NOW(signal(target, positionMillis, playbackSpeed))
    }

    /**
     * 位置不连续（用户 seek / 拖动 / 逐帧 / 队列切换 / 引擎自己的回跳 / 重缓冲位置回退）。
     *
     * 这里一律先作废旧边界检测：跨过 B 若发生在这段不连续里，就不是"自然抵达"。
     * 落点 >= B 只记状态不武装（否则重新武装会立刻补报一次假的自然事件）；落点 < B 重新武装。
     */
    fun onDiscontinuity(positionMillis: Long, playbackSpeed: Float): SampleAction {
        val target = loop ?: return SampleAction.NONE
        sampled = true
        lastSampleMillis = positionMillis
        lastPlaybackSpeed = playbackSpeed
        boundaryVisited = positionMillis >= target.pointBMillis
        timerArmed = !boundaryVisited
        return if (boundaryVisited) SampleAction.NONE else SampleAction.ARM
    }

    /** 引擎的定时消息到点：再采一次位置，避免缓冲把"到点"变成误报。 */
    fun onTimerFired(positionMillis: Long, playbackSpeed: Float): SampleAction =
        onPositionSample(positionMillis, playbackSpeed)

    fun onPlaybackSpeedChanged(playbackSpeed: Float): SampleAction {
        lastPlaybackSpeed = playbackSpeed
        if (loop == null || boundaryVisited) return SampleAction.NONE
        // 倍速变了，"到 B 还要多久"随之改变：重新武装，否则慢速下会在到达 B 之前先误报。
        timerArmed = true
        return SampleAction.ARM
    }

    /** 定时消息是否已武装（真机侧据此决定要不要真的 postDelayed）。 */
    val isTimerArmed: Boolean get() = timerArmed

    /**
     * 到 B 的延迟：按当前播放位置与倍速把剩余播放时间换算成真实等待时间，
     * 并留一点提前量（[SAMPLE_LEAD_MILLIS]），宁可让定时消息略早到、
     * 由定时回调里的采样兜底，也不要晚到 —— 晚到意味着多播了一帧以上的越界内容。
     */
    fun delayToBoundaryMillis(positionMillis: Long, playbackSpeed: Float): Long {
        // 没有配置（或已经处理完）：不需要定时确认，延迟归零。
        val target = loop ?: return 0L
        val speed = playbackSpeed.takeIf { it > 0f } ?: 1f
        val remaining = (target.pointBMillis - positionMillis).coerceAtLeast(0L)
        val delay = kotlin.math.ceil(remaining / speed).toLong()
        return (delay - SAMPLE_LEAD_MILLIS).coerceAtLeast(0L)
    }

    private fun signal(target: EngineAbLoop, positionMillis: Long, playbackSpeed: Float) = AbBoundarySignal(
        generation = target.generation,
        positionMillis = positionMillis,
        pointAMillis = target.pointAMillis,
        pointBMillis = target.pointBMillis,
        playbackSpeed = playbackSpeed,
    )

    sealed interface SampleAction {
        data object NONE : SampleAction
        data object ARM : SampleAction
        data class REPORT_NOW(val signal: AbBoundarySignal) : SampleAction
    }

    private companion object {
        /** 定时消息提前量：留一点余量，让定时回调里的采样接管（宁可早到，不可晚到）。 */
        const val SAMPLE_LEAD_MILLIS = 4L
    }}

/**
 * "自然抵达 B"的内部信号。
 *
 * 带上 `generation` 与 `pointA/pointB` 快照，是因为这个信号可能在**配置已经换掉之后**才被处理
 *（定时消息投递与本机状态更新之间存在窗口）：那时旧信号必须整条作废，不能拿新配置去回跳。
 */
internal data class AbBoundarySignal(
    val generation: Long,
    val positionMillis: Long,
    val pointAMillis: Long,
    val pointBMillis: Long,
    val playbackSpeed: Float,
)

/** 把内部信号翻译成引擎对外的领域事件；Media3 类型不越过这里。 */
internal fun AbBoundarySignal.toEngineEvent(): PlaybackEngineEvent =
    PlaybackEngineEvent.AbBoundaryReached(generation = generation, positionMillis = positionMillis)
