package seeyuer.yingli.player.feature.player

import kotlin.math.abs

/** 手势目标：由"按下位置 + 锁定的主轴"共同决定。 */
enum class PlayerGestureTarget { NONE, VOLUME, BRIGHTNESS, SEEK, ZOOM, PAN }

/** 识别器设置快照（来自播放器偏好，纯数据便于测试）。 */
data class PlayerGestureConfig(
    val seekEnabled: Boolean = true,
    val volumeEnabled: Boolean = true,
    val brightnessEnabled: Boolean = true,
    val zoomEnabled: Boolean = true,
    /** true：左半屏音量、右半屏亮度；false：互换（规格 #101 的"后期可映射"）。 */
    val leftSideIsVolume: Boolean = true,
    /** 锁定态：所有画面手势禁用（规格 #527）。 */
    val locked: Boolean = false,
    /**
     * 显示密度（px per dp）。
     *
     * dp 阈值必须换算成 px 才能和指针坐标比较，而 density 是"当前画面"的属性而不是用户偏好，
     * 这里随设置快照一起注入（调用方在每次事件前 update），JVM 测试用默认 1f 即"1dp = 1px"。
     */
    val densityPxPerDp: Float = 1f,
)

/** 识别结果：调用方据此执行副作用（改音量/亮度/进度/缩放/播放状态）。 */
sealed interface PlayerGestureAction {
    data object None : PlayerGestureAction

    /** 刚判定出目标，调用方在此刻记录起始值。 */
    data class Begin(val target: PlayerGestureTarget) : PlayerGestureAction

    /** 拖动中；dxFraction/dyFraction 是相对手势起点、已按画面尺寸归一化的位移（上滑为正）。 */
    data class Update(
        val target: PlayerGestureTarget,
        val dxFraction: Float,
        val dyFraction: Float,
    ) : PlayerGestureAction

    /** 抬手提交。 */
    data class End(
        val target: PlayerGestureTarget,
        val dxFraction: Float,
        val dyFraction: Float,
    ) : PlayerGestureAction

    /** 单指目标被第二根手指抢占时取消，不提交最后一帧值。 */
    data class Cancel(val target: PlayerGestureTarget) : PlayerGestureAction

    /** 长按倍速开始（默认 2x）。 */
    data object LongPressBegin : PlayerGestureAction

    /** 长按倍速结束（松手/取消/开始拖动）。 */
    data object LongPressEnd : PlayerGestureAction

    /** 单击（切换控件显隐）。 */
    data object Tap : PlayerGestureAction

    /** 双击中央：播放/暂停。 */
    data object DoubleTapCenter : PlayerGestureAction

    /** 双击左侧：快退。 */
    data object DoubleTapBackward : PlayerGestureAction

    /** 双击右侧：快进。 */
    data object DoubleTapForward : PlayerGestureAction
}

/**
 * 手势识别器：把原始指针事件翻译成 [PlayerGestureAction]。
 *
 * 纯逻辑、无 Compose 依赖，坐标一律用 px 传入、阈值一律用 dp 定义，因此可以被 JVM 单测穷举，
 * 也保证不同 dpi 设备上的手感一致（阈值换算见 [PlayerGestureConfig.densityPxPerDp]）。
 *
 * 一次手势的生命周期是"按下 → 累计位移 → 锁定主轴 → 判定目标 → 拖动 → 抬手"，
 * 判定只发生一次、锁定不再翻转：中途改变方向不会把音量滑动变成进度滑动。
 * 时间只由调用方喂入（[onDown] 的 nowMillis 与 [onLongPressTimeout]），识别器自身不读时钟。
 */
class PlayerGestureRecognizer {
    private var config = PlayerGestureConfig()

    private var downX = 0f
    /** 本次按压的最大位移（px），用于长按判定：移动超过取消阈值就不再进入长按。 */
    private var maxTravel = 0f
    private var downY = 0f
    private var downMillis = 0L

    /** 只在本手势的第一个 down 记录，多指事件重复下发 down 时不会把"按下位置"挪到第二根手指。 */
    private var sessionActive = false
    private var multiPointer = false

    private var axisLocked = false
    private var horizontalAxis = false
    private var target = PlayerGestureTarget.NONE

    private var longPressing = false
    /** 长按只允许从中心手势区起手；侧边区专用于音量/亮度。 */
    private var longPressEligible = false

