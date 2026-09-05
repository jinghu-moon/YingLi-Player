package seeyuer.yingli.player.core.media

import kotlinx.coroutines.flow.Flow
import seeyuer.yingli.player.core.model.media.MediaCandidate
import seeyuer.yingli.player.core.model.media.MediaItem
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocation
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaSource
import seeyuer.yingli.player.core.model.media.MediaSourceId
import seeyuer.yingli.player.core.model.media.MediaSourceMode
import seeyuer.yingli.player.core.model.media.ScanFailure
import seeyuer.yingli.player.core.model.media.ScanProgress
import seeyuer.yingli.player.core.model.media.ThumbnailRequest
import seeyuer.yingli.player.core.model.media.ThumbnailKey
import seeyuer.yingli.player.core.model.media.ScrollDirection

data class MediaPermissionSnapshot(
    val allFilesAccess: Boolean,
    val mediaStoreReadAccess: Boolean,
    val persistedSafTrees: Set<String>,
)

sealed interface PermissionActionResult {
    data object Granted : PermissionActionResult
    data object Denied : PermissionActionResult
    data object Cancelled : PermissionActionResult
    data class SafTreeGranted(val uri: String) : PermissionActionResult
}

interface MediaPermissionGateway {
    suspend fun inspect(): MediaPermissionSnapshot

    suspend fun persistSafTree(uri: String): PermissionActionResult

    suspend fun isSafTreeAccessible(uri: String): Boolean
}

sealed interface MediaDiscoveryEvent {
    data class Candidate(val value: MediaCandidate) : MediaDiscoveryEvent
    data class Failure(val value: ScanFailure) : MediaDiscoveryEvent
}

interface MediaDiscoveryDataSource {
    val mode: MediaSourceMode

    fun discover(source: MediaSource): Flow<MediaDiscoveryEvent>
}

data class CatalogSnapshot(
    val items: List<MediaItem>,
    val locations: List<MediaLocation>,
    val itemByLocation: Map<MediaLocationId, MediaItemId>,
)

data class CatalogMutation(
    val upsertItems: List<MediaItem>,
    val upsertLocations: List<MediaLocation>,
    val links: Map<MediaLocationId, MediaItemId>,
    val seenLocationIds: Set<MediaLocationId>,
    val scanCompletedAtEpochMillis: Long,
    val markMissing: Boolean = true,
    val evidenceUpdates: List<MediaLocation> = emptyList(),
)

interface MediaCatalogRepository {
    fun observeItems(): Flow<List<MediaItem>>

    suspend fun snapshot(sourceId: MediaSourceId): CatalogSnapshot

    suspend fun applyMutation(sourceId: MediaSourceId, mutation: CatalogMutation): Pair<Int, Int>
}

interface MediaSourceRepository {
    fun observeSources(): Flow<List<MediaSource>>

    suspend fun get(sourceId: MediaSourceId): MediaSource?

    suspend fun upsert(source: MediaSource)

    suspend fun markAccessState(sourceId: MediaSourceId, state: seeyuer.yingli.player.core.model.media.MediaSourceAccessState)
}

sealed interface ThumbnailState {
    data object Queued : ThumbnailState
    data object Loading : ThumbnailState
    data class Ready(val key: ThumbnailKey) : ThumbnailState
    data object Failed : ThumbnailState
}

interface ThumbnailRepository {
    fun observe(key: ThumbnailKey): Flow<ThumbnailState>

    fun enqueue(request: ThumbnailRequest)

    fun cancel(key: ThumbnailKey)
}

interface ThumbnailLoader {
    fun observe(request: ThumbnailRequest): Flow<ThumbnailState>
    fun request(request: ThumbnailRequest)
    fun cancel(request: ThumbnailRequest)
    fun requestVisible(requests: List<ThumbnailRequest>)
    /**
     * Queues background work for one scroll session. The caller keeps the same
     * generation while the user continues in the same direction and changes it
     * only when the direction changes.
     */
    fun prefetch(requests: List<ThumbnailRequest>, direction: ScrollDirection, generation: Long)
    fun cancelPrefetch(generation: Long)
}

fun interface MediaContentHasher {
    suspend fun sha256(uri: seeyuer.yingli.player.core.model.media.MediaUri): String?

    companion object {
        val None = MediaContentHasher { null }
    }
}
