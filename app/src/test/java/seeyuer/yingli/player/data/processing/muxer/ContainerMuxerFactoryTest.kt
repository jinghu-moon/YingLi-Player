package seeyuer.yingli.player.data.processing.muxer

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.Mp4Muxer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.domain.processing.OutputTargets

/**
 * 步骤 7（G4/F19）的契约测试。
 *
 * 本测试**看不见**「`setMuxerFactory` 有没有真的被调用」——`Transformer` 不提供读回封装工厂的
 * getter，与 G1 的 `.setEncoderFactory(...)` 是同一类盲区（阶段 1 的教训）。这里能钉住的是：
 * 容器→封装器的映射、容器能力表与库自身表同源、以及「MP4 进 MP4 被库自己的表承认」这条 G11 前提。
 */
class ContainerMuxerFactoryTest {

    @Test
    fun `every container has an adapter`() {
        // 「Transformer 对 MP4 不接管」这条决定写在 `Media3ProcessingEngine` 里，
        // 不属于封装器工厂——手写搬运管线（remux）需要 MP4 的 `Mp4Muxer`。
        val containers = listOf(MimeTypes.VIDEO_MP4, MimeTypes.VIDEO_WEBM, MimeTypes.AUDIO_OGG, "audio/aac")
        for (containerMimeType in containers) {
            assertNotNull(containerMimeType, ContainerMuxerFactory.forContainerMimeType(containerMimeType))
        }
    }

    @Test
    fun `mp4 accepts vp9 and opus through the library muxer`() {
        // G11 的前提：平台 `MediaMuxer` 拒绝 VP9/Opus 进 MP4，而 media3 的 `Mp4Muxer` 接受。
        // 这条断言若失败，remux 走 `Mp4Muxer` 就没有意义，整个步骤 8 的判断需要重做。
        assertTrue(MuxerContainer.Mp4.supports(C.TRACK_TYPE_VIDEO, MimeTypes.VIDEO_VP9))
        assertTrue(MuxerContainer.Mp4.supports(C.TRACK_TYPE_AUDIO, MimeTypes.AUDIO_OPUS))
    }

    @Test
    fun `unknown containers are rejected before the engine builds anything`() {
        assertNull(MuxerContainer.forContainerMimeType("video/x-matroska"))
        assertNull(ContainerMuxerFactory.forContainerMimeType("video/x-matroska"))
    }

    @Test
    fun `every shipped preset is accepted by its own container`() {
        for (target in OutputTargets.all) {
            val container = requireNotNull(MuxerContainer.forContainerMimeType(target.containerMimeType)) {
                "${target.id.value} 的容器未被识别：${target.containerMimeType}"
            }
            assertTrue(
                "${target.id.value} 的视频编码被 ${target.containerMimeType} 拒绝",
                container.supports(C.TRACK_TYPE_VIDEO, requireNotNull(target.videoCodecMimeType)),
            )
            assertTrue(
                "${target.id.value} 的音频编码被 ${target.containerMimeType} 拒绝",
                container.supports(C.TRACK_TYPE_AUDIO, requireNotNull(target.audioCodecMimeType)),
            )
        }
    }

    @Test
    fun `the mp4 capability table is the library muxer's own table`() {
        assertEquals(Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES, MuxerContainer.Mp4.videoSampleMimeTypes)
        assertEquals(Mp4Muxer.SUPPORTED_AUDIO_SAMPLE_MIME_TYPES, MuxerContainer.Mp4.audioSampleMimeTypes)
    }

    @Test
    fun `container reachability differs per codec`() {
        // 这两条就是「同样一个 HEVC，MP4 收、WebM 不收」——阶段 2 步骤 8 要用的判据基础。
        // 注意常量名：Media3 用 `VIDEO_H264`/`VIDEO_H265`，`VIDEO_AVC`/`VIDEO_HEVC` 是 Android
        // `MediaFormat` 的命名，在 media3-common 里不存在。
        assertTrue(MuxerContainer.Mp4.supports(C.TRACK_TYPE_VIDEO, MimeTypes.VIDEO_H265))
        assertFalse(MuxerContainer.Webm.supports(C.TRACK_TYPE_VIDEO, MimeTypes.VIDEO_H265))
        // 纯音频容器不收视频、也不收自己没实现的音频编码。
        assertFalse(MuxerContainer.Ogg.supports(C.TRACK_TYPE_VIDEO, MimeTypes.VIDEO_H264))
        assertFalse(MuxerContainer.Adts.supports(C.TRACK_TYPE_AUDIO, MimeTypes.AUDIO_OPUS))
        assertTrue(MuxerContainer.Ogg.supports(C.TRACK_TYPE_AUDIO, MimeTypes.AUDIO_OPUS))
    }

    @Test
    fun `the factory reports the same table through the muxer interface`() {
        val webm = requireNotNull(ContainerMuxerFactory.forContainerMimeType(MimeTypes.VIDEO_WEBM))
        assertEquals(MuxerContainer.Webm.videoSampleMimeTypes, webm.getSupportedSampleMimeTypes(C.TRACK_TYPE_VIDEO))
        assertEquals(MuxerContainer.Webm.audioSampleMimeTypes, webm.getSupportedSampleMimeTypes(C.TRACK_TYPE_AUDIO))
        assertEquals(emptyList<String>(), webm.getSupportedSampleMimeTypes(C.TRACK_TYPE_TEXT))
        assertFalse(webm.supportsWritingNegativeTimestampsInEditList())
    }
}
