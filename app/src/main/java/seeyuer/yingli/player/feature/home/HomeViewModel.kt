package seeyuer.yingli.player.feature.home

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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import seeyuer.yingli.player.domain.duplicates.DuplicateRepository
import seeyuer.yingli.player.domain.home.DeviceStorageRepository
import seeyuer.yingli.player.domain.home.HomeCardId
import seeyuer.yingli.player.domain.home.HomeCardLayout
import seeyuer.yingli.player.domain.home.HomeCollectionPreview
import seeyuer.yingli.player.domain.home.HomeFolderPreview
import seeyuer.yingli.player.domain.home.HomeLayoutRepository
import seeyuer.yingli.player.domain.home.HomeMediaPreview
import seeyuer.yingli.player.domain.home.HomeRepository
import seeyuer.yingli.player.domain.home.HomeStats
import seeyuer.yingli.player.domain.home.MaintenanceItem
import seeyuer.yingli.player.domain.home.MaintenanceKind
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryPage
import seeyuer.yingli.player.domain.library.LibraryQuery
import seeyuer.yingli.player.domain.library.LibraryRepository
import seeyuer.yingli.player.domain.library.LibraryResult
import seeyuer.yingli.player.domain.library.TrashRepository

data class HomeSearchState(
    val keyword: String = "",
    val results: List<LibraryMedia> = emptyList(),
)

data class HomeUiState(
    val layout: HomeCardLayout = HomeCardLayout.Default,
    val stats: HomeStats? = null,
    val continueWatching: List<HomeMediaPreview> = emptyList(),
    val recentlyAdded: List<HomeMediaPreview> = emptyList(),
    val collections: List<HomeCollectionPreview> = emptyList(),
    val frequentFolders: List<HomeFolderPreview> = emptyList(),
    val maintenance: List<MaintenanceItem> = emptyList(),
    val search: HomeSearchState = HomeSearchState(),
    val editorVisible: Boolean = false,
) {
    val allCardsHidden: Boolean get() = layout.hidden.size == layout.order.size
}

private data class HomeContent(
    val stats: HomeStats,
    val continueWatching: List<HomeMediaPreview>,
    val recentlyAdded: List<HomeMediaPreview>,
    val collections: List<HomeCollectionPreview>,
    val frequentFolders: List<HomeFolderPreview>,
)

private data class HomeBase(
    val content: HomeContent,
    val maintenance: List<MaintenanceItem>,
    val layout: HomeCardLayout,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class HomeViewModel(
    homeRepository: HomeRepository,
    libraryRepository: LibraryRepository,
    private val layoutRepository: HomeLayoutRepository,
    deviceStorageRepository: DeviceStorageRepository,
    duplicateRepository: DuplicateRepository,
    trashRepository: TrashRepository,
) : ViewModel() {
    private val keyword = MutableStateFlow("")
    private val editorVisible = MutableStateFlow(false)
    private val layoutDraft = MutableStateFlow<HomeCardLayout?>(null)
    private val search = keyword.debounce(SEARCH_DEBOUNCE_MILLIS).flatMapLatest { text ->
        libraryRepository.observe(LibraryQuery(keyword = text, pageSize = SEARCH_LIMIT))
    }
    private val content = combine(
        homeRepository.observeStats(),
        homeRepository.observeContinueWatching(CONTINUE_LIMIT),
        homeRepository.observeRecentlyAdded(RECENT_LIMIT),
        homeRepository.observeCollections(COLLECTION_LIMIT),
        homeRepository.observeFrequentFolders(FOLDER_LIMIT),
    ) { stats, continueWatching, recentlyAdded, collections, folders ->
        val (totalBytes, availableBytes) = deviceStorageRepository.snapshot()
        HomeContent(
            stats = HomeStats(stats.first, stats.second, totalBytes, availableBytes),
            continueWatching = continueWatching,
            recentlyAdded = recentlyAdded,
            collections = collections,
            frequentFolders = folders,
        )
    }
    private val maintenance = combine(duplicateRepository.groups, trashRepository.observe()) { groups, trash ->
        buildList {
            if (groups.isNotEmpty()) {
                val reclaimable = groups.sumOf { group ->
                    group.candidates.sortedByDescending { it.fingerprint.sizeBytes }
                        .drop(1)
                        .sumOf { it.fingerprint.sizeBytes }
                }
                add(MaintenanceItem(MaintenanceKind.DUPLICATES, groups.size, reclaimable))
            }
            if (trash.isNotEmpty()) add(MaintenanceItem(MaintenanceKind.TRASH, trash.size))
        }
    }
    private val effectiveLayout = combine(layoutRepository.layout, layoutDraft) { stored, draft -> draft ?: stored }
    private val base = combine(content, maintenance, effectiveLayout) { content, maintenance, layout ->
        HomeBase(content, maintenance, layout)
    }

    val state: StateFlow<HomeUiState> = combine(keyword, search, base, editorVisible) { text, result, base, editor ->
        HomeUiState(
            layout = base.layout,
            stats = base.content.stats,
            continueWatching = base.content.continueWatching,
            recentlyAdded = base.content.recentlyAdded,
            collections = base.content.collections,
            frequentFolders = base.content.frequentFolders,
            maintenance = base.maintenance,
            search = HomeSearchState(text, if (text.isBlank()) emptyList() else result.pageItems()),
            editorVisible = editor,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), HomeUiState())

    fun setKeyword(value: String) {
        keyword.value = value.take(LibraryQuery.MAX_KEYWORD_LENGTH)
    }

    fun showEditor() {
        editorVisible.value = true
    }

    fun hideEditor() {
        editorVisible.value = false
    }

    fun moveCard(fromIndex: Int, toIndex: Int) = updateLayout { it.move(fromIndex, toIndex) }

    fun setCardVisible(id: HomeCardId, visible: Boolean) = updateLayout { it.setVisible(id, visible) }

    fun resetLayout() = updateLayout(HomeCardLayout::reset)

    private fun updateLayout(transform: (HomeCardLayout) -> HomeCardLayout) {
        val updated = transform(layoutDraft.value ?: state.value.layout)
        layoutDraft.value = updated
        viewModelScope.launch { runCatching { layoutRepository.save(updated) } }
    }

    private fun LibraryResult<LibraryPage>.pageItems(): List<LibraryMedia> =
        (this as? LibraryResult.Success)?.value?.items.orEmpty()

    companion object {
        private const val SEARCH_DEBOUNCE_MILLIS = 250L
        private const val STOP_TIMEOUT = 5_000L
        private const val SEARCH_LIMIT = 50
        private const val CONTINUE_LIMIT = 10
        private const val RECENT_LIMIT = 10
        private const val COLLECTION_LIMIT = 3
        private const val FOLDER_LIMIT = 5

        fun factory(
            homeRepository: HomeRepository,
            libraryRepository: LibraryRepository,
            layoutRepository: HomeLayoutRepository,
            deviceStorageRepository: DeviceStorageRepository,
            duplicateRepository: DuplicateRepository,
            trashRepository: TrashRepository,
        ) = viewModelFactory {
            initializer {
                HomeViewModel(
                    homeRepository,
                    libraryRepository,
                    layoutRepository,
                    deviceStorageRepository,
                    duplicateRepository,
                    trashRepository,
                )
            }
        }
    }
}
