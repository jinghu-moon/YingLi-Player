package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryEligibilityPolicyTest {
    private val policy = HistoryEligibilityPolicy()

    @Test
    fun `eligibility uses ten percent capped at thirty seconds`() {
        assertEquals(HistoryEligibility.TOO_EARLY, policy.evaluate(9_999, 100_000, false))
        assertEquals(HistoryEligibility.ELIGIBLE, policy.evaluate(10_000, 100_000, false))
        assertEquals(HistoryEligibility.TOO_EARLY, policy.evaluate(29_999, 600_000, false))
        assertEquals(HistoryEligibility.ELIGIBLE, policy.evaluate(30_000, 600_000, false))
    }

    @Test
    fun `incognito and secure sources never become history`() {
        assertEquals(HistoryEligibility.INCOGNITO, policy.evaluate(50_000, 60_000, true))
        assertEquals(HistoryEligibility.SECURE_SOURCE, policy.evaluate(50_000, 60_000, false, secureSource = true))
    }

    @Test
    fun `natural end and ninety five percent are completed`() {
        assertEquals(HistoryEligibility.COMPLETED, policy.evaluate(95_000, 100_000, false))
        assertEquals(HistoryEligibility.COMPLETED, policy.evaluate(1_000, 100_000, false, naturallyEnded = true))
    }
}
