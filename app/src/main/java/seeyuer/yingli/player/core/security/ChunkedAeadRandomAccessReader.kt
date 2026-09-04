package seeyuer.yingli.player.core.security

import java.io.RandomAccessFile
import javax.crypto.SecretKey

class ChunkedAeadRandomAccessReader(
    private val file: RandomAccessFile,
    private val key: SecretKey,
) : SecureRandomAccessReader {
    private val manifest: VaultManifest
    private val noncePrefix: ByteArray
    private val chunks: List<ChunkLocation>
    private var cachedIndex = -1
    private var cachedPlain: ByteArray? = null
    private var failed = false
    private var closed = false

    init {
        file.seek(0)
        val magic = ByteArray(ChunkedAeadVaultCipher.MAGIC.size).also(file::readFully)
        require(magic.contentEquals(ChunkedAeadVaultCipher.MAGIC)) { "INVALID_VAULT_MAGIC" }
        manifest = VaultManifest(file.readInt(), file.readInt(), file.readInt(), file.readLong())
        require(manifest.formatVersion == ChunkedAeadVaultCipher.FORMAT_VERSION)
        noncePrefix = ByteArray(ChunkedAeadVaultCipher.NONCE_PREFIX_BYTES).also(file::readFully)
        val locations = mutableListOf<ChunkLocation>()
        var remaining = manifest.originalSize
        while (remaining > 0) {
            val expected = minOf(manifest.chunkSize.toLong(), remaining).toInt()
            val plainLength = file.readInt()
            val cipherLength = file.readInt()
            require(plainLength == expected)
            require(cipherLength == plainLength + ChunkedAeadVaultCipher.GCM_TAG_BYTES)
            val offset = file.filePointer
            require(offset + cipherLength <= file.length()) { "TRUNCATED_VAULT" }
            locations += ChunkLocation(offset, plainLength, cipherLength)
            file.seek(offset + cipherLength)
            remaining -= plainLength
        }
        require(file.filePointer == file.length()) { "TRAILING_VAULT_DATA" }
        chunks = locations
    }

    override val size: Long get() = manifest.originalSize

    @Synchronized
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        check(!closed)
        check(!failed) { "VAULT_READER_FAILED" }
        require(position >= 0 && offset >= 0 && length >= 0 && offset + length <= buffer.size)
        if (position >= size) return -1
        if (length == 0) return 0
        var sourcePosition = position
        var targetOffset = offset
        var remaining = minOf(length.toLong(), size - position).toInt()
        val requested = remaining
        while (remaining > 0) {
            val chunkIndex = (sourcePosition / manifest.chunkSize).toInt()
            val inChunk = (sourcePosition % manifest.chunkSize).toInt()
            val plain = plaintext(chunkIndex)
            val count = minOf(remaining, plain.size - inChunk)
            plain.copyInto(buffer, targetOffset, inChunk, inChunk + count)
            targetOffset += count
            sourcePosition += count
            remaining -= count
        }
        return requested
    }

    private fun plaintext(index: Int): ByteArray {
        if (cachedIndex == index) return requireNotNull(cachedPlain)
        cachedPlain?.fill(0)
        val location = chunks[index]
        file.seek(location.offset)
        val encrypted = ByteArray(location.cipherLength).also(file::readFully)
        return try {
            ChunkedAeadVaultCipher.decryptChunk(
                key,
                noncePrefix,
                manifest,
                index,
                location.plainLength,
                encrypted,
            ).also {
                cachedIndex = index
                cachedPlain = it
            }
        } catch (error: Exception) {
            failed = true
            cachedPlain?.fill(0)
            cachedPlain = null
            cachedIndex = -1
            throw error
        } finally {
            encrypted.fill(0)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        cachedPlain?.fill(0)
        cachedPlain = null
        cachedIndex = -1
        failed = true
        closed = true
        file.close()
    }

    private data class ChunkLocation(val offset: Long, val plainLength: Int, val cipherLength: Int)
}
