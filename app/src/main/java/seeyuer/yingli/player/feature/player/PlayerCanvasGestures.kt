package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import android.os.SystemClock
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity

/**
 * 画面拖动/长按手势：**一个手写手势循环**把原始指针事件喂给 [PlayerGestureRecognizer]。
 *
 * 单击与双击仍由 `detectTapGestures` 负责（它自带双击窗口与长按/点击互斥），
 * 这里只接管拖动、长按倍速和双指变换，避免多个检测器争抢同一串事件（倍速胶囊轨踩过这个坑）。
 *
 * 用 `requireUnconsumed = true` 起手：按钮、进度条、倍速胶囊轨上的按住拖动不会被
 * 误判成画面手势（那些控件自己会消费 down）。
 */
@Composable
internal fun Modifier.playerCanvasDragGestures(
    config: PlayerGestureConfig,
    callbacks: PlayerGestureCallbacks,
    /** 放大状态下单指拖动改为平移画面（规格 #529），不再触发音量/亮度/进度。 */
    zoomActive: Boolean = false,
    /** 下滑退出播放页：是否启用（设置项，默认关，规格 #386 只作可选手势）。 */
    swipeDownExitEnabled: Boolean = false,
    /** 中央手势区向下长滑触发；是否真的退出由调用方决定。 */
    onSwipeDownExit: () -> Unit = {},

): Modifier {
    val latestConfig by rememberUpdatedState(config)
    val latestCallbacks by rememberUpdatedState(callbacks)
    val latestZoomActive by rememberUpdatedState(zoomActive)
    val latestSwipeDownExitEnabled by rememberUpdatedState(swipeDownExitEnabled)
    val latestOnSwipeDownExit by rememberUpdatedState(onSwipeDownExit)
    val density = LocalDensity.current.density
    // 长按计时必须用**普通协程作用域**里的 delay，而不是在 pointer input 作用域里等超时：
    // 后者的恢复由指针事件驱动，手指按住不动时永远不会到点（REX-Player 同样用
    // rememberCoroutineScope + launch { delay(...) }，见 GestureHandler.kt:433-473）。
    val gestureScope = rememberCoroutineScope()
    return composed {
        val recognizer = remember { PlayerGestureRecognizer() }
        Modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    // 与 REX-Player 一致：**不**要求 down 未被消费，否则被任何叠层控件
                    // 覆盖的区域（顶栏/底栏/中央控件/字幕/胶囊轨）整块收不到画面手势，
                    // 表现为"有些位置长按不触发、捏合时好时坏"。
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.isConsumed) {
                        // 这次按下已经被交互控件接管（按钮、进度条、倍速轨……）：
                        // 画面手势完全不介入，本次按压交给控件，等下一次按下重新开始。
                        return@awaitEachGesture
                    }
                    val width = size.width.toFloat()
                    val height = size.height.toFloat()
                    recognizer.update(latestConfig.copy(densityPxPerDp = density)).dispatch(latestCallbacks)
                    recognizer.onDown(down.position.x, down.position.y, width, height, 1, down.uptimeMillis)
                        .dispatch(latestCallbacks)
                    down.consume()
                    var swipeDownFired = false
                    var zooming = false
                    // 一旦本次按压出现过多指，就不能在第二根手指抬起后回落到单指手势。
                    // 缩放关闭时也必须消费整个多指会话，避免误调音量/亮度/进度。
                    var multiPointerSession = false
                    // 以本次双指手势的初始距离/中点为基准。ViewModel 的缩放也是
                    // “起点状态 + 相对手势增量”，不能把每帧增量再次乘在固定锚点上。
                    var initialSpread = 0f
                    var initialCentroid: Offset? = null
                    // 长按倍速的计时 Job：与识别器同源（识别器还会按位移与锁定做二次校验），
                    // 在三处取消——出现第二指、位移超阈值、手势结束。
                    var longPressJob: Job? = null
                    // 单击延迟兑现：等过双击窗口再发 Tap；期间若来了第二次点击就取消它。
                    var pendingTapJob: Job? = null
                    if (latestConfig.locked.not()) {
                        longPressJob = gestureScope.launch {
                            delay(PlayerGestureSpec.LONG_PRESS_MILLIS)
                            if (GESTURE_TRACE) android.util.Log.d("YingLiGesture", "longPressTimeout fired")
                            recognizer.onLongPressTimeout(SystemClock.uptimeMillis()).dispatch(latestCallbacks)
                        }
                    }
                    try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        // **手指数优先于按压时间**：出现第二根手指就不再是长按，而是缩放/平移。
                        // 同时取消长按计时、收掉已经生效的临时倍速，并让识别器丢弃单指目标。
                        if (pressed > 1 && !multiPointerSession) {
                            multiPointerSession = true
                            longPressJob?.cancel()
                            latestCallbacks.onLongPressSpeedEnd()
                            recognizer.onMultiPointer().dispatch(latestCallbacks)
                        }
                        // 双指：缩放与平移。**恰好两指**且没有任何 change 被消费才进入缩放
                        // （借鉴 NextPlayer 的 `pointCount == 2 && !any { isConsumed }`）。
                        // 不再要求质心落在中心区：那条例外曾把可用区域压到屏宽 4%，导致捏合几乎永不生效。
                        if (multiPointerSession) {
                            // 多指会话内始终消费事件（隔离单指识别器），但**不再因为"本帧有
                            // change 被消费"就整段放弃手势** —— 那会让捏合时好时坏（REX 的
                            // 缩放段也不做消费过滤，只有单指平移段才过滤）。
                            if (pressed >= 2) {
                                if (pressed == 2 && latestConfig.zoomEnabled) {
                                    val pointers = event.changes.filter { it.pressed }
                                    if (!zooming) {
                                        zooming = true
                                        longPressJob?.cancel()
                                        initialSpread = 0f
                                        initialCentroid = null
                                        latestCallbacks.onZoomBegin()
                                    }
                                    val first = pointers[0].position
                                    val second = pointers[1].position
                                    val spread = hypot(second.x - first.x, second.y - first.y)
                                    val centroid = Offset((first.x + second.x) / 2f, (first.y + second.y) / 2f)
                                    if (initialSpread <= 1f) {
                                        initialSpread = spread
                                        initialCentroid = centroid
                                    }
                                    val zoomChange = if (initialSpread > 1f && spread > 1f) {
                                        spread / initialSpread
                                    } else {
                                        1f
                                    }
                                    val initial = initialCentroid
                                    val pan = if (initial == null) Offset.Zero else centroid - initial
                                    if (width > 0f && height > 0f) {
                                        latestCallbacks.onZoom(zoomChange, pan.x / width, -pan.y / height)
                                    }
                                } else if (zooming) {
                                    // 三指等不支持的组合：收尾缩放，但保持多指会话隔离。
                                    latestCallbacks.onZoomEnd()
                                    zooming = false
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            // 只剩一指：本次多指手势收尾，不回落到单指手势（避免手指残留造成跳变）。
                            if (zooming) latestCallbacks.onZoomEnd()
                            event.changes.forEach { it.consume() }
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed) {
                            // 放大后单指拖动 = 平移画面：喂给识别器会产生音量/进度副作用，因此这里直接分流。
                            if (latestZoomActive) {
                                val dx = change.position.x - change.previousPosition.x
                                val dy = change.position.y - change.previousPosition.y
                                if (width > 0f && height > 0f) {
                                    latestCallbacks.onPanZoom(dx / width, -dy / height)
                                }
                                longPressJob?.cancel()
                            } else {
                                val actions = recognizer.onMove(
                                    change.position.x,
                                    change.position.y,
                                    width,
                                    height,
                                    1,
                                    change.uptimeMillis,
                                )
                                if (actions.isNotEmpty()) longPressJob?.cancel()
                                actions.dispatch(latestCallbacks)
                                // 中央手势区向下长滑 = 退出播放页；
                                // 默认关闭，开启后也只作为可选手势，不能成为主返回方式（规格 #386）。
                                if (
                                    actions.isEmpty() &&
                                    latestSwipeDownExitEnabled &&
                                    !swipeDownFired &&
                                    change.position.y - down.position.y > SWIPE_DOWN_EXIT_DP * density &&
                                    PlayerGestureSpec.isCenterGestureZone(down.position.x, width)
                                ) {
                                    swipeDownFired = true
                                    latestOnSwipeDownExit()
                                }
                            }
                            // 长按只由上面那个真 delay Job 负责（REX 同构：单一所有者）。
                            // 这里刻意不再做"事件时间戳回看"——手指按住不动时不一定有事件，
                            // 那条路径不可靠，正是它造成了长按反复失效。
                        } else {
                            val upActions = recognizer.onUp(
                                change.position.x,
                                change.position.y,
                                width,
                                height,
                                change.uptimeMillis,
                            )
                            // 这次按压没判定出任何手势目标（音量/亮度/进度/长按都没有），
                            // 才算点击候选；双击窗口由识别器结算，Tap 延迟到窗口之后再兑现。
                            if (upActions.isEmpty() && !multiPointerSession) {
                                val tapActions = recognizer.onTapReleased(
                                    change.position.x,
                                    width,
                                    change.uptimeMillis,
                                )
                                pendingTapJob?.cancel()
                                if (tapActions.any { it is PlayerGestureAction.Tap }) {
                                    pendingTapJob = gestureScope.launch {
                                        delay(PlayerGestureSpec.DOUBLE_TAP_WINDOW_MILLIS)
                                        latestCallbacks.onTap()
                                    }
                                } else {
                                    tapActions.dispatch(latestCallbacks)
                                }
                            }
                            upActions.dispatch(latestCallbacks)
                            break
                        }
                        change.consume()
                    }
                    } finally {
                        // 手势收尾（抬指、多指、被中断）都必须取消长按计时，避免延迟触发。
                        longPressJob?.cancel()
                    }
                }
            }
    }
}

