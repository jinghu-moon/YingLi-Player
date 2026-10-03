package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCalibrationTest {
    @Test
    fun `frame rate is derived from the real sample timeline`() {
        // 300 个样本、跨 299 个间隔、首末相差 9_966_667us（10 秒 - 一帧）→ 约 30fps。
        val at30Fps = timelineOf(frameCount = 300, firstMicros = 0, lastMicros = 9_966_667, trackDurationMicros = 10_000_000)
        assertEquals(30f, measuredFrameRateOrNull(at30Fps) ?: 0f, 0.01f)

        // 29.97fps 同样能派生出接近 29.97 的值，而不是被容器字段的四舍五入抹平。
        val at2997 = timelineOf(frameCount = 300, firstMicros = 0, lastMicros = 9_976_407, trackDurationMicros = 10_010_000)
        assertEquals(29.97f, measuredFrameRateOrNull(at2997) ?: 0f, 0.01f)
    }

    @Test
    fun `single frame timeline cannot produce a frame rate`() {
        // 只有一个样本时没有"间隔"可言，除零必须被挡住。
        assertNull(
            measuredFrameRateOrNull(
                timelineOf(frameCount = 1, firstMicros = 500, lastMicros = 500, trackDurationMicros = 33_000),
            ),
        )
    }

    @Test
    fun `zero span timeline cannot produce a frame rate`() {
        // 时间戳全 0 的异常容器：帧数看起来正常，但时间跨度是 0。
        assertNull(
            measuredFrameRateOrNull(
                timelineOf(frameCount = 120, firstMicros = 0, lastMicros = 0, trackDurationMicros = 4_000_000),
            ),
        )
    }

    @Test
    fun `calibration keeps the real frame count and drops unusable rate`() {
        val usable = calibrationOf(
            timelineOf(frameCount = 900, firstMicros = 0, lastMicros = 29_966_667, trackDurationMicros = 30_000_000),
        )
        assertEquals(900L, usable.frameCount)
        assertEquals(30f, usable.measuredFrameRate ?: 0f, 0.01f)

        val unusable = calibrationOf(
            timelineOf(frameCount = 42, firstMicros = 7, lastMicros = 7, trackDurationMicros = 1_400_000),
        )
        assertEquals(42L, unusable.frameCount)
        assertNull(unusable.measuredFrameRate)
    }

    @Test
    fun `frame count must be positive`() {
        val failure = runCatching { FrameCalibration(frameCount = 0) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `only local file and content sources are calibratable`() {
        assertTrue(canCalibrateFramesLocally("content://media/external/video/media/42"))
        assertTrue(canCalibrateFramesLocally("file:///storage/emulated/0/Movies/a.mp4"))
        assertTrue(canCalibrateFramesLocally("/storage/emulated/0/Movies/a.mp4"))
    }

    @Test
    fun `network sources are never calibrated`() {
        // 网络源要统计 sample 数就得把整段下载一遍：为了一个帧号不值得，保持估算显示。
        assertFalse(canCalibrateFramesLocally("http://example.com/a.mp4"))
        assertFalse(canCalibrateFramesLocally("HTTPS://example.com/a.mp4"))
        assertFalse(canCalibrateFramesLocally("rtsp://example.com/a"))
        assertFalse(canCalibrateFramesLocally(""))
    }

    @Test
    fun `unknown schemes keep the estimate instead of guessing`() {
        // 自定义管道/vault：读取代价无法保证，宁可不校准。
        assertFalse(canCalibrateFramesLocally("vault://item-1"))
        assertEquals(LocalMediaUriKind.UNKNOWN, localMediaUriKind("vault://item-1"))
        assertEquals(LocalMediaUriKind.CONTENT, localMediaUriKind("content://media/1"))
        assertEquals(LocalMediaUriKind.FILE, localMediaUriKind("file:///tmp/a.mp4"))
        assertEquals(LocalMediaUriKind.REMOTE, localMediaUriKind("https://example.com/a.mp4"))
    }

    @Test
    fun `cost model reproduces every measured device scan`() {
        // 真机实测点（Android 16 / MIUI；表见 docs/19）。模型系数就是从这些点拟合出来的，
        // 所以这条测试的作用是：任何人调系数都必须重新解释这些数字，不能悄悄改坏。
        val measured = listOf(
            MeasuredScan(durationMillis = 6_400, frameRate = 29f, fileSizeBytes = 3_757_807, elapsedMillis = 21),
            MeasuredScan(durationMillis = 41_000, frameRate = 30f, fileSizeBytes = 68_627_617, elapsedMillis = 104),
            MeasuredScan(durationMillis = 1_230_000, frameRate = 30f, fileSizeBytes = 1_961_021_547, elapsedMillis = 2_633),
            MeasuredScan(durationMillis = 1_192_000, frameRate = 30f, fileSizeBytes = 2_345_040_134, elapsedMillis = 2_751),
            MeasuredScan(durationMillis = 1_345_000, frameRate = 30f, fileSizeBytes = 2_645_424_630, elapsedMillis = 3_132),
        )

        measured.forEach { scan ->
            val predicted = estimatedFrameCalibrationMillis(scan.durationMillis, scan.frameRate, scan.fileSizeBytes)
            requireNotNull(predicted)
            val ratio = predicted.toDouble() / scan.elapsedMillis
            assertTrue(
                "模型预测 ${predicted}ms 与实测 ${scan.elapsedMillis}ms 偏差过大（比值 $ratio）",
                ratio in 0.65..1.35,
            )
        }

        // 自造容器那个反例：0.65 MiB 按带宽只要 1ms，实测 102ms —— 所以模型必须保留"按样本数"的那一项。
        val synthetic = estimatedFrameCalibrationMillis(durationMillis = 166_665, frameRate = 30f, fileSizeBytes = 680_829)
        assertEquals(105L, synthetic)
    }

    @Test
    fun `only scans long enough to be noticed ask for a calibration hint`() {
        // 66 MiB / 41s：实测 104ms，比胶囊入场动画（360ms）还快 → 不提示。
        assertFalse(frameCalibrationNoticeRequired(durationMillis = 41_000, frameRate = 30f, fileSizeBytes = 68_627_617))
        // 2.46 GiB / 22:25：实测 3.1s，用户确实在等 → 提示。
        assertTrue(frameCalibrationNoticeRequired(durationMillis = 1_345_000, frameRate = 30f, fileSizeBytes = 2_645_424_630))
        // 只看体积会漏掉的形态：容器不大但样本极多（40 分钟 60fps ≈ 14.4 万样本）。
        // 模型给出 15 + 2592 + 300 ≈ 2.9s，属于"必须提示"。
        assertTrue(frameCalibrationNoticeRequired(durationMillis = 2_400_000, frameRate = 60f, fileSizeBytes = 300L * 1024 * 1024))
        // 反方向：样本少但字节多（高码率低帧率），同样由字节项兜住。
        assertTrue(frameCalibrationNoticeRequired(durationMillis = null, frameRate = null, fileSizeBytes = 1L * 1024 * 1024 * 1024))
        // 什么都拿不到：不猜、不打扰，校准完成后照样是精确值。
        assertFalse(frameCalibrationNoticeRequired(durationMillis = null, frameRate = null, fileSizeBytes = null))
        assertFalse(frameCalibrationNoticeRequired(durationMillis = 0L, frameRate = 0f, fileSizeBytes = null))
    }

    private data class MeasuredScan(
        val durationMillis: Long,
        val frameRate: Float,
        val fileSizeBytes: Long,
        val elapsedMillis: Long,
    )

    private fun timelineOf(
        frameCount: Long,
        firstMicros: Long,
        lastMicros: Long,
        trackDurationMicros: Long?,
    ) = VideoSampleTimeline(
        frameCount = frameCount,
        firstSampleTimeMicros = firstMicros,
        lastSampleTimeMicros = lastMicros,
        trackDurationMicros = trackDurationMicros,
    )
}
