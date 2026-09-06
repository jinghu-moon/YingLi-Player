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
    val initialized: Boolean = false,
    val items: List<MediaItem> = emptyList(),
    val sources: List<MediaSource> = emptyList(),
    val scanning: Boolean = false,
    val scanProgress: ScanProgress = ScanProgress(),
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
    private val scanProgress = (scanner as? seeyuer.yingli.player.domain.media.ScanProgressSource)?.progress
        ?: MutableStateFlow(ScanProgress())
    private val effects = Channel<MediaLibraryEffect>(Channel.BUFFERED)
    private var initializeStarted = false
    private val initialized = MutableStateFlow(false)
    val effect: Flow<MediaLibraryEffect> = effects.receiveAsFlow()
    private val baseState = combine(
        onboardingRepository.completed,
        catalogRepository.observeItems(),
        sourceRepository.observeSources(),
        scanning,
        notice,
    ) { completed, items, sources, isScanning, currentNotice ->
        MediaLibraryUiState(
            onboarding = !completed,
            items = items,
            sources = sources,
            scanning = isScanning,
            scanProgress = ScanProgress(),
            notice = currentNotice,
        )
    }

    val state: StateFlow<MediaLibraryUiState> = combine(baseState, scanProgress, initialized) { base, progress, ready ->
        base.copy(initialized = ready, scanProgress = progress)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), MediaLibraryUiState())

    /** Restores granted access after process start and starts the initial index scan. */
    fun initialize() {
        if (initializeStarted) return
        initializeStarted = true
        viewModelScope.launch {
            // Do not expose the default onboarding state before persisted state is available.
            baseState.first()
            val permission = permissionGateway.inspect()
            when {
                permission.allFilesAccess -> {
                    val source = deviceVideoSource()
                    val existing = sourceRepository.get(source.id)
                    sourceRepository.upsert(source)
                    onboardingRepository.setCompleted(true)
                    initialized.value = true
                    if (existing.requiresInitialScan(source)) scan(setOf(source.id))
                }
                permission.mediaStoreReadAccess -> {
                    val source = deviceVideoSource()
                    val existing = sourceRepository.get(source.id)
                    sourceRepository.upsert(source)
                    onboardingRepository.setCompleted(true)
                    initialized.value = true
                    if (existing.requiresInitialScan(source)) scan(setOf(source.id))
                }
                else -> {
                    val sourceIds = sourceRepository.observeSources().first()
                        .filter { it.accessState == MediaSourceAccessState.AVAILABLE }
                        .map { it.id }
                        .toSet()
                    initialized.value = true
                    if (sourceIds.isNotEmpty() && catalogRepository.observeItems().first().isEmpty()) scan(sourceIds)
                }
            }
        }
    }

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
                val source = deviceVideoSource()
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
                val source = deviceVideoSource()
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

    private fun deviceVideoSource() = MediaSource(
        DEVICE_VIDEO_SOURCE_ID,
        "设备存储",
        MediaUri("content://media/external/video/media"),
        MediaSourceMode.MEDIA_STORE,
        VolumeId("external"),
    )

    private suspend fun MediaSource?.requiresInitialScan(expected: MediaSource): Boolean =
        this == null || mode != expected.mode || rootUri != expected.rootUri ||
            catalogRepository.snapshot(expected.id).items.isEmpty()

    companion object {
        private const val STOP_TIMEOUT = 5_000L
        // Retain the development source ID so existing local indexes converge without duplicates.
        private val DEVICE_VIDEO_SOURCE_ID = MediaSourceId("source_all_files")

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
