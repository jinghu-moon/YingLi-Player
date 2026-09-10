package seeyuer.yingli.player.domain.catalog

import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.model.media.MediaSource
import seeyuer.yingli.player.core.model.media.MediaSourceAccessState
import seeyuer.yingli.player.core.model.media.MediaSourceId
import seeyuer.yingli.player.core.model.media.MediaSourceMode
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.core.model.media.ScanRequest
import seeyuer.yingli.player.core.model.media.ScanResult

@kotlinx.coroutines.ExperimentalCoroutinesApi
class MediaScanCoordinatorTest {
    private val sourceId = MediaSourceId("source_1")
    private val request = ScanRequest(setOf(sourceId))

    @Test
    fun `concurrent ordinary scans share one active job`() = runTest {
        val scanner = ControlledScanner()
        val coordinator = coordinator(scanner, dispatcher = StandardTestDispatcher(testScheduler))

        val first = launch { coordinator.scan(request) }
        val second = launch { coordinator.scan(request) }
        advanceUntilIdle()

        assertEquals(1, scanner.calls)
        assertTrue(first.isCompleted)
        assertTrue(second.isCompleted)
    }

    @Test
    fun `force scan cancels the old job before starting a new generation`() = runTest {
        val scanner = ControlledScanner(blockFirst = true)
        val coordinator = coordinator(scanner, dispatcher = StandardTestDispatcher(testScheduler))

        val old = launch { coordinator.scan(request) }
        advanceUntilIdle()
        assertEquals(1, scanner.calls)

        val forced = launch { coordinator.scan(request, force = true) }
        advanceUntilIdle()

        assertEquals(2, scanner.calls)
        assertTrue(old.isCancelled)
        assertTrue(forced.isCompleted)
        assertEquals(2, coordinator.currentGeneration())
    }

    @Test
    fun `fresh source skips ordinary scan inside ttl`() = runTest {
        val scanner = ControlledScanner()
        val now = Instant.ofEpochMilli(10_000)
        val sourceRepository = FakeSourceRepository(source(lastSynced = 9_000))
        val coordinator = MediaScanCoordinator(
            delegate = scanner,
            sourceRepository = sourceRepository,
            clock = FixedClock(now),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        coordinator.scan(request)

        assertEquals(0, scanner.calls)
        assertEquals(0, coordinator.currentGeneration())
    }

    @Test
    fun `stale source starts a new scan`() = runTest {
        val scanner = ControlledScanner()
        val sourceRepository = FakeSourceRepository(source(lastSynced = 1_000))
        val coordinator = coordinator(
            scanner,
            sourceRepository,
            FixedClock(Instant.ofEpochMilli(5 * 60 * 1000L + 1_001)),
            StandardTestDispatcher(testScheduler),
        )

        coordinator.scan(request)

        assertEquals(1, scanner.calls)
        assertEquals(1, coordinator.currentGeneration())
    }

    private fun coordinator(
        scanner: ControlledScanner,
        sourceRepository: FakeSourceRepository = FakeSourceRepository(source()),
        clock: AppClock = FixedClock(Instant.ofEpochMilli(10_000)),
        dispatcher: CoroutineDispatcher,
    ) = MediaScanCoordinator(scanner, sourceRepository, clock, dispatcher)

    private fun source(lastSynced: Long? = null) = MediaSource(
        id = sourceId,
        displayName = "测试来源",
        rootUri = MediaUri("content://media/root"),
        mode = MediaSourceMode.MEDIA_STORE,
        volumeId = null,
        accessState = MediaSourceAccessState.AVAILABLE,
        lastSyncedEpochMillis = lastSynced,
    )

    private class FixedClock(private val value: Instant) : AppClock {
        override fun now(): Instant = value
    }

    private class FakeSourceRepository(initial: MediaSource) : MediaSourceRepository {
        private val state = MutableStateFlow(listOf(initial))
        override fun observeSources(): Flow<List<MediaSource>> = state.asStateFlow()
        override suspend fun get(sourceId: MediaSourceId): MediaSource? = state.value.firstOrNull { it.id == sourceId }
        override suspend fun upsert(source: MediaSource) {
            state.value = state.value.filterNot { it.id == source.id } + source
        }
        override suspend fun markAccessState(sourceId: MediaSourceId, state: MediaSourceAccessState) {
            this.state.value = this.state.value.map { if (it.id == sourceId) it.copy(accessState = state) else it }
        }
    }

    private class ControlledScanner(
        private val blockFirst: Boolean = false,
    ) : MediaScanner {
        var calls = 0
        private val firstGate = CompletableDeferred<Unit>()

        override suspend fun scan(request: ScanRequest): ScanResult {
            calls++
            if (blockFirst && calls == 1) {
                try {
                    firstGate.await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                }
            }
            return ScanResult(0, 0, 0, 0, emptyList(), 0)
        }
    }
}
