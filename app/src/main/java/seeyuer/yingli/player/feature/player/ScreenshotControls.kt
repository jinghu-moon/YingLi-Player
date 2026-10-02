package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import coil3.compose.AsyncImage
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.component.YingLiIconButton
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.playback.ScreenshotUiState

@Composable
internal fun ScreenshotToolCapsule(
    state: ScreenshotUiState,
    onPreviousFrame: () -> Unit,
    onCapture: () -> Unit,
    onNextFrame: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state !is ScreenshotUiState.Armed && state !is ScreenshotUiState.Capturing) return
    Surface(
        modifier = modifier,
        shape = YingLiTheme.components.componentCorner,
        color = YingLiTheme.player.edgeScrim,
        contentColor = YingLiTheme.player.controlPrimary,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            YingLiIconButton(
                YingLiIcon.SEEK_BACKWARD,
                stringResource(R.string.player_previous_frame),
                onPreviousFrame,
                enabled = state is ScreenshotUiState.Armed,
                tint = YingLiTheme.player.controlPrimary,
            )
            YingLiIconButton(
                YingLiIcon.SCREENSHOT,
                stringResource(R.string.player_screenshot),
                onCapture,
                enabled = state is ScreenshotUiState.Armed,
                tint = YingLiTheme.player.controlPrimary,
            )
            YingLiIconButton(
                YingLiIcon.SEEK_FORWARD,
                stringResource(R.string.player_next_frame),
                onNextFrame,
                enabled = state is ScreenshotUiState.Armed,
                tint = YingLiTheme.player.controlPrimary,
            )
            YingLiIconButton(
                YingLiIcon.CLOSE,
                stringResource(R.string.action_cancel),
                onClose,
                tint = YingLiTheme.player.controlPrimary,
            )
        }
    }
}

@Composable
internal fun ScreenshotPreview(
    state: ScreenshotUiState.Preview,
    onTogglePause: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit = onClose,
) {
    Surface(
        onClick = onTogglePause,
        modifier = modifier.width(220.dp),
        shape = YingLiTheme.components.componentCorner,
        color = YingLiTheme.colors.surfaceComponent,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.fillMaxWidth().height(72.5.dp)) {
                if (state.uri.isNotBlank()) {
                    AsyncImage(
                        model = state.uri,
                        contentDescription = "截图预览",
                        modifier = Modifier.width(116.dp).aspectRatio(1.6f),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.displayName,
                    maxLines = 1,
                    color = YingLiTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${((state.remainingMillis + 999L) / 1_000L).coerceAtMost(3L)}s",
                    color = YingLiTheme.colors.textSecondary,
                )
                YingLiIconButton(
                    YingLiIcon.CLOSE,
                    stringResource(R.string.action_cancel),
                    onClose,
                    modifier = Modifier.size(48.dp),
                )
                YingLiIconButton(
                    YingLiIcon.WARNING,
                    "删除截图",
                    onDelete,
                    modifier = Modifier.size(48.dp),
                )
            }
            LinearProgressIndicator(
                progress = { state.remainingMillis / ScreenshotUiState.PREVIEW_DURATION_MILLIS.toFloat() },
                color = YingLiTheme.player.controlPrimary,
            )
        }
    }
}