    /**
     * 上一次"点击候选"的时刻与所属双击分区。
     * 双击窗口由识别器自己拥有（画面手势只有它一个所有者），调用方只负责把
     * [PlayerGestureAction.Tap] 延迟到窗口之后再兑现。
     */
    private var lastTapMillis = 0L
    private var lastTapZone = 0

    /** 归一化位移只在"相对上次真的变了"时下发，避免每帧重复 Update 触发无意义副作用。 */
    private var hasEmittedUpdate = false
    private var lastDx = 0f
    private var lastDy = 0f

    fun onDown(
        x: Float,
        y: Float,
        widthPx: Float,
        heightPx: Float,
        pointerCount: Int,
        nowMillis: Long,
    ): List<PlayerGestureAction> {
        // 多指手势（缩放/平移）由 Compose 层负责，这里只保证全程不产生单指目标动作。
        if (pointerCount >= 2) {
            multiPointer = true
            longPressEligible = false
            if (!sessionActive) {
                sessionActive = true
                downX = x
        maxTravel = 0f
                downY = y
                downMillis = nowMillis
                resetDragState()
            }
            return emptyList()
        }
        if (multiPointer) return emptyList()

        sessionActive = true
        downX = x
        maxTravel = 0f
        downY = y
        downMillis = nowMillis
        resetDragState()
        // 长按倍速不限制区域（借鉴 NextPlayer）：长按不移动，与音量/亮度/进度不冲突；
        // 之前限制在中心窄带内等于几乎按不出来。
        longPressEligible = true
        return emptyList()
    }

    fun onMove(
        x: Float,
        y: Float,
        widthPx: Float,
        heightPx: Float,
        pointerCount: Int,
        nowMillis: Long,
    ): List<PlayerGestureAction> {
        // 多指期间一律不产生单指目标动作；即使中途抬起一根手指，也等下一次 onDown 重新开始。
        if (multiPointer || pointerCount >= 2) return emptyList()
        if (!sessionActive || widthPx <= 0f || heightPx <= 0f) return emptyList()

        val dx = x - downX
        val dy = y - downY
        // 记录本次按压的最大位移：长按判定要能识别"手指已经移动过"（即使还没到锁轴阈值）。
        maxTravel = maxOf(maxTravel, maxOf(abs(dx), abs(dy)))
        val startSlop = dp(START_SLOP)
        val axisLock = dp(AXIS_LOCK)
        val longPressCancel = dp(LONG_PRESS_MOVE_CANCEL)
        val actions = mutableListOf<PlayerGestureAction>()

        if (!axisLocked && target == PlayerGestureTarget.NONE) {
            val travel = maxOf(abs(dx), abs(dy))
            // 长按期间超过取消阈值：先结束倍速，再按正常拖动重新判定（长按与拖动不能同时生效）。
            if (longPressing && travel > longPressCancel) {
                longPressing = false
                actions += PlayerGestureAction.LongPressEnd
            }
            // 起手阈值内不动：既不定目标，也不锁定主轴。
            if (travel >= startSlop && travel >= axisLock && !longPressing) {
                axisLocked = true
                // |dx| == |dy| 时按垂直处理：垂直滑动是音量/亮度这类更常用的操作。
                horizontalAxis = abs(dx) > abs(dy)
                // 注意：这里刻意**不**把锚点改到锁轴那一帧。NextPlayer 用 Compose 的 touch slop
                // （几像素）所以那样做几乎无损；我们自己是 6/10dp 阈值，单次大步进会吞掉一段位移，
                // 与"起点 + 累计位移"的既有语义和测试都不一致。
                target = resolveTarget(widthPx)
                if (target != PlayerGestureTarget.NONE) {
                    // 判定帧只发 Begin：调用方在此时记录起始值（音量/亮度/进度锚点），
                    // 同一帧再发 Update 会让"锚点 + 位移"重复作用一次。
                    hasEmittedUpdate = true
                    lastDx = normalize(dx, widthPx)
                    lastDy = normalize(-dy, heightPx)
                    actions += PlayerGestureAction.Begin(target)
                    return actions
                }
            }
        }

        if (target != PlayerGestureTarget.NONE) {
            val dxFraction = normalize(dx, widthPx) + 0f
            val dyFraction = normalize(-dy, heightPx) + 0f
            if (!hasEmittedUpdate || dxFraction != lastDx || dyFraction != lastDy) {
                hasEmittedUpdate = true
                lastDx = dxFraction
                lastDy = dyFraction
                actions += PlayerGestureAction.Update(target, dxFraction, dyFraction)
            }
        }
        return actions
    }

