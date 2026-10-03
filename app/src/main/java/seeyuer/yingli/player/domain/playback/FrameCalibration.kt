package seeyuer.yingli.player.domain.playback

import kotlin.math.pow
import kotlin.math.roundToLong
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
 * 一次校准扫描耗时的经验模型（毫秒）：**保守上界**，系数全部来自真机实测，不是猜的。
 *
 * 实测（Android 16 / MIUI 真机，Xiaomi 25102RKBEC；完整表见 `docs/19`）：
 *
 * | 容器 | 样本数 | 实测耗时 | 每样本成本 |
 * | --- | --- | --- | --- |
 * | 3.6 MiB（6s 720p HEVC） | 191 | 21 ms | 110 µs |
 * | 66 MiB（41s 1080p HEVC） | 1 249 | 104 ms | 83 µs |
 * | 1.83 GiB（20:30 1080p） | 36 917 | 2 633 ms | 71 µs |
 * | 2.19 GiB（19:52 1080p） | 35 794 | 2 751 ms | 77 µs |
 * | 2.46 GiB（22:25 1080p） | 40 370 | 3 132 ms | 78 µs |
 * | 高帧率自造容器 16 MiB | 120 000 | 19 748 ms | 164 µs |
 * | 高帧率自造容器 27 MiB | 200 000 | 54 196 ms | 271 µs |
 * | 高帧率自造容器 2.7 MiB | 20 000 | 756 ms | 38 µs |
 * | 高帧率自造容器 6.8 MiB | 50 000 | 3 855 ms | 77 µs |
 * | 自造容器 0.65 MiB（128B/样本） | 5 000 | 96 ms | 19 µs |
 * | 真机素材 29.97fps / 107 893 样本 | 107 893 | ~12 000 ms | 111 µs |
 *
 * ## 为什么旧模型（`15 + 样本数×18µs + MiB×1.0`）必须改
 *
 * 旧模型把"每样本成本"当成常数 18µs。上表后五行证明它不是常数：**18µs 只在 4 万样本以下是
 * 好近似**（真机五个点误差 ≤6%），而样本数上去以后每样本成本单调上升——5 千样本约 19µs、
 * 2 万样本约 38µs、5 万样本约 77µs、12 万样本约 164µs、20 万样本约 271µs，是约 `样本数^0.75`
 * 的幂律（以 5000 样本为原点，见 [CALIBRATION_SAMPLE_GROWTH_EXPONENT] 的标定过程；真实文件里
 * 独立测得的那份 107893 样本容器是 111µs/样本，与同档位的合成容器同量级，说明这不是合成容器独有）。
 * 旧模型因此在 12 万样本的容器上低估 9 倍（预测 2.2s、实测 19.7s），在 20 万样本上低估 15 倍
 * （预测 3.6s、实测 54.2s）。
 *
 * ## 现在的模型
 *
 * `耗时 ≈ max(15ms + 样本数 × 18µs × (样本数/5000)^0.75 + 容器 MiB × 1.0, 15ms + 样本数 × 18µs + 容器 MiB × 1.0)`
 *
 * 两项（幂律项与旧线性项）各自的物理含义：
 * - `15ms`：打开容器（解析 moov/轨道表）的固定开销；
 * - `样本数 × 18µs × (样本数/5000)^0.75`：逐样本推进提取器。18µs 是**小容器上实测的每样本成本**，
 *   幂律因子描述"样本越多、每次 `advance()` 越贵"（样本表/分块索引被反复走过的代价），
 *   5000 是实测里"18µs 成立"的参考样本数；
 * - `容器 MiB × 1.0`：容器字节被读过的代价（≈1 GiB/s 顺序读）；
 * - 取 max 的第二项就是旧模型：它在**1 秒以内的短扫描**上是实测校准得最准的一条
 *   （真实素材五个点误差 ≤5%），而幂律项在样本少时略低于实测（3.6 MiB 那个点预测 19ms / 实测 21ms）。
 *   短扫描本来就不会触发提示（阈值 600ms），所以这里优先保住"真实文件误差不变大"，
 *   只在估计值超过 1 秒之后才让幂律项单独说话。
 *
 * 这是**上界而非点估计**：对 3 秒以上的每一个实测点，模型都给出不小于实测值的耗时
 * （真实素材 1.06–1.92 倍、高帧率容器 1.06–1.37 倍），因为提示的代价是"多标一个 ≈"，
 * 而漏标的代价是"让用户把估算值当精确值看几十秒"——两者不对称。
 *
 * 已知不覆盖的形态：把数据本身撑大的容器（8 KiB/样本 → 40 MiB / 5000 样本，实测 285ms，
 * 模型给 144ms）。那需要单独一项"样本载荷带宽"，而真机实测里没有这种素材（真实 1080p 是
 * 数十 KiB/样本量级，走的是 `MiB × 1.0` 那一项），因此不为它加系数——记在 `docs/19` 的边界里。
 *
 * 为什么用它而不是"体积阈值"：字节数只是其中一个因子。高帧率容器的字节数可以很小却极慢
 * （16 MiB / 12 万样本要 ~20s），只看体积会漏判，所以规则建立在**估算耗时**上。
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
    val bytesMillis = fileSizeBytes?.let { it / BYTES_PER_MIB * CALIBRATION_MILLIS_PER_MIB } ?: 0.0
    val linearMillis = CALIBRATION_OPEN_MILLIS +
        (sampleCount?.let { it * CALIBRATION_MILLIS_PER_SAMPLE } ?: 0.0) +
        bytesMillis
    if (sampleCount == null) return linearMillis.roundToLong()
    val growingMillis = CALIBRATION_OPEN_MILLIS + sampleCostMillis(sampleCount) + bytesMillis
    // 只有"慢到会被用户看见"的那一侧才交给幂律项：短扫描沿用实测最准的旧模型（见上方 KDoc）。
    val millis = if (growingMillis < CALIBRATION_GROWTH_TRUSTED_MILLIS) {
        maxOf(linearMillis, growingMillis)
    } else {
        growingMillis
    }
    return millis.roundToLong()
}

