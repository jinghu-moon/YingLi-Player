package seeyuer.yingli.player.app.playback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.playback.AbLoopCommandOutcome
import seeyuer.yingli.player.domain.playback.AbLoopPlaybackControl
import seeyuer.yingli.player.domain.playback.AbLoopSession
import seeyuer.yingli.player.domain.playback.AbLoopState
import seeyuer.yingli.player.domain.playback.AbPoint
import seeyuer.yingli.player.domain.playback.PlaybackCommandRejection
import seeyuer.yingli.player.domain.playback.PlaybackCommandResult
import seeyuer.yingli.player.domain.playback.PlaybackConnectionState
import seeyuer.yingli.player.domain.playback.PlaybackController
import seeyuer.yingli.player.domain.playback.PlaybackMediaInfo
import seeyuer.yingli.player.domain.playback.PlaybackMediaInfoProvider
import seeyuer.yingli.player.domain.playback.PlaybackRequest
import seeyuer.yingli.player.domain.playback.PlaybackSessionCommand
import seeyuer.yingli.player.domain.playback.PlaybackSessionEvent
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackSourceRepository
import seeyuer.yingli.player.domain.playback.PlaybackState
import seeyuer.yingli.player.domain.playback.PlaybackTimeline
import seeyuer.yingli.player.domain.playback.ResolvedPlaybackSource
import seeyuer.yingli.player.domain.security.SecurePlaybackController
import seeyuer.yingli.player.domain.security.VaultItemId

