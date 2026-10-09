package seeyuer.yingli.player.data.processing.transcode

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
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
import seeyuer.yingli.player.domain.transcode.DefaultTranscodePlanner
import seeyuer.yingli.player.domain.transcode.HdrFormat
import seeyuer.yingli.player.domain.transcode.SourceMediaInfo
import seeyuer.yingli.player.domain.transcode.TranscodeChangeCode
import seeyuer.yingli.player.domain.transcode.TranscodeEngineResult
import seeyuer.yingli.player.domain.transcode.TranscodePlanningResult
import seeyuer.yingli.player.domain.transcode.TranscodePresets
import java.io.File
import java.util.Random

/**
 * 阶段 1 步骤 3（G2）的真机测量。
 *
 * ## 要证的两件事
 *
 * 1. **`supportsHdr` 不再是硬编码**。修复前 `AndroidMediaCapabilityProbe` 把每个编码器的
 *    `supportsHdr` 写成常量 `false`（§20.1.4 记录该硬编码与设备实际能力矛盾：本机有
 *    HEVC Main10、`COLOR_FormatYUVP010` 与专用 HDR 编码器 `c2.qti.hevc.encoder.hdr`）。
 * 2. **HDR 源不再必然不可转码**。修复前 `Media3TranscodeEngine` 一看到计划里含
 *    `HDR_TO_SDR` 就直接返回 `Failed("HDR_TONE_MAPPING_UNAVAILABLE")`，而
 *    `AndroidMediaCapabilityProbe` 的硬编码又保证**每个** HDR 源都会产生 `HDR_TO_SDR` ——
 *    两者叠加使 HDR 视频在当前实现下完全无法转码。
 *
 * ## 为什么必须自己合成 HDR 源
 *
 * 本机媒体库里没有任何 HDR 视频（`content://media/external/video/media` 的 `color_transfer`
 * 只有 3 / 1 / NULL，没有 6 = ST2084、7 = HLG）。所以本测试用 MediaCodec + MediaMuxer
 * 现场编一个**容器层声明 HDR10** 的源，再用生产探测器读回来验证声明生效 —— 这是自校验的：
 * 如果封装没有写出色彩标记，第一个断言就会失败，不会让后面的结论悬空。
 *
 * ## 断言策略
 *
 * 只断言「本机必然成立且失败时能定位原因」的事实。凡是设备相关的能力事实，失败消息里
 * 必须带出原始清单（沿用 `Stage1CodecRefusalMeasurementTest` 的做法：那一次的失败消息
 * 直接把设备真实编码器列表带了出来，正是它纠正了「移动端不会有 AV1/VP9 编码器」的错误假设）。
 */
