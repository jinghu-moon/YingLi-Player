package seeyuer.yingli.player.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

interface MediaOnboardingRepository {
    val completed: Flow<Boolean>
    suspend fun setCompleted(completed: Boolean)
}

private val Context.mediaOnboardingDataStore by preferencesDataStore(name = "media_onboarding")

class DataStoreMediaOnboardingRepository(context: Context) : MediaOnboardingRepository {
    private val store = context.applicationContext.mediaOnboardingDataStore
    override val completed: Flow<Boolean> = store.data
        .catch { cause ->
            if (cause is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw cause
        }
        .map { it[COMPLETED] ?: false }

    override suspend fun setCompleted(completed: Boolean) {
        store.edit { it[COMPLETED] = completed }
    }

    private companion object { val COMPLETED = booleanPreferencesKey("completed") }
}
