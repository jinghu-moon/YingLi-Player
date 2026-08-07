package seeyuer.yingli.player.core.media

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.foundation.AppDispatchers
import seeyuer.yingli.player.core.model.media.MediaUri

class AndroidMediaContentHasher(
    context: Context,
    private val dispatchers: AppDispatchers,
) : MediaContentHasher {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun sha256(uri: MediaUri): String? = withContext(dispatchers.io) {
        try {
            uri.openStream()?.use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().toHex()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun MediaUri.openStream(): InputStream? = when {
        value.startsWith("content://") -> resolver.openInputStream(Uri.parse(value))
        value.startsWith("file://") -> FileInputStream(File(URI(value)))
        else -> null
    }

    private fun ByteArray.toHex(): String {
        val result = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            result[index * 2] = HEX[value ushr 4]
            result[index * 2 + 1] = HEX[value and 0x0f]
        }
        return result.concatToString()
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1_024
        const val HEX = "0123456789abcdef"
    }
}
