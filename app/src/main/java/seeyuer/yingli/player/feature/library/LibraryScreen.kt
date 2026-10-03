package seeyuer.yingli.player.feature.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import seeyuer.yingli.player.core.designsystem.component.YingLiDropdownMenu
import seeyuer.yingli.player.core.designsystem.component.YingLiDropdownMenuItem
import seeyuer.yingli.player.core.designsystem.component.YingLiSegmentedControl
import seeyuer.yingli.player.core.designsystem.component.YingLiSegmentedControlSize
import seeyuer.yingli.player.core.designsystem.component.YingLiStepper
import seeyuer.yingli.player.core.designsystem.component.YingLiIconButton
import seeyuer.yingli.player.core.designsystem.component.YingLiTopBar
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.icon.imageVector
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.library.LibraryDisplayPreference
import seeyuer.yingli.player.domain.library.BreadcrumbMode
import seeyuer.yingli.player.domain.library.LibraryGroup
import seeyuer.yingli.player.domain.library.LibraryBrowseMode
import seeyuer.yingli.player.domain.library.LibraryPathSegment
import seeyuer.yingli.player.domain.library.LibraryDisplayFields
import seeyuer.yingli.player.domain.library.LibraryFolder
import seeyuer.yingli.player.domain.library.FolderField
import seeyuer.yingli.player.domain.library.VideoField
import seeyuer.yingli.player.domain.library.SortDirection
import seeyuer.yingli.player.domain.library.SortSpec
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibrarySortField
import seeyuer.yingli.player.domain.library.LibraryViewMode
import seeyuer.yingli.player.domain.library.TrashEntry
import seeyuer.yingli.player.domain.playback.PlaybackQueueSource

