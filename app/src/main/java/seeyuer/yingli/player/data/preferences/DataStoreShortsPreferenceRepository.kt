package seeyuer.yingli.player.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.shorts.ShortsFitMode
import seeyuer.yingli.player.domain.shorts.ShortsPreferenceRepository
import seeyuer.yingli.player.domain.shorts.ShortsPreferences

private val Context.shortsPreferencesStore by preferencesDataStore(name = "shorts_preferences")

class DataStoreShortsPreferenceRepository(context: Context) : ShortsPreferenceRepository {
    private val store = context.applicationContext.shortsPreferencesStore

    override val preferences = store.data.catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }.map { values ->
        ShortsPreferences(
            autoNext = values[AUTO_NEXT] ?: true,
            repeatCurrent = values[REPEAT_CURRENT] ?: false,
            fitMode = values[FIT_MODE]?.let { stored -> ShortsFitMode.entries.firstOrNull { it.name == stored } }
                ?: ShortsFitMode.COVER,
            lockedSpeed = values[LOCKED_SPEED]?.takeIf { it == 1f || it == 2f } ?: 1f,
            hintShown = values[HINT_SHOWN] ?: false,
        )
    }
    override val blockedMediaIds = store.data.catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }.map { values ->
        values[BLOCKED_IDS].orEmpty().lineSequence().filter(String::isNotBlank).map(::MediaItemId).toSet()
    }

    override suspend fun setAutoNext(enabled: Boolean) = update(AUTO_NEXT, enabled)
    override suspend fun setRepeatCurrent(enabled: Boolean) = update(REPEAT_CURRENT, enabled)
    override suspend fun setFitMode(mode: ShortsFitMode) { store.edit { it[FIT_MODE] = mode.name } }
    override suspend fun setLockedSpeed(speed: Float) {
        require(speed == 1f || speed == 2f)
        update(LOCKED_SPEED, speed)
    }
    override suspend fun setHintShown() = update(HINT_SHOWN, true)
    override suspend fun setBlocked(mediaId: MediaItemId, blocked: Boolean) {
        store.edit { values ->
            val ids = values[BLOCKED_IDS].orEmpty().lineSequence().filter(String::isNotBlank).toMutableSet()
            if (blocked) ids += mediaId.value else ids -= mediaId.value
            values[BLOCKED_IDS] = ids.sorted().joinToString("\n")
        }
    }

    private suspend fun update(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean) {
        store.edit { it[key] = value }
    }
    private suspend fun update(key: androidx.datastore.preferences.core.Preferences.Key<Float>, value: Float) {
        store.edit { it[key] = value }
    }

    private companion object {
        val AUTO_NEXT = booleanPreferencesKey("auto_next")
        val REPEAT_CURRENT = booleanPreferencesKey("repeat_current")
        val FIT_MODE = stringPreferencesKey("fit_mode")
        val LOCKED_SPEED = floatPreferencesKey("locked_speed")
        val HINT_SHOWN = booleanPreferencesKey("hint_shown")
        val BLOCKED_IDS = stringPreferencesKey("blocked_media_ids")
    }
}
