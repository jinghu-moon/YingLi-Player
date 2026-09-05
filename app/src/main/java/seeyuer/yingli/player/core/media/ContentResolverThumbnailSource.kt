package seeyuer.yingli.player.core.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import seeyuer.yingli.player.core.model.media.ThumbnailRequest

class ContentResolverThumbnailSource(context: Context) : ThumbnailExtractor {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val cacheDirectory = context.applicationContext.cacheDir.resolve(ThumbnailStorage.DIRECTORY_NAME)

    override suspend fun extract(request: ThumbnailRequest): Boolean {
        return try {
            load(request)?.also { bitmap ->
                cacheDirectory.mkdirs()
                val target = cacheDirectory.resolve("${request.key.diskName()}.png")
                val temporary = cacheDirectory.resolve(".${request.key.diskName()}.tmp")
                FileOutputStream(temporary).use { output ->
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
                }
                check(temporary.renameTo(target))
            } != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun load(request: ThumbnailRequest): Bitmap? = resolver.loadThumbnail(
        Uri.parse(request.uri.value),
        Size(request.widthPixels, request.heightPixels),
        null,
    )
}
