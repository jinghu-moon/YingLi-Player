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
