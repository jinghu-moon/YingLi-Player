package seeyuer.yingli.player.domain.duplicates

import kotlinx.coroutines.flow.Flow
import seeyuer.yingli.player.core.model.media.MediaItemId

enum class DuplicateMode { EXACT, SIMILAR }

data class MediaFingerprint(
    val mediaId: MediaItemId,
    val sizeBytes: Long,
    val quickHash: String?,
    val fullHash: String?,
    val durationMillis: Long?,
    val width: Int?,
    val height: Int?,
    val perceptualHashes: List<Long>,
    val algorithmVersion: Int,
    val sourceModifiedEpochMillis: Long,
    val generatedAtEpochMillis: Long,
) {
    init {
        require(sizeBytes >= 0)
        require(quickHash == null || quickHash.matches(SHA256))
        require(fullHash == null || fullHash.matches(SHA256))
        require(durationMillis == null || durationMillis >= 0)
        require(width == null || width > 0)
        require(height == null || height > 0)
        require(algorithmVersion > 0)
        require(sourceModifiedEpochMillis >= 0)
        require(generatedAtEpochMillis >= 0)
    }

    val exactReady: Boolean get() = fullHash != null

    private companion object {
        val SHA256 = Regex("[a-f0-9]{64}")
    }
}

sealed interface DuplicateEvidence {
    val algorithmVersion: Int
    val generatedAtEpochMillis: Long

    data class Exact(
        val sizeBytes: Long,
        val fullHash: String,
        override val algorithmVersion: Int,
        override val generatedAtEpochMillis: Long,
    ) : DuplicateEvidence {
        init {
            require(sizeBytes >= 0)
            require(fullHash.matches(Regex("[a-f0-9]{64}")))
            require(algorithmVersion > 0 && generatedAtEpochMillis >= 0)
        }
    }

    data class Similar(
        val visualScore: Double,
        val durationScore: Double,
        val dimensionScore: Double,
        val overallScore: Double,
        override val algorithmVersion: Int,
        override val generatedAtEpochMillis: Long,
    ) : DuplicateEvidence {
        init {
            listOf(visualScore, durationScore, dimensionScore, overallScore).forEach { require(it in 0.0..1.0) }
            require(algorithmVersion > 0 && generatedAtEpochMillis >= 0)
        }
    }
}