    fun onUp(
        x: Float,
        y: Float,
        widthPx: Float,
        heightPx: Float,
        nowMillis: Long,
    ): List<PlayerGestureAction> {
        // 多指手势从按下起就没判定过目标，这里只需收尾即可（不会产生单指动作）。
        if (!sessionActive) return emptyList()

        val actions = mutableListOf<PlayerGestureAction>()
        if (longPressing) {
            longPressing = false
            actions += PlayerGestureAction.LongPressEnd
        }
        if (target != PlayerGestureTarget.NONE && widthPx > 0f && heightPx > 0f) {
            actions += PlayerGestureAction.End(
                target = target,
                dxFraction = normalize(x - downX, widthPx),
                dyFraction = normalize(downY - y, heightPx),
            )
        }
        // 没判定出目标时不返回任何动作：算不算"单击"由调用方在 [onTapReleased] 里结算。
        clearSession()
        return actions
    }

    /**
     * 结算一次"没有判定出任何手势目标"的抬手：返回 [PlayerGestureAction.Tap]（等待双击窗口）
     * 或 [PlayerGestureAction.DoubleTapBackward]/[DoubleTapForward]/[DoubleTapCenter]。
     *
     * 双击窗口由识别器自己拥有——它是画面手势的唯一所有者，能看到完整的按下序列；
     * 调用方只负责把 Tap 延迟到窗口之后再兑现，并在拿到 DoubleTap 时取消那次延迟。
     */
    fun onTapReleased(x: Float, widthPx: Float, nowMillis: Long): List<PlayerGestureAction> {
        val tapZone = doubleTapZoneOf(x, widthPx)
        val isSecondTap = lastTapMillis > 0L &&
            nowMillis - lastTapMillis <= PlayerGestureSpec.DOUBLE_TAP_WINDOW_MILLIS &&
            tapZone == lastTapZone
        lastTapMillis = 0L
        return if (isSecondTap) {
            when (tapZone) {
                0 -> listOf(PlayerGestureAction.DoubleTapBackward)
                2 -> listOf(PlayerGestureAction.DoubleTapForward)
                else -> listOf(PlayerGestureAction.DoubleTapCenter)
            }
        } else {
            lastTapMillis = nowMillis
            lastTapZone = tapZone
            listOf(PlayerGestureAction.Tap)
        }
    }

    /** 双击分区：左右各 [PlayerGestureSpec.DOUBLE_TAP_SIDE_ZONE_FRACTION]，中间为播放/暂停。 */
    private fun doubleTapZoneOf(x: Float, widthPx: Float): Int = when {
        widthPx <= 0f -> 1
        x < widthPx * PlayerGestureSpec.DOUBLE_TAP_SIDE_ZONE_FRACTION -> 0
        x >= widthPx * (1f - PlayerGestureSpec.DOUBLE_TAP_SIDE_ZONE_FRACTION) -> 2
        else -> 1
    }

    fun onCancel(): List<PlayerGestureAction> {
        val actions = if (longPressing) listOf(PlayerGestureAction.LongPressEnd) else emptyList()
        clearSession()
        return actions
    }

    /**
     * 第二根手指进入时取消单指候选，但不提交音量/亮度/进度。
     *
     * 如果第一根手指已经越过侧边竖滑阈值，直接调用 [onCancel] 会补一个 End，
     * 从而在双指缩放开始/结束时错误弹出音量条。多指转换必须丢弃单指目标，
     * 只在已经进入长按倍速时发送 LongPressEnd。
     */
    fun onMultiPointer(): List<PlayerGestureAction> {
        val actions = buildList {
            if (longPressing) add(PlayerGestureAction.LongPressEnd)
            if (target != PlayerGestureTarget.NONE) add(PlayerGestureAction.Cancel(target))
        }
        clearSession()
        return actions
    }