@Composable
fun LibraryRoute(
    viewModel: LibraryViewModel,
    isWide: Boolean,
    onMediaSelected: (String, PlaybackQueueSource?) -> Unit,
    onAddDirectory: () -> Unit,
    onRescan: () -> Unit,
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
        onMediaSelected = { mediaId ->
            val queueSource = when (state.browseMode) {
                LibraryBrowseMode.ALL_VIDEOS -> PlaybackQueueSource.allVideos()
                LibraryBrowseMode.FOLDER -> state.currentPath.lastOrNull()?.path
                    ?.takeIf(String::isNotBlank)
                    ?.let(PlaybackQueueSource::folderTree)
            }
            onMediaSelected(mediaId, queueSource)
        },
        onBrowseModeChange = viewModel::setBrowseMode,
        onEnterFolder = viewModel::enterFolder,
        onNavigatePath = viewModel::navigateToPath,
        onNavigateUp = { viewModel.navigateUp() },
        onToggleSearch = viewModel::toggleSearch,
        onToggleMore = viewModel::toggleMoreMenu,
        onCloseMore = viewModel::closeMoreMenu,
        onApplyQuickSettings = viewModel::applyQuickSettings,
        onAddDirectory = onAddDirectory,
        onRescan = onRescan,
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
    onBrowseModeChange: (LibraryBrowseMode) -> Unit = {},
    onEnterFolder: (LibraryPathSegment) -> Unit = {},
    onNavigatePath: (Int) -> Unit = {},
    onNavigateUp: () -> Unit = {},
    onToggleSearch: () -> Unit = {},
    onToggleMore: () -> Unit = {},
    onCloseMore: () -> Unit = {},
    onApplyQuickSettings: (LibraryBrowseMode, LibraryViewMode, SortSpec, LibraryDisplayFields, BreadcrumbMode, Int, Int) -> Unit = { _, _, _, _, _, _, _ -> },
    onAddDirectory: () -> Unit = {},
    onRescan: () -> Unit = {},
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf<TrashEntry?>(null) }
    // Nested library navigation is not part of the global route stack; intercept
    // system back (gesture / predictive) so it does not finish the activity.
    BackHandler(
        enabled = state.selectionMode || state.searchOpen || state.currentPath.isNotEmpty(),
        onBack = {
            when {
                state.selectionMode -> onClearSelection()
                state.searchOpen -> onToggleSearch()
                else -> onNavigateUp()
            }
        },
    )
    Column(modifier.fillMaxSize()) {
        LibraryTopBar(
            state = state,
            onNavigateUp = onNavigateUp,
            onToggleSearch = onToggleSearch,
            onToggleMore = onToggleMore,
            onCloseMore = onCloseMore,
            onToggleFilter = onToggleFilter,
            onOpenTrash = onToggleTrash,
            onAddDirectory = onAddDirectory,
            onRescan = onRescan,
            onClearSelection = onClearSelection,
        )
        if (state.searchOpen) {
            OutlinedTextField(
                value = state.keyword,
                onValueChange = onKeywordChange,
                placeholder = { Text(stringResource(R.string.library_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        AnimatedVisibility(
            visible = state.browseMode == LibraryBrowseMode.FOLDER && state.currentPath.isNotEmpty() && state.keyword.isBlank(),
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(tween(FolderNavDurationMs, easing = FastOutSlowInEasing)) +
                slideInHorizontally { it / 8 },
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(tween(FolderNavDurationMs, easing = FastOutSlowInEasing)) +
                slideOutHorizontally { it / 8 },
        ) {
            BreadcrumbBar(state.currentPath, state.preference.breadcrumbMode, onNavigatePath)
        }
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
            onEnterFolder = onEnterFolder,
            onClearSelection = onClearSelection,
            onRequestDelete = { confirmDelete = true },
            onToggleTrash = onToggleTrash,
            onRestore = onRestore,
            onRequestPurge = { confirmPurge = it },
            thumbnailRepository = thumbnailRepository,
            modifier = Modifier.weight(1f),
        )
        if (state.filterPanelOpen) {
            ModalBottomSheet(
                onDismissRequest = onToggleFilter,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                sheetGesturesEnabled = false,
            ) {
                QuickSettingsPanel(state, onApplyQuickSettings, onToggleFilter)
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
private fun LibraryTopBar(
    state: LibraryUiState,
    onNavigateUp: () -> Unit,
    onToggleSearch: () -> Unit,
    onToggleMore: () -> Unit,
    onCloseMore: () -> Unit,
    onToggleFilter: () -> Unit,
    onOpenTrash: () -> Unit,
    onAddDirectory: () -> Unit,
    onRescan: () -> Unit,
    onClearSelection: () -> Unit,
) {
    val rootTitle = stringResource(R.string.nav_library)
    val selectedTitle = stringResource(R.string.library_selected_count, state.selectedIds.size)
    val headerState = LibraryHeaderState(
        selectionMode = state.selectionMode,
        pathDepth = state.currentPath.size,
        pathId = state.currentPath.lastOrNull()?.path.orEmpty(),
        title = when {
            state.selectionMode -> selectedTitle
            state.currentPath.isEmpty() -> rootTitle
            else -> state.currentPath.last().name
        },
    )
    YingLiTopBar(
        navigationIcon = {
            AnimatedContent(
                targetState = headerState,
                transitionSpec = { folderSharedAxisX(forward = isFolderForward(initialState, targetState)) },
                contentKey = { header ->
                    when {
                        header.selectionMode -> "selection"
                        header.pathDepth > 0 -> "folder"
                        else -> "root"
                    }
                },
                label = "libraryNavIcon",
            ) { header ->
                when {
                    header.selectionMode -> YingLiIconButton(YingLiIcon.BACK, stringResource(R.string.library_cancel), onClearSelection)
                    header.pathDepth > 0 -> YingLiIconButton(YingLiIcon.BACK, stringResource(R.string.action_back), onNavigateUp)
                }
            }
        },
        title = {
            AnimatedContent(
                targetState = headerState,
                transitionSpec = { folderSharedAxisX(forward = isFolderForward(initialState, targetState)) },
                contentKey = { header -> header.selectionMode to header.title },
                label = "libraryTitle",
                modifier = Modifier.clipToBounds(),
            ) { header ->
                Text(
                    text = header.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = if (header.selectionMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineLarge,
                    fontWeight = if (header.selectionMode) null else FontWeight.Bold,
                )
            }
        },
        actions = {
            YingLiIconButton(YingLiIcon.SEARCH, stringResource(R.string.library_search), onToggleSearch)
            Box {
                YingLiIconButton(YingLiIcon.OVERFLOW, stringResource(R.string.home_more), onToggleMore)
                YingLiDropdownMenu(expanded = state.moreMenuOpen, onDismissRequest = onCloseMore) {
                    YingLiDropdownMenuItem(text = "添加媒体目录", onClick = onAddDirectory)
                    YingLiDropdownMenuItem(text = "重新扫描", onClick = onRescan)
                    YingLiDropdownMenuItem(text = stringResource(R.string.library_view_settings), onClick = onToggleFilter)
                    YingLiDropdownMenuItem(text = stringResource(R.string.library_trash), onClick = onOpenTrash)
                }
            }
        },
    )
}

@Immutable
private data class LibraryHeaderState(
    val selectionMode: Boolean,
    val pathDepth: Int,
    val pathId: String,
    val title: String,
)

private const val FolderNavDurationMs = 250

private fun isFolderForward(initial: LibraryHeaderState, target: LibraryHeaderState): Boolean =
    when {
        initial.selectionMode != target.selectionMode -> target.pathDepth >= initial.pathDepth
        else -> target.pathDepth > initial.pathDepth ||
            (target.pathDepth == initial.pathDepth && target.pathId != initial.pathId && target.pathDepth > 0)
    }

private fun folderSharedAxisX(forward: Boolean): ContentTransform {
    val sign = if (forward) 1 else -1
    val easing = FastOutSlowInEasing
    return ContentTransform(
        targetContentEnter = slideInHorizontally(animationSpec = tween(FolderNavDurationMs, easing = easing)) { it / 4 * sign } +
            fadeIn(tween(FolderNavDurationMs, easing = easing)),
        initialContentExit = slideOutHorizontally(animationSpec = tween(FolderNavDurationMs, easing = easing)) { -it / 4 * sign } +
            fadeOut(tween(FolderNavDurationMs, easing = easing)),
        sizeTransform = SizeTransform(clip = true),
    )
}

@Composable
private fun BreadcrumbBar(
    path: List<LibraryPathSegment>,
    mode: BreadcrumbMode,
    onNavigatePath: (Int) -> Unit,
) {
    Surface(color = YingLiTheme.colors.surface) {
        when (mode) {
            BreadcrumbMode.COLLAPSED -> CollapsedBreadcrumb(path, onNavigatePath)
            BreadcrumbMode.SCROLL -> ScrollableBreadcrumb(path, onNavigatePath)
        }
    }
}

@Composable
private fun ScrollableBreadcrumb(path: List<LibraryPathSegment>, onNavigatePath: (Int) -> Unit) {
    val scrollState = rememberScrollState()
    LaunchedEffect(path.map(LibraryPathSegment::path)) {
        snapshotFlow { scrollState.maxValue }
            .distinctUntilChanged()
            .collect { scrollState.scrollTo(it) }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scrollState).padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = { onNavigatePath(-1) }) { Text("视频", maxLines = 1) }
        path.forEachIndexed { index, segment ->
            Text("›", color = YingLiTheme.colors.textSecondary)
            TextButton(onClick = { onNavigatePath(index) }) {
                Text(segment.name, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CollapsedBreadcrumb(path: List<LibraryPathSegment>, onNavigatePath: (Int) -> Unit) {
    var overflowExpanded by remember(path) { mutableStateOf(false) }
    val layout = remember(path) { collapsedBreadcrumbLayout(path) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        YingLiIconButton(
            icon = YingLiIcon.HOME,
            contentDescription = "返回全部视频",
            onClick = { onNavigatePath(-1) },
        )
        if (layout.hidden.isNotEmpty()) {
            BreadcrumbSeparator()
            Box {
                YingLiIconButton(
                    icon = YingLiIcon.BREADCRUMB_OVERFLOW,
                    contentDescription = "显示隐藏的上级目录",
                    onClick = { overflowExpanded = true },
                )
                YingLiDropdownMenu(
                    expanded = overflowExpanded,
                    onDismissRequest = { overflowExpanded = false },
                ) {
                    layout.hidden.forEach { crumb ->
                        YingLiDropdownMenuItem(
                            text = crumb.value.name,
                            onClick = { onNavigatePath(crumb.index) },
                        )
                    }
                }
            }
        }
        layout.visible.forEach { crumb ->
            BreadcrumbSeparator()
            TextButton(
                onClick = { onNavigatePath(crumb.index) },
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 144.dp),
            ) {
                Text(crumb.value.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun BreadcrumbSeparator() {
    Text("›", color = YingLiTheme.colors.textSecondary)
}

internal data class CollapsedBreadcrumbLayout(
    val hidden: List<IndexedValue<LibraryPathSegment>>,
    val visible: List<IndexedValue<LibraryPathSegment>>,
)

internal fun collapsedBreadcrumbLayout(
    path: List<LibraryPathSegment>,
    visibleAncestorCount: Int = 2,
): CollapsedBreadcrumbLayout {
    require(visibleAncestorCount > 0)
    val ancestors = path.dropLast(1).withIndex().toList()
    val visibleStart = (ancestors.size - visibleAncestorCount).coerceAtLeast(0)
    return CollapsedBreadcrumbLayout(
        hidden = ancestors.take(visibleStart),
        visible = ancestors.drop(visibleStart),
    )
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
    onEnterFolder: (LibraryPathSegment) -> Unit,
    onClearSelection: () -> Unit,
    onRequestDelete: () -> Unit,
    onToggleTrash: () -> Unit,
    onRestore: (TrashEntry) -> Unit,
    onRequestPurge: (TrashEntry) -> Unit,
    thumbnailRepository: ThumbnailLoader?,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize()) {
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
        if (state.selectionMode) {
            SelectionToolbar(state.selectedIds.size, onClearSelection, onRequestDelete)
        }
        when {
            state.trashOpen -> TrashPanel(state.trashEntries, onToggleTrash, onRestore, onRequestPurge, Modifier.weight(1f))
            state.browseMode == LibraryBrowseMode.FOLDER && state.keyword.isBlank() -> {
                val folderPathKey = state.currentPath.lastOrNull()?.path.orEmpty()
                AnimatedContent(
                    targetState = folderPathKey,
                    transitionSpec = {
                        fadeIn(tween(FolderNavDurationMs, easing = FastOutSlowInEasing)) togetherWith
                            fadeOut(tween(FolderNavDurationMs, easing = FastOutSlowInEasing))
                    },
                    label = "folderContent",
                    modifier = Modifier.weight(1f),
                ) { targetFolderPathKey ->
                    // 用 AnimatedContent 传进来的目标值（而不是外面捕获的 folderPathKey）：
                    // 过渡期间旧内容要按旧 key 组合、新内容按新 key 组合；捕获外层变量会让
                    // 两帧用同一个 key，进出场内容就完全一样了。
                    key(targetFolderPathKey, state.preference.viewMode) {
                        FolderBrowseLayout(
                            state = state,
                            pagingItems = pagingItems,
                            onToggleSelection = onToggleSelection,
                            onMediaSelected = onMediaSelected,
                            onEnterFolder = onEnterFolder,
                            thumbnailRepository = thumbnailRepository,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            pagingItems.loadState.refresh is LoadState.Loading && pagingItems.itemCount == 0 ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            pagingItems.loadState.refresh is LoadState.Error && pagingItems.itemCount == 0 ->
                PagingErrorState(pagingItems::retry, Modifier.fillMaxSize())
            pagingItems.itemCount == 0 && state.folders.isEmpty() -> YingLiEmptyState(
                stringResource(R.string.library_empty_result_title),
                stringResource(R.string.library_empty_result_message),
                Modifier.fillMaxSize(),
            )
            else -> MediaLayout(state, pagingItems, onToggleSelection, onMediaSelected, thumbnailRepository, Modifier.weight(1f))
        }
    }
}

@Composable
private fun FolderBrowseLayout(
    state: LibraryUiState,
    pagingItems: LazyPagingItems<LibraryMedia>,
    onToggleSelection: (LibraryMedia) -> Unit,
    onMediaSelected: (String) -> Unit,
    onEnterFolder: (LibraryPathSegment) -> Unit,
    thumbnailRepository: ThumbnailLoader?,
    modifier: Modifier,
) {
    val folderGrid = state.preference.viewMode == LibraryViewMode.GRID
    val folderColumnCount = state.preference.folderColumns.coerceAtLeast(1)
    val videoColumnCount = state.preference.videoColumns.coerceAtLeast(1)
    val folderRowCount = if (folderGrid) {
        gridRowCount(state.folders.size, folderColumnCount)
    } else {
        state.folders.size
    }
    val folderHeaderCount = if (state.folders.isEmpty()) 0 else 1
    val hasVideoContent = pagingItems.itemCount > 0 ||
        pagingItems.loadState.refresh is LoadState.Loading ||
        pagingItems.loadState.refresh is LoadState.Error
    val videoHeaderCount = if (state.folders.isNotEmpty() && hasVideoContent) 1 else 0
    val mediaStartIndex = folderHeaderCount + folderRowCount + videoHeaderCount
    val listState = rememberLazyListState()
    val select = mediaSelectionHandler(state.selectedIds, onToggleSelection, onMediaSelected)
    ThumbnailPrefetchEffect(
        pagingItems = pagingItems,
        thumbnailRepository = thumbnailRepository,
        visibleRange = {
            listState.layoutInfo.visibleItemsInfo
                .filter { it.index >= mediaStartIndex }
                .flatMap { info ->
                    if (folderGrid) {
                        val row = info.index - mediaStartIndex
                        gridRowIndices(row, pagingItems.itemCount, videoColumnCount).toList()
                    } else {
                        listOf(info.index - mediaStartIndex)
                    }
                }
                .filter { it in 0 until pagingItems.itemCount }
                .toIntRange()
        },
    )
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.folders.isNotEmpty()) {
            item(key = "folders-header", contentType = "section-header") {
                Text("文件夹", style = MaterialTheme.typography.titleSmall)
            }
            if (folderGrid) {
                items(
                    count = folderRowCount,
                    key = { row -> "folder-row-${state.folders[row * folderColumnCount].path}" },
                    contentType = { "folder-grid-row" },
                ) { rowIndex ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        repeat(folderColumnCount) { columnIndex ->
                            val folder = state.folders.getOrNull(rowIndex * folderColumnCount + columnIndex)
                            if (folder == null) {
                                Spacer(Modifier.weight(1f))
                            } else {
                                FolderGridCard(
                                    folder = folder,
                                    onEnterFolder = onEnterFolder,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            } else {
                items(
                    items = state.folders,
                    key = { it.path },
                    contentType = { "folder-list-row" },
                ) { folder ->
                    FolderListRow(folder, onEnterFolder)
                }
            }
            if (hasVideoContent) {
                item(key = "videos-header", contentType = "section-header") {
                    Text("视频", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        when {
            pagingItems.loadState.refresh is LoadState.Loading && pagingItems.itemCount == 0 ->
                item(key = "media-refresh-loading") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            pagingItems.loadState.refresh is LoadState.Error && pagingItems.itemCount == 0 ->
                item(key = "media-refresh-error") {
                    PagingErrorState(pagingItems::retry, Modifier.fillMaxWidth())
                }
            pagingItems.itemCount == 0 && state.folders.isEmpty() ->
                item(key = "media-empty") {
                    YingLiEmptyState(
                        stringResource(R.string.library_empty_result_title),
                        stringResource(R.string.library_empty_result_message),
                        Modifier.fillMaxWidth(),
                    )
                }
            pagingItems.itemCount > 0 && folderGrid -> {
                val rowCount = gridRowCount(pagingItems.itemCount, videoColumnCount)
                items(
                    count = rowCount,
                    key = { row -> "media-row-$row" },
                    contentType = { "media-grid-row" },
                ) { rowIndex ->
                    MediaGridRow(
                        startIndex = rowIndex * videoColumnCount,
                        columnCount = videoColumnCount,
                        pagingItems = pagingItems,
                        selectedIds = state.selectedIds,
                        onClick = select,
                        onLongClick = onToggleSelection,
                        thumbnailRepository = thumbnailRepository,
                    )
                }
            }
            pagingItems.itemCount > 0 -> {
                items(
                    count = pagingItems.itemCount,
                    key = pagingItems.itemKey { it.id.value },
                    contentType = pagingItems.itemContentType { "media" },
                ) { index ->
                    pagingItems[index]?.let { item ->
                        MediaListRow(
                            item = item.toMediaListItem(),
                            selected = item.id in state.selectedIds,
                            onClick = { select(item) },
                            onLongClick = { onToggleSelection(item) },
                            thumbnailRepository = thumbnailRepository,
                            secondaryText = item.displayPath(),
                        )
                    }
                }
            }
        }
        if (pagingItems.loadState.append is LoadState.Loading || pagingItems.loadState.append is LoadState.Error) {
            item(key = "media-footer") { PagingFooter(pagingItems.loadState.append, pagingItems::retry) }
        }
    }
}

@Composable
private fun FolderListRow(folder: LibraryFolder, onEnterFolder: (LibraryPathSegment) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = YingLiTheme.colors.surfaceComponent,
        shape = RoundedCornerShape(8.dp),
        onClick = { onEnterFolder(LibraryPathSegment(folder.name, folder.path)) },
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(48.dp), shape = RoundedCornerShape(8.dp), color = YingLiTheme.colors.surfaceMuted) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(YingLiIcon.ORGANIZE.imageVector, contentDescription = null, modifier = Modifier.size(25.dp))
                }
            }
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(folder.path, maxLines = 1, overflow = TextOverflow.Ellipsis, color = YingLiTheme.colors.textSecondary, style = MaterialTheme.typography.labelSmall)
                Text("${folder.videoCount} 个视频 · ${formatFileSize(folder.sizeBytes)}", maxLines = 1, overflow = TextOverflow.Ellipsis, color = YingLiTheme.colors.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            Icon(YingLiIcon.ARROW_RIGHT.imageVector, contentDescription = null)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGridRow(
    startIndex: Int,
    columnCount: Int,
    pagingItems: LazyPagingItems<LibraryMedia>,
    selectedIds: Set<seeyuer.yingli.player.core.model.media.MediaItemId>,
    onClick: (LibraryMedia) -> Unit,
    onLongClick: (LibraryMedia) -> Unit,
    thumbnailRepository: ThumbnailLoader?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        repeat(columnCount) { columnIndex ->
            val itemIndex = startIndex + columnIndex
            val item = itemIndex.takeIf { it < pagingItems.itemCount }?.let(pagingItems::get)
            if (item == null) {
                Spacer(Modifier.weight(1f))
            } else {
                MediaCard(
                    item = item,
                    selected = item.id in selectedIds,
                    ratio = VIDEO_ASPECT_RATIO,
                    onClick = onClick,
                    onLongClick = { onLongClick(item) },
                    thumbnailRepository = thumbnailRepository,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

internal fun gridRowCount(itemCount: Int, columnCount: Int): Int {
    require(columnCount > 0)
    return (itemCount + columnCount - 1) / columnCount
}

internal fun gridRowIndices(rowIndex: Int, itemCount: Int, columnCount: Int): IntRange {
    require(rowIndex >= 0)
    require(columnCount > 0)
    val start = rowIndex * columnCount
    if (start >= itemCount) return IntRange.EMPTY
    return start until minOf(start + columnCount, itemCount)
}

@Composable
private fun FolderGridCard(
    folder: LibraryFolder,
    onEnterFolder: (LibraryPathSegment) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent,
        onClick = { onEnterFolder(LibraryPathSegment(folder.name, folder.path)) },
    ) {
        Column {
            Surface(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                color = YingLiTheme.colors.surfaceMuted,
                shape = RoundedCornerShape(18.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        YingLiIcon.ORGANIZE.imageVector,
                        contentDescription = null,
                        tint = YingLiTheme.colors.actionPrimary,
                        modifier = Modifier.fillMaxSize(0.7f),
                    )
                }
            }
            Text(
                folder.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
            )
            Text(
                "${folder.videoCount} 个视频 · ${formatFileSize(folder.sizeBytes)}",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = YingLiTheme.colors.textSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp),
            )
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
    val select = mediaSelectionHandler(state.selectedIds, onToggleSelection, onMediaSelected)
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
                columns = GridCells.Fixed(state.preference.videoColumns.coerceAtLeast(1)),
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
                        MediaListRow(
                            item = item.toMediaListItem(),
                            selected = item.id in state.selectedIds,
                            onClick = { select(item) },
                            onLongClick = { onLongClick(item) },
                            thumbnailRepository = thumbnailRepository,
                            secondaryText = item.displayPath(),
                        )
                    }
                }
                if (pagingItems.loadState.append is LoadState.Loading || pagingItems.loadState.append is LoadState.Error) {
                    item { PagingFooter(pagingItems.loadState.append, pagingItems::retry) }
                }
            }
        }
    }
}

private fun mediaSelectionHandler(
    selectedIds: Set<seeyuer.yingli.player.core.model.media.MediaItemId>,
    onToggleSelection: (LibraryMedia) -> Unit,
    onMediaSelected: (String) -> Unit,
): (LibraryMedia) -> Unit = { item ->
    if (selectedIds.isNotEmpty()) onToggleSelection(item) else onMediaSelected(item.id.value)
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
    modifier: Modifier = Modifier,
) {
    val thumbnail = item.thumbnailRequest(ThumbnailPriority.VISIBLE)
    Column(
        modifier = modifier
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickSettingsPanel(
    state: LibraryUiState,
    onApply: (LibraryBrowseMode, LibraryViewMode, SortSpec, LibraryDisplayFields, BreadcrumbMode, Int, Int) -> Unit,
    onCancel: () -> Unit,
) {
    var browseMode by remember(state.filterPanelOpen) { mutableStateOf(state.browseMode) }
    var viewMode by remember(state.filterPanelOpen) { mutableStateOf(state.preference.viewMode) }
    var sort by remember(state.filterPanelOpen) { mutableStateOf(state.sort) }
    var fields by remember(state.filterPanelOpen) { mutableStateOf(state.displayFields) }
    var breadcrumbMode by remember(state.filterPanelOpen) { mutableStateOf(state.preference.breadcrumbMode) }
    // 列数是 Int：用 mutableIntStateOf 而不是 mutableStateOf(Int)，避免每次读写都走装箱
    // （lint 的 AutoboxingStateCreation）。读写点都在本 Composable 作用域内，by 委托照旧。
    var folderColumns by remember(state.filterPanelOpen) { mutableIntStateOf(state.preference.folderColumns) }
    var videoColumns by remember(state.filterPanelOpen) { mutableIntStateOf(state.preference.videoColumns) }
    Column(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.7f),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("快捷设置", style = MaterialTheme.typography.titleLarge)
            Text("浏览方式", style = MaterialTheme.typography.titleSmall)
            YingLiSegmentedControl(
                options = listOf("文件夹", "全部视频"),
                selectedIndex = if (browseMode == LibraryBrowseMode.FOLDER) 0 else 1,
                onSelected = { browseMode = if (it == 0) LibraryBrowseMode.FOLDER else LibraryBrowseMode.ALL_VIDEOS },
            )
            Text("面包屑样式", style = MaterialTheme.typography.titleSmall)
            YingLiSegmentedControl(
                options = listOf("折叠菜单", "横向滚动"),
                selectedIndex = if (breadcrumbMode == BreadcrumbMode.COLLAPSED) 0 else 1,
                onSelected = { breadcrumbMode = if (it == 0) BreadcrumbMode.COLLAPSED else BreadcrumbMode.SCROLL },
            )
            if (viewMode == LibraryViewMode.GRID) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StepperSettingRow(
                        label = "文件夹列数",
                        value = folderColumns,
                        onValueChange = { folderColumns = it },
                        decreaseContentDescription = "减少文件夹列数",
                        increaseContentDescription = "增加文件夹列数",
                    )
                    StepperSettingRow(
                        label = "视频列数",
                        value = videoColumns,
                        onValueChange = { videoColumns = it },
                        decreaseContentDescription = "减少视频列数",
                        increaseContentDescription = "增加视频列数",
                    )
                }
            }
            Text("排列方式", style = MaterialTheme.typography.titleSmall)
            YingLiSegmentedControl(
                options = listOf(stringResource(R.string.library_list), stringResource(R.string.library_grid)),
                selectedIndex = if (viewMode == LibraryViewMode.LIST) 0 else 1,
                onSelected = { viewMode = if (it == 0) LibraryViewMode.LIST else LibraryViewMode.GRID },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.library_sort), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                YingLiSegmentedControl(
                    options = listOf("升序", "降序"),
                    selectedIndex = if (sort.direction == SortDirection.ASCENDING) 0 else 1,
                    onSelected = { sort = sort.copy(direction = if (it == 0) SortDirection.ASCENDING else SortDirection.DESCENDING) },
                    modifier = Modifier.width(88.dp),
                    fillMaxWidth = false,
                    size = YingLiSegmentedControlSize.Small,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LibrarySortField.entries.forEach { field ->
                    SortFieldChip(field.label(), sort.field == field) { sort = sort.copy(field = field) }
                }
            }
            Text("文件夹信息", style = MaterialTheme.typography.titleSmall, color = if (browseMode == LibraryBrowseMode.FOLDER) YingLiTheme.colors.textPrimary else YingLiTheme.colors.textSecondary)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FolderField.entries.forEach { field ->
                    FieldCapsule(field.folderLabel(), field in fields.folderFields, browseMode == LibraryBrowseMode.FOLDER) { fields = fields.copy(folderFields = fields.folderFields.toggle(field)) }
                }
            }
            Text("视频信息", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VideoField.entries.forEach { field ->
                    FieldCapsule(field.videoLabel(), field in fields.videoFields, true) { fields = fields.copy(videoFields = fields.videoFields.toggle(field)) }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(48.dp)) { Text(stringResource(R.string.library_cancel)) }
            Button(onClick = { onApply(browseMode, viewMode, sort, fields, breadcrumbMode, folderColumns, videoColumns) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("确定") }
        }
    }
}

@Composable
private fun StepperSettingRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    decreaseContentDescription: String,
    increaseContentDescription: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        YingLiStepper(
            value = value,
            onValueChange = onValueChange,
            valueRange = LibraryDisplayPreference.MIN_COLUMNS..LibraryDisplayPreference.MAX_COLUMNS,
            decreaseContentDescription = decreaseContentDescription,
            increaseContentDescription = increaseContentDescription,
            modifier = Modifier.width(128.dp),
        )
    }
}

@Composable
private fun SortFieldChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.height(40.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) YingLiTheme.colors.surfaceComponent else YingLiTheme.colors.surface,
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Box(Modifier.padding(horizontal = 16.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun FieldCapsule(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .height(40.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) YingLiTheme.colors.surfaceComponent else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(2.dp, if (selected) YingLiTheme.colors.textSecondary else YingLiTheme.colors.borderDivider),
    ) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(50)).background(if (selected) YingLiTheme.colors.textSecondary else Color.Transparent).then(if (!selected) Modifier.border(2.dp, YingLiTheme.colors.textSecondary, RoundedCornerShape(50)) else Modifier),
                contentAlignment = Alignment.Center,
            ) { if (selected) Icon(YingLiIcon.SUCCESS.imageVector, contentDescription = null, tint = YingLiTheme.colors.surface, modifier = Modifier.size(16.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge, color = YingLiTheme.colors.textPrimary, maxLines = 1)
        }
    }
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

private fun FolderField.folderLabel(): String = when (this) {
    FolderField.VIDEO_COUNT -> "视频总数"
    FolderField.FOLDER_SIZE -> "文件夹大小"
    FolderField.TOTAL_DURATION -> "总时长"
    FolderField.MODIFIED_TIME -> "修改时间"
    FolderField.PATH -> "路径"
}

private fun VideoField.videoLabel(): String = when (this) {
    VideoField.PATH -> "路径"
    VideoField.FILE_SIZE -> "文件大小"
    VideoField.RESOLUTION -> "分辨率"
    VideoField.MODIFIED_TIME -> "修改时间"
    VideoField.PLAYBACK_PROGRESS -> "播放进度"
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
    LibrarySortField.RESOLUTION -> R.string.library_sort_resolution
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
