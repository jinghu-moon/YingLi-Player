package seeyuer.yingli.player.data.processing.transcode

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
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
                                supportsHdr = false,
                                hardwareAccelerated = info.isHardwareAccelerated,
                            )
                        }.onFailure { diagnostics += "CODEC_CAPABILITY_ERROR" }.getOrNull()
                    }
            }
            .distinctBy { listOf(it.mimeType, it.maxWidth, it.maxHeight, it.hardwareAccelerated) }
            .toList()
        if (encoders.isEmpty()) diagnostics += "NO_VIDEO_ENCODER_REPORTED"
        return DeviceMediaCapabilities(encoders, clock.now().toEpochMilli(), diagnostics)
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
}