    /** 长按计时器到点（调用方在 360ms 后触发；识别器自己判断是否满足条件）。 */
    fun onLongPressTimeout(nowMillis: Long): List<PlayerGestureAction> {
        if (longPressing || multiPointer || !sessionActive) return emptyList()
        // 已锁定主轴、已判定目标，或手指已经移动超过长按取消阈值，就不再进入长按。
        if (axisLocked || target != PlayerGestureTarget.NONE) return emptyList()
        if (!longPressEligible) return emptyList()
        if (maxTravel > dp(PlayerGestureSpec.LONG_PRESS_MOVE_CANCEL_DP)) return emptyList()
        if (nowMillis - downMillis < PlayerGestureSpec.LONG_PRESS_MILLIS) return emptyList()
        longPressing = true
        return listOf(PlayerGestureAction.LongPressBegin)
    }

    /** 单击（调用方在双击超时后确认）。 */
    fun onTap(): List<PlayerGestureAction> {
        // 长按期间不返回 Tap；多指手势也不是单击。
        if (longPressing || multiPointer) return emptyList()
        return listOf(PlayerGestureAction.Tap)
    }

    /** 双击。 */
    fun onDoubleTap(x: Float, widthPx: Float): List<PlayerGestureAction> {
        if (widthPx <= 0f) return emptyList()
        val inset = dp(PlayerGestureSpec.SYSTEM_EDGE_INSET_DP)
        if (x < inset) return listOf(PlayerGestureAction.DoubleTapBackward)
        if (x > widthPx - inset) return listOf(PlayerGestureAction.DoubleTapForward)

        // 双击仍沿用左右各 40% 的快退/快进分区；它与竖向音量/亮度区独立。
        val leftBoundary = widthPx * PlayerGestureSpec.DOUBLE_TAP_SIDE_ZONE_FRACTION
        val rightBoundary = widthPx * (1f - PlayerGestureSpec.DOUBLE_TAP_SIDE_ZONE_FRACTION)
        return when {
            x < leftBoundary -> listOf(PlayerGestureAction.DoubleTapBackward)
            x >= rightBoundary -> listOf(PlayerGestureAction.DoubleTapForward)
            else -> listOf(PlayerGestureAction.DoubleTapCenter)
        }
    }

    /** 每次事件前更新设置快照；锁定或关闭开关打断进行中的手势时返回收尾动作。 */
    fun update(config: PlayerGestureConfig): List<PlayerGestureAction> {
        val previous = this.config
        this.config = config
        if (config == previous) return emptyList()
        // 锁定/开关变更立即生效：打断进行中的手势，已判定目标就补一个 End 让调用方提交当前值，
        // 尚未判定目标则什么都不返回（没有副作用需要收尾）。返回而不是静默丢弃，
        // 是为了让调用方不必自己处理"拖动中途被锁/被关开关"这条分支。
        if (config.locked || !isTargetEnabled(target)) return interrupt()
        return emptyList()
    }

    private fun isTargetEnabled(target: PlayerGestureTarget): Boolean = when (target) {
        PlayerGestureTarget.SEEK -> config.seekEnabled
        PlayerGestureTarget.VOLUME -> config.volumeEnabled
        PlayerGestureTarget.BRIGHTNESS -> config.brightnessEnabled
        // ZOOM/PAN 由 Compose 层负责，识别器不参与，因此这里不算"被禁用"。
        PlayerGestureTarget.ZOOM, PlayerGestureTarget.PAN, PlayerGestureTarget.NONE -> true
    }

    /** 媒体切换/页面离开时重置。 */
    fun reset() {
        config = PlayerGestureConfig()
        clearSession()
    }

    /** 手势结束后遗留的拖动状态（不清按压起点，多指下重复 down 需要靠它保持起点不变）。 */
    private fun resetDragState() {
        axisLocked = false
        horizontalAxis = false
        target = PlayerGestureTarget.NONE
        longPressing = false
        longPressEligible = false
        hasEmittedUpdate = false
        lastDx = 0f
        lastDy = 0f
    }

    private fun clearSession() {
        resetDragState()
        sessionActive = false
        multiPointer = false
    }

    /** 返回收尾动作：已判定目标补 End（调用方据此提交/回滚），否则为空。 */
    private fun interrupt(): List<PlayerGestureAction> {
        if (target == PlayerGestureTarget.NONE) {
            clearSession()
            return emptyList()
        }
        val actions = listOf(
            PlayerGestureAction.End(target, lastDx, lastDy),
        )
        clearSession()
        return actions
    }

