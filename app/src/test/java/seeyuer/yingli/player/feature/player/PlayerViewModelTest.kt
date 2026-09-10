package seeyuer.yingli.player.feature.player

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.playback.PlaybackCommandResult
import seeyuer.yingli.player.domain.playback.AdvancedPlaybackController
import seeyuer.yingli.player.domain.playback.PlaybackConnectionState
import seeyuer.yingli.player.domain.playback.PlaybackController
import seeyuer.yingli.player.domain.playback.PlaybackRequest
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackSourceRepository
import seeyuer.yingli.player.domain.playback.PlaybackState
import seeyuer.yingli.player.domain.playback.ResolvedPlaybackSource
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.TrackChoice
import seeyuer.yingli.player.domain.playback.TrackPreference
import seeyuer.yingli.player.domain.playback.TrackPreferenceRepository
import seeyuer.yingli.player.domain.playback.TrackPreferenceSet
import seeyuer.yingli.player.domain.playback.VideoScaleMode
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

    @Test
    fun `changing speed preserves other per media preferences`() = runTest {
        val controller = PreferencePlaybackController()
        val preferences = InMemoryTrackPreferences(
            TrackPreferenceSet(perMedia = mapOf(
                MediaItemId("media_1") to TrackPreference(
                    audioLanguage = "ja",
                    subtitleLanguage = "en",
                    subtitlesEnabled = true,
                ),
            )),
        )
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.prepare(request)
        val viewModel = PlayerViewModel(controller, FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片")), TestDispatchers(UnconfinedTestDispatcher()), trackPreferenceRepository = preferences)
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.setSpeed(PlaybackSpeed.of(2f))
        advanceUntilIdle()
        stateCollector.cancel()

        val saved = preferences.trackPreferences.value.resolve(request.mediaId)
        assertEquals("ja", saved.audioLanguage)
        assertEquals("en", saved.subtitleLanguage)
        assertTrue(saved.subtitlesEnabled)
        assertEquals(2f, saved.speed.value)
    }

    @Test
    fun `disabling subtitles persists per media preference`() = runTest {
        val controller = PreferencePlaybackController().also {
            it.subtitleChoices.value = listOf(TrackChoice("en", "English", "en", true))
        }
        val preferences = InMemoryTrackPreferences()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.prepare(request)
        val viewModel = PlayerViewModel(controller, FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片")), TestDispatchers(UnconfinedTestDispatcher()), trackPreferenceRepository = preferences)
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.selectSubtitleTrack(null)
        advanceUntilIdle()
        stateCollector.cancel()

        assertEquals(false, preferences.trackPreferences.value.resolve(request.mediaId).subtitlesEnabled)
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

    private class PreferencePlaybackController : AdvancedPlaybackController {
        private val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val state: StateFlow<PlaybackState> = mutableState
        override val connectionState = MutableStateFlow(PlaybackConnectionState.CONNECTED)
        val subtitleChoices = MutableStateFlow<List<TrackChoice>>(emptyList())
        override val audioTracks = MutableStateFlow<List<TrackChoice>>(emptyList())
        override val subtitleTracks: StateFlow<List<TrackChoice>> = subtitleChoices
        override val speed = MutableStateFlow(PlaybackSpeed.Normal)
        override val scaleMode = MutableStateFlow(VideoScaleMode.FIT)

        override fun prepare(request: PlaybackRequest): PlaybackCommandResult {
            mutableState.value = PlaybackState.Preparing(request)
            return PlaybackCommandResult.Accepted
        }
        override fun play() = PlaybackCommandResult.Accepted
        override fun pause() = PlaybackCommandResult.Accepted
        override fun seekTo(positionMillis: Long) = PlaybackCommandResult.Accepted
        override fun stop() = PlaybackCommandResult.Accepted
        override fun retry() = PlaybackCommandResult.Accepted
        override fun seekBy(offsetMillis: Long) = PlaybackCommandResult.Accepted
        override fun setSpeed(speed: PlaybackSpeed): PlaybackCommandResult { this.speed.value = speed; return PlaybackCommandResult.Accepted }
        override fun selectAudioTrack(id: String) = PlaybackCommandResult.Accepted
        override fun selectSubtitleTrack(id: String?): PlaybackCommandResult = PlaybackCommandResult.Accepted
        override fun setScaleMode(mode: VideoScaleMode): PlaybackCommandResult { scaleMode.value = mode; return PlaybackCommandResult.Accepted }
    }

    private class InMemoryTrackPreferences(initial: TrackPreferenceSet = TrackPreferenceSet()) : TrackPreferenceRepository {
        private val mutablePreferences = MutableStateFlow(initial)
        override val trackPreferences: StateFlow<TrackPreferenceSet> = mutablePreferences
        override suspend fun setGlobal(preference: TrackPreference) {
            mutablePreferences.value = mutablePreferences.value.copy(global = preference)
        }
        override suspend fun setForMedia(mediaId: MediaItemId, preference: TrackPreference?) {
            mutablePreferences.value = mutablePreferences.value.copy(
                perMedia = mutablePreferences.value.perMedia.toMutableMap().apply {
                    if (preference == null) remove(mediaId) else put(mediaId, preference)
                },
            )
        }
    }
}
