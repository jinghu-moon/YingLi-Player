package seeyuer.yingli.player.app.transcode

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import seeyuer.yingli.player.core.foundation.AppDispatchers
import seeyuer.yingli.player.domain.transcode.OutputVerification
import seeyuer.yingli.player.domain.transcode.OutputVerifier
import seeyuer.yingli.player.domain.transcode.TranscodeChangeCode
import seeyuer.yingli.player.domain.transcode.TranscodeEngine
import seeyuer.yingli.player.domain.transcode.TranscodeEngineResult
import seeyuer.yingli.player.domain.transcode.TranscodePlan

@OptIn(UnstableApi::class)
class Media3TranscodeEngine(
    context: Context,
    private val dispatchers: AppDispatchers,
) : TranscodeEngine {
    private val applicationContext = context.applicationContext
    @Volatile private var activeTransformer: Transformer? = null

    @OptIn(UnstableApi::class)
    override suspend fun transcode(
        plan: TranscodePlan,
        outputPath: String,
        onProgress: suspend (Float) -> Unit,
    ): TranscodeEngineResult {
        if (plan.changes.any { it.code == TranscodeChangeCode.HDR_TO_SDR }) {
            return TranscodeEngineResult.Failed("HDR_TONE_MAPPING_UNAVAILABLE")
        }
        return try {
            withContext(dispatchers.main) {
                check(Looper.myLooper() == Looper.getMainLooper())
                coroutineScope {
                    suspendCancellableCoroutine { continuation ->
                        val output = File(outputPath)
                        var progressJob: Job? = null
                        val listener = object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                progressJob?.cancel()
                                activeTransformer = null
                                if (continuation.isActive) continuation.resume(TranscodeEngineResult.Completed)
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                progressJob?.cancel()
                                activeTransformer = null
                                output.delete()
                                if (continuation.isActive) continuation.resume(TranscodeEngineResult.Failed("TRANSCODE_FAILED"))
                            }
                        }
                        val transformer = Transformer.Builder(applicationContext)
                            .setLooper(Looper.getMainLooper())
                            .setVideoMimeType(plan.preset.targetVideoMimeType)
                            .setAudioMimeType(plan.preset.targetAudioMimeType)
                            .addListener(listener)
                            .build()
                        activeTransformer = transformer
                        continuation.invokeOnCancellation {
                            transformer.cancel()
                            activeTransformer = null
                            output.delete()
                        }
                        val effect = Presentation.createForWidthAndHeight(
                            plan.targetWidth,
                            plan.targetHeight,
                            Presentation.LAYOUT_SCALE_TO_FIT,
                        )
                        val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(plan.source.uri)))
                            .setEffects(Effects(emptyList(), listOf(effect)))
                            .build()
                        progressJob = launch {
                            val holder = ProgressHolder()
                            while (continuation.isActive) {
                                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                                    onProgress((holder.progress / 100f).coerceIn(0f, 1f))
                                }
                                delay(PROGRESS_INTERVAL_MILLIS)
                            }
                        }
                        transformer.start(edited, outputPath)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            File(outputPath).delete()
            TranscodeEngineResult.Failed("TRANSCODE_FAILED")
        }
    }

    override suspend fun cancel() {
        withContext(dispatchers.main) { activeTransformer?.cancel() }
    }

    private companion object { const val PROGRESS_INTERVAL_MILLIS = 250L }
}

class MediaExtractorOutputVerifier(
    private val dispatchers: AppDispatchers,
) : OutputVerifier {
    override suspend fun verify(path: String, plan: TranscodePlan): OutputVerification = withContext(dispatchers.io) {
        runCatching {
            val file = File(path)
            if (!file.isFile || file.length() <= 0) {
                return@runCatching OutputVerification(false, setOf("OUTPUT_MISSING"))
            }
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(path)
                    var videoTrack = -1
                    var audioTrack = -1
                    var width: Int? = null
                    var height: Int? = null
                    var durationMillis: Long? = null
                    var videoMime: String? = null
                    var audioMime: String? = null
                    repeat(extractor.trackCount) { index ->
                        val format = extractor.getTrackFormat(index)
                        val mime = format.getString(MediaFormat.KEY_MIME)
                    if (mime?.startsWith("video/") == true && videoTrack < 0) {
                        videoTrack = index
                        videoMime = mime
                        width = format.integer(MediaFormat.KEY_WIDTH)
                        height = format.integer(MediaFormat.KEY_HEIGHT)
                        durationMillis = format.long(MediaFormat.KEY_DURATION)?.div(1_000)
                    } else if (mime?.startsWith("audio/") == true && audioTrack < 0) {
                        audioTrack = index
                        audioMime = mime
                    }
                }
                val errors = linkedSetOf<String>()
                if (videoTrack < 0) errors += "VIDEO_TRACK_MISSING"
                if (videoMime != plan.preset.targetVideoMimeType) errors += "VIDEO_CODEC_MISMATCH"
                if (plan.retainedAudioTrackIds.isNotEmpty() && audioTrack < 0) errors += "AUDIO_TRACK_MISSING"
                if (audioTrack >= 0 && audioMime != plan.preset.targetAudioMimeType) errors += "AUDIO_CODEC_MISMATCH"
                if (width != plan.targetWidth || height != plan.targetHeight) errors += "DIMENSION_MISMATCH"
                val duration = durationMillis
                if (duration == null || kotlin.math.abs(duration - plan.source.durationMillis) > DURATION_TOLERANCE_MILLIS) {
                    errors += "DURATION_MISMATCH"
                }
                if (videoTrack >= 0) {
                    extractor.selectTrack(videoTrack)
                    if (extractor.readSampleData(java.nio.ByteBuffer.allocate(SAMPLE_BYTES), 0) <= 0) {
                        errors += "VIDEO_SAMPLE_UNREADABLE"
                    }
                }
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(path)
                    val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frame == null) errors += "VIDEO_FRAME_UNDECODABLE" else frame.recycle()
                } catch (_: Exception) {
                    errors += "VIDEO_FRAME_UNDECODABLE"
                } finally {
                    retriever.release()
                }
                OutputVerification(errors.isEmpty(), errors, duration, width, height)
            } finally {
                extractor.release()
            }
        }.getOrElse { OutputVerification(false, setOf("OUTPUT_PROBE_FAILED")) }
    }

    private fun MediaFormat.integer(key: String): Int? = if (containsKey(key)) getInteger(key) else null
    private fun MediaFormat.long(key: String): Long? = if (containsKey(key)) getLong(key) else null

    private companion object {
        const val SAMPLE_BYTES = 1024 * 1024
        const val DURATION_TOLERANCE_MILLIS = 1_500L
    }
}
