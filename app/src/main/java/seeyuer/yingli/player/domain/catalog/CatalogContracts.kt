package seeyuer.yingli.player.domain.catalog

import kotlinx.coroutines.flow.Flow
import seeyuer.yingli.player.core.model.media.MediaCandidate
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
    suspend fun markAccessState(sourceId: MediaSourceId, state: MediaSourceAccessState)
}

/**
 * 内容哈希能力。
 *
 * 三个方法对应去重漏斗的不同层（设计稿 §7.1）：
 * - [size]：当前字节数。L4 最终复核要拿它和「扫描时记下的大小」比，才能发现文件已被替换；
 * - [quickFingerprint]（L1）：只看**头尾各 64 KiB 加文件大小**，用来把「大小相同但内容不同」
 *   的候选快速剪掉，代价与文件大小无关；
 * - [sha256]（L2）：整文件流式 SHA-256，是 EXACT 判定的唯一依据。
 *
 * 三者都遵守同一条契约：**读不出来就返回 null，绝不返回猜测值**。
 */
interface MediaContentHasher {
    suspend fun size(uri: MediaUri): Long?

    suspend fun quickFingerprint(uri: MediaUri, sizeBytes: Long): String?

    suspend fun sha256(uri: MediaUri): String?

    companion object {
        val None = object : MediaContentHasher {
            override suspend fun size(uri: MediaUri): Long? = null
            override suspend fun quickFingerprint(uri: MediaUri, sizeBytes: Long): String? = null
            override suspend fun sha256(uri: MediaUri): String? = null
        }
    }
}
