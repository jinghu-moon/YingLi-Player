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
        store.edit { it[LAYOUT_V3] = encode(layout) }
    }

    private fun read(preferences: Preferences): PlayerControlLayout {
        // v3 优先；读到 v2 说明是迁移前保存的布局，需要一次性回填新增按钮。
        preferences[LAYOUT_V3]?.let { return decode(it) }
        val legacy = preferences[LAYOUT_V2] ?: return PlayerControlLayout()
        return decode(legacy).ensureControls(PlayerControlSurface.TOOLS, BACKFILLED_CONTROLS)
    }

    private fun decode(raw: String): PlayerControlLayout = runCatching {
        val slots = raw.split(';').associate { section ->
            val parts = section.split('=', limit = 2)
            val surface = PlayerControlSurface.valueOf(parts[0])
            val ids = parts.getOrNull(1).orEmpty().split(',').filter(String::isNotBlank).map(PlayerControlId::valueOf)
            surface to ids
        }
        PlayerControlLayout(PlayerControlSurface.entries.associateWith { slots[it].orEmpty() })
    }.getOrElse { PlayerControlLayout() }

    private fun encode(layout: PlayerControlLayout): String = PlayerControlSurface.entries.joinToString(";") { surface ->
        "$surface=" + layout.controls(surface).joinToString(",")
    }

    private companion object {
        /** 当前版本。每次新增按钮后，把 id 追加到 [BACKFILLED_CONTROLS] 即可让老用户也看到它们。 */
        val LAYOUT_V3 = stringPreferencesKey("layout_v3")

        /** 迁移前的键：只读，用来给老布局做一次性回填。 */
        val LAYOUT_V2 = stringPreferencesKey("layout_v2")

        /** 后续新增的低频按钮登记在这里（按追加顺序补进"工具托盘"）。 */
        val BACKFILLED_CONTROLS = listOf(
            PlayerControlId.MIRROR_HORIZONTAL,
            PlayerControlId.MIRROR_VERTICAL,
            PlayerControlId.BACKGROUND_PLAYBACK,
        )
    }
}
