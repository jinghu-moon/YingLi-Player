package seeyuer.yingli.player.data.security

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import seeyuer.yingli.player.domain.security.AppLockMode
import seeyuer.yingli.player.domain.security.AppLockPolicy
import seeyuer.yingli.player.domain.security.AppLockRepository
import seeyuer.yingli.player.core.security.StoredCredential

private val Context.appLockDataStore by preferencesDataStore(name = "app_lock")

class DataStoreAppLockRepository(context: Context) : AppLockRepository {
    private val dataStore = context.applicationContext.appLockDataStore

    override val policy: Flow<AppLockPolicy> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences ->
            val mode = AppLockMode.entries.firstOrNull { it.name == preferences[MODE] } ?: AppLockMode.OFF
            AppLockPolicy(
                mode,
                timeoutMillis = preferences[TIMEOUT]?.coerceAtLeast(0) ?: 0,
                lockImmediatelyOnBackground = preferences[IMMEDIATE] ?: true,
            )
        }

    override suspend fun credential(): StoredCredential? = dataStore.data.first().toCredential()

    override suspend fun configure(policy: AppLockPolicy, credential: StoredCredential?) {
        require(policy.mode == AppLockMode.OFF || policy.mode == AppLockMode.BIOMETRIC || credential != null)
        dataStore.edit { preferences ->
            preferences[MODE] = policy.mode.name
            preferences[TIMEOUT] = policy.timeoutMillis
            preferences[IMMEDIATE] = policy.lockImmediatelyOnBackground
            if (credential == null) {
                preferences.remove(SALT)
                preferences.remove(HASH)
                preferences.remove(ITERATIONS)
                preferences.remove(CREATED_AT)
            } else {
                preferences[SALT] = credential.saltBase64
                preferences[HASH] = credential.hashBase64
                preferences[ITERATIONS] = credential.iterations
                preferences[CREATED_AT] = credential.createdAtEpochMillis
            }
        }
    }

    private fun Preferences.toCredential(): StoredCredential? {
        val salt = this[SALT] ?: return null
        val hash = this[HASH] ?: return null
        val iterations = this[ITERATIONS] ?: return null
        val createdAt = this[CREATED_AT] ?: return null
        return runCatching { StoredCredential(salt, hash, iterations, createdAt) }.getOrNull()
    }

    private companion object {
        val MODE = stringPreferencesKey("mode")
        val TIMEOUT = longPreferencesKey("timeout_millis")
        val IMMEDIATE = booleanPreferencesKey("lock_immediately")
        val SALT = stringPreferencesKey("credential_salt")
        val HASH = stringPreferencesKey("credential_hash")
        val ITERATIONS = intPreferencesKey("credential_iterations")
        val CREATED_AT = longPreferencesKey("credential_created_at")
    }
}
