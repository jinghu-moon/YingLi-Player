package seeyuer.yingli.player.data.filesystem

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.core.common.DefaultAppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.library.FileOperationFailure
import seeyuer.yingli.player.domain.library.FileOperationResult
import seeyuer.yingli.player.domain.library.FileOperationTarget

/**
 * `AndroidFileOperationGateway` 现在只剩 `rename` / `move`。
 *
 * 阶段 4 删掉了它的 R3 路径（`trash`/`restore`/`purge` 的 `renameTo(".Trash/YingLi")`）：
 * 回收站不再用「改名到隐藏目录」的方式，因为应用对他应用创建的共享媒体没有路径访问权，
 * 而且改名成功也会让原 URI 失效。回收站的全部文件动作现在归 `AndroidRecycleBinStorage`。
 */
@RunWith(AndroidJUnit4::class)
class AndroidFileOperationGatewayTest {
    @Test
    fun renameKeepsTheFileInPlaceAndReportsTheNewUri() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "file-gateway-${System.nanoTime()}").apply { mkdirs() }
        val source = File(directory, "movie.mp4").apply { writeText("video") }
        val gateway = AndroidFileOperationGateway(context, DefaultAppDispatchers)

        val result = gateway.rename(target(source), "renamed.mp4") as FileOperationResult.Success

        assertFalse(source.exists())
        val renamed = File(Uri.parse(result.uri.value).path.orEmpty())
        assertTrue(renamed.exists())
        assertEquals("renamed.mp4", renamed.name)
    }

    @Test
    fun renameRefusesToOverwriteAnExistingName() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "file-gateway-${System.nanoTime()}").apply { mkdirs() }
        val source = File(directory, "movie.mp4").apply { writeText("video") }
        File(directory, "taken.mp4").writeText("other")
        val gateway = AndroidFileOperationGateway(context, DefaultAppDispatchers)

        val result = gateway.rename(target(source), "taken.mp4")

        assertEquals(FileOperationResult.RecoverableFailure(FileOperationFailure.NAME_CONFLICT), result)
        assertTrue(source.exists())
        assertEquals("other", File(directory, "taken.mp4").readText())
    }

    @Test
    fun moveRefusesACrossSchemeMoveInsteadOfDowngrading() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "file-gateway-${System.nanoTime()}").apply { mkdirs() }
        val source = File(directory, "movie.mp4").apply { writeText("video") }
        val gateway = AndroidFileOperationGateway(context, DefaultAppDispatchers)

        val result = gateway.move(target(source), MediaUri("content://media/external/video/media/1"))

        assertEquals(FileOperationResult.RecoverableFailure(FileOperationFailure.VOLUME_OFFLINE), result)
        assertTrue(source.exists())
    }

    private fun target(file: File) =
        FileOperationTarget(MEDIA_ID, LOCATION_ID, MediaUri(Uri.fromFile(file).toString()))

    private companion object {
        val MEDIA_ID = MediaItemId("media_1")
        val LOCATION_ID = MediaLocationId("location_1")
    }
}
