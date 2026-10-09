package seeyuer.yingli.player.data.processing.transcode

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EncoderUtil
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.transcode.DeviceMediaCapabilities
import seeyuer.yingli.player.domain.transcode.EncoderCapability
import seeyuer.yingli.player.domain.transcode.HdrFormat
import seeyuer.yingli.player.domain.transcode.MediaCapabilityProbe
import seeyuer.yingli.player.domain.transcode.MediaTrackInfo
import seeyuer.yingli.player.domain.transcode.MediaTrackType
import seeyuer.yingli.player.domain.transcode.SourceMediaInfo

@OptIn(UnstableApi::class)
class AndroidMediaCapabilityProbe(
    context: Context,
    private val dispatchers: AppDispatchers,
    private val clock: AppClock,
) : MediaCapabilityProbe {
    private val applicationContext = context.applicationContext
    @Volatile private var cachedCapabilities: DeviceMediaCapabilities? = null

    override suspend fun deviceCapabilities(): DeviceMediaCapabilities = withContext(dispatchers.io) {
        cachedCapabilities ?: synchronized(this@AndroidMediaCapabilityProbe) {
            cachedCapabilities ?: probeEncoders().also { cachedCapabilities = it }
        }
    }

    override suspend fun source(
        mediaId: MediaItemId,
        uri: String,
        displayName: String,
    ): SourceMediaInfo? = withContext(dispatchers.io) {
        runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(applicationContext, Uri.parse(uri), null)
                val tracks = mutableListOf<MediaTrackInfo>()
                var width = 0
                var height = 0
                var durationMicros = 0L
                var frameRate: Float? = null
                var bitrate: Int? = null
                var hdr = HdrFormat.SDR
                repeat(extractor.trackCount) { index ->
                    val format = extractor.getTrackFormat(index)
                    val mime = format.string(MediaFormat.KEY_MIME) ?: return@repeat
                    val type = when {
                        mime.startsWith("video/") -> MediaTrackType.VIDEO
                        mime.startsWith("audio/") -> MediaTrackType.AUDIO
                        mime.startsWith("text/") || mime.contains("subtitle") || mime.contains("cea") -> MediaTrackType.SUBTITLE
                        else -> return@repeat
                    }
                    tracks += MediaTrackInfo(
                        id = index,
                        type = type,
                        mimeType = mime,
                        language = format.string(MediaFormat.KEY_LANGUAGE),
                        channels = format.integer(MediaFormat.KEY_CHANNEL_COUNT),
                    )
                    durationMicros = maxOf(durationMicros, format.long(MediaFormat.KEY_DURATION) ?: 0L)
                    if (type == MediaTrackType.VIDEO) {
                        width = format.integer(MediaFormat.KEY_WIDTH) ?: width
                        height = format.integer(MediaFormat.KEY_HEIGHT) ?: height
                        frameRate = format.integer(MediaFormat.KEY_FRAME_RATE)?.toFloat() ?: frameRate
                        bitrate = format.integer(MediaFormat.KEY_BIT_RATE) ?: bitrate
                        hdr = format.integer(MediaFormat.KEY_COLOR_TRANSFER).toHdrFormat()
                    }
                }
                SourceMediaInfo(
                    mediaId = mediaId,
                    uri = uri,
                    displayName = displayName,
                    durationMillis = durationMicros / 1_000,
                    width = width,
                    height = height,
                    frameRate = frameRate,
                    videoBitrate = bitrate,
                    containerMimeType = applicationContext.contentResolver.getType(Uri.parse(uri)),
                    hdrFormat = hdr,
                    tracks = tracks,
                )
            } finally {
                extractor.release()
            }
        }.getOrNull()
    }

    fun invalidateDeviceCache() {
        cachedCapabilities = null
    }

    private fun probeEncoders(): DeviceMediaCapabilities {
        val diagnostics = linkedSetOf<String>()
        val encoders = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.asSequence()
            .filter { it.isEncoder }
            .flatMap { info ->
                info.supportedTypes.asSequence()
                    .filter { it.startsWith("video/") }
                    .mapNotNull { mime ->
                        runCatching {
                            val video = requireNotNull(info.getCapabilitiesForType(mime).videoCapabilities)
                            EncoderCapability(
                                mimeType = mime,
                                maxWidth = video.supportedWidths.upper,
                                maxHeight = video.supportedHeights.upper,
                                maxFrameRate = video.supportedFrameRates.upper.toFloat(),
                                supportsHdr = info.supportsHdrEditing(mime),
                                hardwareAccelerated = info.isHardwareAccelerated,
                            )
                        }.onFailure { diagnostics += "CODEC_CAPABILITY_ERROR" }.getOrNull()
                    }
            }
            .distinctBy {
                listOf(it.mimeType, it.maxWidth, it.maxHeight, it.hardwareAccelerated, it.supportsHdr)
            }
            .toList()
        if (encoders.isEmpty()) diagnostics += "NO_VIDEO_ENCODER_REPORTED"
        return DeviceMediaCapabilities(encoders, clock.now().toEpochMilli(), diagnostics)
    }

    /**
     * 该编码器能否**产出 HDR 视频**（G2 的实测化）。
     *
     * 修复前这里是硬编码 `false`，于是「设备不支持 HDR 编码」这句话与事实无关：真机实测
     * （`docs/architecture/Organizing-Page-Function-Design.md` §20.1.4）显示本设备存在
     * HEVC Main10 + `COLOR_FormatYUVP010(54)`，还有一个名字里就写着 `hdr` 的专用编码器
     * `c2.qti.hevc.encoder.hdr`。硬编码的后果是**每一个 HDR 源都会被规划成 SDR**，而当时的
     * 引擎见到 `HDR_TO_SDR` 就直接失败（G2）——两者叠加让 HDR 视频完全无法转码。
     *
     * 判据直接用 Media3 自己挑 HDR 编码器时用的那个：[EncoderUtil.getSupportedEncodersForHdrEditing]
     * 会检查 `FEATURE_HdrEditing` 特性、按 [ColorInfo] 推导允许的编码 profile（HEVC Main10 系）、
     * 再核对 `profileLevels` 与尺寸上限。自己再手写一遍 `profileLevels` 判断只会得到
     * 第二个真源，而且可能与引擎的实际选择不一致（奥卡姆剃刀：不重复实现同一个谓词）。
     *
     * `EncoderUtil` 在 API < 33 一律返回空列表——HDR 编辑本身就是 API 33+ 的能力
     * （`HdrFormat` 的保留路径要 MediaCodec 支持 HDR 编辑），因此在 API 31/32 上
     * 「这台设备不能保留 HDR」是事实，不是保守取值。
     *
     * 只要两种 HDR 传输函数（HDR10 的 PQ / HLG）里有一种被支持即算支持：
     * 设备能力是「这个编码器能不能编 HDR」，与具体源用哪一种无关。
     */
    private fun MediaCodecInfo.supportsHdrEditing(mimeType: String): Boolean =
        HDR_COLOR_INFOS.any { colorInfo ->
            EncoderUtil.getSupportedEncodersForHdrEditing(mimeType, colorInfo).any { it.name == name }
        }

    private fun Int?.toHdrFormat(): HdrFormat = when (this) {
        MediaFormat.COLOR_TRANSFER_ST2084 -> HdrFormat.HDR10
        MediaFormat.COLOR_TRANSFER_HLG -> HdrFormat.HLG
        null, MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> HdrFormat.SDR
        else -> HdrFormat.UNKNOWN
    }

    private fun MediaFormat.string(key: String): String? = if (containsKey(key)) getString(key) else null
    private fun MediaFormat.integer(key: String): Int? = if (containsKey(key)) getInteger(key) else null
    private fun MediaFormat.long(key: String): Long? = if (containsKey(key)) getLong(key) else null

    private companion object {
        /**
         * 本项目会识别出的两种 HDR 传输函数，统一按 BT.2020 广色域 + 有限范围 + 10 bit 请求。
         * 这里只用来问「这个编码器能不能编 HDR」，与具体源的实际位深无关
         * （10 bit 是 HDR 的必要条件，8 bit 的编码器不该被算作支持 HDR）。
         */
        val HDR_COLOR_INFOS = listOf(
            hdrColorInfo(C.COLOR_TRANSFER_ST2084),
            hdrColorInfo(C.COLOR_TRANSFER_HLG),
        )

        fun hdrColorInfo(transfer: Int) = ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorTransfer(transfer)
            .setColorRange(C.COLOR_RANGE_LIMITED)
            .setLumaBitdepth(HDR_BIT_DEPTH)
            .setChromaBitdepth(HDR_BIT_DEPTH)
            .build()

        const val HDR_BIT_DEPTH = 10
    }
}
