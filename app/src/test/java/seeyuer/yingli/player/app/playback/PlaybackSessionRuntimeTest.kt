package seeyuer.yingli.player.app.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.playback.BackendId
import seeyuer.yingli.player.domain.playback.BufferingReason
import seeyuer.yingli.player.domain.playback.EngineState
import seeyuer.yingli.player.domain.playback.ElapsedTimeSource
import seeyuer.yingli.player.domain.playback.PlaybackCapabilities
import seeyuer.yingli.player.domain.playback.PlaybackEngine
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

    private class FakeEngine : PlaybackEngine {
        override val state = MutableStateFlow<EngineState>(EngineState.Idle)
        override val capabilities = MutableStateFlow(PlaybackCapabilities(BackendId.MEDIA3))
        val preparedMediaIds = mutableListOf<String>()

        override suspend fun prepare(source: PlaybackSourceHandle, startPositionMillis: Long) {
            preparedMediaIds += source.mediaId.value
            state.value = EngineState.Ready(PlaybackTimeline(startPositionMillis, source.durationMillis))
        }
        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() { state.value = EngineState.Idle }
        override fun seekTo(positionMillis: Long) = Unit
        override fun bindSurface(request: SurfaceBindRequest): Result<SurfaceLease> =
            Result.success(SurfaceLease(request.owner, request.generation, request.surface.value))
        override fun unbindSurface(lease: SurfaceLease): Result<Unit> = Result.success(Unit)
        override fun release() = Unit
        fun emit(value: EngineState) { state.value = value }
    }
}
