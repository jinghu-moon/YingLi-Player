package seeyuer.yingli.player.app

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Rational
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.foundation.AppClock
import seeyuer.yingli.player.core.foundation.AppDispatchers
import seeyuer.yingli.player.domain.playback.PictureInPictureGateway
import seeyuer.yingli.player.domain.playback.ScreenshotFailure
import seeyuer.yingli.player.domain.playback.ScreenshotGateway
import seeyuer.yingli.player.domain.playback.ScreenshotResult
import kotlin.coroutines.resume

class ActivityPictureInPictureGateway(private val activity: Activity) : PictureInPictureGateway {
    override fun isAvailable(): Boolean = activity.packageManager.hasSystemFeature("android.software.picture_in_picture")

    override fun enter(): Boolean {
        if (!isAvailable()) return false
        val parameters = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        return activity.enterPictureInPictureMode(parameters)
    }
}

@OptIn(UnstableApi::class)
class Media3ScreenshotGateway(
    context: Context,
    private val controller: Media3PlaybackController,
    private val dispatchers: AppDispatchers,
    private val clock: AppClock,
) : ScreenshotGateway {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun capture(videoTitle: String, positionMillis: Long): ScreenshotResult {
        val playerView = controller.attachedPlayerView()
            ?: return ScreenshotResult.Failed(ScreenshotFailure.EMPTY_FRAME)
        val surface = playerView.videoSurfaceView
            ?: return ScreenshotResult.Failed(ScreenshotFailure.EMPTY_FRAME)
        val width = surface.width
        val height = surface.height
        if (width <= 0 || height <= 0) return ScreenshotResult.Failed(ScreenshotFailure.EMPTY_FRAME)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val copied = when (surface) {
            is SurfaceView -> copySurface(surface, bitmap)
            is TextureView -> surface.isAvailable.also { available -> if (available) surface.getBitmap(bitmap) }
            else -> false
        }
        if (!copied) {
            bitmap.recycle()
            return ScreenshotResult.Failed(ScreenshotFailure.EMPTY_FRAME)
        }
        return withContext(dispatchers.io) {
            val safeTitle = videoTitle.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifBlank { "video" }
            val displayName = "${safeTitle}_${positionMillis}_${clock.now().toEpochMilli()}.jpg"
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/YingLi")
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext ScreenshotResult.Failed(ScreenshotFailure.READ_ONLY)
                val saved = resolver.openOutputStream(uri, "w").use { output ->
                    output != null && bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                }
                if (!saved) {
                    resolver.delete(uri, null, null)
                    ScreenshotResult.Failed(ScreenshotFailure.STORAGE_FULL)
                } else {
                    ScreenshotResult.Saved(displayName)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                ScreenshotResult.Failed(ScreenshotFailure.PERMISSION)
            } catch (_: IOException) {
                ScreenshotResult.Failed(ScreenshotFailure.STORAGE_FULL)
            } catch (_: Exception) {
                ScreenshotResult.Failed(ScreenshotFailure.UNKNOWN)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private suspend fun copySurface(surface: SurfaceView, bitmap: Bitmap): Boolean =
        suspendCancellableCoroutine { continuation ->
            PixelCopy.request(surface, bitmap, { result ->
                if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS)
            }, Handler(Looper.getMainLooper()))
        }

    private companion object {
        const val JPEG_QUALITY = 92
    }
}
