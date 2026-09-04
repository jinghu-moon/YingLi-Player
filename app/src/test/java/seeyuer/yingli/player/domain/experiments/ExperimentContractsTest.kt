package seeyuer.yingli.player.domain.experiments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentContractsTest {
    @Test
    fun `request without evidence is automatically no go`() {
        val decision = ExperimentGate.decide(proposal(samples = emptyList()), emptyList(), securityApproved = true)

        assertEquals(GoNoGoStatus.NO_GO, decision.status)
        assertTrue("NO_SAMPLES" in decision.reasonCodes)
        assertTrue("METRIC_MISSING" in decision.reasonCodes)
    }

    @Test
    fun `license and privacy failures cannot be overridden by metrics`() {
        val proposal = proposal(
            samples = listOf(EvidenceSample("1", "local-fixture", representative = true, privacyApproved = false)),
            licenseCompatible = false,
        )
        val decision = ExperimentGate.decide(
            proposal,
            listOf(ExperimentMeasurement("success_rate", 1.0)),
            securityApproved = true,
        )

        assertEquals(GoNoGoStatus.NO_GO, decision.status)
        assertTrue("LICENSE_INCOMPATIBLE" in decision.reasonCodes)
        assertTrue("PRIVACY_NOT_APPROVED" in decision.reasonCodes)
    }

    @Test
    fun `go requires every frozen metric and approval`() {
        val proposal = proposal(
            samples = listOf(EvidenceSample("1", "local-fixture", representative = true, privacyApproved = true)),
        )
        val decision = ExperimentGate.decide(
            proposal,
            listOf(ExperimentMeasurement("success_rate", 0.95)),
            securityApproved = true,
        )

        assertEquals(GoNoGoStatus.GO, decision.status)
        assertEquals(setOf("METRICS_MET"), decision.reasonCodes)
    }

    private fun proposal(
        samples: List<EvidenceSample>,
        licenseCompatible: Boolean = true,
    ) = ExperimentProposal(
        ExperimentKind.SECOND_PLAYBACK_ENGINE,
        problem = "Representative legal media fails on the platform provider.",
        hypothesis = "A candidate provider improves the measured success rate.",
        samples = samples,
        metrics = listOf(SuccessMetric("success_rate", 0.9, "ratio", higherIsBetter = true)),
        licenseCompatible = licenseCompatible,
        securityReviewRequired = true,
        exitStrategy = "Remove the provider and retain the platform implementation.",
        owner = "YingLi",
    )
}
