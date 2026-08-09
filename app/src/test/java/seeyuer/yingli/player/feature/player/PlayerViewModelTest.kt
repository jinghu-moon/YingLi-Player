package seeyuer.yingli.player.feature.player

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import seeyuer.yingli.player.core.foundation.AppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.playback.PlaybackCommandResult
import seeyuer.yingli.player.domain.playback.PlaybackConnectionState
import seeyuer.yingli.player.domain.playback.PlaybackController
import seeyuer.yingli.player.domain.playback.PlaybackRequest
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackSourceRepository
import seeyuer.yingli.player.domain.playback.PlaybackState
import seeyuer.yingli.player.domain.playback.ResolvedPlaybackSource
import seeyuer.yingli.player.testing.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `opening a media route resolves a pure request and prepares fake controller`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(
            MediaItemId("media_1"),
            MediaLocationId("location_1"),
            7_000,
            PlaybackSourceContext.HOME,
        )
        val sourceRepository = FakePlaybackSourceRepository(
            ResolvedPlaybackSource(request, "content://media/video/1", "测试影片"),
        )
        val viewModel = PlayerViewModel(controller, sourceRepository, TestDispatchers(StandardTestDispatcher(testScheduler)))

        viewModel.open("media_1", PlaybackSourceContext.HOME)
        advanceUntilIdle()

        assertEquals(request, controller.preparedRequest)
        assertTrue(controller.state.value is PlaybackState.Preparing)
    }

    private class TestDispatchers(private val dispatcher: CoroutineDispatcher) : AppDispatchers {
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
    }

    private class FakePlaybackSourceRepository(
        private val source: ResolvedPlaybackSource,
    ) : PlaybackSourceRepository {
        override suspend fun resolve(
            mediaId: MediaItemId,
            sourceContext: PlaybackSourceContext,
            incognito: Boolean,
        ): ResolvedPlaybackSource = source

        override suspend fun resolve(request: PlaybackRequest): ResolvedPlaybackSource = source
    }

    private class FakePlaybackController : PlaybackController {
        private val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val state: StateFlow<PlaybackState> = mutableState
        override val connectionState = MutableStateFlow(PlaybackConnectionState.CONNECTED)
        var preparedRequest: PlaybackRequest? = null

        override fun prepare(request: PlaybackRequest): PlaybackCommandResult {
            preparedRequest = request
            mutableState.value = PlaybackState.Preparing(request)
            return PlaybackCommandResult.Accepted
        }

        override fun play() = PlaybackCommandResult.Accepted
        override fun pause() = PlaybackCommandResult.Accepted
        override fun seekTo(positionMillis: Long) = PlaybackCommandResult.Accepted
        override fun stop() = PlaybackCommandResult.Accepted
        override fun retry() = PlaybackCommandResult.Accepted
    }
}
