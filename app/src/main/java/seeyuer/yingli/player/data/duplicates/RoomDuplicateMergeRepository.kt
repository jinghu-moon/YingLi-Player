package seeyuer.yingli.player.data.duplicates

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import seeyuer.yingli.player.core.common.AppLogEvent
import seeyuer.yingli.player.core.common.AppLogLevel
import seeyuer.yingli.player.core.common.AppLogger
import seeyuer.yingli.player.core.common.LogValue
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.data.room.DuplicateMergeDao
import seeyuer.yingli.player.data.room.YingLiDatabase
import seeyuer.yingli.player.domain.duplicates.DuplicateMerge
import seeyuer.yingli.player.domain.duplicates.DuplicateMergeRepository
import seeyuer.yingli.player.domain.duplicates.DuplicateMergeResult

/**
 * §7.3 的引用迁移，**全程一个 Room 事务**。
 *
 * 迁移的不是字节而是引用：loser 的每个位置、标签、收藏、播放历史、播放列表成员、
 * 集合成员、切片项目和处理任务输入都改指 survivor，一步都不能少——少一步就是
 * 「用户状态丢失」。全部迁移完成、且 loser 名下不再有任何位置之后，才删除 loser 行。
 *
 * 该删的行必须**显式删**：`favorites`、`playback_history`、`recently_organized`、
 * `playlist_items`、`collection_items`、`processing_project_inputs` 都没有指向
 * `media_items` 的外键，`media_tag_refs` 也只对 `tag_definitions` 有外键——指望级联会留下
 * 孤儿行。只有 `media_tags` 有 `media_items` 级联，但它同样按「先复制再显式删」处理，
 * 这样六张表是同一种写法，不必逐个记住谁有外键。
 */
