package seeyuer.yingli.player.domain.experiments

enum class ExperimentKind {
    GIF_EXPORT,
    SEGMENT_SIMILARITY,
    SECOND_PLAYBACK_ENGINE,
    FFMPEG_BACKEND,
    NETWORK_SOURCE,
}

data class SuccessMetric(
    val name: String,
    val target: Double,
    val unit: String,
    val higherIsBetter: Boolean,
) {
    init {
        require(name.isNotBlank() && unit.isNotBlank())
        require(target.isFinite())
    }
}

data class EvidenceSample(
    val id: String,
    val source: String,
    val representative: Boolean,
    val privacyApproved: Boolean,
) {
    init { require(id.isNotBlank() && source.isNotBlank()) }
}

data class ExperimentProposal(
    val kind: ExperimentKind,
    val problem: String,
    val hypothesis: String,
    val samples: List<EvidenceSample>,
    val metrics: List<SuccessMetric>,
    val licenseCompatible: Boolean,
    val securityReviewRequired: Boolean,
    val exitStrategy: String,
    val owner: String,
) {
    init {
        require(problem.isNotBlank() && hypothesis.isNotBlank())
        require(exitStrategy.isNotBlank() && owner.isNotBlank())
    }
}

data class ExperimentMeasurement(
    val metricName: String,
    val value: Double,
) {
    init { require(metricName.isNotBlank() && value.isFinite()) }
}

enum class GoNoGoStatus { GO, NO_GO }

data class GoNoGoDecision(
    val kind: ExperimentKind,
    val status: GoNoGoStatus,
    val reasonCodes: Set<String>,
    val measurements: List<ExperimentMeasurement>,
) {
    init {
        require(reasonCodes.isNotEmpty())
        require(status != GoNoGoStatus.GO || reasonCodes == setOf("METRICS_MET"))
    }
}

object ExperimentGate {
    fun decide(
        proposal: ExperimentProposal,
        measurements: List<ExperimentMeasurement>,
        securityApproved: Boolean,
    ): GoNoGoDecision {
        val reasons = linkedSetOf<String>()
        if (!proposal.licenseCompatible) reasons += "LICENSE_INCOMPATIBLE"
        if (proposal.samples.isEmpty()) reasons += "NO_SAMPLES"
        if (proposal.samples.any { !it.representative }) reasons += "UNREPRESENTATIVE_SAMPLE"
        if (proposal.samples.any { !it.privacyApproved }) reasons += "PRIVACY_NOT_APPROVED"
        if (proposal.metrics.isEmpty()) reasons += "NO_MEASURABLE_METRICS"
        if (proposal.securityReviewRequired && !securityApproved) reasons += "SECURITY_NOT_APPROVED"
        val measured = measurements.associateBy(ExperimentMeasurement::metricName)
        proposal.metrics.forEach { metric ->
            val value = measured[metric.name]?.value
            if (value == null) {
                reasons += "METRIC_MISSING"
            } else if (metric.higherIsBetter && value < metric.target || !metric.higherIsBetter && value > metric.target) {
                reasons += "METRIC_TARGET_NOT_MET"
            }
        }
        return if (reasons.isEmpty()) {
            GoNoGoDecision(proposal.kind, GoNoGoStatus.GO, setOf("METRICS_MET"), measurements)
        } else {
            GoNoGoDecision(proposal.kind, GoNoGoStatus.NO_GO, reasons, measurements)
        }
    }
}

interface CapabilityProvider<I, O> {
    val id: String
    suspend fun execute(input: I): O
}
