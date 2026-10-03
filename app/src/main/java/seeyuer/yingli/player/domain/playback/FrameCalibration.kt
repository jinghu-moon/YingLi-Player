package seeyuer.yingli.player.domain.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 帧号校准结果：由样本数得出的"真实口径"。
 *
 * [measuredFrameRate] 由样本时间轴派生（见 [measuredFrameRateOrNull]），**不是**容器里那个
 * `Format.frameRate` 字段。容器字段常见缺失/为 0/四舍五入到整数，而"总帧数 ÷ 时间跨度"
 * 是这份文件真实的时间轴事实，与逐帧步进用的帧率必须同源，否则帧号与步长会互相漂移。
 */
data class FrameCalibration(
    val frameCount: Long,
    val measuredFrameRate: Float? = null,
) {
    init { require(frameCount > 0) }
}

/**
 * 只读的视频样本时间轴事实，由平台探针产出（[VideoSampleTimeline]）。
 * 纯数据，便于在没有 Android 运行时的 JVM 单测里驱动校准派生规则。
 */
data class VideoSampleTimeline(
    val frameCount: Long,
    val firstSampleTimeMicros: Long,
    val lastSampleTimeMicros: Long,
    val trackDurationMicros: Long?,
) {
    init {
        require(frameCount > 0)
        require(lastSampleTimeMicros >= firstSampleTimeMicros)
    }
}

/**
 * 由"真实帧数 + 样本时间跨度"派生实测平均帧率。
 *
 * 口径：`(帧数 - 1) × 1e6 / (末样本时间 - 首样本时间)`。减一是因为 N 个样本之间只有 N-1 个间隔，
 * 用 N 会把帧率放大一帧；时间跨度非正（单帧、时间戳全 0 的异常容器）时返回 null，
 * 调用方据此退回容器帧率，而不是拿一个除零/无穷大的结果去显示。
 */
fun measuredFrameRateOrNull(timeline: VideoSampleTimeline): Float? {
    val spanMicros = timeline.lastSampleTimeMicros - timeline.firstSampleTimeMicros
    if (spanMicros <= 0L) return null
    val intervals = timeline.frameCount - 1
    if (intervals <= 0L) return null
    return (intervals * MICROS_PER_SECOND / spanMicros.toDouble()).toFloat().takeIf { it > 0f }
}

/** 由时间轴事实组装校准结果；实测帧率不可用时留空，交给调用方退回容器帧率。 */
fun calibrationOf(timeline: VideoSampleTimeline): FrameCalibration =
    FrameCalibration(
        frameCount = timeline.frameCount,
        measuredFrameRate = measuredFrameRateOrNull(timeline),
    )

/**
 * 帧数校准结果。三种结局都要显式存在：
 * - 网络源根本不去扫（[Skipped]）——这不是失败，UI 该继续显示估算值；
 * - 探测失败/无视频轨（[Failed]）——同样是"保持估算"，但日志与诊断口径不同；
 * - 只有 [Calibrated] 才允许把界面上的帧号换成精确值。
 */
sealed interface FrameCalibrationResult {
    data object Calibrating : FrameCalibrationResult
    data class Calibrated(val calibration: FrameCalibration) : FrameCalibrationResult
    data class Skipped(val reason: String) : FrameCalibrationResult
    data class Failed(val reason: String) : FrameCalibrationResult
}

/**
 * 校准组件（可注入、可单测）。实现负责：后台线程、可取消、只读容器不解码、
 * 以及"只对本地源校准"。
 */
interface FrameCalibrationControl {
    /** 最近一次校准的结果；null 表示当前没有（也不需要）校准值。 */
    val result: StateFlow<FrameCalibrationResult?>

    /**
     * 为 [mediaId] 的后台校准。同一时刻只保留一个任务：新调用取消旧任务。
     *
     * [mediaId] 用来标识结果属于哪个媒体——媒体切换后旧结果必须被丢弃。
     */
    fun calibrate(uri: String, mediaId: String)

    /** 取消在跑的校准并清空结果（退出截图工具、媒体切换、页面销毁都走这里）。 */
    fun close()

    /**
     * 组件级终止（容器/进程退出）：取消在跑的校准、清空结果，并回收组件自身持有的资源（协程作用域）。
     *
     * 为什么必须和 [close] 分开：校准组件是**容器级单例**（跨 Activity/Service 共享），
     * 它的作用域不属于任何单个宿主页面——[close] 只表示"这次会话结束了"，组件随后还要继续服务下一次播放；
     * 只有容器退出才是真正的终态。调用点见 `MediaContainer.shutdown()`。
     */
    fun shutdown()
}

/** 无实现：未接入校准能力时（如部分单测/预览）行为与"只有估算值"完全一致。 */
object NoOpFrameCalibrationControl : FrameCalibrationControl {
    override val result: StateFlow<FrameCalibrationResult?> = MutableStateFlow(null)
    override fun calibrate(uri: String, mediaId: String) = Unit
    override fun close() = Unit
    override fun shutdown() = Unit
}

