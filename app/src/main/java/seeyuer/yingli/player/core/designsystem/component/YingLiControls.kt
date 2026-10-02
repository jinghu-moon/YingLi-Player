package seeyuer.yingli.player.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.icon.imageVector
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme

@Composable
fun YingLiButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: YingLiIcon? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(YingLiTheme.components.minimumTouchTarget),
        enabled = enabled,
        shape = YingLiTheme.components.componentCorner,
    ) {
        leadingIcon?.let { semanticIcon ->
            Icon(
                imageVector = semanticIcon.imageVector,
                contentDescription = null,
                modifier = Modifier.size(YingLiTheme.components.iconSize),
            )
            Spacer(Modifier.width(YingLiTheme.components.itemSpacing))
        }
        Text(text = text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun YingLiIconButton(
    icon: YingLiIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(YingLiTheme.components.minimumTouchTarget),
        enabled = enabled,
    ) {
        Icon(
            imageVector = icon.imageVector,
            contentDescription = contentDescription,
            modifier = Modifier.size(YingLiTheme.components.iconSize),
            tint = tint,
        )
    }
}

@Composable
fun YingLiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        placeholder = placeholder?.let { text -> ({ Text(text) }) },
        shape = YingLiTheme.components.componentCorner,
        singleLine = false,
    )
}

@Composable
fun YingLiChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, maxLines = 2) },
        modifier = modifier.height(YingLiTheme.components.minimumTouchTarget),
        enabled = enabled,
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = YingLiIcon.SUCCESS.imageVector,
                    contentDescription = null,
                    modifier = Modifier.size(YingLiTheme.components.iconSize),
                )
            }
        } else {
            null
        },
        shape = YingLiTheme.components.compactCorner,
    )
}

@Composable
fun YingLiCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    LabeledSelectionControl(
        label = label,
        modifier = modifier,
        enabled = enabled,
        role = Role.Checkbox,
        onClick = { onCheckedChange(!checked) },
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun YingLiRadio(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    LabeledSelectionControl(label, modifier, enabled, Role.RadioButton, onClick) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
    }
}

@Composable
fun YingLiSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    LabeledSelectionControl(label, modifier, enabled, Role.Switch, { onCheckedChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun LabeledSelectionControl(
    label: String,
    modifier: Modifier,
    enabled: Boolean,
    role: Role,
    onClick: () -> Unit,
    control: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(YingLiTheme.components.minimumTouchTarget)
            .semantics {
                this.role = role
                if (!enabled) disabled()
            },
        enabled = enabled,
        color = YingLiTheme.colors.surface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = YingLiTheme.components.itemSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { Text(label, maxLines = 2) }
            control()
        }
    }
}

enum class YingLiSegmentedControlSize {
    Default,
    Small,
}

private data class SegmentedControlMetrics(
    val height: Dp,
    val inset: Dp,
    val corner: Dp,
    val pillCorner: Dp,
    val textStyle: TextStyle,
) {
    val pillHeight: Dp get() = height - inset * 2
}

@Composable
private fun segmentedControlMetrics(size: YingLiSegmentedControlSize): SegmentedControlMetrics =
    when (size) {
        YingLiSegmentedControlSize.Default -> SegmentedControlMetrics(
            height = 48.dp,
            inset = 6.dp,
            corner = 8.dp,
            pillCorner = 6.dp,
            textStyle = MaterialTheme.typography.labelLarge,
        )
        YingLiSegmentedControlSize.Small -> SegmentedControlMetrics(
            height = 32.dp,
            inset = 4.dp,
            corner = 6.dp,
            pillCorner = 4.dp,
            textStyle = MaterialTheme.typography.labelMedium,
        )
    }

