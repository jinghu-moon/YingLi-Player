package seeyuer.yingli.player.engine.thumbnail

import android.content.Context
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.video.VideoFrameDecoder
import java.util.PriorityQueue
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import seeyuer.yingli.player.core.model.media.ThumbnailKey
import seeyuer.yingli.player.core.model.media.ThumbnailRequest
import seeyuer.yingli.player.core.model.media.ThumbnailPriority
import seeyuer.yingli.player.core.model.media.ScrollDirection
import seeyuer.yingli.player.domain.thumbnail.ThumbnailLoader
import seeyuer.yingli.player.domain.thumbnail.ThumbnailRepository
import seeyuer.yingli.player.domain.thumbnail.ThumbnailState

fun interface ThumbnailExtractor {
    suspend fun extract(request: ThumbnailRequest): Boolean
}

class FallbackThumbnailExtractor(
    private val primary: ThumbnailExtractor,
    private val fallback: ThumbnailExtractor,
) : ThumbnailExtractor {
    override suspend fun extract(request: ThumbnailRequest): Boolean =
        if (primary.extract(request)) true else fallback.extract(request)
}

interface ThumbnailCache {
    fun contains(key: ThumbnailKey): Boolean
    fun put(key: ThumbnailKey)
}

class MemoryThumbnailCache(private val maxSize: Int = 256) : ThumbnailCache {
    private val entries = object : LinkedHashMap<ThumbnailKey, Unit>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ThumbnailKey, Unit>?): Boolean = size > maxSize
    }

    @Synchronized override fun contains(key: ThumbnailKey): Boolean = entries.containsKey(key)
    @Synchronized override fun put(key: ThumbnailKey) { entries[key] = Unit }
}

object ThumbnailStorage {
    const val DIRECTORY_NAME = "thumbnail-files"
    const val MAX_DISK_BYTES = 256L * 1024 * 1024
}

/**
 * Checks the shared on-disk thumbnail output used by the provider and Media3
 * extractors. The extractor owns writing the file; this cache only makes an
 * existing file reusable after process restarts.
 */
