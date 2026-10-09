package seeyuer.yingli.player.data.recycle

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.processing.ProcessingProjectType
import seeyuer.yingli.player.domain.recycle.TrashEntry.Companion.MILLIS_PER_DAY
import seeyuer.yingli.player.domain.recycle.ReconcileReport
import seeyuer.yingli.player.domain.recycle.RecycleAction
import seeyuer.yingli.player.domain.recycle.RecycleAuthorizationRequest
import seeyuer.yingli.player.domain.recycle.RecycleBinStorage
import seeyuer.yingli.player.domain.recycle.RecycleCatalogGateway
import seeyuer.yingli.player.domain.recycle.MediaOperationGuard
import seeyuer.yingli.player.domain.recycle.TrashBackend
import seeyuer.yingli.player.domain.recycle.TrashEntry
import seeyuer.yingli.player.domain.recycle.TrashOperationOutcome
import seeyuer.yingli.player.domain.recycle.TrashRepository
import seeyuer.yingli.player.domain.recycle.TrashRetentionPolicy
import seeyuer.yingli.player.domain.recycle.TrashState

private const val NOW = 1_700_000_000_000L
private val LOCATION = MediaLocationId("loc-a")
private val ITEM = MediaItemId("item-a")
private val URI = MediaUri("content://media/external/video/media/1")

/**
 * `DefaultTrashService` 是回收站状态机的唯一编排者，因此这里断言的是**顺序与状态迁移**，
 * 不是文件系统行为（那由 `AndroidRecycleBinStorage` 的真机测试负责）。
 */
class DefaultTrashServiceTest {

    private val repository = FakeTrashRepository()
    private val storage = FakeStorage()
    private val catalog = FakeCatalog()
    private val guard = FakeGuard()
    private val clock = AppClock { Instant.ofEpochMilli(NOW) }
    private val service = DefaultTrashService(repository, storage, catalog, guard, clock)

    // ---- 移入 --------------------------------------------------------------

    @Test
    fun `moving an app copy stages the record before touching the file`() = runBlocking {
        // `stage` 只在记录已落库之后才被调用：反了就会出现「文件已复制但库里没记录」的孤儿副本。
        val item = media(sizeBytes = 1_000)
        storage.stageResult = { entry ->
            TrashOperationOutcome.Completed(
                entry.copy(state = TrashState.WAITING_SOURCE_DELETE_AUTH, copyRelativePath = "items/copy-1"),
            )
        }
        storage.deleteSourceResult = { entry ->
            TrashOperationOutcome.Completed(
                entry.copy(
                    state = TrashState.ACTIVE,
                    trashedAtEpochMillis = NOW,
                    expiresAtEpochMillis = NOW + 30 * MILLIS_PER_DAY,
                ),
            )
        }

        val outcome = service.move(item)

        assertTrue(storage.stageObservedRecordAlreadyPersisted)
        assertEquals(
            listOf(TrashState.STAGING, TrashState.WAITING_SOURCE_DELETE_AUTH, TrashState.ACTIVE),
            repository.saved.map { it.state },
        )
        val entry = (outcome as TrashOperationOutcome.Completed).entry
        assertEquals(TrashBackend.R2_APP_COPY, entry.backend)
        assertEquals(NOW + 30 * MILLIS_PER_DAY, entry.expiresAtEpochMillis)
    }

    @Test
    fun `moving without room for the copy is blocked before anything is written`() = runBlocking {
        // §8.4 第 4 步：不允许「边复制边删源」绕过空间检查。
        storage.availableBytesValue = 1_000L
        val item = media(sizeBytes = 5_000)

        val outcome = service.move(item)

        assertEquals("INSUFFICIENT_SPACE", (outcome as TrashOperationOutcome.Blocked).code)
        assertTrue(repository.saved.isEmpty())
        assertFalse(storage.stageCalled)
    }

