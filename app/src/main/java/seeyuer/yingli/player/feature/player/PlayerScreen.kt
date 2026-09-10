package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.playback.PlaybackRecoveryAction
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.PlaybackState
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
        modifier = modifier.fillMaxSize()
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
            PlayerUnlockButton(onToggleLock, Modifier.align(Alignment.Center))
        } else {
            PlayerTopBar(
                state = state,
                onBack = onBack,
                onScreenshot = onScreenshot,
                onPictureInPicture = onPictureInPicture,
                allowScreenshot = allowScreenshot,
                allowPictureInPicture = allowPictureInPicture,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        PlayerStatusOverlay(
            state = state,
            onBack = onBack,
            onReplay = onReplay,
            onRetry = onRetry,
            onRecovery = onRecovery,
            modifier = Modifier.align(Alignment.Center),
        )
        if (state.overlay.controlsVisible && !state.overlay.locked && state.playback.hasTransportControls()) {
            CenterPlaybackControls(
                playing = state.playback is PlaybackState.Playing,
                onPlay = onPlay,
                onPause = onPause,
                onSeekBackward = onSeekBackward,
                onSeekForward = onSeekForward,
                modifier = Modifier.align(Alignment.Center),
            )
            BottomPlaybackControls(
                state = state,
                onSeek = onSeek,
                onToggleLock = onToggleLock,
                onToggleOrientation = onToggleOrientation,
                onOpenSettings = { settingsOpen = true },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        state.screenshotResult?.let { result ->
            Text(
                text = when (result) {
                    is ScreenshotResult.Saved -> stringResource(R.string.player_screenshot_saved)
                    is ScreenshotResult.Failed -> stringResource(R.string.player_screenshot_failed)
                },
                color = YingLiTheme.player.controlPrimary,
                modifier = Modifier.align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 80.dp),
            )
        }
    }
    if (settingsOpen) {
        ModalBottomSheet(onDismissRequest = { settingsOpen = false }) {
            PlayerSettingsSheet(state, onSetSpeed, onSetScaleMode, onSelectAudioTrack, onSelectSubtitleTrack)
        }
    }
}

private fun PlaybackState.hasTransportControls(): Boolean =
    this is PlaybackState.Ready || this is PlaybackState.Playing || this is PlaybackState.Paused

object PlayerTestTags {
    const val CANVAS = "player.canvas"
    const val LOADING = "player.loading"
    const val PLAY_PAUSE = "player.play_pause"
    const val PROGRESS = "player.progress"
}
