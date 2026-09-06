package seeyuer.yingli.player.engine.thumbnail.frame

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.effect.Presentation
import androidx.media3.inspector.frame.FrameExtractor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.model.media.ThumbnailRequest
import seeyuer.yingli.player.engine.thumbnail.ThumbnailExtractor
import seeyuer.yingli.player.engine.thumbnail.ThumbnailStorage

/** Isolates the unstable Media3 frame API behind the thumbnail infrastructure boundary. */
@UnstableApi
class Media3FrameThumbnailSource(
    context: Context,
    dispatchers: AppDispatchers,
) : ThumbnailExtractor {
    private val appContext = context.applicationContext
    private val extractorDispatcher = dispatchers.io.limitedParallelism(1)
    private val cacheDirectory = appContext.cacheDir.resolve(ThumbnailStorage.DIRECTORY_NAME)

    override suspend fun extract(request: ThumbnailRequest): Boolean = withContext(extractorDispatcher) {
        val extractor = FrameExtractor.Builder(
            appContext,
            MediaItem.fromUri(request.uri.value),
        ).setEffects(
            listOf(Presentation.createForWidthAndHeight(
                request.widthPixels,
                request.heightPixels,
                Presentation.LAYOUT_SCALE_TO_FIT,
            ))
        ).build()
        try {
            val bitmap = extractor.getThumbnail().get().bitmap
            cacheDirectory.mkdirs()
            val target = cacheDirectory.resolve("${request.key.diskName()}.png")
            val temporary = cacheDirectory.resolve(".${request.key.diskName()}.tmp")
            FileOutputStream(temporary).use { output ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
            }
            check(temporary.renameTo(target))
            true
        } finally {
            extractor.close()
        }
    }
}
