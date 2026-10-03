package seeyuer.yingli.player.data.sources

import android.database.Cursor
import android.database.MatrixCursor
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.model.media.MediaSource
import seeyuer.yingli.player.core.model.media.MediaSourceId
import seeyuer.yingli.player.core.model.media.MediaSourceMode
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.core.model.media.ScanFailureKind
import seeyuer.yingli.player.core.model.media.VolumeId
import seeyuer.yingli.player.domain.catalog.MediaDiscoveryEvent

@RunWith(AndroidJUnit4::class)
class MediaStoreDiscoveryDataSourceTest {
    @Test
    fun mapsRequiredColumnsAndDeduplicatesUris() = runTest {
        val provider = CursorProvider { projection ->
            MatrixCursor(projection).apply {
                addRow(row(projection, id = 7, name = "movie.mp4"))
                addRow(row(projection, id = 7, name = "movie.mp4"))
            }
        }
        val events = dataSource(provider).discover(SOURCE).toList()

        val candidates = events.filterIsInstance<MediaDiscoveryEvent.Candidate>()
        assertEquals(1, candidates.size)
        assertEquals("content://media/external/video/media/7", candidates.single().value.evidence.uri.value)
        assertEquals("Movies/Camera", candidates.single().value.evidence.relativePath)
        assertEquals(REQUIRED_COLUMNS, provider.requestedProjection?.toSet())
    }

    @Test
    fun malformedRowDoesNotTerminateTheRemainingBatch() = runTest {
        val provider = CursorProvider { projection ->
            MatrixCursor(projection).apply {
                addRow(row(projection, id = 1, name = null))
                addRow(row(projection, id = 2, name = "valid.mp4"))
            }
        }
        val events = dataSource(provider).discover(SOURCE).toList()

        assertEquals(1, events.filterIsInstance<MediaDiscoveryEvent.Failure>().size)
        assertEquals(1, events.filterIsInstance<MediaDiscoveryEvent.Candidate>().size)
    }

    @Test
    fun missingColumnsProduceRecoverableMalformedFailure() = runTest {
        val provider = CursorProvider { MatrixCursor(arrayOf("_id")) }
        val events = dataSource(provider).discover(SOURCE).toList()

        val failure = events.single() as MediaDiscoveryEvent.Failure
        assertEquals(ScanFailureKind.MALFORMED_ENTRY, failure.value.kind)
        assertTrue(failure.value.recoverable)
    }

    @Test
    fun missingMediaStoreMetadataIsReadFromVideoUri() = runTest {
        val provider = CursorProvider { projection ->
            MatrixCursor(projection).apply {
                addRow(row(projection, id = 8, name = ".hidden.mp4", duration = null, width = null, height = null))
            }
        }
        val reader = MediaMetadataReader { uri ->
            assertEquals("content://media/external/file/8", uri.value)
            VideoMetadata(durationMillis = 12_000, width = 1_280, height = 720)
        }
        val events = dataSource(provider, reader).discover(SOURCE.copy(includeHidden = true)).toList()

        val evidence = (events.single() as MediaDiscoveryEvent.Candidate).value.evidence
        // 期望值必须写成 Long 字面量：`MediaEvidence.durationMillis` 自模型落地起就是 `Long?`
        // （`VideoMetadata.durationMillis` 同样是 `Long?`，因为 MediaStore 的 duration 列就是 64 位）。
        // 这里原来写的是 Int 字面量 `12_000`，`assertEquals` 会解析到 `assertEquals(Object, Object)`，
        // 于是比较的是 `Integer(12000)` 与 `Long(12000)` 的**类型**而不是数值，必然失败。
        // 改用 `12_000L` 后走 `assertEquals(long, long)`，断言的仍是同一个精确数值，强度不变。
        assertEquals(12_000L, evidence.durationMillis)
        assertEquals(1_280, evidence.width)
        assertEquals(720, evidence.height)
    }

    @Test
    fun securityExceptionBecomesRecoverablePermissionFailure() = runTest {
        val provider = CursorProvider { throw SecurityException("permission revoked") }
        val events = dataSource(provider).discover(SOURCE).toList()

        val failure = events.single() as MediaDiscoveryEvent.Failure
        assertEquals(ScanFailureKind.PERMISSION, failure.value.kind)
        assertTrue(failure.value.recoverable)
    }

    @Test
    fun tenThousandRowsRemainALightweightCursorScan() = runTest {
        val count = 10_000
        val provider = CursorProvider { projection ->
            MatrixCursor(projection, count).apply {
                repeat(count) { index ->
                    addRow(row(projection, id = index.toLong(), name = "movie_$index.mp4"))
                }
            }
        }
        lateinit var events: List<MediaDiscoveryEvent>

        val elapsed = measureTimeMillis {
            events = dataSource(provider).discover(SOURCE).toList()
        }

        assertEquals(count, events.size)
        assertTrue("$count MediaStore rows took ${elapsed}ms", elapsed < 3_000)
    }

    private fun dataSource(
        provider: CursorProvider,
        metadataReader: MediaMetadataReader = MediaMetadataReader.None,
    ): MediaStoreDiscoveryDataSource =
        MediaStoreDiscoveryDataSource(
            query = MediaStoreQuery { _, projection, _ -> provider.query(projection) },
            dispatchers = TestDispatchers,
            metadataReader = metadataReader,
        )

    private class CursorProvider(
        private val cursorFactory: (Array<out String>) -> Cursor,
    ) {
        var requestedProjection: Array<out String>? = null

        fun query(projection: Array<String>): Cursor {
            requestedProjection = projection
            return cursorFactory(projection)
        }
    }

    private fun row(
        projection: Array<out String>,
        id: Long,
        name: String?,
        duration: Long? = 5_000L,
        width: Int? = 1_920,
        height: Int? = 1_080,
    ): Array<Any?> =
        projection.map { column ->
            when (column) {
                "_id" -> id
                "_display_name" -> name
                "mime_type" -> "video/mp4"
                "_size" -> 1_024L
                "date_modified" -> 100L
                "duration" -> duration
                "width" -> width
                "height" -> height
                "relative_path" -> "Movies/Camera/"
                else -> null
            }
        }.toTypedArray()

    private object TestDispatchers : AppDispatchers {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private companion object {
        val REQUIRED_COLUMNS = setOf(
            "_id", "_display_name", "mime_type", "_size", "date_modified", "duration", "width", "height",
            "relative_path",
        )
        val SOURCE = MediaSource(
            id = MediaSourceId("source_media_store"),
            displayName = "系统媒体库",
            rootUri = MediaUri("content://media/external/video/media"),
            mode = MediaSourceMode.MEDIA_STORE,
            volumeId = VolumeId("external"),
        )
    }
}
