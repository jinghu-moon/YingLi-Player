package seeyuer.yingli.player.domain.playback

/**
 * 帧扫描的取消标记（只读面）：扫描循环只用它回答"还要不要继续读下去"。
 *
 * 为什么自己定一个：`MediaExtractor` 在 API 36 上**没有** `setCancellationSignal`（javap 确认），
 * 它没有任何"中止当前 extractor"的入口。所以取消必须分成两层：
 * - 这一层是**标记**，扫描循环在每次进入 native 调用之前自查；
 * - 真正把阻塞中的 `advance()` 打断的是 [FrameScanLifecycle] 登记的"打断动作"（释放容器）。
 */
interface FrameScanCancellation {
    val isCancelled: Boolean
}

/** 永不取消的实现，供不需要取消的调用方（以及只需要驱动计数逻辑的测试）使用。 */
object NeverCancelledFrameScan : FrameScanCancellation {
    override val isCancelled: Boolean = false
}

/**
 * 帧扫描的生命周期状态机（纯 Kotlin，可 JVM 单测）：把"取消 / 完成 / 打断 / 结果回收"的先后顺序
 * 固定在两个原子转移上，没有别的路径。
 *
 * ```
 *   SCANNING --cancel()--> CANCELLED   第一个成功的取消者赢得"打断/释放"的义务
 *   SCANNING --finish()--> FINISHED    扫描线程正常跑完，由它自己释放
 * ```
 *
 * 为什么必须是"唯一赢家"：`MediaExtractor` **非线程安全**，重复 `release()` 与漏 `release()` 都会出事
 *（前者会二次释放 native 对象，后者泄漏 fd/解码器）。把"谁负责释放"和状态转移绑成一次判定，
 * 重复释放从结构上不存在；失败方一律不得再碰资源，所以也不会出现"取消后还去读写已释放的 extractor"。
 *
 * 取消方（[cancel] 的赢家）会在**当前线程**执行打断动作：这样 [cancel] 一返回，资源就已经归还，
 * 调用方不必依赖异步回收（宿主作用域若紧接着被取消，异步回收任务会跟着消失，extractor 就泄漏了）。
 *
 * 并发约束（对应实现见 `AndroidFrameCountProbe`）：
 * 1. 打断动作只登记一次、只执行一次；
 * 2. 扫描线程每次进入 native 调用之前读 [isCancelled]，因此取消后最多再走完**一个**已经在跑的 native 调用；
 * 3. [canPublishResult] 在取消被要求之后恒为 false —— 宿主协程即使因为阻塞调用刚抛异常、还没走到挂起点，
 *    也不能把结果写回去（"取消后仍写回结果"就是这么来的）。
 */
class FrameScanLifecycle : FrameScanCancellation {
    enum class Phase { SCANNING, CANCELLED, FINISHED }

    private val lock = Any()

    /**
     * 释放归属状态：转移同时在 [lock] 内完成（转移要一起读写 [interrupt]，只靠 volatile 不够）。
     */
    @Volatile
    private var currentPhase: Phase = Phase.SCANNING

    /**
     * "取消被要求过"是**单调**的，且独立于 [Phase]：
     * 扫描已经跑完（FINISHED）之后宿主才来取消时，没有资源要释放，但**结果同样作废**——
     * 这一位就是给那种情况准备的。热循环里每样本读一次，所以只能是 volatile 读。
     */
    @Volatile
    private var cancelRequested: Boolean = false

    private var interrupt: (() -> Unit)? = null

    override val isCancelled: Boolean get() = cancelRequested

    val phase: Phase get() = currentPhase

    /**
     * 登记"如何真正打断这次扫描"（对 `MediaExtractor` 就是 `release()`）。
     *
     * 取消可能**先于**资源打开到达（例如切换媒体时任务还排在 IO 队列里）：这时还没有任何并发访问者，
     * 由登记方（也就是扫描线程自己）当场执行是安全且唯一的释放机会；
     * 已经 FINISHED 则说明扫描线程自己释放过了，这里必须什么都不做，否则就是重复释放。
     */
    fun attachInterrupt(interrupt: () -> Unit) {
        val runNow = synchronized(lock) {
            when (currentPhase) {
                Phase.SCANNING -> {
                    this.interrupt = interrupt
                    false
                }
                Phase.CANCELLED -> true
                Phase.FINISHED -> false
            }
        }
        if (runNow) interrupt()
    }

    /** 返回 true 表示"本次调用赢得取消权"，调用方负责执行已经登记的打断动作。 */
    fun cancel(): Boolean {
        cancelRequested = true
        val pending = synchronized(lock) {
            if (currentPhase != Phase.SCANNING) return false
            currentPhase = Phase.CANCELLED
            interrupt
        }
        pending?.invoke()
        return true
    }