/** 【临时诊断】手势事件追踪开关：定位完成后必须删除。 */
private const val GESTURE_TRACE = true

private fun List<PlayerGestureAction>.dispatch(callbacks: PlayerGestureCallbacks) {
    forEach { action ->
        when (action) {
            PlayerGestureAction.None -> Unit
            is PlayerGestureAction.Cancel -> Unit
            is PlayerGestureAction.Begin -> when (action.target) {
                PlayerGestureTarget.VOLUME -> callbacks.onVolumeBegin()
                PlayerGestureTarget.BRIGHTNESS -> callbacks.onBrightnessBegin()
                PlayerGestureTarget.SEEK -> callbacks.onSeekBegin()
                else -> Unit
            }
            is PlayerGestureAction.Update -> when (action.target) {
                PlayerGestureTarget.VOLUME -> callbacks.onVolume(action.dyFraction)
                PlayerGestureTarget.BRIGHTNESS -> callbacks.onBrightness(action.dyFraction)
                PlayerGestureTarget.SEEK -> callbacks.onSeekPreview(action.dxFraction)
                else -> Unit
            }
            is PlayerGestureAction.End -> when (action.target) {
                // 抬手事件本身可能已经落在边界位置，而上一帧 move 还没到达那里。
                // 先应用 End 的最终坐标，再结束 HUD，避免滑到屏幕顶端仍停在 90% 左右。
                PlayerGestureTarget.VOLUME -> {
                    callbacks.onVolume(action.dyFraction)
                    callbacks.onVolumeEnd()
                }
                PlayerGestureTarget.BRIGHTNESS -> {
                    callbacks.onBrightness(action.dyFraction)
                    callbacks.onBrightnessEnd()
                }
                PlayerGestureTarget.SEEK -> callbacks.onSeekEnd(action.dxFraction)
                else -> Unit
            }
            PlayerGestureAction.LongPressBegin -> callbacks.onLongPressSpeedBegin()
            PlayerGestureAction.LongPressEnd -> callbacks.onLongPressSpeedEnd()
            // Tap 由循环侧延迟兑现（双击窗口），这里不再处理；DoubleTap* 直接分发。
            PlayerGestureAction.DoubleTapBackward -> callbacks.onDoubleTapBackward()
            PlayerGestureAction.DoubleTapForward -> callbacks.onDoubleTapForward()
            PlayerGestureAction.DoubleTapCenter -> callbacks.onDoubleTapCenter()
            else -> Unit
        }
    }
}

