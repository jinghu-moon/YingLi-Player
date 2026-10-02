package seeyuer.yingli.player.engine.media3

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.core.common.DefaultAppDispatchers

@RunWith(AndroidJUnit4::class)
class MediaStoreScreenshotFileGatewayTest {
    @Test
    fun insertedMediaStoreScreenshotCanBeDeletedByReturnedUri() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "yingli-test-${System.nanoTime()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/YingLiTest")
            },
        ) ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri).use { output -> output?.write(byteArrayOf(1, 2, 3)) }

        val result = MediaStoreScreenshotFileGateway(resolver, DefaultAppDispatchers).delete(uri.toString())

        assertTrue(result.isSuccess)
        assertEquals(0, resolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { it.count })
    }

    @Test
    fun invalidUriIsRejectedWithoutTouchingMediaStore() = runTest {
        val result = MediaStoreScreenshotFileGateway(
            ApplicationProvider.getApplicationContext<Context>().contentResolver,
            DefaultAppDispatchers,
        ).delete("")

        assertFalse(result.isSuccess)
        assertEquals("SCREENSHOT_URI_INVALID", result.exceptionOrNull()?.message)
    }
}
