package seeyuer.yingli.player.feature.player

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import seeyuer.yingli.player.app.playback.PlaybackSessionClientBridge
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.playback.DISPLAY_POSITION_TICK_MILLIS
import seeyuer.yingli.player.domain.playback.PlaybackCommandResult
import seeyuer.yingli.player.domain.playback.PlaybackConnectionState
import seeyuer.yingli.player.domain.playback.PlaybackController
import seeyuer.yingli.player.domain.playback.PlaybackRequest
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackSourceRepository
import seeyuer.yingli.player.domain.playback.PlaybackState
import seeyuer.yingli.player.domain.playback.PlaybackTimeline
import seeyuer.yingli.player.domain.playback.ResolvedPlaybackSource
import seeyuer.yingli.player.testing.MainDispatcherRule

/**
 * **P1 显示层位置冻结的回归**（JVM 侧）。
 *
 * 现场：稳定播放期间 `PlayerUiState.displayedPositionMillis` 完全不更新 —— 进度行停在
 * `00:00 / 01:35`，三次 dump 都是同一个值。根因是它读的是会话快照的 `timeline.positionMillis`，
 * 而快照只在引擎状态跳变时刷新；阶段 1/2 修"设点用陈旧位置"时改了 11 处决策点，却把展示层的
 * 位置推进（原来的 UI ticker）一起删掉了。
 *
 * 这里钉住四件事：① 播放中位置必须**持续推进**；② 每拍只读一次实时位置；
 * ③ 暂停后必须**停止推进且不再读取**（不空转）；④ 恢复播放立即读，不等满一拍。
 *
 * 测试自身的 `Dispatchers.Main` 与 ViewModel 用的是**同一个 `StandardTestDispatcher`**
 * （同一个 `testScheduler`），展示层 tick 才能被 `advanceTimeBy` 真实推进；断言一律用
 * [awaitState]（有界等待）而不是"跑一拍就查状态"，避免把测试写成对调度顺序的假设。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerDisplayPositionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `playing advances the displayed position instead of freezing on the snapshot`() = runTest {
        val controller = SteppingPlaybackController()
        val viewModel = viewModelOf(controller, PHASE_PLAYING)
        val collector = backgroundScope.launch { viewModel.state.collect { } }
        runCurrent()

        // 快照停在 0（稳定播放期间引擎不重发状态），实时位置已经走到 5 秒。
        controller.position = 5_000
        advanceTimeBy(DISPLAY_POSITION_TICK_MILLIS)
        awaitState(viewModel) { it.displayedPositionMillis == 5_000L }

        assertEquals(5_000L, viewModel.state.value.playback.timeline.positionMillis)

        // 再走远一点：展示值必须跟着实时位置继续推进，而不是停在上一次读数上。
        controller.position = 7_500
        advanceTimeBy(DISPLAY_POSITION_TICK_MILLIS)
        awaitState(viewModel) { it.displayedPositionMillis == 7_500L }
        collector.cancel()
    }

    @Test
    fun `the display projection reads the live position once per tick`() = runTest {
        val controller = SteppingPlaybackController()
        val viewModel = viewModelOf(controller, PHASE_PLAYING)
        val collector = backgroundScope.launch { viewModel.state.collect { } }
        runCurrent()
        awaitState(viewModel) { controller.positionReads > 0 }

        val reads = controller.positionReads
        advanceTimeBy(DISPLAY_POSITION_TICK_MILLIS * 3)
        runCurrent()

        assertEquals("三拍应当只读三次", reads + 3, controller.positionReads)
        collector.cancel()
    }

    @Test
    fun `pausing stops the display projection instead of letting it spin`() = runTest {
        val controller = SteppingPlaybackController()
        val viewModel = viewModelOf(controller, PHASE_PLAYING)
        val collector = backgroundScope.launch { viewModel.state.collect { } }
        runCurrent()
        controller.position = 5_000
        advanceTimeBy(DISPLAY_POSITION_TICK_MILLIS)
        awaitState(viewModel) { it.displayedPositionMillis == 5_000L }

        // 暂停：快照相位切到 Paused，引擎在跳变里带上真实位置（真实引擎就是这样）。
        controller.position = 6_000
        controller.publish(PlaybackState.Paused(REQUEST, PlaybackTimeline(6_000, DURATION_MILLIS)))

        // 展示值必须停到 6 秒，并且**再也不推进**。
        awaitState(viewModel) { viewModel.state.value.displayedPositionMillis == 6_000L }
        val readsAtPause = controller.positionReads
        advanceTimeBy(DISPLAY_POSITION_TICK_MILLIS * 8)
        runCurrent()

        assertEquals(6_000L, viewModel.state.value.displayedPositionMillis)
        assertEquals("暂停后不得继续按 tick 读取", readsAtPause, controller.positionReads)
        collector.cancel()
    }

    @Test
    fun `resuming reads the live position immediately instead of waiting a full tick`() = runTest {
        val controller = SteppingPlaybackController()
        val viewModel = viewModelOf(controller, PlaybackState.Paused(REQUEST, PlaybackTimeline(1_000, DURATION_MILLIS)))
        val collector = backgroundScope.launch { viewModel.state.collect { } }
        runCurrent()

        controller.position = 20_000
        controller.publish(PlaybackState.Playing(REQUEST, PlaybackTimeline(1_000, DURATION_MILLIS)))

        // 不推进任何虚拟时间：恢复播放的第一拍就必须读到实时位置。
        awaitState(viewModel) { it.displayedPositionMillis == 20_000L }
        collector.cancel()
    }

    @Test
    fun `an idle session never reads the live position`() = runTest {
        val controller = SteppingPlaybackController()
        val viewModel = viewModelOf(controller, PlaybackState.Idle)
        val collector = backgroundScope.launch { viewModel.state.collect { } }
        runCurrent()
        advanceTimeBy(DISPLAY_POSITION_TICK_MILLIS * 10)
        runCurrent()

        assertEquals(0, controller.positionReads)
        assertEquals(0L, viewModel.state.value.displayedPositionMillis)
        collector.cancel()
    }

    /**
     * 展示层的 tick 不会把位置缓存住：它不插值、不预测，因此位置变了以后最多**一个 tick** 内
     * 就必须反映出来（这条同时说明决策点若直接问实时位置，不会被展示层拖慢）。
     */
    @Test
    fun `the display position never lags more than one tick behind the live position`() = runTest {
        val controller = SteppingPlaybackController()
        val viewModel = viewModelOf(controller, PHASE_PLAYING)
        val collector = backgroundScope.launch { viewModel.state.collect { } }
        runCurrent()

        controller.position = 42_000
        advanceTimeBy(DISPLAY_POSITION_TICK_MILLIS)
        awaitState(viewModel) { it.displayedPositionMillis == 42_000L }

        controller.position = 43_000
        // 同一拍内：展示值不会自己往前走（没有插值/预测）。
        assertEquals(42_000L, viewModel.state.value.displayedPositionMillis)
        // 下一拍必须拿到新值。
        advanceTimeBy(DISPLAY_POSITION_TICK_MILLIS)
        awaitState(viewModel) { it.displayedPositionMillis == 43_000L }
        collector.cancel()
    }

    // ---- 现场装配 ----

    /**
     * 有界等待状态满足条件。
     *
     * 为什么不是"跑一拍再读 `state.value`"：`combine` 把位置与快照合成 UI 状态，中间隔着几次
     * 调度；把断言写成对调度顺序的假设，就会变成"改一处无关的算子顺序就红"的脆弱测试。
     */
    private suspend fun TestScope.awaitState(
        viewModel: PlayerViewModel,
        predicate: (PlayerUiState) -> Boolean,
    ): PlayerUiState = withTimeout(5_000) {
        viewModel.state.first(predicate).also { testScheduler.runCurrent() }
    }

    private fun viewModelOf(
        controller: SteppingPlaybackController,
        initial: PlaybackState,
    ): PlayerViewModel {
        controller.publish(initial)
        val dispatchers = TestDispatchers(mainDispatcherRule.dispatcher)
        val bridge = PlaybackSessionClientBridge(
            controller = controller,
            sourceRepository = UnusedSourceRepository,
            dispatchers = dispatchers,
        )
        return PlayerViewModel(sessionClient = bridge, dispatchers = dispatchers)
    }

    private class TestDispatchers(private val dispatcher: CoroutineDispatcher) : AppDispatchers {
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
    }

    /**
     * 播放位置与播放状态**分开驱动**的假控制器：`publish` 只发布状态跳变（真实引擎的行为），
     * `position` 单独推进 —— 这正是"快照陈旧、实时位置已经走远"能同时成立的原因。
     */
    private class SteppingPlaybackController : PlaybackController {
        private val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val state: StateFlow<PlaybackState> = mutableState
        override val connectionState = MutableStateFlow(PlaybackConnectionState.CONNECTED)

        var position = 0L
        var positionReads = 0

        override fun currentPositionMillis(): Long {
            positionReads++
            return position
        }

        fun publish(value: PlaybackState) {
            mutableState.value = value
        }

        override fun prepare(request: PlaybackRequest): PlaybackCommandResult {
            mutableState.value = PlaybackState.Preparing(request)
            return PlaybackCommandResult.Accepted
        }

        override fun play() = PlaybackCommandResult.Accepted
        override fun pause() = PlaybackCommandResult.Accepted
        override fun seekTo(positionMillis: Long): PlaybackCommandResult {
            position = positionMillis
            return PlaybackCommandResult.Accepted
        }

        override fun stop() = PlaybackCommandResult.Accepted
        override fun retry() = PlaybackCommandResult.Accepted
    }

    private companion object {
        val REQUEST = PlaybackRequest(
            MediaItemId("media_1"),
            MediaLocationId("location_1"),
            0,
            PlaybackSourceContext.HOME,
        )
        val PHASE_PLAYING: PlaybackState = PlaybackState.Playing(REQUEST, PlaybackTimeline(0, DURATION_MILLIS))
        const val DURATION_MILLIS = 95_458L
    }
}

private object UnusedSourceRepository : PlaybackSourceRepository {
    override suspend fun resolve(
        mediaId: MediaItemId,
        sourceContext: PlaybackSourceContext,
        incognito: Boolean,
    ): ResolvedPlaybackSource = error("display projection never resolves a source")

    override suspend fun resolve(request: PlaybackRequest): ResolvedPlaybackSource =
        error("display projection never resolves a source")
}
