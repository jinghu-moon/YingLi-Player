package seeyuer.yingli.player.app.home

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.core.database.CollectionEntity
import seeyuer.yingli.player.core.database.CollectionItemEntity
import seeyuer.yingli.player.core.database.MediaItemEntity
import seeyuer.yingli.player.core.database.MediaItemLocationEntity
import seeyuer.yingli.player.core.database.MediaLocationEntity
import seeyuer.yingli.player.core.database.MediaSourceEntity
import seeyuer.yingli.player.core.database.PlaybackHistoryEntity
import seeyuer.yingli.player.core.database.YingLiDatabase

@RunWith(AndroidJUnit4::class)
class RoomHomeRepositoryTest {
    private lateinit var database: YingLiDatabase
    private lateinit var repository: RoomHomeRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, YingLiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomHomeRepository(database.homeDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun statsUseAllRowsWhilePreviewQueriesStayLimited() = runTest {
        seedSource("source_a", "Movies")
        val items = (0 until 250).map { index -> MediaItemEntity("item_$index", "视频 $index", 0, false) }
        database.mediaCatalogDao().upsertItems(items)
        database.mediaCatalogDao().insertLocations(items.mapIndexed { index, item -> location(item.id, "source_a", index.toLong()) })
        database.mediaCatalogDao().upsertLinks(items.map { item -> MediaItemLocationEntity(item.id, "location_${item.id}") })

        assertEquals(250 to 250L * 1_024, repository.observeStats().first())
        assertEquals(10, repository.observeRecentlyAdded(10).first().size)
        assertEquals("item_249", repository.observeRecentlyAdded(10).first().first().mediaId.value)
    }

    @Test
    fun continueWatchingAppliesEligibilityAndLastPlayedOrderingInDatabase() = runTest {
        seedSource("source_a", "Movies")
        val items = listOf(
            MediaItemEntity("eligible_old", "旧记录", 30_000, false),
            MediaItemEntity("eligible_new", "新记录", 40_000, false),
            MediaItemEntity("too_early", "未达到阈值", 9_999, false),
            MediaItemEntity("near_end", "接近结束", 118_000, false),
            MediaItemEntity("completed", "已完成", 30_000, true),
        )
        database.mediaCatalogDao().upsertItems(items)
        database.mediaCatalogDao().insertLocations(items.mapIndexed { index, item -> location(item.id, "source_a", index.toLong()) })
        database.mediaCatalogDao().upsertLinks(items.map { item -> MediaItemLocationEntity(item.id, "location_${item.id}") })
        items.forEachIndexed { index, item ->
            database.organizeDao().upsertHistory(PlaybackHistoryEntity(item.id, 1, index.toLong(), item.playbackPositionMillis))
        }

        assertEquals(
            listOf("eligible_new", "eligible_old"),
            repository.observeContinueWatching(10).first().map { it.mediaId.value },
        )
    }

    @Test
    fun foldersAndCollectionsAreAggregatedAndLimited() = runTest {
        seedSource("source_a", "Movies")
        seedSource("source_b", "Downloads")
        val items = (0 until 5).map { index -> MediaItemEntity("item_$index", "视频 $index", 0, false) }
        database.mediaCatalogDao().upsertItems(items)
        database.mediaCatalogDao().insertLocations(items.mapIndexed { index, item ->
            location(item.id, if (index < 3) "source_a" else "source_b", index.toLong())
        })
        database.mediaCatalogDao().upsertLinks(items.map { item -> MediaItemLocationEntity(item.id, "location_${item.id}") })
        database.organizeDao().upsertCollection(CollectionEntity("collection_a", "周末", "MANUAL", null, 0, 2))
        database.organizeDao().insertCollectionItems(
            listOf(
                CollectionItemEntity("collection_a", "item_0", 0),
                CollectionItemEntity("collection_a", "item_1", 1),
            ),
        )

        val folders = repository.observeFrequentFolders(1).first()
        assertEquals(1, folders.size)
        assertEquals("Movies", folders.single().name)
        assertEquals(3, folders.single().itemCount)
        assertEquals(2, repository.observeCollections(1).first().single().itemCount)
    }

    private suspend fun seedSource(id: String, name: String) {
        database.mediaSourceDao().upsert(
            MediaSourceEntity(id, name, "content://$id", "MEDIA_STORE", id, "AVAILABLE", false, null, 0),
        )
    }

    private fun location(itemId: String, sourceId: String, modified: Long) = MediaLocationEntity(
        id = "location_$itemId",
        sourceId = sourceId,
        uri = "content://media/$itemId",
        volumeId = sourceId,
        documentId = itemId,
        fileName = "$itemId.mp4",
        mimeType = "video/mp4",
        sizeBytes = 1_024,
        modifiedEpochMillis = modified,
        durationMillis = 120_000,
        width = 1_920,
        height = 1_080,
        missingScanCount = 0,
        lastSeenEpochMillis = modified,
        fastFingerprint = null,
        contentHash = null,
    )
}
