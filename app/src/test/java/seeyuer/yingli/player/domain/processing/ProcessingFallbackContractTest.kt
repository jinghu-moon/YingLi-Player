package seeyuer.yingli.player.domain.processing

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
class ProcessingFallbackContractTest {

    @Test
    fun `every change code has an explicit confirmation verdict`() {
        val requiresConfirmation = ProcessingChangeCode.entries.filter { it.requiresConfirmation() }.toSet()
        assertEquals(
            setOf(
                ProcessingChangeCode.EXTRA_AUDIO_TRACKS_REMOVED,
                ProcessingChangeCode.SUBTITLES_NOT_EMBEDDED,
                ProcessingChangeCode.HDR_TO_SDR,
                ProcessingChangeCode.FRAME_RATE_CAPPED,
                ProcessingChangeCode.VIDEO_CODEC_FALLBACK,
                ProcessingChangeCode.AUDIO_CODEC_FALLBACK,
            ),
            requiresConfirmation,
        )
    }

    @Test
    fun `fallback change codes demand confirmation through the change and the plan`() {
        // ProcessingChange 不再自带布尔字段：确认语义唯一来自 code。
        assertTrue(ProcessingChange(ProcessingChangeCode.VIDEO_CODEC_FALLBACK).requiresConfirmation)
        assertTrue(ProcessingChange(ProcessingChangeCode.AUDIO_CODEC_FALLBACK).requiresConfirmation)
        assertFalse(ProcessingChange(ProcessingChangeCode.RESOLUTION_REDUCED).requiresConfirmation)
    }

    @Test
    fun `completed result carrying a fallback asks for confirmation`() {
        assertFalse(ProcessingEngineResult.Completed().requiresConfirmation)
        assertTrue(
            ProcessingEngineResult.Completed(setOf(ProcessingChangeCode.VIDEO_CODEC_FALLBACK)).requiresConfirmation,
        )
        assertTrue(
            ProcessingEngineResult.Completed(
                setOf(ProcessingChangeCode.VIDEO_CODEC_FALLBACK, ProcessingChangeCode.AUDIO_CODEC_FALLBACK),
            ).requiresConfirmation,
        )
    }

    @Test
    fun `planner rejects a target video codec the device cannot encode`() {
        val target = OutputTargets.Compatible.copy(videoCodecMimeType = "video/hevc")

        val result = DefaultProcessingPlanner.plan(
            source(),
            capabilities(), // 只有 video/avc 编码器：与阶段 0 实测「HEVC 编码器不是平台必需项」一致
            target,
            Long.MAX_VALUE,
        )

        assertEquals(ProcessingPlanningResult.Rejected("VIDEO_ENCODER_UNAVAILABLE"), result)
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
