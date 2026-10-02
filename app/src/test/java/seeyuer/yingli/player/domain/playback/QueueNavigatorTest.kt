package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId

class QueueNavigatorTest {
    private val ids = listOf(MediaItemId("one"), MediaItemId("two"), MediaItemId("three"))

    @Test
    fun `sequence stops at end and repeat wraps`() {
        assertEquals(
            NavigationDecision.StopAtEnd,
            QueueNavigator().next(queue(2, PlaybackOrder.SEQUENCE), ended = true),
        )
        assertEquals(
            NavigationDecision.MoveTo(0, ids[0]),
            QueueNavigator().next(queue(2, PlaybackOrder.QUEUE_REPEAT), ended = true),
        )
    }

    @Test
    fun `single repeat repeats only on natural end`() {
        val navigator = QueueNavigator()

        assertEquals(
            NavigationDecision.MoveTo(1, ids[1]),
            navigator.next(queue(1, PlaybackOrder.SINGLE_REPEAT), ended = true),
        )
        assertEquals(
            NavigationDecision.MoveTo(2, ids[2]),
            navigator.next(queue(1, PlaybackOrder.SINGLE_REPEAT), ended = false),
        )
        assertEquals(
            NavigationDecision.StopAtEnd,
            navigator.next(queue(2, PlaybackOrder.SINGLE_REPEAT), ended = false),
        )
    }

    @Test
    fun `shuffle excludes current and previous follows history`() {
        val navigator = QueueNavigator(ShufflePicker { candidates, _ -> candidates.last() })
        val queue = queue(1, PlaybackOrder.SHUFFLE, shuffleHistory = listOf(0, 2, 1))

        assertEquals(
            NavigationDecision.MoveTo(2, ids[2], listOf(0, 2, 1)),
            navigator.next(queue, ended = false),
        )
        assertEquals(
            NavigationDecision.MoveTo(2, ids[2], listOf(0, 2)),
            navigator.previous(queue, currentPositionMillis = 0),
        )
    }

    @Test
    fun `previous restarts current after five seconds and handles empty queue`() {
        val navigator = QueueNavigator()

        assertEquals(
            NavigationDecision.MoveTo(1, ids[1]),
            navigator.previous(queue(1, PlaybackOrder.SEQUENCE), currentPositionMillis = 5_001),
        )
        assertEquals(
            NavigationDecision.NoCandidate,
            navigator.next(PlaybackQueueSnapshot("empty", emptyList(), -1, PlaybackOrder.SEQUENCE), false),
        )
    }

    private fun queue(
        currentIndex: Int,
        order: PlaybackOrder,
        shuffleHistory: List<Int> = emptyList(),
    ) = PlaybackQueueSnapshot("queue", ids, currentIndex, order, shuffleHistory)
}
