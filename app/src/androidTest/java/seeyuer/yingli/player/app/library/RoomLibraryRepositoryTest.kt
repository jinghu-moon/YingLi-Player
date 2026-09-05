package seeyuer.yingli.player.app.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.core.database.MediaItemEntity
import seeyuer.yingli.player.core.database.MediaItemLocationEntity
import seeyuer.yingli.player.core.database.MediaLocationEntity
import seeyuer.yingli.player.core.database.MediaSourceEntity
import seeyuer.yingli.player.core.database.YingLiDatabase
import seeyuer.yingli.player.core.foundation.DefaultAppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaSourceId
import seeyuer.yingli.player.domain.library.LibraryQuery
import seeyuer.yingli.player.domain.library.LibraryResult

@RunWith(AndroidJUnit4::class)
class RoomLibraryRepositoryTest {
    private lateinit var database: YingLiDatabase
    private lateinit var repository: RoomLibraryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, YingLiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomLibraryRepository(database, DefaultAppDispatchers)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun pageUsesNewestAvailableLocationWithoutScanningEachItem() = runTest {
        seedSource()
        database.mediaCatalogDao().upsertItems(
            listOf(
                MediaItemEntity("item_1", "影片 1", 0, false),
                MediaItemEntity("item_2", "影片 2", 0, false),
                MediaItemEntity("item_3", "影片 3", 0, false),
            ),
        )
        database.mediaCatalogDao().insertLocations(
            listOf(
                location("old", "content://old", lastSeen = 10, missing = 0),
                location("new", "content://new", lastSeen = 20, missing = 0),
                location("second", "content://second", lastSeen = 15, missing = 0),
                location("missing", "content://missing", lastSeen = 30, missing = 1),
            ),
        )
        database.mediaCatalogDao().upsertLinks(
            listOf(
                MediaItemLocationEntity("item_1", "old"),
                MediaItemLocationEntity("item_1", "new"),
                MediaItemLocationEntity("item_2", "second"),
                MediaItemLocationEntity("item_3", "missing"),
            ),
        )

        val result = repository.query(LibraryQuery(pageSize = 1)) as LibraryResult.Success

        assertEquals(2, result.value.totalCount)
        assertEquals(listOf(MediaItemId("item_1")), result.value.items.map { it.id })
        assertEquals("content://new", result.value.items.single().uri.value)
        assertEquals(MediaItemId("item_1"), result.value.nextCursor?.mediaId)

        val next = repository.query(LibraryQuery(pageSize = 1, cursor = result.value.nextCursor)) as LibraryResult.Success
        assertEquals(listOf(MediaItemId("item_2")), next.value.items.map { it.id })
        assertNull(next.value.nextCursor)
    }

    private suspend fun seedSource() {
        database.mediaSourceDao().upsert(
            MediaSourceEntity(
                id = SOURCE_ID.value,
                displayName = "设备存储",
                rootUri = "content://media/external/video/media",
                mode = "MEDIA_STORE",
                volumeId = "external",
                accessState = "AVAILABLE",
                includeHidden = false,
                lastSyncedEpochMillis = null,
                mediaCount = 0,
            ),
        )
    }

    private fun location(id: String, uri: String, lastSeen: Long, missing: Int) = MediaLocationEntity(
        id = id,
        sourceId = SOURCE_ID.value,
        uri = uri,
        volumeId = null,
        documentId = id,
        fileName = "$id.mp4",
        mimeType = "video/mp4",
        sizeBytes = 1_024,
        modifiedEpochMillis = lastSeen,
        durationMillis = 5_000,
        width = 1_920,
        height = 1_080,
        missingScanCount = missing,
        lastSeenEpochMillis = lastSeen,
        fastFingerprint = null,
        contentHash = null,
    )

    private companion object {
        val SOURCE_ID = MediaSourceId("source_1")
    }
}
