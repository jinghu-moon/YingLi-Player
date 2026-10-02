package seeyuer.yingli.player.data.preferences

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.playback.PlaybackQueue

class DataStorePlaybackQueueRepositoryTest {
    @Test
    fun queueSurvivesRepositoryRecreationAndCanBeCleared() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val firstRepository = DataStorePlaybackQueueRepository(context)
        val secondRepository = DataStorePlaybackQueueRepository(context)
        firstRepository.setQueue(null)
        val expected = PlaybackQueue(
            mediaIds = listOf(MediaItemId("persisted_a"), MediaItemId("persisted_b")),
            currentIndex = 1,
            continuousPlayback = true,
        )

        firstRepository.setQueue(expected)
        assertEquals(expected, secondRepository.queue.first())

        secondRepository.setQueue(null)
        assertEquals(null, firstRepository.queue.first())
    }
}
