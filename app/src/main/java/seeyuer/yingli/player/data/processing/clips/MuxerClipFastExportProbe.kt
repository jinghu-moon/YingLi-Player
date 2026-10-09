package seeyuer.yingli.player.data.processing.clips

import androidx.media3.common.C
import seeyuer.yingli.player.data.processing.muxer.MuxerContainer
import seeyuer.yingli.player.domain.clips.ClipFastExportProbe
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.processing.MediaCapabilityProbe
import seeyuer.yingli.player.domain.processing.MediaTrackType

/**
 * 「源能不能快速（无损复制）导出成 MP4」的**数据层实现**：先探一次源，再把样本格式交给
 * 封装层的能力表（设计稿 §14.6 步骤 13 / G11）。
 *
 * 两个"唯一真源"都在这里被引到一处：
 *  - 源的轨道格式来自 [MediaCapabilityProbe]（与切片、转码用的是同一份探测结果口径）；
 *  - 容器收不收这些格式来自 `MuxerContainer.Mp4`（它直接取 `Mp4Muxer.SUPPORTED_*`，
 *    与 `Media3ProcessingEngine` 在 build 之前做的校验是同一张表）。
 *
 * 因此这里**没有**任何 mime 字面量。第一版 G11 的成因就是手写了一份白名单。
 *
 * 具体规则（与 `Media3ProcessingEngine` 的容器校验逐条对应）：
 *  - 源必须有一条视频轨（切片产出的是视频文件）；
 *  - 视频样本格式必须被 MP4 接受；
 *  - 若源有音频轨，其样本格式也必须被 MP4 接受；没有音频轨则只看视频。
 *
 * 探测失败（文件不可读、没有视频轨）一律回 false：宁可默认走精确模式，也不默认一条
 * 注定失败的路。
 */
class MuxerClipFastExportProbe(
    private val probe: MediaCapabilityProbe,
) : ClipFastExportProbe {
    override suspend fun supportsFastExport(media: LibraryMedia): Boolean {
        val source = probe.source(media.id, media.uri.value, media.fileName) ?: return false
        val video = source.tracks.firstOrNull { it.type == MediaTrackType.VIDEO } ?: return false
        if (!MuxerContainer.Mp4.supports(C.TRACK_TYPE_VIDEO, video.mimeType)) return false
        val audio = source.tracks.firstOrNull { it.type == MediaTrackType.AUDIO }
        return audio == null || MuxerContainer.Mp4.supports(C.TRACK_TYPE_AUDIO, audio.mimeType)
    }
}
