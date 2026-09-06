package seeyuer.yingli.player.engine.thumbnail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.core.model.media.ThumbnailPriority
import seeyuer.yingli.player.core.model.media.ThumbnailRequest
import seeyuer.yingli.player.core.model.media.ScrollDirection
import seeyuer.yingli.player.domain.thumbnail.ThumbnailState

@OptIn(ExperimentalCoroutinesApi::class)
class PriorityThumbnailRepositoryTest {
    @Test
    fun `fallback extractor is used after primary failure`() = runTest {
        var fallbackCalls = 0
        val fallback = FallbackThumbnailExtractor(
            ThumbnailExtractor { false },
            ThumbnailExtractor { fallbackCalls++; true },
        )

        assertTrue(fallback.extract(request("fallback", ThumbnailPriority.VISIBLE)))
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun `visible pending work runs before background pending work`() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val repository = PriorityThumbnailRepository(
            scope = this,
            extractor = ThumbnailExtractor { request ->
                calls += request.mediaItemId.value
                if (calls.size == 1) firstGate.await()
                true
            },
            maxConcurrent = 1,
        )

        repository.enqueue(request("running", ThumbnailPriority.BACKGROUND))
        runCurrent()
        repository.enqueue(request("background", ThumbnailPriority.BACKGROUND))
        repository.enqueue(request("visible", ThumbnailPriority.VISIBLE))
        firstGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("running", "visible", "background"), calls)
    }

    @Test
    fun `cancelled running work is not retried`() = runTest {
        var calls = 0
        val repository = PriorityThumbnailRepository(
            scope = this,
            extractor = ThumbnailExtractor {
                calls++
                awaitCancellation()
            },
            maxConcurrent = 1,
        )
        val request = request("visible", ThumbnailPriority.VISIBLE)

        repository.enqueue(request)
        runCurrent()
        repository.cancel(request.key)
        advanceUntilIdle()

        assertEquals(1, calls)
        assertEquals(ThumbnailState.Queued, repository.observe(request.key).first())
    }

    @Test
    fun `disposed visible item is removed before it reaches the extractor`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val repository = PriorityThumbnailRepository(
            scope = this,
            extractor = ThumbnailExtractor {
                calls += it.mediaItemId.value
                if (it.mediaItemId.value == "blocker") gate.await()
                true
            },
            maxConcurrent = 1,
        )
        val stale = request("stale", ThumbnailPriority.VISIBLE)

        repository.request(request("blocker", ThumbnailPriority.VISIBLE))
        runCurrent()
        repository.request(stale)
        repository.cancel(stale)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("blocker"), calls)
    }

    @Test
    fun `failed extraction retries once and then succeeds`() = runTest {
        var calls = 0
        val request = request("retry", ThumbnailPriority.VISIBLE)
        val repository = PriorityThumbnailRepository(
            scope = this,
            extractor = ThumbnailExtractor { ++calls == 2 },
        )

        repository.enqueue(request)
        advanceUntilIdle()

        assertEquals(2, calls)
        assertTrue(repository.observe(request.key).first() is ThumbnailState.Ready)
    }

    @Test
    fun `least recently used key is extracted again after cache eviction`() = runTest {
        var calls = 0
        val first = request("first", ThumbnailPriority.VISIBLE)
        val second = request("second", ThumbnailPriority.VISIBLE)
        val repository = PriorityThumbnailRepository(
            scope = this,
            extractor = ThumbnailExtractor { calls++; true },
            maxCachedKeys = 1,
        )

        repository.enqueue(first)
        advanceUntilIdle()
        repository.enqueue(second)
        advanceUntilIdle()
        repository.enqueue(first)
        advanceUntilIdle()

        assertEquals(3, calls)
    }

    @Test
    fun `visible request promotes a queued prefetch without duplicate work`() = runTest {
        val calls = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val request = request("same", ThumbnailPriority.BACKGROUND)
        val repository = PriorityThumbnailRepository(
            scope = this,
            extractor = ThumbnailExtractor {
                calls += it.mediaItemId.value
                gate.await()
                true
            },
            maxConcurrent = 1,
        )

        repository.prefetch(listOf(request), ScrollDirection.FORWARD, generation = 1)
        repository.requestVisible(listOf(request.copy(priority = ThumbnailPriority.VISIBLE)))
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("same"), calls)
        assertTrue(repository.observe(request).first() is ThumbnailState.Ready)
    }

    @Test
    fun `replacing a prefetch window drops obsolete pending work`() = runTest {
        val calls = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val repository = PriorityThumbnailRepository(
            scope = this,
            extractor = ThumbnailExtractor {
                calls += it.mediaItemId.value
                if (it.mediaItemId.value == "blocker") gate.await()
                true
            },
            maxConcurrent = 1,
        )
        repository.requestVisible(listOf(request("blocker", ThumbnailPriority.VISIBLE)))
        runCurrent()
        repository.prefetch(
            listOf(request("old", ThumbnailPriority.BACKGROUND), request("keep", ThumbnailPriority.BACKGROUND)),
            ScrollDirection.FORWARD,
            generation = 2,
        )
        repository.prefetch(
            listOf(request("keep", ThumbnailPriority.BACKGROUND)),
            ScrollDirection.FORWARD,
            generation = 2,
        )
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("blocker", "keep"), calls)
    }

    @Test
    fun `background prefetch keeps a slot for visible work`() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val repository = PriorityThumbnailRepository(
            scope = this,
            extractor = ThumbnailExtractor {
                calls += it.mediaItemId.value
                if (it.mediaItemId.value == "background") firstGate.await()
                true
            },
            maxConcurrent = 2,
        )
        repository.prefetch(
            listOf(request("background", ThumbnailPriority.BACKGROUND), request("background2", ThumbnailPriority.BACKGROUND)),
            ScrollDirection.FORWARD,
            generation = 1,
        )
        runCurrent()
        repository.requestVisible(listOf(request("visible", ThumbnailPriority.VISIBLE)))
        runCurrent()

        assertEquals(listOf("background", "visible"), calls)
        firstGate.complete(Unit)
        advanceUntilIdle()
    }

    private fun request(id: String, priority: ThumbnailPriority) = ThumbnailRequest(
        mediaItemId = MediaItemId(id),
        locationId = MediaLocationId("location_$id"),
        uri = MediaUri("content://media/video/$id"),
        widthPixels = 320,
        heightPixels = 180,
        priority = priority,
    )
}
