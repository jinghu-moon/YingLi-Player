package seeyuer.yingli.player.data.processing.transcode

import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.Stage0EvidenceRecorder
import seeyuer.yingli.player.core.common.DefaultAppDispatchers
import seeyuer.yingli.player.core.common.SystemAppClock
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.data.processing.muxer.MuxerContainer
import seeyuer.yingli.player.domain.processing.HdrFormat
import seeyuer.yingli.player.domain.processing.MediaTrackInfo
import seeyuer.yingli.player.domain.processing.MediaTrackType
import seeyuer.yingli.player.domain.processing.ProcessingOperation
import seeyuer.yingli.player.domain.processing.SourceMediaInfo
import seeyuer.yingli.player.domain.processing.ProcessingChange
import seeyuer.yingli.player.domain.processing.ProcessingEngineResult
import seeyuer.yingli.player.domain.processing.ProcessingPlan
import seeyuer.yingli.player.domain.processing.OutputTargets
import java.io.File

/**
 * 阶段 1 步骤 2（G6）的真机证据：**请求设备做不到的编码格式时，必须在编码之前被拒绝**。
 *
 * ## 这条证据要回答什么
 *
 * 需求文档 §15 的功能矩阵要求「请求 HEVC 但设备无 HEVC 编码器 → 用户收到确认，
 * 而不是静默拿到 H.264」。G6 的修法有两道防线：
 *
 * 1. **编码前的前置校验**（本测试验证）：两道门都排在编码之前 —— 先是**容器门**
 *    （`MuxerContainer.supports` ⇒ `Failed("CONTAINER_VIDEO_CODEC_UNSUPPORTED")`，
 *    阶段 2 步骤 7 引入），再是**编码器门**（`MediaCodecAvailability` 用
 *    `MediaCodecList.findEncoderForFormat` 判断这台设备能不能编出请求的格式 ⇒
 *    `Failed("VIDEO_ENCODER_UNAVAILABLE")`）。无论哪一道拒绝，都**不产生任何输出文件**，
 *    也**不浪费一次整段编码**。
 *
 *    ⚠ 本用例原先只断言 `VIDEO_ENCODER_UNAVAILABLE`，在阶段 7 回归时**失败**：
 *    它挑选的「本机没有编码器」的候选 mime（`video/quicktime`、`video/x-msvideo`、
 *    `video/x-ms-wmv`、`video/mpeg2`）本身就是容器级格式，MP4 muxer 同样不收，
 *    于是先撞上容器门。**拒绝仍发生在编码之前、仍不产生输出**（用户可见行为未变），
 *    变的只是错误码；而本机所有「MP4 容器收」的视频格式都有编码器，
 *    所以在真机上根本走不到编码器门。因此这里断言的是「落在这两条**编码前**拒绝码之一」，
 *    编码器门在真机的不可达性由 JVM 侧 planner 测试覆盖
 *    （`ProcessingPlanContractsTest` 的 `reachable targets drop the ones no encoder can produce`）。
 * 2. **运行期回退检测**（`onFallbackApplied` → `Media3FallbackMapping`）：由 JVM 单测
 *    `Media3FallbackMappingTest` 用真实的 `TransformationRequest` 覆盖。
 *
 * 两道防线的分工是刻意的：前置校验挡「确定做不到」的组合，运行期检测兜住
 * `findEncoderForFormat` 看不出来的失败（profile/level、并发实例数、configure 期异常）。
 * 因此**本测试刻意不断言「一定观察到回退」**——在有了前置校验之后，确定做不到的组合
 * 根本走不到 `onFallbackApplied`，硬断言会变成「用期望强暴事实」。
 *
 * ## 为什么不需要真实源文件
 *
 * 前置校验发生在 `Transformer` 被构建之前，只依赖计划里的目标编码格式与目标尺寸，
 * 不读任何媒体数据。所以本测试直接用构造出的 [ProcessingPlan] 调用引擎，
 * 不需要编码一份噪声源，也不依赖设备上有没有测试视频。
 *
 * 其中的「对照组」用不存在的源路径：它应当走到**后面**的失败
 * （`TRANSCODE_FAILED`），而不是被前置校验拒掉——这条断言证明前置校验是**有选择性的**，
 * 不是「一律拒绝」，否则前两条断言就没有意义了。
 */
