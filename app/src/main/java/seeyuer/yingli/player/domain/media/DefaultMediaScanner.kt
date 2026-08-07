package seeyuer.yingli.player.domain.media

import kotlinx.coroutines.flow.collect
import seeyuer.yingli.player.core.foundation.AppClock
import seeyuer.yingli.player.core.foundation.IdGenerator
import seeyuer.yingli.player.core.media.*
import seeyuer.yingli.player.core.model.media.*

fun interface MediaScanner {
    suspend fun scan(request: ScanRequest): ScanResult
}

class DefaultMediaScanner(
    dataSources: Set<MediaDiscoveryDataSource>,
    private val sourceRepository: MediaSourceRepository,
    private val catalogRepository: MediaCatalogRepository,
    private val identityResolver: MediaIdentityResolver,
    private val contentHasher: MediaContentHasher = MediaContentHasher.None,
    private val idGenerator: IdGenerator,
    private val clock: AppClock,
) : MediaScanner {
    private val sourceByMode = dataSources.associateBy(MediaDiscoveryDataSource::mode)

    override suspend fun scan(request: ScanRequest): ScanResult {
        val started = clock.now().toEpochMilli()
        var added = 0
        var updated = 0
        var missing = 0
        var unsupported = 0
        val failures = mutableListOf<ScanFailure>()
        request.sourceIds.forEach { sourceId ->
            val source = sourceRepository.get(sourceId) ?: return@forEach
            val dataSource = sourceByMode[source.mode] ?: return@forEach
            val snapshot = catalogRepository.snapshot(sourceId)
            val items = snapshot.items.associateBy(MediaItem::id).toMutableMap()
            val locations = snapshot.locations.toMutableList()
            val links = snapshot.itemByLocation.toMutableMap()
            val scannedLocations = mutableListOf<MediaLocation>()
            val evidenceUpdates = mutableMapOf<MediaLocationId, MediaLocation>()
            val seen = mutableSetOf<MediaLocationId>()
            var scanComplete = true
            dataSource.discover(source).collect { event ->
                when (event) {
                    is MediaDiscoveryEvent.Failure -> {
                        failures += event.value
                        if (event.value.kind == ScanFailureKind.UNSUPPORTED) unsupported++
                        if (event.value.kind in TERMINAL_FAILURES) scanComplete = false
                    }
                    is MediaDiscoveryEvent.Candidate -> {
                        val candidate = event.value
                        val (resolution, resolvedEvidence) = resolveIdentity(
                            candidate.evidence,
                            locations,
                            links,
                            evidenceUpdates,
                        )
                        val matched = resolution as? IdentityResolution.Match
                        val itemId = matched?.itemId ?: MediaItemId(idGenerator.newId().stableId())
                        val locationId = matched?.locationId ?: MediaLocationId(idGenerator.newId().stableId())
                        val item = items[itemId] ?: MediaItem(itemId, resolvedEvidence.fileName.substringBeforeLast('.').ifBlank { resolvedEvidence.fileName })
                        val location = candidate.toLocation(locationId, resolvedEvidence, clock.now().toEpochMilli())
                        items[itemId] = item
                        scannedLocations += location
                        if (locations.none { it.id == locationId }) locations += location
                        links[locationId] = itemId
                        seen += locationId
                    }
                }
            }
            val beforeMissing = snapshot.locations.count { it.id !in seen }
            val counts = catalogRepository.applyMutation(sourceId, CatalogMutation(
                upsertItems = items.values.toList(),
                upsertLocations = scannedLocations,
                links = links.filterKeys { it in seen },
                seenLocationIds = seen,
                scanCompletedAtEpochMillis = clock.now().toEpochMilli(),
                markMissing = scanComplete,
                evidenceUpdates = evidenceUpdates.values.toList(),
            ))
            added += counts.first
            updated += counts.second
            if (scanComplete) missing += beforeMissing
            val sourceFailures = failures.filter { it.sourceId == sourceId }
            when {
                sourceFailures.any { it.kind == ScanFailureKind.PERMISSION } ->
                    sourceRepository.markAccessState(sourceId, MediaSourceAccessState.PERMISSION_LOST)
                sourceFailures.any { it.kind == ScanFailureKind.SOURCE_OFFLINE } ->
                    sourceRepository.markAccessState(sourceId, MediaSourceAccessState.OFFLINE)
            }
        }
        return ScanResult(added, updated, missing, unsupported, failures, (clock.now().toEpochMilli() - started).coerceAtLeast(0))
    }

    private suspend fun resolveIdentity(
        evidence: MediaIdentityEvidence,
        locations: MutableList<MediaLocation>,
        links: Map<MediaLocationId, MediaItemId>,
        evidenceUpdates: MutableMap<MediaLocationId, MediaLocation>,
    ): Pair<IdentityResolution, MediaIdentityEvidence> {
        val initial = identityResolver.resolve(evidence, locations, links)
        if (initial !is IdentityResolution.NeedsReview) return initial to evidence
        val candidateHash = evidence.contentHash ?: contentHasher.sha256(evidence.uri)
            ?: return initial to evidence
        val candidateItems = initial.candidates
        locations.indices.forEach { index ->
            val location = locations[index]
            if (links[location.id] !in candidateItems || location.contentHash != null) return@forEach
            val hash = contentHasher.sha256(location.uri) ?: return@forEach
            val updated = location.copy(contentHash = hash)
            locations[index] = updated
            evidenceUpdates[updated.id] = updated
        }
        val resolvedEvidence = evidence.copy(contentHash = candidateHash)
        return identityResolver.resolve(resolvedEvidence, locations, links) to resolvedEvidence
    }

    private fun MediaCandidate.toLocation(
        id: MediaLocationId,
        resolvedEvidence: MediaIdentityEvidence,
        seenAt: Long,
    ) = MediaLocation(
        id, sourceId, resolvedEvidence.uri, resolvedEvidence.volumeId, resolvedEvidence.documentId,
        resolvedEvidence.fileName, mimeType,
        resolvedEvidence.sizeBytes, resolvedEvidence.modifiedEpochMillis, resolvedEvidence.durationMillis,
        resolvedEvidence.width, resolvedEvidence.height,
        missingScanCount = 0,
        lastSeenEpochMillis = seenAt,
        fastFingerprint = resolvedEvidence.fastFingerprint,
        contentHash = resolvedEvidence.contentHash,
    )

    private fun String.stableId(): String = replace(Regex("[^A-Za-z0-9_-]"), "_").take(128).ifBlank { "generated" }

    private companion object {
        val TERMINAL_FAILURES = setOf(ScanFailureKind.PERMISSION, ScanFailureKind.SOURCE_OFFLINE, ScanFailureKind.IO)
    }
}
