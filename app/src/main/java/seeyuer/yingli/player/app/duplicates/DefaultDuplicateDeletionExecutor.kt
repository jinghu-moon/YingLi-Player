package seeyuer.yingli.player.app.duplicates

import kotlinx.coroutines.flow.first
import seeyuer.yingli.player.domain.duplicates.DuplicateDeletionExecutor
import seeyuer.yingli.player.domain.duplicates.DuplicateDeletionPlan
import seeyuer.yingli.player.domain.duplicates.DuplicateDeletionResult
import seeyuer.yingli.player.domain.duplicates.DuplicateDeletionValidator
import seeyuer.yingli.player.domain.duplicates.DuplicateFingerprintGenerator
import seeyuer.yingli.player.domain.duplicates.DuplicateMode
import seeyuer.yingli.player.domain.duplicates.DuplicatePlanValidation
import seeyuer.yingli.player.domain.duplicates.DuplicateRepository
import seeyuer.yingli.player.domain.duplicates.MediaFingerprint
import seeyuer.yingli.player.domain.library.LibraryCursor
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryMutationRepository
import seeyuer.yingli.player.domain.library.LibraryQuery
import seeyuer.yingli.player.domain.library.LibraryRepository
import seeyuer.yingli.player.domain.library.LibraryResult

class DefaultDuplicateDeletionExecutor(
    private val duplicateRepository: DuplicateRepository,
    private val libraryRepository: LibraryRepository,
    private val mutationRepository: LibraryMutationRepository,
    private val generator: DuplicateFingerprintGenerator,
) : DuplicateDeletionExecutor {
    override suspend fun execute(plan: DuplicateDeletionPlan): DuplicateDeletionResult {
        val group = duplicateRepository.groups.first().firstOrNull { it.id == plan.groupId }
            ?: return DuplicateDeletionResult.Rejected("GROUP_NOT_FOUND")
        if (group.mode != DuplicateMode.EXACT) return DuplicateDeletionResult.Rejected("SIMILAR_DELETION_DISABLED")
        val media = loadAllMedia()?.associateBy(LibraryMedia::id)
            ?: return DuplicateDeletionResult.Rejected("LIBRARY_UNAVAILABLE")
        val current = linkedMapOf<seeyuer.yingli.player.core.model.media.MediaItemId, MediaFingerprint>()
        group.candidates.forEach { candidate ->
            val item = media[candidate.mediaId] ?: return DuplicateDeletionResult.Rejected("MEDIA_MISSING")
            val size = generator.size(item.uri.value) ?: return DuplicateDeletionResult.Rejected("FILE_UNREADABLE")
            val full = generator.fullHash(item.uri.value) ?: return DuplicateDeletionResult.Rejected("FILE_UNREADABLE")
            current[item.id] = candidate.fingerprint.copy(
                sizeBytes = size,
                fullHash = full,
                sourceModifiedEpochMillis = item.modifiedEpochMillis,
            )
        }
        when (val validation = DuplicateDeletionValidator.validate(group, plan, current)) {
            DuplicatePlanValidation.Valid -> Unit
            is DuplicatePlanValidation.Invalid -> return DuplicateDeletionResult.Rejected(validation.code)
        }
        val targets = plan.trashMediaIds.mapNotNull(media::get)
        if (targets.size != plan.trashMediaIds.size) return DuplicateDeletionResult.Rejected("MEDIA_MISSING")
        val result = mutationRepository.trash(targets)
        if (result.succeeded > 0) duplicateRepository.ignore(group.id)
        return when {
            result.failed == 0 -> DuplicateDeletionResult.Completed(result.succeeded)
            result.succeeded == 0 -> DuplicateDeletionResult.Rejected("TRASH_FAILED")
            else -> DuplicateDeletionResult.Partial(result.succeeded, result.failed)
        }
    }

    private suspend fun loadAllMedia(): List<LibraryMedia>? {
        val result = mutableListOf<LibraryMedia>()
        var cursor: LibraryCursor? = null
        do {
            val page = libraryRepository.query(LibraryQuery(cursor = cursor, pageSize = LibraryQuery.MAX_PAGE_SIZE))
            val value = (page as? LibraryResult.Success)?.value ?: return null
            result += value.items
            cursor = value.nextCursor
        } while (cursor != null)
        return result
    }
}
