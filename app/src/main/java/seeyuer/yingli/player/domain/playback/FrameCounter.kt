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
 * 帧号与总帧数。
 *
 * 口径分两级：
 * 1. **已校准**（[calibration] 非空）：总帧数是真实 sample 数，当前帧由"位置 ÷ 实测帧率"换算。
 *    这是精确口径，优先使用；
 * 2. **估算**：`帧号 ≈ 时间 × 帧率` 四舍五入。这个估算只保证量级正确、随播放单调递增、边界不越界，
 *    进入截图模式时先显示它，后台校准完成后再整体切换到精确值（口径平滑升级，不闪不跳）。
 *
 * 帧率不可用（null / 0 / 负数）时返回 `null`，调用方据此**隐藏整个帧数胶囊**：
 * 宁可不出这个胶囊，也不能显示编造出来的帧号——那会让用户以为精确值已经就绪。
 * 唯一的例外是校准值在场：此时总帧数是事实，缺容器帧率也能算出帧号。
 */
fun frameCounterStateOf(
    positionMillis: Long,
    durationMillis: Long?,
    frameRate: Float?,
    calibration: FrameCalibration? = null,
): FrameCounterState? {
    // 与逐帧步进共用同一份帧率优先级：实测 > 容器字段。两者若各取一个值，帧号与步长会互相漂移。
    val fps = if (calibration != null) {
        effectiveFrameRate(frameRate, calibration.measuredFrameRate)
    } else {
        frameRate?.takeIf { it > 0f } ?: return null
    }
    return if (calibration != null) {
        exactFrameCounterStateOf(positionMillis, durationMillis, calibration, fps)
    } else {
        // fps 在上面已经保证非空（估算分支只在容器帧率可用时才会走到这里）。
        estimatedFrameCounterStateOf(positionMillis, durationMillis, requireNotNull(fps))
    }
}

/**
 * 校准口径：总帧数直接用真实 sample 数，不再由"时长 × 帧率"反推
 * （那样会把容器时长的截断误差放大到整段视频上）。
 */
private fun exactFrameCounterStateOf(
    positionMillis: Long,
    durationMillis: Long?,
    calibration: FrameCalibration,
    frameRate: Float?,
): FrameCounterState {
    val totalFrames = calibration.frameCount
    // 校准值只给总帧数、没给帧率时（样本时间跨度不可用），用"总帧数 ÷ 时长"应变；
    // 连时长都没有时按 1 帧处理：帧号退化为 1，好过显示一个越界数字。
    val fps = frameRate ?: durationMillis?.takeIf { it > 0 }?.let { totalFrames * 1_000f / it } ?: 0f
    val safePosition = clampPosition(positionMillis, durationMillis)
    val currentFrame = (safePosition * fps / 1_000f).roundToLong() + 1L
    return FrameCounterState(
        currentFrame = currentFrame.coerceIn(1L, totalFrames),
        totalFrames = totalFrames,
    )
}

/** 估算口径：总帧数与当前帧都由位置/帧率换算，边界一律夹住。 */
private fun estimatedFrameCounterStateOf(
    positionMillis: Long,
    durationMillis: Long?,
    frameRate: Float,
): FrameCounterState? {
    // 时长缺失/为 0（直播、未知时长）时没有"总帧数"可言。
    val duration = durationMillis?.takeIf { it > 0 } ?: return null
    val totalFrames = (duration * frameRate / 1_000f).roundToLong().coerceAtLeast(1L)
    val safePosition = clampPosition(positionMillis, duration)
    // +1：时间 0 是第 1 帧；夹取兜住"末帧位置向上取整多出半帧"与前跳超过总时长两种情况。
    val currentFrame = (safePosition * frameRate / 1_000f).roundToLong() + 1L
    return FrameCounterState(
        currentFrame = currentFrame.coerceIn(1L, totalFrames),
        totalFrames = totalFrames,
    )
}

/**
 * 帧率优先级：**实测帧率 > 容器 `Format.frameRate` 字段**。
 *
 * 实测帧率由真实样本时间轴派生（[measuredFrameRateOrNull]），包含了 VFR 的平均效应与
 * 容器字段的四舍五入误差，是这份文件更可信的口径；容器字段只在算不出实测值时兜底。
 */
internal fun effectiveFrameRate(containerFrameRate: Float?, measuredFrameRate: Float?): Float? =
    measuredFrameRate?.takeIf { it > 0f } ?: containerFrameRate?.takeIf { it > 0f }

private fun clampPosition(positionMillis: Long, durationMillis: Long?): Long =
    if (durationMillis != null && durationMillis > 0) {
        positionMillis.coerceIn(0L, durationMillis)
    } else {
        positionMillis.coerceAtLeast(0L)
    }

/**
 * 单帧时长（毫秒）。帧率不可用时返回 null（调用方退回默认步长），
 * 下限 1ms：高帧率素材（如 1000fps 慢动作）四舍五入会得到 0，步进就成了原地不动。
 */
fun frameDurationMillisOf(frameRate: Float?): Long? {
    val fps = frameRate?.takeIf { it > 0f } ?: return null
    return (1_000f / fps).roundToLong().coerceAtLeast(1L)
}

/**
 * 逐帧步进的目标位置：当前帧 ± 一帧，夹在 `[0, durationMillis]`。
 *
 * 边界按需求"夹住不动"：第 1 帧再往前仍是 0，最后 1 帧再往后仍是总时长——
 * 不越界、不抛错，UI 上表现为帧号停在 1 或停在总数。
 */
fun frameStepTargetMillis(
    positionMillis: Long,
    frameRate: Float?,
    durationMillis: Long?,
    forward: Boolean,
): Long {
    val step = frameDurationMillisOf(frameRate) ?: DEFAULT_FRAME_STEP_MILLIS
    val target = if (forward) positionMillis + step else positionMillis - step
    val maximum = durationMillis?.takeIf { it >= 0 } ?: Long.MAX_VALUE
    return target.coerceIn(0L, maximum)
}

/** 帧率完全不可用时的兜底步长（30fps 一帧），与旧实现的固定步长同量级。 */
internal const val DEFAULT_FRAME_STEP_MILLIS = 34L
