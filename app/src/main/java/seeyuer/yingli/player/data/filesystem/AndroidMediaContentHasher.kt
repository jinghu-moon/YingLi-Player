package seeyuer.yingli.player.data.filesystem

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.catalog.MediaContentHasher

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

    /**
     * 当前字节数。L4 最终复核拿它和「扫描时记下的大小」比。
     *
     * 走的是与 [sha256] 同一套打开方式：`content://` 用 `openFileDescriptor`，
     * `file://` 用文件通道。**读不出来返回 null**，不返回 `0`——`0` 会被当成
     * 「文件变成了空文件」从而误判成变更。
     */
    override suspend fun size(uri: MediaUri): Long? = withContext(dispatchers.io) {
        try {
            when {
                uri.value.startsWith("content://") ->
                    resolver.openFileDescriptor(Uri.parse(uri.value), "r")?.use { descriptor ->
                        descriptor.statSize.takeIf { it >= 0 }
                    }

                uri.value.startsWith("file://") ->
                    File(URI(uri.value)).takeIf(File::isFile)?.length()

                else -> null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /**
     * L1 快速指纹：`SHA-256(8 字节大端 size + 头 64 KiB + 尾 64 KiB)`。
     *
     * 代价与文件大小无关（某 4 GB 视频与某 40 MB 视频都只读 128 KiB），
     * 只用来把「大小相同但内容不同」的候选剪掉；**它不是 EXACT 判定的依据**。
     * 文件小于 64 KiB 时头尾自然重叠，结果仍然确定。
     */
    override suspend fun quickFingerprint(uri: MediaUri, sizeBytes: Long): String? =
        withContext(dispatchers.io) {
            if (sizeBytes < 0) return@withContext null
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(
                    ByteArray(SIZE_FIELD_BYTES) { index ->
                        (sizeBytes ushr ((SIZE_FIELD_BYTES - 1 - index) * Byte.SIZE_BITS)).toByte()
                    },
                )
                val channel = uri.openFileChannel() ?: return@withContext null
                channel.use {
                    digest.update(readRange(it, from = 0L, to = sizeBytes))
                    val tailStart = sizeBytes - QUICK_FINGERPRINT_BYTES
                    if (tailStart > 0L) digest.update(readRange(it, from = tailStart, to = sizeBytes))
                }
                digest.digest().toHex()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    private fun MediaUri.openFileChannel(): FileChannel? = try {
        when {
            value.startsWith("content://") ->
                resolver.openFileDescriptor(Uri.parse(value), "r")?.let { descriptor ->
                    FileInputStream(descriptor.fileDescriptor).channel
                }

            value.startsWith("file://") -> FileInputStream(File(URI(value))).channel
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /** 读 `[from, to)` 这么长的一段，但**最多读 [QUICK_FINGERPRINT_BYTES]**。 */
    private suspend fun readRange(channel: FileChannel, from: Long, to: Long): ByteArray {
        val length = (to - from).coerceIn(0L, QUICK_FINGERPRINT_BYTES.toLong()).toInt()
        if (length == 0) return ByteArray(0)
        val buffer = ByteBuffer.allocate(length)
        channel.position(from)
        while (buffer.hasRemaining()) {
            coroutineContext.ensureActive()
            if (channel.read(buffer) < 0) break
        }
        return buffer.array().copyOf(buffer.position())
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
        const val QUICK_FINGERPRINT_BYTES = 64 * 1_024
        const val SIZE_FIELD_BYTES = 8
        const val HEX = "0123456789abcdef"
    }
}
