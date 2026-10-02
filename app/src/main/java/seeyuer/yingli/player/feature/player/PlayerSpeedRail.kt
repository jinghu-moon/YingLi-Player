package seeyuer.yingli.player.feature.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import seeyuer.yingli.player.core.designsystem.component.sliderFractionFromTouch
import seeyuer.yingli.player.core.designsystem.component.sliderTrackSpanPx
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import kotlin.math.roundToInt

/** 胶囊轨高度＝底栏按钮直径。 */
internal val SpeedRailHeight: Dp = 48.dp

/** 旋钮直径：36dp，保证在 48dp 胶囊内的任意位置都不会越出圆角。 */
private val SpeedKnobSize: Dp = 36.dp

/** 刻度：直径 5dp 的小圆点，居中排在胶囊中线上。 */
private val SpeedTickDotSize: Dp = 5.dp

/** 填充条右端比旋钮右缘再多留出的间距，让旋钮明显落在填充条里面。 */
private val SpeedFillTrailingGap: Dp = 10.dp

/** 展开动画时长与滑入距离。 */
private const val SPEED_RAIL_ANIMATION_MILLIS = 200
private val SpeedRailSlideIn: Dp = 24.dp

/** 支持的倍速档位，顺序即刻度顺序。 */
internal fun supportedSpeeds(): List<PlaybackSpeed> =
    PlaybackSpeed.supportedValues.map { value -> PlaybackSpeed.of(value) }

/** 当前倍速在档位序列中的下标；找不到时回到 1x 的位置。 */
internal fun speedIndex(speed: PlaybackSpeed): Int {
    val speeds = supportedSpeeds()
    return speeds.indexOf(speed).takeIf { it >= 0 }
        ?: speeds.indexOf(PlaybackSpeed.Normal).coerceAtLeast(0)
}

/** 轨道位置（可以是小数下标）对应的档位。 */
internal fun speedAt(fractionIndex: Float): PlaybackSpeed {
    val speeds = supportedSpeeds()
    return speeds[fractionIndex.roundToInt().coerceIn(0, speeds.lastIndex)]
}

/**
 * 倍速胶囊轨：手写的 48dp 高胶囊滑轨（与底栏按钮同高），带刻度、进度填充和旋钮。
 *
 * 手势是单个 [awaitEachGesture] 循环：按下即把旋钮移到触点（绝对跟手，不会与手指错位），
 * 移动持续跟手，抬起才吸附到最近刻度并提交；因此点一下和拖一段是同一套逻辑。
 * 拖动期间只通过 [onPreview] 让旁边的倍速按钮实时显示数值，避免每帧下发命令。
 */
