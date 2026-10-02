package seeyuer.yingli.player.domain.playback

/**
 * 帧数扫描的取消标记（纯 Kotlin，故意不用 `android.os.CancellationSignal`）。
 *
 * 为什么自己定一个：`MediaExtractor` 在 API 36 上**没有** `setCancellationSignal`，
 * 它没有任何"中止当前 extractor"的入口。能做的只有让扫描循环自己定期检查标记并退出。
 * 因此取消的粒度是"每 N 个样本一次"（见 [countVideoFrames]），
 * 而不是"立刻打断系统调用"——这一点对取消时延的预期很重要（单次 `advance()` 是微秒级，
 * 所以实际退出延迟仍在毫秒量级）。
 */
interface FrameScanCancellation {
    val isCancelled: Boolean
}

/** 永不取消的实现，供不需要取消的调用方使用。 */
object NeverCancelledFrameScan : FrameScanCancellation {
    override val isCancelled: Boolean = false
}

/** 由协程持有的可取消标记：取消任务时置位，扫描循环下一次检查即退出。 */
class MutableFrameScanCancellation : FrameScanCancellation {
    @Volatile
    override var isCancelled: Boolean = false
        private set

    fun cancel() {
        isCancelled = true
    }
}

/**
 * 视频样本时间轴探针（可注入）：真实实现用 `MediaExtractor` 只读容器统计视频轨 sample 数。
 *
 * 放在域层的只是一份**数据契约**，不含任何 Android 类型；
 * 平台实现见 `AndroidFrameCountProbe`，因此"帧率派生规则 / 结果处理 / 取消 / 异常"
 * 都能在没有 Android 运行时的 JVM 单测里驱动。
 */
fun interface FrameCountProbe {
    /**
     * 阻塞式探测（调用方保证在 IO 线程）。媒体无视频轨、容器读不动、被取消时返回 null。
     */
    fun probe(uri: String, cancellation: FrameScanCancellation): VideoSampleTimeline?
}

/**
 * 容器样本源契约：`MediaExtractor` 的最小投影。
 * 只是为了能注入一个假的 sample 序列来驱动 [countVideoFrames]，不引入任何抽象层。
 */
interface ContainerSampleSource : AutoCloseable {
    val trackCount: Int

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
 * 统计视频轨 sample 数并给出样本时间轴（纯逻辑，全部可测）。
 *
 * **前提（与报告一并如实说明）**：常见的 AVC/HEVC + MP4/MKV 里，"一个 sample"约等于"一帧"，
 * 因为这类编码一个 access unit 就是一个 sample，容器也不会把一帧拆成多个 sample。
 * 偏差风险来自：
 * - VFR（可变帧率）：帧数依然对，但"总帧数 ÷ 时长"得到的是**平均**帧率，
 *   按它算出的帧号在高/低帧率段会有偏差；
 * - 场编码 / 每帧多个 sample（如某些 MKV 或 field-coded 内容）：sample 数会多于帧数；
 * - 不支持的容器：`MediaExtractor` 读不出轨道（无视频轨）时返回 null，调用方保持估算。
 */
fun countVideoFrames(source: ContainerSampleSource, cancellation: FrameScanCancellation): VideoSampleTimeline? {
    val videoTrack = (0 until source.trackCount).firstOrNull { index ->
        source.trackMimeType(index)?.startsWith(VIDEO_MIME_PREFIX) == true
    } ?: return null
    val trackDurationMicros = source.trackDurationMicros(videoTrack)
    if (!source.selectTrack(videoTrack)) return null

    var frameCount = 0L
    var firstSampleTimeMicros = 0L
    var lastSampleTimeMicros = 0L
    while (true) {
        if (cancellation.isCancelled) return null
        val sampleTimeMicros = source.sampleTimeMicros()
        if (sampleTimeMicros < 0L) break
        val isFirstSample = frameCount == 0L
        if (isFirstSample) firstSampleTimeMicros = sampleTimeMicros
        lastSampleTimeMicros = sampleTimeMicros
        frameCount += 1L
        if (!source.advance()) break
        // 定期回读取消标记：单次 advance() 很快，所以这里的检查频率就决定了取消的响应速度。
        if (frameCount % CANCELLATION_CHECK_INTERVAL_FRAMES == 0L && cancellation.isCancelled) return null
    }
    if (frameCount <= 0L) return null
    return VideoSampleTimeline(
        frameCount = frameCount,
        firstSampleTimeMicros = firstSampleTimeMicros,
        lastSampleTimeMicros = lastSampleTimeMicros.coerceAtLeast(firstSampleTimeMicros),
        trackDurationMicros = trackDurationMicros?.takeIf { it > 0L },
    )
}

internal const val VIDEO_MIME_PREFIX = "video/"
private const val CANCELLATION_CHECK_INTERVAL_FRAMES = 240L

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
