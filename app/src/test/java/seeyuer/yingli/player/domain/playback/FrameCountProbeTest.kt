package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧数统计（`countVideoFrames`）的行为用例：全部用假的样本源驱动，不需要 Android 运行时，
 * 因此"无视频轨 / 空轨 / 取消 / 被释放"这些边界都能真实跑一遍。
 */
class FrameCountProbeTest {
    @Test
    fun `counts every sample of the video track and reports the timeline span`() {
        val source = FakeSampleSource(
            mimeTypes = listOf("audio/mp4a-latm", "video/avc"),
            sampleCounts = listOf(0, 1_000),
            sampleIntervalMicros = 40_000,
            byteSize = 8_000_000L,
        )

        val report = countVideoFrames(source, NeverCancelledFrameScan)

        assertEquals(1_000L, report.timeline?.frameCount)
        assertEquals(0L, report.timeline?.firstSampleTimeMicros)
        assertEquals(39_960_000L, report.timeline?.lastSampleTimeMicros)
        // 统计的是视频轨，不是第 0 轨（音轨）。
        assertEquals(listOf(1), source.selectedTracks)
        // 报告里的扫描量与容器大小是耗时/吞吐日志的数据来源，必须一起带回来。
        assertEquals(1_000L, report.scannedSamples)
        assertEquals(8_000_000L, report.byteSize)
        assertFalse(report.cancelled)
    }

    @Test
    fun `audio only container has no frame count`() {
        val source = FakeSampleSource(
            mimeTypes = listOf("audio/mp4a-latm"),
            sampleCounts = listOf(500),
            sampleIntervalMicros = 20_000,
        )

        val report = countVideoFrames(source, NeverCancelledFrameScan)

        assertNull(report.timeline)
        assertFalse(report.cancelled)
        assertTrue(source.selectedTracks.isEmpty())
    }

    @Test
    fun `video track without samples is not a calibration result`() {
        // 选中了视频轨但一个样本都没有：没有总帧数可言，必须没有时间轴而不是 0 帧。
        val source = FakeSampleSource(mimeTypes = listOf("video/hevc"), sampleCounts = listOf(0), sampleIntervalMicros = 33_000)

        assertNull(countVideoFrames(source, NeverCancelledFrameScan).timeline)
    }

    @Test
    fun `cancellation stops the scan instead of reporting a partial total`() {
        // 取消后必须没有时间轴：报一个"扫到一半"的帧数比不校准更糟。
        val source = FakeSampleSource(
            mimeTypes = listOf("video/avc"),
            sampleCounts = listOf(10_000),
            sampleIntervalMicros = 33_000,
        )
        val lifecycle = FrameScanLifecycle()
        var scanned = 0
        val countingSource = object : ContainerSampleSource by source {
            override fun advance(): Boolean {
                scanned += 1
                // 每样本一个检查点：第 480 个样本之后取消，循环必须在下一次自查时就退出。
                if (scanned == 481) lifecycle.cancel()
                return source.advance()
            }
        }

        val report = countVideoFrames(countingSource, lifecycle)

        assertNull(report.timeline)
        assertTrue(report.cancelled)
        assertTrue("取消后仍在继续扫描：scanned=$scanned", scanned < 1_000)
    }

    @Test
    fun `interrupted native call is classified as cancelled instead of a failure`() {
        // 取消方从另一个线程释放了容器：阻塞中的 advance() 会以运行时异常退出。
        // 这个异常必须被归类为"已取消"（不是失败、更不许原样抛给上层当成容器损坏）。
        val source = FakeSampleSource(
            mimeTypes = listOf("video/avc"),
            sampleCounts = listOf(10_000),
            sampleIntervalMicros = 33_000,
        )
        val lifecycle = FrameScanLifecycle()
        var scanned = 0
        val countingSource = object : ContainerSampleSource by source {
            override fun advance(): Boolean {
                scanned += 1
                if (scanned == 500) {
                    lifecycle.cancel()
                    throw IllegalStateException("extractor has been released")
                }
                return source.advance()
            }
        }

        val report = countVideoFrames(countingSource, lifecycle)

        assertNull(report.timeline)
        assertTrue(report.cancelled)
        // 取消时也要带回"已经扫到哪了"，取消路径的日志才有量可查。
        assertEquals(500L, report.scannedSamples)
    }

