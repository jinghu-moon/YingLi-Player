package seeyuer.yingli.player.domain.organize

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.library.FilterExpression

class OrganizeContractsTest {
    @Test(expected = IllegalArgumentException::class)
    fun `duplicate playlist relationships are rejected`() {
        Playlist(
            PlaylistId("playlist_1"),
            "周末",
            listOf(MediaItemId("media_1"), MediaItemId("media_1")),
            1,
            1,
        )
    }

    @Test
    fun `smart collection stores a filter and never duplicates media references`() {
        val collection = Collection(
            CollectionId("collection_1"),
            "未看",
            CollectionKind.SMART,
            filter = FilterExpression(includedTags = setOf("稍后")),
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        )
        assertTrue(collection.mediaIds.isEmpty())
        assertTrue(collection.filter != null)
    }

    @Test
    fun `continue watching excludes short completed near-end and incognito playback`() {
        val policy = ContinueWatchingPolicy()

        assertTrue(policy.isEligible(20_000, 60_000, completed = false, incognito = false))
        assertFalse(policy.isEligible(5_000, 60_000, completed = false, incognito = false))
        assertFalse(policy.isEligible(58_000, 60_000, completed = false, incognito = false))
        assertFalse(policy.isEligible(20_000, 60_000, completed = true, incognito = false))
        assertFalse(policy.isEligible(20_000, 60_000, completed = false, incognito = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `time moving backwards is rejected`() {
        Tag(TagId("tag_1"), "旅行", TagColor.BLUE, createdAtEpochMillis = 2, updatedAtEpochMillis = 1)
    }
}
