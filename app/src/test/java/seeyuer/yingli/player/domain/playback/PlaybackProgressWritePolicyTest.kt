package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackProgressWritePolicyTest {
    @Test
    fun `periodic writes are limited to one per five seconds`() {
        val policy = PlaybackProgressWritePolicy()

        assertEquals(ProgressWriteDecision.Write(1_000, false), policy.evaluate(sample(1_000), ProgressWriteReason.PERIODIC, 0))
        assertSame(ProgressWriteDecision.Skip, policy.evaluate(sample(2_000), ProgressWriteReason.PERIODIC, 4_999))
        assertEquals(ProgressWriteDecision.Write(3_000, false), policy.evaluate(sample(3_000), ProgressWriteReason.PERIODIC, 5_000))
    }

    @Test
    fun `critical events force a clamped write without duplicate values`() {
        val policy = PlaybackProgressWritePolicy()

        assertEquals(ProgressWriteDecision.Write(10_000, false), policy.evaluate(sample(20_000), ProgressWriteReason.PAUSED, 1))
        assertSame(ProgressWriteDecision.Skip, policy.evaluate(sample(20_000), ProgressWriteReason.STOPPED, 2))
        assertEquals(ProgressWriteDecision.Write(10_000, true), policy.evaluate(sample(9_000), ProgressWriteReason.ENDED, 3))
    }

    @Test
    fun `incognito playback never writes`() {
        val policy = PlaybackProgressWritePolicy()
        assertSame(
            ProgressWriteDecision.Skip,
            policy.evaluate(sample(5_000, incognito = true), ProgressWriteReason.ENDED, 10_000),
        )
    }

    private fun sample(position: Long, incognito: Boolean = false) =
        PlaybackProgressSample(position, durationMillis = 10_000, incognito)
}
