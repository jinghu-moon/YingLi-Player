package seeyuer.yingli.player.data.processing.transcode

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
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
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.data.processing.muxer.ContainerMuxerFactory
import seeyuer.yingli.player.domain.processing.MediaTrackType
import seeyuer.yingli.player.domain.processing.OutputVerification
import seeyuer.yingli.player.domain.processing.OutputVerifier
import seeyuer.yingli.player.domain.processing.ProcessingChangeCode
import seeyuer.yingli.player.domain.processing.ProcessingEngine
import seeyuer.yingli.player.domain.processing.ProcessingEngineResult
import seeyuer.yingli.player.domain.processing.ProcessingPlan

/**
 * 目标 codec 为 `null` 表示「跟随源」——`OutputTarget` 的三个 codec/容器字段都可空（设计稿 §4.2）。
 *
 * 引擎与验证器必须用**同一份**解析结果：验证器把输出的轨道 mime 与「计划请求的 mime」相比，
 * 如果它读的是 `target.videoCodecMimeType` 这个可空原值，那「未指定 codec」的合法计划会被
 * 误报成 `VIDEO_CODEC_MISMATCH`——一个因为「没要求」而产生的失败。
 */
private fun ProcessingPlan.effectiveVideoMimeType(): String? =
    target.videoCodecMimeType
        ?: source.tracks.firstOrNull { it.type == MediaTrackType.VIDEO }?.mimeType

private fun ProcessingPlan.effectiveAudioMimeType(): String? =
    target.audioCodecMimeType
        ?: source.tracks.firstOrNull { it.type == MediaTrackType.AUDIO }?.mimeType

