package seeyuer.yingli.player.engine.thumbnail.system

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import seeyuer.yingli.player.core.model.media.ThumbnailRequest
import seeyuer.yingli.player.engine.thumbnail.ThumbnailExtractor
import seeyuer.yingli.player.engine.thumbnail.ThumbnailStorage

/** Extracts deterministic artwork before paying the cost of decoding a video frame. */
class ArtworkThumbnailSource(context: Context) : ThumbnailExtractor {
    private val appContext = context.applicationContext
    private val cacheDirectory = appContext.cacheDir.resolve(ThumbnailStorage.DIRECTORY_NAME)

    override suspend fun extract(request: ThumbnailRequest): Boolean {
        return try {
            val bitmap = load(request) ?: return false
            cacheDirectory.mkdirs()
            val target = cacheDirectory.resolve("${request.key.diskName()}.png")
            val temporary = cacheDirectory.resolve(".${request.key.diskName()}.tmp")
            FileOutputStream(temporary).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            check(temporary.renameTo(target))
            bitmap.recycle()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun load(request: ThumbnailRequest): Bitmap? {
        val uri = Uri.parse(request.uri.value)
        val file = uri.path?.let(::File)?.takeIf { uri.scheme == "file" && it.isFile }
        val sibling = file?.parentFile?.let(::findSiblingArtwork)?.let { decode(it, request) }
        if (sibling != null) return sibling

        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "content") retriever.setDataSource(appContext, uri)
            else retriever.setDataSource(file?.absolutePath ?: return null)
            retriever.embeddedPicture?.let { decode(it, request) }
        } finally {
            retriever.release()
        }
    }

    private fun findSiblingArtwork(file: File): File? {
        val names = listOf("cover", "folder", "album", "poster", "thumb")
        return names.asSequence()
            .flatMap { base -> IMAGE_EXTENSIONS.asSequence().map { ext -> File(file, "$base.$ext") } }
            .firstOrNull(File::isFile)
    }

    private fun decode(file: File, request: ThumbnailRequest): Bitmap? = decode(file.readBytes(), request)

    private fun decode(bytes: ByteArray, request: ThumbnailRequest): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = calculateSample(bounds.outWidth, bounds.outHeight, request.widthPixels, request.heightPixels)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private fun calculateSample(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2
        return sample
    }

    private companion object {
        val IMAGE_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp")
    }
}
