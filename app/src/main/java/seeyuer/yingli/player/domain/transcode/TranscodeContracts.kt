package seeyuer.yingli.player.domain.transcode

import kotlinx.coroutines.flow.Flow
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.processing.ProcessingProjectId

@JvmInline
value class TranscodePresetId(val value: String) {
    init { require(value.matches(Regex("[a-z0-9_-]{1,48}"))) }
}

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

data class TranscodePreset(
    val id: TranscodePresetId,
    val version: Int,
    val targetContainerMimeType: String,
    val targetVideoMimeType: String,
    val targetAudioMimeType: String,
    val maximumLongEdge: Int,
    val targetVideoBitrate: Int,
    val targetAudioBitrate: Int,
) {
    init {
        require(version > 0)
        require(targetContainerMimeType.contains('/'))
        require(targetVideoMimeType.startsWith("video/"))
        require(targetAudioMimeType.startsWith("audio/"))
        require(maximumLongEdge > 0 && maximumLongEdge % 2 == 0)
        require(targetVideoBitrate > 0 && targetAudioBitrate > 0)
    }
}

object TranscodePresets {
    val Compatible = TranscodePreset(
        TranscodePresetId("compatible_mp4"), 1, "video/mp4", "video/avc", "audio/mp4a-latm",
        maximumLongEdge = 1_920, targetVideoBitrate = 8_000_000, targetAudioBitrate = 192_000,
    )
    val Balanced = TranscodePreset(
        TranscodePresetId("balanced_mp4"), 1, "video/mp4", "video/avc", "audio/mp4a-latm",
        maximumLongEdge = 1_920, targetVideoBitrate = 5_000_000, targetAudioBitrate = 160_000,
    )
    val SpaceSaver = TranscodePreset(
        TranscodePresetId("space_saver_mp4"), 1, "video/mp4", "video/avc", "audio/mp4a-latm",
        maximumLongEdge = 1_280, targetVideoBitrate = 2_500_000, targetAudioBitrate = 128_000,
    )
    val all: List<TranscodePreset> = listOf(Compatible, Balanced, SpaceSaver)
}

enum class TranscodeChangeCode {
    RESOLUTION_REDUCED,
    VIDEO_CODEC_CHANGED,
    AUDIO_CODEC_CHANGED,
    EXTRA_AUDIO_TRACKS_REMOVED,
    SUBTITLES_NOT_EMBEDDED,
    HDR_TO_SDR,
    FRAME_RATE_CAPPED,
}

data class TranscodeChange(
    val code: TranscodeChangeCode,
    val requiresConfirmation: Boolean,
)

data class TranscodePlan(
    val source: SourceMediaInfo,
    val preset: TranscodePreset,
    val targetWidth: Int,
    val targetHeight: Int,
    val retainedAudioTrackIds: Set<Int>,
    val estimatedOutputBytes: Long,
    val requiredFreeBytes: Long,
    val outputDisplayName: String,
    val changes: List<TranscodeChange>,
) {
    init {
        require(targetWidth > 0 && targetHeight > 0)
        require(targetWidth % 2 == 0 && targetHeight % 2 == 0)
        require(estimatedOutputBytes > 0)
        require(requiredFreeBytes >= estimatedOutputBytes)
        require(outputDisplayName.isNotBlank())
    }

    val requiresConfirmation: Boolean get() = changes.any(TranscodeChange::requiresConfirmation)
}

sealed interface TranscodePlanningResult {
    data class Ready(val plan: TranscodePlan) : TranscodePlanningResult
    data class Rejected(val code: String) : TranscodePlanningResult
}

