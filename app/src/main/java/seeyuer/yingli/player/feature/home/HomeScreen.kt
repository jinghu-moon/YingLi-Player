package seeyuer.yingli.player.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.component.*
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.icon.imageVector
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.feature.library.MediaLibraryNotice
import seeyuer.yingli.player.feature.library.MediaLibraryUiState

@Composable
fun HomeRoute(
    mediaState: MediaLibraryUiState,
    viewModel: HomeViewModel,
    onRecommendedSource: () -> Unit,
    onSafSource: () -> Unit,
    onSkip: () -> Unit,
    onRescan: () -> Unit,
    onMediaSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dashboard by viewModel.state.collectAsStateWithLifecycle()
    HomeScreen(
        state = mediaState,
        onRecommendedSource = onRecommendedSource,
        onSafSource = onSafSource,
        onSkip = onSkip,
        onRescan = onRescan,
        modifier = modifier,
        onMediaSelected = onMediaSelected,
        dashboardState = dashboard,
        onSearchChange = viewModel::setKeyword,
    )
}

@Composable
fun HomeScreen(
    state: MediaLibraryUiState,
    onRecommendedSource: () -> Unit,
    onSafSource: () -> Unit,
    onSkip: () -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
    onMediaSelected: (String) -> Unit = {},
    dashboardState: HomeDashboardUiState? = null,
    onSearchChange: (String) -> Unit = {},
    onFolderSelected: (String) -> Unit = {},
) {
    when {
        state.onboarding -> MediaOnboarding(state.scanning, onRecommendedSource, onSafSource, onSkip, modifier)
        state.items.isEmpty() -> EmptyMediaLibrary(state, onRecommendedSource, onSafSource, onRescan, modifier)
        dashboardState != null -> HomeDashboardContent(
            state,
            dashboardState,
            onRescan,
            onSafSource,
            onMediaSelected,
            onSearchChange,
            onFolderSelected,
            modifier,
        )
        else -> MediaLibraryContent(state, onRescan, onMediaSelected, modifier)
    }
}

