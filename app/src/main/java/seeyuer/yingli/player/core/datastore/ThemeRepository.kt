package seeyuer.yingli.player.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

enum class ThemePreference {
    LIGHT,
    DARK,
    SYSTEM;

    companion object {
        fun fromStoredValue(value: String?): ThemePreference =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

data class AppearanceSettings(
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val dynamicColorEnabled: Boolean = false,
    val processingPinned: Boolean = false,
)

interface ThemeRepository {
    val settings: Flow<AppearanceSettings>

    suspend fun setThemePreference(preference: ThemePreference)

    suspend fun setDynamicColorEnabled(enabled: Boolean)

    suspend fun setProcessingPinned(pinned: Boolean)
}

private val Context.appearanceDataStore by preferencesDataStore(name = "appearance_preferences")

class DataStoreThemeRepository(context: Context) : ThemeRepository {
    private val dataStore = context.applicationContext.appearanceDataStore

    override val settings: Flow<AppearanceSettings> = dataStore.data
        .catch { cause ->
            if (cause is IOException) {
                emit(androidx.datastore.preferences.core.emptyPreferences())
            } else {
                throw cause
            }
        }
        .map { preferences ->
            AppearanceSettings(
                themePreference = ThemePreference.fromStoredValue(preferences[THEME_KEY]),
                dynamicColorEnabled = preferences[DYNAMIC_COLOR_KEY] ?: false,
                processingPinned = preferences[PROCESSING_PINNED_KEY] ?: false,
            )
        }

    override suspend fun setThemePreference(preference: ThemePreference) {
        dataStore.edit { preferences -> preferences[THEME_KEY] = preference.name }
    }

    override suspend fun setDynamicColorEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[DYNAMIC_COLOR_KEY] = enabled }
    }

    override suspend fun setProcessingPinned(pinned: Boolean) {
        dataStore.edit { preferences -> preferences[PROCESSING_PINNED_KEY] = pinned }
    }

    private companion object {
        val THEME_KEY = stringPreferencesKey("theme_preference")
        val DYNAMIC_COLOR_KEY = booleanPreferencesKey("dynamic_color_enabled")
        val PROCESSING_PINNED_KEY = booleanPreferencesKey("processing_pinned")
    }
}
