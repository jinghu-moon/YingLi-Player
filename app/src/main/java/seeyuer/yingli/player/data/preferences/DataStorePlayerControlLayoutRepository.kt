package seeyuer.yingli.player.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import seeyuer.yingli.player.domain.playback.PlayerControlSurface
import seeyuer.yingli.player.domain.playback.PlayerControlId
import seeyuer.yingli.player.domain.playback.PlayerControlLayout
import seeyuer.yingli.player.domain.playback.PlayerControlLayoutRepository

private val Context.playerControlLayoutStore by preferencesDataStore(name = "player_control_layout")

class DataStorePlayerControlLayoutRepository(context: Context) : PlayerControlLayoutRepository {
    private val store = context.applicationContext.playerControlLayoutStore
    override val layout: Flow<PlayerControlLayout> = store.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map(::read)

    override suspend fun set(layout: PlayerControlLayout) {
        store.edit { it[LAYOUT] = encode(layout) }
    }

    private fun read(preferences: Preferences): PlayerControlLayout {
        val raw = preferences[LAYOUT] ?: return PlayerControlLayout()
        return runCatching {
            val slots = raw.split(';').associate { section ->
                val parts = section.split('=', limit = 2)
                val surface = PlayerControlSurface.valueOf(parts[0])
                val ids = parts.getOrNull(1).orEmpty().split(',').filter(String::isNotBlank).map(PlayerControlId::valueOf)
                surface to ids
            }
            PlayerControlLayout(PlayerControlSurface.entries.associateWith { slots[it].orEmpty() })
        }.getOrElse { PlayerControlLayout() }
    }

    private fun encode(layout: PlayerControlLayout): String = PlayerControlSurface.entries.joinToString(";") { surface ->
        "$surface=" + layout.controls(surface).joinToString(",")
    }

    private companion object { val LAYOUT = stringPreferencesKey("layout_v2") }
}