class DiskThumbnailCache(
    private val directory: File,
    private val maxBytes: Long = ThumbnailStorage.MAX_DISK_BYTES,
) : ThumbnailCache {
    private var writesSinceTrim = TRIM_EVERY_WRITES - 1

    override fun contains(key: ThumbnailKey): Boolean {
        val file = directory.resolve("${key.diskName()}.png")
        if (!file.isFile) return false
        return true
    }

    override fun put(key: ThumbnailKey) {
        val shouldTrim = synchronized(this) {
            writesSinceTrim++
            if (writesSinceTrim < TRIM_EVERY_WRITES) false else {
                writesSinceTrim = 0
                true
            }
        }
        if (!shouldTrim) return
        val files = directory.listFiles { file -> file.isFile && file.extension == "png" }
            ?.sortedBy(File::lastModified)
            ?: return
        var total = files.sumOf(File::length)
        for (file in files) {
            if (total <= maxBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private companion object {
        const val TRIM_EVERY_WRITES = 256
    }
}

class LayeredThumbnailCache(
    private val memory: ThumbnailCache,
    private val disk: ThumbnailCache,
) : ThumbnailCache {
    override fun contains(key: ThumbnailKey): Boolean {
        if (memory.contains(key)) return true
        if (!disk.contains(key)) return false
        memory.put(key)
        return true
    }
    override fun put(key: ThumbnailKey) {
        memory.put(key)
        disk.put(key)
    }
}

class CoilThumbnailExtractor(
    context: Context,
    private val sharedImageLoader: ImageLoader? = null,
) : ThumbnailExtractor {
    private val appContext = context.applicationContext
    private val imageLoader = sharedImageLoader ?: ImageLoader.Builder(appContext)
        .components { add(VideoFrameDecoder.Factory()) }
        .build()

    override suspend fun extract(request: ThumbnailRequest): Boolean {
        val imageRequest = ImageRequest.Builder(appContext)
            .data(request.uri.value)
            .size(request.widthPixels, request.heightPixels)
            .memoryCacheKey(request.key.diskName())
            .diskCacheKey(request.key.diskName())
            .build()
        return imageLoader.execute(imageRequest) is SuccessResult
    }
}

class PriorityThumbnailRepository(
    private val scope: CoroutineScope,
    private val extractor: ThumbnailExtractor,
    private val maxConcurrent: Int = 2,
    private val maxCachedKeys: Int = 256,
    cache: ThumbnailCache? = null,
) : ThumbnailRepository, ThumbnailLoader {
    private data class Pending(
        val request: ThumbnailRequest,
        val sequence: Long,
        val generation: Long?,
        val attempts: Int = 0,
    )

    private data class Running(
        val request: ThumbnailRequest,
        val generation: Long?,
        val job: Job,
    )

    private val pending = PriorityQueue<Pending>(compareBy<Pending> { it.request.priority.rank }.thenBy { it.sequence })
    private val states = mutableMapOf<ThumbnailKey, MutableStateFlow<ThumbnailState>>()
    private val running = mutableMapOf<ThumbnailKey, Running>()
    private val failedKeys = mutableSetOf<ThumbnailKey>()
    private val thumbnailCache: ThumbnailCache = cache ?: MemoryThumbnailCache(maxCachedKeys)
    private var sequence = 0L

    init {
        require(maxConcurrent > 0)
        require(maxCachedKeys > 0)
    }

    @Synchronized
    override fun observe(key: ThumbnailKey): Flow<ThumbnailState> =
        states.getOrPut(key) { MutableStateFlow(ThumbnailState.Queued) }.asStateFlow()

    @Synchronized
    override fun enqueue(request: ThumbnailRequest) {
        if (request.key in failedKeys) {
            state(request.key).value = ThumbnailState.Failed
            return
        }
        if (thumbnailCache.contains(request.key)) {
            state(request.key).value = ThumbnailState.Ready(request.key)
            return
        }
        if (request.key in running) return
        pending.removeAll { it.request.key == request.key }
        pending += Pending(request, sequence++, generation = null)
        state(request.key).value = ThumbnailState.Queued
        pump()
    }

    override fun observe(request: ThumbnailRequest): Flow<ThumbnailState> = observe(request.key)

    override fun request(request: ThumbnailRequest) = enqueue(request)

    override fun cancel(request: ThumbnailRequest) = cancel(request.key)

    override fun requestVisible(requests: List<ThumbnailRequest>) {
        requests.forEach { enqueue(it.copy(priority = ThumbnailPriority.VISIBLE)) }
    }

    @Synchronized
    override fun prefetch(requests: List<ThumbnailRequest>, direction: ScrollDirection, generation: Long) {
        val requestedKeys = requests.mapTo(mutableSetOf(), ThumbnailRequest::key)
        val visiblePendingKeys = pending
            .filter { it.request.priority == ThumbnailPriority.VISIBLE }
            .mapTo(mutableSetOf()) { it.request.key }
        // Keep one bounded window per direction. Work that has already
        // entered the decoder is intentionally allowed to finish.
        pending.removeAll { it.generation == generation && it.request.key !in requestedKeys }
        requests.forEach { request ->
            if (request.key in visiblePendingKeys || running[request.key]?.request?.priority == ThumbnailPriority.VISIBLE) {
                return@forEach
            }
            if (thumbnailCache.contains(request.key)) {
                state(request.key).value = ThumbnailState.Ready(request.key)
                return@forEach
            }
            pending.removeAll { it.request.key == request.key }
            pending += Pending(
                request.copy(priority = ThumbnailPriority.BACKGROUND),
                sequence++,
                generation,
            )
            state(request.key).value = ThumbnailState.Queued
        }
        pump()
    }

    @Synchronized
    override fun cancelPrefetch(generation: Long) {
        // Let an already decoding frame finish and populate the cache. Only
        // discard work that has not started; visible requests will preempt a
        // background worker explicitly when necessary.
        pending.removeAll { it.request.priority != ThumbnailPriority.VISIBLE && it.generation == generation }
        pump()
    }

    @Synchronized
    override fun cancel(key: ThumbnailKey) {
        pending.removeAll { it.request.key == key }
        running.remove(key)?.job?.cancel()
        states.remove(key)
        pump()
    }

    @Synchronized
    private fun pump() {
        while (running.size < maxConcurrent && pending.isNotEmpty()) {
            val next = pending.peek() ?: return
            val runningBackground = running.values.count { it.request.priority != ThumbnailPriority.VISIBLE }
            if (next.request.priority != ThumbnailPriority.VISIBLE &&
                runningBackground >= backgroundConcurrency
            ) return
            val task = pending.remove()
            val key = task.request.key
            if (key in running) continue
            state(key).value = ThumbnailState.Loading
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val success = try {
                    extractor.extract(task.request)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                complete(task, success)
            }
            running[key] = Running(task.request, task.generation, job)
            job.start()
        }
    }

    private fun complete(task: Pending, success: Boolean) {
        val key = task.request.key
        if (success) {
            // Cache maintenance can enumerate a large directory. Do it before
            // taking the scheduler monitor so UI requests are not blocked by
            // disk housekeeping.
            runCatching { thumbnailCache.put(key) }
        }
        synchronized(this) {
            running.remove(key)
            if (success) {
                failedKeys.remove(key)
                state(key).value = ThumbnailState.Ready(key)
            } else if (task.attempts < MAX_RETRIES) {
                pending += task.copy(sequence = sequence++, attempts = task.attempts + 1)
                state(key).value = ThumbnailState.Queued
            } else {
                failedKeys += key
                state(key).value = ThumbnailState.Failed
            }
            pump()
        }
    }

    private fun state(key: ThumbnailKey): MutableStateFlow<ThumbnailState> =
        states.getOrPut(key) { MutableStateFlow(ThumbnailState.Queued) }

    private val backgroundConcurrency: Int
        get() = if (maxConcurrent == 1) 1 else maxConcurrent - 1

    private companion object { const val MAX_RETRIES = 1 }
}
