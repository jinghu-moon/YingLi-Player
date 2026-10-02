package seeyuer.yingli.player.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.PlayerPreferences

class PlayerPreferencesMappingTest {
    @Test
    fun `stored defaults map onto the domain defaults`() {
        assertEquals(PlayerPreferences(), UserPreferences().toPlayerPreferences())
    }

    @Test
    fun `stored gesture settings map onto the domain contract`() {
        val mapped = UserPreferences(
            gestureSeekEnabled = false,
            gestureVolumeEnabled = false,
            gestureBrightnessEnabled = false,
            gestureZoomEnabled = false,
            gestureLeftSideIsVolume = false,
            gestureDoubleTapSeekMillis = 15_000,
            gestureSwipeDownToExitEnabled = true,
            gestureHintShown = true,
            gestureLongPressSpeed = 1.5f,
        ).toPlayerPreferences()

        assertFalse(mapped.gestureSeekEnabled)
        assertFalse(mapped.gestureVolumeEnabled)
        assertFalse(mapped.gestureBrightnessEnabled)
        assertFalse(mapped.gestureZoomEnabled)
        assertFalse(mapped.gestureLeftSideIsVolume)
        assertEquals(15_000, mapped.gestureDoubleTapSeekMillis)
        assertTrue(mapped.gestureSwipeDownToExitEnabled)
        assertTrue(mapped.gestureHintShown)
        assertEquals(PlaybackSpeed.of(1.5f), mapped.longPressSpeed)
    }

    @Test
    fun `every supported long press speed maps back to its playback speed`() {
        PlayerPreferences.LONG_PRESS_SPEEDS.forEach { speed ->
            assertEquals(
                speed,
                UserPreferences(gestureLongPressSpeed = speed.value).toPlayerPreferences().longPressSpeed,
            )
        }
    }
}
