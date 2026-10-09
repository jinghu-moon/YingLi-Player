package seeyuer.yingli.player.data.recycle

import androidx.room.withTransaction
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.data.room.YingLiDatabase
import seeyuer.yingli.player.domain.recycle.RecycleCatalogGateway

class RoomRecycleCatalogGateway(private val database: YingLiDatabase) : RecycleCatalogGateway {
    private val dao = database.libraryDao()

    override suspend fun markRestored(
        locationId: MediaLocationId,
        restoreUri: MediaUri,
        contentHash: String?,
        hashAlgorithmVersion: Int?,
        nowEpochMillis: Long,
    ) = dao.rebindLocation(
        locationId = locationId.value,
        uri = restoreUri.value,
        contentHash = contentHash,
        hashAlgorithmVersion = hashAlgorithmVersion,
        nowEpochMillis = nowEpochMillis,
    )

    /**
     * 两条删除放在同一个事务里：中间态（位置行已删、条目行还在）会被首页统计与去重扫描看到，
     * 那正是阶段 3 记录过的「一次操作暴露半成品状态」问题。
     */
    override suspend fun detachLocation(locationId: MediaLocationId, mediaItemId: MediaItemId) {
        database.withTransaction {
            dao.deleteLocation(locationId.value)
            dao.deleteOrphanItem(mediaItemId.value)
        }
    }

    override suspend fun deleteItemsWithoutLocations(): Int = dao.deleteItemsWithoutLocations()
}
