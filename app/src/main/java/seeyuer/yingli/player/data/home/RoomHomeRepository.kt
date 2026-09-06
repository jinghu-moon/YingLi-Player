package seeyuer.yingli.player.data.home

import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import seeyuer.yingli.player.data.room.HomeDao
import seeyuer.yingli.player.data.room.HomeMediaRow
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.home.DeviceStorageRepository
import seeyuer.yingli.player.domain.home.HomeCollectionPreview
import seeyuer.yingli.player.domain.home.HomeFolderPreview
import seeyuer.yingli.player.domain.home.HomeMediaPreview
import seeyuer.yingli.player.domain.home.HomeRepository
import seeyuer.yingli.player.domain.organize.CollectionId

class RoomHomeRepository(private val dao: HomeDao) : HomeRepository {
    override fun observeStats(): Flow<Pair<Int, Long>> = dao.observeStats()
        .map { row -> row.videoCount to row.videoBytes }

    override fun observeContinueWatching(limit: Int): Flow<List<HomeMediaPreview>> =
        dao.observeContinueWatching(limit, MINIMUM_PLAYBACK_MILLIS, NEAR_END_MILLIS)
            .map { rows -> rows.map { row -> row.toModel() } }

    override fun observeRecentlyAdded(limit: Int): Flow<List<HomeMediaPreview>> =
        dao.observeRecentlyAdded(limit).map { rows -> rows.map { row -> row.toModel() } }

    override fun observeCollections(limit: Int): Flow<List<HomeCollectionPreview>> =
        dao.observeCollections(limit).map { rows ->
            rows.map { row ->
                HomeCollectionPreview(CollectionId(row.collectionId), row.name, row.itemCount, row.updatedAtEpochMillis)
            }
        }

    override fun observeFrequentFolders(limit: Int): Flow<List<HomeFolderPreview>> =
        dao.observeFrequentFolders(limit).map { rows ->
            rows.map { row -> HomeFolderPreview(row.sourceId, row.name, row.itemCount, row.sizeBytes) }
        }

    private fun HomeMediaRow.toModel() = HomeMediaPreview(
        mediaId = MediaItemId(mediaId),
        locationId = MediaLocationId(locationId),
        uri = MediaUri(uri),
        title = title,
        folderAlias = folderAlias,
        sizeBytes = sizeBytes,
        durationMillis = durationMillis,
        width = width,
        height = height,
        modifiedEpochMillis = modifiedEpochMillis,
        playbackPositionMillis = playbackPositionMillis,
    )

    private companion object {
        const val MINIMUM_PLAYBACK_MILLIS = 10_000L
        const val NEAR_END_MILLIS = 5_000L
    }
}

class AndroidDeviceStorageRepository : DeviceStorageRepository {
    override fun snapshot(): Pair<Long, Long> {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        return stat.totalBytes to stat.availableBytes
    }
}
