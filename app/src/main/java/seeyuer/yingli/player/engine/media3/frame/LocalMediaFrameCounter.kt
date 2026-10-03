package seeyuer.yingli.player.engine.media3.frame

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.common.AppLogEvent
import seeyuer.yingli.player.core.common.AppLogLevel
import seeyuer.yingli.player.core.common.AppLogger
import seeyuer.yingli.player.core.common.LogValue
import seeyuer.yingli.player.domain.playback.ElapsedTimeSource
import seeyuer.yingli.player.domain.playback.FrameCalibrationControl
import seeyuer.yingli.player.domain.playback.FrameCalibrationResult
import seeyuer.yingli.player.domain.playback.FrameCountProbe
import seeyuer.yingli.player.domain.playback.FrameScanLifecycle
import seeyuer.yingli.player.domain.playback.FrameScanReport
import seeyuer.yingli.player.domain.playback.calibrationOf
import seeyuer.yingli.player.domain.playback.canCalibrateFramesLocally

/**
 * 默认校准组件：把 [FrameCountProbe] 放到 IO 线程跑，并保证"可中断取消 + 结果只属于当前任务"。
 *
 * ## 线程与作用域归属
 *
 * 组件**自己持有**协程作用域（不接收外部作用域）：它的生命周期就是"容器/进程的生命周期"，
 * 这样 [shutdown] 才能连作用域一起回收，而不是把回收责任推给调用方。
 *
 * ## 取消
 *
 * 取消分两层（见 [FrameScanLifecycle]）：扫描循环里的标记自查是兜底，真正把阻塞中的
 * `MediaExtractor.advance()` 打断的是"从取消线程释放容器"。释放动作在这里的调用线程上**同步**执行，
 * 因此 `cancel` 一返回容器就已归还——顺序不能改成"丢给作用域异步回收"：宿主作用域紧接着被取消时，
 * 回收任务会跟着消失，容器就泄漏了。
 *
 * 协程取消（`job.cancel()`）与上面这层是**两回事**：它只保证"还没开始的任务不执行"，
 * 打不断已经在跑的 native 调用，因此扫描的收尾（日志 + 发布判定）跑在 [NonCancellable] 上，
 * 见 [runScan]。真正的停止点始终是"释放容器"。
 *
 * ## 结果发布
 *
 * 写回结果只有一条路径 [publishResult]：先确认"仍未被取消"，再在锁内确认"仍是当前任务"。
 * 判定与写入合成一次原子操作，是因为单纯"先判断后写回"会留下一个窗口——
 * 换媒体/退出截图正好踩中时，上一份媒体的帧数会被写进当前会话。
 */
