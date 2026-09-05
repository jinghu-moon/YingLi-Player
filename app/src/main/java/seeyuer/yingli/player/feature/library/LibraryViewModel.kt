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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.library.BatchOperationSummary
import seeyuer.yingli.player.domain.library.DurationFilter
import seeyuer.yingli.player.domain.library.FilterExpression
import seeyuer.yingli.player.domain.library.LibraryDisplayPreference
import seeyuer.yingli.player.domain.library.LibraryGroup
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryMutationRepository
import seeyuer.yingli.player.domain.library.LibraryPagingRepository
import seeyuer.yingli.player.domain.library.LibraryPreferenceRepository
import seeyuer.yingli.player.domain.library.LibraryQuery
import seeyuer.yingli.player.domain.library.LibrarySortField
import seeyuer.yingli.player.domain.library.LibraryViewMode
import seeyuer.yingli.player.domain.library.SortDirection
import seeyuer.yingli.player.domain.library.SortSpec
import seeyuer.yingli.player.domain.library.TrashEntry
import seeyuer.yingli.player.domain.library.TrashRepository

data class LibraryUiState(
    val totalCount: Int = 0,
    val keyword: String = "",
    val group: LibraryGroup = LibraryGroup.ALL,
    val sort: SortSpec = SortSpec(),
    val filter: FilterExpression = FilterExpression(),
    val preference: LibraryDisplayPreference = LibraryDisplayPreference(),
    val selectedIds: Set<MediaItemId> = emptySet(),
    val filterPanelOpen: Boolean = false,
    val lastBatchResult: BatchOperationSummary? = null,
    val trashEntries: List<TrashEntry> = emptyList(),
    val trashOpen: Boolean = false,
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
    private val sort = MutableStateFlow(SortSpec())
    private val filter = MutableStateFlow(FilterExpression())
    private val selectedItems = MutableStateFlow<Map<MediaItemId, LibraryMedia>>(emptyMap())
    private val filterPanelOpen = MutableStateFlow(false)
    private val lastBatchResult = MutableStateFlow<BatchOperationSummary?>(null)
    private val trashOpen = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            preferenceRepository.preference.map { it.sort }
                .distinctUntilChanged()
                .collect { sort.value = it }
        }
    }

    private val queryInput = combine(
        keyword.debounce(SEARCH_DEBOUNCE_MILLIS),
        group,
        sort,
        filter,
    ) { text, selectedGroup, selectedSort, selectedFilter ->
        QueryInput(text, selectedGroup, selectedSort, selectedFilter)
    }.distinctUntilChanged()

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

    private val interactionState = combine(
        selectedItems,
        filterPanelOpen,
        lastBatchResult,
        trashRepository.observe(),
        trashOpen,
    ) { selected, panelOpen, batchResult, trashEntries, isTrashOpen ->
        InteractionState(selected.keys, panelOpen, batchResult, trashEntries, isTrashOpen)
    }

    val state: StateFlow<LibraryUiState> = combine(
        queryInput,
        totalCount,
        interactionState,
        preferenceRepository.preference,
    ) { input, count, interaction, preference ->
        LibraryUiState(
            totalCount = count,
            keyword = input.keyword,
            group = input.group,
            sort = input.sort,
            filter = input.filter,
            preference = preference,
            selectedIds = interaction.selection,
            filterPanelOpen = interaction.panelOpen,
            lastBatchResult = interaction.batchResult,
            trashEntries = interaction.trashEntries,
            trashOpen = interaction.trashOpen,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), LibraryUiState())

    fun setKeyword(value: String) {
        keyword.value = value.take(LibraryQuery.MAX_KEYWORD_LENGTH)
    }

    fun setGroup(value: LibraryGroup) {
        group.value = value
    }

    fun setViewMode(value: LibraryViewMode) {
        viewModelScope.launch { preferenceRepository.setViewMode(value) }
    }

    fun setThumbnailScale(value: Float) {
        viewModelScope.launch { preferenceRepository.setThumbnailScale(value) }
    }

    fun toggleFilterPanel() {
        filterPanelOpen.update { !it }
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
        selectedItems.update { current ->
            if (item.id in current) current - item.id else current + (item.id to item)
        }
    }

    fun clearSelection() {
        selectedItems.value = emptyMap()
    }

    fun trashSelected() {
        val selected = selectedItems.value.values.toList()
        if (selected.isEmpty()) return
        viewModelScope.launch {
            lastBatchResult.value = mutationRepository.trash(selected)
            selectedItems.value = emptyMap()
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
    ) {
        fun toQuery(cursor: seeyuer.yingli.player.domain.library.LibraryCursor? = null) =
            LibraryQuery(keyword, group, sort, filter, cursor = cursor)
    }

    private data class InteractionState(
        val selection: Set<MediaItemId>,
        val panelOpen: Boolean,
        val batchResult: BatchOperationSummary?,
        val trashEntries: List<TrashEntry>,
        val trashOpen: Boolean,
    )
}
