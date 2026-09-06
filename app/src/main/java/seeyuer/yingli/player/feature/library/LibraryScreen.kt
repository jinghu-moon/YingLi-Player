package seeyuer.yingli.player.feature.library

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import seeyuer.yingli.player.domain.thumbnail.ThumbnailLoader
import seeyuer.yingli.player.core.model.media.ThumbnailPriority
import seeyuer.yingli.player.core.model.media.ThumbnailRequest
import seeyuer.yingli.player.core.model.media.ScrollDirection
import java.util.Locale
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.component.BannerKind
import seeyuer.yingli.player.core.designsystem.component.YingLiBanner
import seeyuer.yingli.player.core.designsystem.component.YingLiButton
import seeyuer.yingli.player.core.designsystem.component.YingLiEmptyState
import seeyuer.yingli.player.core.designsystem.component.YingLiSegmentedControl
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.library.LibraryDisplayPreference
import seeyuer.yingli.player.domain.library.LibraryGroup
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibrarySortField
import seeyuer.yingli.player.domain.library.LibraryViewMode
import seeyuer.yingli.player.domain.library.TrashEntry

@Composable
fun LibraryRoute(
    viewModel: LibraryViewModel,
    isWide: Boolean,
    onMediaSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    thumbnailRepository: ThumbnailLoader? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pagingItems = viewModel.pagingData.collectAsLazyPagingItems()
    LibraryScreen(
        state = state,
        pagingItems = pagingItems,
        isWide = isWide,
        onKeywordChange = viewModel::setKeyword,
        onGroupChange = viewModel::setGroup,
        onViewModeChange = viewModel::setViewMode,
        onThumbnailScaleChange = viewModel::setThumbnailScale,
        onToggleFilter = viewModel::toggleFilterPanel,
        onSort = viewModel::setSort,
        onResolutionFilter = viewModel::applyResolutionFilter,
        onDurationFilter = viewModel::applyDurationFilter,
        onResetFilter = viewModel::resetFilter,
        onToggleSelection = viewModel::toggleSelection,
        onClearSelection = viewModel::clearSelection,
        onTrashSelected = viewModel::trashSelected,
        onToggleTrash = viewModel::toggleTrash,
        onRestore = viewModel::restore,
        onPurge = viewModel::purge,
        onMediaSelected = onMediaSelected,
        thumbnailRepository = thumbnailRepository,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    pagingItems: LazyPagingItems<LibraryMedia>,
    isWide: Boolean,
    onKeywordChange: (String) -> Unit,
    onGroupChange: (LibraryGroup) -> Unit,
    onViewModeChange: (LibraryViewMode) -> Unit,
    onThumbnailScaleChange: (Float) -> Unit,
    onToggleFilter: () -> Unit,
    onSort: (LibrarySortField) -> Unit,
    onResolutionFilter: (Int?) -> Unit,
    onDurationFilter: (Long?) -> Unit,
    onResetFilter: () -> Unit,
    onToggleSelection: (LibraryMedia) -> Unit,
    onClearSelection: () -> Unit,
    onTrashSelected: () -> Unit,
    onToggleTrash: () -> Unit,
    onRestore: (TrashEntry) -> Unit,
    onPurge: (TrashEntry) -> Unit,
    onMediaSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    thumbnailRepository: ThumbnailLoader? = null,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf<TrashEntry?>(null) }
    val content: @Composable (Modifier) -> Unit = { contentModifier ->
        LibraryContent(
            state = state,
            pagingItems = pagingItems,
            onKeywordChange = onKeywordChange,
            onGroupChange = onGroupChange,
            onViewModeChange = onViewModeChange,
            onThumbnailScaleChange = onThumbnailScaleChange,
            onToggleFilter = onToggleFilter,
            onToggleSelection = onToggleSelection,
            onMediaSelected = onMediaSelected,
            onClearSelection = onClearSelection,
            onRequestDelete = { confirmDelete = true },
            onToggleTrash = onToggleTrash,
            onRestore = onRestore,
            onRequestPurge = { confirmPurge = it },
            thumbnailRepository = thumbnailRepository,
            modifier = contentModifier,
        )
    }
    if (isWide && state.filterPanelOpen) {
        Row(modifier.fillMaxSize()) {
            content(Modifier.weight(1f))
            FilterPanel(
                state,
                onViewModeChange,
                onThumbnailScaleChange,
                onSort,
                onResolutionFilter,
                onDurationFilter,
                onResetFilter,
                onToggleTrash,
                onToggleFilter,
                Modifier.width(320.dp).fillMaxHeight(),
            )
        }
    } else {
        content(modifier)
        if (state.filterPanelOpen) {
            ModalBottomSheet(onDismissRequest = onToggleFilter) {
                FilterPanel(
                    state,
                    onViewModeChange,
                    onThumbnailScaleChange,
                    onSort,
                    onResolutionFilter,
                    onDurationFilter,
                    onResetFilter,
                    onToggleTrash,
                    onToggleFilter,
                    Modifier.fillMaxWidth(),
                )
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.library_delete_confirm_title)) },
            text = { Text(stringResource(R.string.library_delete_confirm_message, state.selectedIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onTrashSelected()
                }) { Text(stringResource(R.string.library_move_to_trash), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.library_cancel)) } },
        )
    }
    confirmPurge?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmPurge = null },
            title = { Text(stringResource(R.string.library_purge_confirm_title)) },
            text = { Text(stringResource(R.string.library_purge_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmPurge = null
                    onPurge(entry)
                }) { Text(stringResource(R.string.library_purge), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmPurge = null }) { Text(stringResource(R.string.library_cancel)) } },
        )
    }
}

