package seeyuer.yingli.player.engine.media3.frame

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.common.AppLogRecord
import seeyuer.yingli.player.core.common.AppLogSink
import seeyuer.yingli.player.core.common.RedactingAppLogger
import seeyuer.yingli.player.core.common.SensitiveValueRedactor
import seeyuer.yingli.player.domain.playback.ElapsedTimeSource
import seeyuer.yingli.player.domain.playback.FrameCalibrationResult
import seeyuer.yingli.player.domain.playback.FrameCountProbe
import seeyuer.yingli.player.domain.playback.FrameScanLifecycle
import seeyuer.yingli.player.domain.playback.FrameScanReport
import seeyuer.yingli.player.domain.playback.VideoSampleTimeline
import seeyuer.yingli.player.testing.TestAppDispatchers

/**
 * 校准组件的行为用例：结果发布门、取消、shutdown 回收，以及①要求的"可测量日志"字段。
 *
 * 这些用例必须在**真实线程**上跑（假探针会阻塞），因为要测的正是"阻塞中的扫描被取消"，
 * 单线程调度器构造不出这个场景。
 */
class LocalMediaFrameCounterTest {
    @Test
    fun `scan publishes the calibration and logs the measured throughput`() {
        val records = Collections.synchronizedList(mutableListOf<AppLogRecord>())
        val sampleTimeline = timelineOf(frameCount = 300, lastMicros = 9_966_667)
        val counter = counter(
            probe = FrameCountProbe { _, _ -> FrameScanReport(sampleTimeline, 300L, cancelled = false, byteSize = 42L * 1024 * 1024) },
            records = records,
            // 42 MiB / 42ms = 1000 MiB/s：用整数关系写断言，避免用"看起来差不多"的数字糊过去。
            elapsedTime = SteppingElapsedTime(1_000L, 1_042L),
        )

        counter.calibrate(LOCAL_FILE_URI, "media-1")
        val published = awaitResult(counter) { it is FrameCalibrationResult.Calibrated }

        assertEquals(300L, (published as FrameCalibrationResult.Calibrated).calibration.frameCount)
        val record = records.last { it.code == "FRAME_CALIBRATION_SCANNED" }
        assertEquals("SCANNED", record.attributes["outcome"])
        assertEquals("300", record.attributes["samples"])
        assertEquals("42", record.attributes["elapsedMs"])
        assertEquals((42L * 1024 * 1024).toString(), record.attributes["bytes"])
        assertEquals("1000.0", record.attributes["throughputMiBPerSecond"])
        counter.shutdown()
    }

    @Test
    fun `cancelled scan logs the partial sample count and never publishes a result`() {
        val records = Collections.synchronizedList(mutableListOf<AppLogRecord>())
        val probe = BlockingProbe()
        val counter = counter(probe, records, ElapsedTimeSource { 0L })

        counter.calibrate(LOCAL_FILE_URI, "media-1")
        probe.awaitScanStarted()
        counter.close()
        probe.finish()

        val record = awaitRecord(records, "FRAME_CALIBRATION_SCANNED")
        assertEquals("CANCELLED", record.attributes["outcome"])
        assertEquals("137", record.attributes["samples"])
        // 取消标记确实到达了扫描（否则"不发布"只是因为别的原因碰巧成立）。
        assertTrue("扫描没有观察到取消", probe.sawCancellation())
        // 取消之后什么结果都不许写回（这里能写回的只有 Failed，所以必须仍是 null）。
        assertStaysWithoutResult(counter)
        counter.shutdown()
    }

    @Test
    fun `shutdown cancels the running scan and rejects further work`() {
        val records = Collections.synchronizedList(mutableListOf<AppLogRecord>())
        val probe = BlockingProbe()
        val counter = counter(probe, records, ElapsedTimeSource { 0L })

        counter.calibrate(LOCAL_FILE_URI, "media-1")
        probe.awaitScanStarted()
        counter.shutdown()
        probe.finish()

        awaitRecord(records, "FRAME_CALIBRATION_SCANNED")
        assertStaysWithoutResult(counter)

        // 容器退出之后组件不可再用：不接受新任务，也不会声明一个永远等不到结果的"校准中"。
        counter.calibrate(LOCAL_FILE_URI, "media-2")
        assertNull(counter.result.value)
        assertEquals(1, probe.calls.get())
    }