@Composable
internal fun PlayerSpeedRail(
    current: PlaybackSpeed,
    onPreview: (PlaybackSpeed) -> Unit,
    onCommit: (PlaybackSpeed) -> Unit,
    modifier: Modifier = Modifier,
) {
    val speeds = supportedSpeeds()
    val lastIndex = speeds.lastIndex.toFloat()
    val committedIndex = speedIndex(current).toFloat()
    // 拖动期间用本地位置；松手后一律回到档位下标，保证旋钮中心与刻度中心重合。
    var pendingPosition by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(current) { pendingPosition = null }
    val position = pendingPosition ?: committedIndex

    // 手势协程生命周期比单次重组长：回调与已提交档位必须取最新值，
    // 否则父级重建预览状态后，手势仍写向旧状态，按钮数值就不再实时更新。
    val latestOnPreview by rememberUpdatedState(onPreview)
    val latestOnCommit by rememberUpdatedState(onCommit)
    val latestCommittedIndex by rememberUpdatedState(committedIndex)

    val trackColor = YingLiTheme.player.track
    val fillColor = YingLiTheme.player.controlPrimary.copy(alpha = 0.22f)
    // 刻度点比旋钮暗一档，两者一眼可分。
    val tickColor = YingLiTheme.player.controlPrimary.copy(alpha = 0.40f)
    val knobColor = YingLiTheme.player.controlPrimary
    // 当前档位的刻度点压在旋钮之上，用旋钮的"内容色"（画布色）画，形成明显的凹点。
    val currentTickColor = YingLiTheme.player.canvas
    val density = LocalDensity.current
    val railRadiusPx = with(density) { (SpeedRailHeight / 2).toPx() }

    // 点击倍速按钮后：胶囊轨淡入并从按钮一侧滑出。
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    val reveal by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = SPEED_RAIL_ANIMATION_MILLIS),
        label = "speed-rail-reveal",
    )

    fun positionFromTouch(touchX: Float, widthPx: Float): Float =
        sliderFractionFromTouch(touchX, widthPx, railRadiusPx) * lastIndex

    BoxWithConstraints(
        modifier = modifier
            .height(SpeedRailHeight)
            .graphicsLayer {
                alpha = reveal
                translationX = (1f - reveal) * -SpeedRailSlideIn.toPx()
            }
            .clip(CircleShape)
            .background(trackColor)
            .pointerInput(speeds.size) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    fun follow(touchX: Float) {
                        val widthPx = size.width.toFloat()
                        if (widthPx <= 0f) return
                        val next = positionFromTouch(touchX, widthPx)
                        pendingPosition = next
                        latestOnPreview(speedAt(next))
                    }

                    // 按下就贴上手指，避免"拖了但没跟手"。
                    follow(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed) {
                            follow(change.position.x)
                            change.consume()
                        } else {
                            val speed = speedAt(pendingPosition ?: latestCommittedIndex)
                            // 松手吸附到档位下标：旋钮中心与刻度中心重合。
                            pendingPosition = speedIndex(speed).toFloat()
                            latestOnCommit(speed)
                            change.consume()
                            break
                        }
                    }
                }
            },
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val spanPx = sliderTrackSpanPx(widthPx, railRadiusPx)
        val fraction = if (lastIndex == 0f) 0f else position / lastIndex
        val knobCenterPx = railRadiusPx + spanPx * fraction
        val knobRadiusPx = with(density) { (SpeedKnobSize / 2).toPx() }
        val fillTrailingGapPx = with(density) { SpeedFillTrailingGap.toPx() }

        val dotRadius = with(density) { SpeedTickDotSize.toPx() / 2f }
        // 当前（拖动中则为最近）档位：指示点画在旋钮之上，颜色与旋钮相反，一眼看出落在哪个刻度点。
        val currentIndex = position.roundToInt().coerceIn(0, speeds.lastIndex)
        val currentTickX = railRadiusPx + spanPx * (currentIndex / lastIndex.coerceAtLeast(1f))

        Canvas(Modifier.fillMaxSize()) {
            // 已选进度：从胶囊左端填充到旋钮右缘再留出一段间距，旋钮整个嵌在填充条里。
            drawRoundRect(
                color = fillColor,
                size = Size(
                    (knobCenterPx + knobRadiusPx + fillTrailingGapPx).coerceIn(size.height / 2f, size.width),
                    size.height,
                ),
                cornerRadius = CornerRadius(size.height / 2f),
            )
            // 8 个档位刻度：胶囊中线上的小圆点（比旋钮暗，形成"刻度 + 把手"的层次）。
            repeat(speeds.size) { index ->
                val x = railRadiusPx + spanPx * (index / lastIndex.coerceAtLeast(1f))
                drawCircle(
                    color = tickColor,
                    radius = dotRadius,
                    center = Offset(x, size.height / 2f),
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset((knobCenterPx - knobRadiusPx).roundToInt(), 0) }
                .size(SpeedKnobSize)
                .clip(CircleShape)
                .background(knobColor),
        )
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = currentTickColor,
                radius = dotRadius,
                center = Offset(currentTickX, size.height / 2f),
            )
        }
    }
}
