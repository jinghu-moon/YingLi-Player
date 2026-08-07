package seeyuer.yingli.player.core.media

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

@OptIn(ExperimentalCoroutinesApi::class)
class PriorityThumbnailRepositoryTest {
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
        repository.cancel(request.cacheKey)
        advanceUntilIdle()

        assertEquals(1, calls)
        assertEquals(ThumbnailState.Queued, repository.observe(request.cacheKey).first())
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
        assertTrue(repository.observe(request.cacheKey).first() is ThumbnailState.Ready)
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

    private fun request(id: String, priority: ThumbnailPriority) = ThumbnailRequest(
        mediaItemId = MediaItemId(id),
        locationId = MediaLocationId("location_$id"),
        uri = MediaUri("content://media/video/$id"),
        widthPixels = 320,
        heightPixels = 180,
        priority = priority,
    )
}
