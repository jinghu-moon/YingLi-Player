package seeyuer.yingli.player.feature.shell

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import seeyuer.yingli.player.core.datastore.AppearanceSettings
import seeyuer.yingli.player.core.datastore.ThemePreference
import seeyuer.yingli.player.core.datastore.ThemeRepository
import seeyuer.yingli.player.domain.navigation.GlobalAppAction
import seeyuer.yingli.player.domain.navigation.NavigationState
import seeyuer.yingli.player.domain.navigation.RootDestination
import seeyuer.yingli.player.domain.navigation.SavedStateNavigationStateStore
import seeyuer.yingli.player.domain.pagestate.PageViewStateStore
import seeyuer.yingli.player.domain.pagestate.SavedStatePageViewStateStore

class YingLiAppViewModel(
    private val themeRepository: ThemeRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val navigationStore = SavedStateNavigationStateStore(savedStateHandle)
    val navigationState: StateFlow<NavigationState> = navigationStore.state
    val pageViewStateStore: PageViewStateStore = SavedStatePageViewStateStore(savedStateHandle)
    val appearanceSettings: StateFlow<AppearanceSettings> = themeRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = AppearanceSettings(),
    )

    init {
        viewModelScope.launch {
            themeRepository.settings.collect { settings ->
                navigationStore.setProcessingPinned(settings.processingPinned)
            }
        }
    }

    fun selectRoot(destination: RootDestination) = navigationStore.selectRoot(destination)

    fun openGlobalAction(action: GlobalAppAction) = navigationStore.openGlobalAction(action)

    fun openPlayer(mediaId: String) = navigationStore.openPlayer(mediaId)

    fun navigateBack(): Boolean = navigationStore.navigateBack()

    fun setThemePreference(preference: ThemePreference) {
        viewModelScope.launch { themeRepository.setThemePreference(preference) }
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        viewModelScope.launch { themeRepository.setDynamicColorEnabled(enabled) }
    }

    fun setProcessingPinned(pinned: Boolean) {
        viewModelScope.launch { themeRepository.setProcessingPinned(pinned) }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(themeRepository: ThemeRepository) = viewModelFactory {
            initializer {
                YingLiAppViewModel(
                    themeRepository = themeRepository,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }
    }
}