@Composable
private fun LibraryContent(
    state: LibraryUiState,
    pagingItems: LazyPagingItems<LibraryMedia>,
    onKeywordChange: (String) -> Unit,
    onGroupChange: (LibraryGroup) -> Unit,
    onViewModeChange: (LibraryViewMode) -> Unit,
    onThumbnailScaleChange: (Float) -> Unit,
    onToggleFilter: () -> Unit,
    onToggleSelection: (LibraryMedia) -> Unit,
    onMediaSelected: (String) -> Unit,
    onClearSelection: () -> Unit,
    onRequestDelete: () -> Unit,
    onToggleTrash: () -> Unit,
    onRestore: (TrashEntry) -> Unit,
    onRequestPurge: (TrashEntry) -> Unit,
    thumbnailRepository: ThumbnailLoader?,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.keyword,
            onValueChange = onKeywordChange,
            label = { Text(stringResource(R.string.library_search)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = YingLiTheme.components.pagePadding),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = YingLiTheme.components.pagePadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryGroup.entries.forEach { group ->
                FilterChip(
                    selected = group == state.group,
                    onClick = { onGroupChange(group) },
                    label = { Text(group.label()) },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = YingLiTheme.components.pagePadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(
                    R.string.library_result_summary,
                    state.totalCount,
                    state.sort.field.label(),
                ),
                color = YingLiTheme.colors.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }
        state.lastBatchResult?.takeIf { it.failed > 0 }?.let { result ->
            YingLiBanner(
                stringResource(R.string.library_batch_partial, result.succeeded, result.failed),
                BannerKind.WARNING,
            )
        }
        if (state.selectedIds.isNotEmpty()) {
            SelectionToolbar(state.selectedIds.size, onClearSelection, onRequestDelete)
        }
        when {
            state.trashOpen -> TrashPanel(state.trashEntries, onToggleTrash, onRestore, onRequestPurge, Modifier.weight(1f))
            pagingItems.loadState.refresh is LoadState.Loading && pagingItems.itemCount == 0 ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            pagingItems.loadState.refresh is LoadState.Error && pagingItems.itemCount == 0 ->
                PagingErrorState(pagingItems::retry, Modifier.fillMaxSize())
            pagingItems.itemCount == 0 -> YingLiEmptyState(
                stringResource(R.string.library_empty_result_title),
                stringResource(R.string.library_empty_result_message),
                Modifier.fillMaxSize(),
            )
            else -> MediaLayout(state, pagingItems, onToggleSelection, onMediaSelected, thumbnailRepository, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PagingErrorState(onRetry: () -> Unit, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.library_error_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.library_error_message), color = YingLiTheme.colors.textSecondary)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.library_load_more_retry)) }
    }
}