/**
 * 校准提示阈值：估算耗时达到它就要在帧数胶囊上标出"这是估算值"。
 *
 * 取 600ms 的理由（**本轮实测复核后维持不变**）：
 * - 实测 66 MiB / 1 249 样本只要 104ms，而截图模式的帧数胶囊入场动画本身就有 360ms
 *   （`SCREENSHOT_CAPSULE_TRANSITION_MILLIS`）——**比动画还快的扫描根本来不及被看见**，
 *   给它加提示只会制造噪声；3.6 MiB / 191 样本实测 21ms，更不需要提示；
 * - 反过来，1.83 GiB 以上实测 2.5–3.1s。这几秒里胶囊上的帧号与总数都来自"时长 × 容器帧率"，
 *   校准完成后会整体换成"真实样本数 + 实测帧率"：实测 2.46 GiB / 22:25 的容器是估算 40 350 帧、
 *   真实 40 370 帧（CFR 素材只差几十帧，VFR 差得更多），当前帧号也会因为换用实测帧率而移动。
 *   数字会变，就必须让用户看得出来这不是精确值；
 * - 阈值本身不需要跟着新模型改：新模型把边界推到了"约 1.4 万样本 / 约 600 MiB"，
 *   恰好把实测 756ms（2 万样本）以上的容器全部纳入提示，而实测 104ms 的容器仍在阈值以下——
 *   它在 1 秒以内走的是旧线性项（估计 102.9ms，实测 104ms）。**没有实测依据支持改这个数字，
 *   就不改**；本轮补的实测点只用来修"样本数"那一项严重低估的问题。
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

/** 小容器上实测的每样本成本（18µs）。样本数超过 [CALIBRATION_SAMPLE_RATE_REFERENCE] 后按幂律放大。 */
private const val CALIBRATION_MILLIS_PER_SAMPLE = 0.018

/** 顺序读一个 MiB 的开销，实测约 1ms（≈1 GiB/s 的顺序读）。 */
private const val CALIBRATION_MILLIS_PER_MIB = 1.0

/**
 * 每样本成本随样本数放大的幂指数。
 *
 * 标定方式：5000 样本实测 19µs/样本、120000 样本实测 164µs/样本，比值 8.6 给出约 0.72；
 * 再用独立测得的 200000 样本点（271µs/样本，相对 12 万样本再涨 1.65 倍）复核，
 * 0.72 外推只有 1.48 倍、会低估 5%（模型 51.4s / 实测 54.2s），取 0.75 后两个点同时被覆盖。
 * 为什么宁可取偏大的一侧：漏标 ≈ 的代价（用户把估算值当精确值看一分钟）远大于多标一个 ≈。
 *
 * 注意幂律的原点是 [CALIBRATION_SAMPLE_RATE_REFERENCE] 而不是"0 样本"：
 * 用 4 万样本点当原点会得到 `(120000/40370)^0.75 ≈ 2.2`，解释不了 78µs → 164µs 的实测上升。
 */
private const val CALIBRATION_SAMPLE_GROWTH_EXPONENT = 0.75

/** 上一条幂律的参考样本数：实测 18µs/样本在这附近成立（5000 样本的自造容器实测 19µs）。 */
private const val CALIBRATION_SAMPLE_RATE_REFERENCE = 5_000.0

/**
 * 从这一刻起，估计耗时完全交给幂律项（不再与旧线性项取 max）。
 *
 * 取 1000ms 的理由：实测里旧模型在 1 秒以内的短扫描上比幂律项更接近实测（3.6 MiB 点：旧 22ms、
 * 幂律 19ms、实测 21ms），而在 1 秒以上的点上幂律项必须独自承担"不许低估"的责任。
 * 600ms 的提示阈值落在这个分界以下，所以这个分界不影响"要不要标 ≈"，只影响毫秒估计值本身。
 */
private const val CALIBRATION_GROWTH_TRUSTED_MILLIS = 1_000.0

/**
 * 逐样本推进的耗时（毫秒）：`样本数 × CALIBRATION_MILLIS_PER_SAMPLE × (样本数/参考样本数)^幂指数`。
 *
 * 指数与参考样本数都不在这里写死，而是引用 [CALIBRATION_SAMPLE_GROWTH_EXPONENT] /
 * [CALIBRATION_SAMPLE_RATE_REFERENCE]：这两个数字是实测拟合出来的，注释里再抄一份
 * 迟早会和常量漂移（本行此前就抄着旧指数 `0.72`，而常量已经调到 `0.75`）。
 *
 * 抽成独立函数是为了让"样本数这一项怎么长"有一个可单测的点位：幂律是实测拟合，
 * 任何人改指数都必须先解释 `docs/19` 里那张表。
 */
private fun sampleCostMillis(sampleCount: Long): Double {
    val growth = (sampleCount / CALIBRATION_SAMPLE_RATE_REFERENCE).pow(CALIBRATION_SAMPLE_GROWTH_EXPONENT)
    return sampleCount * CALIBRATION_MILLIS_PER_SAMPLE * growth
}

private const val MICROS_PER_SECOND = 1_000_000.0
