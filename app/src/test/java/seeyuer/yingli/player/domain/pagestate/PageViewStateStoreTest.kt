package seeyuer.yingli.player.domain.pagestate

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import seeyuer.yingli.player.domain.navigation.RootDestination

class PageViewStateStoreTest {
    @Test
    fun `page and group states are isolated and restored`() {
        val handle = SavedStateHandle()
        val store = SavedStatePageViewStateStore(handle)
        val home = PageStateKey(RootDestination.HOME, "recent")
        val library = PageStateKey(RootDestination.LIBRARY, "recent")
        val expected = PageViewState(12, 8, PageViewMode.GRID, PageSortOrder.NAME)

        store.update(home, expected)

        assertEquals(expected, SavedStatePageViewStateStore(handle).get(home))
        assertEquals(PageViewState(), store.get(library))
    }

    @Test
    fun `invalid positions and incompatible versions return safe values`() {
        val handle = SavedStateHandle()
        val store = SavedStatePageViewStateStore(handle)
        val current = PageStateKey(RootDestination.ORGANIZE)
        store.update(current, PageViewState(scrollIndex = -9, scrollOffset = -4))

        assertEquals(PageViewState(), store.get(current))
        assertEquals(PageViewState(), store.get(current.copy(version = PageStateKey.CURRENT_VERSION + 1)))
    }

    @Test
    fun `session filters are not persisted and can be cleared`() {
        val handle = SavedStateHandle()
        val key = PageStateKey(RootDestination.LIBRARY)
        val store = SavedStatePageViewStateStore(handle)
        store.setSessionFilter(key, "unwatched")

        assertEquals("unwatched", store.getSessionFilter(key))
        assertNull(SavedStatePageViewStateStore(handle).getSessionFilter(key))
        store.clearSessionFilters()
        assertNull(store.getSessionFilter(key))
    }

    @Test
    fun `removing group clears saved and session state`() {
        val key = PageStateKey(RootDestination.HOME, "continue")
        val store = SavedStatePageViewStateStore(SavedStateHandle())
        store.update(key, PageViewState(scrollIndex = 7))
        store.setSessionFilter(key, "short")

        store.removeGroup(RootDestination.HOME, "continue")

        assertEquals(PageViewState(), store.get(key))
        assertNull(store.getSessionFilter(key))
    }
}