    @Test
    fun `shutdown is idempotent`() {
        val counter = counter(FrameCountProbe { _, _ -> null }, Collections.synchronizedList(mutableListOf()), ElapsedTimeSource { 0L })

        counter.shutdown()
        counter.shutdown()

        assertNull(counter.result.value)
    }

    @Test
    fun `scan that never started is discarded when the media changes`() {
        // 任务还排在调度器里就被换媒体：它一次都不许跑，更不许覆盖新媒体的状态。
        val scheduler = TestCoroutineScheduler()
        val probe = FrameCountProbe { _, _ -> FrameScanReport(timelineOf(300, 9_966_667), 300L, false, 1L) }
        val calls = AtomicInteger(0)
        val countingProbe = FrameCountProbe { uri, lifecycle ->
            calls.incrementAndGet()
            probe.probe(uri, lifecycle)
        }
        val counter = LocalMediaFrameCounter(
            probe = countingProbe,
            dispatchers = TestAppDispatchers(scheduler),
            logger = loggerFor(Collections.synchronizedList(mutableListOf())),
            elapsedTime = ElapsedTimeSource { 0L },
        )

        counter.calibrate(LOCAL_FILE_URI, "media-1")
        counter.calibrate(LOCAL_FILE_URI, "media-2")
        scheduler.runCurrent()

        // 被换掉的任务在启动前就被取消，因此只应该跑一次（第二次的），也只有它的结果被写回。
        assertEquals("被换掉的任务不许执行", 1, calls.get())
        val published = awaitResult(counter) { it is FrameCalibrationResult.Calibrated }
        assertEquals(300L, (published as FrameCalibrationResult.Calibrated).calibration.frameCount)
        counter.shutdown()
    }

    @Test
    fun `unreadable container becomes a failure with a timing log`() {
        val records = Collections.synchronizedList(mutableListOf<AppLogRecord>())
        val counter = counter(
            probe = FrameCountProbe { _, _ -> null },
            records = records,
            elapsedTime = SteppingElapsedTime(500L, 512L),
        )

        counter.calibrate(LOCAL_FILE_URI, "media-1")
        val published = awaitResult(counter) { it is FrameCalibrationResult.Failed }

        assertEquals("UNREADABLE_CONTAINER", (published as FrameCalibrationResult.Failed).reason)
        val record = records.last { it.code == "FRAME_CALIBRATION_FAILED" }
        assertEquals("FAILED", record.attributes["outcome"])
        assertEquals("12", record.attributes["elapsedMs"])
        assertEquals("UNREADABLE_CONTAINER", record.attributes["failureReason"])
        counter.shutdown()
    }

    @Test
    fun `container exception becomes a failure instead of crashing the host`() {
        val records = Collections.synchronizedList(mutableListOf<AppLogRecord>())
        val counter = counter(
            probe = FrameCountProbe { _, _ -> throw IllegalStateException("broken container") },
            records = records,
            elapsedTime = ElapsedTimeSource { 0L },
        )

        counter.calibrate(LOCAL_FILE_URI, "media-1")
        val published = awaitResult(counter) { it is FrameCalibrationResult.Failed }

        assertEquals("IllegalStateException", (published as FrameCalibrationResult.Failed).reason)
        assertEquals("IllegalStateException", records.last { it.code == "FRAME_CALIBRATION_FAILED" }.attributes["failureReason"])
        counter.shutdown()
    }

    @Test
    fun `network source is skipped without touching the container`() {
        val records = Collections.synchronizedList(mutableListOf<AppLogRecord>())
        val probe = BlockingProbe()
        val counter = counter(probe, records, ElapsedTimeSource { 0L })

        counter.calibrate("https://example.com/a.mp4", "media-1")

        assertEquals(FrameCalibrationResult.Skipped("REMOTE_SOURCE"), counter.result.value)
        assertEquals(0, probe.calls.get())
        assertEquals("FRAME_CALIBRATION_SKIPPED", records.last().code)
        counter.shutdown()
    }

    @Test
    fun `close before the scan starts leaves no result behind`() {
        val scheduler = TestCoroutineScheduler()
        val calls = AtomicInteger(0)
        val counter = LocalMediaFrameCounter(
            probe = FrameCountProbe { _, _ ->
                calls.incrementAndGet()
                FrameScanReport(timelineOf(300, 9_966_667), 300L, false, 1L)
            },
            dispatchers = TestAppDispatchers(scheduler),
            logger = loggerFor(Collections.synchronizedList(mutableListOf())),
            elapsedTime = ElapsedTimeSource { 0L },
        )

        counter.calibrate(LOCAL_FILE_URI, "media-1")
        counter.close()
        scheduler.runCurrent()

        assertNull(counter.result.value)
        assertEquals(0, calls.get())
        counter.shutdown()
    }