@RunWith(AndroidJUnit4::class)
class Stage1HdrToneMappingMeasurementTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dispatchers = DefaultAppDispatchers
    private val clock = SystemAppClock

    private val workDir = File(context.cacheDir, "stage1-hdr").apply {
        deleteRecursively()
        mkdirs()
    }

    @Test
    fun probeDerivesHdrSupportFromCodecCapabilities() = runBlocking {
        val capabilities = AndroidMediaCapabilityProbe(context, dispatchers, clock).deviceCapabilities()
        val declaredHdrEditing = hdrEditingFeatureEncoders()

        // 必要条件（反向断言）：任何被标记为支持 HDR 的编码器，其 mimeType 必须确实有编码器
        // 声明了 `hdr-editing` 特性。它挡不住硬编码 false（那由下面的设备事实断言挡），
        // 但能挡住硬编码 true 或任何「凭空宣称」。
        val unsupportedClaims = capabilities.encoders.filter { it.supportsHdr && it.mimeType !in declaredHdrEditing }

        val report = buildString {
            appendLine("{")
            appendLine("  \"capturedAtEpochMillis\": ${clock.now().toEpochMilli()},")
            appendLine("  \"encoders\": [")
            appendLine(
                capabilities.encoders.joinToString(",\n") { encoder ->
                    "    { \"mimeType\": \"${encoder.mimeType}\", \"maxWidth\": ${encoder.maxWidth}, " +
                        "\"maxHeight\": ${encoder.maxHeight}, \"hardwareAccelerated\": ${encoder.hardwareAccelerated}, " +
                        "\"supportsHdr\": ${encoder.supportsHdr} }"
                },
            )
            appendLine("  ],")
            appendLine("  \"hdrEditingFeatureMimeTypes\": ${declaredHdrEditing.sorted().joinToString(",", "[", "]") { "\"$it\"" }},")
            appendLine("  \"hlgEditingFeatureMimeTypes\": ${hlgEditingFeatureEncoders().sorted().joinToString(",", "[", "]") { "\"$it\"" }},")
            appendLine("  \"hdrCapableEncoderCount\": ${capabilities.encoders.count { it.supportsHdr }}")
            appendLine("}")
        }
        Stage0EvidenceRecorder.record(context, "stage1-hdr-capability.json", report)

        assertEquals(
            "有编码器被标记为支持 HDR，但对应 mime 没有任何编码器声明 hdr-editing 特性：$unsupportedClaims",
            emptyList<Any>(),
            unsupportedClaims,
        )

        assertTrue(
            "本机没有任何编码器被判定为支持 HDR 编辑。若本机确实缺少 HDR 编码器，这条断言应当改写；" +
                "但 §20.1.4 已记录本机存在 HEVC Main10 + COLOR_FormatYUVP010 + c2.qti.hevc.encoder.hdr，" +
                "因此这里失败说明 supportsHdr 仍然是错的。声明 hdr-editing 的 mime：$declaredHdrEditing",
            capabilities.encoders.any { it.supportsHdr },
        )
    }

    @Test
    fun hdrSourceIsToneMappedInsteadOfRefused() = runBlocking {
        val sourceFile = File(workDir, "hdr10_avc_1920x1080.mp4")
        encodeNoiseSource(sourceFile)

        val probe = AndroidMediaCapabilityProbe(context, dispatchers, clock)
        val capabilities = probe.deviceCapabilities()
        val source = probe.source(
            MediaItemId("stage1-hdr"),
            "file://${sourceFile.absolutePath}",
            sourceFile.name,
        )

        // 预设目标是 AVC，本机的 AVC 编码器不参与 HDR 编辑 ⇒ 源被识别为 HDR 时必然产生 HDR_TO_SDR。
        val plan = if (source == null) {
            null
        } else {
            DefaultTranscodePlanner.plan(
                source = source,
                capabilities = capabilities,
                preset = TranscodePresets.Compatible,
                availableBytes = workDir.usableSpace,
            )
        }
        val ready = plan as? TranscodePlanningResult.Ready

        val outFile = File(workDir, "out_hdr.mp4")
        if (outFile.exists()) outFile.delete()

        val engine = Media3TranscodeEngine(context, dispatchers)
        val result = ready?.let { engine.transcode(it.plan, outFile.absolutePath) { } }

        val verification = if (result is TranscodeEngineResult.Completed) {
            MediaExtractorOutputVerifier(dispatchers).verify(outFile.absolutePath, ready.plan)
        } else {
            null
        }

        // 先落证据再断言：这样任何一条失败路径都留下现场（阶段 0 的教训：失败时不写证据就无法定位）。
        val report = buildString {
            appendLine("{")
            appendLine("  \"capturedAtEpochMillis\": ${clock.now().toEpochMilli()},")
            appendLine("  \"sourceBytes\": ${if (sourceFile.isFile) sourceFile.length() else 0},")
            appendLine("  \"sourceFormatKeys\": ${withFirstVideoFormat(sourceFile) { it.toString() }?.let { "\"${escape(it)}\"" } ?: "null"},")
            appendLine("  \"sourceColorTransfer\": ${readInt(sourceFile, MediaFormat.KEY_COLOR_TRANSFER)},")
            appendLine("  \"sourceColorStandard\": ${readInt(sourceFile, MediaFormat.KEY_COLOR_STANDARD)},")
            appendLine("  \"sourceHdrFormat\": ${source?.hdrFormat?.let { "\"$it\"" } ?: "null"},")
            appendLine("  \"plannerResult\": ${plan?.let { "\"${it::class.simpleName}\"" } ?: "null"},")
            appendLine("  \"changes\": ${(ready?.plan?.changes?.joinToString(",", "[", "]") { "\"${it.code}\"" }) ?: "null"},")
            appendLine("  \"engineResult\": ${result?.let { "\"${it::class.simpleName}${if (it is TranscodeEngineResult.Failed) "(${it.errorCode})" else ""}\"" } ?: "null"},")
            appendLine("  \"outBytes\": ${if (outFile.isFile) outFile.length() else 0},")
            appendLine("  \"verifierValid\": ${verification?.valid ?: "null"},")
            appendLine("  \"verifierErrors\": ${verification?.errorCodes?.joinToString(",", "[", "]") { "\"$it\"" } ?: "[]"}")
            appendLine("}")
        }
        Stage0EvidenceRecorder.record(context, "stage1-hdr-tonemap.json", report)

        assertEquals(
            "合成源没有写出 HDR10 的色彩标记，后续结论不成立",
            MediaFormat.COLOR_TRANSFER_ST2084,
            readInt(sourceFile, MediaFormat.KEY_COLOR_TRANSFER),
        )
        assertTrue("探测器读不出刚生成的 HDR 源", source != null)
        assertEquals("探测器没有把源识别为 HDR10", HdrFormat.HDR10, source!!.hdrFormat)
        assertTrue("计划被拒绝：$plan", ready != null)
        assertTrue(
            "HDR 源在没有 HDR 能力的 AVC 目标下必须产生 HDR_TO_SDR 变更",
            ready!!.plan.changes.any { it.code == TranscodeChangeCode.HDR_TO_SDR },
        )
        assertTrue("HDR_TO_SDR 必须要求用户确认", ready.plan.requiresConfirmation)
        assertTrue(
            "引擎拒绝了 HDR 转码（G2 回归）：$result",
            result is TranscodeEngineResult.Completed,
        )
        assertTrue(
            "HDR 转码产物未通过独立验证：${verification?.errorCodes}",
            verification != null && verification.valid,
        )
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    // ────────────────────────────────────────────────────────────────────────
    // 独立的能力对照：不经过本项目探测逻辑，直接查 MediaCodecInfo 的特性声明
    // ────────────────────────────────────────────────────────────────────────

    /** 声明了 `FEATURE_HdrEditing` 的编码器所支持的视频 mime。**不经过本项目的探测逻辑。** */
    private fun hdrEditingFeatureEncoders(): Set<String> =
        featureEncoders(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing)

    private fun hlgEditingFeatureEncoders(): Set<String> =
        featureEncoders(MediaCodecInfo.CodecCapabilities.FEATURE_HlgEditing)

    private fun featureEncoders(feature: String): Set<String> {
        val result = mutableSetOf<String>()
        for (info in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
            if (!info.isEncoder) continue
            for (type in info.supportedTypes) {
                if (!type.startsWith("video/")) continue
                val capabilities = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: continue
                if (runCatching { capabilities.isFeatureSupported(feature) }.getOrDefault(false)) {
                    result += type
                }
            }
        }
        return result
    }

    // ────────────────────────────────────────────────────────────────────────
    // HDR10 源生成：HEVC Main10 + BT.2020 + ST2084 标记 + MediaMuxer 封装
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 现场合成一个**容器层声明 HDR10** 的源。
     *
     * 编码用 AVC 而不是 HEVC：本机 HEVC Main10 编码器的 `outputFormat` 交给 `MediaMuxer` 后
     * 封装出来的文件连视频轨都解析不出来（实测 `sourceFormatKeys = null`），而 AVC 这条路径
     * 已被 [Stage1BitrateWiringMeasurementTest] 证明可被本项目探测器正常读回。
     * 换代码并不削弱本测试要证的东西：tone mapping 的判据来自**容器声明的色彩信息**
     * （`KEY_COLOR_STANDARD` / `KEY_COLOR_TRANSFER`），而不是像素的实际位深。
     */
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
            // 这三个键就是「容器声明 HDR10」的全部内容，也是探测器回读的依据。
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }

        val noise = ByteArray(width * (height + 64))
        Random(20261010L).nextBytes(noise)
        val chromaRow = ByteArray(width / 2) { 128.toByte() }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()
        val totalFrames = seconds * fps
        val ptsStepUs = 1_000_000L / fps

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
                            fillNoiseFrame(image, noise, ((frame * 37) % 64) * width, chromaRow)
                            val size = codec.getInputBuffer(inIndex)?.capacity() ?: (width * height * 3 / 2)
                            codec.queueInputBuffer(inIndex, 0, size, frame.toLong() * ptsStepUs, 0)
                            frame++
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 用编码器回报的 outputFormat 建轨（而不是我们传进去的 configure format）：
                    // 它是编码器对自己输出的完整描述，含 csd；色彩标记也由编码器带回来。
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outIndex >= 0) {
                    val endOfStream = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bufferInfo.size = 0
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

        assertTrue("HDR 源生成失败，文件不存在或为空", out.isFile && out.length() > 0)
    }

    private fun fillNoiseFrame(image: Image, noise: ByteArray, base: Int, chromaRow: ByteArray) {
        val maxBase = noise.size - image.width * image.height
        val safeBase = base.coerceIn(0, maxBase)
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        for (r in 0 until image.height) {
            yBuffer.position(r * yPlane.rowStride)
            yBuffer.put(noise, safeBase + r * image.width, image.width)
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

    private fun readIntOrNull(file: File, key: String): Int? = withFirstVideoFormat(file) {
        if (it.containsKey(key)) runCatching { it.getInteger(key) }.getOrNull() else null
    }

    private fun <T> withFirstVideoFormat(file: File, block: (MediaFormat) -> T): T? {
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
