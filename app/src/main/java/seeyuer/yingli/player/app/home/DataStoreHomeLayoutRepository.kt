package seeyuer.yingli.player.app.home

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
import seeyuer.yingli.player.domain.home.HomeCardLayout
import seeyuer.yingli.player.domain.home.HomeLayoutRepository

private val Context.homeLayoutDataStore by preferencesDataStore(name = "home_layout")

class DataStoreHomeLayoutRepository(context: Context) : HomeLayoutRepository {
    private val store = context.applicationContext.homeLayoutDataStore

    override val layout: Flow<HomeCardLayout> = store.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map(::read)

    override suspend fun save(layout: HomeCardLayout) {
        store.edit { preferences ->
            preferences[ORDER] = layout.order.joinToString(SEPARATOR) { it.name }
            preferences[HIDDEN] = layout.hidden.joinToString(SEPARATOR) { it.name }
        }
    }

    private fun read(preferences: Preferences): HomeCardLayout = HomeCardLayout.normalize(
        preferences[ORDER]?.split(SEPARATOR),
        preferences[HIDDEN]?.split(SEPARATOR),
    )

    private companion object {
        const val SEPARATOR = ","
        val ORDER = stringPreferencesKey("home_card_order_v1")
        val HIDDEN = stringPreferencesKey("home_card_hidden_v1")
    }
}
