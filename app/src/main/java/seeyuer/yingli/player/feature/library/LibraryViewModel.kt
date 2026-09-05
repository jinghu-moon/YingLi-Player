package seeyuer.yingli.player.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.library.BatchOperationSummary
import seeyuer.yingli.player.domain.library.DurationFilter
import seeyuer.yingli.player.domain.library.FilterExpression
import seeyuer.yingli.player.domain.library.LibraryDisplayPreference
import seeyuer.yingli.player.domain.library.LibraryGroup
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryMutationRepository
import seeyuer.yingli.player.domain.library.LibraryPreferenceRepository
import seeyuer.yingli.player.domain.library.LibraryQuery
import seeyuer.yingli.player.domain.library.LibraryRepository
import seeyuer.yingli.player.domain.library.LibraryResult
import seeyuer.yingli.player.domain.library.LibrarySortField
import seeyuer.yingli.player.domain.library.LibraryViewMode
import seeyuer.yingli.player.domain.library.SortDirection
import seeyuer.yingli.player.domain.library.SortSpec
import seeyuer.yingli.player.domain.library.TrashEntry
import seeyuer.yingli.player.domain.library.TrashRepository

data class LibraryUiState(
    val loading: Boolean = true,
    val items: List<LibraryMedia> = emptyList(),
    val totalCount: Int = 0,
    val keyword: String = "",
    val group: LibraryGroup = LibraryGroup.ALL,
    val sort: SortSpec = SortSpec(),
    val filter: FilterExpression = FilterExpression(),
    val preference: LibraryDisplayPreference = LibraryDisplayPreference(),
    val selectedIds: Set<MediaItemId> = emptySet(),
    val filterPanelOpen: Boolean = false,
    val retryableFailure: Boolean = false,
    val lastBatchResult: BatchOperationSummary? = null,
    val trashEntries: List<TrashEntry> = emptyList(),
    val trashOpen: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val loadMoreFailed: Boolean = false,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repository: LibraryRepository,
    private val preferenceRepository: LibraryPreferenceRepository,
    private val mutationRepository: LibraryMutationRepository,
    private val trashRepository: TrashRepository,
) : ViewModel() {
    private val keyword = MutableStateFlow("")
    private val group = MutableStateFlow(LibraryGroup.ALL)
    private val sort = MutableStateFlow(SortSpec())
    private val filter = MutableStateFlow(FilterExpression())
    private val selectedIds = MutableStateFlow<Set<MediaItemId>>(emptySet())
    private val filterPanelOpen = MutableStateFlow(false)
    private val lastBatchResult = MutableStateFlow<BatchOperationSummary?>(null)
    private val trashOpen = MutableStateFlow(false)
    private val pageState = MutableStateFlow(PageState())
    private val loadMoreMutex = Mutex()
    private var queryGeneration = 0L

    init {
        viewModelScope.launch {
            preferenceRepository.preference.map { preference -> preference.sort }
                .distinctUntilChanged()
                .collect { stored -> sort.value = stored }
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

    init {
        viewModelScope.launch {
            queryInput.collectLatest { input ->
                val generation = ++queryGeneration
                pageState.value = PageState(input = input, generation = generation, loading = true)
                repository.observe(input.toQuery()).collect { result ->
                    pageState.value = when (result) {
                        is LibraryResult.Success -> PageState(
                            input = input,
                            generation = generation,
                            items = result.value.items,
                            totalCount = result.value.totalCount,
                            nextCursor = result.value.nextCursor,
                        )
                        LibraryResult.RetryableFailure -> PageState(
                            input = input,
                            retryableFailure = true,
                        )
                    }
                }
            }
        }
    }

    private val interactionState = combine(
        selectedIds,
        filterPanelOpen,
        lastBatchResult,
        trashRepository.observe(),
        trashOpen,
    ) { selection, panelOpen, batchResult, trashEntries, isTrashOpen ->
        InteractionState(selection, panelOpen, batchResult, trashEntries, isTrashOpen)
    }

    val state: StateFlow<LibraryUiState> = combine(
        pageState,
        interactionState,
        preferenceRepository.preference,
    ) { page, interaction, preference ->
        LibraryUiState(
            loading = page.loading,
            items = page.items,
            totalCount = page.totalCount,
            keyword = page.input?.keyword.orEmpty(),
            group = page.input?.group ?: LibraryGroup.ALL,
            sort = page.input?.sort ?: SortSpec(),
            filter = page.input?.filter ?: FilterExpression(),
            preference = preference,
            selectedIds = interaction.selection,
            filterPanelOpen = interaction.panelOpen,
            retryableFailure = page.retryableFailure,
            lastBatchResult = interaction.batchResult,
            trashEntries = interaction.trashEntries,
            trashOpen = interaction.trashOpen,
            loadingMore = page.loadingMore,
            hasMore = page.nextCursor != null,
            loadMoreFailed = page.loadMoreFailed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), LibraryUiState())

    fun setKeyword(value: String) {
        keyword.value = value.take(LibraryQuery.MAX_KEYWORD_LENGTH)
    }

    fun loadMore() {
        viewModelScope.launch {
            loadMoreMutex.withLock {
                val current = pageState.value
                val input = current.input ?: return@withLock
                val cursor = current.nextCursor ?: return@withLock
                if (current.loading || current.loadingMore) return@withLock
                val generation = current.generation
                pageState.update { it.copy(loadingMore = true, loadMoreFailed = false) }
                when (val result = repository.query(input.toQuery(cursor))) {
                    is LibraryResult.Success -> pageState.update { previous ->
                        if (previous.input != input || previous.nextCursor != cursor || previous.generation != generation) previous else {
                            previous.copy(
                                items = (previous.items + result.value.items).distinctBy(LibraryMedia::id),
                                totalCount = result.value.totalCount,
                                nextCursor = result.value.nextCursor,
                                loadingMore = false,
                            )
                        }
                    }
                    LibraryResult.RetryableFailure -> pageState.update {
                        if (it.input != input || it.nextCursor != cursor || it.generation != generation) it else {
                            it.copy(loadingMore = false, loadMoreFailed = true)
                        }
                    }
                }
            }
        }
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
        filterPanelOpen.value = !filterPanelOpen.value
    }

    fun toggleTrash() {
        trashOpen.value = !trashOpen.value
    }

    fun setSort(field: LibrarySortField) {
        val updated = if (sort.value.field == field) {
            sort.value.copy(direction = if (sort.value.direction == SortDirection.ASCENDING) {
                SortDirection.DESCENDING
            } else {
                SortDirection.ASCENDING
            })
        } else {
            SortSpec(field)
        }
        sort.value = updated
        viewModelScope.launch { preferenceRepository.setSort(updated) }
    }

    fun applyResolutionFilter(minimumWidth: Int?) {
        filter.value = filter.value.copy(minimumWidth = minimumWidth)
    }

    fun applyDurationFilter(maximumMillis: Long?) {
        filter.value = filter.value.copy(duration = DurationFilter(maximumMillis = maximumMillis))
    }

    fun resetFilter() {
        filter.value = FilterExpression()
        sort.value = SortSpec()
        viewModelScope.launch { preferenceRepository.setSort(SortSpec()) }
    }

    fun toggleSelection(id: MediaItemId) {
        selectedIds.value = if (id in selectedIds.value) selectedIds.value - id else selectedIds.value + id
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    fun trashSelected() {
        val selected = state.value.items.filter { it.id in selectedIds.value }
        if (selected.isEmpty()) return
        viewModelScope.launch {
            lastBatchResult.value = mutationRepository.trash(selected)
            selectedIds.value = emptySet()
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

        fun factory(
            repository: LibraryRepository,
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

    private data class PageState(
        val input: QueryInput? = null,
        val generation: Long = 0L,
        val items: List<LibraryMedia> = emptyList(),
        val totalCount: Int = 0,
        val nextCursor: seeyuer.yingli.player.domain.library.LibraryCursor? = null,
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val retryableFailure: Boolean = false,
        val loadMoreFailed: Boolean = false,
    )

    private data class InteractionState(
        val selection: Set<MediaItemId>,
        val panelOpen: Boolean,
        val batchResult: BatchOperationSummary?,
        val trashEntries: List<TrashEntry>,
        val trashOpen: Boolean,
    )
}
