package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.component.YingLiButton
import seeyuer.yingli.player.core.designsystem.component.YingLiIconButton
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.playback.PlaybackState

@Composable
internal fun CenterPlaybackControls(
    playing: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.background(YingLiTheme.player.edgeScrim).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        YingLiIconButton(YingLiIcon.SEEK_BACKWARD, stringResource(R.string.player_seek_backward), onSeekBackward, tint = YingLiTheme.player.controlPrimary)
        YingLiIconButton(
            if (playing) YingLiIcon.PAUSE else YingLiIcon.PLAY,
            stringResource(if (playing) R.string.player_pause else R.string.player_play),
            if (playing) onPause else onPlay,
            tint = YingLiTheme.player.controlPrimary,
        )
        YingLiIconButton(YingLiIcon.SEEK_FORWARD, stringResource(R.string.player_seek_forward), onSeekForward, tint = YingLiTheme.player.controlPrimary)
    }
}

@Composable
fun MiniPlayerBar(
    state: PlayerUiState,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playing = state.playback is PlaybackState.Playing
    Surface(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth().height(56.dp),
        color = YingLiTheme.colors.surfaceComponent,
        contentColor = YingLiTheme.colors.textPrimary,
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(state.title, modifier = Modifier.weight(1f), maxLines = 1)
            YingLiIconButton(
                icon = if (playing) YingLiIcon.PAUSE else YingLiIcon.PLAY,
                contentDescription = stringResource(if (playing) R.string.player_pause else R.string.player_play),
                onClick = if (playing) onPause else onPlay,
            )
        }
    }
}

@Composable
internal fun BottomPlaybackControls(
    state: PlayerUiState,
    onSeek: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onToggleLock: () -> Unit,
    onToggleOrientation: () -> Unit,
    modifier: Modifier,
) {
    val duration = state.playback.timeline.durationMillis
    Column(
        modifier = modifier.fillMaxWidth()
            .background(YingLiTheme.player.edgeScrim)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(state.title, color = YingLiTheme.player.controlPrimary, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = state.displayedPositionMillis.toFloat().coerceAtMost((duration ?: 1).toFloat()),
                onValueChange = { onSeek(it.toLong()) },
                modifier = Modifier.weight(1f).testTag(PlayerTestTags.PROGRESS),
                enabled = duration != null && duration > 0,
                valueRange = 0f..(duration ?: 1).coerceAtLeast(1).toFloat(),
            )
            Text(
                text = "${formatDuration(state.displayedPositionMillis)} / ${formatDuration(duration)}",
                color = YingLiTheme.player.controlPrimary,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            YingLiButton("${state.speed.value}x", onOpenSettings)
            YingLiButton(stringResource(R.string.player_rotate), onToggleOrientation)
            YingLiIconButton(YingLiIcon.LOCK, stringResource(R.string.player_lock), onToggleLock, tint = YingLiTheme.player.controlPrimary)
        }
    }
}

private fun formatDuration(durationMillis: Long?): String {
    if (durationMillis == null) return "--:--"
    val totalSeconds = durationMillis.coerceAtLeast(0) / 1_000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
