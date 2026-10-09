package seeyuer.yingli.player.data.processing

import seeyuer.yingli.player.domain.processing.ProcessingEngine
import seeyuer.yingli.player.domain.processing.ProcessingEngineResult
import seeyuer.yingli.player.domain.processing.ProcessingOperation
import seeyuer.yingli.player.domain.processing.ProcessingPlan

/**
 * 按计划选出的动作把任务分派给对应引擎。
 *
 * **调用方不选引擎**：`ProcessingOperation` 由 planner 判定，执行器只把计划交给这里
 * （设计稿 §6.2「两者都实现同一个 `ProcessingEngine` 接口，由 planner 的 `operation` 字段路由」）。
 * 这样「压缩/格式转换/切片/AB 区间导出」四个产品入口共用一条执行路径，
 * 也保证不会出现「计划说是 REMUX，调用方却调了编码器」这种漂移。
 */
class RoutingProcessingEngine(
    private val remuxEngine: ProcessingEngine,
    private val transcodeEngine: ProcessingEngine,
) : ProcessingEngine {

    override suspend fun process(
        plan: ProcessingPlan,
        outputPath: String,
        onProgress: suspend (Float) -> Unit,
    ): ProcessingEngineResult = when (plan.operation) {
        ProcessingOperation.REMUX -> remuxEngine.process(plan, outputPath, onProgress)
        ProcessingOperation.TRANSCODE -> transcodeEngine.process(plan, outputPath, onProgress)
    }

    override suspend fun cancel() {
        // 两个引擎是同一个调度器里的互斥资源，取消必须两边都发：调用方不知道当前跑的是哪一个。
        remuxEngine.cancel()
        transcodeEngine.cancel()
    }
}
