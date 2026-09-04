package seeyuer.yingli.player.core.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChunkedAeadVaultCipherTest {
    @Test
    fun `multi chunk content round trips`() {
        val source = ByteArray(150_000) { (it % 251).toByte() }
        val cipher = ChunkedAeadVaultCipher(chunkSize = 16 * 1024)
        val encrypted = ByteArrayOutputStream()
        cipher.encrypt(ByteArrayInputStream(source), encrypted, source.size.toLong(), key(), 1)
        val decrypted = ByteArrayOutputStream()

        cipher.decrypt(ByteArrayInputStream(encrypted.toByteArray()), decrypted, key = testKey)

        assertArrayEquals(source, decrypted.toByteArray())
    }

    @Test
    fun `tamper truncation wrong key and trailing data fail closed`() {
        val source = ByteArray(40_000) { it.toByte() }
        val cipher = ChunkedAeadVaultCipher(chunkSize = 16 * 1024)
        val encrypted = ByteArrayOutputStream().also {
            cipher.encrypt(ByteArrayInputStream(source), it, source.size.toLong(), key(), 1)
        }.toByteArray()
        val tampered = encrypted.copyOf().also { it[it.lastIndex - 8] = (it[it.lastIndex - 8].toInt() xor 1).toByte() }
        val truncated = encrypted.copyOf(encrypted.size - 1)
        val trailing = encrypted + byteArrayOf(1)

        assertThrows(Exception::class.java) { cipher.decrypt(ByteArrayInputStream(tampered), ByteArrayOutputStream(), testKey) }
        assertThrows(Exception::class.java) { cipher.decrypt(ByteArrayInputStream(truncated), ByteArrayOutputStream(), testKey) }
        assertThrows(Exception::class.java) { cipher.decrypt(ByteArrayInputStream(trailing), ByteArrayOutputStream(), testKey) }
        assertThrows(Exception::class.java) { cipher.decrypt(ByteArrayInputStream(encrypted), ByteArrayOutputStream(), newKey()) }
    }

    private lateinit var testKey: javax.crypto.SecretKey

    private fun key(): javax.crypto.SecretKey = newKey().also { testKey = it }

    private fun newKey(): javax.crypto.SecretKey = KeyGenerator.getInstance("AES").run {
        init(256)
        generateKey()
    }
}
