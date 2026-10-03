package seeyuer.yingli.player.engine.media3.frame

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.app.AndroidLogSink
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.common.AppLogEvent
import seeyuer.yingli.player.core.common.AppLogLevel
import seeyuer.yingli.player.core.common.AppLogRecord
import seeyuer.yingli.player.core.common.AppLogSink
import seeyuer.yingli.player.core.common.LogValue
import seeyuer.yingli.player.core.common.RedactingAppLogger
import seeyuer.yingli.player.core.common.SensitiveValueRedactor
import seeyuer.yingli.player.domain.playback.ElapsedTimeSource
import seeyuer.yingli.player.domain.playback.FrameCalibrationResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 校准耗时模型的**真机实测取证**用例（不是性能门禁，是数据来源）。
 *
 * 为什么要单独一类：`estimatedFrameCalibrationMillis` 的系数只能来自实测，而既有的自造容器用例
 * （[AndroidFrameCountProbeTest]）只测"计数正确 + 取消能打断"，从不测"扫一份容器要多久"。
 * 于是模型在"样本极多"的容器上低估了 5~7 倍却一直没被发现（见 `docs/19` 的模型表）。
 *
 * 测量走**生产路径** `LocalMediaFrameCounter`（而不是裸探针）：生产日志里的 `elapsedMs`
 * 就是它记的，用同一条路径取证，模型对齐的才是用户真正在等的那段时间。
 *
 * 覆盖到的形态（每一项都对应模型里的一个自变量）：
 * 1. 常态容器（30fps、128B/样本，5000 样本）；
 * 2. 高帧率容器（3000fps 的 5 万 / 12 万 / 20 万样本、120fps 的 2 万样本）——模型低估的重灾区；
 * 3. 大字节/低样本容器（8KiB/样本，5000 样本）：样本数与 1 相同、字节数是它的 64 倍，
 *    两者耗时之差就是"容器 MiB"那一项的系数；
 * 4. 冷/热重复：同一容器连测 4 次，量化方差（`docs/19` 里同一容器 15.3s vs 1.3s 的疑点）；
 * 5. 后台写盘争抢：另起线程持续写盘，对照冷/热重复看方差能否被 IO 竞争解释。
 *
 * 断言只针对"测到的是有效数据"（容器被真正扫完、耗时为正），不对耗时下门限：
 * 真机抖动会让耗时门禁变成 flaky 门禁，而模型系数是人事后从这些数字拟合的。
 */
