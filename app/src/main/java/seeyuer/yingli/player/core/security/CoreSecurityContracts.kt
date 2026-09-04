package seeyuer.yingli.player.core.security

import java.io.InputStream
import java.io.OutputStream
import javax.crypto.SecretKey

data class StoredCredential(
    val saltBase64: String,
    val hashBase64: String,
    val iterations: Int,
    val createdAtEpochMillis: Long,
) {
    init {
        require(saltBase64.isNotBlank() && hashBase64.isNotBlank())
        require(iterations >= MINIMUM_ITERATIONS)
        require(createdAtEpochMillis >= 0)
    }

    companion object { const val MINIMUM_ITERATIONS = 120_000 }
}

interface CredentialHasher {
    fun create(secret: CharArray, nowEpochMillis: Long): StoredCredential
    fun verify(secret: CharArray, credential: StoredCredential): Boolean
}

data class VaultManifest(
    val formatVersion: Int,
    val keyVersion: Int,
    val chunkSize: Int,
    val originalSize: Long,
) {
    init {
        require(formatVersion > 0 && keyVersion > 0)
        require(chunkSize in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE)
        require(originalSize >= 0)
    }

    companion object {
        const val MIN_CHUNK_SIZE = 16 * 1024
        const val MAX_CHUNK_SIZE = 4 * 1024 * 1024
    }
}

interface VaultCipher {
    fun encrypt(
        input: InputStream,
        output: OutputStream,
        originalSize: Long,
        key: SecretKey,
        keyVersion: Int,
    ): VaultManifest

    fun decrypt(input: InputStream, output: OutputStream, key: SecretKey): VaultManifest
}

interface SecureRandomAccessReader : AutoCloseable {
    val size: Long
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}