@Composable
private fun HomeDashboardContent(
    mediaState: MediaLibraryUiState,
    dashboard: HomeDashboardUiState,
    onRescan: () -> Unit,
    onAddDirectory: () -> Unit,
    onMediaSelected: (String) -> Unit,
    onSearchChange: (String) -> Unit,
    onFolderSelected: (String) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(YingLiTheme.components.pagePadding),
        verticalArrangement = Arrangement.spacedBy(YingLiTheme.components.sectionSpacing),
    ) {
        item {
            if (mediaState.scanning) YingLiBanner(stringResource(R.string.media_indexing), BannerKind.INFO)
        }
        if (dashboard.keyword.isNotBlank()) {
            item { HomeSectionTitle(stringResource(R.string.home_search_results), dashboard.searchResults.size) }
            items(dashboard.searchResults, key = { "search_${it.id.value}" }) { item ->
                DashboardMediaRow(item, onMediaSelected)
            }
        } else {
            if (dashboard.continueWatching.isNotEmpty()) {
                item {
                    HomeSectionTitle(stringResource(R.string.home_continue_watching), dashboard.continueWatching.size)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(dashboard.continueWatching, key = { it.id.value }) { item ->
                            Surface(
                                onClick = { onMediaSelected(item.id.value) },
                                modifier = Modifier.width(232.dp),
                                color = Color.Transparent,
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Column(Modifier.clip(RoundedCornerShape(8.dp)).background(YingLiTheme.colors.surfaceComponent)) {
                                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                                        AsyncImage(item.uri.value, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        Box(Modifier.fillMaxSize().background(YingLiTheme.colors.scrimSubtle))
                                        Icon(
                                            imageVector = YingLiIcon.PLAY.imageVector,
                                            contentDescription = stringResource(R.string.player_play),
                                            tint = YingLiTheme.player.controlPrimary,
                                            modifier = Modifier.align(Alignment.Center).size(30.dp),
                                        )
                                        Text(
                                            formatHomeDuration(item.durationMillis),
                                            color = YingLiTheme.player.controlPrimary,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                                        )
                                    }
                                    Column(Modifier.padding(12.dp)) {
                                        Text(item.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        LinearProgressIndicator(progress = { item.watchedFraction }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                                        val remaining = ((item.durationMillis ?: 0) - item.playbackPositionMillis).coerceAtLeast(0)
                                        Text(stringResource(R.string.home_remaining_minutes, remaining / 60_000), color = YingLiTheme.colors.textSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 5.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (dashboard.recentlyAdded.isNotEmpty()) {
                item {
                    HomeSectionTitle(stringResource(R.string.home_recently_added), dashboard.recentlyAdded.size)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(dashboard.recentlyAdded, key = { "recent_${it.id.value}" }) { item ->
                            Surface(onClick = { onMediaSelected(item.id.value) }, modifier = Modifier.width(156.dp), color = YingLiTheme.colors.surfaceComponent, shape = RoundedCornerShape(8.dp)) {
                                Column {
                                    AsyncImage(item.uri.value, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)))
                                    Text(item.title, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp, 8.dp, 8.dp, 2.dp))
                                    Text(item.folderAlias, maxLines = 1, color = YingLiTheme.colors.textSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                }
                            }
                        }
                    }
                }
            }
            if (dashboard.frequentFolders.isNotEmpty()) {
                item { HomeSectionTitle(stringResource(R.string.home_frequent_folders), dashboard.frequentFolders.size) }
                items(dashboard.frequentFolders, key = FrequentFolder::name) { folder ->
                    Surface(
                        onClick = { onFolderSelected(folder.name) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        color = YingLiTheme.colors.surfaceComponent,
                    ) {
                        Row(Modifier.padding(12.dp)) {
                            Text(folder.name, modifier = Modifier.weight(1f))
                            Text(stringResource(R.string.organize_item_count, folder.count))
                        }
                    }
                }
            }
            item {
                Text(stringResource(R.string.home_media_overview), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    HomeStat(stringResource(R.string.home_video_count), dashboard.totalCount.toString(), Modifier.weight(1f))
                    HomeStat(stringResource(R.string.home_total_duration), formatHomeHours(dashboard.totalDurationMillis), Modifier.weight(1f))
                    HomeStat(stringResource(R.string.home_watched_ratio), if (dashboard.totalCount == 0) "0%" else "${(dashboard.completedCount * 100 / dashboard.totalCount)}%", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    YingLiButton(stringResource(R.string.media_add_saf), onAddDirectory)
                    Spacer(Modifier.width(YingLiTheme.components.itemSpacing))
                    YingLiButton(stringResource(R.string.media_rescan), onRescan)
                }
            }
        }
    }
}

@Composable
private fun HomeSectionTitle(title: String, count: Int) {
    Text("$title · $count", style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun DashboardMediaRow(item: seeyuer.yingli.player.domain.library.LibraryMedia, onMediaSelected: (String) -> Unit) {
    Surface(
        onClick = { onMediaSelected(item.id.value) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        color = YingLiTheme.colors.surfaceComponent,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(item.title, maxLines = 1)
            Text(item.folderAlias, color = YingLiTheme.colors.textSecondary)
        }
    }
}

@Composable
private fun HomeStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = YingLiTheme.colors.surfaceComponent, shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(label, color = YingLiTheme.colors.textSecondary, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

private fun formatHomeDuration(value: Long?): String {
    value ?: return "--:--"
    val seconds = value / 1_000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

private fun formatHomeHours(value: Long): String = "%.1fh".format(value / 3_600_000f)

@Composable
private fun MediaOnboarding(
    scanning: Boolean,
    onRecommendedSource: () -> Unit,
    onSafSource: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier,
) {
    var selectedAccess by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(MediaAccessOption.ALL_FILES) }
    val brandColor = YingLiTheme.colors.textPrimary
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        Surface(
            modifier = Modifier.size(76.dp),
            shape = RoundedCornerShape(22.dp),
            color = YingLiTheme.colors.surfaceMuted,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(56.dp)) {
                    rotate(-45f) {
                        val centerX = size.width / 2f
                        val lineYs = listOf(.22f, .37f, .5f, .63f, .78f)
                        val lineLengths = listOf(.42f, .63f, .80f, .63f, .42f)
                        val opacities = listOf(.4f, .7f, 1f, .7f, .4f)
                        lineYs.forEachIndexed { index, yRatio ->
                            val halfLength = size.width * lineLengths[index] / 2f
                            drawLine(
                                color = brandColor.copy(alpha = opacities[index]),
                                start = androidx.compose.ui.geometry.Offset(centerX - halfLength, size.height * yRatio),
                                end = androidx.compose.ui.geometry.Offset(centerX + halfLength, size.height * yRatio),
                                strokeWidth = 7.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.media_onboarding_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.media_onboarding_message),
            color = YingLiTheme.colors.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.media_access_method), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.media_access_change_anytime), color = YingLiTheme.functional.info.strong, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(8.dp))
        AccessOptionCard(
            title = stringResource(R.string.media_add_all_files),
            message = stringResource(R.string.media_all_files_description),
            badge = stringResource(R.string.media_recommended_full),
            icon = YingLiIcon.LIBRARY.imageVector,
            selected = selectedAccess == MediaAccessOption.ALL_FILES,
            onClick = { selectedAccess = MediaAccessOption.ALL_FILES },
        )
        Spacer(Modifier.height(8.dp))
        AccessOptionCard(
            title = stringResource(R.string.media_add_saf),
            message = stringResource(R.string.media_saf_description),
            badge = stringResource(R.string.media_low_permission),
            icon = YingLiIcon.ORGANIZE.imageVector,
            selected = selectedAccess == MediaAccessOption.SAF_TREE,
            onClick = { selectedAccess = MediaAccessOption.SAF_TREE },
        )
        Spacer(Modifier.height(16.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = YingLiTheme.components.compactCorner,
            color = YingLiTheme.colors.surfaceSubtle,
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                Icon(YingLiIcon.LOCK.imageVector, contentDescription = null, tint = YingLiTheme.functional.info.base, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.media_privacy_message), color = YingLiTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = if (selectedAccess == MediaAccessOption.ALL_FILES) onRecommendedSource else onSafSource,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = YingLiTheme.components.compactCorner,
        ) {
            Icon(YingLiIcon.ARROW_RIGHT.imageVector, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (selectedAccess == MediaAccessOption.ALL_FILES) R.string.media_continue_all_files else R.string.media_continue_add_directory))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(stringResource(R.string.media_add_later))
        }
        Text(
            stringResource(R.string.media_onboarding_footer),
            color = YingLiTheme.colors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp),
        )
        if (scanning) YingLiLoadingState(stringResource(R.string.media_indexing))
    }
}

private enum class MediaAccessOption { ALL_FILES, SAF_TREE }

@Composable
private fun AccessOptionCard(
    title: String,
    message: String,
    badge: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) YingLiTheme.colors.borderStrong else YingLiTheme.colors.borderDefault,
                shape = YingLiTheme.components.componentCorner,
            )
            .clickable(onClick = onClick),
        shape = YingLiTheme.components.componentCorner,
        color = if (selected) YingLiTheme.colors.surfaceSubtle else YingLiTheme.colors.surface,
    ) {
        Box(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(end = 30.dp), verticalAlignment = Alignment.Top) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = YingLiTheme.components.compactCorner,
                    color = YingLiTheme.functional.info.container,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = YingLiTheme.functional.info.base, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(message, color = YingLiTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    Surface(
                        modifier = Modifier.padding(top = 7.dp),
                        shape = RoundedCornerShape(50),
                        color = if (selected) YingLiTheme.functional.info.container else YingLiTheme.colors.surfaceMuted,
                    ) {
                        Text(badge, color = if (selected) YingLiTheme.functional.info.strong else YingLiTheme.colors.textSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
            }
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).size(22.dp),
                shape = RoundedCornerShape(50),
                color = if (selected) YingLiTheme.colors.actionPrimary else Color.Transparent,
                border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, YingLiTheme.colors.borderControl),
            ) {
                if (selected) {
                    Icon(YingLiIcon.SUCCESS.imageVector, contentDescription = null, tint = YingLiTheme.colors.actionOnPrimary, modifier = Modifier.padding(3.dp))
                }
            }
        }
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
    if (state.scanning) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            YingLiLoadingState(stringResource(R.string.media_indexing))
        }
        return
    }
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
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing),
                ) {
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