@RunWith(AndroidJUnit4::class)
class AndroidFrameScanCostMeasurementTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val records = Collections.synchronizedList(mutableListOf<AppLogRecord>())
    private val logger = RedactingAppLogger(
        redactor = SensitiveValueRedactor { it },
        // 同时落设备日志（`adb logcat -s YingLi:V`）与内存记录：离线分析用前者、断言用后者。
        sink = AppLogSink { record ->
            records += record
            AndroidLogSink().emit(record)
        },
    )
    private val elapsedTime = ElapsedTimeSource { SystemClock.elapsedRealtime() }

    @Test
    fun `normal frame rate container cost scales with sample count`() {
        val file = container(CONSTANT_RATE_SAMPLE_COUNT, FRAME_INTERVAL_30FPS_MICROS, NORMAL_FRAME_RATE, BYTES_PER_SMALL_SAMPLE)
        measureRepeatedly("NORMAL_FPS_5K_SAMPLES_128B", file, CONSTANT_RATE_SAMPLE_COUNT.toLong(), NORMAL_FRAME_RATE, REPEAT_ROUNDS)
    }

    @Test
    fun `high sample count container exposes the per sample cost`() {
        // 3000fps × 40s = 120000 样本（`docs/19` 里"同一容器实测 15.3s / 1.3s"的那一份规格）。
        // 只测 2 轮：单轮实测约 20s，再乘以轮数会把这份用例推到 instrumented 预算之外
        //（曾用 4 轮跑到 32 分钟被 framework 掐掉——每个容器在本类里都只重复到"够看方差"为止）。
        val file = container(HIGH_RATE_SAMPLE_COUNT, FRAME_INTERVAL_3000FPS_MICROS, HIGH_FRAME_RATE, BYTES_PER_SMALL_SAMPLE)
        measureRepeatedly("HIGH_FPS_120K_SAMPLES_128B", file, HIGH_RATE_SAMPLE_COUNT.toLong(), HIGH_FRAME_RATE, EXPENSIVE_ROUNDS)
    }

    @Test
    fun `moderate high frame rate container sits between the two`() {
        val file = container(MODERATE_RATE_SAMPLE_COUNT, FRAME_INTERVAL_120FPS_MICROS, MODERATE_FRAME_RATE, BYTES_PER_SMALL_SAMPLE)
        measureRepeatedly("HIGH_FPS_20K_SAMPLES_128B", file, MODERATE_RATE_SAMPLE_COUNT.toLong(), MODERATE_FRAME_RATE, REPEAT_ROUNDS)
    }

    @Test
    fun `much larger sample count keeps the growth trend visible`() {
        // 200000 样本：与 `AndroidFrameCountProbeTest` 的取消用例同规格（那份容器写完后立刻被扫，
        // `docs/19` 用"约 4 s"估过它的整扫耗时）。这一档只测 1 轮——单轮就有一两分钟，
        // 目的只是把曲线的最右端点钉住，方差已由其它档位给出。
        val file = container(VERY_HIGH_RATE_SAMPLE_COUNT, FRAME_INTERVAL_3000FPS_MICROS, HIGH_FRAME_RATE, BYTES_PER_SMALL_SAMPLE)
        measureRepeatedly(
            "HIGH_FPS_200K_SAMPLES_128B",
            file,
            VERY_HIGH_RATE_SAMPLE_COUNT.toLong(),
            HIGH_FRAME_RATE,
            EXPENSIVE_ROUNDS,
        )
    }

    @Test
    fun `mid sample count closes the gap between 20k and 120k`() {
        val file = container(MID_RATE_SAMPLE_COUNT, FRAME_INTERVAL_3000FPS_MICROS, HIGH_FRAME_RATE, BYTES_PER_SMALL_SAMPLE)
        measureRepeatedly("HIGH_FPS_50K_SAMPLES_128B", file, MID_RATE_SAMPLE_COUNT.toLong(), HIGH_FRAME_RATE, REPEAT_ROUNDS)
    }

    @Test
    fun `large sample container separates byte cost from sample cost`() {
        // 5000 样本 × 8KiB = 39 MiB：与第 1 个用例同样本数、字节数是它的 64 倍。
        val file = container(CONSTANT_RATE_SAMPLE_COUNT, FRAME_INTERVAL_30FPS_MICROS, NORMAL_FRAME_RATE, BYTES_PER_LARGE_SAMPLE)
        measureRepeatedly("NORMAL_FPS_5K_SAMPLES_8KIB", file, CONSTANT_RATE_SAMPLE_COUNT.toLong(), NORMAL_FRAME_RATE, REPEAT_ROUNDS)
    }

    /**
     * 后台 IO 争抢：另起线程持续写一个大文件，同时扫描同一目录下的容器。
     *
     * 目的是给 `docs/19` 里 15.3s vs 1.3s 的方差一个可复现的物理原因（设备后台写盘/刷盘与扫描抢 IO），
     * 而不是把差异笼统归给"真机抖动"。对照组就是 [measureRepeatedly] 里的冷/热重复。
     */
    @Test
    fun `scanning while another writer keeps the storage busy`() {
        val target = container(CONSTANT_RATE_SAMPLE_COUNT, FRAME_INTERVAL_30FPS_MICROS, NORMAL_FRAME_RATE, BYTES_PER_SMALL_SAMPLE)
        val noise = File(context.cacheDir, "frame-scan-cost-noise.bin")
        val running = AtomicBoolean(true)
        val writer = Thread {
            val block = ByteArray(NOISE_BLOCK_BYTES)
            while (running.get()) {
                // 每次重写同一批块：持续产生脏页回写压力，同时不让文件无限增长。
                try {
                    FileOutputStream(noise).use { stream ->
                        repeat(NOISE_BLOCKS) { stream.write(block) }
                        stream.fd.sync()
                    }
                } catch (_: IOException) {
                    // 背景噪声写入失败不影响扫描测量：这里没有要断言的产物。
                }
            }
        }
        writer.start()
        try {
            measureRepeatedly("WITH_BACKGROUND_WRITER", target, CONSTANT_RATE_SAMPLE_COUNT.toLong(), NORMAL_FRAME_RATE, CONTENTION_ROUNDS)
        } finally {
            running.set(false)
            writer.join(NOISE_STOP_TIMEOUT_MILLIS)
            noise.delete()
        }
    }

    /** 同一份容器连测 [rounds] 次（第 1 次是冷读、后续是热读），逐轮记日志并断言扫完整份容器。 */
    private fun measureRepeatedly(
        label: String,
        file: File,
        expectedSamples: Long,
        expectedFrameRate: Int,
        rounds: Int,
    ) {
        repeat(rounds) { round ->
            val counter = LocalMediaFrameCounter(AndroidFrameCountProbe(context), DirectIoDispatchers, logger, elapsedTime)
            val before = records.size
            try {
                counter.calibrate(file.absolutePath, "measurement-$label-$round")
                awaitTerminalResult(counter, "$label 第 ${round + 1} 轮")
                val result = counter.result.value
                assertTrue("$label 第 ${round + 1} 轮必须校准成功，实际 $result", result is FrameCalibrationResult.Calibrated)
                val calibration = (result as FrameCalibrationResult.Calibrated).calibration
                assertEquals("$label 第 ${round + 1} 轮没有扫完整份容器", expectedSamples, calibration.frameCount)
                // 实测帧率必须与容器如实声明的帧率同量级（自造容器是 CFR）；容差放到 5%，
                // 因为这份用例要证的是"耗时"，帧率只用来确认扫描没有提前退出。
                val measured = calibration.measuredFrameRate ?: 0f
                assertTrue(
                    "$label 第 ${round + 1} 轮实测帧率 ${measured} 与容器帧率 $expectedFrameRate 相差过大",
                    kotlin.math.abs(measured - expectedFrameRate) <= expectedFrameRate * 0.05f + 1f,
                )
                val scanned = records.drop(before).last { it.code == EVENT_SCANNED }
                val elapsedMillis = requireNotNull(scanned.attributes["elapsedMs"]).toLong()
                assertTrue("$label 第 ${round + 1} 轮必须留下正数耗时", elapsedMillis > 0L)
                logMeasurement(if (round == 0) "${label}_COLD" else "${label}_WARM$round", file, calibration.frameCount, elapsedMillis)
            } finally {
                // 结果流在 shutdown 时被清空，所以测量与断言都必须在它之前完成。
                counter.shutdown()
            }
        }
    }

    /** 等到生产路径给出终态（Calibrated / Failed / Skipped），超时即失败。 */
    private fun awaitTerminalResult(counter: LocalMediaFrameCounter, label: String) {
        val deadline = SystemClock.elapsedRealtime() + AWAIT_RESULT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            when (counter.result.value) {
                is FrameCalibrationResult.Calibrated,
                is FrameCalibrationResult.Failed,
                is FrameCalibrationResult.Skipped,
                -> return
                else -> Thread.sleep(POLL_MILLIS)
            }
        }
        throw AssertionError("$label：生产路径没有在 ${AWAIT_RESULT_MILLIS}ms 内给出终态")
    }

    /**
     * 记一条 `FRAME_SCAN_COST_MEASURED`：字段与生产事件 `FRAME_CALIBRATION_SCANNED` 对齐
     *（`bytes` / `samples` / `elapsedMs`），另加 `microsPerSample`，便于直接看"每样本成本"。
     */
    private fun logMeasurement(label: String, file: File, samples: Long, elapsedMillis: Long) {
        val attributes = linkedMapOf<String, LogValue>(
            "case" to LogValue.Public(label),
            "bytes" to LogValue.Public(file.length().toString()),
            "samples" to LogValue.Public(samples.toString()),
            "elapsedMs" to LogValue.Public(elapsedMillis.toString()),
        )
        if (samples > 0L && elapsedMillis > 0L) {
            attributes["microsPerSample"] =
                LogValue.Public(String.format(Locale.ROOT, "%.2f", elapsedMillis * 1_000.0 / samples))
        }
        logger.log(AppLogLevel.INFO, AppLogEvent(EVENT_COST, "Frame scan cost measurement.", attributes))
    }

    /**
     * 自造 MP4：样本数、帧间隔、帧率、每样本字节数都由测试控制（`MediaMuxer` 不校验样本内容，
     * 只把它们写进容器）。与 [AndroidFrameCountProbeTest] 里那份的差别就是这几个参数可调——
     * 耗时模型的自变量必须能被单独拉出来测量，否则拟合出来的只能是"看着差不多"的系数。
     */
    private fun container(sampleCount: Int, frameIntervalMicros: Long, frameRate: Int, bytesPerSample: Int): File {
        val file = File(context.cacheDir, "frame-scan-cost-$sampleCount-$bytesPerSample.mp4")
        if (file.isFile && file.length() > 0L) return file
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val trackIndex = muxer.addTrack(videoFormat(frameRate))
            muxer.start()
            val sample = ByteArray(bytesPerSample).also { bytes -> IDR_PREFIX.copyInto(bytes) }
            val buffer = ByteBuffer.allocate(bytesPerSample)
            val info = MediaCodec.BufferInfo()
            for (index in 0 until sampleCount) {
                buffer.clear()
                buffer.put(sample)
                buffer.flip()
                info.set(0, bytesPerSample, index * frameIntervalMicros, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer.writeSampleData(trackIndex, buffer, info)
            }
            muxer.stop()
        } finally {
            muxer.release()
        }
        return file
    }

    private fun videoFormat(frameRate: Int): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setByteBuffer("csd-0", ByteBuffer.wrap(SPS))
            setByteBuffer("csd-1", ByteBuffer.wrap(PPS))
        }

    private val sampleBytes: ByteArray
        get() = ByteArray(BYTES_PER_SMALL_SAMPLE).also { bytes -> IDR_PREFIX.copyInto(bytes) }

    /** 测量用例不需要线程切换：直接用真实调度器（生产用的也是 `Dispatchers.IO`）。 */    private object DirectIoDispatchers : AppDispatchers {
        override val main: CoroutineDispatcher = Dispatchers.Main
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    private companion object {
        const val EVENT_SCANNED = "FRAME_CALIBRATION_SCANNED"
        const val EVENT_COST = "FRAME_SCAN_COST_MEASURED"
        const val WIDTH = 1920
        const val HEIGHT = 1080
        const val NORMAL_FRAME_RATE = 30
        const val MODERATE_FRAME_RATE = 120
        const val HIGH_FRAME_RATE = 3000
        const val BYTES_PER_SMALL_SAMPLE = 128
        const val BYTES_PER_LARGE_SAMPLE = 8 * 1024
        const val CONSTANT_RATE_SAMPLE_COUNT = 5_000
        const val MODERATE_RATE_SAMPLE_COUNT = 20_000
        const val MID_RATE_SAMPLE_COUNT = 50_000
        const val HIGH_RATE_SAMPLE_COUNT = 120_000
        const val VERY_HIGH_RATE_SAMPLE_COUNT = 200_000
        const val FRAME_INTERVAL_30FPS_MICROS = 33_333L
        const val FRAME_INTERVAL_120FPS_MICROS = 8_333L
        const val FRAME_INTERVAL_3000FPS_MICROS = 333L
        const val REPEAT_ROUNDS = 4

        /** 单轮就要十几秒到一两分钟的容器：只重复到这个次数，避免整份用例超出 instrumented 预算。 */
        const val EXPENSIVE_ROUNDS = 1
        const val CONTENTION_ROUNDS = 3

        /** 背景噪声写入：每次重写 2 MiB（16 块 × 128 KiB），持续制造脏页回写压力。 */
        const val NOISE_BLOCK_BYTES = 128 * 1024
        const val NOISE_BLOCKS = 16
        const val NOISE_STOP_TIMEOUT_MILLIS = 10_000L

        /** 单轮等待上限：最慢的一档（20 万样本）整扫实测一到两分钟，这里留足余量。 */
        const val AWAIT_RESULT_MILLIS = 300_000L
        const val POLL_MILLIS = 20L

        /** 起始码 + IDR 的 NAL 头；`MediaMuxer` 只搬运这些字节，不解析它们。 */
        val IDR_PREFIX = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65)
        val SPS = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x0A, 0xF8.toByte(), 0x41, 0xA2.toByte())
        val PPS = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x38, 0x80.toByte())
    }
}
