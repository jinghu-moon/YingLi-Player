package seeyuer.yingli.player.data.recycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.common.AppLogEvent
import seeyuer.yingli.player.core.common.AppLogLevel
import seeyuer.yingli.player.core.common.AppLogger
import seeyuer.yingli.player.core.common.LogValue
import seeyuer.yingli.player.domain.recycle.ReconcileReport
import seeyuer.yingli.player.domain.recycle.TrashService

/**
 * 启动/进前台时的回收站维护（D3-C2：**不引入 WorkManager**）。
 *
 * 两件事按顺序做，且**先对账再清理**：
 * 1. [TrashService.reconcile]：把上次进程被杀时留下的半成品收敛到某个稳定态（§10.2）。
 * 2. [TrashService.cleanupExpired]：把已过期的条目物理删掉（§8.7 的「物理删除尽快」）。
 *
 * 顺序不能反：「到期」是在条目进入 `ACTIVE` 时才写下的，而对账有可能把条目**退回** `ACTIVE`；
 * 先清理就会漏掉这些刚被修好的条目，只能等下一次启动。
 *
 * **UI 必须告知用户「物理清理在下次打开应用时执行」（D7）**——这个类只是那句话的实现，
 * 不是「到期即刻删除」的承诺。
 */
class RecycleBinMaintenance(
    private val scope: CoroutineScope,
    private val trashService: TrashService,
    private val clock: AppClock,
    private val logger: AppLogger,
) : AutoCloseable {

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    /**
     * 跑一轮维护。**永不抛异常**：回收站维护失败不能把应用启动弄崩，
     * 也不能因为一次失败就认为「以后都不需要维护了」。
     */
    suspend fun run(): ReconcileReport {
        val reconcile = runCatching { trashService.reconcile() }.getOrElse { error ->
            log("RECYCLE_RECONCILE_FAILED", error)
            ReconcileReport.Empty
        }
        val cleanup = runCatching { trashService.cleanupExpired(clock.now().toEpochMilli()) }.getOrElse { error ->
            log("RECYCLE_CLEANUP_FAILED", error)
            ReconcileReport.Empty
        }
        if (reconcile != ReconcileReport.Empty || cleanup != ReconcileReport.Empty) {
            logger.log(
                AppLogLevel.INFO,
                AppLogEvent(
                    code = "RECYCLE_MAINTENANCE",
                    message = "recycle bin maintenance finished",
                    attributes = mapOf(
                        "repaired" to LogValue.Public((reconcile.repaired + cleanup.repaired).toString()),
                        // 「需要人工决定」的条目数必须出现在日志里：它们是唯一不会被自动收敛的状态。
                        "needsReview" to LogValue.Public((reconcile.needsReview + cleanup.needsReview).toString()),
                    ),
                ),
            )
        }
        return reconcile
    }

    override fun close() {
        job?.cancel()
        job = null
    }

    private fun log(code: String, error: Throwable) {
        logger.log(
            AppLogLevel.WARNING,
            AppLogEvent(
                code = code,
                message = "recycle bin maintenance failed",
                attributes = mapOf("failureType" to LogValue.Public(error.javaClass.simpleName)),
            ),
        )
    }
}
