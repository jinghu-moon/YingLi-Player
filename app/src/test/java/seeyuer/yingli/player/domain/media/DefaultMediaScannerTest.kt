package seeyuer.yingli.player.domain.media

import kotlin.system.measureTimeMillis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.media.CatalogMutation
import seeyuer.yingli.player.core.media.CatalogSnapshot
import seeyuer.yingli.player.core.media.MediaCatalogRepository
import seeyuer.yingli.player.core.media.MediaContentHasher
import seeyuer.yingli.player.core.media.MediaDiscoveryDataSource
import seeyuer.yingli.player.core.media.MediaDiscoveryEvent
import seeyuer.yingli.player.core.media.MediaSourceRepository
import seeyuer.yingli.player.core.model.media.MediaCandidate
import seeyuer.yingli.player.core.model.media.MediaIdentityEvidence
import seeyuer.yingli.player.core.model.media.MediaItem
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocation
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaSource
import seeyuer.yingli.player.core.model.media.MediaSourceAccessState
import seeyuer.yingli.player.core.model.media.MediaSourceId
import seeyuer.yingli.player.core.model.media.MediaSourceMode
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.core.model.media.ScanFailure
import seeyuer.yingli.player.core.model.media.ScanFailureKind
import seeyuer.yingli.player.core.model.media.ScanRequest
import seeyuer.yingli.player.core.model.media.VolumeId
import seeyuer.yingli.player.testing.FakeAppClock
import seeyuer.yingli.player.testing.SequenceIdGenerator

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultMediaScannerTest {
    @Test
    fun `incremental scan links a second physical location to the existing logical item`() = runTest {
        val existingLocation = location("location_existing", "content://media/video/existing")
        val existingItem = MediaItem(MediaItemId("item_existing"), "影片")
        val catalog = FakeCatalogRepository(CatalogSnapshot(
            items = listOf(existingItem),
            locations = listOf(existingLocation),
            itemByLocation = mapOf(existingLocation.id to existingItem.id),
        ))
        val scanner = scanner(catalog, listOf(MediaDiscoveryEvent.Candidate(candidate("duplicate"))))

        val result = scanner.scan(ScanRequest(setOf(SOURCE.id)))

        val mutation = catalog.mutations.single()
        val newLocation = mutation.upsertLocations.single()
        assertEquals(existingItem.id, mutation.links.getValue(newLocation.id))
        assertFalse(newLocation.id == existingLocation.id)
        assertEquals(1, result.added)
    }

    @Test
    fun `same scan can link matching physical locations to one logical item`() = runTest {
        val catalog = FakeCatalogRepository(CatalogSnapshot(emptyList(), emptyList(), emptyMap()))
        val scanner = scanner(catalog, listOf(
            MediaDiscoveryEvent.Candidate(candidate("first")),
            MediaDiscoveryEvent.Candidate(candidate("second")),
        ))

        scanner.scan(ScanRequest(setOf(SOURCE.id)))

        val mutation = catalog.mutations.single()
        assertEquals(2, mutation.upsertLocations.size)
        assertEquals(1, mutation.links.values.toSet().size)
    }

    @Test
    fun `permission loss preserves previous index and does not mark entries missing`() = runTest {
        val existingLocation = location("location_existing", "content://media/video/existing")
        val catalog = FakeCatalogRepository(CatalogSnapshot(
            items = listOf(MediaItem(MediaItemId("item_existing"), "影片")),
            locations = listOf(existingLocation),
            itemByLocation = mapOf(existingLocation.id to MediaItemId("item_existing")),
        ))
        val sourceRepository = FakeSourceRepository(SOURCE)
        val failure = ScanFailure(SOURCE.id, ScanFailureKind.PERMISSION, recoverable = true)
        val scanner = scanner(catalog, listOf(MediaDiscoveryEvent.Failure(failure)), sourceRepository)

        val result = scanner.scan(ScanRequest(setOf(SOURCE.id)))

        assertFalse(catalog.mutations.single().markMissing)
        assertEquals(0, result.missing)
        assertEquals(MediaSourceAccessState.PERMISSION_LOST, sourceRepository.sources.value.single().accessState)
    }

    @Test
    fun `cancelling discovery never commits a partial mutation`() = runTest {
        val catalog = FakeCatalogRepository(CatalogSnapshot(emptyList(), emptyList(), emptyMap()))
        val dataSource = object : MediaDiscoveryDataSource {
            override val mode = MediaSourceMode.MEDIA_STORE
            override fun discover(source: MediaSource): Flow<MediaDiscoveryEvent> = flow {
                emit(MediaDiscoveryEvent.Candidate(candidate("first")))
                awaitCancellation()
            }
        }
        val scanner = createScanner(catalog, dataSource, FakeSourceRepository(SOURCE))

        val scanJob = launch { scanner.scan(ScanRequest(setOf(SOURCE.id))) }
        runCurrent()
        scanJob.cancel()
        scanJob.join()

        assertTrue(catalog.mutations.isEmpty())
    }

    @Test
    fun `full content hash runs only for conflicting identity candidates`() = runTest {
        val first = location("location_1", "content://media/video/one")
        val second = location("location_2", "content://media/video/two")
        val catalog = FakeCatalogRepository(CatalogSnapshot(
            items = listOf(
                MediaItem(MediaItemId("item_1"), "影片一"),
                MediaItem(MediaItemId("item_2"), "影片二"),
            ),
            locations = listOf(first, second),
            itemByLocation = mapOf(first.id to MediaItemId("item_1"), second.id to MediaItemId("item_2")),
        ))
        val hashes = mutableListOf<String>()
        val hasher = MediaContentHasher { uri ->
            hashes += uri.value
            if (uri.value.endsWith("two")) "different-content" else "same-content"
        }
        val scanner = createScanner(
            catalog = catalog,
            dataSource = EventDataSource(listOf(MediaDiscoveryEvent.Candidate(candidate("candidate")))),
            sourceRepository = FakeSourceRepository(SOURCE),
            contentHasher = hasher,
        )

        scanner.scan(ScanRequest(setOf(SOURCE.id)))

        val mutation = catalog.mutations.single()
        assertEquals(MediaItemId("item_1"), mutation.links.values.single())
        assertEquals("same-content", mutation.upsertLocations.single().contentHash)
        assertEquals(3, hashes.size)
        assertEquals(2, mutation.evidenceUpdates.size)
    }

    @Test
    fun `one thousand synthetic candidates remain a lightweight index operation`() = runTest {
        assertSyntheticIndexWithinTarget(count = 1_000, targetMillis = 3_000)
    }

    @Test
    fun `ten thousand synthetic candidates meet the incremental index target`() = runTest {
        assertSyntheticIndexWithinTarget(count = 10_000, targetMillis = 3_000)
    }

    @Test
    fun `ten thousand unchanged locations use the exact uri index`() = runTest {
        val count = 10_000
        val locations = (0 until count).map { index ->
            location("location_$index", "content://media/video/$index")
        }
        val items = locations.mapIndexed { index, _ ->
            MediaItem(MediaItemId("item_$index"), "movie_$index")
        }
        val catalog = FakeCatalogRepository(CatalogSnapshot(
            items = items,
            locations = locations,
            itemByLocation = locations.indices.associate { index -> locations[index].id to items[index].id },
        ))
        val events = (0 until count).map { index ->
            MediaDiscoveryEvent.Candidate(candidate(index.toString(), distinctEvidence = true))
        }
        val scanner = scanner(catalog, events)

        val elapsed = measureTimeMillis { scanner.scan(ScanRequest(setOf(SOURCE.id))) }

        assertEquals(count, catalog.mutations.single().upsertLocations.size)
        assertTrue("$count unchanged locations took ${elapsed}ms", elapsed < 3_000)
    }

    @Test
    fun `ten thousand relocated locations use bounded identity buckets`() = runTest {
        val count = 10_000
        val locations = (0 until count).map { index ->
            location("location_$index", "file:///storage/emulated/0/movie_$index.mp4").copy(
                sizeBytes = 10_000L + index,
                modifiedEpochMillis = 1_000L + index,
                durationMillis = 30_000L + index,
            )
        }
        val items = locations.mapIndexed { index, _ ->
            MediaItem(MediaItemId("item_$index"), "movie_$index")
        }
        val catalog = FakeCatalogRepository(CatalogSnapshot(
            items = items,
            locations = locations,
            itemByLocation = locations.indices.associate { index -> locations[index].id to items[index].id },
        ))
        val events = (0 until count).map { index ->
            MediaDiscoveryEvent.Candidate(candidate(index.toString(), distinctEvidence = true))
        }
        val scanner = scanner(catalog, events)

        val elapsed = measureTimeMillis { scanner.scan(ScanRequest(setOf(SOURCE.id))) }

        val mutation = catalog.mutations.single()
        assertEquals(count, mutation.upsertLocations.size)
        assertEquals(items.map(MediaItem::id).toSet(), mutation.links.values.toSet())
        assertTrue("$count relocated locations took ${elapsed}ms", elapsed < 3_000)
    }

    private suspend fun assertSyntheticIndexWithinTarget(count: Int, targetMillis: Long) {
        val catalog = FakeCatalogRepository(CatalogSnapshot(emptyList(), emptyList(), emptyMap()))
        val events = (0 until count).map { index ->
            MediaDiscoveryEvent.Candidate(candidate(index.toString(), distinctEvidence = true))
        }
        val scanner = scanner(catalog, events)

        val elapsed = measureTimeMillis { scanner.scan(ScanRequest(setOf(SOURCE.id))) }

        assertEquals(count, catalog.mutations.single().upsertLocations.size)
        assertTrue("$count candidates took ${elapsed}ms", elapsed < targetMillis)
    }

    private fun scanner(
        catalog: FakeCatalogRepository,
        events: List<MediaDiscoveryEvent>,
        sourceRepository: FakeSourceRepository = FakeSourceRepository(SOURCE),
    ): DefaultMediaScanner = createScanner(catalog, EventDataSource(events), sourceRepository)

    private fun createScanner(
        catalog: FakeCatalogRepository,
        dataSource: MediaDiscoveryDataSource,
        sourceRepository: FakeSourceRepository,
        contentHasher: MediaContentHasher = MediaContentHasher.None,
    ) = DefaultMediaScanner(
        dataSources = setOf(dataSource),
        sourceRepository = sourceRepository,
        catalogRepository = catalog,
        identityResolver = DefaultMediaIdentityResolver,
        contentHasher = contentHasher,
        idGenerator = SequenceIdGenerator(),
        clock = FakeAppClock(),
    )

    private class EventDataSource(private val events: List<MediaDiscoveryEvent>) : MediaDiscoveryDataSource {
        override val mode = MediaSourceMode.MEDIA_STORE
        override fun discover(source: MediaSource): Flow<MediaDiscoveryEvent> = events.asFlow()
    }

    private class FakeSourceRepository(initial: MediaSource) : MediaSourceRepository {
        val sources = MutableStateFlow(listOf(initial))
        override fun observeSources(): Flow<List<MediaSource>> = sources
        override suspend fun get(sourceId: MediaSourceId): MediaSource? = sources.value.firstOrNull { it.id == sourceId }
        override suspend fun upsert(source: MediaSource) {
            sources.value = sources.value.filterNot { it.id == source.id } + source
        }
        override suspend fun markAccessState(sourceId: MediaSourceId, state: MediaSourceAccessState) {
            sources.value = sources.value.map { if (it.id == sourceId) it.copy(accessState = state) else it }
        }
    }

    private class FakeCatalogRepository(private val current: CatalogSnapshot) : MediaCatalogRepository {
        val mutations = mutableListOf<CatalogMutation>()
        override fun observeItems(): Flow<List<MediaItem>> = MutableStateFlow(current.items)
        override suspend fun snapshot(sourceId: MediaSourceId): CatalogSnapshot = current
        override suspend fun applyMutation(sourceId: MediaSourceId, mutation: CatalogMutation): Pair<Int, Int> {
            mutations += mutation
            val existingIds = current.locations.map { it.id }.toSet()
            val added = mutation.upsertLocations.count { it.id !in existingIds }
            return added to (mutation.upsertLocations.size - added)
        }
    }

    private fun candidate(id: String, distinctEvidence: Boolean = false): MediaCandidate {
        val numeric = id.toLongOrNull() ?: 0L
        return MediaCandidate(
            sourceId = SOURCE.id,
            evidence = MediaIdentityEvidence(
                uri = MediaUri("content://media/video/$id"),
                volumeId = VolumeId("primary"),
                documentId = id,
                fileName = "movie_$id.mp4",
                sizeBytes = if (distinctEvidence) 10_000 + numeric else 1_024,
                modifiedEpochMillis = if (distinctEvidence) 1_000 + numeric else 100,
                durationMillis = if (distinctEvidence) 30_000 + numeric else 5_000,
                width = 1_920,
                height = 1_080,
            ),
            mimeType = "video/mp4",
        )
    }

    private fun location(id: String, uri: String) = MediaLocation(
        id = MediaLocationId(id),
        sourceId = SOURCE.id,
        uri = MediaUri(uri),
        volumeId = VolumeId("primary"),
        documentId = id,
        fileName = "movie.mp4",
        mimeType = "video/mp4",
        sizeBytes = 1_024,
        modifiedEpochMillis = 100,
        durationMillis = 5_000,
        width = 1_920,
        height = 1_080,
        lastSeenEpochMillis = 200,
    )

    private companion object {
        val SOURCE = MediaSource(
            id = MediaSourceId("source_1"),
            displayName = "系统媒体库",
            rootUri = MediaUri("content://media/external/video/media"),
            mode = MediaSourceMode.MEDIA_STORE,
            volumeId = VolumeId("primary"),
        )
    }
}
