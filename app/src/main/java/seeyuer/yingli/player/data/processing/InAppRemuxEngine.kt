package seeyuer.yingli.player.data.processing

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.MuxerException
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.common.AppLogEvent
import seeyuer.yingli.player.core.common.AppLogLevel
import seeyuer.yingli.player.core.common.AppLogger
import seeyuer.yingli.player.core.common.LogValue
import seeyuer.yingli.player.data.processing.muxer.ContainerMuxerFactory
import seeyuer.yingli.player.domain.processing.MediaRange
import seeyuer.yingli.player.domain.processing.ProcessingEngine
import seeyuer.yingli.player.domain.processing.ProcessingEngineResult
import seeyuer.yingli.player.domain.processing.ProcessingPlan

/**
 * 应用内封装引擎：**不重新编码**，只把源的轨道搬进目标容器（`ProcessingOperation.REMUX`）。
 *
 * 它是 `PlatformClipEngine.fastCut` 与「整文件 remux」的统一形态（设计稿 §6.2、§14.3 步骤 6/8）：
 * 同一个 `process(plan, ...)` 既支持带时间区间的切片、也支持 `plan.range == null` 的整文件换容器，
 * 差异只在 [ProcessingPlan.range] 是否存在，不再有第二套入口。
 *
 * **为什么需要它而不是全用 Transformer**：`Transformer` 的输出 codec 集合由 F18 决定
 * （H.263/H.264/H.265/MPEG-4 SP + AAC/AMR），VP9/AV1/Opus/Vorbis 只能以封装方式进入输出。
 * 这不是架构冗余，是可达性的直接后果（设计稿 §4.6）。
 *
 * **为什么用 media3 的 `Muxer` 而不是平台 `MediaMuxer`**（步骤 8）：
 *  1. 平台 `MediaMuxer` 只能写 MP4/WebM/3GP，且**只认平台自己的 codec 表**；media3 的
 *     `Mp4Muxer` 能把 VP9/Opus 写进 MP4，而平台 `MediaMuxer` 不能——这正是 G11
 *     （WebM 源走快速路径必然 `FAST_CONTAINER_UNSUPPORTED`）的根因；
 *  2. 目标容器由 [ProcessingPlan.target] 决定（步骤 7 已把 WebM/Ogg/ADTS 接进来），
 *     平台 `MediaMuxer` 无法表达这四个容器。
 *
 * **白名单来自对封装器的反查**（G3/G11）：本类**没有任何硬编码 mime 列表**，每个轨道都问
 * [ContainerMuxerFactory.supports]——也就是问目标容器的封装器自己收不收这个样本 mime。
 * 手写第二份列表就是 G3 的成因（两处会漂移，而「容器收哪些编码」只有封装器说得准）。
 *
 * **轨道选择**：视频轨全部保留（源只有一个视频轨），音轨只保留计划点名的那些。
 * `retainedTrackIds` 为空表示「目标不要音轨」，不是「保留全部」——后者会让计划里
 * `EXTRA_AUDIO_TRACKS_REMOVED` 这个后果与实际产出不一致。
 *
 * **无损封装只能落在关键帧上**：请求区间时用 `SEEK_TO_PREVIOUS_SYNC`，实际起点必然 ≤ 请求起点。
 * 如实回报实际区间而不是把请求值当成结果（Q220/Q537）。
 *
 * **为什么它需要 [AppLogger]**：搬运失败对外只能报一个 `REMUX_FAILED`（错误码是 UI 契约，
 * 不能把异常文本塞进去），而平台 `MediaExtractor`/media3 封装器抛出的原因才是可定位的信息
 * ——步骤 8 的真机用例正是靠这条日志才从「笼统失败」走到「VP9/Opus 的合成源缺 csd」。
 * 日志走项目的统一口径（事件码 + 脱敏），不记录路径与文件名。
 */
