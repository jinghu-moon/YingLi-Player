package seeyuer.yingli.player.domain.home

import kotlinx.coroutines.flow.Flow
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.organize.CollectionId

enum class HomeCardId {
    STATS,
    CONTINUE_WATCHING,
    RECENTLY_ADDED,
    MY_COLLECTIONS,
    FREQUENT_FOLDERS,
    MAINTENANCE,
}

data class HomeCardLayout(
    val order: List<HomeCardId> = DEFAULT_ORDER,
    val hidden: Set<HomeCardId> = emptySet(),
) {
    init {
        require(order.size == HomeCardId.entries.size)
        require(order.toSet() == HomeCardId.entries.toSet())
        require(hidden.all(order::contains))
    }

    fun move(fromIndex: Int, toIndex: Int): HomeCardLayout {
        if (fromIndex !in order.indices || toIndex !in order.indices || fromIndex == toIndex) return this
        val reordered = order.toMutableList()
        reordered.add(toIndex, reordered.removeAt(fromIndex))
        return copy(order = reordered)
    }

    fun setVisible(id: HomeCardId, visible: Boolean): HomeCardLayout = copy(
        hidden = if (visible) hidden - id else hidden + id,
    )

    fun reset(): HomeCardLayout = Default

    companion object {
        val DEFAULT_ORDER = listOf(
            HomeCardId.STATS,
            HomeCardId.CONTINUE_WATCHING,
            HomeCardId.RECENTLY_ADDED,
            HomeCardId.MY_COLLECTIONS,
            HomeCardId.FREQUENT_FOLDERS,
            HomeCardId.MAINTENANCE,
        )
        val Default = HomeCardLayout(DEFAULT_ORDER)

        fun normalize(orderValues: Iterable<String>?, hiddenValues: Iterable<String>?): HomeCardLayout {
            val knownOrder = (orderValues ?: emptyList())
                .mapNotNull { value -> HomeCardId.entries.firstOrNull { it.name == value } }
                .distinct()
            val order = knownOrder + DEFAULT_ORDER.filterNot(knownOrder::contains)
            val hidden = (hiddenValues ?: emptyList())
                .mapNotNullTo(linkedSetOf()) { value -> HomeCardId.entries.firstOrNull { it.name == value } }
            return HomeCardLayout(order, hidden)
        }
    }
}

interface HomeLayoutRepository {
    val layout: Flow<HomeCardLayout>
    suspend fun save(layout: HomeCardLayout)
}

data class HomeStats(
    val videoCount: Int,
    val videoBytes: Long,
    val deviceTotalBytes: Long,
    val deviceAvailableBytes: Long,
) {
    init {
        require(videoCount >= 0)
        require(videoBytes >= 0 && deviceTotalBytes >= 0 && deviceAvailableBytes >= 0)
    }
}

data class HomeMediaPreview(
    val mediaId: MediaItemId,
    val locationId: MediaLocationId,
    val uri: MediaUri,
    val title: String,
    val folderAlias: String,
    val sizeBytes: Long,
    val durationMillis: Long?,
    val width: Int?,
    val height: Int?,
    val modifiedEpochMillis: Long,
    val playbackPositionMillis: Long,
)

data class HomeCollectionPreview(
    val id: CollectionId,
    val name: String,
    val itemCount: Int,
    val updatedAtEpochMillis: Long,
)

data class HomeFolderPreview(
    val sourceId: String,
    val name: String,
    val itemCount: Int,
    val sizeBytes: Long,
)

enum class MaintenanceKind {
    DUPLICATES,
    TRASH,
}

data class MaintenanceItem(
    val kind: MaintenanceKind,
    val itemCount: Int,
    val reclaimableBytes: Long = 0,
) {
    init {
        require(itemCount > 0)
        require(reclaimableBytes >= 0)
    }
}

interface HomeRepository {
    fun observeStats(): Flow<Pair<Int, Long>>
    fun observeContinueWatching(limit: Int): Flow<List<HomeMediaPreview>>
    fun observeRecentlyAdded(limit: Int): Flow<List<HomeMediaPreview>>
    fun observeCollections(limit: Int): Flow<List<HomeCollectionPreview>>
    fun observeFrequentFolders(limit: Int): Flow<List<HomeFolderPreview>>
}

fun interface DeviceStorageRepository {
    fun snapshot(): Pair<Long, Long>
}
