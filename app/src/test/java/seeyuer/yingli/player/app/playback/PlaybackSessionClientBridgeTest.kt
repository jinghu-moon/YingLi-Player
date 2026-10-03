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

        fun setState(value: PlaybackState) {
            mutableState.value = value
        }

        override fun prepare(request: PlaybackRequest) = PlaybackCommandResult.Accepted
        override fun play() = PlaybackCommandResult.Accepted
        override fun pause() = PlaybackCommandResult.Accepted
        override fun seekTo(positionMillis: Long) = PlaybackCommandResult.Accepted
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

        fun publishAbLoop(session: AbLoopSession) {
            mutableAbLoop.value = session
        }

        override fun requestSetAbPoint(point: AbPoint) {
            requestedPoints += point
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
