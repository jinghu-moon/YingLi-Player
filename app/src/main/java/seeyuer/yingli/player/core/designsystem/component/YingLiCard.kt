package seeyuer.yingli.player.core.designsystem.component

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.icon.imageVector
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme

data class YingLiCardAction(
    val contentDescription: String,
    val onClick: () -> Unit,
)

@Composable
fun YingLiCard(
    title: String,
    modifier: Modifier = Modifier,
    action: YingLiCardAction? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, YingLiTheme.colors.borderDefault, RoundedCornerShape(8.dp)),
        color = YingLiTheme.colors.surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                action?.let {
                    CardHeaderAction(
                        contentDescription = it.contentDescription,
                        onClick = it.onClick,
                    )
                }
            }
            Spacer(Modifier.height(YingLiTheme.components.cardHeaderSpacing))
            content()
        }
    }
}

@Composable
private fun CardHeaderAction(
    contentDescription: String,
    onClick: () -> Unit,
) {
    val iconSize = YingLiTheme.components.iconSize
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            // Only the icon participates in header layout; the 48dp touch target
            // is centered on it and must not inflate the title row height.
            .reportLayoutSize(iconSize)
            .size(YingLiTheme.components.minimumTouchTarget)
            .clickable(onClickLabel = contentDescription, role = Role.Button, onClick = onClick),
    ) {
        Icon(
            imageVector = YingLiIcon.CHEVRON_RIGHT.imageVector,
            contentDescription = contentDescription,
            tint = LocalContentColor.current,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * Reports [size] to the parent while keeping the child's full measurement
 * for drawing and hit testing (e.g. a 48dp touch target).
 */
private fun Modifier.reportLayoutSize(size: Dp): Modifier = layout { measurable, constraints ->
    val side = size.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = 0, minHeight = 0),
    )
    layout(side, side) {
        placeable.placeRelative(
            (side - placeable.width) / 2,
            (side - placeable.height) / 2,
        )
    }
}
