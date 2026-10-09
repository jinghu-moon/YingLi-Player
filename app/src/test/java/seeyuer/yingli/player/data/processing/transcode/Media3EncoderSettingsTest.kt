package seeyuer.yingli.player.data.processing.transcode

import android.media.MediaCodecInfo
import androidx.media3.transformer.Composition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.processing.DefaultProcessingPlanner
import seeyuer.yingli.player.domain.processing.DeviceMediaCapabilities
import seeyuer.yingli.player.domain.processing.EncoderCapability
import seeyuer.yingli.player.domain.processing.HdrFormat
import seeyuer.yingli.player.domain.processing.MediaTrackInfo
import seeyuer.yingli.player.domain.processing.MediaTrackType
import seeyuer.yingli.player.domain.processing.SourceMediaInfo
import seeyuer.yingli.player.domain.processing.ProcessingChangeCode
import seeyuer.yingli.player.domain.processing.ProcessingPlan
import seeyuer.yingli.player.domain.processing.ProcessingPlanningResult
import seeyuer.yingli.player.domain.processing.OutputTarget
import seeyuer.yingli.player.domain.processing.OutputTargets

/**
 * G1 的改后断言：预设码率**必须**真的进入编码器设置。
 *
 * 修复前 [Media3ProcessingEngine] 只设了容器与 mime，实际码率由
 * `DefaultEncoderFactory.getSuggestedBitrate()` 按设备能力推导，于是三档预设在码率维度上完全等价。
 * 真机实测（`docs/architecture/Organizing-Page-Function-Design.md` §20.1.2）给出了该缺陷的可执行证据：
 * `compatible_mp4` 与 `balanced_mp4` 输出字节数完全相同（473 598）。
 *
 * 本测试断言的是**请求已下发**，不是**结果精确等于请求**——编码器可以为了质量忽略请求码率
 * （`AudioEncoderSettings.setBitrate` 的官方说明），后者只能由真机测量回答（§15.3 设备矩阵）。
 *
 * 本测试刻意不写 `@OptIn(UnstableApi::class)`：单元测试源集上 `UnstableApi` 未标注
 * `@RequiresOptIn`，写了反而触发 `OPT_IN_ARGUMENT_IS_NOT_MARKER` 并被 `-Werror` 拦下。
 */
class Media3EncoderSettingsTest {
    @Test
    fun `every preset bitrate reaches the video encoder settings`() {
        for (preset in OutputTargets.all) {
            val settings = Media3EncoderSettings.video(planFor(preset))
            assertEquals(
                "预设 ${preset.id.value} 的目标视频码率必须进入 VideoEncoderSettings",
                preset.videoBitrate,
                settings.bitrate,
            )
        }
    }

    @Test
    fun `every preset bitrate reaches the audio encoder settings`() {
        for (preset in OutputTargets.all) {
            val settings = Media3EncoderSettings.audio(planFor(preset))
            assertEquals(
                "预设 ${preset.id.value} 的目标音频码率必须进入 AudioEncoderSettings",
                preset.audioBitrate,
                settings.bitrate,
            )
        }
    }

    @Test
    fun `compatible and balanced no longer produce identical encoder settings`() {
        val compatible = Media3EncoderSettings.video(planFor(OutputTargets.Compatible))
        val balanced = Media3EncoderSettings.video(planFor(OutputTargets.Balanced))

        assertNotEquals(
            "compatible 与 balanced 的编码参数必须可区分，否则两档预设等价（G1 回归）",
            compatible.bitrate,
            balanced.bitrate,
        )
    }

    @Test
    fun `the three presets request three distinct video bitrates`() {
        val bitrates = OutputTargets.all
            .map { Media3EncoderSettings.video(planFor(it)).bitrate }

        assertEquals(bitrates.size, bitrates.toSet().size)
    }

    @Test
    fun `video bitrate mode is vbr because cq is unavailable on this device`() {
        val settings = Media3EncoderSettings.video(planFor(OutputTargets.Balanced))

        // 真机实测（§20.1.5）：全分辨率硬件编码器只报 VBR / CBR，BITRATE_MODE_CQ 只存在于
        // 尺寸上限 128–512 的编码器上。VideoEncoderSettings.BitrateMode 的 @IntDef 也只有这两档。
        assertEquals(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, settings.bitrateMode)
        assertTrue(
            "CBR 会让静态画面按目标码率灌满，压缩预设不用它",
            settings.bitrateMode != MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
        )
    }

    @Test
    fun `planner produces a plan for every preset so wiring cannot silently no-op`() {
        for (preset in OutputTargets.all) {
            val plan = planFor(preset)
            assertNotNull(plan)
            assertEquals(preset, plan.target)
        }
    }

    @Test
    fun `hdr source on an sdr encoder asks for tone mapping instead of refusing`() {
        val plan = planFor(OutputTargets.Compatible, hdrFormat = HdrFormat.HDR10, supportsHdr = false)

        assertTrue(
            "HDR 源 + 不支持 HDR 的编码器必须产生 HDR_TO_SDR 变更",
            plan.changes.any { it.code == ProcessingChangeCode.HDR_TO_SDR },
        )
        // G2 回归：修复前引擎对这份计划直接返回 Failed("HDR_TONE_MAPPING_UNAVAILABLE")，
        // 于是「每个 HDR 源都必然不可转码」。现在改为请求 tone mapping。
        assertEquals(
            Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
            Media3EncoderSettings.hdrMode(plan),
        )
    }

    @Test
    fun `hdr source on an hdr capable encoder keeps hdr`() {
        val plan = planFor(OutputTargets.Compatible, hdrFormat = HdrFormat.HDR10, supportsHdr = true)

        assertTrue(
            "编码器支持 HDR 时不得产生 HDR_TO_SDR（否则会白白丢 HDR）",
            plan.changes.none { it.code == ProcessingChangeCode.HDR_TO_SDR },
        )
        assertEquals(Composition.HDR_MODE_KEEP_HDR, Media3EncoderSettings.hdrMode(plan))
    }

    @Test
    fun `sdr source keeps hdr mode even though nothing is hdr`() {
        assertEquals(
            Composition.HDR_MODE_KEEP_HDR,
            Media3EncoderSettings.hdrMode(planFor(OutputTargets.Compatible)),
        )
    }

    private fun planFor(
        target: OutputTarget,
        hdrFormat: HdrFormat = HdrFormat.SDR,
        supportsHdr: Boolean = false,
    ): ProcessingPlan {
        val result = DefaultProcessingPlanner.plan(
            source = SourceMediaInfo(
                mediaId = MediaItemId("media-1"),
                uri = "content://media/1",
                displayName = "Sample Video.mov",
                durationMillis = 60_000,
                width = 1_920,
                height = 1_080,
                frameRate = 30f,
                videoBitrate = 10_000_000,
                containerMimeType = "video/quicktime",
                hdrFormat = hdrFormat,
                tracks = listOf(
                    MediaTrackInfo(0, MediaTrackType.VIDEO, "video/avc"),
                    MediaTrackInfo(1, MediaTrackType.AUDIO, "audio/mp4a-latm", "und", 2),
                ),
            ),
            capabilities = DeviceMediaCapabilities(
                encoders = listOf(
                    EncoderCapability("video/avc", 3_840, 2_160, 60f, supportsHdr = supportsHdr, hardwareAccelerated = true),
                ),
                capturedAtEpochMillis = 1,
            ),
            target = target,
            availableBytes = Long.MAX_VALUE,
        )
        return (result as ProcessingPlanningResult.Ready).plan
    }
}
