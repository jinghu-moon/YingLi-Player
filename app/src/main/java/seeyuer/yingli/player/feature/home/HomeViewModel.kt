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
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryPage
import seeyuer.yingli.player.domain.library.LibraryQuery
import seeyuer.yingli.player.domain.library.LibraryRepository
import seeyuer.yingli.player.domain.library.LibraryResult
import seeyuer.yingli.player.domain.organize.ContinueWatchingPolicy
import seeyuer.yingli.player.domain.organize.HistoryEntry
import seeyuer.yingli.player.domain.organize.HistoryRepository

data class FrequentFolder(val name: String, val count: Int)

data class HomeDashboardUiState(
    val keyword: String = "",
    val searchResults: List<LibraryMedia> = emptyList(),
    val continueWatching: List<LibraryMedia> = emptyList(),
    val recentlyAdded: List<LibraryMedia> = emptyList(),
    val frequentFolders: List<FrequentFolder> = emptyList(),
    val totalCount: Int = 0,
    val totalDurationMillis: Long = 0,
    val completedCount: Int = 0,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class HomeViewModel(
    libraryRepository: LibraryRepository,
    historyRepository: HistoryRepository,
    private val continuePolicy: ContinueWatchingPolicy = ContinueWatchingPolicy(),
) : ViewModel() {
    private val keyword = MutableStateFlow("")
    private val library = libraryRepository.observe(LibraryQuery(pageSize = 200))
    private val search = keyword.debounce(SEARCH_DEBOUNCE_MILLIS).flatMapLatest { text ->
        libraryRepository.observe(LibraryQuery(keyword = text, pageSize = 50))
    }

    val state: StateFlow<HomeDashboardUiState> = combine(
        keyword,
        library,
        search,
        historyRepository.history,
    ) { text, libraryResult, searchResult, history ->
        val libraryPage = (libraryResult as? LibraryResult.Success)?.value
        val items = libraryPage?.items.orEmpty()
        val historyByMedia = history.associateBy(HistoryEntry::mediaId)
        HomeDashboardUiState(
            keyword = text,
            searchResults = if (text.isBlank()) emptyList() else searchResult.pageItems(),
            continueWatching = items.filter { item ->
                continuePolicy.isEligible(
                    item.playbackPositionMillis,
                    item.durationMillis,
                    item.completed,
                    incognito = false,
                )
            }.sortedByDescending { historyByMedia[it.id]?.lastPlayedAtEpochMillis ?: 0 }.take(10),
            recentlyAdded = items.sortedByDescending(LibraryMedia::modifiedEpochMillis).take(10),
            frequentFolders = items.groupingBy(LibraryMedia::folderAlias).eachCount()
                .entries.sortedByDescending(Map.Entry<String, Int>::value)
                .take(8).map { FrequentFolder(it.key, it.value) },
            totalCount = libraryPage?.totalCount ?: 0,
            totalDurationMillis = items.sumOf { it.durationMillis ?: 0L },
            completedCount = items.count(LibraryMedia::completed),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), HomeDashboardUiState())

    fun setKeyword(value: String) {
        keyword.value = value.take(LibraryQuery.MAX_KEYWORD_LENGTH)
    }

    private fun LibraryResult<LibraryPage>.pageItems(): List<LibraryMedia> =
        (this as? LibraryResult.Success)?.value?.items.orEmpty()

    companion object {
        private const val SEARCH_DEBOUNCE_MILLIS = 250L
        private const val STOP_TIMEOUT = 5_000L
        fun factory(libraryRepository: LibraryRepository, historyRepository: HistoryRepository) = viewModelFactory {
            initializer { HomeViewModel(libraryRepository, historyRepository) }
        }
    }
}
