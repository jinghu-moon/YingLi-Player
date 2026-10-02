package seeyuer.yingli.player.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import seeyuer.yingli.player.R
import seeyuer.yingli.player.domain.playback.PlaybackCommandRejection
import seeyuer.yingli.player.domain.playback.PlaybackOrder
import seeyuer.yingli.player.domain.playback.PlaybackSpeed

class PlayerFeedbackTextTest {
    @Test
    fun `every rejection reason maps to its own message`() {
        val messages = PlaybackCommandRejection.entries.map { playbackRejectionMessageRes(it.name) }

        assertEquals(PlaybackCommandRejection.entries.size, messages.distinct().size)
        assertFalse(
            "已知拒绝码不应回退到通用文案",
            messages.any { it == R.string.player_reject_unknown },
        )
    }

    @Test
    fun `unknown rejection code falls back to the generic message`() {
        assertEquals(R.string.player_reject_unknown, playbackRejectionMessageRes("SOMETHING_NEW"))
        assertEquals(R.string.player_reject_unknown, playbackRejectionMessageRes(""))
    }

    @Test
    fun `no candidate rejection reuses the queue ended wording`() {
        assertEquals(
            R.string.player_reject_no_candidate,
            playbackRejectionMessageRes(PlaybackCommandRejection.NO_CANDIDATE.name),
        )
    }

    @Test
    fun `playback order labels are distinct`() {
        val labels = PlaybackOrder.entries.map(::playbackOrderLabelRes)

        assertEquals(PlaybackOrder.entries.size, labels.distinct().size)
    }

    @Test
    fun `speed labels drop meaningless decimals`() {
        assertEquals("0.5x", PlaybackSpeed.of(0.5f).displayLabel())
        assertEquals("0.75x", PlaybackSpeed.of(0.75f).displayLabel())
        assertEquals("1x", PlaybackSpeed.of(1f).displayLabel())
        assertEquals("1.25x", PlaybackSpeed.of(1.25f).displayLabel())
        assertEquals("1.5x", PlaybackSpeed.of(1.5f).displayLabel())
        assertEquals("2x", PlaybackSpeed.of(2f).displayLabel())
        assertEquals("4x", PlaybackSpeed.of(4f).displayLabel())
    }

    @Test
    fun `every supported speed has a distinct label`() {
        val labels = PlaybackSpeed.supportedValues.map { PlaybackSpeed.of(it).displayLabel() }

        assertEquals(PlaybackSpeed.supportedValues.size, labels.distinct().size)
    }
}
