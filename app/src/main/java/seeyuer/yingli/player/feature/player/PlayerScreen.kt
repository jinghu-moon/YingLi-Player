package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
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
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.ScreenshotResult
import seeyuer.yingli.player.domain.playback.VideoScaleMode

@OptIn(ExperimentalMaterial3Api::class)
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
    onSeekBackward: () -> Unit = {},
    onSeekForward: () -> Unit = {},
    onToggleOverlay: () -> Unit = {},
    onToggleLock: () -> Unit = {},
    onSetSpeed: (PlaybackSpeed) -> Unit = {},
    onSetScaleMode: (VideoScaleMode) -> Unit = {},
    onSelectAudioTrack: (String) -> Unit = {},
    onSelectSubtitleTrack: (String?) -> Unit = {},
    onPictureInPicture: () -> Unit = {},
    onScreenshot: () -> Unit = {},
    onToggleOrientation: () -> Unit = {},
    allowPictureInPicture: Boolean = true,
    allowScreenshot: Boolean = true,
) {
    var settingsOpen by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(YingLiTheme.player.canvas)
            .pointerInput(state.overlay.locked) {
                detectTapGestures(
                    onTap = { onToggleOverlay() },
                    onDoubleTap = { position ->
                        if (!state.overlay.locked) {
                            if (position.x < size.width / 2f) onSeekBackward() else onSeekForward()
                        }
                    },
                )
            }
            .testTag(PlayerTestTags.CANVAS),
    ) {
        videoSurface()
        if (state.overlay.locked) {
            YingLiIconButton(
                icon = YingLiIcon.UNLOCK,
                contentDescription = stringResource(R.string.player_unlock),
                onClick = onToggleLock,
                tint = YingLiTheme.player.controlPrimary,
                modifier = Modifier.align(Alignment.Center),
            )
        } else if (state.overlay.controlsVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(YingLiTheme.player.edgeScrim)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                YingLiIconButton(
                    icon = YingLiIcon.BACK,
                    contentDescription = stringResource(R.string.action_back),
                    onClick = onBack,
                    tint = YingLiTheme.player.controlPrimary,
                )
                Text(state.title, color = YingLiTheme.player.controlPrimary, maxLines = 1, modifier = Modifier.weight(1f))
                if (allowScreenshot) {
                    YingLiIconButton(
                        icon = YingLiIcon.SCREENSHOT,
                        contentDescription = stringResource(R.string.player_screenshot),
                        onClick = onScreenshot,
                        tint = YingLiTheme.player.controlPrimary,
                    )
                }
                if (allowPictureInPicture) {
                    YingLiIconButton(
                        icon = YingLiIcon.PICTURE_IN_PICTURE,
                        contentDescription = stringResource(R.string.player_pip),
                        onClick = onPictureInPicture,
                        tint = YingLiTheme.player.controlPrimary,
                    )
                }
            }
        }

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
                color = YingLiTheme.player.controlPrimary,
            )
            state.playback is PlaybackState.Ended -> EndedControls(
                onReplay = onReplay,
                onBack = onBack,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (state.overlay.controlsVisible && !state.overlay.locked && (state.playback is PlaybackState.Ready ||
            state.playback is PlaybackState.Playing ||
            state.playback is PlaybackState.Paused)
        ) {
            CenterPlaybackControls(
                playing = state.playback is PlaybackState.Playing,
                onPlay = onPlay,
                onPause = onPause,
                onSeekBackward = onSeekBackward,
                onSeekForward = onSeekForward,
                modifier = Modifier.align(Alignment.Center),
            )
            PlaybackControls(
                state = state,
                onPlay = onPlay,
                onPause = onPause,
                onSeek = onSeek,
                modifier = Modifier.align(Alignment.BottomCenter),
                onOpenSettings = { settingsOpen = true },
                onToggleLock = onToggleLock,
                onToggleOrientation = onToggleOrientation,
            )
        }
        state.screenshotResult?.let { result ->
            Text(
                text = when (result) {
                    is ScreenshotResult.Saved -> stringResource(R.string.player_screenshot_saved)
                    is ScreenshotResult.Failed -> stringResource(R.string.player_screenshot_failed)
                },
                color = YingLiTheme.player.controlPrimary,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp),
            )
        }
    }
    if (settingsOpen) {
        ModalBottomSheet(onDismissRequest = { settingsOpen = false }) {
            AdvancedSettingsSheet(
                state,
                onSetSpeed,
                onSetScaleMode,
                onSelectAudioTrack,
                onSelectSubtitleTrack,
            )
        }
    }
}

@Composable
private fun CenterPlaybackControls(
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
    onOpenSettings: () -> Unit,
    onToggleLock: () -> Unit,
    onToggleOrientation: () -> Unit,
) {
    val duration = state.playback.timeline.durationMillis
    Column(
        modifier = modifier
            .fillMaxWidth()
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
            YingLiIconButton(
                YingLiIcon.LOCK,
                stringResource(R.string.player_lock),
                onToggleLock,
                tint = YingLiTheme.player.controlPrimary,
            )
        }
    }
}

@Composable
private fun AdvancedSettingsSheet(
    state: PlayerUiState,
    onSetSpeed: (PlaybackSpeed) -> Unit,
    onSetScaleMode: (VideoScaleMode) -> Unit,
    onSelectAudioTrack: (String) -> Unit,
    onSelectSubtitleTrack: (String?) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.player_speed), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlaybackSpeed.supportedValues.take(4).forEach { value ->
                val speed = PlaybackSpeed.of(value)
                FilterChip(state.speed == speed, { onSetSpeed(speed) }, { Text("${value}x") })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlaybackSpeed.supportedValues.drop(4).forEach { value ->
                val speed = PlaybackSpeed.of(value)
                FilterChip(state.speed == speed, { onSetSpeed(speed) }, { Text("${value}x") })
            }
        }
        Text(stringResource(R.string.player_scale_mode), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoScaleMode.entries.forEach { mode ->
                FilterChip(state.scaleMode == mode, { onSetScaleMode(mode) }, { Text(mode.name.lowercase()) })
            }
        }
        if (state.audioTracks.isNotEmpty()) {
            Text(stringResource(R.string.player_audio_track), style = MaterialTheme.typography.titleMedium)
            state.audioTracks.forEach { track ->
                FilterChip(track.selected, { onSelectAudioTrack(track.id) }, { Text(track.label) })
            }
        }
        Text(stringResource(R.string.player_subtitle_track), style = MaterialTheme.typography.titleMedium)
        FilterChip(state.subtitleTracks.none { it.selected }, { onSelectSubtitleTrack(null) }, { Text(stringResource(R.string.player_subtitles_off)) })
        state.subtitleTracks.forEach { track ->
            FilterChip(track.selected, { onSelectSubtitleTrack(track.id) }, { Text(track.label) })
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
        Text(stringResource(R.string.player_ended), color = YingLiTheme.player.controlPrimary)
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
        Text(message, color = YingLiTheme.player.controlPrimary)
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
