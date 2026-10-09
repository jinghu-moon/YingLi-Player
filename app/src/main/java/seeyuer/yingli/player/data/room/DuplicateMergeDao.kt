package seeyuer.yingli.player.data.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * 归并（把 loser 条目的全部引用迁移到 survivor）所需的语句集合。
 *
 * 设计稿 §7.3 的规则表逐行对应这里的方法；**全部调用必须包在同一个
 * Room 事务里**（由 `RoomDuplicateMergeRepository` 保证），任何一步失败都不允许
 * 留下半迁移状态。
 *
 * 语义约定（很重要，否则会静默丢数据）：
 * - 带 `OR IGNORE` 的 `UPDATE` 处理「并集」：冲突的行**故意留在 loser 名下**，
 *   等 `DELETE FROM media_items` 时由外键级联清掉；
 * - `addedAtEpochMillis` / `organizedAtEpochMillis` 这类时间戳取**较早/较新**者，
 *   目的是不要因为归并把「第一次收藏/第一次加入播放列表」的时间戳改晚。
 */
@Dao
interface DuplicateMergeDao {
    // ---- 位置重指向 ----

    /**
     * 把 loser 的全部位置改指 survivor。
     * `media_item_locations.locationId` 有唯一索引，因此不会与 survivor 现有行冲突。
     */
    @Query("UPDATE media_item_locations SET mediaItemId = :survivorMediaItemId WHERE mediaItemId = :loserMediaItemId")
    suspend fun relinkLocations(survivorMediaItemId: String, loserMediaItemId: String)

    @Query("SELECT COUNT(*) FROM media_item_locations WHERE mediaItemId = :mediaItemId")
    suspend fun locationCount(mediaItemId: String): Int

    @Query("DELETE FROM media_items WHERE id = :mediaItemId")
    suspend fun deleteItem(mediaItemId: String)

    // ---- 标签（并集） ----

    /**
     * **不要用 `UPDATE OR IGNORE … SET mediaItemId = survivor` 做并集。**
     * 唯一约束冲突时 `OR IGNORE` 只是跳过那一行，loser 的那一行会**原样留下**：
     * `media_tags` 靠 `media_items` 的级联能被删掉，但 `media_tag_refs` 只对
     * `tag_definitions` 有外键，于是留下指向已删除条目的孤儿行。2026-10-09 阶段 3 的
     * `RoomDuplicateMergeRepositoryTest` 正是撞上这个。
     * 因此统一用「先 `INSERT OR IGNORE … SELECT` 复制、再显式删 loser」（与收藏/播放列表同构）。
     */
    @Query(
        "INSERT OR IGNORE INTO media_tags(mediaItemId, tag) " +
            "SELECT :survivorMediaItemId, tag FROM media_tags WHERE mediaItemId = :loserMediaItemId",
    )
    suspend fun copyTags(survivorMediaItemId: String, loserMediaItemId: String)

    @Query("DELETE FROM media_tags WHERE mediaItemId = :mediaItemId")
    suspend fun deleteTags(mediaItemId: String)

    @Query(
        "INSERT OR IGNORE INTO media_tag_refs(mediaItemId, tagId) " +
            "SELECT :survivorMediaItemId, tagId FROM media_tag_refs WHERE mediaItemId = :loserMediaItemId",
    )
    suspend fun copyTagRefs(survivorMediaItemId: String, loserMediaItemId: String)

    @Query("DELETE FROM media_tag_refs WHERE mediaItemId = :mediaItemId")
    suspend fun deleteTagRefs(mediaItemId: String)

    // ---- 收藏（逻辑或，时间戳取更早） ----

    @Query(
        "INSERT OR IGNORE INTO favorites(mediaItemId, createdAtEpochMillis) " +
            "SELECT :survivorMediaItemId, createdAtEpochMillis FROM favorites WHERE mediaItemId = :loserMediaItemId",
    )
    suspend fun copyFavorite(survivorMediaItemId: String, loserMediaItemId: String)