object DefaultTranscodePlanner {
    fun plan(
        source: SourceMediaInfo,
        capabilities: DeviceMediaCapabilities,
        preset: TranscodePreset,
        availableBytes: Long,
    ): TranscodePlanningResult {
        val encoder = capabilities.encoders
            .filter { it.mimeType == preset.targetVideoMimeType }
            .maxByOrNull { it.maxWidth.toLong() * it.maxHeight }
            ?: return TranscodePlanningResult.Rejected("VIDEO_ENCODER_UNAVAILABLE")
        val sourceLong = maxOf(source.width, source.height)
        val sourceShort = minOf(source.width, source.height)
        val targetLong = minOf(sourceLong, preset.maximumLongEdge, maxOf(encoder.maxWidth, encoder.maxHeight))
            .coerceAtLeast(2)
        val scale = targetLong.toDouble() / sourceLong
        val targetShort = (sourceShort * scale).toInt().coerceAtLeast(2)
        val landscape = source.width >= source.height
        val width = even(if (landscape) targetLong else targetShort)
        val height = even(if (landscape) targetShort else targetLong)
        if (width > encoder.maxWidth && height > encoder.maxHeight) {
            return TranscodePlanningResult.Rejected("ENCODER_SIZE_UNSUPPORTED")
        }

        val changes = buildList {
            if (width != source.width || height != source.height) {
                add(TranscodeChange(TranscodeChangeCode.RESOLUTION_REDUCED, false))
            }
            val videoMime = source.tracks.first { it.type == MediaTrackType.VIDEO }.mimeType
            if (videoMime != preset.targetVideoMimeType) {
                add(TranscodeChange(TranscodeChangeCode.VIDEO_CODEC_CHANGED, false))
            }
            val audioTracks = source.tracks.filter { it.type == MediaTrackType.AUDIO }
            if (audioTracks.firstOrNull()?.mimeType?.let { it != preset.targetAudioMimeType } == true) {
                add(TranscodeChange(TranscodeChangeCode.AUDIO_CODEC_CHANGED, false))
            }
            if (audioTracks.size > 1) {
                add(TranscodeChange(TranscodeChangeCode.EXTRA_AUDIO_TRACKS_REMOVED, true))
            }
            if (source.tracks.any { it.type == MediaTrackType.SUBTITLE }) {
                add(TranscodeChange(TranscodeChangeCode.SUBTITLES_NOT_EMBEDDED, true))
            }
            if (source.hdrFormat != HdrFormat.SDR && !encoder.supportsHdr) {
                add(TranscodeChange(TranscodeChangeCode.HDR_TO_SDR, true))
            }
            if ((source.frameRate ?: 0f) > encoder.maxFrameRate) {
                add(TranscodeChange(TranscodeChangeCode.FRAME_RATE_CAPPED, true))
            }
        }
        val durationSeconds = source.durationMillis / 1_000.0
        val audioBitrate = if (source.tracks.any { it.type == MediaTrackType.AUDIO }) preset.targetAudioBitrate else 0
        val estimate = (((preset.targetVideoBitrate + audioBitrate) / 8.0) * durationSeconds * ESTIMATE_FACTOR)
            .toLong()
            .coerceAtLeast(MINIMUM_ESTIMATE_BYTES)
        val required = estimate + FREE_SPACE_RESERVE_BYTES
        if (availableBytes < required) return TranscodePlanningResult.Rejected("INSUFFICIENT_STORAGE")
        val baseName = source.displayName.substringBeforeLast('.', source.displayName).sanitizeOutputName()
        return TranscodePlanningResult.Ready(TranscodePlan(
            source = source,
            preset = preset,
            targetWidth = width,
            targetHeight = height,
            retainedAudioTrackIds = source.tracks.firstOrNull { it.type == MediaTrackType.AUDIO }
                ?.let { setOf(it.id) }
                .orEmpty(),
            estimatedOutputBytes = estimate,
            requiredFreeBytes = required,
            outputDisplayName = "${baseName}_${preset.id.value}.mp4",
            changes = changes,
        ))
    }

    private fun even(value: Int): Int = value.coerceAtLeast(2) and -2
    private fun String.sanitizeOutputName(): String = replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_', '.')
        .take(96)
        .ifBlank { "YingLi_output" }

    private const val ESTIMATE_FACTOR = 1.15
    private const val MINIMUM_ESTIMATE_BYTES = 1L * 1024 * 1024
    private const val FREE_SPACE_RESERVE_BYTES = 128L * 1024 * 1024
}

interface MediaCapabilityProbe {
    suspend fun deviceCapabilities(): DeviceMediaCapabilities
    suspend fun source(mediaId: MediaItemId, uri: String, displayName: String): SourceMediaInfo?
}

sealed interface TranscodeEngineResult {
    data object Completed : TranscodeEngineResult
    data class Failed(val errorCode: String) : TranscodeEngineResult
    data object Canceled : TranscodeEngineResult
}

interface TranscodeEngine {
    suspend fun transcode(plan: TranscodePlan, outputPath: String, onProgress: suspend (Float) -> Unit): TranscodeEngineResult
    suspend fun cancel()
}

data class OutputVerification(
    val valid: Boolean,
    val errorCodes: Set<String> = emptySet(),
    val durationMillis: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

interface OutputVerifier {
    suspend fun verify(path: String, plan: TranscodePlan): OutputVerification
}

interface TranscodeQueue {
    val pendingPlans: Flow<Map<ProcessingProjectId, TranscodePlan>>
    suspend fun enqueue(plan: TranscodePlan, destructiveChangesConfirmed: Boolean): ProcessingProjectId
    suspend fun plan(projectId: ProcessingProjectId): TranscodePlan?
}
