package seeyuer.yingli.player.core.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import java.io.File
import java.io.FileOutputStream
import seeyuer.yingli.player.core.model.media.ThumbnailRequest

class ContentResolverThumbnailSource(context: Context) : ThumbnailExtractor {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val cacheDirectory = context.applicationContext.cacheDir.resolve("thumbnails")

    override suspend fun extract(request: ThumbnailRequest): Boolean {
        return runCatching {
            load(request)?.also { bitmap ->
                cacheDirectory.mkdirs()
                FileOutputStream(cacheDirectory.resolve("${request.key.diskName()}.png")).use { output ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                }
            } != null
        }.getOrDefault(false)
    }

    private fun load(request: ThumbnailRequest): Bitmap? = resolver.loadThumbnail(
        Uri.parse(request.uri.value),
        Size(request.widthPixels, request.heightPixels),
        null,
    )
}
