package seeyuer.yingli.player.data.processing

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.BufferInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.Stage0EvidenceRecorder
import seeyuer.yingli.player.core.common.AppLogEvent
import seeyuer.yingli.player.core.common.AppLogger
import seeyuer.yingli.player.core.common.DefaultAppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.data.processing.muxer.ContainerMuxerFactory
import seeyuer.yingli.player.domain.processing.HdrFormat
import seeyuer.yingli.player.domain.processing.MediaRange
import seeyuer.yingli.player.domain.processing.MediaTrackInfo
import seeyuer.yingli.player.domain.processing.MediaTrackType
import seeyuer.yingli.player.domain.processing.OutputTarget
import seeyuer.yingli.player.domain.processing.OutputTargetId
import seeyuer.yingli.player.domain.processing.ProcessingEngineResult
import seeyuer.yingli.player.domain.processing.ProcessingOperation
import seeyuer.yingli.player.domain.processing.ProcessingPlan
import seeyuer.yingli.player.domain.processing.SourceMediaInfo

/**
 * 阶段 2 步骤 8（G3 / G11）的真机证据：**封装层的白名单必须来自目标容器的封装器**。
 *
 * ## 这条证据要回答什么
 *
 * 步骤 6 的 `PlatformRemuxEngine` 把「容器收哪些编码」手写成常量 `MP4_REMUX_MIME_TYPES`
 * （`video/avc` / `video/hevc` / `video/mp4v-es` / `audio/mp4a-latm` / `audio/mpeg`），
 * 且容器固定 MP4（平台 `MediaMuxer` 只能写 MP4/WebM/3GP）。后果：
 *
 *  1. **G11**：WebM(VP9/Opus) 的源走快速路径**必然** `REMUX_CODEC_UNSUPPORTED`，
 *     而 AOSP 的 `Mp4Muxer` 其实收 `video/x-vnd.on2.vp9` 与 `audio/opus`——
 *     白名单比封装器自己的能力**更窄**，这是纯粹的信息丢失；
 *  2. 反过来它也**不受约束**：手写列表与封装器会独立漂移，没有机制阻止它比封装器更宽。
 *
 * 修法只有一条：删掉列表，逐个轨道问封装器（`Muxer.Factory.getSupportedSampleMimeTypes`）。
 * 本测试用**不可能靠关键词通过**的方式验证它——同一份源，换目标容器，结果必须不同：
 *
 * | 用例 | 目标容器 | 期望 | 说明 |
 * |---|---|---|---|
 * | 1 | MP4 (`video/mp4`) | `Completed` + 轨道保持 VP9/Opus | G11 的正例：修好之前必然失败 |
 * | 2 | Ogg (`audio/ogg`) | `Failed(REMUX_CODEC_UNSUPPORTED)` | Ogg 不收视频轨——反例 |
 * | 3 | Matroska (`video/x-matroska`) | `Failed(REMUX_CONTAINER_UNSUPPORTED)` | 未接入的容器必须在建封装器之前被拒 |
 * | 4 | MP4 + `range` | `Completed` + 实际区间 | 带区间路径在换封装器后仍然成立（切片入口） |
 * | 5 | MP4，但源**不带** VP9 CodecPrivate | `Failed(REMUX_FAILED)` | **已知缺口 G27**：`Mp4Muxer` 建 `vpcC` 要 `csd-0`，白名单管不到这一层 |
 *
 * 用例 2 是关键：它证明用例 1 的成功**不是**「引擎变宽松了」，而是「问对了封装器」。
 * 若有人把白名单换回任何一份写死的表，这两条不可能同时通过。
 *
 * ## 为什么不需要编码器
 *
 * 封装**不解码也不编码**：它只搬样本字节。因此本测试用一个**合成的** WebM 源
 * （`WebmMuxer` + 假的 VP9/Opus 样本）就能覆盖整条链路，不依赖设备上的 VP9/Opus
 * **编码器**，也不依赖媒体库里恰好有 WebM 视频。
 *
 * 唯一需要真实的地方是 Opus 的 `OpusHead`（19 字节）：MP4 的 `dOps` box 与 Matroska 的
 * `CodecPrivate` 都要求它存在，封装器不会替我们编一个。
 */
