package seeyuer.yingli.player.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

@Composable
fun YingLiSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillMaxWidth: Boolean = true,
) {
    Surface(
        modifier = (if (fillMaxWidth) modifier.fillMaxWidth() else modifier).height(48.dp),
        shape = RoundedCornerShape(8.dp),
        color = YingLiTheme.colors.surfaceComponent,
    ) {
        Row(
            modifier = Modifier.padding(4.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEachIndexed { index, option ->
                SegmentOption(option, index == selectedIndex, enabled) { onSelected(index) }
            }
        }
    }
}

@Composable
private fun RowScope.SegmentOption(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(48.dp)
            .clickable(
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(40.dp),
            shape = RoundedCornerShape(6.dp),
            color = if (selected) YingLiTheme.colors.selectionStructural else YingLiTheme.colors.surfaceComponent,
            contentColor = if (selected) YingLiTheme.colors.selectionOnStructural else YingLiTheme.colors.textPrimary,
        ) {
            Box(
                modifier = Modifier.fillMaxSize().alpha(if (enabled) 1f else 0.38f),
                contentAlignment = Alignment.Center,
            ) {
                Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun YingLiSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(value, onValueChange, enabled = enabled, valueRange = valueRange)
    }
}

@Composable
fun YingLiMenu(
    expanded: Boolean,
    options: List<String>,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        options.forEachIndexed { index, option ->
            DropdownMenuItem(text = { Text(option) }, onClick = { onSelected(index) })
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
