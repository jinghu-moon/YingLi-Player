package seeyuer.yingli.player.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class Pbkdf2CredentialHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val secureRandom: SecureRandom = SecureRandom(),
) : CredentialHasher {
    init { require(iterations >= StoredCredential.MINIMUM_ITERATIONS) }

    override fun create(secret: CharArray, nowEpochMillis: Long): StoredCredential {
        require(secret.size in MIN_SECRET_LENGTH..MAX_SECRET_LENGTH)
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        return try {
            val hash = derive(secret, salt, iterations)
            StoredCredential(
                Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(hash),
                iterations,
                nowEpochMillis,
            )
        } finally {
            secret.fill('\u0000')
        }
    }

    override fun verify(secret: CharArray, credential: StoredCredential): Boolean = try {
        val salt = Base64.getDecoder().decode(credential.saltBase64)
        val expected = Base64.getDecoder().decode(credential.hashBase64)
        MessageDigest.isEqual(expected, derive(secret, salt, credential.iterations))
    } catch (_: IllegalArgumentException) {
        false
    } finally {
        secret.fill('\u0000')
    }

    private fun derive(secret: CharArray, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(secret, salt, rounds, HASH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val DEFAULT_ITERATIONS = 210_000
        private const val MIN_SECRET_LENGTH = 4
        private const val MAX_SECRET_LENGTH = 128
        private const val SALT_BYTES = 16
        private const val HASH_BITS = 256
    }
}