@OptIn(UnstableApi::class)
class InAppRemuxEngine(
    context: Context,
    private val dispatchers: AppDispatchers,
    private val logger: AppLogger,
) : ProcessingEngine {
    private val applicationContext = context.applicationContext

    @Volatile
    private var cancelled = false

    override suspend fun process(
        plan: ProcessingPlan,
        outputPath: String,
        onProgress: suspend (Float) -> Unit,
    ): ProcessingEngineResult = withContext(dispatchers.io) {
        cancelled = false
        val range = plan.range
        val output = File(outputPath)
        val muxerFactory = ContainerMuxerFactory.forContainerMimeType(plan.target.containerMimeType)
            ?: return@withContext ProcessingEngineResult.Failed("REMUX_CONTAINER_UNSUPPORTED")
        val extractor = MediaExtractor()
        var muxer: Muxer? = null
        try {
            extractor.setSource(plan.source.uri)

            // 先把要搬的轨道挑出来并登记到封装器上，再进入搬运循环：
            // `Muxer.addTrack` 必须在写样本之前完成（容器头/轨道表在此时确定）。
            val selected = mutableListOf<Pair<Int, Format>>()
            var maximumSampleSize = DEFAULT_SAMPLE_BUFFER_SIZE
            repeat(extractor.trackCount) { track ->
                val mediaFormat = extractor.getTrackFormat(track)
                val sampleMimeType = mediaFormat.stringOrDefault(MediaFormat.KEY_MIME, "")
                val trackType = when {
                    sampleMimeType.startsWith(VIDEO_PREFIX) -> C.TRACK_TYPE_VIDEO
                    sampleMimeType.startsWith(AUDIO_PREFIX) -> C.TRACK_TYPE_AUDIO
                    else -> return@repeat
                }
                // `retainedTrackIds` 为空 = 不要音轨（见类注释），不是「保留全部」。
                if (trackType == C.TRACK_TYPE_AUDIO && track !in plan.retainedTrackIds) return@repeat
                // 白名单 = 向目标容器的封装器反查（G3/G11），本类不持有任何 mime 列表。
                if (!muxerFactory.supports(trackType, sampleMimeType)) {
                    return@withContext ProcessingEngineResult.Failed("REMUX_CODEC_UNSUPPORTED")
                }
                extractor.selectTrack(track)
                selected += track to MediaFormatUtil.createFormatFromMediaFormat(mediaFormat)
                maximumSampleSize = maxOf(
                    maximumSampleSize,
                    mediaFormat.intOrDefault(MediaFormat.KEY_MAX_INPUT_SIZE, DEFAULT_SAMPLE_BUFFER_SIZE),
                )
            }
            if (selected.isEmpty()) return@withContext ProcessingEngineResult.Failed("NO_MEDIA_TRACKS")

            val opened = muxerFactory.create(outputPath)
            muxer = opened
            val trackMap = LinkedHashMap<Int, Int>()
            for ((sourceTrack, format) in selected) {
                trackMap[sourceTrack] = try {
                    opened.addTrack(format)
                } catch (_: MuxerException) {
                    // 封装器自己拒绝了轨道 ⇒ 我们的能力表与它漂移了。这是能力表的问题，
                    // 不是搬运失败，所以要报成「编码不受支持」而不是笼统的 REMUX_FAILED。
                    output.delete()
                    return@withContext ProcessingEngineResult.Failed("REMUX_CODEC_UNSUPPORTED")
                }
            }

            val firstSampleMicros = if (range == null) {
                // 整文件 remux 不 seek：第一个样本就是起点。
                extractor.sampleTime.takeIf { it >= 0 }
            } else {
                extractor.seekTo(range.startMillis * MICROS_PER_MILLI, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                extractor.sampleTime.takeIf { it >= 0 }
            } ?: return@withContext ProcessingEngineResult.Failed("SEEK_FAILED")

            val endMicros = range?.endMillis?.times(MICROS_PER_MILLI) ?: Long.MAX_VALUE
            val totalMicros = if (range == null) {
                (plan.source.durationMillis * MICROS_PER_MILLI).coerceAtLeast(1L)
            } else {
                (endMicros - firstSampleMicros).coerceAtLeast(1L)
            }

            val buffer = ByteBuffer.allocateDirect(maximumSampleSize)
            var lastSampleMicros = firstSampleMicros
            onProgress(0f)
            var reported = 0f
            while (true) {
                if (cancelled) {
                    output.delete()
                    return@withContext ProcessingEngineResult.Canceled
                }
                val sourceTrack = extractor.sampleTrackIndex
                val sampleTime = extractor.sampleTime
                if (sourceTrack < 0 || sampleTime < 0 || sampleTime >= endMicros) break
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val extractorFlags = extractor.sampleFlags
                if (extractorFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                    output.delete()
                    return@withContext ProcessingEngineResult.Failed("ENCRYPTED_SAMPLE_UNSUPPORTED")
                }
                // 只翻译「关键帧」这一个位：media3 的 `BufferInfo.flags` 用 `C.BufferFlags`，
                // 它的位含义与 `MediaCodec.BufferFlags` **不同**（例如 `SAMPLE_FLAG_PARTIAL_FRAME`
                // 是 4，而 `MediaCodec.BUFFER_FLAG_PARTIAL_FRAME` 是 8、在 media3 里是别的含义），
                // 逐位照搬会把错误语义传给封装器。封装器只关心关键帧。
                val flags = if (extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    C.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                val targetTrack = trackMap.getValue(sourceTrack)
                // `readSampleData` 写满 `[0, size)` 但不改 position/limit；封装器按 `remaining()`
                // 判断样本长度，因此这里显式把窗口收成 `[0, size)`。
                buffer.position(0)
                buffer.limit(size)
                opened.writeSampleData(
                    targetTrack,
                    buffer,
                    BufferInfo((sampleTime - firstSampleMicros).coerceAtLeast(0), size, flags),
                )
                buffer.clear()
                lastSampleMicros = sampleTime
                val fraction = ((sampleTime - firstSampleMicros).toFloat() / totalMicros).coerceIn(0f, 1f)
                if (fraction - reported >= PROGRESS_STEP) {
                    reported = fraction
                    onProgress(fraction)
                }
                if (!extractor.advance()) break
            }

            val actualStartMillis = firstSampleMicros / MICROS_PER_MILLI
            // 无损封装只能落在关键帧上，实际起点必然 ≤ 请求起点（`SEEK_TO_PREVIOUS_SYNC`）。
            // 如实回报实际区间而不是把请求值当成结果（Q220/Q537）。
            val actualEndMillis = maxOf(lastSampleMicros / MICROS_PER_MILLI, actualStartMillis + 1)
            opened.close()
            muxer = null
            onProgress(1f)
            ProcessingEngineResult.Completed(
                actualRange = MediaRange(actualStartMillis, actualEndMillis),
            )
        } catch (cancelled: CancellationException) {
            output.delete()
            throw cancelled
        } catch (error: Exception) {
            output.delete()
            logFailure(plan, error)
            ProcessingEngineResult.Failed("REMUX_FAILED")
        } finally {
            // `close()` 同时负责写尾部索引（MP4 的 moov、WebM 的 cues）与关闭输出通道，
            // 所以它只在成功路径上被调用；这里兜住异常路径上已经打开的封装器。
            runCatching { muxer?.close() }
            extractor.release()
        }
    }

    override suspend fun cancel() {
        // 搬运循环里没有挂起点，取消只能靠这个标志在线程内被观察到。
        cancelled = true
    }

    /**
     * 搬运失败的原因只在这里留下记录：对外只有 `REMUX_FAILED` 一个码。
     * 属性里不放路径/文件名（可识别信息），异常文本本身会经 [AppLogger] 的脱敏口径处理。
     */
    private fun logFailure(plan: ProcessingPlan, error: Exception) {
        logger.log(
            AppLogLevel.WARNING,
            AppLogEvent(
                EVENT_REMUX_FAILED,
                "In-app remux failed.",
                mapOf(
                    "containerMimeType" to LogValue.Public(plan.target.containerMimeType),
                    "failureType" to LogValue.Public(error::class.java.simpleName),
                    "failureMessage" to LogValue.Public(error.message ?: error::class.java.name),
                    // 抛出点比文本更稳定：异常文本会随库版本变，栈顶不会。
                    "failureSite" to LogValue.Public(
                        error.stackTrace.firstOrNull()?.let { "${it.className}.${it.methodName}" }
                            ?: "unknown",
                    ),
                ),
            ),
        )
    }

    private fun MediaExtractor.setSource(value: String) {
        val uri = Uri.parse(value)
        if (uri.scheme == null || uri.scheme == "file") {
            setDataSource(uri.path ?: value)
        } else {
            setDataSource(applicationContext, uri, null)
        }
    }

    private fun MediaFormat.stringOrDefault(key: String, default: String): String =
        if (containsKey(key)) getString(key) ?: default else default

    private fun MediaFormat.intOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    private companion object {
        const val VIDEO_PREFIX = "video/"
        const val AUDIO_PREFIX = "audio/"
        const val MICROS_PER_MILLI = 1_000L
        const val DEFAULT_SAMPLE_BUFFER_SIZE = 1024 * 1024
        const val PROGRESS_STEP = 0.01f
        const val EVENT_REMUX_FAILED = "PROCESSING_REMUX_FAILED"
    }
}
