package seeyuer.yingli.player.engine.media3.frame

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.domain.playback.FrameCalibrationControl
import seeyuer.yingli.player.domain.playback.FrameCalibrationResult
import seeyuer.yingli.player.domain.playback.FrameCountProbe
import seeyuer.yingli.player.domain.playback.MutableFrameScanCancellation
import seeyuer.yingli.player.domain.playback.calibrationOf
import seeyuer.yingli.player.domain.playback.canCalibrateFramesLocally

/**
 * 默认校准组件：把 [FrameCountProbe] 放到 IO 线程跑，并保证"可取消 + 结果只属于当前媒体"。
 *
 * 线程与生命周期：
 * - 扫描整段文件是 IO 密集且耗时随文件增长，必须在后台线程（[AppDispatchers.io]），UI 线程不做 IO；
 * - 取消是**协作式**的：`MediaExtractor` 没有中止入口，所以靠一个共享的取消标记，
 *   扫描循环每 240 个样本自查一次。单次 `advance()` 是微秒级，因此取消的实际时延在毫秒量级；
 * - 任务被取消后不会再写结果（协程在挂起点抛出 CancellationException），
 *   因此媒体切换、退出截图工具、页面销毁之后，过期任务不会覆盖新任务的状态。
 */
class LocalMediaFrameCounter(
    private val probe: FrameCountProbe,
    private val dispatchers: AppDispatchers,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.io),
) : FrameCalibrationControl, AutoCloseable {
    private val mutableResult = MutableStateFlow<FrameCalibrationResult?>(null)
    override val result: StateFlow<FrameCalibrationResult?> = mutableResult.asStateFlow()
    private var job: Job? = null
    private var cancellation: MutableFrameScanCancellation? = null

    override fun calibrate(uri: String, mediaId: String) {
        cancelRunningScan()
        // 网络源不校准：为了帧号去下载整段视频不可接受（见 canCalibrateFramesLocally）。
        if (!canCalibrateFramesLocally(uri)) {
            mutableResult.value = FrameCalibrationResult.Skipped(SKIPPED_REMOTE_SOURCE)
            return
        }
        mutableResult.value = FrameCalibrationResult.Calibrating
        val flag = MutableFrameScanCancellation()
        cancellation = flag
        job = scope.launch {
            val outcome = withContext(dispatchers.io) {
                runCatching { probe.probe(uri, flag) }
                    .fold(
                        onSuccess = { timeline ->
                            timeline?.let { FrameCalibrationResult.Calibrated(calibrationOf(it)) }
                                ?: FrameCalibrationResult.Failed(NO_VIDEO_TRACK_OR_UNREADABLE)
                        },
                        onFailure = { error ->
                            // 取消不是失败：结果已经没人要了，直接抛出去结束这个任务。
                            if (error is CancellationException) throw error
                            FrameCalibrationResult.Failed(error::class.java.simpleName)
                        },
                    )
            }
            mutableResult.value = outcome
        }
    }

    override fun close() {
        cancelRunningScan()
        mutableResult.value = null
    }

    /** 宿主销毁（应用结束/测试收尾）：连协程作用域一起收掉，避免校准线程活过宿主。 */
    fun shutdown() {
        close()
        scope.cancel()
    }

    private fun cancelRunningScan() {
        cancellation?.cancel()
        cancellation = null
        job?.cancel()
        job = null
    }

    private companion object {
        const val SKIPPED_REMOTE_SOURCE = "REMOTE_SOURCE"
        const val NO_VIDEO_TRACK_OR_UNREADABLE = "NO_VIDEO_TRACK"
    }
}
