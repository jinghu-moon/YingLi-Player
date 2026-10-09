package seeyuer.yingli.player.domain.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId

class ProcessingPlanContractsTest {
    @Test
    fun `planner rejects unavailable encoder and insufficient storage`() {
        val source = source()
        assertEquals(
            ProcessingPlanningResult.Rejected("VIDEO_ENCODER_UNAVAILABLE"),
            DefaultProcessingPlanner.plan(source, capabilities(emptyList()), OutputTargets.Balanced, Long.MAX_VALUE),
        )
        assertEquals(
            ProcessingPlanningResult.Rejected("INSUFFICIENT_STORAGE"),
            DefaultProcessingPlanner.plan(source, capabilities(), OutputTargets.Balanced, 1),
        )
    }

    @Test
    fun `planner creates even bounded dimensions and conservative estimate`() {
        val result = DefaultProcessingPlanner.plan(
            source(width = 3_841, height = 2_161),
            capabilities(),
            OutputTargets.SpaceSaver,
            Long.MAX_VALUE,
        ) as ProcessingPlanningResult.Ready

        assertEquals(1_280, result.plan.targetWidth)
        assertEquals(720, result.plan.targetHeight)
        assertEquals(0, result.plan.targetWidth % 2)
        assertEquals(0, result.plan.targetHeight % 2)
        assertTrue(result.plan.requiredFreeBytes > result.plan.estimatedOutputBytes)
        assertTrue(result.plan.changes.any { it.code == ProcessingChangeCode.RESOLUTION_REDUCED })
    }

    @Test
    fun `planner never silently drops hdr subtitles or extra audio`() {
        val result = DefaultProcessingPlanner.plan(
            source(hdr = HdrFormat.HDR10, extraTracks = true),
            capabilities(),
            OutputTargets.Compatible,
            Long.MAX_VALUE,
        ) as ProcessingPlanningResult.Ready

        assertTrue(result.plan.requiresConfirmation)
        assertTrue(result.plan.changes.any { it.code == ProcessingChangeCode.HDR_TO_SDR && it.requiresConfirmation })
        assertTrue(result.plan.changes.any { it.code == ProcessingChangeCode.SUBTITLES_NOT_EMBEDDED && it.requiresConfirmation })
        assertTrue(result.plan.changes.any { it.code == ProcessingChangeCode.EXTRA_AUDIO_TRACKS_REMOVED && it.requiresConfirmation })
        assertEquals(setOf(1), result.plan.retainedTrackIds)
    }

    @Test
    fun `sdr single track compatible source needs no destructive confirmation`() {
        val result = DefaultProcessingPlanner.plan(
            source(), capabilities(), OutputTargets.Compatible, Long.MAX_VALUE,
        ) as ProcessingPlanningResult.Ready

        assertFalse(result.plan.requiresConfirmation)
        assertTrue(result.plan.outputDisplayName.endsWith("_compatible_mp4.mp4"))
    }

    /**
     * 阶段 2 重构的 golden test（设计稿 §15.2「同一组输入在重构前后产出**字段级相同**的 plan」）。
     *
     * 期望值取自重构前的 `ProcessingPlan`：1920×1080 的 SDR 单音轨源配 `compatible_mp4`，
     * 目标与源的三元组相同、只有质量参数不同，因此除操作分类外，其余字段必须一个字节都不变。
     *
     * 唯一**有意**变化的是空闲空间预留：`FREE_SPACE_RESERVE_BYTES` 原本是 128 MiB，
     * 与调度侧的 256 MiB 冲突，已按设计稿 §6.1 统一为后者（断言写在下面，不是约等于）。
     */
    @Test
    fun `preset planning is field identical to the pre refactor transcode plan`() {
        val result = DefaultProcessingPlanner.plan(
            source(), capabilities(), OutputTargets.Compatible, Long.MAX_VALUE,
        ) as ProcessingPlanningResult.Ready

        assertEquals(ProcessingOperation.TRANSCODE, result.plan.operation)
        assertEquals(1_920, result.plan.targetWidth)
        assertEquals(1_080, result.plan.targetHeight)
        assertEquals(setOf(1), result.plan.retainedTrackIds)
        assertEquals("Sample_Video_compatible_mp4.mp4", result.plan.outputDisplayName)
        assertEquals(emptyList<ProcessingChange>(), result.plan.changes)
        assertNull(result.plan.range)
        assertEquals(
            PROCESSING_FREE_SPACE_RESERVE_BYTES,
            result.plan.requiredFreeBytes - result.plan.estimatedOutputBytes,
        )
        // 估算公式本身未改，仍是 `(码率之和 / 8) × 秒数 × 1.15`：
        // 1920×1080 ≤ 1920 长边，故目标尺寸与源相同，估算只由码率与时长决定。
        assertEquals(
            (((8_000_000 + 192_000) / 8.0) * 60.0 * 1.15).toLong(),
            result.plan.estimatedOutputBytes,
        )
    }

