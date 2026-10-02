package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧数统计（`countVideoFrames`）的行为用例：全部用假的样本源驱动，不需要 Android 运行时，
 * 因此"无视频轨 / 空轨 / 取消"这些边界都能真实跑一遍。
 */
class FrameCountProbeTest {
    @Test
    fun `counts every sample of the video track and reports the timeline span`() {
        val source = FakeSampleSource(
            mimeTypes = listOf("audio/mp4a-latm", "video/avc"),
            sampleCounts = listOf(0, 1_000),
            sampleIntervalMicros = 40_000,
        )

        val timeline = countVideoFrames(source, NeverCancelledFrameScan)

        assertEquals(1_000L, timeline?.frameCount)
        assertEquals(0L, timeline?.firstSampleTimeMicros)
        assertEquals(39_960_000L, timeline?.lastSampleTimeMicros)
        // 统计的是视频轨，不是第 0 轨（音轨）。
        assertEquals(listOf(1), source.selectedTracks)
    }

    @Test
    fun `audio only container has no frame count`() {
        val source = FakeSampleSource(
            mimeTypes = listOf("audio/mp4a-latm"),
            sampleCounts = listOf(500),
            sampleIntervalMicros = 20_000,
        )

        assertNull(countVideoFrames(source, NeverCancelledFrameScan))
        assertTrue(source.selectedTracks.isEmpty())
    }

    @Test
    fun `video track without samples is not a calibration result`() {
        // 选中了视频轨但一个样本都没有：没有总帧数可言，必须返回 null 而不是 0 帧。
        val source = FakeSampleSource(mimeTypes = listOf("video/hevc"), sampleCounts = listOf(0), sampleIntervalMicros = 33_000)

        assertNull(countVideoFrames(source, NeverCancelledFrameScan))
    }

    @Test
    fun `cancellation stops the scan instead of reporting a partial total`() {
        // 取消后必须返回 null：报一个"扫到一半"的帧数比不校准更糟。
        val source = FakeSampleSource(
            mimeTypes = listOf("video/avc"),
            sampleCounts = listOf(10_000),
            sampleIntervalMicros = 33_000,
        )
        val cancellation = MutableFrameScanCancellation()
        var scanned = 0
        val countingSource = object : ContainerSampleSource by source {
            override fun advance(): Boolean {
                scanned += 1
                // 240 个样本一个检查点：在第 480 个样本之后取消，循环应在那之后很快退出。
                if (scanned == 481) cancellation.cancel()
                return source.advance()
            }
        }

        assertNull(countVideoFrames(countingSource, cancellation))
        assertTrue("取消后仍在继续扫描：scanned=$scanned", scanned < 1_000)
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

        val timeline = countVideoFrames(source, NeverCancelledFrameScan)

        assertEquals(5_000_000L, timeline?.firstSampleTimeMicros)
        assertEquals(5_000_000L + 299 * 33_333L, timeline?.lastSampleTimeMicros)
        // 实测帧率由样本时间轴派生（33.333ms 间隔 ≈ 30fps），与容器时长无关。
        assertEquals(30f, timeline?.let(::measuredFrameRateOrNull) ?: 0f, 0.01f)
    }

    @Test
    fun `raw counting does not release the source`() {
        // countVideoFrames 只负责读：释放由探针在 finally 里做（见 AndroidFrameCountProbe）。
        val source = FakeSampleSource(mimeTypes = listOf("video/avc"), sampleCounts = listOf(10), sampleIntervalMicros = 33_000)

        countVideoFrames(source, NeverCancelledFrameScan)

        assertEquals(false, source.closed)
    }

    private class FakeSampleSource(
        private val mimeTypes: List<String>,
        private val sampleCounts: List<Int>,
        private val sampleIntervalMicros: Long,
        private val firstSampleTimeMicros: Long = 0,
        private val trackDurationMicros: Long? = null,
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
