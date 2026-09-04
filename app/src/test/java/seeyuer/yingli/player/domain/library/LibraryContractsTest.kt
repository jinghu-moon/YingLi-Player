package seeyuer.yingli.player.domain.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri

class LibraryContractsTest {
    @Test
    fun `filter serialization round trips without nested expressions`() {
        val expression = FilterExpression(
            includedTags = setOf("家庭", "旅行"),
            excludedTags = setOf("临时"),
            tagMatchMode = TagMatchMode.ANY,
            duration = DurationFilter(1_000, 60_000),
            minimumWidth = 1_920,
            extensions = setOf("MP4", "mkv"),
        )

        assertEquals(expression.copy(extensions = setOf("mp4", "mkv")), FilterExpression.deserialize(expression.serialize()))
    }

    @Test
    fun `multiple tags default to AND and exclusions always apply`() {
        val item = media(tags = setOf("家庭", "旅行"))

        assertTrue(FilterExpression(includedTags = setOf("家庭", "旅行")).matches(item))
        assertFalse(FilterExpression(includedTags = setOf("家庭", "缺失")).matches(item))
        assertFalse(FilterExpression(excludedTags = setOf("旅行")).matches(item))
    }

    @Test
    fun `query normalizes blank and overlong keywords without changing layout preference`() {
        assertTrue(LibraryQuery(keyword = "   ").normalizedKeyword.isEmpty())
        assertEquals(LibraryQuery.MAX_KEYWORD_LENGTH, LibraryQuery(keyword = "a".repeat(200)).normalizedKeyword.length)
        assertEquals(
            LibraryDisplayPreference(LibraryViewMode.GRID, 1.25f).thumbnailScale,
            LibraryDisplayPreference(LibraryViewMode.LIST, 1.25f).thumbnailScale,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid duration range is rejected`() {
        DurationFilter(5_000, 1_000)
    }

    private fun media(tags: Set<String>) = LibraryMedia(
        MediaItemId("media_1"),
        MediaLocationId("location_1"),
        MediaUri("content://media/video/1"),
        "影片",
        "movie.mp4",
        "相机",
        "mp4",
        10_000,
        1_920,
        1_080,
        100,
        2_000,
        completed = false,
        tags = tags,
    )
}
