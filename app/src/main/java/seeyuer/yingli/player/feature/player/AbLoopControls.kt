package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.component.YingLiButton
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.playback.AbLoopState

@Composable
internal fun AbLoopCapsule(
    state: AbLoopState,
    onSetA: () -> Unit,
    onSetB: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = YingLiTheme.components.componentCorner,
        color = YingLiTheme.player.edgeScrim,
        contentColor = YingLiTheme.player.controlPrimary,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            YingLiButton("A${state.pointA?.let(::formatAbTime).orEmpty()}", onSetA)
            YingLiButton("B${state.pointB?.let(::formatAbTime).orEmpty()}", onSetB, enabled = state.pointA != null)
            YingLiButton(stringResource(R.string.player_ab_clear), onClear, enabled = state.pointA != null)
            YingLiButton(stringResource(R.string.action_cancel), onClose)
        }
    }
}

private fun formatAbTime(value: Long): String {
    val seconds = value.coerceAtLeast(0) / 1_000
    return " ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
