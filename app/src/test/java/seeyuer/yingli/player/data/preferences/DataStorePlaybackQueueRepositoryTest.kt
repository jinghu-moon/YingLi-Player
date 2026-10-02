package seeyuer.yingli.player.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.playback.PlaybackQueue
import seeyuer.yingli.player.domain.playback.PlaybackOrder

class DataStorePlaybackQueueRepositoryTest {
    @Test
    fun `codec preserves queue order current item and playback flag`() {
        val queue = PlaybackQueue(
            mediaIds = listOf(MediaItemId("first"), MediaItemId("second"), MediaItemId("third")),
            currentIndex = 1,
            continuousPlayback = true,
            order = PlaybackOrder.SHUFFLE,
            shuffleHistory = listOf(0),
        )

        assertEquals(queue, PlaybackQueueCodec.decode(PlaybackQueueCodec.encode(queue)))
    }

    @Test
    fun `codec rejects malformed duplicate and out of bounds queues`() {
        assertNull(PlaybackQueueCodec.decode("3|0|true|SEQUENCE||first"))
        assertNull(PlaybackQueueCodec.decode("2|0|true|SEQUENCE||first,first"))
        assertNull(PlaybackQueueCodec.decode("2|2|false|SEQUENCE||first,second"))
        assertNull(PlaybackQueueCodec.decode("2|0|false|SHUFFLE|4|first,second"))
        assertNull(PlaybackQueueCodec.decode("2|0|false|SEQUENCE||invalid.id"))
    }
}