@Composable
fun YingLiSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillMaxWidth: Boolean = true,
    size: YingLiSegmentedControlSize = YingLiSegmentedControlSize.Default,
) {
    val metrics = segmentedControlMetrics(size)
    Surface(
        modifier = (if (fillMaxWidth) modifier.fillMaxWidth() else modifier).height(metrics.height),
        shape = RoundedCornerShape(metrics.corner),
        color = YingLiTheme.colors.surfaceComponent,
    ) {
        if (options.isEmpty()) return@Surface
        val safeIndex = selectedIndex.coerceIn(options.indices)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val inset = metrics.inset
            // Uniform inset on all sides: P | opt | P | opt | P
            // optionWidth = (W - 2P - P*(n-1)) / n = (W - P*(n+1)) / n
            val optionWidth = (maxWidth - inset * (options.size + 1)) / options.size
            val targetPillX = inset + (optionWidth + inset) * safeIndex
            val animatedPillX by animateDpAsState(
                targetValue = targetPillX,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                label = "segmentPillX",
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = animatedPillX, y = inset)
                    .width(optionWidth)
                    .height(metrics.pillHeight),
                shape = RoundedCornerShape(metrics.pillCorner),
                color = YingLiTheme.colors.selectionStructural,
            ) {}
            Row(Modifier.fillMaxSize()) {
                options.forEachIndexed { index, option ->
                    val selected = index == safeIndex
                    val textColor by animateColorAsState(
                        targetValue = if (selected) {
                            YingLiTheme.colors.selectionOnStructural
                        } else {
                            YingLiTheme.colors.textPrimary
                        },
                        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                        label = "segmentText",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                enabled = enabled,
                                role = Role.Tab,
                                onClick = { onSelected(index) },
                            )
                            .alpha(if (enabled) 1f else 0.38f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            option,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = metrics.textStyle,
                            color = textColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun YingLiSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    trackHeight: Dp = 4.dp,
    thumbRadius: Dp = 7.dp,
    colors: YingLiSliderColors = YingLiSliderDefaults.colors(),
) {
    var draggingValue by remember { mutableStateOf<Float?>(null) }
    val coerced = (draggingValue ?: value).coerceIn(valueRange.start, valueRange.endInclusive)
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val progress = ((coerced - valueRange.start) / span).coerceIn(0f, 1f)

    fun valueFromFraction(fraction: Float): Float =
        (valueRange.start + span * fraction.coerceIn(0f, 1f)).coerceIn(valueRange.start, valueRange.endInclusive)

    // 滑块中心只能落在 [radius, width - radius]，触摸映射必须用同一段区间，
    // 否则大滑块会与手指错位、点击刻度落不到对应档位。
    val thumbRadiusPx = with(LocalDensity.current) { thumbRadius.toPx() }

    Column(modifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f)) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(10.dp))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 视觉轨道保持细，但整个控件提供标准 48dp 触摸目标。
                .height(48.dp)
                .then(
                    if (!enabled) {
                        Modifier
                    } else {
                        Modifier
                            // 点击与拖动必须由同一个手势循环处理。两个 detector 会互相消费
                            // down，导致旧实现只能点击、无法进入拖动。
                            .pointerInput(valueRange) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    fun updateFromTouch(x: Float) {
                                        val next = valueFromFraction(
                                            sliderFractionFromTouch(x, size.width.toFloat(), thumbRadiusPx),
                                        )
                                        draggingValue = next
                                        onValueChange(next)
                                    }
                                    updateFromTouch(down.position.x)
                                    down.consume()
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (change.pressed) {
                                            updateFromTouch(change.position.x)
                                            change.consume()
                                        } else {
                                            updateFromTouch(change.position.x)
                                            onValueChangeFinished?.invoke()
                                            draggingValue = null
                                            change.consume()
                                            break
                                        }
                                    }
                                }
                            }
                    },
                ),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val centerY = size.height / 2f
                val stroke = trackHeight.toPx()
                val radius = thumbRadius.toPx()
                val startX = radius
                val endX = (size.width - radius).coerceAtLeast(startX)
                val thumbX = startX + (endX - startX) * progress
                drawLine(
                    color = colors.inactiveTrack,
                    start = Offset(startX, centerY),
                    end = Offset(endX, centerY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = colors.activeTrack,
                    start = Offset(startX, centerY),
                    end = Offset(thumbX, centerY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawCircle(color = colors.thumb, radius = radius, center = Offset(thumbX, centerY))
            }
        }
    }
}

