package seeyuer.yingli.player.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.data.room.MediaItemEntity
import seeyuer.yingli.player.data.room.MediaItemLocationEntity
import seeyuer.yingli.player.data.room.MediaLocationEntity
import seeyuer.yingli.player.data.room.MediaSourceEntity
import seeyuer.yingli.player.data.room.YingLiDatabase
import seeyuer.yingli.player.core.common.DefaultAppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaSourceId
import seeyuer.yingli.player.domain.library.LibraryQuery
import seeyuer.yingli.player.domain.library.LibraryPageDirection
import seeyuer.yingli.player.domain.library.LibraryBrowseMode
import seeyuer.yingli.player.domain.library.LibraryResult
import seeyuer.yingli.player.domain.library.LibrarySortField
import seeyuer.yingli.player.domain.library.SortDirection
import seeyuer.yingli.player.domain.library.SortSpec

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

    @Test
    fun prependRestoresTheExactDroppedPageWithoutDuplicatesOrGaps() = runTest {
        seedSource()
        val itemIds = (0 until 12).map { index -> "item_${index.toString().padStart(2, '0')}" }
        database.mediaCatalogDao().upsertItems(
            itemIds.map { id -> MediaItemEntity(id, "影片", 0, false) },
        )
        database.mediaCatalogDao().insertLocations(
            itemIds.mapIndexed { index, id ->
                // Repeated values exercise the media ID tie breaker.
                location("location_$id", "content://media/$id", lastSeen = (index / 3).toLong(), missing = 0)
            },
        )
        database.mediaCatalogDao().upsertLinks(
            itemIds.map { id -> MediaItemLocationEntity(id, "location_$id") },
        )

        listOf(SortDirection.ASCENDING, SortDirection.DESCENDING).forEach { sortDirection ->
            val baseQuery = LibraryQuery(
                sort = SortSpec(LibrarySortField.RECENTLY_ADDED, sortDirection),
                pageSize = 4,
            )
            val first = repository.page(baseQuery, LibraryPageDirection.REFRESH)
            val second = repository.page(
                baseQuery.copy(cursor = requireNotNull(first.nextCursor)),
                LibraryPageDirection.APPEND,
            )
            val restored = repository.page(
                baseQuery.copy(cursor = requireNotNull(second.previousCursor)),
                LibraryPageDirection.PREPEND,
            )
            val refreshed = repository.page(
                baseQuery.copy(cursor = requireNotNull(second.previousCursor)),
                LibraryPageDirection.REFRESH,
            )

            assertEquals(first.items.map { it.id }, restored.items.map { it.id })
            assertEquals(first.items.size, restored.items.map { it.id }.distinct().size)
            assertNull(restored.previousCursor)
            assertNotNull(restored.nextCursor)
            assertEquals(second.items.map { it.id }, refreshed.items.map { it.id })
        }
    }

    @Test
    fun folderBrowseBuildsRealDirectoryHierarchyFromRelativePaths() = runTest {
        seedSource()
        val entries = listOf(
            Triple("camera", "DCIM/Camera", 2_048L),
            Triple("root_dcim", "DCIM", 1_024L),
            Triple("movie", "Movies", 4_096L),
        )
        database.mediaCatalogDao().upsertItems(entries.map { (id) -> MediaItemEntity(id, id, 0, false) })
        database.mediaCatalogDao().insertLocations(entries.map { (id, path, size) ->
            location(id, "content://media/$id", lastSeen = 1, missing = 0, relativePath = path, sizeBytes = size)
        })
        database.mediaCatalogDao().upsertLinks(entries.map { (id) -> MediaItemLocationEntity(id, id) })

        val roots = repository.folders(LibraryQuery(browseMode = LibraryBrowseMode.FOLDER))
        val dcimChildren = repository.folders(LibraryQuery(browseMode = LibraryBrowseMode.FOLDER, currentPath = "DCIM"))
        val directVideos = repository.page(
            LibraryQuery(browseMode = LibraryBrowseMode.FOLDER, currentPath = "DCIM"),
            LibraryPageDirection.REFRESH,
        )

        assertEquals(listOf("DCIM", "Movies"), roots.map { it.path })
        assertEquals(3_072L, roots.first { it.path == "DCIM" }.sizeBytes)
        assertEquals(listOf("DCIM/Camera"), dcimChildren.map { it.path })
        assertEquals(listOf(MediaItemId("root_dcim")), directVideos.items.map { it.id })
    }

    @Test
    fun folderTreeCountIncludesEveryDescendantButNotSimilarPrefix() = runTest {
        seedSource()
        val entries = listOf(
            "direct" to "DCIM",
            "nested" to "DCIM/Camera",
            "deep" to "DCIM/Camera/Trips",
            "similar_prefix" to "DCIM2/Camera",
            "outside" to "Movies",
            "wildcard_path" to "DCIM_2026/Camera",
            "wildcard_false_match" to "DCIMX2026/Camera",
        )
        database.mediaCatalogDao().upsertItems(entries.map { (id) -> MediaItemEntity(id, id, 0, false) })
        database.mediaCatalogDao().insertLocations(entries.map { (id, path) ->
            location(id, "content://media/$id", lastSeen = 1, missing = 0, relativePath = path)
        })
        database.mediaCatalogDao().upsertLinks(entries.map { (id) -> MediaItemLocationEntity(id, id) })

        val count = repository.observeFolderTreeVideoCount("DCIM").first()
        val wildcardCount = repository.observeFolderTreeVideoCount("DCIM_2026").first()

        assertEquals(3, count)
        assertEquals(1, wildcardCount)
    }

    @Test
    fun folderBrowsePrefersMediaLocationInsideSelectedDirectoryTree() = runTest {
        seedSource()
        database.mediaCatalogDao().upsertItems(listOf(MediaItemEntity("shared", "共享视频", 0, false)))
        database.mediaCatalogDao().insertLocations(
            listOf(
                location(
                    id = "z_outside",
                    uri = "content://media/outside",
                    lastSeen = 10,
                    missing = 0,
                    relativePath = "Download/Other",
                ),
                location(
                    id = "a_inside",
                    uri = "content://media/inside",
                    lastSeen = 10,
                    missing = 0,
                    relativePath = "A-测试面包屑/长目录/视频所在地",
                ),
            ),
        )
        database.mediaCatalogDao().upsertLinks(
            listOf(
                MediaItemLocationEntity("shared", "z_outside"),
                MediaItemLocationEntity("shared", "a_inside"),
            ),
        )

        val page = repository.page(
            LibraryQuery(
                browseMode = LibraryBrowseMode.FOLDER,
                currentPath = "A-测试面包屑/长目录/视频所在地",
            ),
            LibraryPageDirection.REFRESH,
        )
        val count = repository.observeFolderTreeVideoCount("A-测试面包屑/长目录/视频所在地").first()

        assertEquals(listOf(MediaItemId("shared")), page.items.map { it.id })
        assertEquals("content://media/inside", page.items.single().uri.value)
        assertEquals(1, count)
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

    private fun location(
        id: String,
        uri: String,
        lastSeen: Long,
        missing: Int,
        relativePath: String? = null,
        sizeBytes: Long = 1_024,
    ) = MediaLocationEntity(
        id = id,
        sourceId = SOURCE_ID.value,
        uri = uri,
        volumeId = null,
        documentId = id,
        fileName = "$id.mp4",
        mimeType = "video/mp4",
        sizeBytes = sizeBytes,
        modifiedEpochMillis = lastSeen,
        durationMillis = 5_000,
        width = 1_920,
        height = 1_080,
        missingScanCount = missing,
        lastSeenEpochMillis = lastSeen,
        fastFingerprint = null,
        contentHash = null,
        relativePath = relativePath,
    )

    private companion object {
        val SOURCE_ID = MediaSourceId("source_1")
    }
}
