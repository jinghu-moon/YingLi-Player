package seeyuer.yingli.player.feature.library

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import seeyuer.yingli.player.data.preferences.MediaOnboardingRepository
import seeyuer.yingli.player.domain.catalog.CatalogMutation
import seeyuer.yingli.player.domain.catalog.CatalogSnapshot
import seeyuer.yingli.player.domain.catalog.MediaCatalogRepository
import seeyuer.yingli.player.domain.catalog.MediaPermissionGateway
import seeyuer.yingli.player.domain.catalog.MediaPermissionSnapshot
import seeyuer.yingli.player.domain.catalog.MediaSourceRepository
import seeyuer.yingli.player.domain.catalog.PermissionActionResult
import seeyuer.yingli.player.core.model.media.MediaItem
import seeyuer.yingli.player.core.model.media.MediaSource
import seeyuer.yingli.player.core.model.media.MediaSourceAccessState
import seeyuer.yingli.player.core.model.media.MediaSourceId
import seeyuer.yingli.player.core.model.media.MediaSourceMode
import seeyuer.yingli.player.core.model.media.ScanFailure
import seeyuer.yingli.player.core.model.media.ScanFailureKind
import seeyuer.yingli.player.core.model.media.ScanRequest
import seeyuer.yingli.player.core.model.media.ScanResult
import seeyuer.yingli.player.domain.catalog.MediaScanner
import seeyuer.yingli.player.testing.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class MediaLibraryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `cancelled saf picker exits onboarding into recoverable empty home`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val onboarding = FakeOnboardingRepository()
        val viewModel = viewModel(onboarding = onboarding)
        val collector = backgroundScope.launch { viewModel.state.collect {} }

        viewModel.onSafTreeSelected(null)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.onboarding)
        collector.cancel()
    }

    @Test
    fun `denied media permission keeps saf recovery available through notice state`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val viewModel = viewModel()
        val collector = backgroundScope.launch { viewModel.state.collect {} }

        viewModel.onMediaReadPermissionResult(false)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.onboarding)
        assertEquals(MediaLibraryNotice.PERMISSION_DENIED, viewModel.state.value.notice)
        collector.cancel()
    }

    @Test
    fun `offline scan maps to non blocking source notice`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val sourceId = MediaSourceId("source_1")
        val sourceRepository = FakeSourceRepository()
        val scanner = MediaScanner {
            ScanResult(
                added = 0,
                updated = 0,
                missing = 0,
                unsupported = 0,
                failures = listOf(ScanFailure(sourceId, ScanFailureKind.SOURCE_OFFLINE, true)),
                elapsedMillis = 1,
            )
        }
        val viewModel = viewModel(sourceRepository = sourceRepository, scanner = scanner)
        val collector = backgroundScope.launch { viewModel.state.collect {} }
        sourceRepository.sources.value = listOf(testSource(sourceId))
        advanceUntilIdle()

        viewModel.rescan()
        advanceUntilIdle()

        assertEquals(MediaLibraryNotice.SOURCE_OFFLINE, viewModel.state.value.notice)
        assertFalse(viewModel.state.value.scanning)
        collector.cancel()
    }

    @Test
    fun `startup with all files permission restores source and starts indexing`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val onboarding = FakeOnboardingRepository()
        val sourceRepository = FakeSourceRepository()
        var scanCount = 0
        val viewModel = viewModel(
            onboarding = onboarding,
            sourceRepository = sourceRepository,
            permissionGateway = FakePermissionGateway(MediaPermissionSnapshot(true, false, emptySet())),
            scanner = MediaScanner {
                scanCount++
                ScanResult(0, 0, 0, 0, emptyList(), 1)
            },
        )
        val collector = backgroundScope.launch { viewModel.state.collect {} }

        viewModel.initialize()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.onboarding)
        assertEquals(1, scanCount)
        val source = sourceRepository.sources.value.single()
        assertEquals(MediaSourceMode.MEDIA_STORE, source.mode)
        assertEquals("content://media/external/video/media", source.rootUri.value)
        collector.cancel()
    }

    @Test
    fun `startup migrates an indexed file source to MediaStore and rescans`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val staleSource = MediaSource(
            id = MediaSourceId("source_all_files"),
            displayName = "设备存储",
            rootUri = seeyuer.yingli.player.core.model.media.MediaUri("file:///storage/emulated/0/"),
            mode = MediaSourceMode.ALL_FILES,
            volumeId = null,
        )
        val sourceRepository = FakeSourceRepository().also { it.sources.value = listOf(staleSource) }
        var scanCount = 0
        val viewModel = MediaLibraryViewModel(
            sourceRepository = sourceRepository,
            catalogRepository = FakeCatalogRepository(listOf(MediaItem(seeyuer.yingli.player.core.model.media.MediaItemId("item_1"), "movie"))),
            permissionGateway = FakePermissionGateway(MediaPermissionSnapshot(true, false, emptySet())),
            scanner = MediaScanner {
                scanCount++
                ScanResult(0, 0, 0, 0, emptyList(), 1)
            },
            onboardingRepository = FakeOnboardingRepository(),
        )
        val collector = backgroundScope.launch { viewModel.state.collect {} }

        viewModel.initialize()
        advanceUntilIdle()

        assertEquals(1, scanCount)
        assertEquals(MediaSourceMode.MEDIA_STORE, sourceRepository.sources.value.single().mode)
        collector.cancel()
    }

    private fun viewModel(
        onboarding: FakeOnboardingRepository = FakeOnboardingRepository(),
        sourceRepository: FakeSourceRepository = FakeSourceRepository(),
        scanner: MediaScanner = MediaScanner { ScanResult(0, 0, 0, 0, emptyList(), 0) },
        permissionGateway: FakePermissionGateway = FakePermissionGateway(),
    ) = MediaLibraryViewModel(
        sourceRepository = sourceRepository,
        catalogRepository = FakeCatalogRepository(),
        permissionGateway = permissionGateway,
        scanner = scanner,
        onboardingRepository = onboarding,
    )

    private class FakeOnboardingRepository : MediaOnboardingRepository {
        override val completed = MutableStateFlow(false)
        override suspend fun setCompleted(completed: Boolean) {
            this.completed.value = completed
        }
    }

    private class FakeSourceRepository : MediaSourceRepository {
        val sources = MutableStateFlow<List<MediaSource>>(emptyList())
        override fun observeSources(): Flow<List<MediaSource>> = sources
        override suspend fun get(sourceId: MediaSourceId): MediaSource? = sources.value.firstOrNull { it.id == sourceId }
        override suspend fun upsert(source: MediaSource) {
            sources.value = sources.value.filterNot { it.id == source.id } + source
        }
        override suspend fun markAccessState(sourceId: MediaSourceId, state: MediaSourceAccessState) = Unit
    }

    private class FakeCatalogRepository(initialItems: List<MediaItem> = emptyList()) : MediaCatalogRepository {
        private val items = MutableStateFlow(initialItems)
        override fun observeItems(): Flow<List<MediaItem>> = items
        override suspend fun snapshot(sourceId: MediaSourceId) = CatalogSnapshot(items.value, emptyList(), emptyMap())
        override suspend fun applyMutation(sourceId: MediaSourceId, mutation: CatalogMutation) = 0 to 0
    }

    private class FakePermissionGateway(
        private val snapshot: MediaPermissionSnapshot = MediaPermissionSnapshot(false, false, emptySet()),
    ) : MediaPermissionGateway {
        override suspend fun inspect() = snapshot
        override suspend fun persistSafTree(uri: String): PermissionActionResult = PermissionActionResult.SafTreeGranted(uri)
        override suspend fun isSafTreeAccessible(uri: String) = true
    }

    private fun testSource(id: MediaSourceId) = MediaSource(
        id = id,
        displayName = "系统媒体库",
        rootUri = seeyuer.yingli.player.core.model.media.MediaUri("content://media/external/video/media"),
        mode = seeyuer.yingli.player.core.model.media.MediaSourceMode.MEDIA_STORE,
        volumeId = null,
    )
}
