package seeyuer.yingli.player.feature.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.component.YingLiEmptyState

@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    YingLiEmptyState(
        title = stringResource(R.string.library_empty_title),
        message = stringResource(R.string.library_empty_message),
        modifier = modifier.fillMaxSize(),
    )
}
