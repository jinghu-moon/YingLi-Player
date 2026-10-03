package seeyuer.yingli.player.app.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.playback.AbLoopCommandOutcome
import seeyuer.yingli.player.domain.playback.AbPoint
import seeyuer.yingli.player.domain.playback.BackendId
import seeyuer.yingli.player.domain.playback.BufferingReason
import seeyuer.yingli.player.domain.playback.EngineAbLoop
import seeyuer.yingli.player.domain.playback.EngineState
import seeyuer.yingli.player.domain.playback.ElapsedTimeSource
import seeyuer.yingli.player.domain.playback.PlaybackCapabilities
import seeyuer.yingli.player.domain.playback.PlaybackCommandRejection
import seeyuer.yingli.player.domain.playback.PlaybackEngine
import seeyuer.yingli.player.domain.playback.PlaybackEngineEvent
import seeyuer.yingli.player.domain.playback.PlaybackMediaInfo
import seeyuer.yingli.player.domain.playback.PlaybackMediaInfoProvider
import seeyuer.yingli.player.domain.playback.PlaybackOpenRequest
import seeyuer.yingli.player.domain.playback.PlaybackOrder
import seeyuer.yingli.player.domain.playback.PlaybackQueueSnapshot
import seeyuer.yingli.player.domain.playback.PlaybackPhase
import seeyuer.yingli.player.domain.playback.PlaybackSessionCommand
import seeyuer.yingli.player.domain.playback.PlaybackSessionId
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackSourceHandle
import seeyuer.yingli.player.domain.playback.PlaybackSourceResolver
import seeyuer.yingli.player.domain.playback.PlaybackTimeline
import seeyuer.yingli.player.domain.playback.SurfaceBindRequest
import seeyuer.yingli.player.domain.playback.SurfaceLease
import seeyuer.yingli.player.domain.security.VaultItemId
import seeyuer.yingli.player.testing.TestAppDispatchers

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackSessionRuntimeTest {
    @Test
    fun `stale source resolution cannot prepare a newer open`() = runTest {
        val firstResolved = CompletableDeferred<Unit>()
        val secondResolved = CompletableDeferred<Unit>()
        val resolver = PlaybackSourceResolver { request ->
            if (request.mediaId.value == "first") firstResolved.await() else secondResolved.await()
            Result.success(source(request.mediaId))
        }
        val engine = FakeEngine()
        val runtime = runtime(resolver, engine, testScheduler)
        try {
            runtime.dispatch(PlaybackSessionCommand.Open(request("first")))
            runCurrent()
            runtime.dispatch(PlaybackSessionCommand.Open(request("second")))
            runCurrent()
            secondResolved.complete(Unit)
            runCurrent()
            firstResolved.complete(Unit)
            runCurrent()

            assertEquals(listOf("second"), engine.preparedMediaIds)
            assertEquals(MediaItemId("second"), runtime.snapshot.value.mediaId)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `engine buffering and ready facts are projected without a second playing flag`() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(PlaybackSourceResolver { Result.success(source(it.mediaId)) }, engine, testScheduler)
        try {
            runtime.dispatch(PlaybackSessionCommand.Open(request("media")))
            runCurrent()
            engine.emit(EngineState.Ready(PlaybackTimeline(0, 10_000, isSeekable = true)))
            runCurrent()
            assertTrue(runtime.snapshot.value.phase is PlaybackPhase.Ready)
            engine.emit(EngineState.Buffering(PlaybackTimeline(500, 10_000), BufferingReason.REBUFFER))
            runCurrent()
            assertEquals(PlaybackPhase.Buffering(BufferingReason.REBUFFER), runtime.snapshot.value.phase)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `changing playback order clears stale shuffle history`() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(PlaybackSourceResolver { Result.success(source(it.mediaId)) }, engine, testScheduler)
        try {
            runtime.setQueue(
                PlaybackQueueSnapshot(
                    queueId = "queue",
                    mediaIds = listOf(MediaItemId("a"), MediaItemId("b")),
                    currentIndex = 1,
                    order = PlaybackOrder.SHUFFLE,
                    shuffleHistory = listOf(0),
                ),
            )
            runCurrent()
            runtime.dispatch(PlaybackSessionCommand.SetOrder(PlaybackOrder.QUEUE_REPEAT))
            runCurrent()

            assertEquals(PlaybackOrder.QUEUE_REPEAT, runtime.snapshot.value.queue?.order)
            assertTrue(runtime.snapshot.value.queue?.shuffleHistory.isNullOrEmpty())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `vault open resolves an opaque source and prepares the service engine`() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(
            resolver = PlaybackSourceResolver { Result.failure(IllegalStateException("not used")) },
            engine = engine,
            scheduler = testScheduler,
            vaultResolver = { itemId, title ->
                Result.success(source(MediaItemId("vault-${itemId.value}")).copy(displayName = title))
            },
        )
        try {
            runtime.dispatch(PlaybackSessionCommand.OpenVault(VaultItemId("secret"), "安全视频"))
            runCurrent()

            assertEquals(listOf("vault-secret"), engine.preparedMediaIds)
            assertEquals(MediaItemId("vault-secret"), runtime.snapshot.value.mediaId)
            assertEquals("安全视频", runtime.snapshot.value.title)
        } finally {
            runtime.close()
        }
    }

    private fun runtime(
        resolver: PlaybackSourceResolver,
        engine: FakeEngine,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        vaultResolver: (suspend (VaultItemId, String) -> Result<PlaybackSourceHandle>)? = null,
    ): PlaybackSessionRuntime =
        PlaybackSessionRuntime(
            resolver = resolver,
            engine = engine,
            dispatchers = TestAppDispatchers(scheduler),
            sessionId = PlaybackSessionId("session"),
            elapsedTimeSource = ElapsedTimeSource { 42L },
            vaultResolver = vaultResolver,
        )

    // ---- AB 循环：runtime 是唯一权威状态源 ----

    /**
     * 设点必须把区间与 generation 一并下发给引擎，且生成新 generation。
     * 帧率来自会话快照的 `mediaInfo.frameRate`（引擎从轨道读到的声明帧率）。
     */
    @Test
    fun `setting ab points publishes the interval to the engine with a fresh generation`() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(PlaybackSourceResolver { Result.success(source(it.mediaId)) }, engine, testScheduler)
        try {
            runtime.dispatch(PlaybackSessionCommand.Open(request("media")))
            runCurrent()
            engine.emit(EngineState.Ready(PlaybackTimeline(1_000, 10_000, isSeekable = true)))
            runCurrent()
            engine.emitMediaInfo(frameRate = 25f)
            runCurrent()

            assertEquals(AbLoopCommandOutcome.Applied, runtime.setPoint(AbPoint.A))
            engine.setPosition(4_000)
            runCurrent()
            assertEquals(AbLoopCommandOutcome.Applied, runtime.setPoint(AbPoint.B))
            runCurrent()

            assertEquals(1_000L, runtime.abLoop.value.state.pointA)
            assertEquals(4_000L, runtime.abLoop.value.state.pointB)
            val configured = engine.abLoop
            assertEquals(1_000L, configured?.pointAMillis)
            assertEquals(4_000L, configured?.pointBMillis)
            // 设点两次 → 两个不同的 generation；旧 generation 的边界事件必须被作废。
            assertTrue(configured != null && configured.generation >= 2)
        } finally {
            runtime.close()
        }
    }

    /** 计数只认当前 generation 的自然边界事件。 */
    @Test
    fun `loop count only advances for the current generation boundary event`() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(PlaybackSourceResolver { Result.success(source(it.mediaId)) }, engine, testScheduler)
        try {
            runtime.dispatch(PlaybackSessionCommand.Open(request("media")))
            runCurrent()
            engine.emit(EngineState.Ready(PlaybackTimeline(1_000, 10_000, isSeekable = true)))
            runCurrent()
            runtime.setPoint(AbPoint.A)
            engine.setPosition(4_000)
            runCurrent()
            runtime.setPoint(AbPoint.B)
            runCurrent()
            val generation = requireNotNull(engine.abLoop).generation

            engine.emitBoundary(generation, 4_001)
            runCurrent()
            assertEquals(1L, runtime.abLoop.value.loopCount)

            // 过期 generation（旧配置的晚到事件）不得计数。
            engine.emitBoundary(generation - 1, 4_001)
            runCurrent()
            assertEquals(1L, runtime.abLoop.value.loopCount)
        } finally {
            runtime.close()
        }
    }

    /** 设点是新一轮配置：计数从这一轮重新开始。 */
    @Test
    fun `changing a point restarts the loop count`() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(PlaybackSourceResolver { Result.success(source(it.mediaId)) }, engine, testScheduler)
        try {
            runtime.dispatch(PlaybackSessionCommand.Open(request("media")))
            runCurrent()
            engine.emit(EngineState.Ready(PlaybackTimeline(1_000, 10_000, isSeekable = true)))
            runCurrent()
            runtime.setPoint(AbPoint.A)
            engine.setPosition(4_000)
            runCurrent()
            runtime.setPoint(AbPoint.B)
            runCurrent()
            engine.emitBoundary(requireNotNull(engine.abLoop).generation, 4_001)
            runCurrent()
            assertEquals(1L, runtime.abLoop.value.loopCount)

            engine.setPosition(5_000)
            runCurrent()
            runtime.setPoint(AbPoint.B)
            runCurrent()

            assertEquals(0L, runtime.abLoop.value.loopCount)
        } finally {
            runtime.close()
        }
    }

    /** 清除：区间清空、计数归零、新 generation、引擎下发 null。 */
    @Test
    fun `clearing the loop empties the interval and resets the count`() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(PlaybackSourceResolver { Result.success(source(it.mediaId)) }, engine, testScheduler)
        try {
            runtime.dispatch(PlaybackSessionCommand.Open(request("media")))
            runCurrent()
            engine.emit(EngineState.Ready(PlaybackTimeline(1_000, 10_000, isSeekable = true)))
            runCurrent()
            runtime.setPoint(AbPoint.A)
            engine.setPosition(4_000)
            runCurrent()
            runtime.setPoint(AbPoint.B)
            runCurrent()

            runtime.dispatch(PlaybackSessionCommand.ClearAb)
            runCurrent()

            assertEquals(false, runtime.abLoop.value.active)
            assertEquals(0L, runtime.abLoop.value.loopCount)
            assertNull(engine.abLoop)
        } finally {
            runtime.close()
        }
    }

    /** 切媒体：自动清空区间与计数。 */
    @Test
    fun `opening another media clears the interval`() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(PlaybackSourceResolver { Result.success(source(it.mediaId)) }, engine, testScheduler)
        try {
            runtime.dispatch(PlaybackSessionCommand.Open(request("first")))
            runCurrent()
            engine.emit(EngineState.Ready(PlaybackTimeline(1_000, 10_000, isSeekable = true)))
            runCurrent()
            runtime.setPoint(AbPoint.A)
            engine.setPosition(4_000)
            runCurrent()
            runtime.setPoint(AbPoint.B)
            runCurrent()
            assertTrue(runtime.abLoop.value.active)

            runtime.dispatch(PlaybackSessionCommand.Open(request("second")))
            runCurrent()

            assertEquals(false, runtime.abLoop.value.active)
            assertNull(engine.abLoop)
        } finally {
            runtime.close()
        }
    }

    /** 启用策略：时长未知 → 拒绝设点（AB_UNAVAILABLE），不是靠来源类型禁用。 */
    @Test
    fun `unknown duration disables ab point setting`() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(PlaybackSourceResolver { Result.success(source(it.mediaId)) }, engine, testScheduler)
        try {
            runtime.dispatch(PlaybackSessionCommand.Open(request("media")))
            runCurrent()
            engine.emit(EngineState.Ready(PlaybackTimeline(0, durationMillis = null, isSeekable = true)))
            runCurrent()

            assertEquals(
                AbLoopCommandOutcome.Rejected(PlaybackCommandRejection.AB_UNAVAILABLE),
                runtime.setPoint(AbPoint.A),
            )
        } finally {
            runtime.close()
        }
    }

    /** 启用策略：不可 seek → 拒绝设点。 */
    @Test
    fun `non seekable media disables ab point setting`() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(PlaybackSourceResolver { Result.success(source(it.mediaId)) }, engine, testScheduler)
        try {
            runtime.dispatch(PlaybackSessionCommand.Open(request("media")))
            runCurrent()
            engine.emit(EngineState.Ready(PlaybackTimeline(0, 10_000, isSeekable = false)))
            runCurrent()

            assertEquals(
                AbLoopCommandOutcome.Rejected(PlaybackCommandRejection.AB_UNAVAILABLE),
                runtime.setPoint(AbPoint.A),
            )
        } finally {
            runtime.close()
        }
    }

    /**
     * 时长从"未知"变成已知（保险库媒体的典型路径）：准备完成后按**真实 timeline** 才可用。
     * 判定时机必须在准备完成之后，而不是按最初 metadata 一律禁用。
     */
    @Test
    fun `ab becomes available once the prepared timeline reports a real duration`() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(PlaybackSourceResolver { Result.success(source(it.mediaId)) }, engine, testScheduler)
        try {
            runtime.dispatch(PlaybackSessionCommand.Open(request("media")))
            runCurrent()
            engine.emit(EngineState.Ready(PlaybackTimeline(1_000, durationMillis = null, isSeekable = true)))
            runCurrent()
            assertTrue(runtime.setPoint(AbPoint.A) is AbLoopCommandOutcome.Rejected)

            engine.emit(EngineState.Ready(PlaybackTimeline(1_000, 10_000, isSeekable = true)))
            runCurrent()

            assertEquals(AbLoopCommandOutcome.Applied, runtime.setPoint(AbPoint.A))
        } finally {
            runtime.close()
        }
    }

    /** 用户 seek 不再被 AB 钳制（D8-A：循环期间允许拖到区间外）。 */
    @Test
    fun `seek outside the interval is forwarded unchanged`() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(PlaybackSourceResolver { Result.success(source(it.mediaId)) }, engine, testScheduler)
        try {
            runtime.dispatch(PlaybackSessionCommand.Open(request("media")))
            runCurrent()
            engine.emit(EngineState.Ready(PlaybackTimeline(1_000, 10_000, isSeekable = true)))
            runCurrent()
            runtime.setPoint(AbPoint.A)
            engine.setPosition(4_000)
            runCurrent()
            runtime.setPoint(AbPoint.B)
            runCurrent()

            runtime.dispatch(PlaybackSessionCommand.Seek(9_000))
            runCurrent()

            assertEquals(9_000L, engine.seekTargets.last())
        } finally {
            runtime.close()
        }
    }

    private fun request(mediaId: String) = PlaybackOpenRequest(
        sessionId = PlaybackSessionId("session"),
        mediaId = MediaItemId(mediaId),
        sourceContext = PlaybackSourceContext.HOME,
    )

    private fun source(mediaId: MediaItemId) = PlaybackSourceHandle(
        mediaId = mediaId,
        locationId = MediaLocationId("location-${mediaId.value}"),
        accessHandleId = seeyuer.yingli.player.domain.playback.SourceAccessHandleId("access-${mediaId.value}"),
        displayName = mediaId.value,
        durationMillis = 10_000,
    )

    private class FakeEngine : PlaybackEngine, PlaybackMediaInfoProvider {
        override val state = MutableStateFlow<EngineState>(EngineState.Idle)
        override val capabilities = MutableStateFlow(PlaybackCapabilities(BackendId.MEDIA3))
        override val events = MutableSharedFlow<PlaybackEngineEvent>(extraBufferCapacity = 8)
        override val mediaInfo = MutableStateFlow<PlaybackMediaInfo?>(null)
        val preparedMediaIds = mutableListOf<String>()
        val seekTargets = mutableListOf<Long>()
        var abLoop: EngineAbLoop? = null
        var abLoopConfigurations = 0
        private var positionMillis = 0L

        override fun configureAbLoop(loop: EngineAbLoop?) {
            abLoop = loop
            abLoopConfigurations++
        }

        override suspend fun prepare(source: PlaybackSourceHandle, startPositionMillis: Long) {
            preparedMediaIds += source.mediaId.value
            positionMillis = startPositionMillis
            state.value = EngineState.Ready(PlaybackTimeline(startPositionMillis, source.durationMillis))
        }
        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() { state.value = EngineState.Idle }
        override fun seekTo(positionMillis: Long) {
            seekTargets += positionMillis
            this.positionMillis = positionMillis
        }
        override fun bindSurface(request: SurfaceBindRequest): Result<SurfaceLease> =
            Result.success(SurfaceLease(request.owner, request.generation, request.surface.value))
        override fun unbindSurface(lease: SurfaceLease): Result<Unit> = Result.success(Unit)
        override fun release() = Unit
        fun emit(value: EngineState) { state.value = value }

        /** 改变播放位置并让引擎状态重新上报（模拟播放推进/用户 seek 到位）。 */
        fun setPosition(positionMillis: Long) {
            this.positionMillis = positionMillis
            val current = state.value
            val duration = when (current) {
                is EngineState.Ready -> current.timeline.durationMillis
                is EngineState.Playing -> current.timeline.durationMillis
                is EngineState.Paused -> current.timeline.durationMillis
                else -> null
            }
            state.value = EngineState.Playing(PlaybackTimeline(positionMillis, duration))
        }

        /** 上报声明帧率（吸附用它；拿不到就不吸附）。 */
        fun emitMediaInfo(frameRate: Float?) {
            mediaInfo.value = PlaybackMediaInfo(title = "media", frameRate = frameRate)
        }

        fun emitBoundary(generation: Long, positionMillis: Long) {
            events.tryEmit(PlaybackEngineEvent.AbBoundaryReached(generation, positionMillis))
        }
    }
}