    private fun counter(
        probe: FrameCountProbe,
        records: MutableList<AppLogRecord>,
        elapsedTime: ElapsedTimeSource,
    ) = LocalMediaFrameCounter(
        probe = probe,
        dispatchers = RealIoDispatchers(),
        logger = loggerFor(records),
        elapsedTime = elapsedTime,
    )

    private fun loggerFor(records: MutableList<AppLogRecord>) = RedactingAppLogger(
        redactor = SensitiveValueRedactor { it },
        sink = AppLogSink { record -> records += record },
    )

    private fun awaitResult(
        counter: LocalMediaFrameCounter,
        predicate: (FrameCalibrationResult) -> Boolean,
    ): FrameCalibrationResult = runBlocking {
        requireNotNull(withTimeout(AWAIT_TIMEOUT_MILLIS) { counter.result.first { it != null && predicate(it) } })
    }

    /**
     * 断言"不会发生"只能有界地等：等到日志已经出现（发布判定就在它之后几步），
     * 再给一个足够覆盖两线程调度与写回的窗口，确认结果始终没有被写回。
     */
    private fun assertStaysWithoutResult(counter: LocalMediaFrameCounter) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(QUIET_WINDOW_MILLIS)
        while (System.nanoTime() < deadline) {
            assertNull("取消后仍写回了结果：${counter.result.value}", counter.result.value)
            Thread.sleep(QUIET_POLL_MILLIS)
        }
        assertNull("取消后仍写回了结果：${counter.result.value}", counter.result.value)
    }

    private fun awaitRecord(records: MutableList<AppLogRecord>, code: String): AppLogRecord {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_TIMEOUT_MILLIS)
        while (System.nanoTime() < deadline) {
            records.lastOrNull { it.code == code }?.let { return it }
            Thread.sleep(QUIET_POLL_MILLIS)
        }
        throw AssertionError("没有等到日志事件：$code（实际：${records.map(AppLogRecord::code)}）")
    }

    private fun timelineOf(frameCount: Long, lastMicros: Long) = VideoSampleTimeline(
        frameCount = frameCount,
        firstSampleTimeMicros = 0L,
        lastSampleTimeMicros = lastMicros,
        trackDurationMicros = null,
    )

    /** 假探针：模拟"阻塞在一次 native 调用里"，只有取消方能让它返回。 */
    private class BlockingProbe : FrameCountProbe {
        private val scanStarted = CountDownLatch(1)
        private val proceed = CountDownLatch(1)
        val calls = AtomicInteger(0)
        private val cancelledSeen = AtomicBoolean(false)

        override fun probe(uri: String, lifecycle: FrameScanLifecycle): FrameScanReport {
            calls.incrementAndGet()
            scanStarted.countDown()
            proceed.await(AWAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            cancelledSeen.set(lifecycle.isCancelled)
            return FrameScanReport(
                timeline = null,
                scannedSamples = 137L,
                cancelled = lifecycle.isCancelled,
                byteSize = 4L * 1024 * 1024,
            )
        }

        fun awaitScanStarted() = assertTrue("扫描没有开始", scanStarted.await(AWAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

        fun finish() = proceed.countDown()

        fun sawCancellation(): Boolean = cancelledSeen.get()
    }

    private class RealIoDispatchers : AppDispatchers {
        override val main: CoroutineDispatcher = Dispatchers.Default
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    /** 依次返回给定刻度，之后一直返回最后一个：让"耗时"在测试里完全确定。 */
    private class SteppingElapsedTime(private vararg val millis: Long) : ElapsedTimeSource {
        private val index = AtomicInteger(0)
        override fun nowMillis(): Long = millis[minOf(index.getAndIncrement(), millis.lastIndex)]
    }

    private companion object {
        const val LOCAL_FILE_URI = "file:///storage/emulated/0/Movies/sample.mp4"
        const val AWAIT_TIMEOUT_MILLIS = 5_000L
        const val QUIET_WINDOW_MILLIS = 300L
        const val QUIET_POLL_MILLIS = 10L
    }
}
