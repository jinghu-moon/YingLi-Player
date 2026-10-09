package seeyuer.yingli.player.data.processing.transcode

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
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
import seeyuer.yingli.player.domain.transcode.DefaultTranscodePlanner
import seeyuer.yingli.player.domain.transcode.EncoderCapability
import seeyuer.yingli.player.domain.transcode.TranscodeEngineResult
import seeyuer.yingli.player.domain.transcode.TranscodePlanningResult
import seeyuer.yingli.player.domain.transcode.TranscodePreset
import seeyuer.yingli.player.domain.transcode.TranscodePresets
import java.io.File

/**
 * 阶段 0 真机实测（G7）。
 *
 * 本类**不是**行为契约测试，而是取证工具：它把「设备实际报告了什么」写成一份 JSON 快照，
 * 供 `docs/architecture/Organizing-Page-Function-Design.md` §20 记录。
 *
 * 为什么必须是真机：G7 的问题是「`Transformer` 对竖屏源到底产出横向还是纵向的编码尺寸」，
 * 这取决于设备上的 `MediaCodec` 与 Media3 的运行时行为，**JVM 单测与文档都无法回答**
 * （设计文档 §15.3 已明确「在设备测试恢复前，以下矩阵属于未验证」）。
 *
 * 断言只覆盖「测量本身有效」这一层（拿到了编码器、生成了可解码的竖屏源、跑通了三档预设），
 * **不对 G7 的结论下断言** —— 结论是要被记录的事实，不是要被强制的期望。在没有实测数据之前
 * 改写 planner 或 verifier 都是设计文档明令禁止的臆测性修改。
 */