@Composable
private fun TrashPanel(
    entries: List<TrashEntry>,
    onClose: () -> Unit,
    onRestore: (TrashEntry) -> Unit,
    onPurge: (TrashEntry) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.library_trash), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            YingLiButton(stringResource(R.string.action_back), onClose)
        }
        if (entries.isEmpty()) {
            YingLiEmptyState(
                stringResource(R.string.library_trash_empty),
                stringResource(R.string.library_trash_empty_message),
                Modifier.weight(1f),
            )
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp)) {
                items(entries, key = { it.mediaId.value }) { entry ->
                    Surface(color = YingLiTheme.colors.surfaceComponent) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(entry.mediaId.value, modifier = Modifier.weight(1f))
                            YingLiButton(stringResource(R.string.library_restore), { onRestore(entry) })
                            Button(
                                onClick = { onPurge(entry) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            ) { Text(stringResource(R.string.library_purge)) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SelectionToolbar(count: Int, onClear: () -> Unit, onDelete: () -> Unit) {
    Surface(color = YingLiTheme.colors.selectionStructural, contentColor = YingLiTheme.colors.selectionOnStructural) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.library_selected_count, count), modifier = Modifier.weight(1f))
            TextButton(onClick = onClear) { Text(stringResource(R.string.library_cancel)) }
            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.library_delete)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaLayout(
    state: LibraryUiState,
    pagingItems: LazyPagingItems<LibraryMedia>,
    onToggleSelection: (LibraryMedia) -> Unit,
    onMediaSelected: (String) -> Unit,
    thumbnailRepository: ThumbnailLoader?,
    modifier: Modifier,
) {
    val select: (LibraryMedia) -> Unit = { item ->
        if (state.selectedIds.isNotEmpty()) onToggleSelection(item) else onMediaSelected(item.id.value)
    }
    val onLongClick: (LibraryMedia) -> Unit = onToggleSelection
    when (state.preference.viewMode) {
        LibraryViewMode.GRID -> {
            val gridState = rememberLazyGridState(
                cacheWindow = LazyLayoutCacheWindow(ahead = 240.dp, behind = 120.dp),
            )
            ThumbnailPrefetchEffect(
                pagingItems = pagingItems,
                thumbnailRepository = thumbnailRepository,
                visibleRange = { gridState.layoutInfo.visibleItemsInfo.map { it.index }.toIntRange() },
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive((144.dp * state.preference.thumbnailScale)),
                state = gridState,
                modifier = modifier.testTag(LibraryTestTags.GRID),
                contentPadding = PaddingValues(12.dp),
            ) {
                items(
                    count = pagingItems.itemCount,
                    key = pagingItems.itemKey { it.id.value },
                    contentType = pagingItems.itemContentType { "media" },
                ) { index ->
                    pagingItems[index]?.let { item ->
                        MediaCard(item, item.id in state.selectedIds, VIDEO_ASPECT_RATIO, select, { onLongClick(item) }, thumbnailRepository)
                    }
                }
                if (pagingItems.loadState.append is LoadState.Loading || pagingItems.loadState.append is LoadState.Error) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PagingFooter(pagingItems.loadState.append, pagingItems::retry)
                    }
                }
            }
        }
        LibraryViewMode.LIST -> {
            val listState = rememberLazyListState()
            ThumbnailPrefetchEffect(
                pagingItems = pagingItems,
                thumbnailRepository = thumbnailRepository,
                visibleRange = { listState.layoutInfo.visibleItemsInfo.map { it.index }.toIntRange() },
            )
            LazyColumn(
                state = listState,
                modifier = modifier.testTag(LibraryTestTags.LIST),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(
                    count = pagingItems.itemCount,
                    key = pagingItems.itemKey { it.id.value },
                    contentType = pagingItems.itemContentType { "media" },
                ) { index ->
                    pagingItems[index]?.let { item ->
                        MediaListRow(item, item.id in state.selectedIds, select, { onLongClick(item) }, thumbnailRepository)
                    }
                }
                if (pagingItems.loadState.append is LoadState.Loading || pagingItems.loadState.append is LoadState.Error) {
                    item { PagingFooter(pagingItems.loadState.append, pagingItems::retry) }
                }
            }
        }
    }
}