/**
 * 下滑退出播放页的触发距离：与 Shorts 的上下切换阈值一致（约 64dp），
 * 保证"误触下滑"不会直接离开播放页。
 */
private const val SWIPE_DOWN_EXIT_DP = 64f

/**
 * 画面手势最终要做的事。音量/亮度/进度用"起点 + 位移比例"的方式计算，
 * 因此 Begin 时记锚点、Update 时按比例、End 时提交（进度只在 End 提交）。
 */
data class PlayerGestureCallbacks(
    val onVolumeBegin: () -> Unit = {},
    val onVolume: (Float) -> Unit = {},
    val onVolumeEnd: () -> Unit = {},
    val onBrightnessBegin: () -> Unit = {},
    val onBrightness: (Float) -> Unit = {},
    val onBrightnessEnd: () -> Unit = {},
    val onSeekBegin: () -> Unit = {},
    val onSeekPreview: (Float) -> Unit = {},
    val onSeekEnd: (Float) -> Unit = {},
    val onLongPressSpeedBegin: () -> Unit = {},
    val onLongPressSpeedEnd: () -> Unit = {},
    /** 双指捏合：Begin 时记缩放锚点，Update 时传相对初始双指状态的倍率与位移。 */
    val onZoomBegin: () -> Unit = {},
    val onZoom: (scaleFactor: Float, dxFraction: Float, dyFraction: Float) -> Unit = { _, _, _ -> },
    val onZoomEnd: () -> Unit = {},
    /** 放大后单指拖动 = 平移画面。 */
    val onPanZoom: (dxFraction: Float, dyFraction: Float) -> Unit = { _, _ -> },
    /** 复位缩放（双击画面或点复位胶囊时调用）。 */
    val onResetZoom: () -> Unit = {},
    /** 点亮度条顶部图标：在"自动（跟随系统亮度）/手动"之间切换。 */
    val onToggleAutoBrightness: () -> Unit = {},
    /** 单击画面（双击窗口过后确认）：切换控件显隐。 */
    val onTap: () -> Unit = {},
    /** 双击左/右/中三个分区：快退、快进、播放暂停（放大时由调用方改为复位缩放）。 */
    val onDoubleTapBackward: () -> Unit = {},
    val onDoubleTapForward: () -> Unit = {},
    val onDoubleTapCenter: () -> Unit = {},
)