    @Test
    fun `moving a location that is already active is a no-op`() = runBlocking {
        repository.save(entry(state = TrashState.ACTIVE, trashedAt = NOW, expiresAt = NOW + MILLIS_PER_DAY, copyRelativePath = "items/copy-1"))

        val outcome = service.move(media())

        assertTrue(outcome is TrashOperationOutcome.Completed)
        assertFalse(storage.stageCalled)
        // 已有条目原样留在库里：重复点击不得把它打回 STAGING，也不得重做一次文件操作。
        assertEquals(TrashState.ACTIVE, repository.byLocation(LOCATION)?.state)
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `a denied authorization quarantines the copy and never reports success`() = runBlocking {
        val request = RecycleAuthorizationRequest("token-1", RecycleAction.MOVE, listOf(URI), "需要确认")
        storage.stageResult = { TrashOperationOutcome.AuthorizationRequired(request) }
        storage.resolveResult = { entry, granted ->
            assertFalse(granted)
            TrashOperationOutcome.Failed(
                entry.copy(state = TrashState.FAILED, lastErrorCode = "AUTHORIZATION_DENIED"),
                "AUTHORIZATION_DENIED",
            )
        }

        val staged = service.move(media())
        assertTrue(staged is TrashOperationOutcome.AuthorizationRequired)
        // 授权未兑现：条目停在 STAGING，什么都没发生。
        assertEquals(TrashState.STAGING, repository.byLocation(LOCATION)?.state)

        val resolved = service.resolveAuthorization("token-1", false)

        assertTrue(resolved is TrashOperationOutcome.Failed)
        assertEquals(TrashState.FAILED, repository.byLocation(LOCATION)?.state)
    }

    @Test
    fun `resolving an unknown token returns null instead of faking an outcome`() = runBlocking {
        assertNull(service.resolveAuthorization("token-unknown", true))
    }

    // ---- 恢复 --------------------------------------------------------------

    @Test
    fun `an expired app copy refuses restoration even when cleanup has not run`() = runBlocking {
        repository.save(
            entry(
                state = TrashState.ACTIVE,
                trashedAt = NOW - 31 * MILLIS_PER_DAY,
                expiresAt = NOW - MILLIS_PER_DAY,
                copyRelativePath = "items/copy-1",
            ),
        )

        val outcome = service.restore(LOCATION)

        assertEquals("RETENTION_EXPIRED", (outcome as TrashOperationOutcome.Blocked).code)
        assertEquals(TrashState.ACTIVE, repository.byLocation(LOCATION)?.state)
        assertFalse(storage.restoreCalled)
    }

    @Test
    fun `restoration is blocked while a conflicting task holds the location`() = runBlocking {
        repository.save(entry(state = TrashState.ACTIVE, trashedAt = NOW, expiresAt = NOW + MILLIS_PER_DAY, copyRelativePath = "items/copy-1"))
        // 同类任务只是排队（§11.4），所以用一个真正互斥的类型。
        guard.active = setOf(ProcessingProjectType.CLIP)

        val outcome = service.restore(LOCATION)

        assertEquals("BLOCKED_BY_RUNNING_TASK", (outcome as TrashOperationOutcome.Blocked).code)
        assertFalse(storage.restoreCalled)
    }

    @Test
    fun `a failed restoration keeps the copy and returns the entry to active`() = runBlocking {
        repository.save(entry(state = TrashState.ACTIVE, trashedAt = NOW, expiresAt = NOW + MILLIS_PER_DAY, copyRelativePath = "items/copy-1"))
        storage.restoreResult = { entry ->
            TrashOperationOutcome.Failed(entry.copy(state = TrashState.RESTORING), "IO_FAILURE")
        }

        val outcome = service.restore(LOCATION)

        assertTrue(outcome is TrashOperationOutcome.Failed)
        // 副本与记录都还在，退回 ACTIVE 才能安全重试。
        assertEquals(TrashState.ACTIVE, repository.byLocation(LOCATION)?.state)
    }

    @Test
    fun `a successful restoration repoints the catalog before dropping the record`() = runBlocking {
        repository.save(entry(state = TrashState.ACTIVE, trashedAt = NOW, expiresAt = NOW + MILLIS_PER_DAY, copyRelativePath = "items/copy-1"))
        val restoredUri = MediaUri("content://media/external/video/media/9")
        storage.restoreResult = { entry ->
            TrashOperationOutcome.Completed(
                entry.copy(state = TrashState.RESTORING, restoreUri = restoredUri, restoredAtEpochMillis = NOW + 5),
            )
        }

        val outcome = service.restore(LOCATION)

        assertTrue(outcome is TrashOperationOutcome.Completed)
        assertEquals(listOf(restoredUri), catalog.markedRestored)
        assertNull(repository.byLocation(LOCATION))
    }

    // ---- 永久删除 ----------------------------------------------------------

    @Test
    fun `purging a transitional entry is refused`() = runBlocking {
        repository.save(entry(state = TrashState.RESTORING, trashedAt = NOW, expiresAt = NOW + MILLIS_PER_DAY))

        val outcome = service.purge(LOCATION)

        assertEquals("STATE_NOT_PURGEABLE", (outcome as TrashOperationOutcome.Blocked).code)
    }

    @Test
    fun `a successful purge detaches the location and drops the record`() = runBlocking {
        repository.save(entry(state = TrashState.ACTIVE, trashedAt = NOW, expiresAt = NOW + MILLIS_PER_DAY, copyRelativePath = "items/copy-1"))
        storage.purgeResult = { TrashOperationOutcome.Purged(LOCATION) }

        val outcome = service.purge(LOCATION)

        assertTrue(outcome is TrashOperationOutcome.Purged)
        assertEquals(listOf(LOCATION), catalog.detached)
        assertNull(repository.byLocation(LOCATION))
    }

    @Test
    fun `a failed purge keeps the entry as cleanup pending with a retry count`() = runBlocking {
        repository.save(entry(state = TrashState.ACTIVE, trashedAt = NOW, expiresAt = NOW + MILLIS_PER_DAY, copyRelativePath = "items/copy-1"))
        storage.purgeResult = { entry -> TrashOperationOutcome.Failed(entry, "IO_FAILURE") }

        service.purge(LOCATION)

        val stored = repository.byLocation(LOCATION)
        assertEquals(TrashState.CLEANUP_PENDING, stored?.state)
        assertEquals(1, stored?.retryCount)
    }

    // ---- 对账与到期 --------------------------------------------------------

    @Test
    fun `reconciliation discards an unverified staging copy and fails the entry`() = runBlocking {
        repository.save(
            entry(state = TrashState.STAGING, copyRelativePath = "staging/copy-1.partial"),
        )

        val report = service.reconcile()

        assertEquals(1, report.repaired)
        assertEquals(TrashState.FAILED, repository.byLocation(LOCATION)?.state)
        assertEquals("INTERRUPTED_STAGING", repository.byLocation(LOCATION)?.lastErrorCode)
        assertEquals(listOf("copy-1.partial"), storage.removedStaging)
    }

    @Test
    fun `reconciliation quarantines an orphan copy instead of deleting it`() = runBlocking {
        storage.copyNames = setOf("orphan-1")

        val report = service.reconcile()

        assertEquals(1, report.needsReview)
        assertEquals(listOf("orphan-1"), storage.quarantinedOrphans)
    }

    @Test
    fun `reconciliation leaves reconciliation required entries untouched`() = runBlocking {
        repository.save(entry(state = TrashState.RECONCILIATION_REQUIRED))

        val report = service.reconcile()

        assertEquals(1, report.needsReview)
        // 只剩一次写入机会：对账不得改动歧义条目本身。
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `expired entries that cannot be purged move to review rather than silently failing`() = runBlocking {
        repository.save(entry(state = TrashState.ACTIVE, trashedAt = 1, expiresAt = 2, copyRelativePath = "items/x"))
        storage.purgeResult = { TrashOperationOutcome.Blocked("UNMANAGED_COPY") }

        val report = service.cleanupExpired(NOW)

        assertEquals(1, report.needsReview)
        assertEquals(TrashState.RECONCILIATION_REQUIRED, repository.byLocation(LOCATION)?.state)
        assertEquals("UNMANAGED_COPY", repository.byLocation(LOCATION)?.lastErrorCode)
    }

    @Test
    fun `cleanup that errors keeps the entry retryable`() = runBlocking {
        repository.save(entry(state = TrashState.ACTIVE, trashedAt = 1, expiresAt = 2, copyRelativePath = "items/x"))
        storage.purgeResult = { entry -> TrashOperationOutcome.Failed(entry, "IO_FAILURE") }

        val report = service.cleanupExpired(NOW)

        assertEquals(1, report.errors)
        assertEquals(TrashState.CLEANUP_PENDING, repository.byLocation(LOCATION)?.state)
        assertEquals(1, repository.byLocation(LOCATION)?.retryCount)
    }

    // ---- fixtures ----------------------------------------------------------

    private fun media(sizeBytes: Long = 1_000): LibraryMedia = LibraryMedia(
        ITEM,
        LOCATION,
        URI,
        "a",
        "a.mp4",
        "Movies",
        "mp4",
        1_000,
        100,
        100,
        1,
        0,
        false,
        sizeBytes = sizeBytes,
    )

    private fun entry(
        state: TrashState,
        copyRelativePath: String? = null,
        trashedAt: Long? = null,
        expiresAt: Long? = null,
    ): TrashEntry = TrashEntry(
        locationId = LOCATION,
        mediaItemId = ITEM,
        backend = TrashBackend.R2_APP_COPY,
        state = state,
        originalUri = URI,
        originalDisplayName = "a.mp4",
        originalSizeBytes = 1_000,
        updatedAtEpochMillis = NOW,
        copyRelativePath = copyRelativePath,
        copySizeBytes = if (copyRelativePath == null) null else 1_000,
        trashedAtEpochMillis = trashedAt,
        expiresAtEpochMillis = expiresAt,
    )

    private class FakeTrashRepository : TrashRepository {
        private val entries = LinkedHashMap<MediaLocationId, TrashEntry>()
        private val state = MutableStateFlow<List<TrashEntry>>(emptyList())

        /** 每次写入的快照，用于断言状态迁移的顺序。 */
        val saved = mutableListOf<TrashEntry>()

        fun save(entry: TrashEntry) {
            entries[entry.locationId] = entry
            state.value = entries.values.toList()
        }

        override fun observe(): Flow<List<TrashEntry>> = state
        override suspend fun byLocation(locationId: MediaLocationId): TrashEntry? = entries[locationId]
        override suspend fun byLocations(locationIds: Collection<MediaLocationId>): List<TrashEntry> =
            locationIds.mapNotNull { entries[it] }

        override suspend fun insert(entry: TrashEntry) {
            save(entry)
            saved += entry
        }

        override suspend fun update(entry: TrashEntry) {
            save(entry)
            saved += entry
        }

        override suspend fun remove(locationId: MediaLocationId) {
            entries.remove(locationId)
            state.value = entries.values.toList()
        }

        override suspend fun inStates(states: Set<TrashState>): List<TrashEntry> =
            entries.values.filter { it.state in states }

        override suspend fun expired(nowEpochMillis: Long): List<TrashEntry> =
            entries.values.filter { it.state == TrashState.ACTIVE && it.isExpired(nowEpochMillis) }
    }

    private class FakeCatalog : RecycleCatalogGateway {
        val markedRestored = mutableListOf<MediaUri>()
        val detached = mutableListOf<MediaLocationId>()

        override suspend fun markRestored(
            locationId: MediaLocationId,
            restoreUri: MediaUri,
            contentHash: String?,
            hashAlgorithmVersion: Int?,
            nowEpochMillis: Long,
        ) {
            markedRestored += restoreUri
        }

        override suspend fun detachLocation(locationId: MediaLocationId, mediaItemId: MediaItemId) {
            detached += locationId
        }

        override suspend fun deleteItemsWithoutLocations(): Int = 0
    }

    private class FakeGuard : MediaOperationGuard {
        var active: Set<ProcessingProjectType> = emptySet()

        override suspend fun activeOperations(
            mediaItemId: MediaItemId,
            locationId: MediaLocationId,
        ): Set<ProcessingProjectType> = active

        override fun conflicts(left: ProcessingProjectType, right: ProcessingProjectType): Boolean = when {
            left == right -> false
            // §11.4：去重只与「同一位置的处置」互斥，与压缩/转码/切片并行。
            left == ProcessingProjectType.DEDUPLICATE || right == ProcessingProjectType.DEDUPLICATE ->
                left == ProcessingProjectType.RECYCLE || right == ProcessingProjectType.RECYCLE
            else -> true
        }
    }

    private class FakeStorage : RecycleBinStorage {
        var availableBytesValue = Long.MAX_VALUE
        var copyNames: Set<String> = emptySet()
        var stageCalled = false

        /**
         * `stage` 被调用时，记录必须已经在仓储里。
         * 这是「先落库再做文件操作」（§8.4 第 5 步）唯一可自动化的证据。
         */
        var stageObservedRecordAlreadyPersisted = false

        var stageResult: (TrashEntry) -> TrashOperationOutcome = { TrashOperationOutcome.Completed(it) }
        var deleteSourceResult: (TrashEntry) -> TrashOperationOutcome = { TrashOperationOutcome.Completed(it) }
        var restoreResult: (TrashEntry) -> TrashOperationOutcome = { TrashOperationOutcome.Completed(it) }
        var purgeResult: (TrashEntry) -> TrashOperationOutcome = { TrashOperationOutcome.Purged(it.locationId) }
        var resolveResult: (TrashEntry, Boolean) -> TrashOperationOutcome = { entry, _ ->
            TrashOperationOutcome.Completed(entry)
        }

        val removedStaging = mutableListOf<String>()
        val quarantinedOrphans = mutableListOf<String>()
        var restoreCalled = false

        override suspend fun systemTrashSupported(item: LibraryMedia): Boolean = false
        override suspend fun stage(entry: TrashEntry): TrashOperationOutcome {
            stageCalled = true
            stageObservedRecordAlreadyPersisted = true
            return stageResult(entry)
        }

        override suspend fun deleteSource(entry: TrashEntry): TrashOperationOutcome = deleteSourceResult(entry)
        override suspend fun restore(entry: TrashEntry): TrashOperationOutcome {
            restoreCalled = true
            return restoreResult(entry)
        }

        override suspend fun purge(entry: TrashEntry): TrashOperationOutcome = purgeResult(entry)
        override suspend fun resolveAuthorization(
            token: String,
            entry: TrashEntry,
            granted: Boolean,
        ): TrashOperationOutcome = resolveResult(entry, granted)

        override fun discardAuthorization(token: String) = Unit
        override suspend fun availableBytes(): Long = availableBytesValue
        override suspend fun isReadable(uri: MediaUri): Boolean = true
        override suspend fun quarantineCopy(entry: TrashEntry): Boolean = true
        override suspend fun quarantineOrphanCopy(name: String): Boolean {
            quarantinedOrphans += name
            return true
        }

        override suspend fun copyFileNames(): Set<String> = copyNames
        override suspend fun removeCopyFile(name: String): Boolean = true
        override suspend fun removeStagingFile(name: String): Boolean {
            removedStaging += name
            return true
        }
    }
}