@Composable
private fun ThumbnailPrefetchEffect(
    pagingItems: LazyPagingItems<LibraryMedia>,
    thumbnailRepository: ThumbnailLoader?,
    visibleRange: () -> IntRange,
) {
    if (thumbnailRepository == null) return
    LaunchedEffect(pagingItems, thumbnailRepository) {
        var previousFirst = -1
        var generation = 0L
        var direction: ScrollDirection? = null
        try {
            snapshotFlow(visibleRange)
                .distinctUntilChanged()
                .collect { range ->
                    if (range.isEmpty()) return@collect
                    val nextDirection = when {
                        previousFirst < 0 || range.first >= previousFirst -> ScrollDirection.FORWARD
                        else -> ScrollDirection.BACKWARD
                    }
                    if (nextDirection != direction) {
                        if (direction != null) thumbnailRepository.cancelPrefetch(generation)
                        direction = nextDirection
                        generation++
                    }
                    val indices = if (nextDirection == ScrollDirection.FORWARD) {
                        (range.last + 1)..(range.last + PREFETCH_ITEM_COUNT)
                            .coerceAtMost(pagingItems.itemCount - 1)
                    } else {
                        (range.first - PREFETCH_ITEM_COUNT).coerceAtLeast(0)..(range.first - 1)
                    }
                    val requests = indices.mapNotNull { index ->
                        // peek() intentionally avoids turning thumbnail prefetch into
                        // a Paging access hint. The viewport itself owns pagination.
                        pagingItems.peek(index)?.thumbnailRequest(ThumbnailPriority.BACKGROUND)
                    }
                    thumbnailRepository.prefetch(requests, nextDirection, generation)
                    previousFirst = range.first
                }
        } finally {
            if (direction != null) thumbnailRepository.cancelPrefetch(generation)
        }
    }
}

private fun List<Int>.toIntRange(): IntRange = if (isEmpty()) IntRange.EMPTY else minOrNull()!!..maxOrNull()!!

private const val PREFETCH_ITEM_COUNT = 24
@Composable
private fun PagingFooter(loadState: LoadState, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loadState is LoadState.Loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            loadState is LoadState.Error -> TextButton(onClick = onRetry) {
                Text(stringResource(R.string.library_load_more_retry))
            }
        }
    }
}