@OptIn(UnstableApi::class)
class Media3ProcessingEngine(
    context: Context,
    private val dispatchers: AppDispatchers,
) : ProcessingEngine {
    private val applicationContext = context.applicationContext
    @Volatile private var activeTransformer: Transformer? = null

    @OptIn(UnstableApi::class)
    override suspend fun process(
        plan: ProcessingPlan,
        outputPath: String,
        onProgress: suspend (Float) -> Unit,
    ): ProcessingEngineResult {
        // 目标 codec 为 null 表示「跟随源」，必须先解析成确定的 mime 再交给框架。
        val videoMimeType = plan.effectiveVideoMimeType()
            ?: return ProcessingEngineResult.Failed("VIDEO_CODEC_UNSPECIFIED")
        val audioMimeType = plan.effectiveAudioMimeType()
        // 步骤 7/8（G4 / F19）：容器不存在，或容器收不下这个编码组合时，必须在 build 之前拒绝。
        // 库对「不可能组合」的处理是**编完整段之后才抛异常**——那是一次注定作废的完整编码。
        // 能力表与封装器同源：[ContainerMuxerFactory.supports] 问的就是库自己会问的那个方法。
        val muxerFactory = ContainerMuxerFactory.forContainerMimeType(plan.target.containerMimeType)
            ?: return ProcessingEngineResult.Failed("TARGET_CONTAINER_UNSUPPORTED")
        if (!muxerFactory.supports(C.TRACK_TYPE_VIDEO, videoMimeType)) {
            return ProcessingEngineResult.Failed("CONTAINER_VIDEO_CODEC_UNSUPPORTED")
        }
        if (plan.retainedTrackIds.isNotEmpty() && audioMimeType != null &&
            !muxerFactory.supports(C.TRACK_TYPE_AUDIO, audioMimeType)
        ) {
            return ProcessingEngineResult.Failed("CONTAINER_AUDIO_CODEC_UNSUPPORTED")
        }
        // G6 的第二道防线：在花掉一次注定作废的整段编码之前就拒绝。
        Media3CodecAvailability
            .videoEncoderErrorCode(videoMimeType, plan.targetWidth, plan.targetHeight)
            ?.let { return ProcessingEngineResult.Failed(it) }
        if (plan.retainedTrackIds.isNotEmpty() && audioMimeType != null) {
            Media3CodecAvailability
                .audioEncoderErrorCode(audioMimeType)
                ?.let { return ProcessingEngineResult.Failed(it) }
        }
        return try {
            withContext(dispatchers.main) {
                check(Looper.myLooper() == Looper.getMainLooper())
                coroutineScope {
                    suspendCancellableCoroutine { continuation ->
                        val output = File(outputPath)
                        var progressJob: Job? = null
                        // G6 的第一道防线：库静默回退时**依然报告成功**，只有这个回调会说出来。
                        // 回调与 onCompleted/onError 一样跑在 setLooper 指定的主线程上，因此这里
                        // 不需要同步。记录而不中断：中断要区分「我们自己取消」与「用户取消」，
                        // 而结果无论如何都不会被提交（执行器见到非空 fallbacks 即拒绝），
                        // 让库把剩下的帧编完没有额外的正确性代价。
                        val fallbacks = mutableSetOf<ProcessingChangeCode>()
                        val listener = object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                progressJob?.cancel()
                                activeTransformer = null
                                if (continuation.isActive) {
                                    continuation.resume(ProcessingEngineResult.Completed(fallbacks.toSet()))
                                }
                            }

                            override fun onFallbackApplied(
                                composition: Composition,
                                originalTransformationRequest: TransformationRequest,
                                fallbackTransformationRequest: TransformationRequest,
                            ) {
                                fallbacks += Media3FallbackMapping.changes(
                                    originalTransformationRequest,
                                    fallbackTransformationRequest,
                                )
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                progressJob?.cancel()
                                activeTransformer = null
                                output.delete()
                                if (continuation.isActive) continuation.resume(ProcessingEngineResult.Failed("TRANSCODE_FAILED"))
                            }
                        }
                        val transformerBuilder = Transformer.Builder(applicationContext)
                            .setLooper(Looper.getMainLooper())
                            .setVideoMimeType(videoMimeType)
                            .setEncoderFactory(Media3EncoderSettings.encoderFactory(applicationContext, plan))
                        // 未解析出音频编码格式时**不调用** `setAudioMimeType`：`null` 的语义就是
                        // 「没有对音频编码格式提出要求」，把它翻译成「要求某个默认格式」会凭空改变输出。
                        if (audioMimeType != null) transformerBuilder.setAudioMimeType(audioMimeType)
                        // G4：非 MP4 容器换成 media3-muxer 的封装器。MP4 **刻意**不交给
                        // `ContainerMuxerFactory`——它是 Transformer 自己的取舍，理由见该类的
                        // 类注释：库自带的 `DefaultMuxer.Factory` 本就产出 MP4，且带着
                        // `videoDurationUs`、元数据与 faststart 配置，换掉它是净损失。
                        // 判据用容器 mime 而不是封装器实例：是否接管是 Transformer 的取舍，
                        // 不是封装器工厂的属性（工厂对四个容器一视同仁）。
                        if (plan.target.containerMimeType != MimeTypes.VIDEO_MP4) {
                            transformerBuilder.setMuxerFactory(muxerFactory)
                        }
                        val transformer = transformerBuilder.addListener(listener).build()
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
                        // G2：HDR 模式只能通过 Composition 指定（`Transformer.Builder` 没有 `setHdrMode`）。
                        // 轨道类型由此处显式声明，取值直接来自计划的 `retainedTrackIds`：
                        // 计划说要保留音轨就产音视频，说不要（或源本来没有音轨）就只产视频。
                        // 不用无参 `Builder()` 是因为它在 1.10.1 已被标记废弃，
                        // 而能「从 item 自身推断轨道类型」的 `fromSingleItem` 是包内私有。
                        val sequence = if (plan.retainedTrackIds.isEmpty()) {
                            EditedMediaItemSequence.withVideoFrom(listOf(edited))
                        } else {
                            EditedMediaItemSequence.withAudioAndVideoFrom(listOf(edited))
                        }
                        val composition = Composition.Builder(sequence)
                            .setHdrMode(Media3EncoderSettings.hdrMode(plan))
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
                        transformer.start(composition, outputPath)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            File(outputPath).delete()
            ProcessingEngineResult.Failed("TRANSCODE_FAILED")
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
    override suspend fun verify(path: String, plan: ProcessingPlan): OutputVerification = withContext(dispatchers.io) {
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
                var videoFormat: MediaFormat? = null
                repeat(extractor.trackCount) { index ->
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME)
                    if (mime?.startsWith("video/") == true && videoTrack < 0) {
                        videoTrack = index
                        videoMime = mime
                        videoFormat = format
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
                if (videoMime != plan.effectiveVideoMimeType()) errors += "VIDEO_CODEC_MISMATCH"
                if (plan.retainedTrackIds.isNotEmpty() && audioTrack < 0) errors += "AUDIO_TRACK_MISSING"
                if (audioTrack >= 0 && audioMime != plan.effectiveAudioMimeType()) errors += "AUDIO_CODEC_MISMATCH"
                if (width != plan.targetWidth || height != plan.targetHeight) errors += "DIMENSION_MISMATCH"
                // 带时间区间的任务产出的是区间而不是整段源，期望时长必须跟着区间走。
                // 容差沿用同一个常数：remux 的实际起点会向前吸附到关键帧，实际时长可能略长于请求区间。
                val expectedDurationMillis = plan.range?.durationMillis ?: plan.source.durationMillis
                val duration = durationMillis
                if (duration == null || kotlin.math.abs(duration - expectedDurationMillis) > DURATION_TOLERANCE_MILLIS) {
                    errors += "DURATION_MISMATCH"
                }
                if (videoTrack >= 0) {
                    extractor.selectTrack(videoTrack)
                    if (!hasReadableSample(extractor, videoFormat)) {
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
                OutputVerification(
                    valid = errors.isEmpty(),
                    errorCodes = errors,
                    durationMillis = duration,
                    width = width,
                    height = height,
                    averageBitrateBitsPerSecond = averageBitrate(file.length(), duration),
                )
            } finally {
                extractor.release()
            }
        }.getOrElse { OutputVerification(false, setOf("OUTPUT_PROBE_FAILED")) }
    }

    /**
     * 「至少有一个视频样本能被读出来」。
     *
     * **不能用一个固定大小的缓冲去读**：`MediaExtractor.readSampleData` 在样本大于缓冲容量时抛
     * `IllegalArgumentException`，此前该异常被外层 `runCatching` 吞成 `OUTPUT_PROBE_FAILED`，
     * 于是**一次合法输出被整体判成验证失败**，连已经查明的尺寸 / 时长 / mime 结论都一起丢掉。
     *
     * 真机实测（`docs/architecture/Organizing-Page-Function-Design.md` §20.3）：1920×1080 高熵源的
     * `compatible_mp4`（8 Mbps）与 `balanced_mp4`（5 Mbps）输出，首样本都超过原先固定的 1 MiB，
     * 双双误报；唯独 `space_saver_mp4`（1280×720 / 2.5 Mbps）样本小于 1 MiB 而通过。
     * 这不是边角场景——**高码率恰恰是 `compatible_mp4` 这一默认预设的常态**。
     *
     * 缓冲容量优先取容器声明的 `KEY_MAX_INPUT_SIZE`，再退回 1 MiB 下限，并以 [MAX_SAMPLE_BYTES]
     * 封顶；首次失败时用封顶容量重试一次。整段用 `runCatching` 隔离，使这一项失败只产生
     * `VIDEO_SAMPLE_UNREADABLE`，不再摧毁整份验证结论。
     */
    private fun hasReadableSample(extractor: MediaExtractor, format: MediaFormat?): Boolean {
        val declared = format?.integer(MediaFormat.KEY_MAX_INPUT_SIZE) ?: SAMPLE_BYTES
        val capacity = declared.coerceIn(SAMPLE_BYTES, MAX_SAMPLE_BYTES)
        if (readSample(extractor, capacity)) return true
        return capacity < MAX_SAMPLE_BYTES && readSample(extractor, MAX_SAMPLE_BYTES)
    }

    private fun readSample(extractor: MediaExtractor, capacity: Int): Boolean =
        runCatching {
            extractor.readSampleData(java.nio.ByteBuffer.allocate(capacity), 0) > 0
        }.getOrDefault(false)

    /**
     * 整个输出文件的平均码率（含音频与容器开销）。
     *
     * 真机实测（`docs/architecture/Organizing-Page-Function-Design.md` §20.1.3）输出 MP4 的视频轨
     * 读不到 `KEY_BIT_RATE`，因此这是唯一可得的码率观测量。它受内容复杂度影响、**不等于**请求码率，
     * 只作对照数据，见 [OutputVerification.averageBitrateBitsPerSecond] 的口径说明。
     */
    private fun averageBitrate(sizeBytes: Long, durationMillis: Long?): Int? {
        if (durationMillis == null || durationMillis <= 0L || sizeBytes <= 0L) return null
        return (sizeBytes * 8 * 1_000 / durationMillis).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun MediaFormat.integer(key: String): Int? = if (containsKey(key)) getInteger(key) else null
    private fun MediaFormat.long(key: String): Long? = if (containsKey(key)) getLong(key) else null

    private companion object {
        const val SAMPLE_BYTES = 1024 * 1024

        /**
         * 采样缓冲的容量上限（16 MiB）。足以覆盖 4K 高码率的单个关键帧，
         * 又不至于在探测阶段因为一次误判而分配出离谱的内存。
         */
        const val MAX_SAMPLE_BYTES = 16 * 1024 * 1024
        const val DURATION_TOLERANCE_MILLIS = 1_500L
    }
}
