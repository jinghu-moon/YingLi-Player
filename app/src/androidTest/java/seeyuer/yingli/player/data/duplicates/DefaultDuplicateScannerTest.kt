package seeyuer.yingli.player.data.duplicates

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.core.common.AppLogger
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.data.room.MediaCatalogDao
import seeyuer.yingli.player.data.room.MediaItemEntity
import seeyuer.yingli.player.data.room.MediaItemLocationEntity
import seeyuer.yingli.player.data.room.MediaLocationEntity
import seeyuer.yingli.player.data.room.MediaSourceEntity
import seeyuer.yingli.player.data.room.TrashEntryEntity
import seeyuer.yingli.player.data.room.YingLiDatabase
import seeyuer.yingli.player.domain.catalog.MediaContentHasher
import seeyuer.yingli.player.domain.duplicates.DUPLICATE_HASH_ALGORITHM_VERSION
import seeyuer.yingli.player.domain.duplicates.DuplicateMode
import seeyuer.yingli.player.domain.duplicates.DuplicateScanResult
import seeyuer.yingli.player.domain.recycle.TrashBackend
import seeyuer.yingli.player.domain.recycle.TrashState

/**
 * L0–L4 分层扫描的**真库**验证。
 *
 * 为什么是仪表化测试而不是 JVM 单测：分层本身由 SQL 完成（`GROUP BY … HAVING COUNT(*) >= 2`、
 * 键游标分页、算法版本过滤、回收站排除），这些恰恰是最容易写错的部分，而 JVM 上没有
 * SQLite/Room 可用；用假 DAO 去测只会验证「我自己写的假实现」。
 */
@RunWith(AndroidJUnit4::class)
class DefaultDuplicateScannerTest {
    private lateinit var database: YingLiDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, YingLiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun exactScanLayersSizeQuickAndFullHashAndPersistsOnlyVerifiedGroups() = runTest {
        // a / b / c 同为 100 字节，d 是 200 字节的独一份。
        seedCatalog(
            locations = listOf(location(A, sizeBytes = 100), location(B, sizeBytes = 100), location(C, sizeBytes = 100), location(D, sizeBytes = 200)),
        )
        val hasher = FakeHasher(
            quick = mapOf(uri(A) to hash('1'), uri(B) to hash('1'), uri(C) to hash('2'), uri(D) to hash('1')),
            full = mapOf(uri(A) to hash('a'), uri(B) to hash('a'), uri(C) to hash('c'), uri(D) to hash('a')),
        )
        val scanner = DefaultDuplicateScanner(database, hasher, AppLogger { _, _ -> })

        val result = scanner.scan(DuplicateMode.EXACT) as DuplicateScanResult.Completed

        assertEquals(3, result.candidateCount) // d 的大小是独一份，L0 就把它挡在门外
        assertEquals(3, result.quickHashedCount)
        assertEquals(2, result.fullHashedCount) // 只有快速指纹相同的那一对进入 L2
        assertEquals(0, result.failureCount)
        assertEquals(1, result.groupCount)
        assertEquals(3, hasher.quickCalls)
        assertEquals(2, hasher.fullCalls)
        // 200 字节那份从头到尾没被读过字节。
        assertNull(location(D).fastFingerprint)
        assertNull(location(D).contentHash)

        val groups = RoomDuplicateRepository(database).groups.first()
        assertEquals(1, groups.size)
        assertEquals(setOf("item-a", "item-b"), groups.single().candidates.map { it.mediaItemId.value }.toSet())
    }

    @Test
    fun alreadyHashedLocationsAreReusedInsteadOfRecomputed() = runTest {
        seedCatalog(locations = listOf(location(A, sizeBytes = 100), location(B, sizeBytes = 100)))
        persistHashes(A, quick = hash('1'), full = hash('a'))
        persistHashes(B, quick = hash('1'), full = hash('a'))
        val hasher = FakeHasher(quick = emptyMap(), full = emptyMap())

        val result = scanner(hasher).scan(DuplicateMode.EXACT) as DuplicateScanResult.Completed

        assertEquals(0, hasher.quickCalls)
        assertEquals(0, hasher.fullCalls)
        assertEquals(0, result.quickHashedCount)
        assertEquals(0, result.fullHashedCount)
        assertEquals(1, result.groupCount)
    }

