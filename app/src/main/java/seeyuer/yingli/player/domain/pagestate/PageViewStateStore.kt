package seeyuer.yingli.player.domain.pagestate

import androidx.lifecycle.SavedStateHandle
import seeyuer.yingli.player.domain.navigation.RootDestination

enum class PageViewMode {
    LIST,
    GRID,
}

enum class PageSortOrder {
    RECENT,
    NAME,
    DURATION,
}

data class PageStateKey(
    val page: RootDestination,
    val groupKey: String = DEFAULT_GROUP,
    val version: Int = CURRENT_VERSION,
) {
    init {
        require(groupKey.matches(GROUP_PATTERN)) { "Page group keys must be stable identifiers." }
    }

    val storagePrefix: String = "page.v$version.${page.name.lowercase()}.$groupKey"

    companion object {
        const val CURRENT_VERSION = 1
        const val DEFAULT_GROUP = "default"
        val GROUP_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

data class PageViewState(
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    val viewMode: PageViewMode = PageViewMode.LIST,
    val sortOrder: PageSortOrder = PageSortOrder.RECENT,
)

interface PageViewStateStore {
    fun get(key: PageStateKey): PageViewState

    fun update(key: PageStateKey, state: PageViewState)

    fun removeGroup(page: RootDestination, groupKey: String)

    fun setSessionFilter(key: PageStateKey, filter: String?)

    fun getSessionFilter(key: PageStateKey): String?

    fun clearSessionFilters()
}

class SavedStatePageViewStateStore(
    private val savedStateHandle: SavedStateHandle,
) : PageViewStateStore {
    private val sessionFilters = mutableMapOf<PageStateKey, String>()

    override fun get(key: PageStateKey): PageViewState {
        if (key.version != PageStateKey.CURRENT_VERSION) return PageViewState()
        val prefix = key.storagePrefix
        return PageViewState(
            scrollIndex = (savedStateHandle.get<Int>("$prefix.scroll_index") ?: 0).coerceAtLeast(0),
            scrollOffset = (savedStateHandle.get<Int>("$prefix.scroll_offset") ?: 0).coerceAtLeast(0),
            viewMode = savedStateHandle.get<String>("$prefix.view_mode").toEnumOrDefault(PageViewMode.LIST),
            sortOrder = savedStateHandle.get<String>("$prefix.sort_order").toEnumOrDefault(PageSortOrder.RECENT),
        )
    }

    override fun update(key: PageStateKey, state: PageViewState) {
        if (key.version != PageStateKey.CURRENT_VERSION) return
        val prefix = key.storagePrefix
        savedStateHandle["$prefix.scroll_index"] = state.scrollIndex.coerceAtLeast(0)
        savedStateHandle["$prefix.scroll_offset"] = state.scrollOffset.coerceAtLeast(0)
        savedStateHandle["$prefix.view_mode"] = state.viewMode.name
        savedStateHandle["$prefix.sort_order"] = state.sortOrder.name
    }

    override fun removeGroup(page: RootDestination, groupKey: String) {
        val key = PageStateKey(page = page, groupKey = groupKey)
        val prefix = key.storagePrefix
        listOf("scroll_index", "scroll_offset", "view_mode", "sort_order").forEach { suffix ->
            savedStateHandle.remove<Any>("$prefix.$suffix")
        }
        sessionFilters.remove(key)
    }

    override fun setSessionFilter(key: PageStateKey, filter: String?) {
        if (filter.isNullOrBlank()) sessionFilters.remove(key) else sessionFilters[key] = filter
    }

    override fun getSessionFilter(key: PageStateKey): String? = sessionFilters[key]

    override fun clearSessionFilters() {
        sessionFilters.clear()
    }

    private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T): T =
        enumValues<T>().firstOrNull { value -> value.name == this } ?: default
}
