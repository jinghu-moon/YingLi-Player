package seeyuer.yingli.player.domain.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId

class PlaybackEngineContractTest {
    @Test
    fun `engine exposes backend independent lifecycle`() = runTest {
        val engine = ContractFakeEngine()
        val source = PlaybackSourceHandle(
            MediaItemId("media"),
            MediaLocationId("location"),
            SourceAccessHandleId("opaque"),
            "Video",
            10_000,
        )

        engine.prepare(source, 1_000)
        engine.play()
        engine.seekTo(4_000)
        engine.pause()
        engine.stop()

        assertEquals(listOf("prepare:opaque:1000", "play", "seek:4000", "pause", "stop"), engine.commands)
        assertTrue(engine.state.value is EngineState.Idle)
    }

    @Test
    fun `stale surface lease cannot detach the current output`() {
        val engine = ContractFakeEngine()
        val first = engine.bindSurface(
            SurfaceBindRequest(SurfaceOwner.REGULAR_PLAYER, 1, VideoSurfaceToken("first")),
        ).getOrThrow()
        val second = engine.bindSurface(
            SurfaceBindRequest(SurfaceOwner.REGULAR_PLAYER, 2, VideoSurfaceToken("second")),
        ).getOrThrow()

        assertTrue(engine.unbindSurface(first).isFailure)
        assertTrue(engine.unbindSurface(second).isSuccess)
    }

    private class ContractFakeEngine : PlaybackEngine {
        override val state = MutableStateFlow<EngineState>(EngineState.Idle)
        override val capabilities = MutableStateFlow(PlaybackCapabilities(BackendId.MEDIA3))
        val commands = mutableListOf<String>()
        private var lease: SurfaceLease? = null

        override suspend fun prepare(source: PlaybackSourceHandle, startPositionMillis: Long) {
            commands += "prepare:${source.accessHandleId.value}:$startPositionMillis"
            state.value = EngineState.Ready(PlaybackTimeline(startPositionMillis, source.durationMillis))
        }

        override fun play() { commands += "play" }
        override fun pause() { commands += "pause" }
        override fun stop() { commands += "stop"; state.value = EngineState.Idle }
        override fun seekTo(positionMillis: Long) { commands += "seek:$positionMillis" }

        override fun bindSurface(request: SurfaceBindRequest): Result<SurfaceLease> {
            val current = lease
            if (current != null && request.generation <= current.generation) {
                return Result.failure(IllegalStateException("STALE_SURFACE_GENERATION"))
            }
            return Result.success(
                SurfaceLease(request.owner, request.generation, request.surface.value).also { lease = it },
            )
        }

        override fun unbindSurface(lease: SurfaceLease): Result<Unit> =
            if (this.lease == lease) Result.success(Unit).also { this.lease = null }
            else Result.failure(IllegalStateException("STALE_SURFACE_LEASE"))

        override fun release() { lease = null }
    }
}
