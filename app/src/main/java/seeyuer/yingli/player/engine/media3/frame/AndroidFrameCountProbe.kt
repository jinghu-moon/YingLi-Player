package seeyuer.yingli.player.engine.media3.frame

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.io.IOException
import seeyuer.yingli.player.domain.playback.ContainerSampleSource
import seeyuer.yingli.player.domain.playback.FrameCountProbe
import seeyuer.yingli.player.domain.playback.FrameScanLifecycle
import seeyuer.yingli.player.domain.playback.FrameScanReport
import seeyuer.yingli.player.domain.playback.countVideoFrames

/**
 * `MediaExtractor` 版本的帧数探针：**只读容器、不解码**。
 *
 * 计数本身不需要 `readSampleData`（不把样本拷进 ByteBuffer），只需 `advance()` 让提取器在容器里前进；
 * 真实耗时/吞吐以真机实测为准（见 `docs/19` 的记录与 `LocalMediaFrameCounter` 里的事件码），
 * 不在这里臆测"随文件大小线性增长"。
 *
 * ## 取消：真中断 + 唯一的释放者
 *
 * API 36 的 `MediaExtractor` 没有 `setCancellationSignal`，`advance()` 也没有可中断的重载，
 * 所以"打断正在执行的 `advance()`"只能靠**从另一个线程释放容器**：`release()` 把 native 对象换掉，
 * 阻塞中的调用会以异常/`false` 退出。
 *
 * 但 `MediaExtractor` **非线程安全**，所以并发约束必须写死（由 [FrameScanLifecycle] 保证）：
 * 1. 打断动作（就是这里的 `source.close()`）在 [FrameScanLifecycle.attachInterrupt] 时登记一次；
 * 2. **谁赢得状态机终态，谁负责 `close()`**：扫描线程正常跑完走 [FrameScanLifecycle.finish] 释放；
 *    被取消则 [FrameScanLifecycle.cancel] 的赢家在取消线程上释放。两者互斥，因此不存在重复释放；
 * 3. 取消之后扫描线程不得再碰容器：它在每次 native 调用前读取消标记，
 *    即使赢的是取消方，它也已经退出循环（最多走完一个正在跑的 native 调用）；
 * 4. 释放之后的结果一律作废：见 [FrameScanLifecycle.canPublishResult]。
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class AndroidFrameCountProbe(
    context: Context,
    private val openSampleSource: (String, Context) -> ContainerSampleSource? = ::openMediaExtractorSource,
) : FrameCountProbe {
    private val applicationContext = context.applicationContext

    override fun probe(uri: String, lifecycle: FrameScanLifecycle): FrameScanReport? {
        val source = openSampleSource(uri, applicationContext) ?: return null
        // 先登记打断动作，再判断是否已经取消：取消可能早于资源打开到达（任务还排在 IO 队列里），
        // 那种情况下 attachInterrupt 会当场执行释放，因此不会出现"没人释放"。
        //
        // 释放是**尽力而为**的收尾：它可能在取消线程（生产的取消路径就是主线程）上执行，
        // 容器的 teardown 失败绝不能把调用方带崩——这与既有 finally 里的 close() 同一口径。
        lifecycle.attachInterrupt { runCatching { source.close() } }
        if (lifecycle.isCancelled) {
            // 已经取消：容器刚被释放，接下来任何一个调用都可能落在已释放的对象上，一次都不许碰。
            return FrameScanReport(timeline = null, scannedSamples = 0L, cancelled = true, byteSize = source.byteSize)
        }
        return try {
            countVideoFrames(source, lifecycle)
        } finally {
            // finish() 赢得终态 => 扫描线程负责释放；输给取消 => 取消方已经/即将释放，这里绝不能重复 close()。
            // 释放失败不改变扫描结论（MediaExtractor.release() 不返回错误，这里只是防御性兜底）。
            if (lifecycle.finish()) runCatching { source.close() }
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
        MediaExtractorSampleSource(extractor, containerByteSize(parsed, uri, context))
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

/**
 * 取容器字节数，只用于耗时/吞吐日志。
 * 拿不到（流式源、读不到 stat、无权限）就返回 null —— 少一个日志字段，不能让整次探测失败。
 */
private fun containerByteSize(parsed: Uri, uri: String, context: Context): Long? = try {
    when (parsed.scheme) {
        null, "file" -> File(parsed.path ?: uri).length().takeIf { it > 0L }
        else -> context.contentResolver.openAssetFileDescriptor(parsed, "r")?.use { descriptor ->
            descriptor.length.takeIf { it > 0L }
        }
    }
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
} catch (_: RuntimeException) {
    null
}

/** 把 `MediaExtractor` 收窄成 [ContainerSampleSource]（只暴露计数需要的读取面）。 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class MediaExtractorSampleSource(
    private val extractor: MediaExtractor,
    override val byteSize: Long?,
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