/**
 * bridge 在 AB 上的职责只有两条：**把命令送出去**、**把会话回流的状态投影进快照**。
 * 它不持有一份自己的 AB 状态，也不做任何 AB 规则判定 —— 旧实现的本地 `abLoop`
 * 会在每次 publish 时把本地那份写回快照（覆盖会话取值），这是三份状态互相覆盖的成因之一。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackSessionClientBridgeTest {
    @Test
    fun `ab state is projected from the session and never derived locally`() = runTest {
        val controller = FakeAbLoopController()
        val bridge = bridge(controller, testScheduler)
        try {
            controller.publishAbLoop(AbLoopSession(AbLoopState(2_000, 6_000), loopCount = 12))
            runCurrent()

            assertEquals(AbLoopState(2_000, 6_000), bridge.snapshot.value.abLoop)
            assertEquals(12L, bridge.snapshot.value.loopCount)

            // 播放状态刷新不得把 AB 投影覆盖回空值（两个独立事件源）。
            controller.setState(PlaybackState.Playing(request(), PlaybackTimeline(3_000, 10_000)))
            runCurrent()
            assertEquals(AbLoopState(2_000, 6_000), bridge.snapshot.value.abLoop)
            assertEquals(12L, bridge.snapshot.value.loopCount)

            // 会话清空 → 投影跟着清空。
            controller.publishAbLoop(AbLoopSession.EMPTY)
            runCurrent()
            assertEquals(AbLoopState(), bridge.snapshot.value.abLoop)
            assertEquals(0L, bridge.snapshot.value.loopCount)
        } finally {
            bridge.close()
        }
    }

    @Test
    fun `ab commands are forwarded to the session instead of being applied locally`() = runTest {
        val controller = FakeAbLoopController()
        val bridge = bridge(controller, testScheduler)
        try {
            bridge.dispatch(PlaybackSessionCommand.SetAbPoint(AbPoint.A))
            bridge.dispatch(PlaybackSessionCommand.SetAbPoint(AbPoint.B))
            bridge.dispatch(PlaybackSessionCommand.ClearAb)
            runCurrent()

            assertEquals(listOf(AbPoint.A, AbPoint.B), controller.requestedPoints)
            assertEquals(1, controller.clearRequests)
            // bridge 自己没有留下任何区间。
            assertEquals(AbLoopState(), bridge.snapshot.value.abLoop)
        } finally {
            bridge.close()
        }
    }

    /** 会话不支持 AB 时如实反馈，不静默吞掉命令。 */
    @Test
    fun `ab commands report capability unavailable when the session has no ab channel`() = runTest {
        val controller = PlainController()
        val bridge = bridge(controller, testScheduler)
        try {
            val feedback = mutableListOf<String>()
            val collector = launch {
                bridge.events.collect { event ->
                    if (event is PlaybackSessionEvent.OneShotFeedback) feedback += event.code
                }
            }

            bridge.dispatch(PlaybackSessionCommand.SetAbPoint(AbPoint.A))
            runCurrent()

            assertEquals(listOf(PlaybackCommandRejection.CAPABILITY_UNAVAILABLE.name), feedback)
            collector.cancel()
        } finally {
            bridge.close()
        }
    }

    /**
     * 设点被拒必须走到统一瞬时反馈：命令是跨会话边界的，拒绝只有在**回执**回来时才知道，
     * fire-and-forget 就等于用户设点被拒时界面什么都不显示。
     */
    @Test
    fun `a rejected set point reports the session rejection code to the ui`() = runTest {
        val controller = FakeAbLoopController().apply {
            setPointOutcome = AbLoopCommandOutcome.Rejected(PlaybackCommandRejection.INVALID_AB_RANGE)
        }
        val bridge = bridge(controller, testScheduler)
        try {
            val feedback = mutableListOf<String>()
            val collector = launch {
                bridge.events.collect { event ->
                    if (event is PlaybackSessionEvent.OneShotFeedback) feedback += event.code
                }
            }

            bridge.dispatch(PlaybackSessionCommand.SetAbPoint(AbPoint.B))
            runCurrent()

            assertEquals(listOf(AbPoint.B), controller.requestedPoints)
            assertEquals(listOf(PlaybackCommandRejection.INVALID_AB_RANGE.name), feedback)
            collector.cancel()
        } finally {
            bridge.close()
        }
    }

    /** 设点被接受时不产生任何反馈：成功路径不需要提示，反馈通道只承载"用户需要知道的事"。 */
    @Test
    fun `an applied set point stays silent`() = runTest {
        val controller = FakeAbLoopController()
        val bridge = bridge(controller, testScheduler)
        try {
            val feedback = mutableListOf<String>()
            val collector = launch {
                bridge.events.collect { event ->
                    if (event is PlaybackSessionEvent.OneShotFeedback) feedback += event.code
                }
            }

            bridge.dispatch(PlaybackSessionCommand.SetAbPoint(AbPoint.A))
            runCurrent()

            assertEquals(emptyList<String>(), feedback)
            collector.cancel()
        } finally {
            bridge.close()
        }
    }

    /**
     * bridge 暴露的实时位置直接来自控制器，而不是它自己的快照 timeline。
     *
     * 快照只在 `controller.state` 变化时刷新，稳定播放期间它是陈旧值（真机实测静置播放 60s 后
     * 仍是 0）。这里让快照停在 1_000、控制器实时位置是 59_894，断言 bridge 报出后者。
     */
    @Test
    fun `the live position comes from the controller and not from the snapshot timeline`() = runTest {
        val controller = PlainController()
        val bridge = bridge(controller, testScheduler)
        try {
            controller.setState(
                PlaybackState.Playing(request(), PlaybackTimeline(1_000, 95_458)),
            )
            runCurrent()
            assertEquals(1_000L, bridge.snapshot.value.timeline.positionMillis)

            controller.setLivePosition(59_894)

            assertEquals(59_894L, bridge.currentPositionMillis())
            assertEquals(1_000L, bridge.snapshot.value.timeline.positionMillis)
        } finally {
            bridge.close()
        }
    }

    /**
     * 相对跳转的基准是实时位置：快照停在 1 秒、实时位置已经 59 秒时，
     * "+10s" 必须落到 69_894 而不是 11_000。
     */
    @Test
    fun `relative seeks are based on the live position`() = runTest {
        val controller = PlainController()
        val bridge = bridge(controller, testScheduler)
        try {
            controller.setState(PlaybackState.Playing(request(), PlaybackTimeline(1_000, 95_458)))
            runCurrent()
            controller.setLivePosition(59_894)

            bridge.dispatch(PlaybackSessionCommand.SeekBy(10_000))
            runCurrent()

            assertEquals(69_894L, controller.lastSeekPosition)
        } finally {
            bridge.close()
        }
    }

    private fun bridge(
        controller: PlaybackController,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ) = PlaybackSessionClientBridge(
        controller = controller,
        sourceRepository = UnusedSourceRepository,
        dispatchers = TestDispatchers(StandardTestDispatcher(scheduler)),
    )

    private fun request() = PlaybackRequest(MediaItemId("media"), MediaLocationId("location"), 0, PlaybackSourceContext.HOME)

    private open class PlainControllerBase : PlaybackController, PlaybackMediaInfoProvider {
        protected val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val state: StateFlow<PlaybackState> = mutableState
        override val connectionState = MutableStateFlow(PlaybackConnectionState.CONNECTED)
        override val mediaInfo = MutableStateFlow<PlaybackMediaInfo?>(null)

        /**
         * 实时位置的独立来源：由 [setLivePosition] 驱动，**不**跟着 [state] 的 timeline 走，
         * 这样测试才能构造"快照停在旧位置、实时位置已经走远"的现场。
         */
        private var livePositionMillis = 0L
        var lastSeekPosition: Long? = null

        override fun currentPositionMillis(): Long = livePositionMillis

        fun setLivePosition(positionMillis: Long) {
            livePositionMillis = positionMillis
        }

        fun setState(value: PlaybackState) {
            mutableState.value = value
        }

        override fun prepare(request: PlaybackRequest) = PlaybackCommandResult.Accepted
        override fun play() = PlaybackCommandResult.Accepted
        override fun pause() = PlaybackCommandResult.Accepted
        override fun seekTo(positionMillis: Long): PlaybackCommandResult {
            lastSeekPosition = positionMillis
            livePositionMillis = positionMillis
            return PlaybackCommandResult.Accepted
        }
        override fun stop() = PlaybackCommandResult.Accepted
        override fun retry() = PlaybackCommandResult.Accepted
    }

    private class PlainController : PlainControllerBase(), SecurePlaybackController {
        override fun prepare(itemId: VaultItemId): Boolean = false
        override fun invalidateSecureSession() = Unit
    }

    private class FakeAbLoopController : PlainControllerBase(), SecurePlaybackController, AbLoopPlaybackControl {
        private val mutableAbLoop = MutableStateFlow(AbLoopSession.EMPTY)
        override val abLoop: StateFlow<AbLoopSession> = mutableAbLoop
        val requestedPoints = mutableListOf<AbPoint>()
        var clearRequests = 0

        /** 会话对这个假实现的判定结果：默认接受，测试按需改成拒绝。 */
        var setPointOutcome: AbLoopCommandOutcome = AbLoopCommandOutcome.Applied

        fun publishAbLoop(session: AbLoopSession) {
            mutableAbLoop.value = session
        }

        override suspend fun requestSetAbPoint(point: AbPoint): AbLoopCommandOutcome {
            requestedPoints += point
            return setPointOutcome
        }

        override fun requestClearAbLoop() {
            clearRequests++
        }

        override fun prepare(itemId: VaultItemId): Boolean = false
        override fun invalidateSecureSession() = Unit
    }

    private class TestDispatchers(private val dispatcher: CoroutineDispatcher) : AppDispatchers {
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
    }

    private object UnusedSourceRepository : PlaybackSourceRepository {
        override suspend fun resolve(
            mediaId: MediaItemId,
            sourceContext: PlaybackSourceContext,
            incognito: Boolean,
        ): ResolvedPlaybackSource = error("not used")

        override suspend fun resolve(request: PlaybackRequest): ResolvedPlaybackSource = error("not used")
    }
}