    @Query(
        "UPDATE favorites SET createdAtEpochMillis = (" +
            "SELECT MIN(createdAtEpochMillis) FROM favorites " +
            "WHERE mediaItemId IN (:survivorMediaItemId, :loserMediaItemId)) " +
            "WHERE mediaItemId = :survivorMediaItemId",
    )
    suspend fun reconcileFavoriteTimestamp(survivorMediaItemId: String, loserMediaItemId: String)

    /**
     * `favorites` / `playback_history` / `recently_organized` / `playlist_items` /
     * `collection_items` / `processing_project_inputs` **都没有指向 `media_items` 的外键**，
     * 所以 loser 名下的行必须由归并过程显式清掉，不能指望级联。
     */
    @Query("DELETE FROM favorites WHERE mediaItemId = :mediaItemId")
    suspend fun deleteFavorite(mediaItemId: String)

    // ---- 播放历史（求和 + 取最新 + 位置取较新者） ----

    @Query("SELECT * FROM playback_history WHERE mediaItemId = :mediaItemId")
    suspend fun playbackHistory(mediaItemId: String): PlaybackHistoryEntity?

    @Upsert
    suspend fun upsertPlaybackHistory(history: PlaybackHistoryEntity)

    @Query("DELETE FROM playback_history WHERE mediaItemId = :mediaItemId")
    suspend fun deletePlaybackHistory(mediaItemId: String)

    // ---- 最近整理（取较新） ----

    @Query("SELECT * FROM recently_organized WHERE mediaItemId = :mediaItemId")
    suspend fun recentlyOrganized(mediaItemId: String): RecentlyOrganizedEntity?

    @Upsert
    suspend fun upsertRecentlyOrganized(entry: RecentlyOrganizedEntity)

    @Query("DELETE FROM recently_organized WHERE mediaItemId = :mediaItemId")
    suspend fun deleteRecentlyOrganized(mediaItemId: String)

    // ---- 播放列表成员（并集 + 时间戳取更早 + 重排 position） ----

    @Query("SELECT playlistId FROM playlist_items WHERE mediaItemId = :mediaItemId")
    suspend fun playlistIdsFor(mediaItemId: String): List<String>

    @Query(
        "INSERT OR IGNORE INTO playlist_items(playlistId, mediaItemId, position, addedAtEpochMillis) " +
            "SELECT :playlistId, :survivorMediaItemId, 0, addedAtEpochMillis FROM playlist_items " +
            "WHERE playlistId = :playlistId AND mediaItemId = :loserMediaItemId",
    )
    suspend fun copyPlaylistItem(playlistId: String, survivorMediaItemId: String, loserMediaItemId: String)

    @Query(
        "UPDATE playlist_items SET addedAtEpochMillis = (" +
            "SELECT MIN(addedAtEpochMillis) FROM playlist_items " +
            "WHERE playlistId = :playlistId AND mediaItemId IN (:survivorMediaItemId, :loserMediaItemId)) " +
            "WHERE playlistId = :playlistId AND mediaItemId = :survivorMediaItemId",
    )
    suspend fun reconcilePlaylistItemTimestamp(playlistId: String, survivorMediaItemId: String, loserMediaItemId: String)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND mediaItemId = :mediaItemId")
    suspend fun deletePlaylistItem(playlistId: String, mediaItemId: String)

    @Query("SELECT mediaItemId FROM playlist_items WHERE playlistId = :playlistId ORDER BY addedAtEpochMillis, mediaItemId")
    suspend fun orderedPlaylistItems(playlistId: String): List<String>

    /** 先把位置整体挪到负数区，避免逐行写回时撞上 `(playlistId, position)` 的唯一索引。 */
    @Query("UPDATE playlist_items SET position = -1 - position WHERE playlistId = :playlistId")
    suspend fun negatePlaylistPositions(playlistId: String)

    @Query("UPDATE playlist_items SET position = :position WHERE playlistId = :playlistId AND mediaItemId = :mediaItemId")
    suspend fun setPlaylistPosition(playlistId: String, mediaItemId: String, position: Int)

    // ---- 集合成员（并集 + 时间戳取更早） ----

