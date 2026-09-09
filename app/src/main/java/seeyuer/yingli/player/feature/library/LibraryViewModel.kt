package seeyuer.yingli.player.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.library.BatchOperationSummary
import seeyuer.yingli.player.domain.library.BreadcrumbMode
import seeyuer.yingli.player.domain.library.DurationFilter
import seeyuer.yingli.player.domain.library.FilterExpression
import seeyuer.yingli.player.domain.library.LibraryDisplayPreference
import seeyuer.yingli.player.domain.library.LibraryDisplayFields
import seeyuer.yingli.player.domain.library.LibraryBrowseMode
import seeyuer.yingli.player.domain.library.LibraryGroup
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryMutationRepository
import seeyuer.yingli.player.domain.library.LibraryPagingRepository
import seeyuer.yingli.player.domain.library.LibraryPreferenceRepository
import seeyuer.yingli.player.domain.library.LibraryQuery
import seeyuer.yingli.player.domain.library.LibrarySortField
import seeyuer.yingli.player.domain.library.LibraryViewMode
import seeyuer.yingli.player.domain.library.LibraryPathSegment
import seeyuer.yingli.player.domain.library.LibraryFolder
import seeyuer.yingli.player.domain.library.SortDirection
import seeyuer.yingli.player.domain.library.SortSpec
import seeyuer.yingli.player.domain.library.TrashEntry
import seeyuer.yingli.player.domain.library.TrashRepository

