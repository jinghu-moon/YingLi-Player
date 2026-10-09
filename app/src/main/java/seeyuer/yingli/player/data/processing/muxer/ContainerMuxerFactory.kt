package seeyuer.yingli.player.data.processing.muxer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.AacMuxer
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.OggMuxer
import androidx.media3.muxer.SeekableMuxerOutput
import androidx.media3.muxer.WebmMuxer
import com.google.common.collect.ImmutableList
import java.io.File
import java.io.FileOutputStream

/**
 * 目标容器，以及**该容器实际允许写入的样本 mime**（设计稿 §6.2 的 G4）。
 *
 * 这张表的两个用途都来自设计稿的 F12/F19：
 *  1. `Transformer.Builder.setMuxerFactory(...)` 只能接受**一个**容器——库自带的是 MP4，
 *     WebM / Ogg / ADTS 必须由我们把 `WebmMuxer` / `OggMuxer` / `AacMuxer` 接进去；
 *  2. 请求「容器 + 编码」的不可能组合时，库的行为是**在编码完成之后抛异常**。把这句话
 *     翻译成一次注定作废的整段编码，代价是几十秒到几分钟的 CPU 与电量，所以要在
 *     `Transformer` build **之前**用本表挡掉（F19 的第二道防线）。
 *
 * MP4 一行刻意取自库自己的 [Mp4Muxer] 静态表，而不是我们手写一份：
 * 手写就会产生第二个真源，而「容器收哪些编码」这件事只有封装器自己说得准（G3/G11）。
 *
 * 本类只是一张**能力表**：谁创建 MP4 封装器是调用方的取舍，见 [ContainerMuxerFactory] 的说明。
 */
@OptIn(UnstableApi::class)
enum class MuxerContainer(
    val containerMimeType: String,
    val videoSampleMimeTypes: List<String>,
    val audioSampleMimeTypes: List<String>,
) {
    Mp4(
        containerMimeType = MimeTypes.VIDEO_MP4,
        videoSampleMimeTypes = Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES,
        audioSampleMimeTypes = Mp4Muxer.SUPPORTED_AUDIO_SAMPLE_MIME_TYPES,
    ),
    Webm(
        containerMimeType = MimeTypes.VIDEO_WEBM,
        videoSampleMimeTypes = listOf(MimeTypes.VIDEO_VP8, MimeTypes.VIDEO_VP9),
        audioSampleMimeTypes = listOf(MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_VORBIS),
    ),
    Ogg(
        containerMimeType = MimeTypes.AUDIO_OGG,
        videoSampleMimeTypes = emptyList(),
        audioSampleMimeTypes = listOf(MimeTypes.AUDIO_OPUS),
    ),
    Adts(
        // 容器名沿用域层 `OutputTarget.fileExtension` 已经认识的写法（`audio/aac` → `.aac`）。
        // `MimeTypes.AUDIO_AAC` 是**样本** mime（`audio/mp4a-latm`），不是容器 mime。
        containerMimeType = "audio/aac",
        videoSampleMimeTypes = emptyList(),
        audioSampleMimeTypes = listOf(MimeTypes.AUDIO_AAC),
    ),
    ;

    /** 该容器是否允许把 [sampleMimeType] 作为 [trackType] 轨道写入。 */
    fun supports(trackType: Int, sampleMimeType: String): Boolean = when (trackType) {
        C.TRACK_TYPE_VIDEO -> sampleMimeType in videoSampleMimeTypes
        C.TRACK_TYPE_AUDIO -> sampleMimeType in audioSampleMimeTypes
        else -> false
    }

    companion object {
        fun forContainerMimeType(containerMimeType: String): MuxerContainer? =
            entries.firstOrNull { it.containerMimeType == containerMimeType }
    }
}

/**
 * 把 [MuxerContainer] 里各个容器的封装器接进来（G4）。
 *
 * ## 与 `Transformer.Builder` 的关系
 *
 * `Transformer` 自己的默认封装器是 `DefaultMuxer.Factory`（产出 MP4），它带着 `videoDurationUs`、
 * 元数据收集与 faststart 相关配置。用裸 `Mp4Muxer` 顶替它，能力增量是零（MP4 本来就可输出），
 * 换掉的却是一段已在真机上取证过的默认行为（阶段 1 的码率与 HDR 证据全跑在这条路径上）。
 * **因此「MP4 不交给本类」这条规则写在 `Media3ProcessingEngine` 里**——那是
 * `Transformer` 的取舍，不是封装器工厂的属性。封装器工厂本身对四个容器一视同仁：
 * 手写搬运管线（`InAppRemuxEngine`）需要 MP4 的 `Mp4Muxer`，而且**只有它**能把
 * VP9 / Opus 写进 MP4（G11）。
 *
 * ## 资源归属
 *
 * `Mp4Muxer`（经 `SeekableMuxerOutput`）、`WebmMuxer`、`OggMuxer` 的 `close()` 会关掉它们持有的
 * 输出通道/流，`AacMuxer` 自己持有 `FileOutputStream` 并负责关闭，因此这里不需要额外的包装。
 */
@OptIn(UnstableApi::class)
class ContainerMuxerFactory private constructor(
    private val container: MuxerContainer,
) : Muxer.Factory {

    @Suppress("DEPRECATION") // 1.10.1 起 `Mp4Muxer.Builder(FileOutputStream)` 已废弃，用 SeekableMuxerOutput。
    override fun create(path: String): Muxer = when (container) {
        MuxerContainer.Mp4 -> Mp4Muxer.Builder(SeekableMuxerOutput.of(path)).build()
        MuxerContainer.Webm -> WebmMuxer.Builder(SeekableMuxerOutput.of(path)).build()
        // FileChannel 实现了 WritableByteChannel；通道关闭会一并关闭底层流。
        MuxerContainer.Ogg -> OggMuxer.Builder(FileOutputStream(File(path)).channel).build()
        MuxerContainer.Adts -> AacMuxer(FileOutputStream(File(path)))
    }

    /**
     * 与 [container] 的能力表同源。库内部在写每个样本前会查这个方法，
     * 我们自己在 planner 之后也查同一张表（[MuxerContainer.supports]）。
     */
    override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> = ImmutableList.copyOf(
        when (trackType) {
            C.TRACK_TYPE_VIDEO -> container.videoSampleMimeTypes
            C.TRACK_TYPE_AUDIO -> container.audioSampleMimeTypes
            else -> emptyList()
        },
    )

    /**
     * 白名单查询：目标容器能否写入 [trackType] 类型、样本 mime 为 [sampleMimeType] 的轨道。
     *
     * 与 [getSupportedSampleMimeTypes] **同源**——库内部在写样本前查的就是那个方法，
     * 因此这个判断与「库自己会不会拒绝」不可能漂移。封装层引擎与 `Transformer` 前置校验
     * 都只问这一个方法，不再各自维护 mime 列表（G3/G11 的修法）。
     */
    fun supports(trackType: Int, sampleMimeType: String): Boolean =
        sampleMimeType in getSupportedSampleMimeTypes(trackType)

    override fun supportsWritingNegativeTimestampsInEditList(): Boolean = false

    override fun toString(): String = "ContainerMuxerFactory(${container.containerMimeType})"

    companion object {
        /** 目标容器对应的封装器适配器；容器未知时返回 `null`。 */
        fun forContainerMimeType(containerMimeType: String): ContainerMuxerFactory? =
            MuxerContainer.forContainerMimeType(containerMimeType)?.let(::ContainerMuxerFactory)
    }
}
