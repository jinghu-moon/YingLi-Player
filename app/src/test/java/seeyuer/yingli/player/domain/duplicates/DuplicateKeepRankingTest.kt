package seeyuer.yingli.player.domain.duplicates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaSourceMode
import seeyuer.yingli.player.core.model.media.MediaUri

class DuplicateKeepRankingTest {
    @Test
    fun `a location that is still present wins over a missing one`() {
        val present = candidate("present", lastSeenEpochMillis = 1)
        val missing = candidate("missing", missingScanCount = 3, lastSeenEpochMillis = 9_000)

        assertEquals(present.locationId, DuplicateKeepRanking.best(listOf(missing, present))?.locationId)
    }

    @Test
    fun `the most recently seen location wins`() {
        val older = candidate("older", lastSeenEpochMillis = 100)
        val newer = candidate("newer", lastSeenEpochMillis = 200)

        assertEquals(newer.locationId, DuplicateKeepRanking.best(listOf(older, newer))?.locationId)
    }

    @Test
    fun `a saf tree location wins over a media store one`() {
        val store = candidate("store", sourceMode = MediaSourceMode.MEDIA_STORE)
        val tree = candidate("tree", sourceMode = MediaSourceMode.SAF_TREE)

        assertEquals(tree.locationId, DuplicateKeepRanking.best(listOf(store, tree))?.locationId)
    }

    @Test
    fun `a hidden directory and a copy name both lose`() {
        val clean = candidate("clean", fileName = "holiday.mp4")
        val hidden = candidate("hidden", relativePath = ".trash/holiday.mp4")
        val copy = candidate("copy", fileName = "holiday (1).mp4")

        assertEquals(clean.locationId, DuplicateKeepRanking.best(listOf(hidden, copy, clean))?.locationId)
        assertEquals(listOf("clean", "copy", "hidden"), DuplicateKeepRanking.rank(listOf(hidden, copy, clean)).map { it.locationId.value })
    }

    @Test
    fun `ranking is total and stable when every criterion ties`() {
        val first = candidate("b")
        val second = candidate("a")

        assertEquals(listOf("a", "b"), DuplicateKeepRanking.rank(listOf(first, second)).map { it.locationId.value })
        assertEquals(listOf("a", "b"), DuplicateKeepRanking.rank(listOf(second, first)).map { it.locationId.value })
    }

    @Test
    fun `an empty group has no recommendation`() {
        assertNull(DuplicateKeepRanking.best(emptyList()))
    }

    private fun candidate(
        locationId: String,
        fileName: String = "$locationId.mp4",
        relativePath: String? = null,
        sourceMode: MediaSourceMode = MediaSourceMode.MEDIA_STORE,
        missingScanCount: Int = 0,
        modifiedEpochMillis: Long = 1_000,
        lastSeenEpochMillis: Long = 1_000,
    ) = DuplicateCandidate(
        locationId = MediaLocationId(locationId),
        mediaItemId = MediaItemId("item-$locationId"),
        uri = MediaUri("content://media/$locationId"),
        fileName = fileName,
        relativePath = relativePath,
        sourceMode = sourceMode,
        sizeBytes = 100,
        modifiedEpochMillis = modifiedEpochMillis,
        durationMillis = 1_000,
        width = 1_920,
        height = 1_080,
        missingScanCount = missingScanCount,
        lastSeenEpochMillis = lastSeenEpochMillis,
    )
}
