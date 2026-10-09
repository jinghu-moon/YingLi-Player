package seeyuer.yingli.player.domain.transcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId

/**
 * G6：编码格式被库静默替换（fallback）必须在类型层面就不可忽略。
 *
 * 这一层的测试只锁**契约**：哪些后果需要确认、一次回退的成功结果是否被标记为需要确认、
 * 以及不支持的目标是否在规划阶段就被拒绝。真机上的实际回退行为由
 * `Media3FallbackMappingTest`（用真实的 `TransformationRequest`）覆盖。
 */
class TranscodeFallbackContractTest {

    @Test
    fun `every change code has an explicit confirmation verdict`() {
        val requiresConfirmation = TranscodeChangeCode.entries.filter { it.requiresConfirmation() }.toSet()
        assertEquals(
            setOf(
                TranscodeChangeCode.EXTRA_AUDIO_TRACKS_REMOVED,
                TranscodeChangeCode.SUBTITLES_NOT_EMBEDDED,
                TranscodeChangeCode.HDR_TO_SDR,
                TranscodeChangeCode.FRAME_RATE_CAPPED,
                TranscodeChangeCode.VIDEO_CODEC_FALLBACK,
                TranscodeChangeCode.AUDIO_CODEC_FALLBACK,
            ),
            requiresConfirmation,
        )
    }

    @Test
    fun `fallback change codes demand confirmation through the change and the plan`() {
        // TranscodeChange 不再自带布尔字段：确认语义唯一来自 code。
        assertTrue(TranscodeChange(TranscodeChangeCode.VIDEO_CODEC_FALLBACK).requiresConfirmation)
        assertTrue(TranscodeChange(TranscodeChangeCode.AUDIO_CODEC_FALLBACK).requiresConfirmation)
        assertFalse(TranscodeChange(TranscodeChangeCode.RESOLUTION_REDUCED).requiresConfirmation)
    }

    @Test
    fun `completed result carrying a fallback asks for confirmation`() {
        assertFalse(TranscodeEngineResult.Completed().requiresConfirmation)
        assertTrue(
            TranscodeEngineResult.Completed(setOf(TranscodeChangeCode.VIDEO_CODEC_FALLBACK)).requiresConfirmation,
        )
        assertTrue(
            TranscodeEngineResult.Completed(
                setOf(TranscodeChangeCode.VIDEO_CODEC_FALLBACK, TranscodeChangeCode.AUDIO_CODEC_FALLBACK),
            ).requiresConfirmation,
        )
    }

    @Test
    fun `planner rejects a target video codec the device cannot encode`() {
        val preset = TranscodePresets.Compatible.copy(targetVideoMimeType = "video/hevc")

        val result = DefaultTranscodePlanner.plan(
            source(),
            capabilities(), // 只有 video/avc 编码器：与阶段 0 实测「HEVC 编码器不是平台必需项」一致
            preset,
            Long.MAX_VALUE,
        )

        assertEquals(TranscodePlanningResult.Rejected("VIDEO_ENCODER_UNAVAILABLE"), result)
    }

    private fun capabilities(encoders: List<EncoderCapability> = listOf(
        EncoderCapability("video/avc", 3_840, 2_160, 60f, supportsHdr = false, hardwareAccelerated = true),
    )) = DeviceMediaCapabilities(encoders, 1)

    private fun source() = SourceMediaInfo(
        MediaItemId("media-1"),
        "content://media/1",
        "Sample Video.mov",
        durationMillis = 60_000,
        width = 1_920,
        height = 1_080,
        frameRate = 30f,
        videoBitrate = 10_000_000,
        containerMimeType = "video/quicktime",
        hdrFormat = HdrFormat.SDR,
        tracks = listOf(
            MediaTrackInfo(0, MediaTrackType.VIDEO, "video/avc"),
            MediaTrackInfo(1, MediaTrackType.AUDIO, "audio/mp4a-latm", "und", 2),
        ),
    )
}
