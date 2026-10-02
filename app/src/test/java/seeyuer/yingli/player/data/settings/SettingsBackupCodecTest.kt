package seeyuer.yingli.player.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.data.preferences.UserPreferences

class SettingsBackupCodecTest {
    @Test
    fun `gesture settings round trip through the backup map`() {
        val source = UserPreferences(
            gestureSeekEnabled = false,
            gestureVolumeEnabled = false,
            gestureBrightnessEnabled = true,
            gestureZoomEnabled = false,
            gestureLeftSideIsVolume = false,
            gestureDoubleTapSeekMillis = 30_000,
            gestureSwipeDownToExitEnabled = true,
            gestureLongPressSpeed = 3f,
        )

        assertEquals(source, source.toBackupMap().toUserPreferences(source))
    }

    @Test
    fun `backup without gesture entries keeps the current gesture settings`() {
        val fallback = UserPreferences(
            gestureSeekEnabled = false,
            gestureVolumeEnabled = false,
            gestureBrightnessEnabled = false,
            gestureZoomEnabled = false,
            gestureLeftSideIsVolume = false,
            gestureDoubleTapSeekMillis = 5_000,
            gestureSwipeDownToExitEnabled = true,
            gestureLongPressSpeed = 1.5f,
        )

        val restored = emptyMap<String, String>().toUserPreferences(fallback)

        assertFalse(restored.gestureSeekEnabled)
        assertFalse(restored.gestureVolumeEnabled)
        assertFalse(restored.gestureBrightnessEnabled)
        assertFalse(restored.gestureZoomEnabled)
        assertFalse(restored.gestureLeftSideIsVolume)
        assertEquals(5_000, restored.gestureDoubleTapSeekMillis)
        assertTrue(restored.gestureSwipeDownToExitEnabled)
        assertEquals(1.5f, restored.gestureLongPressSpeed)
    }

    @Test
    fun `damaged backup gesture entries fall back to the sanitized defaults`() {
        val restored = mapOf(
            "gestureSeekEnabled" to "yes",
            "gestureDoubleTapSeekMillis" to "12abc",
            "gestureLongPressSpeed" to "9",
        ).toUserPreferences(UserPreferences())

        assertTrue(restored.gestureSeekEnabled)
        assertEquals(10_000, restored.gestureDoubleTapSeekMillis)
        assertEquals(2f, restored.gestureLongPressSpeed)
    }
}
