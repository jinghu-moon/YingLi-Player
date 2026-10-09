package seeyuer.yingli.player.data.processing.transcode

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.Stage0EvidenceRecorder
import seeyuer.yingli.player.core.common.DefaultAppDispatchers
import seeyuer.yingli.player.core.common.SystemAppClock
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.processing.DefaultProcessingPlanner
import seeyuer.yingli.player.domain.processing.DeviceMediaCapabilities
import seeyuer.yingli.player.domain.processing.SourceMediaInfo
import seeyuer.yingli.player.domain.processing.ProcessingEngineResult
import seeyuer.yingli.player.domain.processing.ProcessingPlanningResult
import seeyuer.yingli.player.domain.processing.OutputTarget
import seeyuer.yingli.player.domain.processing.OutputTargets
import java.io.File
import java.util.Random

/**
 * 阶段 1 步骤 1（G1）的**改前 / 改后**真机测量。
 *
 * ## 为什么这条证据必须落在真机上
 *
 * G1 的缺陷是「`Media3ProcessingEngine` 没有把预设码率交给编码器」。要在 JVM 单测里发现它，
 * 必须有办法读到 `Transformer.Builder` 里的 `EncoderFactory` —— 而 Media3 不提供这样的 getter。
 *
 * 唯一能观测到的信号是**输出本身**：如果码率真的下发了，三档预设（8 / 5 / 2.5 Mbps）在同一源上
 * 必须产出**分离的字节数**；如果没下发，`DefaultEncoderFactory.getSuggestedBitrate()` 会按设备能力
 * 推导出同一个值，三档预设的字节数**完全相同** —— 阶段 0 的 §20.1.2 已经观测到这一点
 * （`compatible_mp4` 与 `balanced_mp4` 都是 473 598 字节）。
 *
 * ## 源的选择：必须高熵，否则测不出来
 *
 * 阶段 0 的竖屏源是「竖直条纹 + 移动亮带」，画面几乎平坦，编码器受**质量**而非**码率**约束，
 * 三档预设的字节数差异被淹没。本测量改用**逐帧平移的随机噪声**：相邻帧完全不相关，
 * 每个宏块都无预测可依，编码器被迫按请求的码率花比特 —— 此时码率才成为约束。
 * 平移量取 `(frame * 37) % 64` 行，避免静态画面被 P 帧跳过而让比特数崩塌。
 *
 * ## 怎么用它做改前 / 改后
 *
 * 同一份代码、同一个源，跑两次：
 * - **改前**：临时注释掉 `Media3ProcessingEngine` 里的 `.setEncoderFactory(...)` 一行；
 * - **改后**：保留该行。
 *
 * 两次的 JSON 都归档进 `docs/architecture/evidence/stage1/`。**本类刻意不断言「字节数必须分离」**：
 * 那样在没有实测数据时就是「用期望强暴事实」，且会把设备差异误判成回归。断言只覆盖测量有效性。
 */
