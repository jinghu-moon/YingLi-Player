package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.component.YingLiButton
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.playback.PlaybackErrorKind
import seeyuer.yingli.player.domain.playback.PlaybackRecoveryAction
import seeyuer.yingli.player.domain.playback.PlaybackState

@Composable
internal fun PlayerStatusOverlay(
    state: PlayerUiState,
    onBack: () -> Unit,
    onReplay: () -> Unit,
    onRetry: () -> Unit,
    onRecovery: (PlaybackRecoveryAction) -> Unit,
    modifier: Modifier,
) {
    when {
        state.sourceUnavailable -> PlayerFailure(
            stringResource(R.string.player_error_file_missing),
            stringResource(R.string.action_back),
            onBack,
            modifier,
        )
        state.playback is PlaybackState.Failed -> {
            val failed = state.playback
            val retries = failed.error.recoveryAction == PlaybackRecoveryAction.RETRY
            PlayerFailure(
                stringResource(failed.error.kind.messageResource()),
                stringResource(failed.error.recoveryAction.actionResource()),
                if (retries) onRetry else { { onRecovery(failed.error.recoveryAction) } },
                modifier,
            )
        }
        state.playback is PlaybackState.Preparing -> CircularProgressIndicator(
            modifier = modifier.testTag(PlayerTestTags.LOADING),
            color = YingLiTheme.player.controlPrimary,
        )
        state.playback is PlaybackState.Ended -> EndedControls(onReplay, onBack, modifier)
    }
}

@Composable
private fun EndedControls(onReplay: () -> Unit, onBack: () -> Unit, modifier: Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
