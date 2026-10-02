package seeyuer.yingli.player.engine.media3

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.domain.playback.ScreenshotFailure
import seeyuer.yingli.player.domain.playback.ScreenshotGateway
import seeyuer.yingli.player.domain.playback.ScreenshotResult
import seeyuer.yingli.player.domain.playback.ScreenshotFileGateway
import seeyuer.yingli.player.domain.playback.VideoRotation
import kotlin.coroutines.resume

@OptIn(UnstableApi::class)
class Media3ScreenshotGateway(
    context: Context,
    private val controller: Media3PlaybackController,
    private val dispatchers: AppDispatchers,
    private val clock: AppClock,
) : ScreenshotGateway, ScreenshotFileGateway {
    private val resolver = context.applicationContext.contentResolver
    private val fileGateway = MediaStoreScreenshotFileGateway(resolver, dispatchers)

    override suspend fun capture(
        videoTitle: String,
        positionMillis: Long,
        rotation: VideoRotation,
    ): ScreenshotResult {
        val playerView = controller.attachedPlayerView()
            ?: return ScreenshotResult.Failed(ScreenshotFailure.EMPTY_FRAME)
        // 画面旋转时输出是 TextureView，PlayerView 只公开 SurfaceView 访问器，需要在子树里找。
        val output = playerView.videoSurfaceView ?: findTextureView(playerView)
            ?: return ScreenshotResult.Failed(ScreenshotFailure.EMPTY_FRAME)
        val width = output.width
        val height = output.height
        if (width <= 0 || height <= 0) return ScreenshotResult.Failed(ScreenshotFailure.EMPTY_FRAME)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val copied = when (output) {
            is SurfaceView -> copySurface(output, bitmap)
            is TextureView -> output.isAvailable.also { available -> if (available) output.getBitmap(bitmap) }
            else -> false
        }
        if (!copied) {
            bitmap.recycle()
            return ScreenshotResult.Failed(ScreenshotFailure.EMPTY_FRAME)
        }
        // 画面旋转是视图层变换，抓到的帧还是原始朝向，保存前按当前角度烘焙。
        val frame = if (rotation == VideoRotation.Default) bitmap else bitmap.rotatedBy(rotation.degrees)
        return withContext(dispatchers.io) {
            val safeTitle = videoTitle.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifBlank { "video" }
            val displayName = "${safeTitle}_${positionMillis}_${clock.now().toEpochMilli()}.jpg"
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext ScreenshotResult.Failed(ScreenshotFailure.READ_ONLY)
                val saved = resolver.openOutputStream(uri, "w").use { output ->
                    output != null && frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                }
                if (!saved) {
                    resolver.delete(uri, null, null)
                    ScreenshotResult.Failed(ScreenshotFailure.STORAGE_FULL)
                } else {
                    // 位置直接由 RELATIVE_PATH 与文件名拼出：写入落点就在这一处，
                    // 让 UI 去反查 MediaStore 反而多一条可能失败/漂移的路径（见 ScreenshotResult.Saved 注释）。
                    ScreenshotResult.Saved(
                        displayName = displayName,
                        uri = uri.toString(),
                        location = "$RELATIVE_PATH/$displayName",
                    )
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
                if (frame !== bitmap) frame.recycle()
                bitmap.recycle()
            }
        }
    }

    override suspend fun delete(uri: String): Result<Unit> = fileGateway.delete(uri)

    private fun Bitmap.rotatedBy(degrees: Int): Bitmap = Bitmap.createBitmap(
        this,
        0,
        0,
        width,
        height,
        Matrix().apply { postRotate(degrees.toFloat()) },
        true,
    )

    private suspend fun copySurface(surface: SurfaceView, bitmap: Bitmap): Boolean =
        suspendCancellableCoroutine { continuation ->
            PixelCopy.request(surface, bitmap, { result ->
                if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS)
            }, Handler(Looper.getMainLooper()))
        }

    /** 在 PlayerView 子树里找视频输出用的 TextureView（内容框内唯一的 TextureView）。 */
    private fun findTextureView(view: View): TextureView? {
        if (view is TextureView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findTextureView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private companion object {
        const val JPEG_QUALITY = 92

        /** 截图落点（MediaStore 相对路径）。提示给用户的保存位置就是它 + 文件名。 */
        val RELATIVE_PATH = "${Environment.DIRECTORY_PICTURES}/YingLi"
    }
}
