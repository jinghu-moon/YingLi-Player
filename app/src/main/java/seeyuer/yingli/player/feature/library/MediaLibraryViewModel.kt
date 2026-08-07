package seeyuer.yingli.player.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import seeyuer.yingli.player.core.datastore.MediaOnboardingRepository
import seeyuer.yingli.player.core.media.*
import seeyuer.yingli.player.core.model.media.*
import seeyuer.yingli.player.domain.media.MediaScanner

enum class MediaLibraryNotice {
    PERMISSION_DENIED,
    SOURCE_OFFLINE,
    SCAN_PARTIAL,
}

data class MediaLibraryUiState(
    val onboarding: Boolean = true,
    val items: List<MediaItem> = emptyList(),
    val sources: List<MediaSource> = emptyList(),
    val scanning: Boolean = false,
    val notice: MediaLibraryNotice? = null,
)

sealed interface MediaLibraryEffect {
    data object OpenAllFilesSettings : MediaLibraryEffect
    data object RequestMediaReadPermission : MediaLibraryEffect
    data object OpenSafTreePicker : MediaLibraryEffect
}

class MediaLibraryViewModel(
    private val sourceRepository: MediaSourceRepository,
    private val catalogRepository: MediaCatalogRepository,
    private val permissionGateway: MediaPermissionGateway,
    private val scanner: MediaScanner,
    private val onboardingRepository: MediaOnboardingRepository,
) : ViewModel() {
    private val scanning = MutableStateFlow(false)
    private val notice = MutableStateFlow<MediaLibraryNotice?>(null)
    private val effects = Channel<MediaLibraryEffect>(Channel.BUFFERED)
    val effect: Flow<MediaLibraryEffect> = effects.receiveAsFlow()
    val state: StateFlow<MediaLibraryUiState> = combine(
        onboardingRepository.completed,
        catalogRepository.observeItems(),
        sourceRepository.observeSources(),
        scanning,
        notice,
    ) { completed, items, sources, isScanning, currentNotice ->
        MediaLibraryUiState(!completed, items, sources, isScanning, currentNotice)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), MediaLibraryUiState())

    fun skipOnboarding() {
        viewModelScope.launch { onboardingRepository.setCompleted(true) }
    }

    fun addRecommendedSource() {
        viewModelScope.launch { effects.send(MediaLibraryEffect.OpenAllFilesSettings) }
    }

    fun addSafSource() {
        viewModelScope.launch { effects.send(MediaLibraryEffect.OpenSafTreePicker) }
    }

    fun onAllFilesSettingsReturned() {
        viewModelScope.launch {
            val permission = permissionGateway.inspect()
            if (permission.allFilesAccess) {
                val source = MediaSource(
                    ALL_FILES_ID,
                    "设备存储",
                    MediaUri("file:///storage/emulated/0/"),
                    MediaSourceMode.ALL_FILES,
                    VolumeId("primary"),
                )
                sourceRepository.upsert(source)
                onboardingRepository.setCompleted(true)
                scan(setOf(source.id))
            } else {
                effects.send(MediaLibraryEffect.RequestMediaReadPermission)
            }
        }
    }

    fun onMediaReadPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            onboardingRepository.setCompleted(true)
            if (granted) {
                val source = MediaSource(
                    MEDIA_STORE_ID,
                    "系统媒体库",
                    MediaUri("content://media/external/video/media"),
                    MediaSourceMode.MEDIA_STORE,
                    VolumeId("external"),
                )
                sourceRepository.upsert(source)
                scan(setOf(source.id))
            } else {
                notice.value = MediaLibraryNotice.PERMISSION_DENIED
            }
        }
    }

    fun onSafTreeSelected(uri: String?) {
        viewModelScope.launch {
            if (uri == null) {
                onboardingRepository.setCompleted(true)
                return@launch
            }
            when (permissionGateway.persistSafTree(uri)) {
                is PermissionActionResult.SafTreeGranted -> {
                    val source = MediaSource(
                        MediaSourceId("saf_${uri.hashCode().toUInt()}"),
                        "已授权目录",
                        MediaUri(uri),
                        MediaSourceMode.SAF_TREE,
                        null,
                    )
                    sourceRepository.upsert(source)
                    onboardingRepository.setCompleted(true)
                    scan(setOf(source.id))
                }
                else -> notice.value = MediaLibraryNotice.PERMISSION_DENIED
            }
        }
    }

    fun rescan() {
        viewModelScope.launch {
            val sourceIds = state.value.sources.filter { it.accessState == MediaSourceAccessState.AVAILABLE }.map { it.id }.toSet()
            if (sourceIds.isNotEmpty()) scan(sourceIds)
        }
    }

    private suspend fun scan(sourceIds: Set<MediaSourceId>) {
        scanning.value = true
        notice.value = null
        try {
            val result = scanner.scan(ScanRequest(sourceIds))
            notice.value = when {
                result.failures.any { it.kind == ScanFailureKind.SOURCE_OFFLINE } -> MediaLibraryNotice.SOURCE_OFFLINE
                result.failures.isNotEmpty() -> MediaLibraryNotice.SCAN_PARTIAL
                else -> null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            notice.value = MediaLibraryNotice.SCAN_PARTIAL
        } finally {
            scanning.value = false
        }
    }

    companion object {
        private const val STOP_TIMEOUT = 5_000L
        private val ALL_FILES_ID = MediaSourceId("source_all_files")
        private val MEDIA_STORE_ID = MediaSourceId("source_media_store")

        fun factory(
            sourceRepository: MediaSourceRepository,
            catalogRepository: MediaCatalogRepository,
            permissionGateway: MediaPermissionGateway,
            scanner: MediaScanner,
            onboardingRepository: MediaOnboardingRepository,
        ) = viewModelFactory {
            initializer {
                MediaLibraryViewModel(
                    sourceRepository,
                    catalogRepository,
                    permissionGateway,
                    scanner,
                    onboardingRepository,
                )
            }
        }
    }
}
