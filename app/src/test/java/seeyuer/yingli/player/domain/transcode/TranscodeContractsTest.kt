package seeyuer.yingli.player.domain.transcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId

class TranscodeContractsTest {
    @Test
    fun `planner rejects unavailable encoder and insufficient storage`() {
        val source = source()
        assertEquals(
            TranscodePlanningResult.Rejected("VIDEO_ENCODER_UNAVAILABLE"),
            DefaultTranscodePlanner.plan(source, capabilities(emptyList()), TranscodePresets.Balanced, Long.MAX_VALUE),
        )
        assertEquals(
            TranscodePlanningResult.Rejected("INSUFFICIENT_STORAGE"),
            DefaultTranscodePlanner.plan(source, capabilities(), TranscodePresets.Balanced, 1),
        )
    }

    @Test
    fun `planner creates even bounded dimensions and conservative estimate`() {
        val result = DefaultTranscodePlanner.plan(
            source(width = 3_841, height = 2_161),
            capabilities(),
            TranscodePresets.SpaceSaver,
            Long.MAX_VALUE,
        ) as TranscodePlanningResult.Ready

        assertEquals(1_280, result.plan.targetWidth)
        assertEquals(720, result.plan.targetHeight)
        assertEquals(0, result.plan.targetWidth % 2)
        assertEquals(0, result.plan.targetHeight % 2)
        assertTrue(result.plan.requiredFreeBytes > result.plan.estimatedOutputBytes)
        assertTrue(result.plan.changes.any { it.code == TranscodeChangeCode.RESOLUTION_REDUCED })
    }

    @Test
    fun `planner never silently drops hdr subtitles or extra audio`() {
        val result = DefaultTranscodePlanner.plan(
            source(hdr = HdrFormat.HDR10, extraTracks = true),
            capabilities(),
            TranscodePresets.Compatible,
            Long.MAX_VALUE,
        ) as TranscodePlanningResult.Ready

        assertTrue(result.plan.requiresConfirmation)
        assertTrue(result.plan.changes.any { it.code == TranscodeChangeCode.HDR_TO_SDR && it.requiresConfirmation })
        assertTrue(result.plan.changes.any { it.code == TranscodeChangeCode.SUBTITLES_NOT_EMBEDDED && it.requiresConfirmation })
        assertTrue(result.plan.changes.any { it.code == TranscodeChangeCode.EXTRA_AUDIO_TRACKS_REMOVED && it.requiresConfirmation })
        assertEquals(setOf(1), result.plan.retainedAudioTrackIds)
    }

    @Test
    fun `sdr single track compatible source needs no destructive confirmation`() {
        val result = DefaultTranscodePlanner.plan(
            source(), capabilities(), TranscodePresets.Compatible, Long.MAX_VALUE,
        ) as TranscodePlanningResult.Ready

        assertFalse(result.plan.requiresConfirmation)
        assertTrue(result.plan.outputDisplayName.endsWith("_compatible_mp4.mp4"))
    }

    private fun capabilities(encoders: List<EncoderCapability> = listOf(
        EncoderCapability("video/avc", 3_840, 2_160, 60f, supportsHdr = false, hardwareAccelerated = true),
    )) = DeviceMediaCapabilities(encoders, 1)

    private fun source(
        width: Int = 1_920,
        height: Int = 1_080,
        hdr: HdrFormat = HdrFormat.SDR,
        extraTracks: Boolean = false,
    ) = SourceMediaInfo(
        MediaItemId("media-1"),
        "content://media/1",
        "Sample Video.mov",
        durationMillis = 60_000,
        width = width,
        height = height,
        frameRate = 30f,
        videoBitrate = 10_000_000,
        containerMimeType = "video/quicktime",
        hdrFormat = hdr,
        tracks = buildList {
            add(MediaTrackInfo(0, MediaTrackType.VIDEO, "video/avc"))
            add(MediaTrackInfo(1, MediaTrackType.AUDIO, "audio/mp4a-latm", "und", 2))
            if (extraTracks) {
                add(MediaTrackInfo(2, MediaTrackType.AUDIO, "audio/mp4a-latm", "zh", 2))
                add(MediaTrackInfo(3, MediaTrackType.SUBTITLE, "text/vtt", "zh"))
            }
        },
    )
}
