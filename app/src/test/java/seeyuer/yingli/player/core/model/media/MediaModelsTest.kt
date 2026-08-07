package seeyuer.yingli.player.core.model.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaModelsTest {
    @Test
    fun `ids are stable value objects instead of path strings`() {
        assertEquals(MediaItemId("movie_1"), MediaItemId("movie_1"))
        assertNotEquals(MediaItemId("movie_1"), MediaItemId("movie_2"))
        assertThrows(IllegalArgumentException::class.java) { MediaItemId("content://movie/1") }
    }

    @Test
    fun `logical item retains user data independently from physical locations`() {
        val item = MediaItem(
            id = MediaItemId("movie_1"),
            title = "影片",
            playbackPositionMillis = 12_000,
            tags = setOf("收藏"),
        )
        val first = location("location_1", "content://media/video/1")
        val second = location("location_2", "content://tree/root/video/1")

        assertEquals(setOf(first.id, second.id).size, 2)
        assertEquals(12_000, item.playbackPositionMillis)
        assertEquals(setOf("收藏"), item.tags)
    }

    @Test
    fun `media uri only accepts storage schemes`() {
        assertEquals("content://media/video/1", MediaUri("content://media/video/1").value)
        assertThrows(IllegalArgumentException::class.java) { MediaUri("https://example.com/movie.mp4") }
    }

    private fun location(id: String, uri: String) = MediaLocation(
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
    )
}