    @Test
    fun hashesFromAnOlderAlgorithmVersionAreRecomputed() = runTest {
        seedCatalog(locations = listOf(location(A, sizeBytes = 100), location(B, sizeBytes = 100)))
        val staleVersion = DUPLICATE_HASH_ALGORITHM_VERSION - 1
        persistHashes(A, quick = hash('9'), full = hash('9'), version = staleVersion)
        persistHashes(B, quick = hash('9'), full = hash('9'), version = staleVersion)
        val hasher = FakeHasher(
            quick = mapOf(uri(A) to hash('1'), uri(B) to hash('1')),
            full = mapOf(uri(A) to hash('a'), uri(B) to hash('a')),
        )

        val result = scanner(hasher).scan(DuplicateMode.EXACT) as DuplicateScanResult.Completed

        assertEquals(2, hasher.quickCalls) // 旧版本的哈希必须整体失效
        assertEquals(2, hasher.fullCalls)
        assertEquals(1, result.groupCount)
        assertEquals(DUPLICATE_HASH_ALGORITHM_VERSION, location(A).hashAlgorithmVersion)
        assertEquals(hash('a'), location(A).contentHash)
    }

    @Test
    fun unreadableFileIsReportedAsFailureAndWritesNoHash() = runTest {
        seedCatalog(locations = listOf(location(A, sizeBytes = 100), location(B, sizeBytes = 100)))
        val hasher = FakeHasher(quick = mapOf(uri(A) to hash('1')), full = emptyMap())

        val result = scanner(hasher).scan(DuplicateMode.EXACT) as DuplicateScanResult.Completed

        assertEquals(1, result.failureCount)
        assertEquals(1, result.quickHashedCount)
        assertNull(location(B).fastFingerprint)
        assertEquals(0, result.groupCount)
    }

    @Test
    fun fileThatChangesWhileBeingHashedIsDiscarded() = runTest {
        seedCatalog(locations = listOf(location(A, sizeBytes = 100), location(B, sizeBytes = 100)))
        val hasher = FakeHasher(
            quick = mapOf(uri(A) to hash('1'), uri(B) to hash('1')),
            full = emptyMap(),
            onHash = { value ->
                // 模拟「扫描期间这个文件被替换了」。
                if (value == uri(B)) {
                    database.mediaCatalogDao().updateLocations(listOf(location(B, sizeBytes = 100).copy(modifiedEpochMillis = CHANGED_MILLIS)))
                }
            },
        )

        val result = scanner(hasher).scan(DuplicateMode.EXACT) as DuplicateScanResult.Completed

        assertEquals(1, result.failureCount)
        assertEquals(1, result.quickHashedCount)
        assertNull(location(B).fastFingerprint)
        assertEquals(0, result.groupCount)
    }

    @Test
    fun trashedLocationsAreExcludedFromTheScan() = runTest {
        seedCatalog(locations = listOf(location(A, sizeBytes = 100), location(B, sizeBytes = 100), location(C, sizeBytes = 100)))
        database.libraryDao().insertTrash(
            TrashEntryEntity(
                locationId = B,
                mediaItemId = "item-b",
                backend = TrashBackend.R1_SYSTEM.name,
                state = TrashState.ACTIVE.name,
                originalUri = uri(B),
                originalDisplayName = "b.mp4",
                originalSizeBytes = 100,
                trashedAtEpochMillis = 1,
                updatedAtEpochMillis = 1,
            ),
        )
        val hasher = FakeHasher(
            quick = mapOf(uri(A) to hash('1'), uri(C) to hash('1')),
            full = mapOf(uri(A) to hash('a'), uri(C) to hash('a')),
        )

        val result = scanner(hasher).scan(DuplicateMode.EXACT) as DuplicateScanResult.Completed

        assertEquals(2, result.candidateCount) // 已回收的那份既不进桶也不被读
        assertEquals(2, hasher.quickCalls)
        assertNull(location(B).fastFingerprint)
    }