    @Query("SELECT collectionId FROM collection_items WHERE mediaItemId = :mediaItemId")
    suspend fun collectionIdsFor(mediaItemId: String): List<String>

    @Query(
        "INSERT OR IGNORE INTO collection_items(collectionId, mediaItemId, addedAtEpochMillis) " +
            "SELECT :collectionId, :survivorMediaItemId, addedAtEpochMillis FROM collection_items " +
            "WHERE collectionId = :collectionId AND mediaItemId = :loserMediaItemId",
    )
    suspend fun copyCollectionItem(collectionId: String, survivorMediaItemId: String, loserMediaItemId: String)

    @Query(
        "UPDATE collection_items SET addedAtEpochMillis = (" +
            "SELECT MIN(addedAtEpochMillis) FROM collection_items " +
            "WHERE collectionId = :collectionId AND mediaItemId IN (:survivorMediaItemId, :loserMediaItemId)) " +
            "WHERE collectionId = :collectionId AND mediaItemId = :survivorMediaItemId",
    )
    suspend fun reconcileCollectionItemTimestamp(collectionId: String, survivorMediaItemId: String, loserMediaItemId: String)

    @Query("DELETE FROM collection_items WHERE collectionId = :collectionId AND mediaItemId = :mediaItemId")
    suspend fun deleteCollectionItem(collectionId: String, mediaItemId: String)

    // ---- 切片项目（重指向到 survivor 保留的那个位置） ----

    /**
     * `sourceLocationId` 取 [survivorLocationId]，也就是**用户选择保留的位置**。
     *
     * 不能用「survivor 名下最近见到的位置」：这次处置会把其余位置移入回收站，
     * 那样切片项目会指向一个即将不可见的位置，导出时才发现源没了。
     */
    @Query(
        "UPDATE clip_projects SET sourceMediaId = :survivorMediaItemId, " +
            "sourceLocationId = :survivorLocationId " +
            "WHERE sourceMediaId = :loserMediaItemId",
    )
    suspend fun repointClipProjects(survivorMediaItemId: String, survivorLocationId: String, loserMediaItemId: String)

    // ---- 处理任务输入（重指向 + 去重 + 重排 position） ----

    @Query("SELECT projectId FROM processing_project_inputs WHERE mediaItemId = :mediaItemId")
    suspend fun projectIdsFor(mediaItemId: String): List<String>

    @Query(
        "INSERT OR IGNORE INTO processing_project_inputs(projectId, mediaItemId, position) " +
            "SELECT :projectId, :survivorMediaItemId, 0 FROM processing_project_inputs " +
            "WHERE projectId = :projectId AND mediaItemId = :loserMediaItemId",
    )
    suspend fun copyProjectInput(projectId: String, survivorMediaItemId: String, loserMediaItemId: String)

    @Query("DELETE FROM processing_project_inputs WHERE projectId = :projectId AND mediaItemId = :mediaItemId")
    suspend fun deleteProjectInput(projectId: String, mediaItemId: String)

    @Query("SELECT mediaItemId FROM processing_project_inputs WHERE projectId = :projectId ORDER BY position, mediaItemId")
    suspend fun orderedProjectInputs(projectId: String): List<String>

    @Query("UPDATE processing_project_inputs SET position = :position WHERE projectId = :projectId AND mediaItemId = :mediaItemId")
    suspend fun setProjectInputPosition(projectId: String, mediaItemId: String, position: Int)

    // ---- 条目自身 ----
    //
    // `media_items.playbackPositionMillis` 按设计稿取 **survivor** 的值，也就是不做任何写入：
    // 归并只把 `completed` 取逻辑或。播放进度不是「用户状态丢失」——同一个内容
    // 位置换了，用户看的还是同一部片子。

    @Query("SELECT * FROM media_items WHERE id = :mediaItemId")
    suspend fun mediaItem(mediaItemId: String): MediaItemEntity?

    @Query("UPDATE media_items SET completed = :completed WHERE id = :mediaItemId")
    suspend fun setCompleted(mediaItemId: String, completed: Boolean)
}
