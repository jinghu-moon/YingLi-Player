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
}

/** 无实现：未接入校准能力时（如部分单测/预览）行为与"只有估算值"完全一致。 */
object NoOpFrameCalibrationControl : FrameCalibrationControl {
    override val result: StateFlow<FrameCalibrationResult?> = MutableStateFlow(null)
    override fun calibrate(uri: String, mediaId: String) = Unit
    override fun close() = Unit
}

private const val MICROS_PER_SECOND = 1_000_000.0
