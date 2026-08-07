package seeyuer.yingli.player.feature.organize

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.component.YingLiEmptyState

@Composable
fun OrganizeScreen(modifier: Modifier = Modifier) {
    YingLiEmptyState(
        title = stringResource(R.string.organize_empty_title),
        message = stringResource(R.string.organize_empty_message),
        modifier = modifier.fillMaxSize(),
    )
}
