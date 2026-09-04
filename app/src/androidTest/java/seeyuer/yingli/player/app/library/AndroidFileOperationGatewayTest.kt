package seeyuer.yingli.player.app.library

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.app.AndroidFileOperationGateway
import seeyuer.yingli.player.core.foundation.DefaultAppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.library.FileOperationResult
import seeyuer.yingli.player.domain.library.FileOperationTarget
import seeyuer.yingli.player.domain.library.TrashEntry

@RunWith(AndroidJUnit4::class)
class AndroidFileOperationGatewayTest {
    @Test
    fun fileTrashRestoreAndPurgeNeverOverwriteSource() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "file-gateway-${System.nanoTime()}").apply { mkdirs() }
        val source = File(directory, "movie.mp4").apply { writeText("video") }
        val gateway = AndroidFileOperationGateway(context, DefaultAppDispatchers)
        val target = FileOperationTarget(MEDIA_ID, LOCATION_ID, source.mediaUri())

        val trashed = gateway.trash(target) as FileOperationResult.Success
        assertFalse(source.exists())
        val entry = TrashEntry(MEDIA_ID, LOCATION_ID, target.sourceUri, trashed.uri, 1, 2)

        assertTrue(gateway.restore(entry) is FileOperationResult.Success)
        assertTrue(source.exists())
        val trashedAgain = gateway.trash(target) as FileOperationResult.Success
        val purgeEntry = entry.copy(trashedUri = trashedAgain.uri)
        assertTrue(gateway.purge(purgeEntry) is FileOperationResult.Success)
        assertFalse(File(Uri.parse(trashedAgain.uri.value).path.orEmpty()).exists())
    }

    private fun File.mediaUri() = MediaUri(Uri.fromFile(this).toString())

    private companion object {
        val MEDIA_ID = MediaItemId("media_1")
        val LOCATION_ID = MediaLocationId("location_1")
    }
}