@Immutable
data class YingLiSliderColors(
    val activeTrack: Color,
    val inactiveTrack: Color,
    val thumb: Color,
)

/** 滑块中心可达的轨道长度：两端各让出滑块半径，保证滑块不越界。 */
internal fun sliderTrackSpanPx(widthPx: Float, thumbRadiusPx: Float): Float =
    (widthPx - 2f * thumbRadiusPx).takeIf { it > 0f } ?: 1f

/** 触摸位置映射到轨道比例：与滑块中心可达区间一致，避免大滑块与手指错位。 */
internal fun sliderFractionFromTouch(touchX: Float, widthPx: Float, thumbRadiusPx: Float): Float =
    ((touchX - thumbRadiusPx) / sliderTrackSpanPx(widthPx, thumbRadiusPx)).coerceIn(0f, 1f)

object YingLiSliderDefaults {
    @Composable
    fun colors(
        activeTrack: Color = YingLiTheme.colors.textPrimary,
        inactiveTrack: Color = YingLiTheme.colors.borderDivider,
        thumb: Color = YingLiTheme.colors.textPrimary,
    ): YingLiSliderColors = YingLiSliderColors(
        activeTrack = activeTrack,
        inactiveTrack = inactiveTrack,
        thumb = thumb,
    )
}

@Composable
fun YingLiStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: IntRange,
    modifier: Modifier = Modifier,
    step: Int = 1,
    enabled: Boolean = true,
    decreaseContentDescription: String = "减少",
    increaseContentDescription: String = "增加",
    formatValue: (Int) -> String = { it.toString() },
) {
    require(step > 0) { "step must be positive" }
    val canDecrease = enabled && value - step >= valueRange.first
    val canIncrease = enabled && value + step <= valueRange.last
    Surface(
        modifier = modifier.height(40.dp),
        shape = YingLiTheme.components.componentCorner,
        color = YingLiTheme.colors.surfaceComponent,
    ) {
        Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            StepperButton(
                icon = YingLiIcon.MINUS,
                contentDescription = decreaseContentDescription,
                enabled = canDecrease,
                onClick = { onValueChange((value - step).coerceIn(valueRange)) },
            )
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = formatValue(value),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            StepperButton(
                icon = YingLiIcon.PLUS,
                contentDescription = increaseContentDescription,
                enabled = canIncrease,
                onClick = { onValueChange((value + step).coerceIn(valueRange)) },
            )
        }
    }
}

@Composable
private fun StepperButton(
    icon: YingLiIcon,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon.imageVector,
            contentDescription = contentDescription,
            modifier = Modifier.size(YingLiTheme.components.iconSize),
        )
    }
}

@Composable
fun YingLiDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scale = remember { Animatable(if (expanded) 1f else 0.86f) }
    val alpha = remember { Animatable(if (expanded) 1f else 0f) }
    var attached by remember { mutableStateOf(expanded) }
    val density = LocalDensity.current
    val positionProvider = remember(density) {
        YingLiDropdownPositionProvider(
            gapPx = with(density) { 4.dp.roundToPx() },
            edgeMarginPx = with(density) { 8.dp.roundToPx() },
        )
    }

    LaunchedEffect(expanded) {
        if (expanded) {
            attached = true
            scale.snapTo(0.86f)
            alpha.snapTo(0f)
            launch {
                scale.animateTo(1f, tween(durationMillis = 180, easing = FastOutSlowInEasing))
            }
            alpha.animateTo(1f, tween(durationMillis = 140, easing = FastOutSlowInEasing))
        } else if (attached) {
            alpha.animateTo(0f, tween(durationMillis = 110, easing = FastOutLinearInEasing))
            scale.animateTo(0.9f, tween(durationMillis = 110, easing = FastOutLinearInEasing))
            attached = false
        }
    }

    if (!attached) return

    CompositionLocalProvider(LocalYingLiDropdownDismiss provides onDismissRequest) {
        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = true),
        ) {
            Surface(
                modifier = modifier
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        this.alpha = alpha.value
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                    // Widest item plus 10dp total horizontal breathing room.
                    .width(IntrinsicSize.Max)
                    .padding(horizontal = 5.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color.White,
                tonalElevation = 2.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, YingLiTheme.colors.borderDefault),
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 6.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
fun YingLiDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: YingLiIcon? = null,
    enabled: Boolean = true,
) {
    val dismiss = LocalYingLiDropdownDismiss.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    onClick()
                    dismiss?.invoke()
                },
            )
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let {
            Icon(
                imageVector = it.imageVector,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun YingLiDropdownMenuDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        thickness = 1.dp,
        color = YingLiTheme.colors.borderDivider,
    )
}

