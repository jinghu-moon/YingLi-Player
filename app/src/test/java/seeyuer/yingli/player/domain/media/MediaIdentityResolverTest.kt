package seeyuer.yingli.player.domain.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaIdentityEvidence
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocation
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaSourceId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.core.model.media.VolumeId

class MediaIdentityResolverTest {
    @Test
    fun `exact uri restores the existing physical location`() {
        val location = location("location_1", "content://media/video/1")

        val result = DefaultMediaIdentityResolver.resolve(
            evidence(uri = location.uri.value),
            listOf(location),
            mapOf(location.id to MediaItemId("item_1")),
        ) as IdentityResolution.Match

        assertEquals(MediaItemId("item_1"), result.itemId)
        assertEquals(location.id, result.locationId)
    }

    @Test
    fun `same content at another uri links a new location to the logical item`() {
        val location = location("location_1", "content://media/video/1")

        val result = DefaultMediaIdentityResolver.resolve(
            evidence(uri = "content://tree/root/video/2", documentId = "2"),
            listOf(location),
            mapOf(location.id to MediaItemId("item_1")),
        ) as IdentityResolution.Match

        assertEquals(MediaItemId("item_1"), result.itemId)
        assertNull(result.locationId)
    }

    @Test
    fun `file name alone never merges different content`() {
        val existing = location("location_1", "content://media/video/1")
        val result = DefaultMediaIdentityResolver.resolve(
            evidence(
                uri = "content://media/video/2",
                documentId = "2",
                sizeBytes = 9_999,
                modifiedEpochMillis = 9_999,
                durationMillis = 60_000,
            ),
            listOf(existing),
            mapOf(existing.id to MediaItemId("item_1")),
        )

        assertEquals(IdentityResolution.NewIdentity, result)
    }

    @Test
    fun `equally strong evidence for different items requires review`() {
        val first = location("location_1", "content://media/video/1")
        val second = location("location_2", "content://media/video/2")

        val result = DefaultMediaIdentityResolver.resolve(
            evidence(uri = "content://media/video/3", documentId = "3"),
            listOf(first, second),
            mapOf(first.id to MediaItemId("item_1"), second.id to MediaItemId("item_2")),
        )

        assertTrue(result is IdentityResolution.NeedsReview)
        assertEquals(setOf(MediaItemId("item_1"), MediaItemId("item_2")), (result as IdentityResolution.NeedsReview).candidates)
    }

    @Test
    fun `content hash can relink a renamed item without reusing its old location`() {
        val existing = location(
            id = "location_1",
            uri = "content://media/video/1",
            contentHash = "sha256-content",
        )

        val result = DefaultMediaIdentityResolver.resolve(
            evidence(
                uri = "content://media/video/9",
                documentId = "9",
                sizeBytes = 9_999,
                contentHash = "sha256-content",
            ),
            listOf(existing),
            mapOf(existing.id to MediaItemId("item_1")),
        ) as IdentityResolution.Match

        assertEquals(MediaItemId("item_1"), result.itemId)
        assertNull(result.locationId)
    }

    private fun evidence(
        uri: String,
        documentId: String = "1",
        sizeBytes: Long = 1_024,
        modifiedEpochMillis: Long = 100,
        durationMillis: Long = 5_000,
        contentHash: String? = null,
    ) = MediaIdentityEvidence(
        uri = MediaUri(uri),
        volumeId = VolumeId("primary"),
        documentId = documentId,
        fileName = "movie.mp4",
        sizeBytes = sizeBytes,
        modifiedEpochMillis = modifiedEpochMillis,
        durationMillis = durationMillis,
        width = 1_920,
        height = 1_080,
        contentHash = contentHash,
    )

    private fun location(
        id: String,
        uri: String,
        contentHash: String? = null,
    ) = MediaLocation(
        id = MediaLocationId(id),
        sourceId = MediaSourceId("source_1"),
        uri = MediaUri(uri),
        volumeId = VolumeId("primary"),
        documentId = id,
        fileName = "movie.mp4",
        mimeType = "video/mp4",
        sizeBytes = 1_024,
        modifiedEpochMillis = 100,
        durationMillis = 5_000,
        width = 1_920,
        height = 1_080,
        lastSeenEpochMillis = 200,
        contentHash = contentHash,
    )
}
