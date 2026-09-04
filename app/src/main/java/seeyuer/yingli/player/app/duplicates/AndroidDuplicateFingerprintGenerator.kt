package seeyuer.yingli.player.app.duplicates

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.foundation.AppDispatchers
import seeyuer.yingli.player.domain.duplicates.DuplicateFingerprintGenerator

class AndroidDuplicateFingerprintGenerator(
    context: Context,
    private val dispatchers: AppDispatchers,
) : DuplicateFingerprintGenerator {
    private val applicationContext = context.applicationContext

    override suspend fun size(uri: String): Long? = withContext(dispatchers.io) {
        val parsed = Uri.parse(uri)
        applicationContext.contentResolver.openAssetFileDescriptor(parsed, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0 } ?: descriptor.parcelFileDescriptor.statSize.takeIf { it >= 0 }
        }
    }

    override suspend fun quickHash(uri: String, sizeBytes: Long): String? = withContext(dispatchers.io) {
        try {
            ensureActive()
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(sizeBytes).array())
            applicationContext.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                val buffer = ByteArray(QUICK_HASH_BYTES)
                val count = stream.read(buffer)
                if (count > 0) digest.update(buffer, 0, count)
            } ?: return@withContext null
            ensureActive()
            if (sizeBytes > QUICK_HASH_BYTES) {
                applicationContext.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { descriptor ->
                    FileInputStream(descriptor.fileDescriptor).use { stream ->
                        stream.channel.position((sizeBytes - QUICK_HASH_BYTES).coerceAtLeast(0))
                        val buffer = ByteArray(QUICK_HASH_BYTES)
                        val count = stream.read(buffer)
                        if (count > 0) digest.update(buffer, 0, count)
                    }
                } ?: return@withContext null
            }
            digest.digest().hex()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun fullHash(uri: String): String? = withContext(dispatchers.io) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            applicationContext.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                while (true) {
                    ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
                buffer.fill(0)
            } ?: return@withContext null
            digest.digest().hex()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun perceptualHashes(uri: String, durationMillis: Long): List<Long> = withContext(dispatchers.io) {
        try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(applicationContext, Uri.parse(uri))
                FRAME_POSITIONS.mapNotNull { fraction ->
                    ensureActive()
                    val timeMicros = (durationMillis * fraction * 1_000).toLong()
                    retriever.getFrameAtTime(timeMicros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.useBitmap(::averageHash)
                }
            } finally {
                retriever.release()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun averageHash(source: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(source, HASH_SIDE, HASH_SIDE, true)
        return try {
            val pixels = IntArray(HASH_SIDE * HASH_SIDE)
            scaled.getPixels(pixels, 0, HASH_SIDE, 0, 0, HASH_SIDE, HASH_SIDE)
            val luminance = pixels.map { pixel ->
                val red = pixel shr 16 and 0xff
                val green = pixel shr 8 and 0xff
                val blue = pixel and 0xff
                (red * 299 + green * 587 + blue * 114) / 1_000
            }
            val average = luminance.average()
            luminance.foldIndexed(0L) { index, hash, value ->
                if (value >= average) hash or (1L shl index) else hash
            }
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }

    private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T = try { block(this) } finally { recycle() }
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val QUICK_HASH_BYTES = 64 * 1024
        const val STREAM_BUFFER_BYTES = 256 * 1024
        const val HASH_SIDE = 8
        val FRAME_POSITIONS = listOf(0.2, 0.5, 0.8)
    }
}