data class LibraryUiState(
    val totalCount: Int = 0,
    val folderTreeVideoCount: Int = 0,
    val keyword: String = "",
    val group: LibraryGroup = LibraryGroup.ALL,
    val browseMode: LibraryBrowseMode = LibraryBrowseMode.FOLDER,
    val currentPath: List<LibraryPathSegment> = emptyList(),
    val sort: SortSpec = SortSpec(),
    val filter: FilterExpression = FilterExpression(),
    val preference: LibraryDisplayPreference = LibraryDisplayPreference(),
    val selectedIds: Set<MediaItemId> = emptySet(),
    val selectionMode: Boolean = false,
    val filterPanelOpen: Boolean = false,
    val searchOpen: Boolean = false,
    val moreMenuOpen: Boolean = false,
    val displayFields: LibraryDisplayFields = LibraryDisplayFields(),
    val lastBatchResult: BatchOperationSummary? = null,
    val trashEntries: List<TrashEntry> = emptyList(),
    val trashOpen: Boolean = false,
    val folders: List<LibraryFolder> = emptyList(),
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repository: LibraryPagingRepository,
    private val preferenceRepository: LibraryPreferenceRepository,
    private val mutationRepository: LibraryMutationRepository,
    private val trashRepository: TrashRepository,
) : ViewModel() {
    private val keyword = MutableStateFlow("")
    private val group = MutableStateFlow(LibraryGroup.ALL)
    private val browseMode = MutableStateFlow(LibraryBrowseMode.FOLDER)
    private val currentPath = MutableStateFlow<List<LibraryPathSegment>>(emptyList())
    private val sort = MutableStateFlow(SortSpec())
    private val filter = MutableStateFlow(FilterExpression())
    private val selectedItems = MutableStateFlow<Set<MediaItemId>>(emptySet())
    private val selectionMode = MutableStateFlow(false)
    private val filterPanelOpen = MutableStateFlow(false)
    private val searchOpen = MutableStateFlow(false)
    private val moreMenuOpen = MutableStateFlow(false)
    private val displayFields = MutableStateFlow(LibraryDisplayFields())
    private val lastBatchResult = MutableStateFlow<BatchOperationSummary?>(null)
    private val trashOpen = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            preferenceRepository.preference.map { it.sort }
                .distinctUntilChanged()
                .collect { sort.value = it }
        }
    }

    private val baseQueryInput = combine(
        keyword.debounce(SEARCH_DEBOUNCE_MILLIS),
        group,
        sort,
        filter,
    ) { text, selectedGroup, selectedSort, selectedFilter ->
        QueryInput(text, selectedGroup, selectedSort, selectedFilter)
    }.distinctUntilChanged()

    private val queryInput = combine(baseQueryInput, browseMode, currentPath) { input, mode, path ->
        input.copy(browseMode = mode, currentPath = path.lastOrNull()?.path.orEmpty())
    }.distinctUntilChanged().onEach { selectedItems.value = emptySet() }

    val pagingData: Flow<PagingData<LibraryMedia>> = queryInput
        .flatMapLatest { input ->
            Pager(
                config = PagingConfig(
                    pageSize = LibraryQuery.DEFAULT_PAGE_SIZE,
                    initialLoadSize = LibraryQuery.DEFAULT_PAGE_SIZE,
                    prefetchDistance = PREFETCH_DISTANCE,
                    maxSize = MAX_PAGE_SIZE,
                    enablePlaceholders = false,
                ),
                pagingSourceFactory = {
                    LibraryPagingSource(repository, input.toQuery(), viewModelScope)
                },
            ).flow
        }
        .cachedIn(viewModelScope)

    private val totalCount = queryInput
        .flatMapLatest { input -> repository.observeCount(input.toQuery()) }
        .catch { emit(0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), 0)

    private val folderTreeVideoCount = currentPath
        .map { it.lastOrNull()?.path.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { path ->
            if (path.isBlank()) kotlinx.coroutines.flow.flowOf(0)
            else repository.observeFolderTreeVideoCount(path)
        }
        .catch { emit(0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), 0)

    private val folders = queryInput
        .flatMapLatest { input ->
            val query = input.toQuery()
            if (query.browseMode != LibraryBrowseMode.FOLDER || query.normalizedKeyword.isNotBlank()) {
                kotlinx.coroutines.flow.flowOf(emptyList())
            } else {
                kotlinx.coroutines.flow.flow { emit(repository.folders(query)) }
            }
        }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    private val counts = combine(totalCount, folderTreeVideoCount, ::LibraryCounts)

    private val interactionState = combine(
        selectedItems,
        selectionMode,
        filterPanelOpen,
        searchOpen,
        moreMenuOpen,
        displayFields,
        lastBatchResult,
        trashRepository.observe(),
        trashOpen,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        InteractionState(
            selection = values[0] as Set<MediaItemId>,
            selectionMode = values[1] as Boolean,
            panelOpen = values[2] as Boolean,
            searchOpen = values[3] as Boolean,
            moreMenuOpen = values[4] as Boolean,
            displayFields = values[5] as LibraryDisplayFields,
            batchResult = values[6] as BatchOperationSummary?,
            trashEntries = values[7] as List<TrashEntry>,
            trashOpen = values[8] as Boolean,
        )
    }

    val state: StateFlow<LibraryUiState> = combine(
        queryInput,
        counts,
        folders,
        interactionState,
        preferenceRepository.preference,
    ) { input, counts, folderRows, interaction, preference ->
        LibraryUiState(
            totalCount = counts.direct,
            folderTreeVideoCount = counts.tree,
            keyword = input.keyword,
            group = input.group,
            browseMode = input.browseMode,
            currentPath = currentPath.value,
            sort = input.sort,
            filter = input.filter,
            preference = preference,
            selectedIds = interaction.selection,
            selectionMode = interaction.selectionMode,
            filterPanelOpen = interaction.panelOpen,
            searchOpen = interaction.searchOpen,
            moreMenuOpen = interaction.moreMenuOpen,
            displayFields = interaction.displayFields,
            lastBatchResult = interaction.batchResult,
            trashEntries = interaction.trashEntries,
            trashOpen = interaction.trashOpen,
            folders = folderRows,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), LibraryUiState())

    fun setKeyword(value: String) {
        keyword.value = value.take(LibraryQuery.MAX_KEYWORD_LENGTH)
    }

    fun setGroup(value: LibraryGroup) {
        group.value = value
    }

    fun setBrowseMode(value: LibraryBrowseMode) {
        browseMode.value = value
        if (value == LibraryBrowseMode.ALL_VIDEOS) currentPath.value = emptyList()
    }

    fun enterFolder(segment: LibraryPathSegment) {
        browseMode.value = LibraryBrowseMode.FOLDER
        currentPath.update { it + segment }
        clearSelection()
    }

    fun navigateToPath(index: Int) {
        currentPath.update { path -> if (index < 0) emptyList() else path.take(index + 1) }
        clearSelection()
    }

    fun navigateUp(): Boolean {
        if (currentPath.value.isEmpty()) return false
        currentPath.update { it.dropLast(1) }
        clearSelection()
        return true
    }

    fun toggleSearch() {
        searchOpen.update { !it }
        moreMenuOpen.value = false
        if (!searchOpen.value) keyword.value = ""
    }

    fun closeSearch() {
        searchOpen.value = false
        keyword.value = ""
    }

    fun toggleMoreMenu() {
        moreMenuOpen.update { !it }
        if (moreMenuOpen.value) searchOpen.value = false
    }

    fun closeMoreMenu() { moreMenuOpen.value = false }

    fun setViewMode(value: LibraryViewMode) {
        viewModelScope.launch { preferenceRepository.setViewMode(value) }
    }

    fun setThumbnailScale(value: Float) {
        viewModelScope.launch { preferenceRepository.setThumbnailScale(value) }
    }

    fun toggleFilterPanel() {
        filterPanelOpen.update { !it }
        moreMenuOpen.value = false
    }


    fun applyQuickSettings(
        mode: LibraryBrowseMode,
        viewMode: LibraryViewMode,
        sortSpec: SortSpec,
        fields: LibraryDisplayFields,
        breadcrumbMode: BreadcrumbMode = BreadcrumbMode.SCROLL,
        folderColumns: Int = LibraryDisplayPreference.DEFAULT_FOLDER_COLUMNS,
        videoColumns: Int = LibraryDisplayPreference.DEFAULT_VIDEO_COLUMNS,
    ) {
        browseMode.value = mode
        if (mode == LibraryBrowseMode.ALL_VIDEOS) currentPath.value = emptyList()
        sort.value = sortSpec
        displayFields.value = fields
        filterPanelOpen.value = false
        viewModelScope.launch {
            preferenceRepository.setViewMode(viewMode)
            preferenceRepository.setBreadcrumbMode(breadcrumbMode)
            preferenceRepository.setSort(sortSpec)
            preferenceRepository.setFolderColumns(folderColumns)
            preferenceRepository.setVideoColumns(videoColumns)
        }
    }

    fun toggleTrash() {
        trashOpen.update { !it }
    }

    fun setSort(field: LibrarySortField) {
        val updated = if (sort.value.field == field) {
            sort.value.copy(
                direction = if (sort.value.direction == SortDirection.ASCENDING) {
                    SortDirection.DESCENDING
                } else {
                    SortDirection.ASCENDING
                },
            )
        } else {
            SortSpec(field)
        }
        sort.value = updated
        viewModelScope.launch { preferenceRepository.setSort(updated) }
    }

    fun applyResolutionFilter(minimumWidth: Int?) {
        filter.update { it.copy(minimumWidth = minimumWidth) }
    }

    fun applyDurationFilter(maximumMillis: Long?) {
        filter.update { it.copy(duration = DurationFilter(maximumMillis = maximumMillis)) }
    }

    fun resetFilter() {
        filter.value = FilterExpression()
        sort.value = SortSpec()
        viewModelScope.launch { preferenceRepository.setSort(SortSpec()) }
    }

    fun toggleSelection(item: LibraryMedia) {
        selectionMode.value = true
        selectedItems.update { current ->
            if (item.id in current) current - item.id else current + item.id
        }
    }

    fun clearSelection() {
        selectedItems.value = emptySet()
        selectionMode.value = false
    }

    fun enterSelectionMode() { selectionMode.value = true }

    fun trashSelected() {
        val selectedIds = selectedItems.value
        if (selectedIds.isEmpty()) return
        viewModelScope.launch {
            val selected = repository.findByIds(selectedIds)
            lastBatchResult.value = mutationRepository.trash(selected)
            selectedItems.value = emptySet()
        }
    }

    fun restore(entry: TrashEntry) {
        viewModelScope.launch { mutationRepository.restore(entry) }
    }

    fun purge(entry: TrashEntry) {
        viewModelScope.launch { mutationRepository.purge(entry) }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MILLIS = 250L
        private const val STOP_TIMEOUT = 5_000L
        private const val PREFETCH_DISTANCE = 24
        private const val MAX_PAGE_SIZE = 150

        fun factory(
            repository: LibraryPagingRepository,
            preferenceRepository: LibraryPreferenceRepository,
            mutationRepository: LibraryMutationRepository,
            trashRepository: TrashRepository,
        ) = viewModelFactory {
            initializer { LibraryViewModel(repository, preferenceRepository, mutationRepository, trashRepository) }
        }
    }

    private data class QueryInput(
        val keyword: String,
        val group: LibraryGroup,
        val sort: SortSpec,
        val filter: FilterExpression,
        val browseMode: LibraryBrowseMode = LibraryBrowseMode.FOLDER,
        val currentPath: String = "",
    ) {
        fun toQuery(cursor: seeyuer.yingli.player.domain.library.LibraryCursor? = null) =
            LibraryQuery(keyword, group, sort, filter, cursor = cursor, browseMode = browseMode, currentPath = currentPath)
    }

    private data class LibraryCounts(
        val direct: Int,
        val tree: Int,
    )

    private data class InteractionState(
        val selection: Set<MediaItemId>,
        val selectionMode: Boolean,
        val panelOpen: Boolean,
        val searchOpen: Boolean,
        val moreMenuOpen: Boolean,
        val displayFields: LibraryDisplayFields,
        val batchResult: BatchOperationSummary?,
        val trashEntries: List<TrashEntry>,
        val trashOpen: Boolean,
    )
}