    @Test
    fun `released container that simply ends the scan still cannot publish a partial timeline`() {
        // 释放并不保证 advance() 抛异常：也可能只是返回 false。此时循环"正常"结束，
        // 但取消已经发生——绝不能把这份残缺时间轴当成校准结果。
        val source = FakeSampleSource(
            mimeTypes = listOf("video/avc"),
            sampleCounts = listOf(10_000),
            sampleIntervalMicros = 33_000,
        )
        val lifecycle = FrameScanLifecycle()
        var scanned = 0
        val countingSource = object : ContainerSampleSource by source {
            override fun advance(): Boolean {
                scanned += 1
                if (scanned == 600) {
                    lifecycle.cancel()
                    return false
                }
                return source.advance()
            }
        }

        val report = countVideoFrames(countingSource, lifecycle)

        assertEquals(600L, report.scannedSamples)
        assertNull(report.timeline)
        assertTrue(report.cancelled)
    }

    @Test
    fun `container failure without cancellation is not swallowed`() {
        val source = FakeSampleSource(mimeTypes = listOf("video/avc"), sampleCounts = listOf(10), sampleIntervalMicros = 33_000)
        val brokenSource = object : ContainerSampleSource by source {
            override fun advance(): Boolean = throw IllegalStateException("broken container")
        }

        val failure = runCatching { countVideoFrames(brokenSource, NeverCancelledFrameScan) }.exceptionOrNull()

        assertTrue("未取消时的异常必须原样抛出：$failure", failure is IllegalStateException)
    }

    @Test
    fun `frame rate is derived from the sample span not from the container duration`() {
        // 首样本时间不是 0（前面有音频偏移）时，帧率派生用的是相对跨度。
        val source = FakeSampleSource(
            mimeTypes = listOf("video/avc"),
            sampleCounts = listOf(300),
            sampleIntervalMicros = 33_333,
            firstSampleTimeMicros = 5_000_000,
            trackDurationMicros = 20_000_000,
        )

        val timeline = countVideoFrames(source, NeverCancelledFrameScan).timeline

        assertEquals(5_000_000L, timeline?.firstSampleTimeMicros)
        assertEquals(5_000_000L + 299 * 33_333L, timeline?.lastSampleTimeMicros)
        // 实测帧率由样本时间轴派生（33.333ms 间隔 ≈ 30fps），与容器时长无关。
        assertEquals(30f, timeline?.let(::measuredFrameRateOrNull) ?: 0f, 0.01f)
    }

    @Test
    fun `raw counting does not release the source`() {
        // countVideoFrames 只负责读：释放由探针按状态机归属做（见 AndroidFrameCountProbe）。
        val source = FakeSampleSource(mimeTypes = listOf("video/avc"), sampleCounts = listOf(10), sampleIntervalMicros = 33_000)

        countVideoFrames(source, NeverCancelledFrameScan)

        assertFalse(source.closed)
    }

    private class FakeSampleSource(
        private val mimeTypes: List<String>,
        private val sampleCounts: List<Int>,
        private val sampleIntervalMicros: Long,
        private val firstSampleTimeMicros: Long = 0,
        private val trackDurationMicros: Long? = null,
        override val byteSize: Long? = null,
    ) : ContainerSampleSource {
        override val trackCount: Int get() = mimeTypes.size
        val selectedTracks = mutableListOf<Int>()
        var closed = false
        private var selected = -1
        private var sampleIndex = -1

        override fun trackMimeType(index: Int): String? = mimeTypes.getOrNull(index)

        override fun trackDurationMicros(index: Int): Long? = trackDurationMicros

        override fun selectTrack(index: Int): Boolean {
            if (index !in mimeTypes.indices) return false
            selected = index
            selectedTracks += index
            // 真实 MediaExtractor 在 selectTrack 之后 sampleTime 就是首个样本的时间，
            // 不需要先 advance()：假实现必须保持同一语义，否则测的不是真实调用顺序。
            sampleIndex = if ((sampleCounts.getOrNull(index) ?: 0) > 0) 0 else -1
            return true
        }

        override fun sampleTimeMicros(): Long {
            val count = sampleCounts.getOrNull(selected) ?: 0
            if (sampleIndex !in 0 until count) return -1
            return firstSampleTimeMicros + sampleIndex * sampleIntervalMicros
        }

        override fun advance(): Boolean {
            val count = sampleCounts.getOrNull(selected) ?: 0
            sampleIndex += 1
            return sampleIndex < count
        }
        override fun close() {
            closed = true
        }
    }
}
