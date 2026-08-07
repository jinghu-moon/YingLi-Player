package seeyuer.yingli.player.core.media

import android.content.Context
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.video.VideoFrameDecoder
import java.util.PriorityQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import seeyuer.yingli.player.core.model.media.ThumbnailRequest

fun interface ThumbnailExtractor {
    suspend fun extract(request: ThumbnailRequest): Boolean
}

class CoilThumbnailExtractor(
    context: Context,
) : ThumbnailExtractor {
    private val appContext = context.applicationContext
    private val imageLoader = ImageLoader.Builder(appContext)
        .components { add(VideoFrameDecoder.Factory()) }
        .build()

    override suspend fun extract(request: ThumbnailRequest): Boolean {
        val imageRequest = ImageRequest.Builder(appContext)
            .data(request.uri.value)
            .size(request.widthPixels, request.heightPixels)
            .build()
        return imageLoader.execute(imageRequest) is SuccessResult
    }
}

class PriorityThumbnailRepository(
    private val scope: CoroutineScope,
    private val extractor: ThumbnailExtractor,
    private val maxConcurrent: Int = 2,
    private val maxCachedKeys: Int = 256,
) : ThumbnailRepository {
    private data class Pending(val request: ThumbnailRequest, val sequence: Long, val attempts: Int = 0)

    private val pending = PriorityQueue<Pending>(compareBy<Pending> { it.request.priority.rank }.thenBy { it.sequence })
    private val states = mutableMapOf<String, MutableStateFlow<ThumbnailState>>()
    private val running = mutableMapOf<String, Job>()
    private val cachedKeys = LinkedHashMap<String, Unit>(maxCachedKeys, 0.75f, true)
    private var sequence = 0L

    init {
        require(maxConcurrent > 0)
        require(maxCachedKeys > 0)
    }

    @Synchronized
    override fun observe(cacheKey: String): Flow<ThumbnailState> =
        states.getOrPut(cacheKey) { MutableStateFlow(ThumbnailState.Queued) }.asStateFlow()

    @Synchronized
    override fun enqueue(request: ThumbnailRequest) {
        if (request.cacheKey in cachedKeys) {
            state(request.cacheKey).value = ThumbnailState.Ready(request.cacheKey)
            return
        }
        pending.removeAll { it.request.cacheKey == request.cacheKey }
        pending += Pending(request, sequence++)
        state(request.cacheKey).value = ThumbnailState.Queued
        pump()
    }

    @Synchronized
    override fun cancel(cacheKey: String) {
        pending.removeAll { it.request.cacheKey == cacheKey }
        running.remove(cacheKey)?.cancel()
        states.remove(cacheKey)
        pump()
    }

    @Synchronized
    private fun pump() {
        while (running.size < maxConcurrent && pending.isNotEmpty()) {
            val task = pending.remove()
            val key = task.request.cacheKey
            if (key in running) continue
            state(key).value = ThumbnailState.Loading
            running[key] = scope.launch {
                val success = try {
                    extractor.extract(task.request)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                complete(task, success)
            }
        }
    }

    @Synchronized
    private fun complete(task: Pending, success: Boolean) {
        val key = task.request.cacheKey
        running.remove(key)
        if (success) {
            cachedKeys[key] = Unit
            while (cachedKeys.size > maxCachedKeys) cachedKeys.remove(cachedKeys.keys.first())
            state(key).value = ThumbnailState.Ready(key)
        } else if (task.attempts < MAX_RETRIES) {
            pending += task.copy(sequence = sequence++, attempts = task.attempts + 1)
            state(key).value = ThumbnailState.Queued
        } else {
            state(key).value = ThumbnailState.Failed
        }
        pump()
    }

    private fun state(key: String): MutableStateFlow<ThumbnailState> =
        states.getOrPut(key) { MutableStateFlow(ThumbnailState.Queued) }

    private companion object { const val MAX_RETRIES = 1 }
}
