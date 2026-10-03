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
    fun `cost model bounds every measured device scan`() {
        // 真机实测点（Android 16 / MIUI；完整表见 docs/19）。模型的口径分两侧：
        // - 实测 ≥ BOUNDED_FROM_MILLIS（用户真的要等）→ 必须满足 **预测 >= 实测**（上界，不许低估）；
        // - 更短的扫描 → 只要求与实测同量级（它连 600ms 的提示阈值都够不到）。
        // 这条断言取代了旧的"±6% 内、允许低估"的写法：旧写法正是"12 万样本被低估 9 倍"没被发现的原因。
        val measured = listOf(
            // 真实素材五点（docs/19，2026-10-03 前实测）
            MeasuredScan(durationMillis = 6_400, frameRate = 29f, fileSizeBytes = 3_757_807, elapsedMillis = 21),
            MeasuredScan(durationMillis = 41_000, frameRate = 30f, fileSizeBytes = 68_627_617, elapsedMillis = 104),
            MeasuredScan(durationMillis = 1_230_000, frameRate = 30f, fileSizeBytes = 1_961_021_547, elapsedMillis = 2_633),
            MeasuredScan(durationMillis = 1_192_000, frameRate = 30f, fileSizeBytes = 2_345_040_134, elapsedMillis = 2_751),
            MeasuredScan(durationMillis = 1_345_000, frameRate = 30f, fileSizeBytes = 2_645_424_630, elapsedMillis = 3_132),
            // 高帧率自造容器（本批真机实测，AndroidFrameScanCostMeasurementTest）：
            // 5 千 / 2 万 / 5 万 / 12 万 / 20 万样本，每样本成本随样本数上升，旧模型在后三档低估 4~15 倍。
            // 这里的 durationMillis × frameRate 就是容器的真实样本数（自造容器是 333µs/样本的 CFR）。
            MeasuredScan(durationMillis = 166_665, frameRate = 30f, fileSizeBytes = 680_829, elapsedMillis = 96),
            MeasuredScan(durationMillis = 166_665, frameRate = 120f, fileSizeBytes = 2_720_829, elapsedMillis = 756),
            MeasuredScan(durationMillis = 16_666, frameRate = 3_000f, fileSizeBytes = 6_800_829, elapsedMillis = 3_855),
            MeasuredScan(durationMillis = 40_000, frameRate = 3_000f, fileSizeBytes = 16_320_829, elapsedMillis = 19_748),
            MeasuredScan(durationMillis = 66_666, frameRate = 3_003f, fileSizeBytes = 27_200_829, elapsedMillis = 54_196),
        )

        measured.forEach { scan ->
            val predicted = estimatedFrameCalibrationMillis(scan.durationMillis, scan.frameRate, scan.fileSizeBytes)
            requireNotNull(predicted)
            if (scan.elapsedMillis >= BOUNDED_FROM_MILLIS) {
                // 用户要等的那一侧：**上界不允许低估**。旧模型在 12 万样本上给 2190ms（实测 19748ms），
                // 这条断言就是为了让那种低估再也不可能悄悄回来。
                assertTrue(
                    "模型预测 ${predicted}ms 低于实测 ${scan.elapsedMillis}ms：上界不允许低估",
                    predicted >= scan.elapsedMillis,
                )
            } else {
                // 1 秒以内的短扫描不参与"上界"判定：它连 600ms 的提示阈值都够不到（不会标 ≈），
                // 而实测显示旧线性项在这个区间比幂律项更准，所以模型在这侧取两者的 max。
                val ratio = predicted.toDouble() / scan.elapsedMillis
                assertTrue(
                    "短扫描（实测 ${scan.elapsedMillis}ms）预测 ${predicted}ms 偏离过大（比值 $ratio）",
                    ratio in 0.6..2.0,
                )
            }
        }

        // 真实素材上也不许变成"离谱的高估"：旧模型在这五点上误差 ≤6%，新模型的上界不允许比旧模型
        // 高出一个数量级——这是"真实文件不退化"的机器判据（docs/19 的对比表给出具体数字）。
        val realFileRatioBounds = listOf(
            // 实测 21ms：新模型 22ms（1.05 倍，走 max 的线性项）
            Triple(3_757_807L, 21L, 1.35),
            // 实测 104ms：新模型 103ms（0.99 倍，同样走线性项）
            Triple(68_627_617L, 104L, 1.35),
            // 实测 2633ms：新模型 4862ms（1.85 倍，已经交给幂律项；旧模型是 0.97 倍）
            Triple(1_961_021_547L, 2_633L, 2.2),
            // 实测 3132ms：新模型 6018ms（1.92 倍）
            Triple(2_645_424_630L, 3_132L, 2.2),
        )
        realFileRatioBounds.forEach { (bytes, elapsedMillis, maximumRatio) ->
            val predicted = requireNotNull(estimatedFrameCalibrationMillis(null, null, bytes)).toDouble()
            val ratio = predicted / elapsedMillis
            assertTrue(
                "只有字节数时预测 ${predicted}ms 相对实测 ${elapsedMillis}ms 的比值 $ratio 超出允许区间",
                ratio in 0.5..maximumRatio,
            )
        }

        // 自造容器那个反例：0.65 MiB 按带宽只要 1ms，实测 96ms —— 所以模型必须保留"按样本数"的那一项。
        val synthetic = estimatedFrameCalibrationMillis(durationMillis = 166_665, frameRate = 30f, fileSizeBytes = 680_829)
        assertEquals(106L, synthetic)
    }

    @Test
    fun `per sample cost grows with the sample count instead of staying constant`() {
        // 这条用例把"每样本成本不是常数"这件事钉住：旧模型假定 18µs 恒定，于是 12 万样本的容器
        // 被低估到 2.2s（实测 19.7s）。断言的是**同一份字节数尺度下**耗时的相对增长。
        val fiveThousand = requireNotNull(estimatedFrameCalibrationMillis(durationMillis = 166_665, frameRate = 30f, fileSizeBytes = 680_829))
        val twentyThousand = requireNotNull(estimatedFrameCalibrationMillis(durationMillis = 166_665, frameRate = 120f, fileSizeBytes = 2_720_829))
        val hundredTwentyThousand = requireNotNull(estimatedFrameCalibrationMillis(durationMillis = 40_000, frameRate = 3_000f, fileSizeBytes = 16_320_829))

        // 样本数 ×4：耗时至少 ×4（线性），实测是 ×7.9（756/96）。
        assertTrue("样本数 ×4 后耗时必须超过线性增长：$fiveThousand -> $twentyThousand", twentyThousand > fiveThousand * 4)
        // 样本数 ×24：耗时至少 ×24，实测是 ×205（19748/96）——幂律 1.72 次方给出约 ×240。
        assertTrue(
            "样本数 ×24 后耗时必须远超线性增长：$fiveThousand -> $hundredTwentyThousand",
            hundredTwentyThousand > fiveThousand * 24 * 4,
        )
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

    private companion object {
        /**
         * 从这一刻起的实测点必须被模型**上界**覆盖（预测 >= 实测）。
         * 取 3000ms：这远超 600ms 的提示阈值，是"用户确实在等"的一侧；
         * 更短的扫描只要求同量级，因为模型在那一侧刻意保留了实测更准的线性项（见模型 KDoc）。
         */
        const val BOUNDED_FROM_MILLIS = 3_000L
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