class LocalMediaFrameCounter(
    private val probe: FrameCountProbe,
    private val dispatchers: AppDispatchers,
    private val logger: AppLogger,
    private val elapsedTime: ElapsedTimeSource,
) : FrameCalibrationControl {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private val mutableResult = MutableStateFlow<FrameCalibrationResult?>(null)
    override val result: StateFlow<FrameCalibrationResult?> = mutableResult.asStateFlow()

    /**
     * 保护"当前任务是谁"和"结果写回"这两件必须一起判定的事。锁内不做任何阻塞/native 调用：
     * 真正释放容器在 [cancelRunningScan] 的锁外执行。
     */
    private val publishLock = Any()
    private var publishToken = 0L
    private var activeLifecycle: FrameScanLifecycle? = null
    private var activeJob: Job? = null

    /**
     * 终态标记（[shutdown] 之后为 true）。组件被回收后不再接受新任务：
     * 否则会声明一个永远等不到结果的 `Calibrating`，界面上就是"一直校准中"。
     */
    @Volatile
    private var terminated = false

    override fun calibrate(uri: String, mediaId: String) {
        if (terminated) return
        cancelRunningScan()
        // 网络源不校准：为了帧号去下载整段视频不可接受（见 canCalibrateFramesLocally）。
        if (!canCalibrateFramesLocally(uri)) {
            logger.log(
                AppLogLevel.INFO,
                AppLogEvent(
                    EVENT_SKIPPED,
                    "Frame calibration was skipped.",
                    mapOf("reason" to LogValue.Public(SKIPPED_REMOTE_SOURCE)),
                ),
            )
            synchronized(publishLock) { mutableResult.value = FrameCalibrationResult.Skipped(SKIPPED_REMOTE_SOURCE) }
            return
        }
        val lifecycle = FrameScanLifecycle()
        synchronized(publishLock) {
            publishToken += 1L
            val token = publishToken
            activeLifecycle = lifecycle
            mutableResult.value = FrameCalibrationResult.Calibrating
            activeJob = scope.launch { runScan(uri, lifecycle, token) }
        }
    }

    override fun close() {
        // 只作废当前任务：组件之后还能继续用（换媒体后再 calibrate 即可）。
        cancelRunningScan()
        synchronized(publishLock) { mutableResult.value = null }
    }

    /**
     * 组件级终止（容器/进程退出）：先进入终态、取消在跑的扫描并归还容器，再收掉自己的作用域。
     * 顺序不能反——先 `scope.cancel()` 的话，在跑的扫描连"释放容器"这一步都做不完。
     */
    override fun shutdown() {
        if (terminated) return
        terminated = true
        close()
        scope.cancel()
    }

    /**
     * 一次扫描的全部动作：跑探针、记证据、按门写回。
     *
     * 整段跑在 `NonCancellable + IO` 上，**不随协程取消而中止**，理由有两条：
     * 1. 取消是由 [FrameScanLifecycle]（释放容器）送达的：协作式协程取消本来就打不断阻塞中的 native 调用，
     *    所以"协程取消"在这里只负责"还没开始的任务不执行"，不负责中止已经在跑的扫描；
     * 2. 取消之后恰恰**最需要**留下这次扫描的耗时/吞吐/是否取消——那段证据不能被取消吃掉。
     * 结果写回另有"未被取消 + 仍是当前任务"两道门（见 [publishResult]），所以这里不会变成"取消后仍写回结果"。
     */
    private suspend fun runScan(uri: String, lifecycle: FrameScanLifecycle, token: Long) {
        withContext(NonCancellable + dispatchers.io) {
            val startedAtMillis = elapsedTime.nowMillis()
            val attempt = try {
                ScanAttempt(
                    report = probe.probe(uri, lifecycle),
                    failureReason = null,
                    elapsedMillis = elapsedTime.nowMillis() - startedAtMillis,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // 容器解析失败等：归类成 Failed 并如实记录耗时（不静默吞掉，日志里带 failureReason）。
                ScanAttempt(
                    report = null,
                    failureReason = error::class.java.simpleName,
                    elapsedMillis = elapsedTime.nowMillis() - startedAtMillis,
                )
            }
            logScan(attempt)
            val report = attempt.report
            if (report?.cancelled == true) {
                // 取消不是失败：宿主已经不要这个结果了（换媒体/退出截图/关闭容器），一个字段都不许写回。
                return@withContext
            }
            val outcome = when {
                report == null -> FrameCalibrationResult.Failed(attempt.failureReason ?: UNREADABLE_CONTAINER)
                report.timeline != null -> FrameCalibrationResult.Calibrated(calibrationOf(report.timeline))
                else -> FrameCalibrationResult.Failed(NO_VIDEO_TRACK_OR_UNREADABLE)
            }
            publishResult(token, lifecycle, outcome)
        }
    }

    /**
     * 写回结果的唯一入口：先确认"没被取消"，再在锁内确认"仍是当前任务"，然后才写。
     * 两道门都要有：取消说明这次扫描本身作废；token 说明这次任务已经被更新的任务取代。
     */
    private fun publishResult(token: Long, lifecycle: FrameScanLifecycle, outcome: FrameCalibrationResult) {
        if (!lifecycle.canPublishResult()) return
        synchronized(publishLock) {
            if (token != publishToken) return
            mutableResult.value = outcome
        }
    }

    /**
     * 作废旧任务并把"释放容器"的义务交给它的取消赢家。
     * 锁外执行 [FrameScanLifecycle.cancel]：它会同步调用 `MediaExtractor.release()`。
     */
    private fun cancelRunningScan() {
        val (lifecycle, job) = synchronized(publishLock) {
            // token 自增即作废旧任务的发布权：它随后即使跑到写回那一步也发布不出去。
            publishToken += 1L
            val running = activeLifecycle to activeJob
            activeLifecycle = null
            activeJob = null
            running
        }
        lifecycle?.cancel()
        job?.cancel()
    }

    /**
     * 扫描耗时/吞吐日志：策略（静默后台校准 / 显示进度 / 允许取消）只能由实测吞吐决定，
     * 所以扫描路径必须自己产出 **文件大小 + 样本数 + 耗时 + 派生吞吐 + 是否取消**。
     *
     * 走 [AppLogger] 而不是 `android.util.Log`：项目里日志统一有事件码与脱敏口径，
     * 真机上 `adb logcat -s YingLi:I` 就能取到（本机环境与取证方式见 `docs/19`）。
     * 不记录路径/文件名：那属于可识别信息，且日志字段里 `bytes` 已经足够对应到具体文件。
     */
    private fun logScan(attempt: ScanAttempt) {
        val report = attempt.report
        if (report == null) {
            logger.log(
                AppLogLevel.WARNING,
                AppLogEvent(
                    EVENT_FAILED,
                    "Frame calibration could not scan the container.",
                    mapOf(
                        "outcome" to LogValue.Public("FAILED"),
                        "elapsedMs" to LogValue.Public(attempt.elapsedMillis.toString()),
                        "failureReason" to LogValue.Public(attempt.failureReason ?: UNREADABLE_CONTAINER),
                    ),
                ),
            )
            return
        }
        val attributes = linkedMapOf<String, LogValue>(
            "outcome" to LogValue.Public(if (report.cancelled) "CANCELLED" else "SCANNED"),
            "samples" to LogValue.Public(report.scannedSamples.toString()),
            "elapsedMs" to LogValue.Public(attempt.elapsedMillis.toString()),
        )
        report.byteSize?.let { attributes["bytes"] = LogValue.Public(it.toString()) }
        throughputMibPerSecond(report.byteSize, attempt.elapsedMillis)?.let {
            attributes["throughputMiBPerSecond"] = LogValue.Public(it)
        }
        logger.log(AppLogLevel.INFO, AppLogEvent(EVENT_SCANNED, "Frame calibration container scan finished.", attributes))
    }

    /**
     * 派生吞吐（MiB/s）。耗时为 0ms（同一次时钟刻度内完成）时不派生：那只会得到一个假的天文数字。
     */
    private fun throughputMibPerSecond(byteSize: Long?, elapsedMillis: Long): String? {
        if (byteSize == null || elapsedMillis <= 0L) return null
        val mibPerSecond = byteSize.toDouble() / BYTES_PER_MIB / (elapsedMillis / MILLIS_PER_SECOND)
        return String.format(Locale.ROOT, "%.1f", mibPerSecond)
    }

    private data class ScanAttempt(
        val report: FrameScanReport?,
        val failureReason: String?,
        val elapsedMillis: Long,
    )

    private companion object {
        const val EVENT_SCANNED = "FRAME_CALIBRATION_SCANNED"
        const val EVENT_FAILED = "FRAME_CALIBRATION_FAILED"
        const val EVENT_SKIPPED = "FRAME_CALIBRATION_SKIPPED"
        const val SKIPPED_REMOTE_SOURCE = "REMOTE_SOURCE"
        const val NO_VIDEO_TRACK_OR_UNREADABLE = "NO_VIDEO_TRACK"

        /** 探针返回 null：URI 打不开（无权限/文件不存在/容器不识别）。 */
        const val UNREADABLE_CONTAINER = "UNREADABLE_CONTAINER"
        const val BYTES_PER_MIB = 1024.0 * 1024.0
        const val MILLIS_PER_SECOND = 1000.0
    }
}
