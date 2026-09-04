package seeyuer.yingli.player.app.clips

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.foundation.AppDispatchers
import seeyuer.yingli.player.domain.clips.ClipCapabilities
import seeyuer.yingli.player.domain.clips.ClipEngine
import seeyuer.yingli.player.domain.clips.ClipEngineResult
import seeyuer.yingli.player.domain.clips.ClipSegment
import seeyuer.yingli.player.domain.clips.ClipSource

class PlatformClipEngine(
    context: Context,
    private val dispatchers: AppDispatchers,
) : ClipEngine {
    private val applicationContext = context.applicationContext

    override suspend fun probe(uri: String): Pair<ClipSource, ClipCapabilities>? = withContext(dispatchers.io) {
        val extractor = MediaExtractor()
        try {
            extractor.setSource(uri)
            var durationMicros = 0L
            var hasVideo = false
            var hasAudio = false
            var fastCompatible = true
            repeat(extractor.trackCount) { index ->
                val format = extractor.getTrackFormat(index)
                durationMicros = maxOf(durationMicros, format.getLongOrDefault(MediaFormat.KEY_DURATION, 0))
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                hasVideo = hasVideo || mime.startsWith("video/")
                hasAudio = hasAudio || mime.startsWith("audio/")
                if ((mime.startsWith("video/") || mime.startsWith("audio/")) && mime !in FAST_MP4_MIME_TYPES) {
                    fastCompatible = false
                }
            }
            if (!hasVideo || durationMicros <= 0) return@withContext null
            ClipSource(uri, durationMicros / MICROS_PER_MILLI, hasVideo, hasAudio) to ClipCapabilities(
                fastCut = fastCompatible,
                accurateCut = true,
                diagnosticCode = if (fastCompatible) null else "FAST_CONTAINER_UNSUPPORTED",
            )
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    override suspend fun fastCut(
        source: ClipSource,
        segment: ClipSegment,
        outputPath: String,
    ): ClipEngineResult = withContext(dispatchers.io) {
        require(segment.endMillis <= source.durationMillis)
        val output = File(outputPath)
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setSource(source.uri)
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = mutableMapOf<Int, Int>()
            var maximumSampleSize = DEFAULT_SAMPLE_BUFFER_SIZE
            repeat(extractor.trackCount) { track ->
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    if (mime !in FAST_MP4_MIME_TYPES) return@withContext ClipEngineResult.Unsupported("FAST_CODEC_UNSUPPORTED")
                    extractor.selectTrack(track)
                    trackMap[track] = muxer.addTrack(format)
                    maximumSampleSize = maxOf(
                        maximumSampleSize,
                        format.getIntOrDefault(MediaFormat.KEY_MAX_INPUT_SIZE, DEFAULT_SAMPLE_BUFFER_SIZE),
                    )
                }
            }
            if (trackMap.isEmpty()) return@withContext ClipEngineResult.Unsupported("NO_MEDIA_TRACKS")
            extractor.seekTo(segment.startMillis * MICROS_PER_MILLI, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val baseTimeMicros = extractor.sampleTime.takeIf { it >= 0 }
                ?: return@withContext ClipEngineResult.Failed("SEEK_FAILED")
            val endMicros = segment.endMillis * MICROS_PER_MILLI
            val buffer = ByteBuffer.allocateDirect(maximumSampleSize)
            val info = MediaCodec.BufferInfo()
            var lastSampleMicros = baseTimeMicros
            muxer.start()
            while (true) {
                val sourceTrack = extractor.sampleTrackIndex
                val sampleTime = extractor.sampleTime
                if (sourceTrack < 0 || sampleTime < 0 || sampleTime >= endMicros) break
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val extractorFlags = extractor.sampleFlags
                if (extractorFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                    output.delete()
                    return@withContext ClipEngineResult.Unsupported("ENCRYPTED_SAMPLE_UNSUPPORTED")
                }
                var codecFlags = 0
                if (extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                }
                if (extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                    codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                }
                info.set(0, size, (sampleTime - baseTimeMicros).coerceAtLeast(0), codecFlags)
                trackMap[sourceTrack]?.let { targetTrack -> muxer.writeSampleData(targetTrack, buffer, info) }
                lastSampleMicros = sampleTime
                buffer.clear()
                if (!extractor.advance()) break
            }
            ClipEngineResult.Success(baseTimeMicros / MICROS_PER_MILLI, lastSampleMicros / MICROS_PER_MILLI)
        } catch (cancelled: CancellationException) {
            output.delete()
            throw cancelled
        } catch (_: Exception) {
            output.delete()
            ClipEngineResult.Failed("FAST_CUT_FAILED")
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    @UnstableApi
    override suspend fun accurateCut(
        source: ClipSource,
        segment: ClipSegment,
        outputPath: String,
    ): ClipEngineResult = withContext(dispatchers.main) {
        require(Looper.myLooper() == Looper.getMainLooper())
        require(segment.endMillis <= source.durationMillis)
        val output = File(outputPath)
        suspendCancellableCoroutine { continuation ->
            lateinit var transformer: Transformer
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (continuation.isActive) {
                        continuation.resume(ClipEngineResult.Success(segment.startMillis, segment.endMillis))
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    output.delete()
                    if (continuation.isActive) continuation.resume(ClipEngineResult.Failed("ACCURATE_CUT_FAILED"))
                }
            }
            transformer = Transformer.Builder(applicationContext)
                .setLooper(Looper.getMainLooper())
                .addListener(listener)
                .build()
            continuation.invokeOnCancellation {
                transformer.cancel()
                output.delete()
            }
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(segment.startMillis)
                .setEndPositionMs(segment.endMillis)
                .setStartsAtKeyFrame(false)
                .build()
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(source.uri))
                .setClippingConfiguration(clipping)
                .build()
            transformer.start(EditedMediaItem.Builder(mediaItem).build(), outputPath)
        }
    }

    private fun MediaFormat.getLongOrDefault(key: String, default: Long): Long =
        if (containsKey(key)) getLong(key) else default

    private fun MediaFormat.getIntOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    private fun MediaExtractor.setSource(value: String) {
        val uri = Uri.parse(value)
        if (uri.scheme == null || uri.scheme == "file") {
            setDataSource(uri.path ?: value)
        } else {
            setDataSource(applicationContext, uri, null)
        }
    }

    private companion object {
        const val MICROS_PER_MILLI = 1_000L
        const val DEFAULT_SAMPLE_BUFFER_SIZE = 1024 * 1024
        val FAST_MP4_MIME_TYPES = setOf(
            "video/avc",
            "video/hevc",
            "video/mp4v-es",
            "audio/mp4a-latm",
            "audio/mpeg",
        )
    }
}
