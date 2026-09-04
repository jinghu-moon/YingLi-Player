package seeyuer.yingli.player.core.security

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ChunkedAeadVaultCipher(
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val secureRandom: SecureRandom = SecureRandom(),
) : VaultCipher {
    init { require(chunkSize in VaultManifest.MIN_CHUNK_SIZE..VaultManifest.MAX_CHUNK_SIZE) }

    override fun encrypt(
        input: InputStream,
        output: OutputStream,
        originalSize: Long,
        key: SecretKey,
        keyVersion: Int,
    ): VaultManifest {
        require(originalSize >= 0 && keyVersion > 0)
        val manifest = VaultManifest(FORMAT_VERSION, keyVersion, chunkSize, originalSize)
        val prefix = ByteArray(NONCE_PREFIX_BYTES).also(secureRandom::nextBytes)
        val data = DataOutputStream(output)
        data.write(MAGIC)
        data.writeInt(manifest.formatVersion)
        data.writeInt(manifest.keyVersion)
        data.writeInt(manifest.chunkSize)
        data.writeLong(manifest.originalSize)
        data.write(prefix)
        val buffer = ByteArray(chunkSize)
        var remaining = originalSize
        var counter = 0
        while (remaining > 0) {
            val expected = minOf(buffer.size.toLong(), remaining).toInt()
            input.readExactly(buffer, expected)
            val encrypted = crypt(Cipher.ENCRYPT_MODE, key, nonce(prefix, counter), aad(manifest, counter, expected), buffer, expected)
            data.writeInt(expected)
            data.writeInt(encrypted.size)
            data.write(encrypted)
            remaining -= expected
            counter++
        }
        check(input.read() == -1) { "SOURCE_SIZE_CHANGED" }
        data.flush()
        buffer.fill(0)
        return manifest
    }

    override fun decrypt(input: InputStream, output: OutputStream, key: SecretKey): VaultManifest {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size).also(data::readFully)
        require(magic.contentEquals(MAGIC)) { "INVALID_VAULT_MAGIC" }
        val manifest = VaultManifest(
            formatVersion = data.readInt(),
            keyVersion = data.readInt(),
            chunkSize = data.readInt(),
            originalSize = data.readLong(),
        )
        require(manifest.formatVersion == FORMAT_VERSION) { "UNSUPPORTED_VAULT_VERSION" }
        val prefix = ByteArray(NONCE_PREFIX_BYTES).also(data::readFully)
        var remaining = manifest.originalSize
        var counter = 0
        while (remaining > 0) {
            val expected = minOf(manifest.chunkSize.toLong(), remaining).toInt()
            val plainLength = data.readInt()
            val cipherLength = data.readInt()
            require(plainLength == expected) { "INVALID_CHUNK_LENGTH" }
            require(cipherLength == plainLength + GCM_TAG_BYTES) { "INVALID_CIPHER_LENGTH" }
            val encrypted = ByteArray(cipherLength).also(data::readFully)
            val plain = crypt(Cipher.DECRYPT_MODE, key, nonce(prefix, counter), aad(manifest, counter, plainLength), encrypted, cipherLength)
            require(plain.size == plainLength) { "INVALID_PLAIN_LENGTH" }
            output.write(plain)
            plain.fill(0)
            remaining -= plainLength
            counter++
        }
        require(data.read() == -1) { "TRAILING_VAULT_DATA" }
        output.flush()
        return manifest
    }

    private fun crypt(
        mode: Int,
        key: SecretKey,
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray,
        length: Int,
    ): ByteArray = try {
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAAD(aad)
            doFinal(input, 0, length)
        }
    } catch (error: GeneralSecurityException) {
        throw SecurityException("VAULT_AUTHENTICATION_FAILED", error)
    }

    private fun InputStream.readExactly(buffer: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val read = read(buffer, offset, length - offset)
            if (read < 0) throw EOFException("TRUNCATED_SOURCE")
            offset += read
        }
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val DEFAULT_CHUNK_SIZE = 1024 * 1024
        internal val MAGIC = "YLVLT001".toByteArray(Charsets.US_ASCII)
        internal const val NONCE_PREFIX_BYTES = 8
        internal const val NONCE_BYTES = 12
        internal const val GCM_TAG_BITS = 128
        internal const val GCM_TAG_BYTES = GCM_TAG_BITS / 8

        internal fun nonce(prefix: ByteArray, counter: Int): ByteArray = ByteBuffer.allocate(NONCE_BYTES)
            .put(prefix)
            .putInt(counter)
            .array()

        internal fun aad(manifest: VaultManifest, counter: Int, plainLength: Int): ByteArray =
            ByteBuffer.allocate(Int.SIZE_BYTES * 5 + Long.SIZE_BYTES)
                .putInt(manifest.formatVersion)
                .putInt(manifest.keyVersion)
                .putInt(manifest.chunkSize)
                .putLong(manifest.originalSize)
                .putInt(counter)
                .putInt(plainLength)
                .array()

        internal fun decryptChunk(
            key: SecretKey,
            prefix: ByteArray,
            manifest: VaultManifest,
            counter: Int,
            plainLength: Int,
            encrypted: ByteArray,
        ): ByteArray = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce(prefix, counter)))
                updateAAD(aad(manifest, counter, plainLength))
                doFinal(encrypted)
            }
        } catch (error: GeneralSecurityException) {
            throw SecurityException("VAULT_AUTHENTICATION_FAILED", error)
        }
    }
}