    /** 返回 true 表示"扫描线程赢得终态"，由它负责释放资源；false 表示资源归取消方处理。 */
    fun finish(): Boolean = synchronized(lock) {
        if (currentPhase != Phase.SCANNING) return false
        currentPhase = Phase.FINISHED
        interrupt = null
        true
    }

    /** 只有从未被取消过才允许把结果写回宿主。 */
    fun canPublishResult(): Boolean = !cancelRequested
}

/**
 * 容器样本源契约：`MediaExtractor` 的最小投影。
 * 只是为了能注入一个假的 sample 序列来驱动 [countVideoFrames]，不引入任何抽象层。
 */
interface ContainerSampleSource : AutoCloseable {
    val trackCount: Int

    /**
     * 容器总字节数；未知长度（流式源、读不到 stat）时为 null。
     *
     * 为什么由样本源提供：它是**唯一**知道容器大小的地方（`MediaExtractor` 自己不暴露），
     * 而"文件大小"是扫描耗时/吞吐日志的必需字段——策略只能由这个实测吞吐决定。
     */
    val byteSize: Long?

    /** 返回该轨的 MIME 类型（如 `video/avc`）；轨道不存在时返回 null。 */
    fun trackMimeType(index: Int): String?

    /** 该轨的时长（微秒）；容器未提供时返回 null。 */
    fun trackDurationMicros(index: Int): Long?

    /** 选中该轨；轨道不可选时返回 false。 */
    fun selectTrack(index: Int): Boolean

    /** 当前样本的呈现时间（微秒）；没有样本时返回 -1。 */
    fun sampleTimeMicros(): Long

    /** 前进到下一个样本；没有更多样本时返回 false。 */
    fun advance(): Boolean
}

/**
 * 一次扫描的完整事实：结果 + "为什么结束" + 扫描量 + 容器大小。
 *
 * 为什么要把"被取消"和"扫了多少样本"显式带回来：取消与"没有视频轨"在结果上都表现为"没有时间轴"，
 * 但调用方动作完全相反（取消**不许**发布任何结果，无视频轨要发布 `Failed`）；
 * 扫描量则让"耗时/吞吐"这条日志在取消路径上也有据可查。
 */
data class FrameScanReport(
    val timeline: VideoSampleTimeline?,
    val scannedSamples: Long,
    val cancelled: Boolean,
    val byteSize: Long?,
)

/**
 * 视频样本时间轴探针（可注入）：真实实现用 `MediaExtractor` 只读容器统计视频轨 sample 数。
 *
 * 放在域层的只是一份**数据契约**，不含任何 Android 类型；
 * 平台实现见 `AndroidFrameCountProbe`，因此"帧率派生规则 / 结果处理 / 取消 / 异常"
 * 都能在没有 Android 运行时的 JVM 单测里驱动。
 */
fun interface FrameCountProbe {
    /**
     * 阻塞式探测（调用方保证在 IO 线程）。返回 null 表示"这份 URI 打不开"（无权限/容器不识别）。
     *
     * [lifecycle] 同时承担三件事：给扫描循环读取消标记、给取消方登记打断动作、判定谁负责释放资源。
     */
    fun probe(uri: String, lifecycle: FrameScanLifecycle): FrameScanReport?
}

/**
 * 统计视频轨 sample 数并给出样本时间轴（纯逻辑，全部可测）。
 *
 * **前提（与报告一并如实说明）**：常见的 AVC/HEVC + MP4/MKV 里，"一个 sample"约等于"一帧"，
 * 因为这类编码一个 access unit 就是一个 sample，容器也不会把一帧拆成多个 sample。
 * 偏差风险来自：
 * - VFR（可变帧率）：帧数依然对，但"总帧数 ÷ 时长"得到的是**平均**帧率，
 *   按它算出的帧号在高/低帧率段会有偏差；
 * - 场编码 / 每帧多个 sample（如某些 MKV 或 field-coded 内容）：sample 数会多于帧数；
 * - 不支持的容器：`MediaExtractor` 读不出轨道（无视频轨）时返回 null，调用方保持估算。
 *
 * 取消语义：**取消一律不产出时间轴**（返回的报告里 `timeline == null, cancelled = true`）。
 * 报一个"扫到一半"的帧数比不校准更糟：帧号会整体偏到错误的位置。
 */