@RunWith(AndroidJUnit4::class)
class TranscodeDeviceCapabilityMeasurementTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dispatchers = DefaultAppDispatchers
    private val clock = SystemAppClock

    private val workDir = File(context.cacheDir, "stage0-measurement").apply {
        deleteRecursively()
        mkdirs()
    }

    // ────────────────────────────────────────────────────────────────────────
    // 1. 设备编码能力快照
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun deviceEncoderCapabilitySnapshot() {
        val lines = mutableListOf<String>()
        lines += "{"
        lines += "  \"capturedAtEpochMillis\": ${clock.now().toEpochMilli()},"
        lines += "  \"sdkInt\": ${android.os.Build.VERSION.SDK_INT},"
        lines += "  \"device\": \"${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\","
        lines += "  \"soc\": \"${android.os.Build.SOC_MODEL}\","
        lines += "  \"videoEncoders\": ["

        val encoders = collectVideoEncoders()
        encoders.forEachIndexed { index, entry ->
            val comma = if (index == encoders.lastIndex) "" else ","
            lines += "    $entry$comma"
        }
        lines += "  ]"
        lines += "}"

        val json = lines.joinToString("\n")
        writeSnapshot("stage0-encoder-capability.json", json)

        // 测量本身有效：这台设备至少报告了一个 AVC 编码器，否则整个压缩功能不成立。
        assertTrue(
            "设备未报告任何 video/avc 编码器，压缩功能的前提不成立",
            encoders.any { it.contains("\"mimeType\": \"video/avc\"") },
        )
    }

    /**
     * 每个视频编码器的：厂家名、`BitrateMode` 可用取值、码率区间、HDR 证据。
     *
     * HDR 取两个互相独立的信号：`colorFormats` 是否含 10-bit（`COLOR_FormatYUVP010`），
     * 以及 `isFormatSupported` 对 HEVC Main10 + ST2084 的说法。两者都记录，
     * 因为 G2 的 `supportsHdr` 目前在生产代码里是硬编码 `false`，需要一个可对照的实测值。
     */
    private fun collectVideoEncoders(): List<String> {
        val out = mutableListOf<String>()
        for (info in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
            if (!info.isEncoder) continue
            for (mime in info.supportedTypes) {
                if (!mime.startsWith("video/")) continue
                val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
                val video = caps.videoCapabilities ?: continue
                val enc = caps.encoderCapabilities ?: continue
                val modes = BITRATE_MODES.filter { mode ->
                    runCatching { enc.isBitrateModeSupported(mode.value) }.getOrDefault(false)
                }.map { it.name }
                val tenBit = caps.colorFormats.any { it == COLOR_FORMAT_YUV_P010 }
                val hevcMain10Profile = caps.profileLevels.any {
                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                }

                out += buildString {
                    append("{ ")
                    append("\"name\": \"${info.name}\", ")
                    append("\"mimeType\": \"$mime\", ")
                    append("\"hardwareAccelerated\": ${info.isHardwareAccelerated}, ")
                    append("\"vendor\": ${info.isVendor}, ")
                    append("\"bitrateModes\": ${modes.joinToString(",", "[", "]") { "\"$it\"" }}, ")
                    append("\"supportedWidths\": \"${video.supportedWidths}\", ")
                    append("\"supportedHeights\": \"${video.supportedHeights}\", ")
                    append("\"widthAlignment\": ${video.widthAlignment}, ")
                    append("\"heightAlignment\": ${video.heightAlignment}, ")
                    append("\"portrait1920x1080Supported\": ${video.isSizeSupported(1080, 1920)}, ")
                    append("\"landscape1920x1080Supported\": ${video.isSizeSupported(1920, 1080)}, ")
                    append("\"colorFormats\": ${caps.colorFormats.joinToString(",", "[", "]")}, ")
                    append("\"has10BitColorFormat\": $tenBit, ")
                    append("\"hevcMain10ProfileSupported\": $hevcMain10Profile ")
                    append("}")
                }
            }
        }
        return out
    }

    private data class BitrateMode(val name: String, val value: Int)

    private companion object {
        val BITRATE_MODES = listOf(
            BitrateMode("CQ", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ),
            BitrateMode("VBR", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR),
            BitrateMode("CBR", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR),
        )
        const val COLOR_FORMAT_YUV_P010 = 0x36 // MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2. G7：竖屏源 × 三档预设
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun portraitSourceDimensionsAcrossAllPresets() = runBlocking {
        val sourceFile = File(workDir, "portrait_1080x1920_avc.mp4")
        encodePortraitSource(sourceFile)

        val sourceFacts = readTrackFacts(sourceFile)
        assertEquals(
            "生成的竖屏源尺寸不对，G7 测量前提不成立",
            1080,
            sourceFacts.width,
        )
        assertEquals(1920, sourceFacts.height)

        val probe = AndroidMediaCapabilityProbe(context, dispatchers, clock)
        val capabilities = probe.deviceCapabilities()
        val source = probe.source(
            MediaItemId("stage0-portrait"),
            "file://${sourceFile.absolutePath}",
            sourceFile.name,
        )
        assertTrue("能力探测器读不出刚生成的竖屏源", source != null)

        val report = StringBuilder()
        report.appendLine("{")
        report.appendLine("  \"source\": ${sourceFacts.toJson()},")
        report.appendLine("  \"deviceCapabilities\": ${capabilities.toJson()},")
        report.appendLine("  \"presets\": [")

        val rows = mutableListOf<String>()
        for (preset in TranscodePresets.all) {
            rows += measurePreset(preset, sourceFile, source!!, capabilities)
        }
        report.appendLine(rows.joinToString(",\n"))
        report.appendLine("  ]")
        report.appendLine("}")
        writeSnapshot("stage0-g7-portrait.json", report.toString())

        // 测量本身有效：三档预设都跑出了结果（成功或带结构化错误码的失败），
        // 而不是抛异常把整轮测量炸掉。
        assertTrue("三档预设的测量行数不对", rows.size == TranscodePresets.all.size)
    }

    private suspend fun measurePreset(
        preset: TranscodePreset,
        sourceFile: File,
        source: seeyuer.yingli.player.domain.transcode.SourceMediaInfo,
        capabilities: seeyuer.yingli.player.domain.transcode.DeviceMediaCapabilities,
    ): String {
        val outFile = File(workDir, "out_${preset.id.value}.mp4")
        val plan = DefaultTranscodePlanner.plan(
            source = source,
            capabilities = capabilities,
            preset = preset,
            availableBytes = workDir.usableSpace,
        )
        val plannerTarget = when (plan) {
            is TranscodePlanningResult.Ready -> "\"${plan.plan.targetWidth}x${plan.plan.targetHeight}\""
            is TranscodePlanningResult.Rejected -> null
        }
        if (plan is TranscodePlanningResult.Rejected) {
            return """
    { "preset": "${preset.id.value}", "plannerRejected": "${plan.code}" }""".trimIndent()
        }
        val ready = plan as TranscodePlanningResult.Ready

        val engine = Media3TranscodeEngine(context, dispatchers)
        val result = engine.transcode(ready.plan, outFile.absolutePath) { }
        val verification = if (result is TranscodeEngineResult.Completed) {
            MediaExtractorOutputVerifier(dispatchers).verify(outFile.absolutePath, ready.plan)
        } else {
            null
        }
        val outFacts = if (result is TranscodeEngineResult.Completed) readTrackFacts(outFile) else null

        return buildString {
            append("    { ")
            append("\"preset\": \"${preset.id.value}\", ")
            append("\"requestedVideoBitrate\": ${preset.targetVideoBitrate}, ")
            append("\"maximumLongEdge\": ${preset.maximumLongEdge}, ")
            append("\"plannerTarget\": $plannerTarget, ")
            append("\"engineResult\": \"${result::class.simpleName}${if (result is TranscodeEngineResult.Failed) "(${result.errorCode})" else ""}\", ")
            append("\"outputFacts\": ${outFacts?.toJson() ?: "null"}, ")
            append("\"verifier\": ${verification?.toJson() ?: "null"}, ")
            append("\"outputBytes\": ${if (outFile.isFile) outFile.length() else 0}, ")
            append("\"sourceBytes\": ${sourceFile.length()} ")
            append("}")
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 轨道事实读取：MediaExtractor 与 MediaMetadataRetriever 双份，
    // 因为两者对「旋转后尺寸」的口径不同，差异本身就是 G7 的证据。
    // ────────────────────────────────────────────────────────────────────────

    private data class TrackFacts(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val durationMillis: Long,
        val mimeType: String?,
        val bitrate: Int?,
        val metadataRetrieverWidth: Int?,
        val metadataRetrieverHeight: Int?,
        val metadataRetrieverRotation: Int?,
    ) {
        fun toJson(): String = buildString {
            append("{ ")
            append("\"keyWidth\": $width, ")
            append("\"keyHeight\": $height, ")
            append("\"keyRotation\": $rotation, ")
            append("\"durationMillis\": $durationMillis, ")
            append("\"mimeType\": ${mimeType?.let { "\"$it\"" } ?: "null"}, ")
            append("\"keyBitRate\": ${bitrate ?: "null"}, ")
            append("\"mmrWidth\": ${metadataRetrieverWidth ?: "null"}, ")
            append("\"mmrHeight\": ${metadataRetrieverHeight ?: "null"}, ")
            append("\"mmrRotation\": ${metadataRetrieverRotation ?: "null"} ")
            append("}")
        }
    }

    private fun readTrackFacts(file: File): TrackFacts {
        val extractor = MediaExtractor()
        var width = 0
        var height = 0
        var rotation = 0
        var duration = 0L
        var mime: String? = null
        var bitrate: Int? = null
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val trackMime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!trackMime.startsWith("video/")) continue
                width = format.getIntOrNull(MediaFormat.KEY_WIDTH) ?: 0
                height = format.getIntOrNull(MediaFormat.KEY_HEIGHT) ?: 0
                rotation = format.getIntOrNull(MediaFormat.KEY_ROTATION) ?: 0
                duration = (format.getLongOrNull(MediaFormat.KEY_DURATION) ?: 0L) / 1000L
                mime = trackMime
                bitrate = format.getIntOrNull(MediaFormat.KEY_BIT_RATE)
                break
            }
        } finally {
            runCatching { extractor.release() }
        }

        val retriever = MediaMetadataRetriever()
        var mmrWidth: Int? = null
        var mmrHeight: Int? = null
        var mmrRotation: Int? = null
        try {
            retriever.setDataSource(file.absolutePath)
            mmrWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            mmrHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            mmrRotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        } catch (_: Throwable) {
            // MMR 失败不影响 extractor 侧的证据
        } finally {
            runCatching { retriever.release() }
        }

        return TrackFacts(
            width = width,
            height = height,
            rotation = rotation,
            durationMillis = duration,
            mimeType = mime,
            bitrate = bitrate,
            metadataRetrieverWidth = mmrWidth,
            metadataRetrieverHeight = mmrHeight,
            metadataRetrieverRotation = mmrRotation,
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // 竖屏源生成：MediaCodec 编码 + MediaMuxer 封装
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 生成一段真正可解码的 1080×1920 竖屏 H.264 视频（无音轨）。
     *
     * 画面内容对本测量无关紧要（G7 只问尺寸语义），所以只填「竖直条纹 + 每帧移动的亮带」，
     * 既保证编码器有真实运动可编码，又完全避开逐像素循环的性能陷阱
     * （2M 像素 × 90 帧用绝对索引写会慢到分钟级；按行批量 `put` 是毫秒级）。
     */
    private fun encodePortraitSource(out: File, seconds: Int = 3, fps: Int = 30) {
        val width = 1080
        val height = 1920
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()
        val totalFrames = seconds * fps
        val ptsStepUs = 1_000_000L / fps

        val yRow = ByteArray(width) { i -> (((i / 16) % 2) * 200 + 16).toByte() }
        val chromaRow = ByteArray(width / 2) { 128.toByte() }

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            var frame = 0
            var inputDone = false
            var guard = 0
            while (guard++ < 200_000) {
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
                            fillFrame(image, frame, yRow, chromaRow)
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

        assertTrue("竖屏源生成失败，文件不存在或为空", out.isFile && out.length() > 0)
    }

    /** 按行批量写 Y/U/V 平面，避开逐像素绝对索引的性能陷阱。 */
    private fun fillFrame(image: Image, frame: Int, yRow: ByteArray, chromaRow: ByteArray) {
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val bandStart = (frame * 12) % yRow.size
        val row = ByteArray(yRow.size)
        System.arraycopy(yRow, 0, row, 0, yRow.size)
        for (i in 0 until 48) {
            row[(bandStart + i) % row.size] = 235.toByte()
        }
        for (r in 0 until image.height) {
            yBuffer.position(r * yPlane.rowStride)
            yBuffer.put(row)
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

    private fun writeSnapshot(name: String, json: String) =
        Stage0EvidenceRecorder.record(context, name, json)
}

private fun MediaFormat.getIntOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

private fun MediaFormat.getLongOrNull(key: String): Long? =
    if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

private fun seeyuer.yingli.player.domain.transcode.DeviceMediaCapabilities.toJson(): String =
    buildString {
        append("{ \"capturedAt\": $capturedAtEpochMillis, \"diagnostics\": ")
        append(diagnosticCodes.joinToString(",", "[", "]") { "\"$it\"" })
        append(", \"encoders\": [")
        append(encoders.joinToString(",") { it.toJson() })
        append("] }")
    }

private fun EncoderCapability.toJson(): String =
    "{ \"mime\": \"$mimeType\", \"max\": \"${maxWidth}x$maxHeight\", " +
        "\"maxFps\": $maxFrameRate, \"supportsHdr\": $supportsHdr, \"hw\": $hardwareAccelerated }"

private fun seeyuer.yingli.player.domain.transcode.OutputVerification.toJson(): String =
    "{ \"valid\": $valid, \"errorCodes\": " +
        errorCodes.joinToString(",", "[", "]") { "\"$it\"" } +
        ", \"width\": ${width ?: "null"}, \"height\": ${height ?: "null"}, " +
        "\"durationMillis\": ${durationMillis ?: "null"} }"

private const val TIMEOUT_US = 10_000L
