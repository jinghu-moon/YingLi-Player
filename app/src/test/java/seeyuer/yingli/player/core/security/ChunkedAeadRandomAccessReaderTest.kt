package seeyuer.yingli.player.core.security

import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChunkedAeadRandomAccessReaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `random and cross chunk reads return authenticated plaintext`() {
        val source = ByteArray(70_000) { (it % 251).toByte() }
        val encrypted = encryptedFile(source)

        ChunkedAeadRandomAccessReader(RandomAccessFile(encrypted, "r"), key).use { reader ->
            val target = ByteArray(25_000)
            val count = reader.read(12_000, target, 0, target.size)

            assertEquals(target.size, count)
            assertArrayEquals(source.copyOfRange(12_000, 37_000), target)
            assertEquals(-1, reader.read(source.size.toLong(), target, 0, 1))
        }
    }

    @Test
    fun `tampered chunk fails reader closed for subsequent reads`() {
        val source = ByteArray(40_000) { it.toByte() }
        val encrypted = encryptedFile(source)
        RandomAccessFile(encrypted, "rw").use { file ->
            file.seek(file.length() - 8)
            file.writeByte(file.readByte().toInt() xor 1)
        }

        ChunkedAeadRandomAccessReader(RandomAccessFile(encrypted, "r"), key).use { reader ->
            val target = ByteArray(8_000)
            assertThrows(SecurityException::class.java) {
                reader.read(32_000, target, 0, target.size)
            }
            assertThrows(IllegalStateException::class.java) {
                reader.read(0, target, 0, target.size)
            }
        }
    }

    @Test
    fun `wrong key and truncated container fail closed`() {
        val source = ByteArray(20_000) { it.toByte() }
        val encrypted = encryptedFile(source)
        ChunkedAeadRandomAccessReader(RandomAccessFile(encrypted, "r"), newKey()).use { reader ->
            assertThrows(SecurityException::class.java) {
                reader.read(0, ByteArray(1), 0, 1)
            }
        }

        RandomAccessFile(encrypted, "rw").use { it.setLength(it.length() - 1) }
        RandomAccessFile(encrypted, "r").use { file ->
            assertThrows(IllegalArgumentException::class.java) {
                ChunkedAeadRandomAccessReader(file, key)
            }
        }
    }

    private val key: SecretKey by lazy(::newKey)

    private fun encryptedFile(source: ByteArray): java.io.File = temporaryFolder.newFile().also { file ->
        FileOutputStream(file).use { output ->
            ChunkedAeadVaultCipher(chunkSize = 16 * 1024).encrypt(
                ByteArrayInputStream(source),
                output,
                source.size.toLong(),
                key,
                1,
            )
        }
    }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").run {
        init(256)
        generateKey()
    }
}
