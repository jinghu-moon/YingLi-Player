package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.component.YingLiIconButton
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme

@Composable
internal fun PlayerTopBar(
    state: PlayerUiState,
    onBack: () -> Unit,
    onScreenshot: () -> Unit,
    onPictureInPicture: () -> Unit,
    allowScreenshot: Boolean,
    allowPictureInPicture: Boolean,
    modifier: Modifier,
) {
    if (!state.overlay.controlsVisible) return
    Row(
        modifier = modifier.fillMaxWidth()
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
        Text(
            text = state.title,
            color = YingLiTheme.player.controlPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
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

@Composable
internal fun PlayerUnlockButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    YingLiIconButton(
        icon = YingLiIcon.UNLOCK,
        contentDescription = stringResource(R.string.player_unlock),
        onClick = onClick,
        tint = YingLiTheme.player.controlPrimary,
        modifier = modifier,
    )
}