/**
 * 一次校准扫描耗时的经验模型（毫秒）。系数**全部来自真机实测**，不是猜的。
 *
 * 实测（Android 16 / MIUI 真机，日志事件 `FRAME_CALIBRATION_SCANNED`，完整表见 `docs/19`）：
 *
 * | 容器 | 样本数 | 实测耗时 |
 * | --- | --- | --- |
 * | 3.6 MiB（6s 720p HEVC） | 191 | 21 ms |
 * | 66 MiB（41s 1080p HEVC） | 1 249 | 104 / 108 ms |
 * | 1.83 GiB（20:30 1080p） | 36 917 | 2 633 ms |
 * | 2.19 GiB（19:52 1080p） | 35 794 | 2 751 ms |
 * | 2.46 GiB（22:25 1080p） | 40 370 | 3 132 ms |
 * | 自造容器 0.65 MiB（128B/样本） | 5 000 | 102 ms |
 *
 * 最后一行是关键反例：0.65 MiB 按带宽算只要 1ms，实测却 102ms —— 所以耗时**不是**只由字节数决定。
 * 拟合结果是有两项的：`耗时 ≈ 15ms（打开容器） + 样本数 × 18µs + 容器 MiB × 1.0`。
 * 六个实测点的预测误差都在 ±6% 以内（JVM 测试 `FrameCalibrationTest` 把这条拟合钉住了）。
 *
 * 为什么用它而不是"体积阈值"：字节数只是其中一个因子。极端样本数（高帧率、长时长）的容器可以
 * 又小又慢——只看体积会漏判，所以规则建立在**估算耗时**上。
 * 时长/帧率缺失时退化成只用字节数那一项；两者都拿不到就返回 null（拿不到就不打扰用户）。
 */
fun estimatedFrameCalibrationMillis(
    durationMillis: Long?,
    frameRate: Float?,
    fileSizeBytes: Long?,
): Long? {
    val sampleCount = if (durationMillis != null && durationMillis > 0L && frameRate != null && frameRate > 0f) {
        (durationMillis / MILLIS_PER_SECOND * frameRate).toLong()
    } else {
        null
    }
    if (sampleCount == null && fileSizeBytes == null) return null
    var millis = CALIBRATION_OPEN_MILLIS
    sampleCount?.let { millis += it * CALIBRATION_MILLIS_PER_SAMPLE }
    fileSizeBytes?.let { millis += it / BYTES_PER_MIB * CALIBRATION_MILLIS_PER_MIB }
    return millis.toLong()
}

/**
 * 校准提示阈值：估算耗时达到它就要在帧数胶囊上标出"这是估算值"。
 *
 * 取 600ms 的理由：
 * - 实测 66 MiB / 1 249 样本只要 103ms，而截图模式的帧数胶囊入场动画本身就有 360ms
 *   （`SCREENSHOT_CAPSULE_TRANSITION_MILLIS`）——**比动画还快的扫描根本来不及被看见**，
 *   给它加提示只会制造噪声；
 * - 反过来，1.83 GiB 以上实测 2.5–3.1s。这几秒里胶囊上的帧号与总数都来自"时长 × 容器帧率"，
 *   校准完成后会整体换成"真实样本数 + 实测帧率"：实测 2.46 GiB / 22:25 的容器是估算 40 350 帧、
 *   真实 40 370 帧（CFR 素材只差几十帧，VFR 差得更多），当前帧号也会因为换用实测帧率而移动。
 *   数字会变，就必须让用户看得出来这不是精确值。
 */
const val FRAME_CALIBRATION_NOTICE_THRESHOLD_MILLIS = 600L

/** 是否需要在校准期间标记"帧号还是估算值"（模型与阈值理由见 [estimatedFrameCalibrationMillis]）。 */
fun frameCalibrationNoticeRequired(
    durationMillis: Long?,
    frameRate: Float?,
    fileSizeBytes: Long?,
): Boolean {
    val estimated = estimatedFrameCalibrationMillis(durationMillis, frameRate, fileSizeBytes) ?: return false
    return estimated >= FRAME_CALIBRATION_NOTICE_THRESHOLD_MILLIS
}

private const val MILLIS_PER_SECOND = 1_000.0
private const val BYTES_PER_MIB = 1024.0 * 1024.0

/** 打开容器（解析 moov/轨道表）的固定开销，实测约 15ms。 */
private const val CALIBRATION_OPEN_MILLIS = 15.0

/** 每个样本的固定开销（提取器逐样本推进），实测约 18µs。 */
private const val CALIBRATION_MILLIS_PER_SAMPLE = 0.018

/** 顺序读一个 MiB 的开销，实测约 1ms（≈1 GiB/s 的顺序读）。 */
private const val CALIBRATION_MILLIS_PER_MIB = 1.0

private const val MICROS_PER_SECOND = 1_000_000.0
