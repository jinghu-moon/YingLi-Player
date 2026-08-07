package seeyuer.yingli.player.domain.navigation

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationStateStoreTest {
    @Test
    fun `root destinations keep independent stacks and restore source`() {
        val store = SavedStateNavigationStateStore(SavedStateHandle())
        store.openDetail("home-media")
        store.selectRoot(RootDestination.LIBRARY)
        store.openDetail("library-media")

        assertEquals(AppRoute.Detail("library-media", RootDestination.LIBRARY), store.state.value.currentRoute)
        store.selectRoot(RootDestination.HOME)
        assertEquals(AppRoute.Detail("home-media", RootDestination.HOME), store.state.value.currentRoute)
        assertTrue(store.navigateBack())
        assertEquals(AppRoute.Root(RootDestination.HOME), store.state.value.currentRoute)
    }

    @Test
    fun `processing is a top action until pinned as a fourth destination`() {
        val store = SavedStateNavigationStateStore(SavedStateHandle())

        store.selectRoot(RootDestination.PROCESSING)
        assertEquals(RootDestination.HOME, store.state.value.currentRoot)
        store.openGlobalAction(GlobalAppAction.OPEN_PROCESSING)
        assertEquals(AppRoute.Processing, store.state.value.currentRoute)

        store.setProcessingPinned(true)
        store.selectRoot(RootDestination.PROCESSING)
        assertEquals(4, store.state.value.primaryDestinations.size)
        assertEquals(RootDestination.PROCESSING, store.state.value.currentRoot)

        store.setProcessingPinned(false)
        assertEquals(RootDestination.HOME, store.state.value.currentRoot)
        assertEquals(3, store.state.value.primaryDestinations.size)
    }

    @Test
    fun `player and app lock hide primary navigation`() {
        val playerStore = SavedStateNavigationStateStore(SavedStateHandle())
        playerStore.openPlayer("movie-1")
        assertFalse(playerStore.state.value.showPrimaryNavigation)

        val lockStore = SavedStateNavigationStateStore(SavedStateHandle())
        lockStore.openAppLock()
        assertFalse(lockStore.state.value.showPrimaryNavigation)
    }

    @Test
    fun `duplicate routes and invalid identifiers are ignored`() {
        val store = SavedStateNavigationStateStore(SavedStateHandle())

        store.openDetail("movie-1")
        store.openDetail("movie-1")
        store.openDetail("path/not-stable")

        assertEquals(2, store.state.value.stacks.getValue(RootDestination.HOME).size)
    }

    @Test
    fun `unknown deep link resets safely to home`() {
        val store = SavedStateNavigationStateStore(SavedStateHandle())
        store.selectRoot(RootDestination.ORGANIZE)

        store.navigateDeepLink("unknown/path")

        assertEquals(NavigationState(), store.state.value)
    }

    @Test
    fun `saved state restores route stacks without business objects`() {
        val handle = SavedStateHandle()
        val first = SavedStateNavigationStateStore(handle)
        first.selectRoot(RootDestination.LIBRARY)
        first.openPlayer("movie_42")

        val restored = SavedStateNavigationStateStore(handle)

        assertEquals(first.state.value, restored.state.value)
        assertEquals(AppRoute.Player("movie_42", RootDestination.LIBRARY), restored.state.value.currentRoute)
    }
}
