package seeyuer.yingli.player.domain.processing

import seeyuer.yingli.player.core.model.media.MediaItemId

/**
 * 源媒体与设备能力的领域契约。
 *
 * 这些类型在两个引擎（`REMUX` 的 `MediaExtractor` + `Muxer` 路径与 `TRANSCODE` 的
 * Media3 `Transformer` 路径）之间共享，因此**不得**携带任何平台类型：
 * 它们描述的是「源有什么」「设备能编什么」，与谁能读能写无关。
 */

enum class MediaTrackType { VIDEO, AUDIO, SUBTITLE }

enum class HdrFormat { SDR, HDR10, HDR10_PLUS, HLG, DOLBY_VISION, UNKNOWN }

data class MediaTrackInfo(
    val id: Int,
    val type: MediaTrackType,
    val mimeType: String,
    val language: String? = null,
    val channels: Int? = null,
) {
    init {
        require(id >= 0)
        require(mimeType.contains('/'))
        require(channels == null || channels > 0)
    }
}

data class SourceMediaInfo(
    val mediaId: MediaItemId,
    val uri: String,
    val displayName: String,
    val durationMillis: Long,
    val width: Int,
    val height: Int,
    val frameRate: Float?,
    val videoBitrate: Int?,
    val containerMimeType: String?,
    val hdrFormat: HdrFormat,
    val tracks: List<MediaTrackInfo>,
) {
    init {
        require(uri.isNotBlank())
        require(displayName.isNotBlank())
        require(durationMillis > 0)
        require(width > 0 && height > 0)
        require(frameRate == null || frameRate > 0)
        require(videoBitrate == null || videoBitrate > 0)
        require(tracks.any { it.type == MediaTrackType.VIDEO })
    }
}

data class EncoderCapability(
    val mimeType: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFrameRate: Float,
    val supportsHdr: Boolean,
    val hardwareAccelerated: Boolean,
) {
    init {
        require(mimeType.startsWith("video/"))
        require(maxWidth > 0 && maxHeight > 0 && maxFrameRate > 0)
    }
}

data class DeviceMediaCapabilities(
    val encoders: List<EncoderCapability>,
    val capturedAtEpochMillis: Long,
    val diagnosticCodes: Set<String> = emptySet(),
) {
    init { require(capturedAtEpochMillis >= 0) }
}

interface MediaCapabilityProbe {
    suspend fun deviceCapabilities(): DeviceMediaCapabilities

    suspend fun source(mediaId: MediaItemId, uri: String, displayName: String): SourceMediaInfo?
}
