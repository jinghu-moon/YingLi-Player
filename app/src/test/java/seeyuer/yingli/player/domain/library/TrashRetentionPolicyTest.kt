package seeyuer.yingli.player.domain.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri

class TrashRetentionPolicyTest {
    @Test
    fun `entry expires exactly at configured retention boundary`() {
        val policy = TrashRetentionPolicy(30)
        val deletedAt = 1_000L
        val entry = TrashEntry(
            MediaItemId("media_1"),
            MediaLocationId("location_1"),
            MediaUri("content://media/video/1"),
            null,
            deletedAt,
            policy.purgeAt(deletedAt),
        )

        assertFalse(policy.isExpired(entry, entry.purgeAtEpochMillis - 1))
        assertTrue(policy.isExpired(entry, entry.purgeAtEpochMillis))
    }
}
