package seeyuer.yingli.player.data.duplicates

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.core.common.AppLogger
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.data.room.YingLiDatabase
import seeyuer.yingli.player.domain.duplicates.DuplicateMerge
import seeyuer.yingli.player.domain.duplicates.DuplicateMergeResult

/**
 * 归并必须**一个用户状态都不丢**。
 *
 * 为什么放在 androidTest：归并全部是 SQL（`UPDATE OR IGNORE` 的并集语义、负数区重排、
 * 外键级联），JVM 上没有 SQLite 就测不了这些语义——用假 DAO 测的只是我自己写的假实现。
 */
@RunWith(AndroidJUnit4::class)
class RoomDuplicateMergeRepositoryTest {
    private lateinit var database: YingLiDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            YingLiDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun mergeMovesEveryReferenceToTheSurvivorAndKeepsTheOldestTimestamps() = runTest {
        seed()

        val result = RoomDuplicateMergeRepository(database, AppLogger { _, _ -> })
            .merge(listOf(DuplicateMerge(MediaItemId(KEEP), MediaLocationId(KEEP_LOCATION), listOf(MediaItemId(LOSE)))))

        assertEquals(DuplicateMergeResult.Completed(1), result)

        assertEquals(listOf(KEEP), items())
        assertEquals(1, scalar("SELECT completed FROM media_items WHERE id = '$KEEP'").toInt())
        assertEquals(listOf(KEEP to KEEP_LOCATION, KEEP to LOSE_LOCATION), links())

        // 收藏：并集（loser 的那条搬过来），时间戳取更早的 5。
        assertEquals(listOf(KEEP to 5L), table("SELECT mediaItemId, createdAtEpochMillis FROM favorites"))

        // 播放历史：次数求和、时间取最新、位置取时间较近者的。
        assertEquals(
            listOf(listOf<String?>(KEEP, "5", "200", "2000")),
            rows("SELECT mediaItemId, playCount, lastPlayedAtEpochMillis, lastPositionMillis FROM playback_history"),
        )

        // 最近整理：取较新的那一条。
        assertEquals(
            listOf(listOf<String?>(KEEP, "900", "trash")),
            rows("SELECT mediaItemId, organizedAtEpochMillis, action FROM recently_organized"),
        )

        // 标签：并集 —— 保留项自己的 t2、loser 的 t1、两边都有的 t3，共三条。
        assertEquals(
            listOf(listOf<String?>(KEEP, "t1"), listOf<String?>(KEEP, "t2"), listOf<String?>(KEEP, "t3")),
            rows("SELECT mediaItemId, tagId FROM media_tag_refs ORDER BY tagId"),
        )

        // 播放列表：并集 + 加入时间取更早 + position 重排成连续序号。
        assertEquals(
            listOf(listOf<String?>("pl-1", KEEP, "0", "5")),
            rows("SELECT playlistId, mediaItemId, position, addedAtEpochMillis FROM playlist_items"),
        )

        // 集合：并集 + 加入时间取更早。
        assertEquals(
            listOf(listOf<String?>("col-1", KEEP, "7")),
            rows("SELECT collectionId, mediaItemId, addedAtEpochMillis FROM collection_items"),
        )

        // 切片项目：重指向保留项与**用户保留的那个位置**（不是最近见到的 loc-lose）。
        assertEquals(
            listOf(listOf<String?>(KEEP, KEEP_LOCATION)),
            rows("SELECT sourceMediaId, sourceLocationId FROM clip_projects"),
        )

        // 处理任务输入：重指向 + 去重 + 重排。
        assertEquals(
            listOf(listOf<String?>(KEEP, "0")),
            rows("SELECT mediaItemId, position FROM processing_project_inputs"),
        )
    }

    @Test
    fun mergeOfAnUnknownItemIsRejectedAndChangesNothing() = runTest {
        seed()

        val result = RoomDuplicateMergeRepository(database, AppLogger { _, _ -> })
            .merge(listOf(DuplicateMerge(MediaItemId(KEEP), MediaLocationId(KEEP_LOCATION), listOf(MediaItemId("item-missing")))))

        assertEquals(DuplicateMergeResult.Rejected("DUPLICATE_MERGE_FAILED"), result)
        assertEquals(listOf(KEEP, LOSE), items())
        assertEquals(listOf(KEEP to KEEP_LOCATION, LOSE to LOSE_LOCATION), links())
    }

    // ---- seeding -----------------------------------------------------------