@RunWith(AndroidJUnit4::class)
class Stage1CodecRefusalMeasurementTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dispatchers = DefaultAppDispatchers
    private val clock = SystemAppClock

    private val workDir = File(context.cacheDir, "stage1-codec-refusal").apply {
        deleteRecursively()
        mkdirs()
    }

    @Test
    fun unsupportedTargetCodecIsRefusedBeforeEncoding() = runBlocking {
        val encoders = encoderMimes()
        val missingVideo = missingMime(VIDEO_CANDIDATES, encoders)
        val missingAudio = missingMime(AUDIO_CANDIDATES, encoders)

        assertTrue(
            "这台设备能编出全部候选视频格式 $VIDEO_CANDIDATES，无法验证前置拒绝；" +
                "请把设备实际缺少的格式加进候选列表（实测编码器：$encoders）",
            missingVideo != null,
        )
        assertTrue(
            "这台设备能编出全部候选音频格式 $AUDIO_CANDIDATES，无法验证前置拒绝；" +
                "请把设备实际缺少的格式加进候选列表（实测编码器：$encoders）",
            missingAudio != null,
        )

        val videoOut = File(workDir, "refused-video.mp4")
        val videoResult = Media3ProcessingEngine(context, dispatchers)
            .process(videoPlan(missingVideo!!), videoOut.absolutePath) { }

        val audioOut = File(workDir, "refused-audio.mp4")
        val audioResult = Media3ProcessingEngine(context, dispatchers)
            .process(audioPlan(missingAudio!!), audioOut.absolutePath) { }

        // 对照组：编码格式本机支持，只是源不存在 —— 必须走到别的失败上。
        val controlOut = File(workDir, "control.mp4")
        val controlResult = Media3ProcessingEngine(context, dispatchers)
            .process(supportedControlPlan(encoders), controlOut.absolutePath) { }

        val report = buildString {
            appendLine("{")
            appendLine("  \"capturedAtEpochMillis\": ${clock.now().toEpochMilli()},")
            appendLine("  \"deviceEncoders\": ${encoders.sorted().joinToString(",", "[", "]") { "\"$it\"" }},")
            appendLine("  \"requestedMissingVideoMime\": \"$missingVideo\",")
            appendLine("  \"requestedMissingAudioMime\": \"$missingAudio\",")
            appendLine("  \"videoResult\": \"${describe(videoResult)}\",")
            appendLine("  \"audioResult\": \"${describe(audioResult)}\",")
            appendLine("  \"controlResult\": \"${describe(controlResult)}\",")
            appendLine("  \"videoOutputCreated\": ${videoOut.exists()},")
            appendLine("  \"audioOutputCreated\": ${audioOut.exists()}")
            appendLine("}")
        }
        Stage0EvidenceRecorder.record(context, "stage1-codec-refusal.json", report)

        // 视频侧有两条**编码前**的合法拒绝码：容器不收这种样本格式
        // （`CONTAINER_VIDEO_CODEC_UNSUPPORTED`，阶段 2 步骤 7 引入的容器门），
        // 或本机没有这个编码器（`VIDEO_ENCODER_UNAVAILABLE`）。
        // 本用例要钉住的性质是「在编码之前被拒、且不产生输出文件」，
        // 两条码都满足；而候选 mime 本身是容器级格式（`video/quicktime` 之类），
        // 容器门排在编码器门之前，所以真机可观察到的永远是容器那一条。
        // 本机所有「MP4 容器收」的视频格式都有编码器 ⇒ 编码器门在真机上不可达，
        // 这一点由 JVM 侧的 planner 测试（`ProcessingPlanContractsTest`）覆盖。
        val videoRefusal = describe(videoResult)
        assertTrue(
            "请求本机没有编码器的视频格式时，应在编码前拒绝（实际 $videoRefusal）",
            videoRefusal in PRE_ENCODE_VIDEO_REFUSALS,
        )
        assertFalse("被拒绝的请求不得留下输出文件", videoOut.exists())

        val audioRefusal = describe(audioResult)
        assertTrue(
            "请求本机没有编码器的音频格式时，应在编码前拒绝（实际 $audioRefusal）",
            audioRefusal in PRE_ENCODE_AUDIO_REFUSALS,
        )
        assertFalse("被拒绝的请求不得留下输出文件", audioOut.exists())

        // 对照组必须**绕开全部**编码前校验（容器门与编码器门都绕开），
        // 否则「前置校验是有选择性的」这句话就没有被验证 —— 只盯着编码器码
        // 会让容器门悄悄拒掉对照组也照样变绿。
        assertFalse(
            "前置校验把本机支持的格式也拒掉了，说明它不是有选择性的（实际 ${describe(controlResult)}）",
            describe(controlResult) in PRE_ENCODE_VIDEO_REFUSALS,
        )
        assertFalse(
            "对照组请求的是本机支持的音频格式，不应被音频前置校验拒掉（实际 ${describe(controlResult)}）",
            describe(controlResult) in PRE_ENCODE_AUDIO_REFUSALS,
        )
    }

    // ────────────────────────────────────────────────────────────────────────

    /** 设备上**实际存在编码器**的 mime 集合，按分辨率探测（与前置校验同一口径）。 */
    private fun encoderMimes(): Set<String> {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        return list.codecInfos
            .filter { it.isEncoder }
            .flatMap { info -> info.supportedTypes.toList() }
            .toSet()
    }

    private fun hasEncoder(mime: String): Boolean {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        val format = if (MimeTypes.isVideo(mime)) {
            MediaFormat.createVideoFormat(mime, PROBE_WIDTH, PROBE_HEIGHT)
        } else {
            MediaFormat.createAudioFormat(mime, PROBE_SAMPLE_RATE, PROBE_CHANNELS)
        }
        return list.findEncoderForFormat(format) != null
    }

    private fun missingMime(candidates: List<String>, encoders: Set<String>): String? =
        candidates.firstOrNull { it !in encoders && !hasEncoder(it) }

    private fun videoPlan(mime: String) = plan(
        target = OutputTargets.Compatible.copy(videoCodecMimeType = mime),
        retainedTrackIds = emptySet(),
    )

    private fun audioPlan(mime: String) = plan(
        target = OutputTargets.Compatible.copy(audioCodecMimeType = mime),
        retainedTrackIds = setOf(1),
    )

    /**
     * 源指向不存在的文件：前置校验不读源，所以它能通过；
     * 真正的编码阶段会失败——这正是我们想看到的「没有被前置校验拒掉」。
     *
     * ⚠ 对照组必须是**真正的正例**：既要有本机编码器，**也要被目标容器（MP4）接受**。
     * 只按「有编码器」挑会撞上容器门 —— 本机具备 MP4 不收的编码器
     * （`audio/flac`、`audio/opus`、`video/x-vnd.on2.vp9`、`video/x-mvhevc` 等），
     * 而 `encoders` 是 `Set`，`first {}` 取到哪个是不确定的。阶段 7 回归时这里正是
     * `Failed(CONTAINER_AUDIO_CODEC_UNSUPPORTED)`：对照组被容器门拒了，
     * 「前置校验是有选择性的」这条断言于是形同虚设（旧写法只看编码器码，看不见这一点）。
     */
    private fun supportedControlPlan(encoders: Set<String>): ProcessingPlan {
        val supportedVideo = encodableAndAccepted(encoders, MuxerContainer.Mp4.videoSampleMimeTypes)
            ?: error("本机没有任何既被 MP4 接受、又能编码的视频格式，对照组无法构造")
        val supportedAudio = encodableAndAccepted(encoders, MuxerContainer.Mp4.audioSampleMimeTypes)
        return plan(
            target = OutputTargets.Compatible.copy(
                videoCodecMimeType = supportedVideo,
                audioCodecMimeType = supportedAudio ?: OutputTargets.Compatible.audioCodecMimeType,
            ),
            retainedTrackIds = if (supportedAudio == null) emptySet() else setOf(1),
        )
    }

    /** 容器接受且本机确实能编的样本格式；取容器表的顺序，保证结果是确定的。 */
    private fun encodableAndAccepted(encoders: Set<String>, accepted: List<String>): String? =
        accepted.firstOrNull { it in encoders && hasEncoder(it) }

    private fun plan(target: seeyuer.yingli.player.domain.processing.OutputTarget, retainedTrackIds: Set<Int>) =
        ProcessingPlan(
            source = SourceMediaInfo(
                MediaItemId("stage1-refusal"),
                "file://${File(workDir, "does-not-exist.mp4").absolutePath}",
                "does-not-exist.mp4",
                durationMillis = 1_000,
                width = PROBE_WIDTH,
                height = PROBE_HEIGHT,
                frameRate = 30f,
                videoBitrate = 1_000_000,
                containerMimeType = "video/mp4",
                hdrFormat = HdrFormat.SDR,
                tracks = listOf(
                    MediaTrackInfo(0, MediaTrackType.VIDEO, "video/avc"),
                    MediaTrackInfo(1, MediaTrackType.AUDIO, "audio/mp4a-latm", "und", 2),
                ),
            ),
            target = target,
            operation = ProcessingOperation.TRANSCODE,
            targetWidth = PROBE_WIDTH,
            targetHeight = PROBE_HEIGHT,
            retainedTrackIds = retainedTrackIds,
            estimatedOutputBytes = 1_000_000,
            requiredFreeBytes = 1_000_000,
            outputDisplayName = "refusal.mp4",
            // 本测试只关心编码前的可达性校验，不涉及降级后果。
            changes = emptyList(),
        )

    private fun describe(result: ProcessingEngineResult): String = when (result) {
        is ProcessingEngineResult.Failed -> "Failed(${result.errorCode})"
        is ProcessingEngineResult.Completed -> "Completed(fallbacks=${result.fallbacks.map { it.name }})"
        ProcessingEngineResult.Canceled -> "Canceled"
    }

    private companion object {
        /** 视频侧**编码前**可达的全部拒绝码：容器门与编码器门。 */
        val PRE_ENCODE_VIDEO_REFUSALS = setOf(
            "Failed(CONTAINER_VIDEO_CODEC_UNSUPPORTED)",
            "Failed(VIDEO_ENCODER_UNAVAILABLE)",
        )

        /** 音频侧**编码前**可达的全部拒绝码：容器门与编码器门。 */
        val PRE_ENCODE_AUDIO_REFUSALS = setOf(
            "Failed(CONTAINER_AUDIO_CODEC_UNSUPPORTED)",
            "Failed(AUDIO_ENCODER_UNAVAILABLE)",
        )

        const val PROBE_WIDTH = 1_280
        const val PROBE_HEIGHT = 720
        const val PROBE_SAMPLE_RATE = 44_100
        const val PROBE_CHANNELS = 2

        /**
         * 候选**必须真的包含设备缺失的格式**，否则本测试无法验证前置拒绝。
         *
         * 这台实测设备（Xiaomi 25102RKBEC / myron）**同时具备** AV1 / VP9 / VP8 / H.263 /
         * MPEG-4 的编码器，所以第一版候选列表被全部命中而失败。教训：不要假设「移动端不会有
         * 某个编码器」——HEVC、AV1 都已是常见硬件能力（这也正是 §15 那条「设备无 HEVC 编码器」
         * 的用例在本机不可复现的原因）。
         *
         * 因此候选按「Android 上确实不存在编码器」的可信度排序：
         * `video/mpeg2` 只有解码器（Media3 自己也有 `MimeTypes.VIDEO_MPEG2`），
         * `video/quicktime` / `video/x-msvideo` / `video/x-ms-wmv` 是容器 mime 而非编码器 mime，
         * 永远不会有编码器声明它们。后面的现代编码格式只作兜底。
         */
        val VIDEO_CANDIDATES = listOf(
            "video/mpeg2",
            "video/quicktime",
            "video/x-msvideo",
            "video/x-ms-wmv",
            "video/av01",
            "video/x-vnd.on2.vp9",
            "video/x-vnd.on2.vp8",
            "video/3gpp",
            "video/mp4v-es",
        )

        /** AC-3/E-AC-3 需要授权，Android 上通常只有解码器没有编码器。 */
        val AUDIO_CANDIDATES = listOf(
            "audio/ac3",
            "audio/eac3",
            "audio/quicktime",
            "audio/x-ms-wma",
            "audio/opus",
            "audio/vorbis",
            "audio/amr-wb",
            "audio/flac",
        )
    }
}
