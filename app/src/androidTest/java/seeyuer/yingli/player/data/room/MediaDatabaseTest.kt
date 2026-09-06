package seeyuer.yingli.player.data.room

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import seeyuer.yingli.player.domain.catalog.CatalogMutation
import seeyuer.yingli.player.core.model.media.MediaItem
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocation
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaSourceId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.data.room.RoomPlaybackRepository
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext

@RunWith(AndroidJUnit4::class)
class MediaDatabaseTest {
    private lateinit var database: YingLiDatabase
    private lateinit var repository: RoomMediaCatalogRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, YingLiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomMediaCatalogRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun duplicateUriIsRejected() = runTest {
        seedSource()
        database.mediaCatalogDao().insertLocations(listOf(locationEntity("location_1", URI)))

        expectConstraintFailure {
            database.mediaCatalogDao().insertLocations(listOf(locationEntity("location_2", URI)))
        }
    }

    @Test
    fun orphanLocationIsRejectedByForeignKey() = runTest {
        expectConstraintFailure {
            database.mediaCatalogDao().insertLocations(listOf(locationEntity("orphan", URI)))
        }
    }

    @Test
    fun failedMutationRollsBackItemsAndLocationsAtomically() = runTest {
        seedSource()
        val item = MediaItem(MediaItemId("item_1"), "影片")
        val location = location("location_1", URI)

        expectConstraintFailure {
            repository.applyMutation(SOURCE_ID, CatalogMutation(
                upsertItems = listOf(item),
                upsertLocations = listOf(location),
                links = mapOf(location.id to MediaItemId("missing_item")),
                seenLocationIds = setOf(location.id),
                scanCompletedAtEpochMillis = 200,
            ))
        }

        val snapshot = repository.snapshot(SOURCE_ID)
        assertTrue(snapshot.items.isEmpty())
        assertTrue(snapshot.locations.isEmpty())
    }

    @Test
    fun repeatedMissingScansIncrementCounterWithoutDeletingRelationships() = runTest {
        seedSource()
        val item = MediaItem(MediaItemId("item_1"), "影片", tags = setOf("收藏"))
        val location = location("location_1", URI)
        repository.applyMutation(SOURCE_ID, CatalogMutation(
            upsertItems = listOf(item),
            upsertLocations = listOf(location),
            links = mapOf(location.id to item.id),
            seenLocationIds = setOf(location.id),
            scanCompletedAtEpochMillis = 200,
        ))

        repeat(2) { index ->
            repository.applyMutation(SOURCE_ID, CatalogMutation(
                upsertItems = emptyList(),
                upsertLocations = emptyList(),
                links = emptyMap(),
                seenLocationIds = emptySet(),
                scanCompletedAtEpochMillis = 300L + index,
            ))
        }

        val snapshot = repository.snapshot(SOURCE_ID)
        assertEquals(2, snapshot.locations.single().missingScanCount)
        assertEquals(item.id, snapshot.itemByLocation.getValue(location.id))
        assertEquals(setOf("收藏"), snapshot.items.single().tags)
    }

    @Test
    fun playbackRepositoryResolvesResumePositionAndPersistsProgress() = runTest {
        seedSource()
        val item = MediaItem(MediaItemId("item_1"), "影片", playbackPositionMillis = 10_000)
        val location = location("location_1", URI).copy(durationMillis = 60_000)
        repository.applyMutation(SOURCE_ID, CatalogMutation(
            upsertItems = listOf(item),
            upsertLocations = listOf(location),
            links = mapOf(location.id to item.id),
            seenLocationIds = setOf(location.id),
            scanCompletedAtEpochMillis = 200,
        ))
        val playbackRepository = RoomPlaybackRepository(database)

        val source = playbackRepository.resolve(item.id, PlaybackSourceContext.HOME)
        playbackRepository.saveProgress(item.id, 20_000, completed = false)

        assertEquals(7_000L, source?.request?.startPositionMillis)
        assertEquals(20_000L, database.mediaCatalogDao().item(item.id.value)?.playbackPositionMillis)
        assertEquals(false, database.mediaCatalogDao().item(item.id.value)?.completed)
    }

    private suspend fun expectConstraintFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected SQLiteConstraintException")
        } catch (_: SQLiteConstraintException) {
            // Expected: Room must leave the transaction unchanged.
        }
    }

    private suspend fun seedSource() {
        database.mediaSourceDao().upsert(MediaSourceEntity(
            id = SOURCE_ID.value,
            displayName = "系统媒体库",
            rootUri = "content://media/external/video/media",
            mode = "MEDIA_STORE",
            volumeId = "primary",
            accessState = "AVAILABLE",
            includeHidden = false,
            lastSyncedEpochMillis = null,
            mediaCount = 0,
        ))
    }

    private fun location(id: String, uri: String) = MediaLocation(
        id = MediaLocationId(id),
        sourceId = SOURCE_ID,
        uri = MediaUri(uri),
        volumeId = null,
        documentId = id,
        fileName = "movie.mp4",
        mimeType = "video/mp4",
        sizeBytes = 1_024,
        modifiedEpochMillis = 100,
        durationMillis = 5_000,
        width = 1_920,
        height = 1_080,
        lastSeenEpochMillis = 200,
    )

    private fun locationEntity(id: String, uri: String) = MediaLocationEntity(
        id = id,
        sourceId = SOURCE_ID.value,
        uri = uri,
        volumeId = null,
        documentId = id,
        fileName = "movie.mp4",
        mimeType = "video/mp4",
        sizeBytes = 1_024,
        modifiedEpochMillis = 100,
        durationMillis = 5_000,
        width = 1_920,
        height = 1_080,
        missingScanCount = 0,
        lastSeenEpochMillis = 200,
        fastFingerprint = null,
        contentHash = null,
    )

    private companion object {
        val SOURCE_ID = MediaSourceId("source_1")
        const val URI = "content://media/video/1"
    }
}