    private fun seed() {
        exec("INSERT INTO media_sources(id, displayName, rootUri, mode, volumeId, accessState, includeHidden, lastSyncedEpochMillis, mediaCount, includeNomedia) VALUES('src', 's', 'content://s', 'MEDIA_STORE', 'external', 'GRANTED', 0, 1, 1, 0)")
        exec("INSERT INTO media_items(id, title, playbackPositionMillis, completed) VALUES('$KEEP', 'keep', 100, 0)")
        exec("INSERT INTO media_items(id, title, playbackPositionMillis, completed) VALUES('$LOSE', 'lose', 900, 1)")
        exec(location(KEEP_LOCATION, 100))
        exec(location(LOSE_LOCATION, 200))
        exec("INSERT INTO media_item_locations(mediaItemId, locationId) VALUES('$KEEP', '$KEEP_LOCATION')")
        exec("INSERT INTO media_item_locations(mediaItemId, locationId) VALUES('$LOSE', '$LOSE_LOCATION')")

        exec("INSERT INTO favorites(mediaItemId, createdAtEpochMillis) VALUES('$LOSE', 5)")
        exec("INSERT INTO playback_history(mediaItemId, playCount, lastPlayedAtEpochMillis, lastPositionMillis) VALUES('$KEEP', 2, 100, 1000)")
        exec("INSERT INTO playback_history(mediaItemId, playCount, lastPlayedAtEpochMillis, lastPositionMillis) VALUES('$LOSE', 3, 200, 2000)")
        exec("INSERT INTO recently_organized(mediaItemId, organizedAtEpochMillis, action) VALUES('$KEEP', 500, 'tag')")
        exec("INSERT INTO recently_organized(mediaItemId, organizedAtEpochMillis, action) VALUES('$LOSE', 900, 'trash')")

        exec("INSERT INTO tag_definitions(id, name, color, createdAtEpochMillis, updatedAtEpochMillis) VALUES('t1', 'a', 'BLUE', 1, 1)")
        exec("INSERT INTO tag_definitions(id, name, color, createdAtEpochMillis, updatedAtEpochMillis) VALUES('t2', 'b', 'BLUE', 1, 1)")
        exec("INSERT INTO tag_definitions(id, name, color, createdAtEpochMillis, updatedAtEpochMillis) VALUES('t3', 'c', 'BLUE', 1, 1)")
        exec("INSERT INTO media_tag_refs(mediaItemId, tagId) VALUES('$KEEP', 't2')")
        exec("INSERT INTO media_tag_refs(mediaItemId, tagId) VALUES('$LOSE', 't1')")
        exec("INSERT INTO media_tag_refs(mediaItemId, tagId) VALUES('$KEEP', 't3')")
        exec("INSERT INTO media_tag_refs(mediaItemId, tagId) VALUES('$LOSE', 't3')")

        exec("INSERT INTO playlists(id, name, createdAtEpochMillis, updatedAtEpochMillis) VALUES('pl-1', 'p', 1, 1)")
        exec("INSERT INTO playlist_items(playlistId, mediaItemId, position, addedAtEpochMillis) VALUES('pl-1', '$KEEP', 0, 10)")
        exec("INSERT INTO playlist_items(playlistId, mediaItemId, position, addedAtEpochMillis) VALUES('pl-1', '$LOSE', 1, 5)")

        exec("INSERT INTO collections(id, name, kind, serializedFilter, createdAtEpochMillis, updatedAtEpochMillis) VALUES('col-1', 'c', 'MANUAL', NULL, 1, 1)")
        exec("INSERT INTO collection_items(collectionId, mediaItemId, addedAtEpochMillis) VALUES('col-1', '$KEEP', 9)")
        exec("INSERT INTO collection_items(collectionId, mediaItemId, addedAtEpochMillis) VALUES('col-1', '$LOSE', 7)")

        exec("INSERT INTO clip_projects(id, sourceMediaId, sourceLocationId, sourceDurationMillis, exportMode, preset, createdAtEpochMillis, updatedAtEpochMillis) VALUES('clip-1', '$LOSE', '$LOSE_LOCATION', 1000, 'FAST', 'default', 1, 1)")

        exec("INSERT INTO processing_projects(id, type, outputPolicy, createdAtEpochMillis) VALUES('proj-1', 'CLIP', 'p', 1)")
        exec("INSERT INTO processing_project_inputs(projectId, mediaItemId, position) VALUES('proj-1', '$KEEP', 0)")
        exec("INSERT INTO processing_project_inputs(projectId, mediaItemId, position) VALUES('proj-1', '$LOSE', 1)")
    }

    private fun location(id: String, lastSeen: Long) =
        "INSERT INTO media_locations(id, sourceId, uri, volumeId, documentId, fileName, mimeType, sizeBytes, modifiedEpochMillis, durationMillis, width, height, missingScanCount, lastSeenEpochMillis) " +
            "VALUES('$id', 'src', 'content://s/$id', 'external', '$id', '$id.mp4', 'video/mp4', 100, 1, 1000, 1920, 1080, 0, $lastSeen)"

    // ---- assertions --------------------------------------------------------

    private fun exec(sql: String) = database.openHelper.writableDatabase.execSQL(sql)

    private fun items(): List<String> =
        rows("SELECT id FROM media_items ORDER BY id").map { requireNotNull(it.single()) }

    private fun links(): List<Pair<String, String>> =
        rows("SELECT mediaItemId, locationId FROM media_item_locations ORDER BY locationId")
            .map { requireNotNull(it[0]) to requireNotNull(it[1]) }

    private fun table(sql: String): List<Pair<String, Long>> =
        rows(sql).map { requireNotNull(it[0]) to requireNotNull(it[1]!!.toLong()) }

    private fun scalar(sql: String): String = rows(sql).single().single().orEmpty()

    private fun rows(sql: String): List<List<String?>> =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).map { cursor.getString(it) })
                }
            }
        }

    private companion object {
        const val KEEP = "item-keep"
        const val LOSE = "item-lose"
        const val KEEP_LOCATION = "loc-keep"
        const val LOSE_LOCATION = "loc-lose"
    }
}