class RoomDuplicateMergeRepository(
    private val database: YingLiDatabase,
    private val logger: AppLogger,
) : DuplicateMergeRepository {
    private val dao: DuplicateMergeDao = database.duplicateMergeDao()

    override suspend fun merge(merges: List<DuplicateMerge>): DuplicateMergeResult {
        var mergedCount = 0
        try {
            database.withTransaction {
                for (merge in merges) {
                    for (loser in merge.loserMediaIds) {
                        mergeOne(merge.survivorMediaId, merge.survivorLocationId, loser)
                        mergedCount++
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // 取消同样必须整体回滚：半迁移状态比慢一点糟得多。
            throw cancelled
        } catch (error: Exception) {
            logger.log(
                AppLogLevel.ERROR,
                AppLogEvent(
                    code = DUPLICATE_MERGE_FAILED,
                    message = "duplicate merge transaction rolled back",
                    attributes = mapOf(
                        "failureType" to LogValue.Public(error.javaClass.simpleName),
                        "failureMessage" to LogValue.Public(error.message.orEmpty()),
                        "mergeCount" to LogValue.Public(merges.size.toString()),
                    ),
                ),
            )
            return DuplicateMergeResult.Rejected(DUPLICATE_MERGE_FAILED)
        }
        return DuplicateMergeResult.Completed(mergedCount)
    }

    private suspend fun mergeOne(
        survivorId: MediaItemId,
        survivorLocationId: MediaLocationId,
        loserId: MediaItemId,
    ) {
        val survivor = survivorId.value
        val loser = loserId.value

        // 1. 位置：唯一索引保证不会与 survivor 现有位置冲突。
        dao.relinkLocations(survivor, loser)

        // 2. 标签：并集（先复制再显式删 loser；`UPDATE OR IGNORE` 会把冲突行留在 loser 名下）。
        dao.copyTags(survivor, loser)
        dao.deleteTags(loser)
        dao.copyTagRefs(survivor, loser)
        dao.deleteTagRefs(loser)

        // 3. 收藏：逻辑或，时间戳取更早（不要因为归并让「第一次收藏」变晚）。
        dao.copyFavorite(survivor, loser)
        dao.reconcileFavoriteTimestamp(survivor, loser)
        dao.deleteFavorite(loser)

        // 4. 播放历史：次数求和，时间取更近，进度取更近那一条的。
        mergePlaybackHistory(survivor, loser)

        // 5. 最近整理：取更近的一条。
        mergeRecentlyOrganized(survivor, loser)

        // 6. 播放列表：并集 + 时间戳取更早 + 整体重排 position。
        for (playlistId in dao.playlistIdsFor(loser)) {
            dao.copyPlaylistItem(playlistId, survivor, loser)
            dao.reconcilePlaylistItemTimestamp(playlistId, survivor, loser)
            dao.deletePlaylistItem(playlistId, loser)
            reorderPlaylist(playlistId)
        }

        // 7. 集合：并集 + 时间戳取更早。
        for (collectionId in dao.collectionIdsFor(loser)) {
            dao.copyCollectionItem(collectionId, survivor, loser)
            dao.reconcileCollectionItemTimestamp(collectionId, survivor, loser)
            dao.deleteCollectionItem(collectionId, loser)
        }

        // 8. 切片项目：改指 survivor，并把 sourceLocationId 换到**用户保留的那个位置**。
        dao.repointClipProjects(survivor, survivorLocationId.value, loser)

        // 9. 处理任务输入：改指 + 同项目内去重 + 重排 position。
        for (projectId in dao.projectIdsFor(loser)) {
            dao.copyProjectInput(projectId, survivor, loser)
            dao.deleteProjectInput(projectId, loser)
            reorderProjectInputs(projectId)
        }

        // 10. 条目自身：进度取 survivor（不写），只把 completed 取逻辑或。
        val survivorItem = dao.mediaItem(survivor) ?: error("survivor vanished during merge")
        val loserItem = dao.mediaItem(loser) ?: error("loser vanished during merge")
        if (loserItem.completed && !survivorItem.completed) dao.setCompleted(survivor, true)

        // 11. 不变量 2：不存在没有位置的条目。确认 loser 已无位置再删。
        check(dao.locationCount(loser) == 0) { "loser still owns locations after relink" }
        dao.deleteItem(loser)
    }

    private suspend fun mergePlaybackHistory(survivor: String, loser: String) {
        val loserRow = dao.playbackHistory(loser) ?: return
        val survivorRow = dao.playbackHistory(survivor)
        if (survivorRow == null) {
            dao.upsertPlaybackHistory(loserRow.copy(mediaItemId = survivor))
        } else {
            val loserIsNewer = loserRow.lastPlayedAtEpochMillis > survivorRow.lastPlayedAtEpochMillis
            if (loserRow.lastPlayedAtEpochMillis == survivorRow.lastPlayedAtEpochMillis &&
                loserRow.lastPositionMillis != survivorRow.lastPositionMillis
            ) {
                // 时间戳并列而进度不同：规则取 survivor，但要留下痕迹，不能静默丢进度。
                logger.log(
                    AppLogLevel.INFO,
                    AppLogEvent(
                        code = DUPLICATE_MERGE_POSITION_TIE,
                        message = "playback history timestamps tied; kept survivor position",
                        attributes = mapOf(
                            "survivorPositionMillis" to LogValue.Public(survivorRow.lastPositionMillis.toString()),
                            "loserPositionMillis" to LogValue.Public(loserRow.lastPositionMillis.toString()),
                        ),
                    ),
                )
            }
            dao.upsertPlaybackHistory(
                survivorRow.copy(
                    playCount = survivorRow.playCount + loserRow.playCount,
                    lastPlayedAtEpochMillis = maxOf(
                        survivorRow.lastPlayedAtEpochMillis,
                        loserRow.lastPlayedAtEpochMillis,
                    ),
                    lastPositionMillis = if (loserIsNewer) {
                        loserRow.lastPositionMillis
                    } else {
                        survivorRow.lastPositionMillis
                    },
                ),
            )
        }
        dao.deletePlaybackHistory(loser)
    }

    private suspend fun mergeRecentlyOrganized(survivor: String, loser: String) {
        val loserRow = dao.recentlyOrganized(loser) ?: return
        val survivorRow = dao.recentlyOrganized(survivor)
        if (survivorRow == null || loserRow.organizedAtEpochMillis > survivorRow.organizedAtEpochMillis) {
            dao.upsertRecentlyOrganized(loserRow.copy(mediaItemId = survivor))
        }
        dao.deleteRecentlyOrganized(loser)
    }

    private suspend fun reorderPlaylist(playlistId: String) {
        // 先把 position 整体挪到负数区，避免逐行写回时撞 (playlistId, position) 唯一索引。
        dao.negatePlaylistPositions(playlistId)
        dao.orderedPlaylistItems(playlistId).forEachIndexed { index, mediaItemId ->
            dao.setPlaylistPosition(playlistId, mediaItemId, index)
        }
    }

    private suspend fun reorderProjectInputs(projectId: String) {
        // processing_project_inputs 的 position 不参与主键，没有唯一索引，直接写回即可。
        dao.orderedProjectInputs(projectId).forEachIndexed { index, mediaItemId ->
            dao.setProjectInputPosition(projectId, mediaItemId, index)
        }
    }

    private companion object {
        const val DUPLICATE_MERGE_FAILED = "DUPLICATE_MERGE_FAILED"
        const val DUPLICATE_MERGE_POSITION_TIE = "DUPLICATE_MERGE_POSITION_TIE"
    }
}
