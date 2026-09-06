package seeyuer.yingli.player.data.sources

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import seeyuer.yingli.player.core.model.media.MediaUri

data class VideoMetadata(
    val durationMillis: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

fun interface MediaMetadataReader {
    fun read(uri: MediaUri): VideoMetadata

    companion object {
        val None = MediaMetadataReader { VideoMetadata() }
    }
}

class AndroidMediaMetadataReader(context: Context) : MediaMetadataReader {
    private val applicationContext = context.applicationContext

    override fun read(uri: MediaUri): VideoMetadata {
        val parsedUri = Uri.parse(uri.value)
        val retriever = MediaMetadataRetriever()
        return try {
            when (parsedUri.scheme) {
                ContentResolver.SCHEME_FILE, null -> retriever.setDataSource(parsedUri.path ?: uri.value)
                else -> retriever.setDataSource(applicationContext, parsedUri)
            }
            val duration = retriever.metadataLong(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.takeIf { it >= 0L }
            val sourceWidth = retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.takeIf { it > 0 }
            val sourceHeight = retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.takeIf { it > 0 }
            val rotation = retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0
            val rotated = rotation % 180 != 0
            VideoMetadata(
                durationMillis = duration,
                width = if (rotated) sourceHeight else sourceWidth,
                height = if (rotated) sourceWidth else sourceHeight,
            )
        } finally {
            retriever.release()
        }
    }

    private fun MediaMetadataRetriever.metadataLong(key: Int): Long? =
        extractMetadata(key)?.toLongOrNull()

    private fun MediaMetadataRetriever.metadataInt(key: Int): Int? =
        extractMetadata(key)?.toIntOrNull()
}
