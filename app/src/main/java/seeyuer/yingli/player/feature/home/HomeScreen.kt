package seeyuer.yingli.player.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.component.*
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.feature.library.MediaLibraryNotice
import seeyuer.yingli.player.feature.library.MediaLibraryUiState

@Composable
fun HomeScreen(
    state: MediaLibraryUiState,
    onRecommendedSource: () -> Unit,
    onSafSource: () -> Unit,
    onSkip: () -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
    onMediaSelected: (String) -> Unit = {},
) {
    when {
        state.onboarding -> MediaOnboarding(state.scanning, onRecommendedSource, onSafSource, onSkip, modifier)
        state.items.isEmpty() -> EmptyMediaLibrary(state, onRecommendedSource, onSafSource, onRescan, modifier)
        else -> MediaLibraryContent(state, onRescan, onMediaSelected, modifier)
    }
}

@Composable
private fun MediaOnboarding(
    scanning: Boolean,
    onRecommendedSource: () -> Unit,
    onSafSource: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(YingLiTheme.components.pagePadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.media_onboarding_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(YingLiTheme.components.itemSpacing))
        Text(stringResource(R.string.media_onboarding_message), color = YingLiTheme.colors.textSecondary)
        Spacer(Modifier.height(YingLiTheme.components.sectionSpacing))
        YingLiButton(stringResource(R.string.media_add_all_files), onRecommendedSource, leadingIcon = YingLiIcon.LIBRARY)
        Spacer(Modifier.height(YingLiTheme.components.itemSpacing))
        YingLiButton(stringResource(R.string.media_add_saf), onSafSource, leadingIcon = YingLiIcon.ORGANIZE)
        Spacer(Modifier.height(YingLiTheme.components.itemSpacing))
        YingLiButton(stringResource(R.string.media_add_later), onSkip)
        if (scanning) YingLiLoadingState(stringResource(R.string.media_indexing))
    }
}

@Composable
private fun EmptyMediaLibrary(
    state: MediaLibraryUiState,
    onRecommendedSource: () -> Unit,
    onSafSource: () -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize().padding(YingLiTheme.components.pagePadding)) {
        state.notice?.let { notice ->
            YingLiBanner(
                message = stringResource(when (notice) {
                    MediaLibraryNotice.PERMISSION_DENIED -> R.string.media_permission_denied
                    MediaLibraryNotice.SOURCE_OFFLINE -> R.string.media_source_offline
                    MediaLibraryNotice.SCAN_PARTIAL -> R.string.media_scan_partial
                }),
                kind = BannerKind.WARNING,
            )
            Spacer(Modifier.height(YingLiTheme.components.itemSpacing))
        }
        YingLiEmptyState(
            title = stringResource(R.string.home_empty_title),
            message = stringResource(R.string.home_empty_message),
            modifier = Modifier.weight(1f),
            action = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    YingLiButton(stringResource(R.string.media_add_source), onRecommendedSource)
                    YingLiButton(stringResource(R.string.media_add_saf), onSafSource)
                    if (state.sources.isNotEmpty()) YingLiButton(stringResource(R.string.media_rescan), onRescan)
                }
            },
        )
        if (state.scanning) YingLiBanner(stringResource(R.string.media_indexing), BannerKind.INFO)
    }
}

@Composable
private fun MediaLibraryContent(
    state: MediaLibraryUiState,
    onRescan: () -> Unit,
    onMediaSelected: (String) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize()) {
        if (state.scanning) YingLiBanner(stringResource(R.string.media_indexing), BannerKind.INFO)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = YingLiTheme.components.pagePadding),
            horizontalArrangement = Arrangement.End,
        ) { YingLiButton(stringResource(R.string.media_rescan), onRescan) }
        LazyColumn(contentPadding = PaddingValues(YingLiTheme.components.pagePadding)) {
            items(state.items, key = { it.id.value }) { media ->
                Surface(
                    onClick = { onMediaSelected(media.id.value) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = YingLiTheme.components.minimumTouchTarget),
                    color = YingLiTheme.colors.page,
                ) {
                    Text(media.title, modifier = Modifier.fillMaxWidth().padding(YingLiTheme.components.pagePadding))
                }
                YingLiSectionDivider()
            }
        }
    }
}