    /**
     * 同容器、同 codec、且没有质量参数 ⇒ REMUX，且**不需要编码器**。
     *
     * 这条是 remux 路径存在的理由：源已经是目标 codec 时要求「设备有该 codec 的编码器」
     * 会错误拒绝合法任务（例如把 VP9/Opus 的 MKV 换容器成 MP4）。
     */
    @Test
    fun `same container and codecs without quality parameters is a remux that needs no encoder`() {
        val remuxTarget = OutputTarget(
            id = OutputTargetId("mp4_same"),
            containerMimeType = "video/mp4",
            videoCodecMimeType = "video/avc",
            audioCodecMimeType = "audio/mp4a-latm",
        )
        val remux = DefaultProcessingPlanner.plan(
            source(), capabilities(emptyList()), remuxTarget, Long.MAX_VALUE,
        ) as ProcessingPlanningResult.Ready
        assertEquals(ProcessingOperation.REMUX, remux.plan.operation)
        // REMUX 不需要编码器，所以「设备没有该编码器」不能拒绝它。
        assertEquals(1_920, remux.plan.targetWidth)
        assertEquals(1_080, remux.plan.targetHeight)
    }

    @Test
    fun `range beyond source duration is rejected instead of clamped`() {
        val range = MediaRange(0, 60_001)
        assertEquals(
            ProcessingPlanningResult.Rejected("RANGE_EXCEEDS_SOURCE"),
            DefaultProcessingPlanner.plan(source(), capabilities(), OutputTargets.Compatible, Long.MAX_VALUE, range),
        )
    }

    @Test
    fun `range shortens the size estimate proportionally`() {
        val full = DefaultProcessingPlanner.plan(
            source(), capabilities(), OutputTargets.Compatible, Long.MAX_VALUE,
        ) as ProcessingPlanningResult.Ready
        val half = DefaultProcessingPlanner.plan(
            source(), capabilities(), OutputTargets.Compatible, Long.MAX_VALUE, MediaRange(10_000, 40_000),
        ) as ProcessingPlanningResult.Ready

        assertEquals(30_000L, half.plan.range?.durationMillis)
        assertTrue(half.plan.estimatedOutputBytes < full.plan.estimatedOutputBytes)
    }

    @Test
    fun `frame accurate cut forces transcode even though container and codecs are unchanged`() {
        fun clipTarget(accurate: Boolean) = OutputTarget(
            id = OutputTargetId(if (accurate) "clip_accurate" else "clip_fast"),
            containerMimeType = "video/mp4",
            videoCodecMimeType = null,
            audioCodecMimeType = null,
            frameAccurateCut = accurate,
        )
        val range = MediaRange(1_000, 5_000)

        val fast = DefaultProcessingPlanner.plan(
            source(), capabilities(emptyList()), clipTarget(false), Long.MAX_VALUE, range,
        ) as ProcessingPlanningResult.Ready
        assertEquals(ProcessingOperation.REMUX, fast.plan.operation)
        assertEquals(range, fast.plan.range)
        // REMUX 不查编码器：设备一个编码器都没有也必须能无损切片。
        assertEquals(0, fast.plan.changes.size)

        val accurate = DefaultProcessingPlanner.plan(
            source(), capabilities(), clipTarget(true), Long.MAX_VALUE, range,
        ) as ProcessingPlanningResult.Ready
        // 「精确到帧」是用户意图，推不出来：目标容器与 codec 与源完全相同、也没有质量参数，
        // 只看这些会把精确重编码误判成无损封装（关键帧吸附会让实际起点早于请求起点）。
        assertEquals(ProcessingOperation.TRANSCODE, accurate.plan.operation)
        assertEquals(range, accurate.plan.range)
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
