package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.component.YingLiButton
import seeyuer.yingli.player.core.designsystem.component.YingLiIconButton
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.playback.PlaybackErrorKind
import seeyuer.yingli.player.domain.playback.PlaybackRecoveryAction
import seeyuer.yingli.player.domain.playback.PlaybackState

@Composable
fun PlayerScreen(
    state: PlayerUiState,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onReplay: () -> Unit,
    onRetry: () -> Unit,
    onRecovery: (PlaybackRecoveryAction) -> Unit,
    videoSurface: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(PlayerTestTags.CANVAS),
    ) {
        videoSurface()
        YingLiIconButton(
            icon = YingLiIcon.BACK,
            contentDescription = stringResource(R.string.action_back),
            onClick = onBack,
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(8.dp),
        )

        when {
            state.sourceUnavailable -> PlayerFailure(
                message = stringResource(R.string.player_error_file_missing),
                action = stringResource(R.string.action_back),
                onAction = onBack,
                modifier = Modifier.align(Alignment.Center),
            )
            state.playback is PlaybackState.Failed -> {
                val failed = state.playback
                val retriesPlayback = failed.error.recoveryAction == PlaybackRecoveryAction.RETRY
                PlayerFailure(
                    message = stringResource(failed.error.kind.messageResource()),
                    action = stringResource(failed.error.recoveryAction.actionResource()),
                    onAction = if (retriesPlayback) onRetry else { { onRecovery(failed.error.recoveryAction) } },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            state.playback is PlaybackState.Preparing -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).testTag(PlayerTestTags.LOADING),
                color = Color.White,
            )
            state.playback is PlaybackState.Ended -> EndedControls(
                onReplay = onReplay,
                onBack = onBack,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (state.playback is PlaybackState.Ready ||
            state.playback is PlaybackState.Playing ||
            state.playback is PlaybackState.Paused
        ) {
            PlaybackControls(
                state = state,
                onPlay = onPlay,
                onPause = onPause,
                onSeek = onSeek,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
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
    androidx.compose.material3.Surface(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth().height(56.dp),
        color = YingLiTheme.colors.surfaceComponent,
        contentColor = YingLiTheme.colors.textPrimary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
private fun PlaybackControls(
    state: PlayerUiState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
) {
    val playing = state.playback is PlaybackState.Playing
    val duration = state.playback.timeline.durationMillis
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.72f))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(state.title, color = Color.White, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            YingLiIconButton(
                icon = if (playing) YingLiIcon.PAUSE else YingLiIcon.PLAY,
                contentDescription = stringResource(if (playing) R.string.player_pause else R.string.player_play),
                onClick = if (playing) onPause else onPlay,
                tint = Color.White,
                modifier = Modifier.testTag(PlayerTestTags.PLAY_PAUSE),
            )
            Slider(
                value = state.displayedPositionMillis.toFloat().coerceAtMost((duration ?: 1).toFloat()),
                onValueChange = { onSeek(it.toLong()) },
                modifier = Modifier.weight(1f).testTag(PlayerTestTags.PROGRESS),
                enabled = duration != null && duration > 0,
                valueRange = 0f..(duration ?: 1).coerceAtLeast(1).toFloat(),
            )
            Text(
                text = "${formatDuration(state.displayedPositionMillis)} / ${formatDuration(duration)}",
                color = Color.White,
            )
        }
    }
}

@Composable
private fun EndedControls(onReplay: () -> Unit, onBack: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.player_ended), color = Color.White)
        Spacer(Modifier.height(YingLiTheme.components.itemSpacing))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YingLiButton(stringResource(R.string.player_replay), onReplay, leadingIcon = YingLiIcon.REPLAY)
            YingLiButton(stringResource(R.string.player_next), onClick = {}, enabled = false)
            YingLiButton(stringResource(R.string.action_back), onBack)
        }
    }
}

@Composable
private fun PlayerFailure(message: String, action: String, onAction: () -> Unit, modifier: Modifier) {
    Column(modifier = modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = Color.White)
        Spacer(Modifier.height(YingLiTheme.components.itemSpacing))
        YingLiButton(action, onAction)
    }
}

private fun PlaybackErrorKind.messageResource(): Int = when (this) {
    PlaybackErrorKind.PERMISSION -> R.string.player_error_permission
    PlaybackErrorKind.FILE_MISSING -> R.string.player_error_file_missing
    PlaybackErrorKind.UNSUPPORTED_CONTAINER -> R.string.player_error_container
    PlaybackErrorKind.UNSUPPORTED_DECODER -> R.string.player_error_decoder
    PlaybackErrorKind.CORRUPT_MEDIA -> R.string.player_error_corrupt
    PlaybackErrorKind.UNKNOWN -> R.string.player_error_unknown
}

private fun PlaybackRecoveryAction.actionResource(): Int = when (this) {
    PlaybackRecoveryAction.REAUTHORIZE -> R.string.player_reauthorize
    PlaybackRecoveryAction.RELOCATE -> R.string.player_relocate
    PlaybackRecoveryAction.VIEW_COMPATIBILITY -> R.string.action_back
    PlaybackRecoveryAction.RETRY -> R.string.player_retry
}

private fun formatDuration(durationMillis: Long?): String {
    if (durationMillis == null) return "--:--"
    val totalSeconds = durationMillis.coerceAtLeast(0) / 1_000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

object PlayerTestTags {
    const val CANVAS = "player.canvas"
    const val LOADING = "player.loading"
    const val PLAY_PAUSE = "player.play_pause"
    const val PROGRESS = "player.progress"
}