@JvmInline
value class DuplicateGroupId(val value: String) {
    init { require(value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }
}

data class DuplicateCandidate(
    val mediaId: MediaItemId,
    val fingerprint: MediaFingerprint,
) {
    init { require(mediaId == fingerprint.mediaId) }
}

data class DuplicateGroup(
    val id: DuplicateGroupId,
    val mode: DuplicateMode,
    val candidates: List<DuplicateCandidate>,
    val evidence: DuplicateEvidence,
) {
    init {
        require(candidates.size >= 2)
        require(candidates.map(DuplicateCandidate::mediaId).distinct().size == candidates.size)
        require(candidates.all { it.fingerprint.algorithmVersion == evidence.algorithmVersion })
        require((mode == DuplicateMode.EXACT) == (evidence is DuplicateEvidence.Exact))
    }
}

data class SimilarityThreshold(
    val minimumOverall: Double = 0.88,
    val minimumVisual: Double = 0.82,
) {
    init { require(minimumOverall in 0.0..1.0 && minimumVisual in 0.0..1.0) }
}

object DuplicateEvidenceFactory {
    fun exact(fingerprints: List<MediaFingerprint>, nowEpochMillis: Long): List<DuplicateEvidence.Exact> =
        fingerprints.asSequence()
            .filter(MediaFingerprint::exactReady)
            .groupBy { it.sizeBytes to it.fullHash }
            .values
            .filter { it.size >= 2 }
            .map { group ->
                DuplicateEvidence.Exact(
                    group.first().sizeBytes,
                    requireNotNull(group.first().fullHash),
                    group.first().algorithmVersion,
                    nowEpochMillis,
                )
            }
            .toList()

    fun similarity(first: MediaFingerprint, second: MediaFingerprint, nowEpochMillis: Long): DuplicateEvidence.Similar? {
        if (first.algorithmVersion != second.algorithmVersion) return null
        if (first.perceptualHashes.isEmpty() || first.perceptualHashes.size != second.perceptualHashes.size) return null
        val visual = first.perceptualHashes.zip(second.perceptualHashes)
            .map { (left, right) -> 1.0 - java.lang.Long.bitCount(left xor right) / 64.0 }
            .average()
            .coerceIn(0.0, 1.0)
        val duration = ratioScore(first.durationMillis, second.durationMillis)
        val dimensions = ratioScore(
            first.width?.toLong()?.times(first.height ?: 0),
            second.width?.toLong()?.times(second.height ?: 0),
        )
        val overall = (visual * 0.7 + duration * 0.2 + dimensions * 0.1).coerceIn(0.0, 1.0)
        return DuplicateEvidence.Similar(
            visual, duration, dimensions, overall, first.algorithmVersion, nowEpochMillis,
        )
    }

    private fun ratioScore(first: Long?, second: Long?): Double {
        if (first == null || second == null || first <= 0 || second <= 0) return 0.0
        return minOf(first, second).toDouble() / maxOf(first, second)
    }
}

data class DuplicateDeletionPlan(
    val groupId: DuplicateGroupId,
    val keepMediaIds: Set<MediaItemId>,
    val trashMediaIds: Set<MediaItemId>,
    val evidenceAlgorithmVersion: Int,
    val createdAtEpochMillis: Long,
) {
    init {
        require(keepMediaIds.isNotEmpty())
        require(trashMediaIds.isNotEmpty())
        require(keepMediaIds.intersect(trashMediaIds).isEmpty())
        require(evidenceAlgorithmVersion > 0)
        require(createdAtEpochMillis >= 0)
    }
}

sealed interface DuplicatePlanValidation {
    data object Valid : DuplicatePlanValidation
    data class Invalid(val code: String) : DuplicatePlanValidation
}

object DuplicateDeletionValidator {
    fun validate(
        group: DuplicateGroup,
        plan: DuplicateDeletionPlan,
        currentFingerprints: Map<MediaItemId, MediaFingerprint>,
    ): DuplicatePlanValidation {
        if (group.id != plan.groupId) return DuplicatePlanValidation.Invalid("GROUP_MISMATCH")
        val all = group.candidates.map(DuplicateCandidate::mediaId).toSet()
        if (plan.keepMediaIds + plan.trashMediaIds != all) return DuplicatePlanValidation.Invalid("INCOMPLETE_SELECTION")
        if (plan.evidenceAlgorithmVersion != group.evidence.algorithmVersion) {
            return DuplicatePlanValidation.Invalid("EVIDENCE_VERSION_CHANGED")
        }
        group.candidates.forEach { candidate ->
            val current = currentFingerprints[candidate.mediaId]
                ?: return DuplicatePlanValidation.Invalid("FINGERPRINT_MISSING")
            if (current.algorithmVersion != candidate.fingerprint.algorithmVersion ||
                current.sourceModifiedEpochMillis != candidate.fingerprint.sourceModifiedEpochMillis ||
                current.sizeBytes != candidate.fingerprint.sizeBytes ||
                current.fullHash != candidate.fingerprint.fullHash ||
                current.perceptualHashes != candidate.fingerprint.perceptualHashes
            ) {
                return DuplicatePlanValidation.Invalid("FILE_CHANGED")
            }
        }
        return DuplicatePlanValidation.Valid
    }
}

data class SimilarFeatureBaseline(
    val sampleCount: Int,
    val precision: Double?,
    val recall: Double?,
    val approved: Boolean,
) {
    init {
        require(sampleCount >= 0)
        require(precision == null || precision in 0.0..1.0)
        require(recall == null || recall in 0.0..1.0)
        require(!approved || sampleCount > 0 && precision != null && recall != null)
    }
}

interface DuplicateRepository {
    val groups: Flow<List<DuplicateGroup>>
    suspend fun fingerprint(mediaId: MediaItemId): MediaFingerprint?
    suspend fun saveFingerprints(fingerprints: List<MediaFingerprint>)
    suspend fun replaceGroups(mode: DuplicateMode, groups: List<DuplicateGroup>)
    suspend fun ignore(groupId: DuplicateGroupId)
}

interface DuplicateScanner {
    suspend fun scan(mode: DuplicateMode): DuplicateScanResult
    suspend fun cancel()
}

interface DuplicateFingerprintGenerator {
    suspend fun size(uri: String): Long?
    suspend fun quickHash(uri: String, sizeBytes: Long): String?
    suspend fun fullHash(uri: String): String?
    suspend fun perceptualHashes(uri: String, durationMillis: Long): List<Long>
}

sealed interface DuplicateDeletionResult {
    data class Completed(val trashedCount: Int) : DuplicateDeletionResult
    data class Rejected(val code: String) : DuplicateDeletionResult
    data class Partial(val trashedCount: Int, val failedCount: Int) : DuplicateDeletionResult
}

interface DuplicateDeletionExecutor {
    suspend fun execute(plan: DuplicateDeletionPlan): DuplicateDeletionResult
}

sealed interface DuplicateScanResult {
    data class Completed(val groups: List<DuplicateGroup>) : DuplicateScanResult
    data class Rejected(val code: String) : DuplicateScanResult
    data object Canceled : DuplicateScanResult
}
