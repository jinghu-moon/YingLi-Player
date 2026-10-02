package seeyuer.yingli.player.domain.playback

import kotlin.math.roundToLong

/**
 * 截图模式帧数胶囊要显示的两个数。
 *
 * 帧号统一为**从 1 开始**：位置 0 显示「1 / total」，位置等于总时长时显示「total / total」。
 * 这样「1 / 1」不会出现，也符合用户按秒表读帧号的习惯（设计稿示例 `1324 / 24531`）。
 */
data class FrameCounterState(
    val currentFrame: Long,
    val totalFrames: Long,
) {
    init {
        require(totalFrames > 0)
        require(currentFrame in 1..totalFrames)
    }
}

/**
 * 由播放位置、总时长与媒体帧率推算帧号（**本步是估算**）。
 *
 * 口径：`帧号 ≈ 时间 × 帧率` 四舍五入，而不是按解码器实际输出的帧计数——真实帧号需要
 * 后台逐帧校准（下一步），本步只要求"量级正确、随播放单调递增、边界不越界"。
 *
 * 帧率不可用（null / 0 / 负数）时返回 `null`，调用方据此**隐藏整个帧数胶囊**：
 * 宁可不出这个胶囊，也不能显示编造出来的帧号——那会让用户以为精确值已经就绪，
 * 而"上一帧/下一帧"下一步才接上真实帧步进，两者口径必须一致，不能一个真一个假。
 */
fun frameCounterStateOf(
    positionMillis: Long,
    durationMillis: Long?,
    frameRate: Float?,
): FrameCounterState? {
    val fps = frameRate?.takeIf { it > 0f } ?: return null
    // 时长缺失/为 0（直播、未知时长）时同样没有"总帧数"可言。
    val duration = durationMillis?.takeIf { it > 0 } ?: return null
    val safePosition = positionMillis.coerceIn(0, duration)
    val totalFrames = (duration * fps / 1_000f).roundToLong().coerceAtLeast(1L)
    // +1：时间 0 是第 1 帧；夹取兜住"末帧位置向上取整多出半帧"与前跳超过总时长两种情况。
    val currentFrame = (safePosition * fps / 1_000f).roundToLong() + 1L
    return FrameCounterState(
        currentFrame = currentFrame.coerceIn(1L, totalFrames),
        totalFrames = totalFrames,
    )
}