private fun LibraryMedia.thumbnailRequest(priority: ThumbnailPriority) = ThumbnailRequest(
    mediaItemId = id,
    locationId = locationId,
    uri = uri,
    widthPixels = 320,
    heightPixels = 180,
    priority = priority,
    modifiedEpochMillis = modifiedEpochMillis,
    sizeBytes = sizeBytes,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCard(
    item: LibraryMedia,
    selected: Boolean,
    ratio: Float,
    onClick: (LibraryMedia) -> Unit,
    onLongClick: (seeyuer.yingli.player.core.model.media.MediaItemId) -> Unit,
    thumbnailRepository: ThumbnailLoader?,
) {
    val thumbnail = item.thumbnailRequest(ThumbnailPriority.VISIBLE)
    Column(
        modifier = Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) YingLiTheme.colors.actionPrimarySoft else Color.Transparent)
            .combinedClickable(onClick = { onClick(item) }, onLongClick = { onLongClick(item.id) })
            .semantics {
                role = Role.Button
                this.selected = selected
                contentDescription = item.accessibilityDescription()
            },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(8.dp))) {
            YingLiThumbnail(thumbnail, thumbnailRepository)
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                color = YingLiTheme.player.edgeScrim,
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    formatDuration(item.durationMillis),
                    color = YingLiTheme.player.controlPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            if (item.watchedFraction > 0f) {
                LinearProgressIndicator(
                    progress = { item.watchedFraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter),
                    color = YingLiTheme.player.controlPrimary,
                    trackColor = YingLiTheme.player.track,
                )
            }
        }
        Text(
            item.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
        )
        Text(
            item.cardMetadataText(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = YingLiTheme.colors.textSecondary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaListRow(
    item: LibraryMedia,
    selected: Boolean,
    onClick: (LibraryMedia) -> Unit,
    onLongClick: (seeyuer.yingli.player.core.model.media.MediaItemId) -> Unit,
    thumbnailRepository: ThumbnailLoader?,
) {
    val thumbnail = item.thumbnailRequest(ThumbnailPriority.VISIBLE)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LIST_THUMBNAIL_HEIGHT)
            .background(if (selected) YingLiTheme.colors.selectionStructural else Color.Transparent)
            .combinedClickable(onClick = { onClick(item) }, onLongClick = { onLongClick(item.id) })
            .semantics {
                role = Role.Button
                this.selected = selected
                contentDescription = item.accessibilityDescription()
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(LIST_THUMBNAIL_WIDTH)
                .height(LIST_THUMBNAIL_HEIGHT)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            YingLiThumbnail(thumbnail, thumbnailRepository)
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                color = YingLiTheme.player.edgeScrim,
                shape = RoundedCornerShape(3.dp),
            ) {
                Text(
                    formatDuration(item.durationMillis),
                    color = YingLiTheme.player.controlPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp),
        ) {
            Text(
                item.fileName,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall.copy(lineHeight = 18.sp),
            )
            Text(
                item.displayPath(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = YingLiTheme.colors.textSecondary,
                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 14.sp),
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MetadataCapsule(formatFileSize(item.sizeBytes))
                MetadataCapsule(item.resolutionLabel())
            }
        }
    }
}

@Composable
private fun MetadataCapsule(text: String) {
    Surface(
        color = YingLiTheme.colors.surfaceMuted,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text,
            color = YingLiTheme.colors.textSecondary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun FilterPanel(
    state: LibraryUiState,
    onViewModeChange: (LibraryViewMode) -> Unit,
    onThumbnailScaleChange: (Float) -> Unit,
    onSort: (LibrarySortField) -> Unit,
    onResolutionFilter: (Int?) -> Unit,
    onDurationFilter: (Long?) -> Unit,
    onReset: () -> Unit,
    onToggleTrash: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    Surface(modifier, color = YingLiTheme.colors.surface) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.library_view_settings), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.library_layout), style = MaterialTheme.typography.titleMedium)
            YingLiSegmentedControl(
                options = listOf(
                    stringResource(R.string.library_list),
                    stringResource(R.string.library_grid),
                ),
                selectedIndex = listOf(
                    LibraryViewMode.LIST,
                    LibraryViewMode.GRID,
                ).indexOf(state.preference.viewMode),
                onSelected = { index ->
                    onViewModeChange(
                        listOf(
                            LibraryViewMode.LIST,
                            LibraryViewMode.GRID,
                        )[index],
                    )
                },
            )
            Text(stringResource(R.string.library_thumbnail_size), style = MaterialTheme.typography.titleMedium)
            Slider(
                value = state.preference.thumbnailScale,
                onValueChange = onThumbnailScaleChange,
                valueRange = LibraryDisplayPreference.MIN_SCALE..LibraryDisplayPreference.MAX_SCALE,
            )
            Text(stringResource(R.string.library_sort))
            LibrarySortField.entries.forEach { field ->
                FilterChip(
                    selected = state.sort.field == field,
                    onClick = { onSort(field) },
                    label = { Text(field.label()) },
                )
            }
            Text(stringResource(R.string.library_resolution))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(state.filter.minimumWidth == null, { onResolutionFilter(null) }, { Text(stringResource(R.string.library_all)) })
                FilterChip(state.filter.minimumWidth == 1_920, { onResolutionFilter(1_920) }, { Text("1080p+") })
                FilterChip(state.filter.minimumWidth == 3_840, { onResolutionFilter(3_840) }, { Text("4K+") })
            }
            Text(stringResource(R.string.library_duration))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(state.filter.duration.maximumMillis == null, { onDurationFilter(null) }, { Text(stringResource(R.string.library_all)) })
                FilterChip(state.filter.duration.maximumMillis == 300_000L, { onDurationFilter(300_000L) }, { Text("≤ 5 min") })
                FilterChip(state.filter.duration.maximumMillis == 1_800_000L, { onDurationFilter(1_800_000L) }, { Text("≤ 30 min") })
            }
            Text(stringResource(R.string.library_result_count, state.totalCount))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YingLiButton(stringResource(R.string.library_reset), onReset)
                YingLiButton(stringResource(R.string.library_trash), {
                    onToggleTrash()
                    onClose()
                })
                YingLiButton(stringResource(R.string.library_view_results, state.totalCount), onClose, enabled = state.totalCount > 0)
            }
        }
    }
}

