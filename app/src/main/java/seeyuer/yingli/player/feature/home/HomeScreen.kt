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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.component.*
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.icon.imageVector
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.core.media.ThumbnailLoader
import seeyuer.yingli.player.core.model.media.ThumbnailPriority
import seeyuer.yingli.player.core.model.media.ThumbnailRequest
import seeyuer.yingli.player.domain.home.HomeCardId
import seeyuer.yingli.player.domain.home.HomeCardLayout
import seeyuer.yingli.player.domain.home.HomeCollectionPreview
import seeyuer.yingli.player.domain.home.HomeFolderPreview
import seeyuer.yingli.player.domain.home.HomeMediaPreview
import seeyuer.yingli.player.domain.home.HomeStats
import seeyuer.yingli.player.domain.home.MaintenanceItem
import seeyuer.yingli.player.domain.home.MaintenanceKind
import seeyuer.yingli.player.feature.library.MediaLibraryNotice
import seeyuer.yingli.player.feature.library.MediaLibraryUiState
import java.util.Locale
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.rememberReorderableLazyListState

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
    thumbnailRepository: ThumbnailLoader? = null,
    onOpenLibrary: () -> Unit = {},
    onOpenOrganize: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onFolderSelected: (String) -> Unit = {},
) {
    val homeState by viewModel.state.collectAsStateWithLifecycle()
    HomeScreen(
        state = mediaState,
        onRecommendedSource = onRecommendedSource,
        onSafSource = onSafSource,
        onSkip = onSkip,
        onRescan = onRescan,
        modifier = modifier,
        onMediaSelected = onMediaSelected,
        dashboardState = homeState,
        thumbnailRepository = thumbnailRepository,
        onOpenLibrary = onOpenLibrary,
        onOpenOrganize = onOpenOrganize,
        onOpenStats = onOpenStats,
        onFolderSelected = onFolderSelected,
        onMoveCard = viewModel::moveCard,
        onSetCardVisible = viewModel::setCardVisible,
        onResetLayout = viewModel::resetLayout,
        onDismissEditor = viewModel::hideEditor,
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
    dashboardState: HomeUiState? = null,
    onFolderSelected: (String) -> Unit = {},
    thumbnailRepository: ThumbnailLoader? = null,
    onOpenLibrary: () -> Unit = {},
    onOpenOrganize: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onMoveCard: (Int, Int) -> Unit = { _, _ -> },
    onSetCardVisible: (HomeCardId, Boolean) -> Unit = { _, _ -> },
    onResetLayout: () -> Unit = {},
    onDismissEditor: () -> Unit = {},
) {
    when {
        state.onboarding -> MediaOnboarding(state.scanning, onRecommendedSource, onSafSource, onSkip, modifier)
        state.items.isEmpty() -> EmptyMediaLibrary(state, onRecommendedSource, onSafSource, onRescan, modifier)
        dashboardState != null -> HomeDashboardContent(
            state,
            dashboardState,
            onMediaSelected,
            onFolderSelected,
            thumbnailRepository,
            onOpenLibrary,
            onOpenOrganize,
            onOpenStats,
            onResetLayout,
            modifier,
        )
        else -> MediaLibraryContent(state, onRescan, onMediaSelected, modifier)
    }
    if (dashboardState?.editorVisible == true) {
        HomeCardOrderSheet(
            layout = dashboardState.layout,
            onMove = onMoveCard,
            onSetVisible = onSetCardVisible,
            onReset = onResetLayout,
            onDismiss = onDismissEditor,
        )
    }
}

@Composable
private fun HomeDashboardContent(
    mediaState: MediaLibraryUiState,
    dashboard: HomeUiState,
    onMediaSelected: (String) -> Unit,
    onFolderSelected: (String) -> Unit,
    thumbnailRepository: ThumbnailLoader?,
    onOpenLibrary: () -> Unit,
    onOpenOrganize: () -> Unit,
    onOpenStats: () -> Unit,
    onResetLayout: () -> Unit,
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
        if (dashboard.search.keyword.isNotBlank()) {
            item { HomeSectionTitle(stringResource(R.string.home_search_results), dashboard.search.results.size) }
            items(dashboard.search.results, key = { "search_${it.id.value}" }) { item ->
                DashboardMediaRow(item, onMediaSelected)
            }
        } else {
            if (dashboard.allCardsHidden) {
                item {
                    YingLiEmptyState(
                        title = stringResource(R.string.home_all_cards_hidden),
                        message = stringResource(R.string.home_all_cards_hidden_message),
                        action = { YingLiButton(stringResource(R.string.home_restore_default), onResetLayout) },
                    )
                }
            }
            dashboard.layout.order.forEach { cardId ->
                if (cardId !in dashboard.layout.hidden) {
                    when (cardId) {
                        HomeCardId.STATS -> dashboard.stats?.takeIf { it.videoCount > 0 }?.let { stats ->
                            item(cardId.name) { HomeStatsCard(stats, onOpenStats) }
                        }
                        HomeCardId.CONTINUE_WATCHING -> if (dashboard.continueWatching.isNotEmpty()) {
                            item(cardId.name) { ContinueWatchingCard(dashboard.continueWatching, onMediaSelected, thumbnailRepository) }
                        }
                        HomeCardId.RECENTLY_ADDED -> if (dashboard.recentlyAdded.isNotEmpty()) {
                            item(cardId.name) { RecentlyAddedCard(dashboard.recentlyAdded, onMediaSelected, thumbnailRepository, onOpenLibrary) }
                        }
                        HomeCardId.MY_COLLECTIONS -> if (dashboard.collections.isNotEmpty()) {
                            item(cardId.name) { MyCollectionsCard(dashboard.collections, onOpenOrganize) }
                        }
                        HomeCardId.FREQUENT_FOLDERS -> if (dashboard.frequentFolders.isNotEmpty()) {
                            item(cardId.name) { FrequentFoldersCard(dashboard.frequentFolders, onFolderSelected) }
                        }
                        HomeCardId.MAINTENANCE -> if (dashboard.maintenance.isNotEmpty()) {
                            item(cardId.name) { MaintenanceCard(dashboard.maintenance, onOpenOrganize) }
                        }
                    }
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
private fun HomeCard(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().border(1.dp, YingLiTheme.colors.borderDefault, RoundedCornerShape(8.dp)),
        color = YingLiTheme.colors.surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (onAction != null) {
                    TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                        if (actionLabel != null) Text(actionLabel)
                        Icon(YingLiIcon.ARROW_RIGHT.imageVector, contentDescription = actionLabel, modifier = Modifier.size(18.dp))
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun HomeStatsCard(stats: HomeStats, onOpenStats: () -> Unit) {
    val deviceTotal = stats.deviceTotalBytes
    val deviceAvailable = stats.deviceAvailableBytes.coerceIn(0, deviceTotal)
    val used = (deviceTotal - deviceAvailable).coerceAtLeast(0)
    val video = stats.videoBytes.coerceAtMost(used)
    val other = (used - video).coerceAtLeast(0)
    val barTotal = (video + other + deviceAvailable).coerceAtLeast(1)
    HomeCard(stringResource(R.string.home_media_overview), onAction = onOpenStats) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            StatMetric(formatCount(stats.videoCount), stringResource(R.string.home_video_count), Modifier.weight(1f))
            StatMetric(formatFileSize(stats.videoBytes), stringResource(R.string.home_space_used), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_device_storage), color = YingLiTheme.colors.textSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text("${formatFileSize(used)}/${formatFileSize(deviceTotal)}", style = MaterialTheme.typography.labelLarge)
        }
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).padding(top = 0.dp)) {
            if (video > 0) Box(Modifier.weight((video.toDouble() / barTotal).toFloat()).fillMaxHeight().background(YingLiTheme.colors.actionPrimary))
            if (other > 0) Box(Modifier.weight((other.toDouble() / barTotal).toFloat()).fillMaxHeight().background(YingLiTheme.colors.textSecondary))
            if (deviceAvailable > 0) Box(Modifier.weight((deviceAvailable.toDouble() / barTotal).toFloat()).fillMaxHeight().background(YingLiTheme.colors.surfaceMuted))
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StorageLegend(YingLiTheme.colors.actionPrimary, stringResource(R.string.home_storage_video), video, barTotal)
            StorageLegend(YingLiTheme.colors.textSecondary, stringResource(R.string.home_storage_other), other, barTotal)
            StorageLegend(YingLiTheme.colors.surfaceMuted, stringResource(R.string.home_storage_available), deviceAvailable, barTotal)
        }
    }
}

@Composable
private fun StatMetric(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(label, color = YingLiTheme.colors.textSecondary, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StorageLegend(color: Color, label: String, bytes: Long, total: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(color))
        val percentage = ((bytes.toDouble() / total.coerceAtLeast(1)) * 100).toInt()
        Text("$label$percentage%", color = YingLiTheme.colors.textSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun ContinueWatchingCard(items: List<HomeMediaPreview>, onMediaSelected: (String) -> Unit, thumbnails: ThumbnailLoader?) {
    HomeCard(stringResource(R.string.home_continue_watching)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 10.dp)) {
            items(items, key = { it.mediaId.value }) { item ->
                Surface(onClick = { onMediaSelected(item.mediaId.value) }, modifier = Modifier.width(220.dp), color = Color.Transparent, shape = RoundedCornerShape(8.dp)) {
                    Column {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
                            YingLiThumbnail(item.thumbnailRequest(ThumbnailPriority.CONTINUE_WATCHING), thumbnails, contentDescription = item.title)
                            Box(Modifier.fillMaxSize().background(YingLiTheme.colors.scrimSubtle))
                            Icon(YingLiIcon.PLAY.imageVector, stringResource(R.string.player_play), tint = YingLiTheme.player.controlPrimary, modifier = Modifier.align(Alignment.Center).size(30.dp))
                            LinearProgressIndicator(
                                progress = { item.watchedFraction },
                                modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                                color = YingLiTheme.player.controlPrimary,
                                trackColor = YingLiTheme.player.track,
                            )
                        }
                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        val remaining = ((item.durationMillis ?: 0) - item.playbackPositionMillis).coerceAtLeast(0)
                        Text(stringResource(R.string.home_remaining_minutes, remaining / 60_000), color = YingLiTheme.colors.textSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentlyAddedCard(items: List<HomeMediaPreview>, onMediaSelected: (String) -> Unit, thumbnails: ThumbnailLoader?, onOpenLibrary: () -> Unit) {
    HomeCard(stringResource(R.string.home_recently_added), stringResource(R.string.home_all), onOpenLibrary) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 8.dp)) {
            items(items, key = { it.mediaId.value }) { item ->
                Surface(onClick = { onMediaSelected(item.mediaId.value) }, modifier = Modifier.width(154.dp), color = Color.Transparent, shape = RoundedCornerShape(8.dp)) {
                    Column {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
                            YingLiThumbnail(item.thumbnailRequest(ThumbnailPriority.RECENTLY_ADDED), thumbnails, contentDescription = item.title)
                            Surface(Modifier.align(Alignment.BottomEnd).padding(5.dp), color = YingLiTheme.player.edgeScrim, shape = RoundedCornerShape(3.dp)) {
                                Text(formatHomeDuration(item.durationMillis), color = YingLiTheme.player.controlPrimary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                            }
                        }
                        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 7.dp))
                        Text("${formatFileSize(item.sizeBytes)} · ${item.resolutionLabel()}", maxLines = 1, color = YingLiTheme.colors.textSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MyCollectionsCard(items: List<HomeCollectionPreview>, onOpenOrganize: () -> Unit) {
    HomeCard(stringResource(R.string.home_my_collections), stringResource(R.string.home_all), onOpenOrganize) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEach { item ->
                Surface(onClick = onOpenOrganize, modifier = Modifier.weight(1f), color = Color.Transparent, shape = RoundedCornerShape(8.dp)) {
                    Column {
                        Box(Modifier.fillMaxWidth().aspectRatio(1.35f).clip(RoundedCornerShape(8.dp)).background(YingLiTheme.colors.surfaceMuted), contentAlignment = Alignment.Center) {
                            Icon(YingLiIcon.ORGANIZE.imageVector, contentDescription = null, tint = YingLiTheme.colors.textSecondary, modifier = Modifier.size(28.dp))
                        }
                        Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 7.dp))
                        Text(stringResource(R.string.home_video_items, item.itemCount), color = YingLiTheme.colors.textSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun FrequentFoldersCard(items: List<HomeFolderPreview>, onFolderSelected: (String) -> Unit) {
    HomeCard(stringResource(R.string.home_frequent_folders)) {
        items.forEach { folder ->
            Surface(onClick = { onFolderSelected(folder.sourceId) }, color = Color.Transparent, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(6.dp), color = YingLiTheme.colors.surfaceMuted) {
                        Icon(YingLiIcon.ORGANIZE.imageVector, contentDescription = null, modifier = Modifier.padding(9.dp).size(20.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text("${stringResource(R.string.home_video_items, folder.itemCount)} · ${formatFileSize(folder.sizeBytes)}", color = YingLiTheme.colors.textSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    Icon(YingLiIcon.ARROW_RIGHT.imageVector, contentDescription = null, tint = YingLiTheme.colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun MaintenanceCard(items: List<MaintenanceItem>, onOpenOrganize: () -> Unit) {
    HomeCard(stringResource(R.string.home_maintenance), stringResource(R.string.home_go_organize), onOpenOrganize) {
        items.forEach { item ->
            val title = when (item.kind) {
                MaintenanceKind.DUPLICATES -> stringResource(R.string.home_duplicates_found)
                MaintenanceKind.TRASH -> stringResource(R.string.home_trash_pending)
            }
            val detail = when (item.kind) {
                MaintenanceKind.DUPLICATES -> stringResource(R.string.home_duplicate_summary, item.itemCount, formatFileSize(item.reclaimableBytes))
                MaintenanceKind.TRASH -> stringResource(R.string.home_trash_summary, item.itemCount)
            }
            Surface(onClick = onOpenOrganize, color = Color.Transparent, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(6.dp), color = YingLiTheme.colors.surfaceMuted) {
                        Icon(YingLiIcon.SETTINGS.imageVector, contentDescription = null, modifier = Modifier.padding(9.dp).size(20.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(detail, color = YingLiTheme.colors.textSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    Icon(YingLiIcon.ARROW_RIGHT.imageVector, contentDescription = null, tint = YingLiTheme.colors.textSecondary)
                }
            }
        }
    }
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

private fun formatHomeDuration(value: Long?): String {
    value ?: return "--:--"
    val seconds = value / 1_000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

private val HomeMediaPreview.watchedFraction: Float
    get() = durationMillis?.takeIf { it > 0 }?.let { (playbackPositionMillis.toFloat() / it).coerceIn(0f, 1f) } ?: 0f

private fun HomeMediaPreview.thumbnailRequest(priority: ThumbnailPriority) = ThumbnailRequest(
    mediaItemId = mediaId,
    locationId = locationId,
    uri = uri,
    widthPixels = 320,
    heightPixels = 180,
    priority = priority,
    modifiedEpochMillis = modifiedEpochMillis,
    sizeBytes = sizeBytes,
)

private fun HomeMediaPreview.resolutionLabel(): String = width?.let { width ->
    when {
        width >= 7_680 -> "4320P"
        width >= 3_840 -> "2160P"
        width >= 2_560 -> "1440P"
        width >= 1_920 -> "1080P"
        width >= 1_280 -> "720P"
        else -> "${height ?: width}P"
    }
} ?: "--P"

private fun formatCount(value: Int): String = String.format(Locale.US, "%,d", value)

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1_024) return "${bytes}B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1_024 && index < units.lastIndex) {
        value /= 1_024
        index += 1
    }
    return if (value >= 100 || value % 1.0 == 0.0) "%.0f%s".format(Locale.US, value, units[index])
    else "%.1f%s".format(Locale.US, value, units[index])
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeCardOrderSheet(
    layout: HomeCardLayout,
    onMove: (Int, Int) -> Unit,
    onSetVisible: (HomeCardId, Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        onMove(from.index, to.index)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // The sheet must not compete with the list's vertical reorder gesture.
        sheetGesturesEnabled = false,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.home_customize), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Box(Modifier.fillMaxWidth().height(44.dp).padding(top = 10.dp)) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = YingLiTheme.colors.surfaceMuted,
                    shape = YingLiTheme.components.compactCorner,
                ) {
                    Text(
                        text = stringResource(R.string.home_card_count, layout.order.size),
                        color = YingLiTheme.colors.textSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(top = 12.dp),
                state = lazyListState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(layout.order, key = { _, id -> id.name }) { index, id ->
                    ReorderableItem(reorderableState, key = id.name) { isDragging ->
                        HomeCardOrderRow(
                            id = id,
                            isDragging = isDragging,
                            visible = id !in layout.hidden,
                            canMoveUp = index > 0,
                            canMoveDown = index < layout.order.lastIndex,
                            onMoveUp = { onMove(index, index - 1) },
                            onMoveDown = { onMove(index, index + 1) },
                            onVisibleChange = { onSetVisible(id, it) },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onReset, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(YingLiIcon.REPLAY.imageVector, contentDescription = null)
                    Text(stringResource(R.string.home_restore_default), modifier = Modifier.padding(start = 6.dp))
                }
                Button(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.home_done))
                }
            }
        }
    }
}

@Composable
private fun ReorderableCollectionItemScope.HomeCardOrderRow(
    id: HomeCardId,
    isDragging: Boolean,
    visible: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onVisibleChange: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val title = homeCardTitle(id)
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .alpha(if (visible) 1f else .45f)
            .background(
                if (isDragging) YingLiTheme.colors.surfaceComponentHover else YingLiTheme.colors.surface,
                shape,
            )
            .border(
                width = 1.dp,
                color = if (isDragging) YingLiTheme.colors.borderStrong else YingLiTheme.colors.borderDefault,
                shape = shape,
            )
            .padding(horizontal = 8.dp)
            .semantics {
                customActions = buildList {
                    if (canMoveUp) add(CustomAccessibilityAction("上移$title") { onMoveUp(); true })
                    if (canMoveDown) add(CustomAccessibilityAction("下移$title") { onMoveDown(); true })
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
            Icon(homeCardIcon(id), contentDescription = null, modifier = Modifier.size(21.dp))
            Text(title, modifier = Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { onVisibleChange(!visible) }) {
                Icon(if (visible) YingLiIcon.VISIBILITY.imageVector else YingLiIcon.VISIBILITY_OFF.imageVector, contentDescription = stringResource(if (visible) R.string.home_hide_card else R.string.home_show_card, title))
            }
            IconButton(
                modifier = Modifier
                    .size(48.dp)
                    .longPressDraggableHandle()
                    .clearAndSetSemantics { },
                onClick = {},
            ) {
                Icon(
                    YingLiIcon.DRAG_HANDLE.imageVector,
                    contentDescription = stringResource(R.string.home_drag_card, title),
                )
            }
    }
}

@Composable
private fun homeCardTitle(id: HomeCardId): String = stringResource(
    when (id) {
        HomeCardId.STATS -> R.string.home_stats_card
        HomeCardId.CONTINUE_WATCHING -> R.string.home_continue_watching
        HomeCardId.RECENTLY_ADDED -> R.string.home_recently_added
        HomeCardId.MY_COLLECTIONS -> R.string.home_my_collections
        HomeCardId.FREQUENT_FOLDERS -> R.string.home_frequent_folders
        HomeCardId.MAINTENANCE -> R.string.home_maintenance
    },
)

private fun homeCardIcon(id: HomeCardId): ImageVector = when (id) {
    HomeCardId.STATS -> YingLiIcon.PROCESSING.imageVector
    HomeCardId.CONTINUE_WATCHING -> YingLiIcon.PLAY.imageVector
    HomeCardId.RECENTLY_ADDED -> YingLiIcon.REPLAY.imageVector
    HomeCardId.MY_COLLECTIONS -> YingLiIcon.ORGANIZE.imageVector
    HomeCardId.FREQUENT_FOLDERS -> YingLiIcon.ORGANIZE.imageVector
    HomeCardId.MAINTENANCE -> YingLiIcon.SETTINGS.imageVector
}

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
