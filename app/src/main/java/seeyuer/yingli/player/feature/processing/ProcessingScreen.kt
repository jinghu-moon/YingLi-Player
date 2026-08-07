package seeyuer.yingli.player.feature.processing

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.component.YingLiEmptyState

@Composable
fun ProcessingScreen(modifier: Modifier = Modifier) {
    YingLiEmptyState(
        title = stringResource(R.string.processing_empty_title),
        message = stringResource(R.string.processing_empty_message),
        modifier = modifier.fillMaxSize(),
    )
}
