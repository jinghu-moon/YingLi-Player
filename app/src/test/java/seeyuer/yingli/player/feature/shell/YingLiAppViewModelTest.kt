package seeyuer.yingli.player.feature.shell

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import seeyuer.yingli.player.data.preferences.LibraryLayoutPreference
import seeyuer.yingli.player.domain.navigation.RootDestination
import seeyuer.yingli.player.testing.FakeThemeRepository
import seeyuer.yingli.player.testing.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class YingLiAppViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `primary destinations remain fixed`() {
        val viewModel = YingLiAppViewModel(FakeThemeRepository(), SavedStateHandle())

        assertEquals(
            listOf(RootDestination.HOME, RootDestination.LIBRARY, RootDestination.ORGANIZE),
            viewModel.navigationState.value.primaryDestinations,
        )
    }

    @Test
    fun `library mutations are delegated to repository`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val repository = FakeThemeRepository()
        val viewModel = YingLiAppViewModel(repository, SavedStateHandle())

        viewModel.setLibraryLayout(LibraryLayoutPreference.LIST)
        advanceUntilIdle()

        assertEquals(LibraryLayoutPreference.LIST, repository.settings.first().libraryLayout)
    }
}
