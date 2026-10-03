package seeyuer.yingli.player.engine.media3.frame

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import seeyuer.yingli.player.app.AndroidLogSink
import seeyuer.yingli.player.core.common.AppLogEvent
import seeyuer.yingli.player.core.common.AppLogLevel
import seeyuer.yingli.player.core.common.LogValue
import seeyuer.yingli.player.core.common.RedactingAppLogger
import seeyuer.yingli.player.core.common.SensitiveValueRedactor
import seeyuer.yingli.player.domain.playback.ContainerSampleSource
import seeyuer.yingli.player.domain.playback.FrameScanLifecycle
import seeyuer.yingli.player.domain.playback.FrameScanReport
import seeyuer.yingli.player.domain.playback.measuredFrameRateOrNull

/**
 * 真机验证帧数探针的两件事（JVM 测不了）：
 *
 * 1. 只读容器统计出的 sample 数与容器事实一致（用自造的 MP4：样本数由测试自己写进去，因此是已知量）；
 * 2. **取消能真正打断阻塞中的 `advance()`**：取消方从另一个线程 `release()` 容器，
 *    测量"取消返回耗时"和"扫描线程退出耗时"，并确认结果被归类为已取消、没有崩溃。
 *
 * 自造容器而不是依赖设备里已有的视频：样本数、字节数、时间轴都由测试控制，
 * 断言才有确定的口径（真机真实文件的实测吞吐另见 docs/19 的记录）。
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AndroidFrameCountProbeTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = RedactingAppLogger(SensitiveValueRedactor { it }, AndroidLogSink())

    @Test
    fun countingReadsEverySampleOfTheSyntheticContainer() {
        val file = syntheticContainer(SMALL_SAMPLE_COUNT)
        val lifecycle = FrameScanLifecycle()
        val probe = AndroidFrameCountProbe(context)

        val startedAt = SystemClock.elapsedRealtime()
        val report = probe.probe(file.absolutePath, lifecycle)
        val elapsedMillis = SystemClock.elapsedRealtime() - startedAt

        assertNotNull("自造容器必须能被 MediaExtractor 读出来", report)
        requireNotNull(report)
        assertEquals(SMALL_SAMPLE_COUNT.toLong(), report.timeline?.frameCount)
        assertEquals(SMALL_SAMPLE_COUNT.toLong(), report.scannedSamples)
        assertFalse(report.cancelled)
        assertEquals(file.length(), report.byteSize)
        // 30fps 的时间轴必须由真实样本时间派生（33_333us 间隔）。
        assertEquals(30f, report.timeline?.let(::measuredFrameRateOrNull) ?: 0f, 0.5f)
        // 扫描正常结束 => 扫描线程赢得终态、资源由它释放：这之后再取消不许再释放一次。
        assertFalse("扫描结束后取消方不得再释放资源", lifecycle.cancel())
        logger.log(
            AppLogLevel.INFO,
            AppLogEvent(
                "FRAME_SCAN_TEST_MEASURED",
                "Instrumented probe measurement.",
                mapOf(
                    "bytes" to LogValue.Public(file.length().toString()),
                    "samples" to LogValue.Public(report.scannedSamples.toString()),
                    "elapsedMs" to LogValue.Public(elapsedMillis.toString()),
                    "cancelled" to LogValue.Public("false"),
                ),
            ),
        )
    }

    @Test
    fun cancelInterruptsTheRunningScanInsteadOfWaitingForIt() {
        // 跑多轮、每轮在不同的样本位置取消：既证明"取消不会等扫描自己跑完"，也证明
        // 从另一个线程 release() 容器不会把进程搞崩（每一轮都复用了同一份并发路径）。
        val file = syntheticContainer(LARGE_SAMPLE_COUNT)
        val rounds = (1..INTERRUPT_ROUNDS).map { round -> cancelRound(file, INTERRUPT_AFTER_SAMPLES * round) }

        rounds.forEachIndexed { index, round ->
            assertTrue("第 ${index + 1} 轮：取消导致的退出必须归类为已取消", round.report.cancelled)
            assertEquals(null, round.report.timeline)
            assertTrue("第 ${index + 1} 轮：取消时必须带回已扫到的样本数", round.report.scannedSamples > 0L)
            assertTrue(
                "第 ${index + 1} 轮：扫描似乎已经跑完，取消时延不能说明中断生效",
                round.scannedBeforeCancel < LARGE_SAMPLE_COUNT,
            )
            // 真正的因果证明：还有大量样本没读，却在取消后立刻退出。
            assertTrue("第 ${index + 1} 轮：取消返回过慢（${round.cancelReturnMillis}ms）", round.cancelReturnMillis <= CANCEL_BUDGET_MILLIS)
            assertTrue("第 ${index + 1} 轮：扫描线程退出过慢（${round.scanExitMillis}ms）", round.scanExitMillis <= CANCEL_BUDGET_MILLIS)
            assertEquals(FrameScanLifecycle.Phase.CANCELLED, round.lifecycle.phase)
        }
        logger.log(
            AppLogLevel.INFO,
            AppLogEvent(
                "FRAME_SCAN_TEST_MEASURED",
                "Instrumented cancel measurement.",
                mapOf(
                    "bytes" to LogValue.Public(file.length().toString()),
                    "samples" to LogValue.Public(rounds.joinToString("/") { it.report.scannedSamples.toString() }),
                    "scannedBeforeCancel" to LogValue.Public(rounds.joinToString("/") { it.scannedBeforeCancel.toString() }),
                    "cancelReturnMillis" to LogValue.Public(rounds.joinToString("/") { it.cancelReturnMillis.toString() }),
                    "scanExitMillis" to LogValue.Public(rounds.joinToString("/") { it.scanExitMillis.toString() }),
                    // release() 是并发发生的：如果它真的把阻塞中的 native 调用打断，这里会记下异常类型；
                    // 如果扫描线程恰好在两次调用之间被标记拦下，这里就是 none —— 两种都算"已取消"。
                    "interruptedBy" to LogValue.Public(rounds.joinToString("/") { it.interruptedBy ?: "flag" }),
                    "cancelled" to LogValue.Public("true"),
                ),
            ),
        )
    }

    private fun cancelRound(file: File, cancelAfterSamples: Int): CancelRound {
        val scanStarted = CountDownLatch(1)
        val scanFinished = CountDownLatch(1)
        val advanced = AtomicInteger(0)
        val interruptedBy = AtomicReference<String?>(null)
        // 注入一个包装过的样本源：扫到第 N 个样本时通知测试线程"扫描确实在跑"，
        // 这样取消时机不依赖 sleep，也不依赖扫描速度。
        val probe = AndroidFrameCountProbe(context) { uri, openContext ->
            openMediaExtractorSource(uri, openContext)?.let { source ->
                object : ContainerSampleSource by source {
                    override fun advance(): Boolean {
                        if (advanced.incrementAndGet() == cancelAfterSamples) scanStarted.countDown()
                        return try {
                            source.advance()
                        } catch (error: RuntimeException) {
                            // 容器被另一个线程 release() 了：记下异常类型当证据，然后原样抛出。
                            interruptedBy.compareAndSet(null, error::class.java.simpleName)
                            throw error
                        }
                    }
                }
            }
        }
        val lifecycle = FrameScanLifecycle()
        var report: FrameScanReport? = null
        val scanThread = Thread {
            report = probe.probe(file.absolutePath, lifecycle)
            scanFinished.countDown()
        }

        scanThread.start()
        assertTrue("扫描没有进入运行状态", scanStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS))

        val cancelStartedAt = SystemClock.elapsedRealtime()
        val ownedRelease = lifecycle.cancel()
        val cancelReturnedAt = SystemClock.elapsedRealtime()
        assertTrue("取消必须由本次调用赢得释放义务", ownedRelease)
        assertTrue("扫描线程没有在取消后退出", scanFinished.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        val scanExitedAt = SystemClock.elapsedRealtime()
        scanThread.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS))

        return CancelRound(
            lifecycle = lifecycle,
            report = requireNotNull(report),
            scannedBeforeCancel = advanced.get(),
            cancelReturnMillis = cancelReturnedAt - cancelStartedAt,
            scanExitMillis = scanExitedAt - cancelStartedAt,
            interruptedBy = interruptedBy.get(),
        )
    }

    private data class CancelRound(
        val lifecycle: FrameScanLifecycle,
        val report: FrameScanReport,
        val scannedBeforeCancel: Int,
        val cancelReturnMillis: Long,
        val scanExitMillis: Long,
        val interruptedBy: String?,
    )

    @Test
    fun cancelBeforeTheScanStartsReleasesWithoutScanning() {
        val file = syntheticContainer(SMALL_SAMPLE_COUNT)
        val lifecycle = FrameScanLifecycle()
        var releasedByAttach = false
        val advanced = AtomicInteger(0)
        val probe = AndroidFrameCountProbe(context) { uri, openContext ->
            openMediaExtractorSource(uri, openContext)?.let { source ->
                object : ContainerSampleSource by source {
                    override fun advance(): Boolean {
                        advanced.incrementAndGet()
                        return source.advance()
                    }

                    override fun close() {
                        releasedByAttach = true
                        source.close()
                    }
                }
            }
        }

        // 资源还没打开就先取消：attachInterrupt 必须当场释放，且一个样本都不许读。
        assertTrue(lifecycle.cancel())
        val report = probe.probe(file.absolutePath, lifecycle)

        assertTrue("取消先到时必须由登记方立即释放", releasedByAttach)
        assertEquals(0, advanced.get())
        assertEquals(true, report?.cancelled)
        assertEquals(0L, report?.scannedSamples)
        assertEquals(null, report?.timeline)
    }

    /** 自造一个 MP4：样本数与字节数都由测试控制（`MediaMuxer` 不校验样本内容，只写进容器）。 */
    private fun syntheticContainer(sampleCount: Int): File {
        val existing = File(context.cacheDir, "frame-count-probe-$sampleCount.mp4")
        if (existing.isFile && existing.length() > 0L) return existing
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setByteBuffer("csd-0", ByteBuffer.wrap(SPS))
            setByteBuffer("csd-1", ByteBuffer.wrap(PPS))
        }
        val muxer = MediaMuxer(existing.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val trackIndex = muxer.addTrack(format)
            muxer.start()
            val sample = ByteArray(BYTES_PER_SAMPLE).also { bytes ->
                IDR_PREFIX.copyInto(bytes)
            }
            val buffer = ByteBuffer.allocate(BYTES_PER_SAMPLE)
            val info = MediaCodec.BufferInfo()
            for (index in 0 until sampleCount) {
                buffer.clear()
                buffer.put(sample)
                buffer.flip()
                info.set(0, BYTES_PER_SAMPLE, index * FRAME_INTERVAL_MICROS, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer.writeSampleData(trackIndex, buffer, info)
            }
            muxer.stop()
        } finally {
            muxer.release()
        }
        return existing
    }

    private companion object {
        const val SMALL_SAMPLE_COUNT = 5_000
        const val LARGE_SAMPLE_COUNT = 200_000
        const val INTERRUPT_AFTER_SAMPLES = 20_000
        const val INTERRUPT_ROUNDS = 4
        const val BYTES_PER_SAMPLE = 128
        const val WIDTH = 1920
        const val HEIGHT = 1080
        const val FRAME_RATE = 30
        const val FRAME_INTERVAL_MICROS = 33_333L
        const val AWAIT_SECONDS = 20L

        /** 取消时延预算：真机上"释放容器 + 扫描线程退出"必须在几十毫秒内完成，这里留足一个数量级。 */
        const val CANCEL_BUDGET_MILLIS = 500L

        /** 起始码 + IDR 的 NAL 头；`MediaMuxer` 只搬运这些字节，不解析它们。 */
        val IDR_PREFIX = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65)
        val SPS = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x0A, 0xF8.toByte(), 0x41, 0xA2.toByte())
        val PPS = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x38, 0x80.toByte())
    }
}
