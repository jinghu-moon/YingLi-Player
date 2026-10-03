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
import seeyuer.yingli.player.domain.playback.FrameCaptureRequest
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
import seeyuer.yingli.player.domain.playback.ScreenshotArtifact
import seeyuer.yingli.player.domain.playback.SnapshotControl
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
        snapshotControl: SnapshotControl? = null,
    ): PlaybackSessionRuntime =
        PlaybackSessionRuntime(
            resolver = resolver,
            engine = engine,
            dispatchers = TestAppDispatchers(scheduler),
            sessionId = PlaybackSessionId("session"),
            elapsedTimeSource = ElapsedTimeSource { 42L },
            vaultResolver = vaultResolver,
            snapshotControl = snapshotControl,
        )

    /** 只记录"会话请求截取的位置"的截图控制：位置正确性由它来钉。 */
    private class RecordingSnapshotControl : SnapshotControl {
        val requestedPositions = mutableListOf<Long>()

        override suspend fun captureFrame(request: FrameCaptureRequest): Result<ScreenshotArtifact> {
            requestedPositions += request.positionMillis
            return Result.success(
                ScreenshotArtifact(
                    id = "shot",
                    displayName = "shot_${request.positionMillis}.jpg",
                    positionMillis = request.positionMillis,
                ),
            )
        }
    }

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

    /** 用户 seek 不再被 AB 钳制（D8-A：循环期间允许拖到区间外）。 */    @Test
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

    // ---- 缺陷 1：决策必须读实时位置，不得读快照 ----

    /**
     * 设 A 用的是**此刻**的播放位置，不是快照 timeline 的位置。
     *
     * 这是缺陷 1 的原始现场：稳定播放期间引擎不重新发布状态，快照 timeline 停在最后一次跳变的
     * 位置（真机实测静置播放 60s 后仍是 0），于是"设 A"把 A 设成了 0ms。
     * 这里让快照停在 1_000、实时位置走到 59_894，断言 A 落在 59_894 上。
     */
    @Test
    fun `setting point A uses the live position and not the stale snapshot`() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(PlaybackSourceResolver { Result.success(source(it.mediaId)) }, engine, testScheduler)
        try {
            runtime.dispatch(PlaybackSessionCommand.Open(request("media")))
            runCurrent()
            // 快照最后一次跳变在 1 秒处。
            engine.emit(EngineState.Playing(PlaybackTimeline(1_000, 95_458, isSeekable = true)))
            runCurrent()
            assertEquals(1_000L, runtime.snapshot.value.timeline.positionMillis)

            // 播放推进 59 秒，但引擎**不**重新发布状态：快照仍然停在 1 秒。
            engine.setPosition(59_894)
            runCurrent()
            assertEquals(1_000L, runtime.snapshot.value.timeline.positionMillis)

            assertEquals(AbLoopCommandOutcome.Applied, runtime.setPoint(AbPoint.A))

            assertEquals(59_894L, runtime.abLoop.value.state.pointA)
        } finally {
            runtime.close()
        }
    }

    /**
     * 区间被设全时，runtime 必须发起一次"回到 A"的激活：跳转目标就是刚设的 A。
     *
     * 缺陷 2 的根因是"激活没有把位置带回 A"，于是边界检测在配置那一刻就认为已经越过 B、
     * 永不武装。断言激活确实发生、且落点就是 A，正是这条修复的可观察形态。
     */
    @Test
    fun `completing the interval activates the loop by seeking back to point A`() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(PlaybackSourceResolver { Result.success(source(it.mediaId)) }, engine, testScheduler)
        try {
            runtime.dispatch(PlaybackSessionCommand.Open(request("media")))
            runCurrent()
            engine.emit(EngineState.Playing(PlaybackTimeline(2_000, 95_458, isSeekable = true)))
            runCurrent()
            engine.setPosition(2_000)
            runtime.setPoint(AbPoint.A)
            runCurrent()
            // 用户把 B 设在**当前位置**上（正常流程）：此刻位置已经落在 B 上。
            engine.setPosition(4_000)
            runtime.setPoint(AbPoint.B)
            runCurrent()

            assertEquals(1, engine.abLoopActivations)
            assertEquals(2_000L, engine.seekTargets.last())
            // 激活与配置必须描述同一个区间，否则精度链与检测链会对"当前配置"给出两种答案。
            assertEquals(EngineAbLoop(requireNotNull(engine.abLoop).generation, 2_000, 4_000), engine.abLoop)
        } finally {
            runtime.close()
        }
    }

    /** 还没设全（只有 A）时不激活：区间不完整就没有可回跳的目标。 */
    @Test
    fun `an incomplete interval does not activate the loop`() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(PlaybackSourceResolver { Result.success(source(it.mediaId)) }, engine, testScheduler)
        try {
            runtime.dispatch(PlaybackSessionCommand.Open(request("media")))
            runCurrent()
            engine.emit(EngineState.Playing(PlaybackTimeline(2_000, 95_458, isSeekable = true)))
            runCurrent()

            runtime.setPoint(AbPoint.A)
            runCurrent()

            assertEquals(0, engine.abLoopActivations)
            assertNull(engine.abLoop)
        } finally {
            runtime.close()
        }
    }

    /** 截图的请求位置是**此刻**的播放位置（它同时是截到哪一帧和文件名时间戳的依据）。 */
    @Test
    fun `capturing a frame uses the live position`() = runTest {
        val engine = FakeEngine()
        val control = RecordingSnapshotControl()
        val runtime = runtime(
            resolver = PlaybackSourceResolver { Result.success(source(it.mediaId)) },
            engine = engine,
            scheduler = testScheduler,
            snapshotControl = control,
        )
        try {
            runtime.dispatch(PlaybackSessionCommand.Open(request("media")))
            runCurrent()
            engine.emit(EngineState.Playing(PlaybackTimeline(1_000, 95_458, isSeekable = true)))
            runCurrent()
            engine.setPosition(59_894)
            runCurrent()

            runtime.dispatch(PlaybackSessionCommand.CaptureFrame)
            runCurrent()

            assertEquals(listOf(59_894L), control.requestedPositions)
        } finally {
            runtime.close()
        }
    }

    /**
     * "上一项"的 5 秒判定读实时位置：快照停在 1 秒（小于阈值）而实时位置已经 59 秒时，
     * 判定必须按 59 秒走，也就是**回到本集开头**（`MoveTo(currentIndex)`）而不是切上一项。
     */
    @Test
    fun `navigating to the previous item uses the live position for the restart decision`() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(PlaybackSourceResolver { Result.success(source(it.mediaId)) }, engine, testScheduler)
        try {
            runtime.setQueue(
                PlaybackQueueSnapshot(
                    queueId = "queue",
                    mediaIds = listOf(MediaItemId("first"), MediaItemId("second")),
                    currentIndex = 1,
                    order = PlaybackOrder.SEQUENCE,
                ),
            )
            runCurrent()
            runtime.dispatch(PlaybackSessionCommand.Open(request("second")))
            runCurrent()
            engine.emit(EngineState.Playing(PlaybackTimeline(1_000, 95_458, isSeekable = true)))
            runCurrent()
            engine.setPosition(59_894)
            runCurrent()
            // 快照位置仍停在 1 秒（低于 5 秒阈值）：用陈旧值判定会错误地切到上一项。
            assertEquals(1_000L, runtime.snapshot.value.timeline.positionMillis)

            runtime.dispatch(PlaybackSessionCommand.Previous)
            runCurrent()

            assertEquals(0L, engine.seekTargets.last())
            assertEquals(1, runtime.snapshot.value.queue?.currentIndex)
        } finally {
            runtime.close()
        }
    }

    /** 相对跳转的基准是实时位置：连续两次相对跳转必须各从落点起算，而不是都从旧位置起算。 */
    @Test
    fun `relative seeks are based on the live position`() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(PlaybackSourceResolver { Result.success(source(it.mediaId)) }, engine, testScheduler)
        try {
            runtime.dispatch(PlaybackSessionCommand.Open(request("media")))
            runCurrent()
            engine.emit(EngineState.Playing(PlaybackTimeline(1_000, 95_458, isSeekable = true)))
            runCurrent()
            engine.setPosition(59_894)
            runCurrent()

            runtime.dispatch(PlaybackSessionCommand.SeekBy(1_000))
            runCurrent()

            assertEquals(60_894L, engine.seekTargets.last())
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
        var abLoopActivations = 0
        private var positionMillis = 0L

        /** 实时位置（[PlaybackEngine.currentPositionMillis]）：与 [state] 里的 timeline 位置**独立**。 */
        override fun currentPositionMillis(): Long = positionMillis

        override fun configureAbLoop(loop: EngineAbLoop?) {
            abLoop = loop
            abLoopConfigurations++
        }

        /**
         * 与真实引擎同序地激活：**先精确跳回 A**（记进 [seekTargets]，位置随之更新），
         * 再换配置。测试因此能验证"设全 A/B 时确实发生了一次回 A 的跳转"，
         * 而不是只验证配置被下发过。
         */
        override fun activateAbLoop(loop: EngineAbLoop) {
            abLoopActivations++
            seekTo(loop.pointAMillis)
            abLoop = loop
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

        /**
         * 上报引擎状态。与真实引擎一致：**发布状态时实时位置随之对齐**（状态跳变来自
         * seek/换源/暂停这类位置本身也变了的时刻）。
         *
         * 播放**推进**不走这里 —— 那一路用 [setPosition]，它只改实时位置、不发布状态，
         * 这正是"快照会陈旧"的现场。
         */
        fun emit(value: EngineState) {
            state.value = value
            when (value) {
                is EngineState.Ready -> positionMillis = value.timeline.positionMillis
                is EngineState.Playing -> positionMillis = value.timeline.positionMillis
                is EngineState.Paused -> positionMillis = value.timeline.positionMillis
                is EngineState.Buffering -> positionMillis = value.timeline.positionMillis
                is EngineState.Ended -> positionMillis = value.timeline.positionMillis
                is EngineState.Failed -> positionMillis = value.timeline.positionMillis
                EngineState.Idle, EngineState.Preparing -> Unit
            }
        }

        /**
         * 播放推进：**只更新实时位置，不重新发布引擎状态**。
         *
         * 这正是真实播放器的行为——`onPlaybackStateChanged` / `onIsPlayingChanged` 都不触发，
         * 所以会话快照的 timeline 停在原地，而实时位置已经走远
         *（真机实测：静置播放 60s 后快照位置仍是 0）。
         *
         * 需要快照也跟上时，测试必须显式 `emit(...)`：把两件事分开，才能测出"决策究竟读的是哪一个"。
         */
        fun setPosition(positionMillis: Long) {
            this.positionMillis = positionMillis
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
