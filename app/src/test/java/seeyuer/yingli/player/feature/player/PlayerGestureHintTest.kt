package seeyuer.yingli.player.feature.player

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.domain.playback.PlaybackCommandHandle
import seeyuer.yingli.player.domain.playback.PlaybackCommandId
import seeyuer.yingli.player.domain.playback.PlaybackConnectionState
import seeyuer.yingli.player.domain.playback.PlaybackPhase
import seeyuer.yingli.player.domain.playback.PlaybackSessionClient
import seeyuer.yingli.player.domain.playback.PlaybackSessionCommand
import seeyuer.yingli.player.domain.playback.PlaybackSessionEvent
import seeyuer.yingli.player.domain.playback.PlaybackSessionId
import seeyuer.yingli.player.domain.playback.PlaybackSessionSnapshot
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.PlayerPreferenceRepository
import seeyuer.yingli.player.domain.playback.PlayerPreferences
import seeyuer.yingli.player.testing.MainDispatcherRule

/**
 * 常规播放页首次进入的一次性手势提示（规格 §5.15 / §19.3）：
 * 默认未展示、条目覆盖全部手势、展示一次后写入偏好并让页面状态不再显示提示。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerGestureHintTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `gesture hint is unshown until the playback page marks it`() {
        assertFalse(PlayerPreferences().gestureHintShown)
        assertFalse(PlayerUiState().preferences.gestureHintShown)
    }

    @Test
    fun `hint entries cover every documented gesture with its own wording`() {
        assertEquals(6, GESTURE_HINT_ENTRIES.size)
        assertEquals(
            GESTURE_HINT_ENTRIES.size,
            GESTURE_HINT_ENTRIES.map(GestureHintEntry::labelRes).distinct().size,
        )
        // 只有长按倍速条目带参数：档位能在设置里改（1.5/2/3），文案不能写死成 2x。
        assertEquals(
            listOf(R.string.player_gesture_hint_long_press_speed),
            GESTURE_HINT_ENTRIES.filter(GestureHintEntry::longPressSpeedArgument)
                .map(GestureHintEntry::labelRes),
        )
        assertEquals(2_800L, GESTURE_HINT_SHOW_MILLIS)
    }

    @Test
    fun `marking the hint shown persists it and stops the hint in the player state`() = runTest {
        val preferences = InMemoryPlayerPreferences()
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val viewModel = PlayerViewModel(
            FakePlaybackSessionClient(),
            dispatchers,
            playerPreferenceRepository = preferences,
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()
        assertFalse(viewModel.state.value.preferences.gestureHintShown)

        viewModel.markGestureHintShown()
        advanceUntilIdle()

        assertEquals(listOf(true), preferences.hintShownWrites)
        assertTrue(preferences.playerPreferences.value.gestureHintShown)
        assertTrue(viewModel.state.value.preferences.gestureHintShown)
        stateCollector.cancel()
    }

    private class TestDispatchers(private val dispatcher: CoroutineDispatcher) : AppDispatchers {
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
    }

    /** 最小的会话替身：这个用例只关心偏好落库，不需要真实播放管线。 */
    private class FakePlaybackSessionClient : PlaybackSessionClient {
        override val snapshot = MutableStateFlow(
            PlaybackSessionSnapshot(
                PlaybackSessionId("test"),
                mediaId = null,
                title = null,
                phase = PlaybackPhase.Idle,
                connectionState = PlaybackConnectionState.CONNECTED,
            ),
        )
        override val events: Flow<PlaybackSessionEvent> = emptyFlow()

        override fun dispatch(command: PlaybackSessionCommand): PlaybackCommandHandle =
            PlaybackCommandHandle(PlaybackCommandId(0))
    }

    private class InMemoryPlayerPreferences(
        initial: PlayerPreferences = PlayerPreferences(),
    ) : PlayerPreferenceRepository {
        private val mutablePreferences = MutableStateFlow(initial)
        override val playerPreferences: StateFlow<PlayerPreferences> = mutablePreferences
        val hintShownWrites = mutableListOf<Boolean>()

        override suspend fun setMiniPlayerEnabled(enabled: Boolean) = update { it.copy(miniPlayerEnabled = enabled) }
        override suspend fun setAutoPictureInPicture(enabled: Boolean) = update { it.copy(autoPictureInPicture = enabled) }
        override suspend fun setGestureSeekEnabled(enabled: Boolean) = update { it.copy(gestureSeekEnabled = enabled) }
        override suspend fun setGestureVolumeEnabled(enabled: Boolean) = update { it.copy(gestureVolumeEnabled = enabled) }
        override suspend fun setGestureBrightnessEnabled(enabled: Boolean) = update { it.copy(gestureBrightnessEnabled = enabled) }
        override suspend fun setGestureZoomEnabled(enabled: Boolean) = update { it.copy(gestureZoomEnabled = enabled) }
        override suspend fun setGestureLeftSideIsVolume(enabled: Boolean) = update { it.copy(gestureLeftSideIsVolume = enabled) }
        override suspend fun setGestureDoubleTapSeekMillis(millis: Int) = update { it.copy(gestureDoubleTapSeekMillis = millis) }
        override suspend fun setGestureSwipeDownToExitEnabled(enabled: Boolean) =
            update { it.copy(gestureSwipeDownToExitEnabled = enabled) }

        override suspend fun setGestureHintShown(shown: Boolean) {
            hintShownWrites += shown
            update { it.copy(gestureHintShown = shown) }
        }

        override suspend fun setGestureLongPressSpeed(speed: PlaybackSpeed) = update { it.copy(longPressSpeed = speed) }

        override suspend fun setPreviousRestartsCurrentItem(enabled: Boolean) =
            update { it.copy(previousRestartsCurrentItem = enabled) }

        private fun update(transform: (PlayerPreferences) -> PlayerPreferences) {
            mutablePreferences.value = transform(mutablePreferences.value)
        }
    }
}
