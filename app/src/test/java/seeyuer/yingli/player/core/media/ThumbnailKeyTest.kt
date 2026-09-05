package seeyuer.yingli.player.core.media

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.core.model.media.ThumbnailPriority
import seeyuer.yingli.player.core.model.media.ThumbnailRequest

class ThumbnailKeyTest {
    @Test
    fun `media version changes invalidate thumbnail key`() {
        val original = request(modified = 10, size = 100)
        assertNotEquals(original.key, request(modified = 11, size = 100).key)
        assertNotEquals(original.key, request(modified = 10, size = 101).key)
    }

    @Test
    fun `thumbnail key has a stable disk identity`() {
        val key = request(modified = 10, size = 100).key
        assertNotNull(key.diskName())
    }

    private fun request(modified: Long, size: Long) = ThumbnailRequest(
        mediaItemId = MediaItemId("media_1"),
        locationId = MediaLocationId("location_1"),
        uri = MediaUri("content://media/video/1"),
        widthPixels = 320,
        heightPixels = 180,
        priority = ThumbnailPriority.VISIBLE,
        modifiedEpochMillis = modified,
        sizeBytes = size,
    )
}
