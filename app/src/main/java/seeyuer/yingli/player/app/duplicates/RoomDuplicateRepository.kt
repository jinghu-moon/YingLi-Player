package seeyuer.yingli.player.app.duplicates

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import seeyuer.yingli.player.core.database.DuplicateFingerprintEntity
import seeyuer.yingli.player.core.database.DuplicateGroupEntity
import seeyuer.yingli.player.core.database.DuplicateGroupMemberEntity
import seeyuer.yingli.player.core.database.YingLiDatabase
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.duplicates.DuplicateCandidate
import seeyuer.yingli.player.domain.duplicates.DuplicateEvidence
import seeyuer.yingli.player.domain.duplicates.DuplicateGroup
import seeyuer.yingli.player.domain.duplicates.DuplicateGroupId
import seeyuer.yingli.player.domain.duplicates.DuplicateMode
import seeyuer.yingli.player.domain.duplicates.DuplicateRepository
import seeyuer.yingli.player.domain.duplicates.MediaFingerprint

class RoomDuplicateRepository(
    private val database: YingLiDatabase,
) : DuplicateRepository {
    private val dao = database.duplicateDao()

    override val groups: Flow<List<DuplicateGroup>> = dao.observeGroups().map { rows ->
        rows.mapNotNull { row ->
            val memberRows = dao.members(row.id)
            val fingerprints = dao.fingerprints(memberRows.map(DuplicateGroupMemberEntity::mediaItemId))
                .associateBy(DuplicateFingerprintEntity::mediaItemId)
            val candidates = memberRows.mapNotNull { member ->
                fingerprints[member.mediaItemId]?.toDomain()?.let { DuplicateCandidate(MediaItemId(member.mediaItemId), it) }
            }
            if (candidates.size < 2) return@mapNotNull null
            DuplicateGroup(
                DuplicateGroupId(row.id),
                DuplicateMode.valueOf(row.mode),
                candidates,
                row.toEvidence() ?: return@mapNotNull null,
            )
        }
    }

    override suspend fun fingerprint(mediaId: MediaItemId): MediaFingerprint? =
        dao.fingerprint(mediaId.value)?.toDomain()

    override suspend fun saveFingerprints(fingerprints: List<MediaFingerprint>) {
        if (fingerprints.isNotEmpty()) dao.upsertFingerprints(fingerprints.map { it.toEntity() })
    }

    override suspend fun replaceGroups(mode: DuplicateMode, groups: List<DuplicateGroup>) {
        require(groups.all { it.mode == mode })
        database.withTransaction {
            dao.deleteGroups(mode.name)
            if (groups.isNotEmpty()) {
                dao.insertGroups(groups.map { it.toEntity() })
                dao.insertMembers(groups.flatMap { group ->
                    group.candidates.mapIndexed { index, candidate ->
                        DuplicateGroupMemberEntity(group.id.value, candidate.mediaId.value, index)
                    }
                })
            }
        }
    }

    override suspend fun ignore(groupId: DuplicateGroupId) = dao.deleteGroup(groupId.value)

    private fun DuplicateFingerprintEntity.toDomain() = MediaFingerprint(
        MediaItemId(mediaItemId),
        sizeBytes,
        quickHash,
        fullHash,
        durationMillis,
        width,
        height,
        perceptualHashes.split(',').mapNotNull(String::toLongOrNull),
        algorithmVersion,
        sourceModifiedEpochMillis,
        generatedAtEpochMillis,
    )

    private fun MediaFingerprint.toEntity() = DuplicateFingerprintEntity(
        mediaId.value,
        sizeBytes,
        quickHash,
        fullHash,
        durationMillis,
        width,
        height,
        perceptualHashes.joinToString(","),
        algorithmVersion,
        sourceModifiedEpochMillis,
        generatedAtEpochMillis,
    )

    private fun DuplicateGroupEntity.toEvidence(): DuplicateEvidence? = when (DuplicateMode.valueOf(mode)) {
        DuplicateMode.EXACT -> DuplicateEvidence.Exact(
            sizeBytes ?: return null,
            fullHash ?: return null,
            algorithmVersion,
            generatedAtEpochMillis,
        )
        DuplicateMode.SIMILAR -> DuplicateEvidence.Similar(
            visualScore ?: return null,
            durationScore ?: return null,
            dimensionScore ?: return null,
            overallScore ?: return null,
            algorithmVersion,
            generatedAtEpochMillis,
        )
    }

    private fun DuplicateGroup.toEntity(): DuplicateGroupEntity = when (val value = evidence) {
        is DuplicateEvidence.Exact -> DuplicateGroupEntity(
            id.value, mode.name, value.sizeBytes, value.fullHash,
            null, null, null, null, value.algorithmVersion, value.generatedAtEpochMillis,
        )
        is DuplicateEvidence.Similar -> DuplicateGroupEntity(
            id.value, mode.name, null, null,
            value.visualScore, value.durationScore, value.dimensionScore, value.overallScore,
            value.algorithmVersion, value.generatedAtEpochMillis,
        )
    }
}
