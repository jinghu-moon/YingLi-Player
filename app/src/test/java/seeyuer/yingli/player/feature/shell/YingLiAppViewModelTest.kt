package seeyuer.yingli.player.feature.shell

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import seeyuer.yingli.player.core.datastore.ThemePreference
import seeyuer.yingli.player.domain.navigation.RootDestination
import seeyuer.yingli.player.testing.FakeThemeRepository
import seeyuer.yingli.player.testing.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class YingLiAppViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `repository processing preference updates primary destinations`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val repository = FakeThemeRepository()
        val viewModel = YingLiAppViewModel(repository, SavedStateHandle())

        viewModel.setProcessingPinned(true)
        advanceUntilIdle()

        assertEquals(RootDestination.entries, viewModel.navigationState.value.primaryDestinations)
    }

    @Test
    fun `theme mutations are delegated to repository`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val repository = FakeThemeRepository()
        val viewModel = YingLiAppViewModel(repository, SavedStateHandle())

        viewModel.setThemePreference(ThemePreference.DARK)
        advanceUntilIdle()

        val settings = repository.settings.first()
        assertEquals(ThemePreference.DARK, settings.themePreference)
    }
}
