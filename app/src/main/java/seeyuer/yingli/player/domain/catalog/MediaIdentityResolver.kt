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
        }
        // Do not infer identity from metadata. Different files commonly share
        // size, duration, and resolution; only stable platform identity is
        // reliable for this temporary one-file-per-item experiment.
        return IdentityResolution.NewIdentity
    }

    private fun MediaLocation.match(links: Map<MediaLocationId, MediaItemId>): IdentityResolution.Match? =
        links[id]?.let { IdentityResolution.Match(it, id) }

}