@Composable
private fun LibraryGroup.label(): String = stringResource(when (this) {
    LibraryGroup.ALL -> R.string.library_all
    LibraryGroup.FOLDER -> R.string.library_folder
    LibraryGroup.RECENT -> R.string.library_recent
    LibraryGroup.UNWATCHED -> R.string.library_unwatched
})

@Composable
private fun LibrarySortField.label(): String = stringResource(when (this) {
    LibrarySortField.NAME -> R.string.library_sort_name
    LibrarySortField.RECENTLY_ADDED -> R.string.library_sort_recent
    LibrarySortField.DURATION -> R.string.library_sort_duration
    LibrarySortField.PLAY_COUNT -> R.string.library_sort_play_count
})

private fun LibraryMedia.cardMetadataText(): String = "${extension.uppercase()} · ${width ?: "-"}×${height ?: "-"}"
private fun LibraryMedia.accessibilityDescription(): String =
    "$title, ${formatDuration(durationMillis)}, ${width ?: 0} × ${height ?: 0}, 已观看 ${(watchedFraction * 100).toInt()}%"

private fun LibraryMedia.displayPath(): String {
    val parsed = Uri.parse(uri.value)
    val path = when (parsed.scheme) {
        "file" -> parsed.path?.substringBeforeLast('/', missingDelimiterValue = "")
        "content" -> Uri.decode(uri.value)
            .substringAfterLast("/document/", missingDelimiterValue = "")
            .substringBeforeLast('/', missingDelimiterValue = "")
            .toStoragePath()
        else -> null
    }
    return path?.takeIf(String::isNotBlank) ?: folderAlias
}

private fun String.toStoragePath(): String? = when {
    isBlank() -> null
    startsWith("primary:") -> "/storage/emulated/0/${substringAfter(':')}"
    ':' in this -> "/storage/${substringBefore(':')}/${substringAfter(':')}"
    else -> this
}

private fun LibraryMedia.resolutionLabel(): String =
    listOfNotNull(width, height).minOrNull()?.let { "${it}P" } ?: "--P"

private fun formatFileSize(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0)
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = safeBytes.toDouble()
    var unitIndex = 0
    while (value >= 1_024 && unitIndex < units.lastIndex) {
        value /= 1_024
        unitIndex++
    }
    val pattern = if (unitIndex == 0 || value >= 10 || value % 1.0 == 0.0) "%.0f %s" else "%.1f %s"
    return String.format(Locale.US, pattern, value, units[unitIndex])
}

private fun formatDuration(value: Long?): String {
    value ?: return "--:--"
    val seconds = value / 1_000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

object LibraryTestTags {
    const val GRID = "library.grid"
    const val LIST = "library.list"
}

private const val VIDEO_ASPECT_RATIO = 16f / 9f
private val LIST_THUMBNAIL_WIDTH = 140.dp
private val LIST_THUMBNAIL_HEIGHT = 90.dp
