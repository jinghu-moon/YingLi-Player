package seeyuer.yingli.player.app.duplicates

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import seeyuer.yingli.player.core.foundation.AppClock
import seeyuer.yingli.player.domain.duplicates.DuplicateCandidate
import seeyuer.yingli.player.domain.duplicates.DuplicateEvidence
import seeyuer.yingli.player.domain.duplicates.DuplicateFingerprintGenerator
import seeyuer.yingli.player.domain.duplicates.DuplicateGroup
import seeyuer.yingli.player.domain.duplicates.DuplicateGroupId
import seeyuer.yingli.player.domain.duplicates.DuplicateMode
import seeyuer.yingli.player.domain.duplicates.DuplicateRepository
import seeyuer.yingli.player.domain.duplicates.DuplicateScanResult
import seeyuer.yingli.player.domain.duplicates.DuplicateScanner
import seeyuer.yingli.player.domain.duplicates.MediaFingerprint
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryQuery
import seeyuer.yingli.player.domain.library.LibraryRepository
import seeyuer.yingli.player.domain.library.LibraryResult

class DefaultDuplicateScanner(
    private val libraryRepository: LibraryRepository,
    private val duplicateRepository: DuplicateRepository,
    private val generator: DuplicateFingerprintGenerator,
    private val clock: AppClock,
) : DuplicateScanner {
    private val canceled = AtomicBoolean(false)

    override suspend fun scan(mode: DuplicateMode): DuplicateScanResult {
        canceled.set(false)
        if (mode == DuplicateMode.SIMILAR) return DuplicateScanResult.Rejected("SIMILAR_EXPERIMENT_DISABLED")
        return try {
            val media = loadAllMedia() ?: return DuplicateScanResult.Rejected("LIBRARY_UNAVAILABLE")
            val now = clock.now().toEpochMilli()
            val bySize = media.mapNotNull { item ->
                ensureNotCanceled()
                val cached = duplicateRepository.fingerprint(item.id)
                if (cached != null && cached.algorithmVersion == ALGORITHM_VERSION &&
                    cached.sourceModifiedEpochMillis == item.modifiedEpochMillis && cached.exactReady
                ) {
                    item to cached
                } else {
                    generator.size(item.uri.value)?.let { size -> item to MediaFingerprint(
                        item.id, size, null, null, item.durationMillis, item.width, item.height, emptyList(),
                        ALGORITHM_VERSION, item.modifiedEpochMillis, now,
                    ) }
                }
            }.groupBy { it.second.sizeBytes }

            val completed = mutableListOf<Pair<LibraryMedia, MediaFingerprint>>()
            bySize.values.filter { it.size >= 2 }.forEach { sizeBucket ->
                val byQuick = sizeBucket.mapNotNull { (item, base) ->
                    ensureNotCanceled()
                    val quick = base.quickHash ?: generator.quickHash(item.uri.value, base.sizeBytes)
                    quick?.let { item to base.copy(quickHash = it) }
                }.groupBy { it.second.quickHash }
                byQuick.values.filter { it.size >= 2 }.forEach { quickBucket ->
                    quickBucket.forEach { (item, base) ->
                        ensureNotCanceled()
                        val full = base.fullHash ?: generator.fullHash(item.uri.value)
                        if (full != null) completed += item to base.copy(fullHash = full)
                    }
                }
            }
            duplicateRepository.saveFingerprints(completed.map { it.second })
            val groups = completed.groupBy { it.second.sizeBytes to it.second.fullHash }
                .filterKeys { it.second != null }
                .values
                .filter { it.size >= 2 }
                .map { matches ->
                    val fingerprint = matches.first().second
                    DuplicateGroup(
                        id = DuplicateGroupId("exact-${requireNotNull(fingerprint.fullHash).take(24)}-${fingerprint.sizeBytes}"),
                        mode = DuplicateMode.EXACT,
                        candidates = matches.map { (_, value) -> DuplicateCandidate(value.mediaId, value) },
                        evidence = DuplicateEvidence.Exact(
                            fingerprint.sizeBytes,
                            requireNotNull(fingerprint.fullHash),
                            ALGORITHM_VERSION,
                            now,
                        ),
                    )
                }
            duplicateRepository.replaceGroups(DuplicateMode.EXACT, groups)
            DuplicateScanResult.Completed(groups)
        } catch (_: CancellationException) {
            DuplicateScanResult.Canceled
        }
    }

    override suspend fun cancel() {
        canceled.set(true)
    }

    private suspend fun loadAllMedia(): List<LibraryMedia>? {
        val result = mutableListOf<LibraryMedia>()
        var cursor: seeyuer.yingli.player.domain.library.LibraryCursor? = null
        do {
            val page = libraryRepository.query(LibraryQuery(cursor = cursor, pageSize = LibraryQuery.MAX_PAGE_SIZE))
            val value = (page as? LibraryResult.Success)?.value ?: return null
            result += value.items
            cursor = value.nextCursor
        } while (cursor != null)
        return result.distinctBy(LibraryMedia::id)
    }

    private fun ensureNotCanceled() {
        if (canceled.get()) throw CancellationException("Duplicate scan canceled")
    }

    companion object { const val ALGORITHM_VERSION = 1 }
}
