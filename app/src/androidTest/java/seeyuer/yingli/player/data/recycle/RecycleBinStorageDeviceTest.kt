package seeyuer.yingli.player.data.recycle

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.common.DefaultAppDispatchers
import seeyuer.yingli.player.core.common.IdGenerator
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.processing.ProcessingProjectType
import seeyuer.yingli.player.domain.recycle.MediaOperationGuard
import seeyuer.yingli.player.domain.recycle.RecycleCatalogGateway
import seeyuer.yingli.player.domain.recycle.TrashBackend
import seeyuer.yingli.player.domain.recycle.TrashEntry
import seeyuer.yingli.player.domain.recycle.TrashOperationOutcome
import seeyuer.yingli.player.domain.recycle.TrashRepository
import seeyuer.yingli.player.domain.recycle.TrashState

/**
 * 阶段 4 的退出条件（§14.5）：**`content://` 与 `file://` 两种来源都能移入/恢复/永久删除，
 * 且任何失败路径都不丢唯一副本。**
 *
 * 这里跑的是真的 `AndroidRecycleBinStorage`（真的 `filesDir/recycle-bin/`、真的 MediaStore
 * 授权与更新），只有仓储/目录痕量/互斥守卫是内存替身——它们不是本用例要验证的对象。
 */
@RunWith(AndroidJUnit4::class)
class RecycleBinStorageDeviceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = InMemoryTrashRepository()
    private val catalog = RecordingCatalog()
    private val guard = NoConflictGuard()
    private val storage = AndroidRecycleBinStorage(
        context,
        DefaultAppDispatchers,
        IdGenerator { "copy-${System.nanoTime()}" },
        AppClock { Instant.now() },
        retentionDays = { 30 },
    )
    private val service = DefaultTrashService(repository, storage, catalog, guard, AppClock { Instant.now() })

    @Test
    fun appCopyRoundTripsWithoutLosingTheOnlyCopy() = runTest {
        val source = privateFile("app-copy.mp4", "unique-payload")
        val sourceSize = source.length()
        val item = media(source, sizeBytes = sourceSize, suffix = "app-copy")

        // 移入：源被删掉之前，副本必须已经在 items/ 且校验过。
        val moved = service.move(item)
        assertTrue(moved is TrashOperationOutcome.Completed)
        val active = (moved as TrashOperationOutcome.Completed).entry
        assertEquals(TrashBackend.R2_APP_COPY, active.backend)
        assertEquals(TrashState.ACTIVE, active.state)
        assertEquals(sourceSize, active.copySizeBytes)
        val copy = copyFile(active)
        assertTrue("副本必须存在：$copy", copy.exists())
        assertFalse("源文件应已被删除", source.exists())

        // 恢复：文件回到一个新名字，副本在写完之后才删。
        val restored = service.restore(item.locationId)
        assertTrue(restored is TrashOperationOutcome.Completed)
        val restoreUri = (restored as TrashOperationOutcome.Completed).entry.restoreUri
        assertTrue("恢复后必须报出可用 URI", restoreUri != null)
        assertTrue(storage.isReadable(requireNotNull(restoreUri)))
        assertFalse("恢复完成后副本必须删掉", copy.exists())
        assertNull(repository.byLocation(item.locationId))

        // 再次移入 + 永久删除：副本与源都不再存在。
        val restoredFile = File(Uri.parse(requireNotNull(restoreUri).value).path!!)
        assertEquals("恢复出来的内容必须与原文件一致", sourceSize, restoredFile.length())
        val again = service.move(
            media(restoredFile, sizeBytes = restoredFile.length(), suffix = "restored"),
        ) as TrashOperationOutcome.Completed
        val purged = service.purge(again.entry.locationId)
        assertTrue("永久删除失败：$purged", purged is TrashOperationOutcome.Purged)
        assertFalse(copyFile(again.entry).exists())
        assertEquals(listOf(again.entry.locationId), catalog.detached)
    }

    @Test
    fun aSystemTrashedMediaStoreVideoRoundTrips() = runTest {
        val uri = insertOwnMediaStoreVideo()
        val item = LibraryMedia(
            id = MediaItemId("item-r1"),
            locationId = MediaLocationId("loc-r1"),
            uri = MediaUri(uri.toString()),
            title = "r1",
            fileName = "yingli-r1.mp4",
            folderAlias = "Movies",
            extension = "mp4",
            durationMillis = 1_000,
            width = 16,
            height = 16,
            modifiedEpochMillis = 0,
            playbackPositionMillis = 0,
            completed = false,
            sizeBytes = 100,
        )
        assertTrue("本应用自己插入的 MediaStore 行应支持系统回收站", storage.systemTrashSupported(item))

        val moved = service.move(item)
        assertTrue("移入失败：$moved", moved is TrashOperationOutcome.Completed)
        val entry = (moved as TrashOperationOutcome.Completed).entry
        assertEquals(TrashBackend.R1_SYSTEM, entry.backend)
        assertEquals(TrashState.ACTIVE, entry.state)

        // 恢复：`IS_TRASHED` 回到 0，记录被移除，媒体行还在。
        val restored = service.restore(item.locationId)
        assertTrue("恢复失败：$restored", restored is TrashOperationOutcome.Completed)
        assertTrue(storage.isReadable(item.uri))
        assertNull(repository.byLocation(item.locationId))

        // 再次移入 + 永久删除：行必须真的消失。
        service.move(item)
        val purged = service.purge(item.locationId)
        assertTrue("删除失败：$purged", purged is TrashOperationOutcome.Purged)
        assertFalse("永久删除后媒体行必须不存在", storage.isReadable(item.uri))
    }

    // ---- fixtures ----------------------------------------------------------

    private fun privateFile(name: String, content: String): File {
        val directory = File(context.cacheDir, "recycle-device-${System.nanoTime()}").apply { mkdirs() }
        return File(directory, name).apply { writeText(content) }
    }

    private fun media(file: File, sizeBytes: Long, suffix: String) = LibraryMedia(
        id = MediaItemId("item-$suffix"),
        locationId = MediaLocationId("loc-$suffix"),
        uri = MediaUri(Uri.fromFile(file).toString()),
        title = file.name,
        fileName = file.name,
        folderAlias = file.parent.orEmpty(),
        extension = "mp4",
        durationMillis = 1_000,
        width = 16,
        height = 16,
        modifiedEpochMillis = 0,
        playbackPositionMillis = 0,
        completed = false,
        sizeBytes = sizeBytes,
    )

    private fun copyFile(entry: TrashEntry): File =
        // 真实存储把副本放在 <filesDir>/recycle-bin/ 下，条目里存的是相对它的路径。
        File(File(context.filesDir, "recycle-bin"), entry.copyRelativePath.orEmpty())

    private fun insertOwnMediaStoreVideo(): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "yingli-r1-${System.nanoTime()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/YingLi-RecycleTest",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(resolver.insert(collection, values)) { "无法插入测试媒体行" }
        resolver.openOutputStream(uri)!!.use { it.write(ByteArray(100)) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun assertNull(value: Any?) {
        org.junit.Assert.assertNull(value)
    }

    private class InMemoryTrashRepository : TrashRepository {
        private val entries = LinkedHashMap<MediaLocationId, TrashEntry>()

        override fun observe(): Flow<List<TrashEntry>> = flowOf(entries.values.toList())
        override suspend fun byLocation(locationId: MediaLocationId): TrashEntry? = entries[locationId]
        override suspend fun byLocations(locationIds: Collection<MediaLocationId>): List<TrashEntry> =
            locationIds.mapNotNull { entries[it] }

        override suspend fun insert(entry: TrashEntry) {
            entries[entry.locationId] = entry
        }

        override suspend fun update(entry: TrashEntry) {
            entries[entry.locationId] = entry
        }

        override suspend fun remove(locationId: MediaLocationId) {
            entries.remove(locationId)
        }

        override suspend fun inStates(states: Set<TrashState>): List<TrashEntry> =
            entries.values.filter { it.state in states }

        override suspend fun expired(nowEpochMillis: Long): List<TrashEntry> =
            entries.values.filter { it.state == TrashState.ACTIVE && it.isExpired(nowEpochMillis) }
    }

    private class RecordingCatalog : RecycleCatalogGateway {
        val detached = mutableListOf<MediaLocationId>()

        override suspend fun markRestored(
            locationId: MediaLocationId,
            restoreUri: MediaUri,
            contentHash: String?,
            hashAlgorithmVersion: Int?,
            nowEpochMillis: Long,
        ) = Unit

        override suspend fun detachLocation(locationId: MediaLocationId, mediaItemId: MediaItemId) {
            detached += locationId
        }

        override suspend fun deleteItemsWithoutLocations(): Int = 0
    }

    private class NoConflictGuard : MediaOperationGuard {
        override suspend fun activeOperations(
            mediaItemId: MediaItemId,
            locationId: MediaLocationId,
        ): Set<ProcessingProjectType> = emptySet()

        override fun conflicts(left: ProcessingProjectType, right: ProcessingProjectType): Boolean = false
    }
}
