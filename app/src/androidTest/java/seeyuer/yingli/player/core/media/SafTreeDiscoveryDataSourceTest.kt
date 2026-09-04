package seeyuer.yingli.player.core.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.core.foundation.AppDispatchers
import seeyuer.yingli.player.core.model.media.MediaSource
import seeyuer.yingli.player.core.model.media.MediaSourceId
import seeyuer.yingli.player.core.model.media.MediaSourceMode
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.core.model.media.ScanFailureKind

@RunWith(AndroidJUnit4::class)
class SafTreeDiscoveryDataSourceTest {
    @Test
    fun cyclicProviderAndDuplicateUrisEmitEachVideoOnce() = runTest {
        val first = MutableNode("content://documents/tree/root/document/first", "first", isDirectory = true)
        val second = MutableNode("content://documents/tree/root/document/second", "second", isDirectory = true)
        val video = MutableNode(
            uri = "content://documents/tree/root/document/video",
            name = "movie.mp4",
            mimeType = "video/mp4",
        )
        first.children = { listOf(second, video) }
        second.children = { listOf(first, video) }

        val events = dataSource(first).discover(SOURCE).toList()

        assertEquals(1, events.filterIsInstance<MediaDiscoveryEvent.Candidate>().size)
        assertTrue(events.none { it is MediaDiscoveryEvent.Failure })
    }

    @Test
    fun videoMetadataIsAddedToCandidate() = runTest {
        val video = MutableNode(
            uri = "content://documents/tree/root/document/video",
            name = "movie.mp4",
            mimeType = "video/mp4",
        )
        val root = MutableNode("content://documents/tree/root", "root", isDirectory = true).apply {
            children = { listOf(video) }
        }
        val metadata = VideoMetadata(durationMillis = 65_000L, width = 1_920, height = 1_080)

        val candidate = dataSource(root, MediaMetadataReader { metadata })
            .discover(SOURCE)
            .toList()
            .filterIsInstance<MediaDiscoveryEvent.Candidate>()
            .single()

        assertEquals(metadata.durationMillis, candidate.value.evidence.durationMillis)
        assertEquals(metadata.width, candidate.value.evidence.width)
        assertEquals(metadata.height, candidate.value.evidence.height)
    }

    @Test
    fun metadataFailureDoesNotSkipVideo() = runTest {
        val video = MutableNode(
            uri = "content://documents/tree/root/document/video",
            name = "movie.mp4",
            mimeType = "video/mp4",
        )
        val root = MutableNode("content://documents/tree/root", "root", isDirectory = true).apply {
            children = { listOf(video) }
        }

        val candidate = dataSource(root, MediaMetadataReader { error("unreadable metadata") })
            .discover(SOURCE)
            .toList()
            .filterIsInstance<MediaDiscoveryEvent.Candidate>()
            .single()

        assertEquals(null, candidate.value.evidence.durationMillis)
    }

    @Test
    fun inaccessibleTreeProducesRecoverablePermissionFailure() = runTest {
        val root = MutableNode("content://documents/tree/root", "root", isDirectory = true, canRead = false)

        val failure = dataSource(root).discover(SOURCE).toList().single() as MediaDiscoveryEvent.Failure

        assertEquals(ScanFailureKind.PERMISSION, failure.value.kind)
        assertTrue(failure.value.recoverable)
    }

    @Test
    fun providerFailureOnlySkipsTheFailingDirectory() = runTest {
        val broken = MutableNode("content://documents/tree/root/document/broken", "broken", isDirectory = true).apply {
            children = { throw IllegalStateException("provider failed") }
        }
        val video = MutableNode(
            uri = "content://documents/tree/root/document/video",
            name = "valid.mp4",
            mimeType = "video/mp4",
        )
        val root = MutableNode("content://documents/tree/root", "root", isDirectory = true).apply {
            children = { listOf(broken, video) }
        }

        val events = dataSource(root).discover(SOURCE).toList()

        assertEquals(1, events.filterIsInstance<MediaDiscoveryEvent.Candidate>().size)
        assertEquals(ScanFailureKind.IO, events.filterIsInstance<MediaDiscoveryEvent.Failure>().single().value.kind)
    }

    @Test
    fun traversalStopsBeforeVideosBeyondMaximumDepth() = runTest {
        val root = MutableNode("content://documents/tree/root", "root", isDirectory = true)
        var parent = root
        repeat(66) { depth ->
            val child = MutableNode(
                "content://documents/tree/root/document/d$depth",
                "d$depth",
                isDirectory = true,
            )
            parent.children = { listOf(child) }
            parent = child
        }
        parent.children = { listOf(MutableNode(
            uri = "content://documents/tree/root/document/deep-video",
            name = "deep.mp4",
            mimeType = "video/mp4",
        )) }

        val events = dataSource(root).discover(SOURCE).toList()

        assertTrue(events.filterIsInstance<MediaDiscoveryEvent.Candidate>().isEmpty())
    }

    private fun dataSource(
        root: SafDocumentNode,
        metadataReader: MediaMetadataReader = MediaMetadataReader.None,
    ) = SafTreeDiscoveryDataSource(
        documents = SafDocumentGateway { root },
        dispatchers = TestDispatchers,
        metadataReader = metadataReader,
    )

    private class MutableNode(
        override val uri: String,
        override val name: String?,
        override val mimeType: String? = null,
        override val isDirectory: Boolean = false,
        override val canRead: Boolean = true,
        override val length: Long = 1_024,
        override val lastModified: Long = 100,
    ) : SafDocumentNode {
        var children: () -> List<SafDocumentNode> = { emptyList() }
        override fun children(): List<SafDocumentNode> = children.invoke()
    }

    private object TestDispatchers : AppDispatchers {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private companion object {
        val SOURCE = MediaSource(
            id = MediaSourceId("source_saf"),
            displayName = "已授权目录",
            rootUri = MediaUri("content://documents/tree/root"),
            mode = MediaSourceMode.SAF_TREE,
            volumeId = null,
        )
    }
}
