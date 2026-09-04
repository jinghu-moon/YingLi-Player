package seeyuer.yingli.player.app.security

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.foundation.AppDispatchers
import seeyuer.yingli.player.domain.security.KeyAccessResult
import seeyuer.yingli.player.domain.security.KeyManagementGateway

class AndroidKeystoreKeyManagementGateway(
    context: Context,
    private val dispatchers: AppDispatchers,
) : KeyManagementGateway {
    private val preferences = context.applicationContext.getSharedPreferences("vault_key_state", Context.MODE_PRIVATE)

    override suspend fun create(): KeyAccessResult = withContext(dispatchers.io) {
        val current = preferences.getInt(KEY_CURRENT_VERSION, 0)
        if (current > 0) return@withContext keyBlocking(current)
        createVersion(1)
    }

    override suspend fun current(): KeyAccessResult = withContext(dispatchers.io) {
        val version = preferences.getInt(KEY_CURRENT_VERSION, 0)
        if (version <= 0) KeyAccessResult.Unavailable else keyBlocking(version)
    }

    override suspend fun key(version: Int): KeyAccessResult = withContext(dispatchers.io) {
        if (version <= 0) KeyAccessResult.Unavailable else keyBlocking(version)
    }

    override suspend fun rotate(): KeyAccessResult = withContext(dispatchers.io) {
        val next = preferences.getInt(KEY_CURRENT_VERSION, 0).coerceAtLeast(0) + 1
        createVersion(next)
    }

    override suspend fun invalidate() = withContext(dispatchers.io) {
        val store = keyStore()
        store.aliases().toList().filter { it.startsWith(ALIAS_PREFIX) }.forEach(store::deleteEntry)
        preferences.edit().remove(KEY_CURRENT_VERSION).apply()
    }

    @SuppressLint("ApplySharedPref")
    private fun createVersion(version: Int): KeyAccessResult = runCatching {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(KeyGenParameterSpec.Builder(
            alias(version),
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUnlockedDeviceRequired(true)
            .build())
        generator.generateKey()
        check(preferences.edit().putInt(KEY_CURRENT_VERSION, version).commit()) {
            "KEY_VERSION_PERSIST_FAILED"
        }
        keyBlocking(version)
    }.getOrElse { KeyAccessResult.Unavailable }

    private fun keyBlocking(version: Int): KeyAccessResult = try {
        val key = keyStore().getKey(alias(version), null) as? SecretKey ?: return KeyAccessResult.Unavailable
        KeyAccessResult.Available(key, version)
    } catch (_: UserNotAuthenticatedException) {
        KeyAccessResult.AuthenticationRequired
    } catch (_: KeyPermanentlyInvalidatedException) {
        KeyAccessResult.PermanentlyInvalidated
    } catch (_: Exception) {
        KeyAccessResult.Unavailable
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    private fun alias(version: Int): String = "$ALIAS_PREFIX$version"

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val ALIAS_PREFIX = "yingli_vault_v"
        const val KEY_CURRENT_VERSION = "current_version"
    }
}
