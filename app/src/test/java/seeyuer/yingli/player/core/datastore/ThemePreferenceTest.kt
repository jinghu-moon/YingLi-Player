package seeyuer.yingli.player.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePreferenceTest {
    @Test
    fun `stored values restore all three theme modes`() {
        ThemePreference.entries.forEach { preference ->
            assertEquals(preference, ThemePreference.fromStoredValue(preference.name))
        }
    }

    @Test
    fun `missing and invalid values fall back to system`() {
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStoredValue(null))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStoredValue("INVALID"))
    }

    @Test
    fun `appearance defaults do not enable dynamic color or processing destination`() {
        assertEquals(
            AppearanceSettings(
                themePreference = ThemePreference.SYSTEM,
                dynamicColorEnabled = false,
                processingPinned = false,
            ),
            AppearanceSettings(),
        )
    }
}
