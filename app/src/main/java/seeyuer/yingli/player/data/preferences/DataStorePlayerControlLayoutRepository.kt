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
import seeyuer.yingli.player.domain.playback.PlayerControlLayout
import seeyuer.yingli.player.domain.playback.PlayerControlLayoutCodec
import seeyuer.yingli.player.domain.playback.PlayerControlLayoutRepository

private val Context.playerControlLayoutStore by preferencesDataStore(name = "player_control_layout")

/** 当前写入键。布局的格式版本写在 value 里，键名只表示"这份数据由哪一代实现写下"。 */
private val PLAYER_CONTROL_LAYOUT_KEY = stringPreferencesKey("layout_v4")

/**
 * 迁移前的键：只读兜底、永不删除。
 * 它们写下的值没有版本前缀，[PlayerControlLayoutCodec] 会按 legacy 处理并回填新增按钮。
 */
private val PLAYER_CONTROL_LAYOUT_LEGACY_KEYS = listOf(
    stringPreferencesKey("layout_v3"),
    stringPreferencesKey("layout_v2"),
)

/** 读取顺序：新键优先。 */
private val PLAYER_CONTROL_LAYOUT_KEYS = listOf(PLAYER_CONTROL_LAYOUT_KEY) + PLAYER_CONTROL_LAYOUT_LEGACY_KEYS

class DataStorePlayerControlLayoutRepository(context: Context) : PlayerControlLayoutRepository {
    private val store = context.applicationContext.playerControlLayoutStore
    override val layout: Flow<PlayerControlLayout> = store.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { it.readPlayerControlLayout() }

    override suspend fun set(layout: PlayerControlLayout) {
        store.edit { it[PLAYER_CONTROL_LAYOUT_KEY] = PlayerControlLayoutCodec.encode(layout) }
    }
}

/**
 * 按新键 → 老键的顺序取第一份存在的布局。
 * 版本判断与回填都在 value 里完成（见 [PlayerControlLayoutCodec]）：老键内容没有版本前缀，
 * 因此升级后第一次读取就会补齐新增按钮；用户之后的一次写入落到新键，回填即停止。
 */
internal fun Preferences.readPlayerControlLayout(): PlayerControlLayout {
    val stored = PLAYER_CONTROL_LAYOUT_KEYS.firstNotNullOfOrNull { this[it] } ?: return PlayerControlLayout()
    return PlayerControlLayoutCodec.decode(stored)
}