    /**
     * 按锁定轴与按下位置判定目标；返回 [PlayerGestureTarget.NONE] 表示本次不响应。
     * 侧边区域但手势被关闭时只返回 NONE，不回退到另一半屏的另一个手势（规格 #527）。
     */
    private fun resolveTarget(widthPx: Float): PlayerGestureTarget {
        if (config.locked) return PlayerGestureTarget.NONE
        if (horizontalAxis) {
            return if (config.seekEnabled) PlayerGestureTarget.SEEK else PlayerGestureTarget.NONE
        }
        // 垂直滑动按"按下位置"分侧，而不是按当前手指位置，避免滑动过程中目标跳变。
        val inset = dp(PlayerGestureSpec.SYSTEM_EDGE_INSET_DP)
        if (downX < inset || downX > widthPx - inset) return PlayerGestureTarget.NONE
        val sideWidth = widthPx * PlayerGestureSpec.VERTICAL_GESTURE_ZONE_FRACTION
        // 用 <= / >= 让"正好落在中线"的那一像素也有归属，整屏无死区（借鉴 NextPlayer 的 50/50）。
        val leftSide = downX <= widthPx / 2f
        val insideZone = if (leftSide) downX <= sideWidth else downX >= widthPx - sideWidth
        if (!insideZone) return PlayerGestureTarget.NONE
        return if (leftSide == config.leftSideIsVolume) {
            if (config.volumeEnabled) PlayerGestureTarget.VOLUME else PlayerGestureTarget.NONE
        } else {
            if (config.brightnessEnabled) PlayerGestureTarget.BRIGHTNESS else PlayerGestureTarget.NONE
        }
    }

    private fun dp(value: Float): Float = value * densityPxPerDp

    private val densityPxPerDp: Float
        // density 不可信时退回 1，避免阈值被算成 0 而让"没动也算拖动"。
        get() = config.densityPxPerDp.takeIf { it > 0f && it.isFinite() } ?: 1f

    private fun normalize(value: Float, size: Float): Float = if (size <= 0f) 0f else value / size

    private companion object {
        const val START_SLOP = PlayerGestureSpec.START_SLOP_DP
        const val AXIS_LOCK = PlayerGestureSpec.AXIS_LOCK_DP
        const val LONG_PRESS_MOVE_CANCEL = PlayerGestureSpec.LONG_PRESS_MOVE_CANCEL_DP
    }
}

/** 常量与纯函数，便于测试与时序对齐。 */
object PlayerGestureSpec {
    const val START_SLOP_DP: Float = 6f
    const val AXIS_LOCK_DP: Float = 10f
    const val LONG_PRESS_MILLIS: Long = 360L
    const val LONG_PRESS_MOVE_CANCEL_DP: Float = 8f

    /** 双击窗口：两次"点击候选"落在这个间隔内、且同属一个分区，就算双击。 */
    const val DOUBLE_TAP_WINDOW_MILLIS: Long = 250L

    /**
     * 竖向音量/亮度区：**以画面中线严格二分、无中间死区、不做边缘避让**（借鉴 NextPlayer，
     * 用户反馈"触发舒服"主要来自这里）。原来各 48% 且左右各留 12dp，恰好挡掉了最顺手的起手位置。
     */
    const val VERTICAL_GESTURE_ZONE_FRACTION: Float = 0.5f

    /** 双击快退/快进分区独立于音量/亮度区，左右各 40%，中心 20% 播放/暂停。 */
    const val DOUBLE_TAP_SIDE_ZONE_FRACTION: Float = 0.40f

    /** 竖动手势不做边缘避让（仿 NextPlayer）：垂直滑动不会触发系统返回手势。 */
    const val SYSTEM_EDGE_INSET_DP: Float = 0f

    /** 中心区（保留给长按倍速与下滑退出）按双击分区算，不随竖向分区一起变。 */
    fun isCenterGestureZone(x: Float, widthPx: Float): Boolean =
        widthPx > 0f && x >= widthPx * DOUBLE_TAP_SIDE_ZONE_FRACTION &&
            x < widthPx * (1f - DOUBLE_TAP_SIDE_ZONE_FRACTION)
}
