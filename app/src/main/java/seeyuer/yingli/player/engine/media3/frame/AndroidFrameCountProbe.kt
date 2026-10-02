package seeyuer.yingli.player.engine.media3.frame

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import java.io.IOException
import seeyuer.yingli.player.domain.playback.ContainerSampleSource
import seeyuer.yingli.player.domain.playback.FrameCountProbe
import seeyuer.yingli.player.domain.playback.FrameScanCancellation
import seeyuer.yingli.player.domain.playback.VideoSampleTimeline
import seeyuer.yingli.player.domain.playback.countVideoFrames

/**
 * `MediaExtractor` 版本的帧数探针：**只读容器、不解码**，因此比全解码铺一遍快得多。
 *
 * 代价只有一遍顺序读：sample 计数不需要 `readSampleData`（不把样本拷进 ByteBuffer），
 * 只需 `advance()` 让提取器在文件里前进。实际耗时随文件大小与 IO 带宽线性增长
 * （报告里给出量级），所以调用方必须放在 IO 线程并能取消。
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class AndroidFrameCountProbe(
    context: Context,
    private val openSampleSource: (String, Context) -> ContainerSampleSource? = ::openMediaExtractorSource,
) : FrameCountProbe {
    private val applicationContext = context.applicationContext

    override fun probe(uri: String, cancellation: FrameScanCancellation): VideoSampleTimeline? {
        val source = openSampleSource(uri, applicationContext) ?: return null
        return try {
            countVideoFrames(source, cancellation)
        } finally {
            runCatching { source.close() }
        }
    }
}

/** 打开一个 `MediaExtractor` 样本源；URI 不可读（无权限/文件不存在/容器不识别）时返回 null。 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun openMediaExtractorSource(uri: String, context: Context): ContainerSampleSource? {
    val extractor = MediaExtractor()
    return try {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == null || parsed.scheme == "file") {
            extractor.setDataSource(parsed.path ?: uri)
        } else {
            extractor.setDataSource(context, parsed, null)
        }
        MediaExtractorSampleSource(extractor)
    } catch (_: IOException) {
        runCatching { extractor.release() }
        null
    } catch (_: SecurityException) {
        // content URI 未授权读取：保持估算值，不把异常抛到 UI 线程。
        runCatching { extractor.release() }
        null
    } catch (_: RuntimeException) {
        // 部分容器的原生解析失败会以 RuntimeException 冒出来。
        runCatching { extractor.release() }
        null
    }
}

/** 把 `MediaExtractor` 收窄成 [ContainerSampleSource]（只暴露计数需要的读取面）。 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class MediaExtractorSampleSource(
    private val extractor: MediaExtractor,
) : ContainerSampleSource {
    override val trackCount: Int get() = extractor.trackCount

    override fun trackMimeType(index: Int): String? =
        runCatching { extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) }.getOrNull()

    override fun trackDurationMicros(index: Int): Long? =
        runCatching {
            val format = extractor.getTrackFormat(index)
            if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else null
        }.getOrNull()

    override fun selectTrack(index: Int): Boolean =
        runCatching { extractor.selectTrack(index) }.isSuccess

    override fun sampleTimeMicros(): Long = extractor.sampleTime

    override fun advance(): Boolean = extractor.advance()

    override fun close() {
        extractor.release()
    }
}
