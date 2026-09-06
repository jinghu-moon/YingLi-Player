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
import seeyuer.yingli.player.data.preferences.AppearanceSettings
import seeyuer.yingli.player.data.preferences.LibraryLayoutPreference
import seeyuer.yingli.player.data.preferences.ThemeRepository
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

    fun selectRoot(destination: RootDestination) = navigationStore.selectRoot(destination)

    fun openGlobalAction(action: GlobalAppAction) = navigationStore.openGlobalAction(action)

    fun openPlayer(mediaId: String) = navigationStore.openPlayer(mediaId)

    fun openVault() = navigationStore.openVault()

    fun openVaultPlayer(itemId: String) = navigationStore.openVaultPlayer(itemId)

    fun navigateBack(): Boolean = navigationStore.navigateBack()

    fun setLibraryLayout(layout: LibraryLayoutPreference) {
        viewModelScope.launch { themeRepository.update { it.copy(libraryLayout = layout) } }
    }

    fun setThumbnailScale(scale: Float) {
        viewModelScope.launch {
            themeRepository.update {
                it.copy(thumbnailScale = scale.coerceIn(
                    AppearanceSettings.MIN_THUMBNAIL_SCALE,
                    AppearanceSettings.MAX_THUMBNAIL_SCALE,
                ))
            }
        }
    }

    fun setTrashRetentionDays(days: Int) {
        viewModelScope.launch {
            themeRepository.update {
                it.copy(trashRetentionDays = days.coerceIn(
                    AppearanceSettings.MIN_TRASH_RETENTION_DAYS,
                    AppearanceSettings.MAX_TRASH_RETENTION_DAYS,
                ))
            }
        }
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