@RunWith(AndroidJUnit4::class)
class Stage1BitrateWiringMeasurementTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dispatchers = DefaultAppDispatchers
    private val clock = SystemAppClock

    private val workDir = File(context.cacheDir, "stage1-bitrate").apply {
        deleteRecursively()
        mkdirs()
    }

    @Test
    fun outputSizePerPresetOnHighEntropySource() = runBlocking {
        val sourceFile = File(workDir, "noise_1920x1080_avc.mp4")
        encodeNoiseSource(sourceFile)

        val probe = AndroidMediaCapabilityProbe(context, dispatchers, clock)
        val capabilities = probe.deviceCapabilities()
        val source = probe.source(
            MediaItemId("stage1-noise"),
            "file://${sourceFile.absolutePath}",
            sourceFile.name,
        )
        assertTrue("能力探测器读不出刚生成的高熵源", source != null)
        assertEquals("源宽不符合预期", 1920, readInt(sourceFile, MediaFormat.KEY_WIDTH))
        assertEquals("源高不符合预期", 1080, readInt(sourceFile, MediaFormat.KEY_HEIGHT))

        // 记录这次运行用的是哪一份引擎（改前 / 改后由调用方通过 -e phaseLabel 标注），
        // 避免重演阶段 0 那次「三份证据落到同一个文件名、只剩最后一份」的事故。
        val phaseLabel = InstrumentationRegistry.getArguments().getString("phaseLabel") ?: "unlabelled"

        val rows = mutableListOf<String>()
        for (preset in OutputTargets.all) {
            rows += measurePreset(preset, sourceFile, source!!, capabilities)
        }

        val report = buildString {
            appendLine("{")
            appendLine("  \"phaseLabel\": \"$phaseLabel\",")
            appendLine("  \"capturedAtEpochMillis\": ${clock.now().toEpochMilli()},")
            appendLine("  \"sourceBytes\": ${sourceFile.length()},")
            appendLine("  \"sourceBitrate\": ${preset0SourceBitrate(sourceFile)},")
            appendLine("  \"presets\": [")
            appendLine(rows.joinToString(",\n"))
            appendLine("  ]")
            appendLine("}")
        }
        Stage0EvidenceRecorder.record(context, "stage1-bitrate-$phaseLabel.json", report)

        assertTrue("三档预设的测量行数不对", rows.size == OutputTargets.all.size)
    }

    // ────────────────────────────────────────────────────────────────────────

    private suspend fun measurePreset(
        preset: OutputTarget,
        sourceFile: File,
        source: SourceMediaInfo,
        capabilities: DeviceMediaCapabilities,
    ): String {
        val outFile = File(workDir, "out_${preset.id.value}.mp4")
        if (outFile.exists()) outFile.delete()

        val plan = DefaultProcessingPlanner.plan(
            source = source,
            capabilities = capabilities,
            target = preset,
            availableBytes = workDir.usableSpace,
        )
        if (plan is ProcessingPlanningResult.Rejected) {
            return "    { \"preset\": \"${preset.id.value}\", \"plannerRejected\": \"${plan.code}\" }"
        }
        val ready = plan as ProcessingPlanningResult.Ready
        // 三档预设的长边上限分别是 1920 / 1920 / 1280，源是 1920x1080，
        // 所以期望目标宽度就是 `min(1920, preset.maximumLongEdge)`（编码器上限远大于此）。
        assertEquals(
            "planner 的目标宽度不等于预设长边上限，码率对比的前提被破坏",
            minOf(1920, requireNotNull(preset.maximumLongEdge)),
            ready.plan.targetWidth,
        )

        val engine = Media3ProcessingEngine(context, dispatchers)
        val result = engine.process(ready.plan, outFile.absolutePath) { }
        if (result !is ProcessingEngineResult.Completed) {
            return "    { \"preset\": \"${preset.id.value}\", " +
                "\"engineResult\": \"${result::class.simpleName}" +
                "${if (result is ProcessingEngineResult.Failed) "(${result.errorCode})" else ""}\" }"
        }

        val verification = MediaExtractorOutputVerifier(dispatchers).verify(outFile.absolutePath, ready.plan)
        val bytes = outFile.length()
        val durationMillis = verification.durationMillis

        return buildString {
            append("    { ")
            append("\"preset\": \"${preset.id.value}\", ")
            append("\"requestedVideoBitrate\": ${preset.videoBitrate}, ")
            append("\"outBytes\": $bytes, ")
            append("\"outDurationMillis\": ${durationMillis ?: "null"}, ")
            append("\"outAverageBitrate\": ${verification.averageBitrateBitsPerSecond ?: "null"}, ")
            append("\"outFormatBitrate\": ${readIntOrNull(outFile, MediaFormat.KEY_BIT_RATE) ?: "null"}, ")
            append("\"outWidth\": ${verification.width ?: "null"}, ")
            append("\"outHeight\": ${verification.height ?: "null"}, ")
            append("\"verifierValid\": ${verification.valid}, ")
            append("\"verifierErrors\": ${verification.errorCodes.joinToString(",", "[", "]") { "\"$it\"" }} ")
            append("}")
        }
    }

    /** 源文件自身轨道的平均码率，用来证明源确实「喂满」了请求码率。 */
    private fun preset0SourceBitrate(sourceFile: File): Long {
        val duration = readLong(sourceFile, MediaFormat.KEY_DURATION)
        return if (duration > 0) sourceFile.length() * 8 * 1_000_000 / duration else 0
    }

    // ────────────────────────────────────────────────────────────────────────
    // 高熵源生成：逐帧平移的随机噪声 + MediaCodec 编码 + MediaMuxer 封装
    // ────────────────────────────────────────────────────────────────────────

    private fun encodeNoiseSource(out: File, seconds: Int = 3, fps: Int = 30) {
        val width = 1920
        val height = 1080
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 20_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        // 固定种子的随机噪声：可复现，且帧间不相关。
        val noise = ByteArray(width * (height + 64))
        Random(20261009L).nextBytes(noise)
        val maxBase = noise.size - width * height

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()
        val totalFrames = seconds * fps
        val ptsStepUs = 1_000_000L / fps
        val chromaRow = ByteArray(width / 2) { 128.toByte() }

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            var frame = 0
            var inputDone = false
            var guard = 0
            while (guard++ < 400_000) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        if (frame >= totalFrames) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, frame.toLong() * ptsStepUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val image = codec.getInputImage(inIndex)
                                ?: error("编码器未接受 COLOR_FormatYUV420Flexible，getInputImage 返回 null")
                            // 逐帧平移 37 行（mod 64）：相邻帧的每行都来自噪声的不同位置，
                            // 于是 P 帧没有可跳过的静态块，编码器被迫按请求码率花比特。
                            val base = ((frame * 37) % 64) * width
                            fillNoiseFrame(image, noise, base.coerceAtMost(maxBase), chromaRow)
                            val size = codec.getInputBuffer(inIndex)?.capacity() ?: (width * height * 3 / 2)
                            codec.queueInputBuffer(inIndex, 0, size, frame.toLong() * ptsStepUs, 0)
                            frame++
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outIndex >= 0) {
                    val endOfStream = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val codecConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (codecConfig) bufferInfo.size = 0
                    if (bufferInfo.size > 0 && muxerStarted && trackIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outIndex)!!
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (endOfStream) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }

        assertTrue("高熵源生成失败，文件不存在或为空", out.isFile && out.length() > 0)
    }

    /** 按行批量写 Y/U/V 平面（`ByteBuffer.put(byte[],int,int)`），避开逐像素绝对索引的性能陷阱。 */
    private fun fillNoiseFrame(image: Image, noise: ByteArray, base: Int, chromaRow: ByteArray) {
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        for (r in 0 until image.height) {
            yBuffer.position(r * yPlane.rowStride)
            yBuffer.put(noise, base + r * image.width, image.width)
        }

        for (p in 1..2) {
            val plane = image.planes[p]
            val buffer = plane.buffer
            for (r in 0 until image.height / 2) {
                buffer.position(r * plane.rowStride)
                buffer.put(chromaRow)
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────

    private fun readInt(file: File, key: String): Int = readIntOrNull(file, key) ?: -1

    private fun readIntOrNull(file: File, key: String): Int? =
        withFirstVideoFormat(file) { if (it.containsKey(key)) runCatching { it.getInteger(key) }.getOrNull() else null }

    private fun readLong(file: File, key: String): Long =
        withFirstVideoFormat(file) { if (it.containsKey(key)) runCatching { it.getLong(key) }.getOrNull() else null } ?: 0L

    private inline fun <T> withFirstVideoFormat(file: File, block: (MediaFormat) -> T): T? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var result: T? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    result = block(format)
                    break
                }
            }
            result
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