fun countVideoFrames(source: ContainerSampleSource, cancellation: FrameScanCancellation): FrameScanReport {
    val byteSize = source.byteSize

    fun stopped(cancelled: Boolean, scanned: Long = 0L): FrameScanReport =
        FrameScanReport(timeline = null, scannedSamples = scanned, cancelled = cancelled, byteSize = byteSize)

    val videoTrack = (0 until source.trackCount).firstOrNull { index ->
        source.trackMimeType(index)?.startsWith(VIDEO_MIME_PREFIX) == true
    } ?: return stopped(cancelled = cancellation.isCancelled)
    val trackDurationMicros = source.trackDurationMicros(videoTrack)
    if (!source.selectTrack(videoTrack)) return stopped(cancelled = cancellation.isCancelled)

    var frameCount = 0L
    var firstSampleTimeMicros = 0L
    var lastSampleTimeMicros = 0L
    try {
        while (true) {
            // 每个样本自查一次取消标记：读一个 volatile 字段的成本远小于循环体里那次 JNI 调用
            //（实测样本吞吐见 docs/19），所以"取消后最多再走一个 native 调用"这个界是白拿的。
            if (cancellation.isCancelled) return stopped(cancelled = true, scanned = frameCount)
            val sampleTimeMicros = source.sampleTimeMicros()
            if (sampleTimeMicros < 0L) break
            val isFirstSample = frameCount == 0L
            if (isFirstSample) firstSampleTimeMicros = sampleTimeMicros
            lastSampleTimeMicros = sampleTimeMicros
            frameCount += 1L
            if (!source.advance()) break
        }
    } catch (error: RuntimeException) {
        // 取消方在另一个线程 release 了容器：阻塞中的 native 调用会以运行时异常退出。
        // 只有确实处于取消状态时才把它归类为"已取消"；否则原样抛出——异常不许被吞掉。
        if (cancellation.isCancelled) return stopped(cancelled = true, scanned = frameCount)
        throw error
    }
    if (cancellation.isCancelled) return stopped(cancelled = true, scanned = frameCount)
    if (frameCount <= 0L) return stopped(cancelled = false)
    return FrameScanReport(
        timeline = VideoSampleTimeline(
            frameCount = frameCount,
            firstSampleTimeMicros = firstSampleTimeMicros,
            lastSampleTimeMicros = lastSampleTimeMicros.coerceAtLeast(firstSampleTimeMicros),
            trackDurationMicros = trackDurationMicros?.takeIf { it > 0L },
        ),
        scannedSamples = frameCount,
        cancelled = false,
        byteSize = byteSize,
    )
}

internal const val VIDEO_MIME_PREFIX = "video/"

/**
 * 是否允许对这份 URI 做逐帧校准。
 *
 * **只对本地文件 / 本地 content URI 校准**：统计 sample 数必须从头读到尾。
 * 本地文件这是一次文件顺序读（按解码器读数据的带宽而言很快），
 * 而 http(s) 源要做到同样的事就得**把整段视频下载一遍**——为了一个帧号显示去耗
 * 用户的流量与时间是不可接受的，所以网络源直接跳过校准、继续显示估算值。
 *
 * 不认识的 scheme（自定义管道、vault 等）同样按"宁可估算"处理：无法保证读取代价。
 */
fun canCalibrateFramesLocally(uri: String): Boolean = when (localMediaUriKind(uri)) {
    LocalMediaUriKind.FILE, LocalMediaUriKind.CONTENT -> true
    LocalMediaUriKind.REMOTE, LocalMediaUriKind.UNKNOWN -> false
}

/** 本地媒体 URI 的种类（见 [canCalibrateFramesLocally]）。 */
enum class LocalMediaUriKind { FILE, CONTENT, REMOTE, UNKNOWN }

/**
 * 判断 URI 种类。裸路径（没有 scheme）按文件处理——`MediaExtractor.setDataSource(path)` 的既有用法。
 * 大小写不敏感：`HTTPS://` 也是网络源。
 */
fun localMediaUriKind(uri: String): LocalMediaUriKind {
    val trimmed = uri.trim()
    if (trimmed.isEmpty()) return LocalMediaUriKind.UNKNOWN
    val colonIndex = trimmed.indexOf(':')
    val slashIndex = trimmed.indexOf('/')
    // 没有冒号（或冒号在第一个分隔符之后）说明不是 `scheme:` 形式，而是裸路径。
    if (colonIndex <= 0 || (slashIndex in 0 until colonIndex)) return LocalMediaUriKind.FILE
    return when (trimmed.substring(0, colonIndex).lowercase()) {
        "file" -> LocalMediaUriKind.FILE
        "content", "android.resource" -> LocalMediaUriKind.CONTENT
        "http", "https", "rtsp", "rtmp", "hls", "dash" -> LocalMediaUriKind.REMOTE
        else -> LocalMediaUriKind.UNKNOWN
    }
}
