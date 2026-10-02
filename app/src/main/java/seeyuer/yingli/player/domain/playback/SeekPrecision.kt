package seeyuer.yingli.player.domain.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 跳转精度策略（域层口径，不依赖任何播放引擎类型）。
 *
 * 为什么要有这个枚举而不是直接把引擎的 `SeekParameters` 传到调用方：
 * 兼容层在 **Activity 进程**，真正执行 seek 的播放在 **service 进程的引擎**里，
 * 两边只能靠业务口径通信。引擎自己把口径翻译成 `SeekParameters`，
 * 纯规则（见 [seekPrecisionFor]）留在域层就能被 JVM 单测覆盖。
 */
enum class SeekPrecision {
    /** 落在请求位置：从目标前的关键帧解码到目标点。逐帧步进必须用它，代价是可能等待解码。 */
    FRAME_ACCURATE,

    /** 落在最近的关键帧：跟手，但不保证落点。 */
    CLOSEST_SYNC,
}

/** 时长不超过该值时用精确跳转：短视频解码代价小，落点准确更划算。 */
const val FRAME_ACCURATE_SEEK_MAX_DURATION_MILLIS = 120_000L

/**
 * 由媒体时长决定"默认精度"：这是拖动进度条那一档策略（REX 同构：默认关键帧跳转，短视频才精确）。
 *
 * 时长未知（null / 0 / 负数）时按"长视频"处理，落在 [SeekPrecision.CLOSEST_SYNC]：
 * 未知时长多半是网络流或探测失败，拿不到长度的源做精确跳转只会卡住画面。
 */
fun defaultSeekPrecision(durationMillis: Long?): SeekPrecision =
    if (durationMillis != null && durationMillis in 1..FRAME_ACCURATE_SEEK_MAX_DURATION_MILLIS) {
        SeekPrecision.FRAME_ACCURATE
    } else {
        SeekPrecision.CLOSEST_SYNC
    }

/**
 * 有效跳转精度的唯一判定点（纯函数）：
 * - 截图工具激活（[frameAccurate] = true）→ 一律精确：逐帧检查时落点错一帧就白点了；
 * - 否则退回按 [durationMillis] 选择。
 */
fun seekPrecisionFor(durationMillis: Long?, frameAccurate: Boolean): SeekPrecision =
    if (frameAccurate) SeekPrecision.FRAME_ACCURATE else defaultSeekPrecision(durationMillis)

/** 只读侧：播放引擎据此设置实际跳转精度。 */
interface SeekPrecisionState {
    val precision: StateFlow<SeekPrecision>
}

/** 读写侧：UI 层据此在进入/退出截图工具时切换精度。 */
interface SeekPrecisionControl : SeekPrecisionState {
    fun setPrecision(value: SeekPrecision)
}

/**
 * 默认实现：一个进程内的可变状态。
 *
 * 为什么可以直接共享：`YingLiPlaybackService` 与 `MainActivity` 跑在同一个进程
 * （Manifest 没有 `android:process`），所以"只改策略、不打断播放"可以靠一个共享状态完成——
 * 引擎自己订阅它并调用 `setSeekParameters`，既不需要重新 prepare/重设媒体，
 * 也不需要为了一条控制指令去接管 MediaSession 的 `onConnect`（那会连带影响所有播放命令）。
 */
class MutableSeekPrecisionControl(initial: SeekPrecision = SeekPrecision.CLOSEST_SYNC) : SeekPrecisionControl {
    private val mutablePrecision = MutableStateFlow(initial)
    override val precision: StateFlow<SeekPrecision> = mutablePrecision.asStateFlow()

    override fun setPrecision(value: SeekPrecision) {
        mutablePrecision.value = value
    }
}
