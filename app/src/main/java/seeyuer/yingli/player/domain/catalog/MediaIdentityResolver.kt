package seeyuer.yingli.player.domain.catalog

import seeyuer.yingli.player.core.model.media.*

sealed interface IdentityResolution {
    data class Match(val itemId: MediaItemId, val locationId: MediaLocationId?) : IdentityResolution
    data object NewIdentity : IdentityResolution
    data class NeedsReview(val candidates: Set<MediaItemId>) : IdentityResolution
}

interface MediaIdentityResolver {
    fun resolve(
        evidence: MediaIdentityEvidence,
        locations: List<MediaLocation>,
        itemByLocation: Map<MediaLocationId, MediaItemId>,
    ): IdentityResolution
}

object DefaultMediaIdentityResolver : MediaIdentityResolver {
    override fun resolve(
        evidence: MediaIdentityEvidence,
        locations: List<MediaLocation>,
        itemByLocation: Map<MediaLocationId, MediaItemId>,
    ): IdentityResolution {
        locations.firstOrNull { it.uri == evidence.uri }?.let { location ->
            return location.match(itemByLocation) ?: IdentityResolution.NewIdentity
        }
        if (evidence.volumeId != null && evidence.documentId != null) {
            val stable = locations.filter { it.volumeId == evidence.volumeId && it.documentId == evidence.documentId }
            stable.singleOrNull()?.let { return it.match(itemByLocation) ?: IdentityResolution.NewIdentity }
            if (stable.size > 1) return stable.matchLogicalItem(itemByLocation)
        }
        if (evidence.contentHash != null) {
            val hashed = locations.filter { it.contentHash == evidence.contentHash }
            if (hashed.isNotEmpty()) return hashed.matchLogicalItem(itemByLocation)
        }
        val scored = locations.map { it to similarityScore(evidence, it) }
        val bestScore = scored.maxOfOrNull { it.second } ?: return IdentityResolution.NewIdentity
        if (bestScore < MINIMUM_RELOCATION_SCORE) return IdentityResolution.NewIdentity
        val best = scored.filter { it.second == bestScore }.map(Pair<MediaLocation, Int>::first)
        return best.matchLogicalItem(itemByLocation)
    }

    private fun similarityScore(candidate: MediaIdentityEvidence, existing: MediaLocation): Int {
        var score = 0
        if (candidate.volumeId == existing.volumeId && candidate.volumeId != null) score += 1
        if (candidate.sizeBytes == existing.sizeBytes) score += 2
        if (candidate.durationMillis != null && candidate.durationMillis == existing.durationMillis) score += 2
        if (candidate.width != null && candidate.width == existing.width && candidate.height == existing.height) score += 1
        if (candidate.modifiedEpochMillis == existing.modifiedEpochMillis) score += 1
        return score
    }

    private fun MediaLocation.match(links: Map<MediaLocationId, MediaItemId>): IdentityResolution.Match? =
        links[id]?.let { IdentityResolution.Match(it, id) }

    private fun List<MediaLocation>.matchLogicalItem(
        links: Map<MediaLocationId, MediaItemId>,
    ): IdentityResolution {
        val itemIds = mapNotNull { links[it.id] }.toSet()
        return when (itemIds.size) {
            0 -> IdentityResolution.NewIdentity
            1 -> IdentityResolution.Match(itemIds.single(), locationId = null)
            else -> IdentityResolution.NeedsReview(itemIds)
        }
    }

    private const val MINIMUM_RELOCATION_SCORE = 5
}