private val LocalYingLiDropdownDismiss = staticCompositionLocalOf<(() -> Unit)?> { null }

private class YingLiDropdownPositionProvider(
    private val gapPx: Int,
    private val edgeMarginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - edgeMarginPx).coerceAtLeast(edgeMarginPx)
        val x = when (layoutDirection) {
            LayoutDirection.Ltr -> anchorBounds.left
            LayoutDirection.Rtl -> anchorBounds.right - popupContentSize.width
        }.coerceIn(edgeMarginPx, maxX)

        var y = anchorBounds.bottom + gapPx
        if (y + popupContentSize.height > windowSize.height - edgeMarginPx) {
            y = anchorBounds.top - popupContentSize.height - gapPx
        }
        return IntOffset(x, y.coerceIn(edgeMarginPx, (windowSize.height - edgeMarginPx).coerceAtLeast(edgeMarginPx)))
    }
}

@Composable
fun YingLiMenu(
    expanded: Boolean,
    options: List<String>,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    YingLiDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        options.forEachIndexed { index, option ->
            YingLiDropdownMenuItem(text = option, onClick = { onSelected(index) })
        }
    }
}

@Composable
fun YingLiDialog(
    title: String,
    message: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissText) } },
        shape = YingLiTheme.components.componentCorner,
    )
}

enum class BannerKind {
    INFO,
    WARNING,
    ERROR,
    SUCCESS,
}

@Composable
fun YingLiBanner(
    message: String,
    kind: BannerKind,
    modifier: Modifier = Modifier,
) {
    val family = when (kind) {
        BannerKind.INFO -> YingLiTheme.functional.info
        BannerKind.WARNING -> YingLiTheme.functional.warning
        BannerKind.ERROR -> YingLiTheme.functional.error
        BannerKind.SUCCESS -> YingLiTheme.functional.success
    }
    val semanticIcon = when (kind) {
        BannerKind.INFO, BannerKind.WARNING, BannerKind.ERROR -> YingLiIcon.WARNING
        BannerKind.SUCCESS -> YingLiIcon.SUCCESS
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = family.container,
        contentColor = family.onContainer,
        shape = YingLiTheme.components.componentCorner,
    ) {
        Row(
            modifier = Modifier.padding(YingLiTheme.components.pagePadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(semanticIcon.imageVector, contentDescription = null)
            Spacer(Modifier.width(YingLiTheme.components.itemSpacing))
            Text(message, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun YingLiEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(YingLiTheme.components.pagePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(YingLiTheme.components.itemSpacing))
        Text(message, color = YingLiTheme.colors.textSecondary)
        action?.let {
            Spacer(Modifier.height(YingLiTheme.components.sectionSpacing))
            it()
        }
    }
}

@Composable
fun YingLiLoadingState(
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(YingLiTheme.components.pagePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(YingLiTheme.components.itemSpacing))
        Text(message)
    }
}

@Composable
fun YingLiDisabledState(
    message: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(DISABLED_ALPHA)
            .semantics { disabled() }
            .padding(YingLiTheme.components.pagePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(YingLiIcon.LOCK.imageVector, contentDescription = null)
        Spacer(Modifier.width(YingLiTheme.components.itemSpacing))
        Text(message)
    }
}

@Composable
fun YingLiSectionDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = YingLiTheme.colors.borderDivider)
}

private const val DISABLED_ALPHA = 0.40f
