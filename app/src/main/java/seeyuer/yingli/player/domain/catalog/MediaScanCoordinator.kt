package seeyuer.yingli.player.domain.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.model.media.MediaSourceId
import seeyuer.yingli.player.core.model.media.ScanRequest
import seeyuer.yingli.player.core.model.media.ScanResult

/**
 * Coordinates media scans so callers cannot race one another or reconcile an
 * older scan after a newer scan has been requested.
 */
class MediaScanCoordinator(
    private val delegate: MediaScanner,
    private val sourceRepository: MediaSourceRepository,
    private val clock: AppClock,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) : MediaScanner, ScanProgressSource {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private var active: Deferred<ScanResult>? = null
    private var activeRequest: ScanRequest? = null
    private var generation = 0L
    private val fallbackProgress = MutableStateFlow(seeyuer.yingli.player.core.model.media.ScanProgress())

    override val progress: StateFlow<seeyuer.yingli.player.core.model.media.ScanProgress> =
        (delegate as? ScanProgressSource)?.progress ?: fallbackProgress

    override suspend fun scan(request: ScanRequest): ScanResult = scan(request, force = false)

    suspend fun scan(request: ScanRequest, force: Boolean): ScanResult {
        val running = mutex.withLock { active?.takeIf { it.isActive } }
        val sameRequest = mutex.withLock { activeRequest == request }
        if (running != null && !force && sameRequest) return running.await()
        if (running != null && !force && !sameRequest) {
            // Scans for different source sets cannot share a result. Serialize
            // them behind the current generation without cancelling it.
            running.await()
        }
        if (running != null) {
            if (force) {
                running.cancel()
                runCatching { running.await() }
            }
        }

        val task = mutex.withLock {
            val stillRunning = active?.takeIf { it.isActive }
            if (stillRunning != null) return@withLock stillRunning
            if (!force && isFresh(request.sourceIds)) return@withLock null
            generation += 1
            // Start immediately while holding the mutex so a concurrent caller
            // observes the same active job instead of another lazy Deferred.
            scope.async {
                delegate.scan(request)
            }.also {
                active = it
                activeRequest = request
            }
        }

        if (task == null) {
            return ScanResult(0, 0, 0, 0, emptyList(), 0)
        }
        return try {
            task.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            mutex.withLock {
                if (active === task) {
                    active = null
                    activeRequest = null
                }
            }
        }
    }

    suspend fun cancel() {
        mutex.withLock { active?.cancel() }
    }

    suspend fun currentGeneration(): Long = mutex.withLock { generation }

    private suspend fun isFresh(sourceIds: Set<MediaSourceId>): Boolean {
        if (sourceIds.isEmpty()) return true
        val now = clock.now().toEpochMilli()
        return sourceIds.all { id ->
            val source = sourceRepository.get(id)
            source != null &&
                source.accessState == seeyuer.yingli.player.core.model.media.MediaSourceAccessState.AVAILABLE &&
                source.lastSyncedEpochMillis != null &&
                now - source.lastSyncedEpochMillis <= ttlMillis
        }
    }

    private companion object {
        const val DEFAULT_TTL_MILLIS = 5 * 60 * 1000L
    }
}
