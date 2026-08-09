package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ResumePlaybackPolicyTest {
    private val policy = ResumePlaybackPolicy()

    @Test
    fun `resume position rewinds three seconds and clamps at zero`() {
        assertEquals(7_000, policy.startPosition(10_000, 60_000, completed = false))
        assertEquals(0, policy.startPosition(2_000, 60_000, completed = false))
    }

    @Test
    fun `completed and near-end media restart from zero`() {
        assertEquals(0, policy.startPosition(20_000, 60_000, completed = true))
        assertEquals(0, policy.startPosition(58_000, 60_000, completed = false))
        assertEquals(0, policy.startPosition(95_000, 100_000, completed = false))
    }

    @Test
    fun `automatic advance is disabled by default`() {
        assertFalse(policy.autoAdvance)
    }
}
