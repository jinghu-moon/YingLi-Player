package seeyuer.yingli.player.data.processing.clips

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.domain.clips.TimelineFrame
import seeyuer.yingli.player.domain.clips.TimelineFrameProvider
import seeyuer.yingli.player.domain.clips.TimelineFrameRequest
import kotlin.coroutines.coroutineContext

class AndroidTimelineFrameProvider(
    context: Context,
    private val dispatchers: AppDispatchers,
) : TimelineFrameProvider {
    private val applicationContext = context.applicationContext
    private val root = File(applicationContext.cacheDir, FRAME_DIRECTORY)

    override suspend fun frames(request: TimelineFrameRequest): List<TimelineFrame> = withContext(dispatchers.io) {
        val retriever = MediaMetadataRetriever()
        try {
            val uri = Uri.parse(request.sourceUri)
            if (uri.scheme == null || uri.scheme == "file") {
                retriever.setDataSource(uri.path ?: request.sourceUri)
            } else {
                retriever.setDataSource(applicationContext, uri)
            }
            val sourceDirectory = File(root, request.sourceUri.sha256()).also { it.mkdirs() }
            val interval = (request.visibleEndMillis - request.visibleStartMillis) / request.maximumFrames
            (0 until request.maximumFrames).mapNotNull { index ->
                coroutineContext.ensureActive()
                val position = request.visibleStartMillis + interval * index
                val output = File(sourceDirectory, "$position.jpg")
                if (!output.isFile) {
                    val bitmap = retriever.getScaledFrameAtTime(
                        position * MICROS_PER_MILLI,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        FRAME_WIDTH,
                        FRAME_HEIGHT,
                    ) ?: return@mapNotNull null
                    output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                    bitmap.recycle()
                }
                TimelineFrame(position, output.toURI().toString())
            }
        } finally {
            retriever.release()
        }
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val FRAME_DIRECTORY = "timeline-frames"
        const val FRAME_WIDTH = 320
        const val FRAME_HEIGHT = 180
        const val JPEG_QUALITY = 82
        const val MICROS_PER_MILLI = 1_000L
    }
}