@RunWith(AndroidJUnit4::class)
@UnstableApi
class Stage2RemuxContainerMeasurementTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dispatchers = DefaultAppDispatchers

    /**
     * 引擎把失败原因**只**写进日志（对外只有一个 `REMUX_FAILED` 码），
     * 所以测试自己接住日志，失败时把原因一并打出来——否则只会看到一个笼统的码。
     */
    private val loggedEvents = mutableListOf<AppLogEvent>()
    private val logger = AppLogger { _, event -> loggedEvents += event }

    private fun engine(): InAppRemuxEngine = InAppRemuxEngine(context, dispatchers, logger)

    private fun lastFailureReason(): String {
        val event = loggedEvents.lastOrNull() ?: return "引擎没有留下失败日志"
        val type = event.attributes["failureType"]?.value
        val message = event.attributes["failureMessage"]?.value
        val site = event.attributes["failureSite"]?.value
        return "$type@$site: $message"
    }

    private val workDir = File(context.cacheDir, "stage2-remux").apply {
        deleteRecursively()
        mkdirs()
    }

    /** 用例 1：G11 的正例——VP9 + Opus 的 WebM 源必须能被搬进 MP4。 */
    @Test
    fun vp9AndOpusWebmSourceIsRemuxedIntoMp4() = runBlocking {
        val source = syntheticWebm("source.webm")
        val output = File(workDir, "out-vp9-opus.mp4")

        val result = engine()
            .process(remuxPlan(source, MimeTypes.VIDEO_MP4, range = null), output.absolutePath) { }

        val outputTracks = if (output.exists()) trackMimeTypes(output) else emptyList()
        record(
            "stage2-remux-case1-vp9-opus-into-mp4.json",
            request = mapOf(
                "containerMimeType" to MimeTypes.VIDEO_MP4,
                "sourceVideoMimeType" to MimeTypes.VIDEO_VP9,
                "sourceAudioMimeType" to MimeTypes.AUDIO_OPUS,
                "sourceHasVp9CodecPrivate" to "true",
            ),
            measured = mapOf(
                "engineResult" to describe(result),
                "outputExists" to output.exists().toString(),
                "outputTrackMimeTypes" to outputTracks.toString(),
            ),
        )
        assertEquals(
            "VP9/Opus 的 WebM 源搬进 MP4 必须成功：修好之前这里必然 REMUX_CODEC_UNSUPPORTED（G11）；" +
                "失败原因 = ${lastFailureReason()}",
            "Completed",
            describe(result),
        )
        val tracks = trackMimeTypes(output)
        assertTrue(
            "MP4 输出必须保留 VP9 视频轨（说明白名单来自 Mp4Muxer 而不是那份旧常量），实测 $tracks",
            MimeTypes.VIDEO_VP9 in tracks,
        )
        assertTrue(
            "MP4 输出必须保留 Opus 音频轨，实测 $tracks",
            MimeTypes.AUDIO_OPUS in tracks,
        )
    }

    /**
     * 用例 5：**已知缺口 G27** —— 源没有 VP9 `CodecPrivate` 时，MP4 封装器建不出 `vpcC`。
     *
     * 这不是「白名单」的问题：`Mp4Muxer` 的能力表收 `video/x-vnd.on2.vp9`，引擎会放行，
     * 直到它真正要写 `vpcC` 才抛 `IllegalArgumentException`。所以本条与用例 1 共用同一份计划，
     * 唯一差别是源里有没有那个 CodecPrivate。
     *
     * 为什么现实里会遇到：ffmpeg 的 Matroska 封装器只写 `CodecPrivate` 给它认识的输入
     * （`refer/video-transcode-repos/FFmpeg/libavformat/matroskaenc.c:1219-1224` 的 `default`
     * 分支：**只有 `extradata_size > 0` 才写**），而 libvpx 的 VP9 **没有 extradata** ⇒
     * ffmpeg 产出的 VP9 WebM 通常不带 CodecPrivate。ffmpeg 自己能把它封进 MP4，是因为它在
     * MP4 侧**合成** `vpcC`（`ff_isom_write_vpcc`），而 media3 的 `Mp4Muxer` 不做这件事。
     *
     * 这条断言把缺口钉住：**一旦有人补上 csd 合成，它必须被改成 `Completed`**。
     */
    @Test
    fun vp9WithoutCodecPrivateCannotBeMuxedIntoMp4() = runBlocking {
        val source = syntheticWebm("source-without-csd.webm", withVp9CodecPrivate = false)
        val output = File(workDir, "out-without-csd.mp4")

        val result = engine()
            .process(remuxPlan(source, MimeTypes.VIDEO_MP4, range = null), output.absolutePath) { }

        record(
            "stage2-remux-case5-vp9-without-csd.json",
            request = mapOf(
                "containerMimeType" to MimeTypes.VIDEO_MP4,
                "sourceVideoMimeType" to MimeTypes.VIDEO_VP9,
                "sourceHasVp9CodecPrivate" to "false",
            ),
            measured = mapOf(
                "engineResult" to describe(result),
                "failureReason" to lastFailureReason(),
                "outputExists" to output.exists().toString(),
                "mp4MuxerVpcCRequirement" to "csd-0 is not found in the format for vpcC box",
            ),
        )
        assertEquals(
            "缺口 G27：没有 VP9 CodecPrivate 的源目前只能失败（失败原因 = ${lastFailureReason()}）。" +
                "补上 csd 合成后这条要改成 Completed。",
            "Failed(REMUX_FAILED)",
            describe(result),
        )
        assertTrue(
            "失败原因必须正是 vpcC 缺 csd-0；实测 = ${lastFailureReason()}",
            lastFailureReason().contains("vpcC"),
        )
        assertTrue("失败的请求不得留下输出文件", !output.exists())
    }

    /** 用例 2：反例——同一份源换 Ogg，视频轨必须被拒。 */
    @Test
    fun theSameSourceIsRefusedByAContainerThatCannotCarryVideo() = runBlocking {
        val source = syntheticWebm("source-for-ogg.webm")
        val output = File(workDir, "out-vp9-opus.ogg")

        val result = engine()
            .process(remuxPlan(source, MimeTypes.AUDIO_OGG, range = null), output.absolutePath) { }

        record(
            "stage2-remux-case2-ogg-refuses-video.json",
            request = mapOf(
                "containerMimeType" to MimeTypes.AUDIO_OGG,
                "sourceVideoMimeType" to MimeTypes.VIDEO_VP9,
            ),
            measured = mapOf(
                "engineResult" to describe(result),
                "outputExists" to output.exists().toString(),
            ),
        )
        assertEquals(
            "Ogg 只收 Opus、不收视频轨，必须按封装器的能力被拒（若这里变宽松，用例 1 的成功就没有意义）",
            "Failed(REMUX_CODEC_UNSUPPORTED)",
            describe(result),
        )
        assertTrue("被拒绝的请求不得留下输出文件", !output.exists())
    }

    /** 用例 3：未接入的容器必须在创建封装器之前被拒。 */
    @Test
    fun unknownContainerIsRefusedBeforeAnyMuxerIsCreated() = runBlocking {
        val source = syntheticWebm("source-for-mkv.webm")
        val output = File(workDir, "out-vp9-opus.mkv")

        val result = engine()
            .process(remuxPlan(source, "video/x-matroska", range = null), output.absolutePath) { }

        record(
            "stage2-remux-case3-unknown-container.json",
            request = mapOf(
                "containerMimeType" to "video/x-matroska",
                "muxerFactoryRegistered" to "false",
            ),
            measured = mapOf(
                "engineResult" to describe(result),
                "outputExists" to output.exists().toString(),
            ),
        )
        assertEquals(
            "MKV 没有接入封装器，必须在建输出之前拒绝",
            "Failed(REMUX_CONTAINER_UNSUPPORTED)",
            describe(result),
        )
        assertTrue("被拒绝的请求不得留下输出文件", !output.exists())
    }

    /** 用例 4：带时间区间的搬运（切片入口）在换成 media3 封装器之后仍然成立。 */
    @Test
    fun rangedRemuxStillReportsTheActualRange() = runBlocking {
        val source = syntheticWebm("source-for-range.webm")
        val output = File(workDir, "out-ranged.mp4")
        val requested = MediaRange(startMillis = 200, endMillis = 600)

        val result = engine()
            .process(remuxPlan(source, MimeTypes.VIDEO_MP4, range = requested), output.absolutePath) { }

        record(
            "stage2-remux-case4-ranged-remux.json",
            request = mapOf(
                "containerMimeType" to MimeTypes.VIDEO_MP4,
                "requestedStartMillis" to requested.startMillis.toString(),
                "requestedEndMillis" to requested.endMillis.toString(),
                "sampleIntervalMillis" to VIDEO_SAMPLE_DURATION_MS.toString(),
            ),
            measured = mapOf(
                "engineResult" to describe(result),
                "actualRange" to (result as? ProcessingEngineResult.Completed)?.actualRange.toString(),
            ),
        )
        assertEquals(
            "带区间的无损搬运应当成功；失败原因 = ${lastFailureReason()}",
            "Completed",
            describe(result),
        )
        val actual = requireNotNull(
            (result as ProcessingEngineResult.Completed).actualRange,
        ) { "Completed 必须回报实际区间（Q220/Q537）" }
        assertTrue(
            "无损搬运只能落在关键帧上，实际起点必须 ≤ 请求起点：实际 $actual，请求 $requested",
            actual.startMillis <= requested.startMillis,
        )
        // 实际终点是「最后一个被写入样本的时间戳」，所以它恒 ≤ 请求终点：
        // 搬运循环的第一条判断就是 `sampleTime >= endMicros → break`。这不是本次改动引入的语义，
        // 它与阶段 10 的实现逐行一致（`PlatformClipEngine.kt` @ f3ce9a7，原始提交 7a5d2a6）：
        // `if (sourceTrack < 0 || sampleTime < 0 || sampleTime >= endMicros) break`
        // `ClipEngineResult.Success(baseTimeMicros / MICROS_PER_MILLI, lastSampleMicros / MICROS_PER_MILLI)`。
        assertTrue(
            "实际终点不得越过请求终点（越过就是把区间外的内容也搬了）：实际 $actual，请求 $requested",
            actual.endMillis <= requested.endMillis,
        )
        assertTrue(
            "实际终点必须贴住请求终点，差值不得超过一个样本间隔——否则就是提前截断：" +
                "实际 $actual，请求 $requested",
            actual.endMillis >= requested.endMillis - VIDEO_SAMPLE_DURATION_MS,
        )
    }

    // ────────────────────────────────────────────────────────────────────────

    /**
     * 合成一份 WebM：VP9 视频轨 + Opus 音频轨。
     *
     * 样本是**假的**（不自洽的字节），这是刻意的：封装层不解码，所以它能如实检验
     * 「容器收不收这个编码」，而不需要设备具备 VP9/Opus 编码器。
     */
    private fun syntheticWebm(
        name: String,
        withVp9CodecPrivate: Boolean = true,
    ): File {
        val file = File(workDir, name)
        val factory = requireNotNull(ContainerMuxerFactory.forContainerMimeType(MimeTypes.VIDEO_WEBM)) {
            "WebM 封装器必须存在，否则本测试无法构造源"
        }
        val muxer = factory.create(file.absolutePath)
        try {
            val videoFormat = Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_VP9)
                .setWidth(WIDTH)
                .setHeight(HEIGHT)
                .setFrameRate(FRAME_RATE)
                // `language` 必须给：Matroska 的 TrackEntry 会把它写成 LANGUAGE 元素，
                // 而 `Format.Builder` 默认留空 ⇒ media3 的 `WebmElements` 在
                // `Util.getUtf8Bytes(null)` 上抛 NPE。真实源经 `MediaExtractor` 读出的
                // Format 一定带语言（至少 `und`），所以这条只影响手工构造的 Format。
                .setLanguage(LANGUAGE)
                .setInitializationData(
                    if (withVp9CodecPrivate) listOf(vp9CodecPrivate()) else emptyList(),
                )
                .build()
            val videoTrack = muxer.addTrack(videoFormat)
            val audioTrack = muxer.addTrack(
                Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_OPUS)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelCount(CHANNELS)
                    .setLanguage(LANGUAGE)
                    .setInitializationData(listOf(opusHead()))
                    .build(),
            )
            for (index in 0 until SAMPLE_COUNT) {
                val videoBytes = ByteArray(VIDEO_SAMPLE_BYTES) { ((it + index) and 0xFF).toByte() }
                muxer.writeSampleData(
                    videoTrack,
                    window(videoBytes),
                    // 全部标为关键帧：让用例 4 的 SEEK_TO_PREVIOUS_SYNC 一定能落在样本上。
                    BufferInfo(index * VIDEO_SAMPLE_DURATION_US, videoBytes.size, C.BUFFER_FLAG_KEY_FRAME),
                )
                val audioBytes = ByteArray(AUDIO_SAMPLE_BYTES) { ((it * 3 + index) and 0xFF).toByte() }
                muxer.writeSampleData(
                    audioTrack,
                    window(audioBytes),
                    BufferInfo(index * AUDIO_SAMPLE_DURATION_US, audioBytes.size, 0),
                )
            }
        } finally {
            muxer.close()
        }
        assertTrue("合成源必须真的写出了内容：${file.absolutePath}", file.length() > 0)
        assertEquals(
            "合成源的轨道必须能被平台解析器读出来（否则后面的断言测的就不是封装层）",
            listOf(MimeTypes.VIDEO_VP9, MimeTypes.AUDIO_OPUS).sorted(),
            trackMimeTypes(file).sorted(),
        )
        return file
    }

    /**
     * 计划里刻意**不**带质量参数：带质量参数的目标会被判成 `TRANSCODE`（§14.3.1 的判定顺序更正），
     * 那就走到重编码路径去了，测不到封装层。
     */
    private fun remuxPlan(source: File, containerMimeType: String, range: MediaRange?): ProcessingPlan {
        val tracks = trackMimeTypes(source).mapIndexed { index, mime ->
            MediaTrackInfo(
                id = index,
                type = if (MimeTypes.isVideo(mime)) MediaTrackType.VIDEO else MediaTrackType.AUDIO,
                mimeType = mime,
            )
        }
        return ProcessingPlan(
            source = SourceMediaInfo(
                mediaId = MediaItemId("stage2-remux"),
                uri = "file://${source.absolutePath}",
                displayName = source.name,
                durationMillis = DURATION_MILLIS,
                width = WIDTH,
                height = HEIGHT,
                frameRate = FRAME_RATE,
                videoBitrate = null,
                containerMimeType = MimeTypes.VIDEO_WEBM,
                hdrFormat = HdrFormat.SDR,
                tracks = tracks,
            ),
            target = OutputTarget(
                id = OutputTargetId("remux_test"),
                containerMimeType = containerMimeType,
                // `null` = 跟随源：封装层的语义就是「不改编码」。
                videoCodecMimeType = null,
                audioCodecMimeType = null,
                // 不设 maximumLongEdge / 码率 ⇒ `hasQualityParameters` 为 false，
                // 这正是「这个计划是 REMUX 而不是 TRANSCODE」的充要条件。
            ),
            operation = ProcessingOperation.REMUX,
            targetWidth = WIDTH,
            targetHeight = HEIGHT,
            // 保留全部音轨；轨道顺序由解析器决定，**不能写死索引**（实测 WebM 源的
            // 解析器顺序是音轨在前）。空集表示「不要音轨」。
            retainedTrackIds = tracks.filter { it.type == MediaTrackType.AUDIO }.map { it.id }.toSet(),
            estimatedOutputBytes = 1_000_000,
            requiredFreeBytes = 1_000_000,
            outputDisplayName = "remux_test.mp4",
            changes = emptyList(),
            range = range,
        )
    }

    /** 用平台解析器读回轨道 mime —— 这是「输出里到底有什么」的唯一可信来源。 */
    private fun trackMimeTypes(file: File): List<String> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).map { track ->
                extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME) ?: ""
            }
        } finally {
            extractor.release()
        }
    }

    private fun window(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).apply {
        position(0)
        limit(bytes.size)
    }

    /**
     * `OpusHead`（RFC 7845 §5.1）：封装器不会替我们生成 CodecPrivate / `dOps`，
     * 所以合成源必须自带一份合法的。
     */
    private fun opusHead(): ByteArray {
        val head = ByteArray(OPUS_HEAD_BYTES)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1 // version
        head[9] = CHANNELS.toByte()
        head[10] = (OPUS_PRE_SKIP and 0xFF).toByte()
        head[11] = ((OPUS_PRE_SKIP shr 8) and 0xFF).toByte()
        // inputSampleRate = 48000，小端
        head[12] = 0x80.toByte()
        head[13] = 0xBB.toByte()
        head[14] = 0x00
        head[15] = 0x00
        // outputGain = 0（2 字节）、mappingFamily = 0（1 字节）已是 0
        return head
    }

    /**
     * VP9 的 CodecPrivate，按 WebM 的「VP9 Codec Feature Metadata」二进制形式：
     * 每个特性 = `ID(1 字节) + 长度(1 字节) + 数据(1 字节)`，
     * `1=profile`、`2=level`、`3=bitDepth`、`4=chromaSubsampling`
     * （解析方是 media3 的 `Boxes.parseVp9CodecPrivateFromCsd`，它按 `i += 3` 读 `csd0[i]` 与 `csd0[i+2]`）。
     *
     * MP4 侧的 `vpcC` box 需要它：`Boxes.vpcCBox` 的第一句就是
     * `checkArgument(!format.initializationData.isEmpty(), "csd-0 is not found in the format for vpcC box")`。
     */
    private fun vp9CodecPrivate(): ByteArray = byteArrayOf(
        0x01, 0x01, 0x00, // profile = 0
        0x02, 0x01, 0x0A, // level = 10
        0x03, 0x01, 0x08, // bitDepth = 8
        0x04, 0x01, 0x00, // chromaSubsampling = 0
    )

    /**
     * 把一次实测写成独立 JSON（`adb pull` 后归档到 `docs/architecture/evidence/stage2/`）。
     *
     * **每个用例一个文件名**：阶段 0 出过一次「三份证据落到同一个文件名、只剩最后一份」的事故，
     * 根因就是用同一路径反复覆盖。而且这里刻意在断言**之前**记录——断言失败时证据仍然存在。
     */
    private fun record(name: String, request: Map<String, String>, measured: Map<String, String>) {
        val json = JSONObject().apply {
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            put("androidApi", Build.VERSION.SDK_INT)
            put("source", "合成 WebM：VP9 视频轨（假样本）+ Opus 音频轨（真 OpusHead）")
            put("requested", JSONObject(request as Map<*, *>))
            put("measured", JSONObject(measured as Map<*, *>))
        }
        Stage0EvidenceRecorder.record(context, name, json.toString(2))
    }

    private fun describe(result: ProcessingEngineResult): String = when (result) {        is ProcessingEngineResult.Failed -> "Failed(${result.errorCode})"
        // 封装层不产生回退（回退是 Transformer 的概念），所以正常情况下就是 `Completed`；
        // 若真有回退，让它显式出现而不是被吞掉。
        is ProcessingEngineResult.Completed -> if (result.fallbacks.isEmpty()) {
            "Completed"
        } else {
            "Completed(fallbacks=${result.fallbacks.map { it.name }})"
        }
        ProcessingEngineResult.Canceled -> "Canceled"
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 360
        const val FRAME_RATE = 30f
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val LANGUAGE = "und"
        const val OPUS_PRE_SKIP = 312
        const val OPUS_HEAD_BYTES = 19

        /** 1 秒：40 帧视频（25 ms 一帧）+ 50 个音频块（20 ms 一帧）。 */
        const val SAMPLE_COUNT = 40
        const val VIDEO_SAMPLE_DURATION_US = 25_000L
        const val AUDIO_SAMPLE_DURATION_US = 20_000L

        /** 相邻样本之间的最大时间戳间隔（视频 25 ms > 音频 20 ms）。 */
        const val VIDEO_SAMPLE_DURATION_MS = 25L
        const val VIDEO_SAMPLE_BYTES = 512
        const val AUDIO_SAMPLE_BYTES = 128
        const val DURATION_MILLIS = 1_000L
    }
}
