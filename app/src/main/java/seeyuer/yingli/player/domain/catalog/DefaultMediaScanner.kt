package seeyuer.yingli.player.domain.catalog

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.common.IdGenerator
import seeyuer.yingli.player.core.model.media.*

fun interface MediaScanner {
    suspend fun scan(request: ScanRequest): ScanResult
}

interface ScanProgressSource {
    val progress: StateFlow<ScanProgress>
}

class DefaultMediaScanner(
    dataSources: Set<MediaDiscoveryDataSource>,
    private val sourceRepository: MediaSourceRepository,
    private val catalogRepository: MediaCatalogRepository,
    private val identityResolver: MediaIdentityResolver,
    private val contentHasher: MediaContentHasher = MediaContentHasher.None,
    private val idGenerator: IdGenerator,
    private val clock: AppClock,
) : MediaScanner, ScanProgressSource {
    private val progressState = MutableStateFlow(ScanProgress())
    override val progress: StateFlow<ScanProgress> = progressState
    private val sourceByMode = dataSources.associateBy(MediaDiscoveryDataSource::mode)

    override suspend fun scan(request: ScanRequest): ScanResult {
        progressState.value = ScanProgress()
        val started = clock.now().toEpochMilli()
        var added = 0
        var updated = 0
        var missing = 0
        var unsupported = 0
        var progressProcessed = 0
        val failures = mutableListOf<ScanFailure>()
        request.sourceIds.forEach { sourceId ->
            val source = sourceRepository.get(sourceId) ?: return@forEach
            val dataSource = sourceByMode[source.mode] ?: return@forEach
            val snapshot = catalogRepository.snapshot(sourceId)
            val items = snapshot.items.associateBy(MediaItem::id).toMutableMap()
            val existingItemIds = snapshot.items.mapTo(mutableSetOf(), MediaItem::id)
            val locations = snapshot.locations.toMutableList()
            val locationsByUri = snapshot.locations.associateBy(MediaLocation::uri).toMutableMap()
            val locationsBySize = snapshot.locations.groupBy(MediaLocation::sizeBytes)
                .mapValuesTo(mutableMapOf()) { (_, values) -> values.toMutableList() }
            val locationsByStableIdentity = snapshot.locations
                .filter { it.volumeId != null && it.documentId != null }
                .groupBy { it.volumeId to it.documentId }
                .mapValuesTo(mutableMapOf()) { (_, values) -> values.toMutableList() }
            val locationsByHash = snapshot.locations.filter { it.contentHash != null }
                .groupBy(MediaLocation::contentHash)
                .mapValuesTo(mutableMapOf()) { (_, values) -> values.toMutableList() }
            val indexedLocationIds = snapshot.locations.mapTo(mutableSetOf(), MediaLocation::id)
            val links = snapshot.itemByLocation.toMutableMap()
            val batchLocations = mutableListOf<MediaLocation>()
            val batchItems = linkedMapOf<MediaItemId, MediaItem>()
            val batchLinks = linkedMapOf<MediaLocationId, MediaItemId>()
            val batchEvidenceUpdates = linkedMapOf<MediaLocationId, MediaLocation>()
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
                        progressProcessed++
                        if (progressProcessed % PROGRESS_BATCH == 0) {
                            progressState.value = progressState.value.copy(
                                processed = progressProcessed,
                                discovered = progressProcessed,
                            )
                        }
                        val candidate = event.value
                        val exactLocation = locationsByUri[candidate.evidence.uri]
                        val exactItemId = exactLocation?.let { links[it.id] }
                        val (resolution, resolvedEvidence) = if (exactLocation != null && exactItemId != null) {
                            IdentityResolution.Match(exactItemId, exactLocation.id) to candidate.evidence
                        } else {
                            val stableCandidates = if (candidate.evidence.volumeId != null && candidate.evidence.documentId != null) {
                                locationsByStableIdentity[candidate.evidence.volumeId to candidate.evidence.documentId].orEmpty()
                            } else {
                                emptyList()
                            }
                            val hashCandidates = candidate.evidence.contentHash
                                ?.let { locationsByHash[it].orEmpty() }
                                .orEmpty()
                            val candidateLocations = (
                                locationsBySize[candidate.evidence.sizeBytes].orEmpty() + stableCandidates + hashCandidates
                            )
                                .distinctBy(MediaLocation::id)
                            resolveIdentity(
                                candidate.evidence,
                                locations,
                                links,
                                batchEvidenceUpdates,
                                candidateLocations,
                            )
                        }
                        val matched = resolution as? IdentityResolution.Match
                        val itemId = matched?.itemId ?: MediaItemId(idGenerator.newId().stableId())
                        val locationId = matched?.locationId ?: MediaLocationId(idGenerator.newId().stableId())
                        val item = items[itemId] ?: MediaItem(itemId, resolvedEvidence.fileName.substringBeforeLast('.').ifBlank { resolvedEvidence.fileName })
                        val location = candidate.toLocation(locationId, resolvedEvidence, clock.now().toEpochMilli())
                        items[itemId] = item
                        batchLocations += location
                        batchLinks[locationId] = itemId
                        if (itemId !in existingItemIds) {
                            batchItems[itemId] = item
                            existingItemIds += itemId
                        }
                        if (indexedLocationIds.add(location.id)) {
                            locations += location
                            locationsByUri[location.uri] = location
                            locationsBySize.getOrPut(location.sizeBytes) { mutableListOf() } += location
                            if (location.volumeId != null && location.documentId != null) {
                                locationsByStableIdentity.getOrPut(location.volumeId to location.documentId) {
                                    mutableListOf()
                                } += location
                            }
                            location.contentHash?.let { hash ->
                                locationsByHash.getOrPut(hash) { mutableListOf() } += location
                            }
                        }
                        links[locationId] = itemId
                        seen += locationId
                        if (batchLocations.size >= BATCH_SIZE) {
                            val counts = catalogRepository.applyMutation(sourceId, CatalogMutation(
                                upsertItems = batchItems.values.toList(),
                                upsertLocations = batchLocations.toList(),
                                links = batchLinks.toMap(),
                                seenLocationIds = seen,
                                scanCompletedAtEpochMillis = clock.now().toEpochMilli(),
                                markMissing = false,
                                evidenceUpdates = batchEvidenceUpdates.values.toList(),
                            ))
                            added += counts.first
                            updated += counts.second
                            batchItems.clear()
                            batchLocations.clear()
                            batchLinks.clear()
                            batchEvidenceUpdates.clear()
                            progressState.value = progressState.value.copy(
                                processed = progressProcessed,
                                discovered = progressProcessed,
                            )
                        }
                    }
                }
            }
            val beforeMissing = snapshot.locations.count { it.id !in seen }
            val counts = catalogRepository.applyMutation(sourceId, CatalogMutation(
                upsertItems = batchItems.values.toList(),
                upsertLocations = batchLocations.toList(),
                links = batchLinks.toMap(),
                seenLocationIds = seen,
                scanCompletedAtEpochMillis = clock.now().toEpochMilli(),
                markMissing = scanComplete,
                evidenceUpdates = batchEvidenceUpdates.values.toList(),
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
        progressState.value = progressState.value.copy(
            processed = progressProcessed,
            discovered = progressProcessed,
            completed = true,
        )
        return ScanResult(added, updated, missing, unsupported, failures, (clock.now().toEpochMilli() - started).coerceAtLeast(0))
    }

    private suspend fun resolveIdentity(
        evidence: MediaIdentityEvidence,
        locations: MutableList<MediaLocation>,
        links: Map<MediaLocationId, MediaItemId>,
        evidenceUpdates: MutableMap<MediaLocationId, MediaLocation>,
        candidateLocations: List<MediaLocation> = locations,
    ): Pair<IdentityResolution, MediaIdentityEvidence> {
        val initial = identityResolver.resolve(evidence, candidateLocations, links)
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
        relativePath = resolvedEvidence.relativePath,
    )

    private fun String.stableId(): String = replace(Regex("[^A-Za-z0-9_-]"), "_").take(128).ifBlank { "generated" }

    private companion object {
        const val PROGRESS_BATCH = 64
        const val BATCH_SIZE = 400
        val TERMINAL_FAILURES = setOf(ScanFailureKind.PERMISSION, ScanFailureKind.SOURCE_OFFLINE, ScanFailureKind.IO)
    }
}
