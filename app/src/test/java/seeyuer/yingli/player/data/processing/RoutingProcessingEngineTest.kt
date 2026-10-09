package seeyuer.yingli.player.data.processing

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.processing.HdrFormat
import seeyuer.yingli.player.domain.processing.MediaTrackInfo
import seeyuer.yingli.player.domain.processing.MediaTrackType
import seeyuer.yingli.player.domain.processing.OutputTargets
import seeyuer.yingli.player.domain.processing.ProcessingEngine
import seeyuer.yingli.player.domain.processing.ProcessingEngineResult
import seeyuer.yingli.player.domain.processing.ProcessingOperation
import seeyuer.yingli.player.domain.processing.ProcessingPlan
import seeyuer.yingli.player.domain.processing.SourceMediaInfo

class RoutingProcessingEngineTest {

    @Test
    fun `remux plan goes to the muxing engine and never to the encoder`() = runTest {
        val remux = RecordingEngine(ProcessingEngineResult.Completed())
        val transcode = RecordingEngine(ProcessingEngineResult.Completed())
        val router = RoutingProcessingEngine(remux, transcode)

        router.process(plan(ProcessingOperation.REMUX), "out.mp4") { }

        assertEquals(1, remux.calls)
        assertEquals(0, transcode.calls)
    }

    @Test
    fun `transcode plan goes to the encoding engine`() = runTest {
        val remux = RecordingEngine(ProcessingEngineResult.Completed())
        val transcode = RecordingEngine(ProcessingEngineResult.Completed())
        val router = RoutingProcessingEngine(remux, transcode)

        router.process(plan(ProcessingOperation.TRANSCODE), "out.mp4") { }

        assertEquals(0, remux.calls)
        assertEquals(1, transcode.calls)
    }

    @Test
    fun `cancel reaches both engines because the caller cannot know which one is running`() = runTest {
        val remux = RecordingEngine(ProcessingEngineResult.Completed())
        val transcode = RecordingEngine(ProcessingEngineResult.Completed())
        val router = RoutingProcessingEngine(remux, transcode)

        router.cancel()

        assertEquals(1, remux.cancels)
        assertEquals(1, transcode.cancels)
    }

    private class RecordingEngine(
        private val result: ProcessingEngineResult,
    ) : ProcessingEngine {
        var calls = 0
        var cancels = 0

        override suspend fun process(
            plan: ProcessingPlan,
            outputPath: String,
            onProgress: suspend (Float) -> Unit,
        ): ProcessingEngineResult {
            calls++
            return result
        }

        override suspend fun cancel() {
            cancels++
        }
    }

    private fun plan(operation: ProcessingOperation) = ProcessingPlan(
        source = SourceMediaInfo(
            mediaId = MediaItemId("media-1"),
            uri = "content://media/1",
            displayName = "Sample Video.mov",
            durationMillis = 60_000,
            width = 1_920,
            height = 1_080,
            frameRate = 30f,
            videoBitrate = 10_000_000,
            containerMimeType = "video/mp4",
            hdrFormat = HdrFormat.SDR,
            tracks = listOf(MediaTrackInfo(0, MediaTrackType.VIDEO, "video/avc")),
        ),
        target = OutputTargets.Compatible,
        operation = operation,
        targetWidth = 1_920,
        targetHeight = 1_080,
        retainedTrackIds = emptySet(),
        estimatedOutputBytes = 1_000_000,
        requiredFreeBytes = 2_000_000,
        outputDisplayName = "Sample_Video_compatible_mp4.mp4",
        changes = emptyList(),
    )
}