    @Test
    fun similarModeStaysDisabled() = runTest {
        seedCatalog(locations = listOf(location(A, sizeBytes = 100), location(B, sizeBytes = 100)))
        val hasher = FakeHasher(quick = emptyMap(), full = emptyMap())

        assertEquals(
            DuplicateScanResult.Rejected("SIMILAR_EXPERIMENT_DISABLED"),
            scanner(hasher).scan(DuplicateMode.SIMILAR),
        )
        assertEquals(0, hasher.quickCalls)
    }

    // ---- fixtures ----------------------------------------------------------

    private fun scanner(hasher: MediaContentHasher) = DefaultDuplicateScanner(database, hasher, AppLogger { _, _ -> })

    private suspend fun seedCatalog(locations: List<MediaLocationEntity>) {
        val catalog: MediaCatalogDao = database.mediaCatalogDao()
        database.mediaSourceDao().upsert(
            MediaSourceEntity(
                id = SOURCE,
                displayName = "Movies",
                rootUri = "content://media/external/video",
                mode = "MEDIA_STORE",
                volumeId = "external",
                accessState = "GRANTED",
                includeHidden = false,
                lastSyncedEpochMillis = 0,
                mediaCount = locations.size,
            ),
        )
        val items = locations.map { MediaItemEntity(itemId(it.id), "item", 0, false) }
        catalog.upsertItems(items)
        catalog.insertLocations(locations)
        catalog.upsertLinks(
            locations.map { MediaItemLocationEntity(mediaItemId = itemId(it.id), locationId = it.id) },
        )
    }

    /** 一个位置一个条目：这样「同一 contentHash 跨两个条目」才是真正的重复。 */
    private fun itemId(locationId: String) = "item-" + locationId.substringAfter("location-")

    private suspend fun persistHashes(locationId: String, quick: String, full: String, version: Int = DUPLICATE_HASH_ALGORITHM_VERSION) {
        database.duplicateDao().updateFastFingerprint(locationId, quick, version)
        database.duplicateDao().updateContentHash(locationId, full, version)
    }

    private suspend fun location(id: String): MediaLocationEntity =
        requireNotNull(database.mediaCatalogDao().playableLocationById(id)) { "location $id is missing" }

    private fun location(id: String, sizeBytes: Long) = MediaLocationEntity(
        id = id,
        sourceId = SOURCE,
        uri = uri(id),
        volumeId = "external",
        documentId = null,
        fileName = "$id.mp4",
        mimeType = "video/mp4",
        sizeBytes = sizeBytes,
        modifiedEpochMillis = MODIFIED_MILLIS,
        durationMillis = 1_000,
        width = 1_920,
        height = 1_080,
        missingScanCount = 0,
        lastSeenEpochMillis = 1,
        fastFingerprint = null,
        contentHash = null,
    )

    private fun uri(id: String) = "content://media/external/video/media/$id"

    private fun hash(value: Char): String = value.toString().repeat(64)

    private class FakeHasher(
        private val quick: Map<String, String>,
        private val full: Map<String, String>,
        private val onHash: suspend (String) -> Unit = {},
    ) : MediaContentHasher {
        var quickCalls = 0
        var fullCalls = 0

        override suspend fun size(uri: MediaUri): Long? = null

        override suspend fun quickFingerprint(uri: MediaUri, sizeBytes: Long): String? {
            quickCalls++
            onHash(uri.value)
            return quick[uri.value]
        }

        override suspend fun sha256(uri: MediaUri): String? {
            fullCalls++
            onHash(uri.value)
            return full[uri.value]
        }
    }

    private companion object {
        const val SOURCE = "source-1"
        const val MODIFIED_MILLIS = 1_000L
        const val CHANGED_MILLIS = 2_000L
        const val A = "location-a"
        const val B = "location-b"
        const val C = "location-c"
        const val D = "location-d"
    }
}
