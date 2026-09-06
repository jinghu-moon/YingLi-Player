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

    private fun dataSource(provider: CursorProvider): MediaStoreDiscoveryDataSource =
        MediaStoreDiscoveryDataSource(
            query = MediaStoreQuery { projection, _ -> provider.query(projection) },
            dispatchers = TestDispatchers,
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

    private fun row(projection: Array<out String>, id: Long, name: String?): Array<Any?> =
        projection.map { column ->
            when (column) {
                "_id" -> id
                "_display_name" -> name
                "mime_type" -> "video/mp4"
                "_size" -> 1_024L
                "date_modified" -> 100L
                "duration" -> 5_000L
                "width" -> 1_920
                "height" -> 1_080
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
