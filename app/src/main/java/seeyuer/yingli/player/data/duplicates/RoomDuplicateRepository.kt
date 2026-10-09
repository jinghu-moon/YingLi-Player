package seeyuer.yingli.player.data.duplicates

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaSourceMode
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.data.room.DuplicateCandidateRow
import seeyuer.yingli.player.data.room.DuplicateIgnoreEntity
import seeyuer.yingli.player.data.room.YingLiDatabase
import seeyuer.yingli.player.domain.duplicates.DUPLICATE_HASH_ALGORITHM_VERSION
import seeyuer.yingli.player.domain.duplicates.DuplicateCandidate
import seeyuer.yingli.player.domain.duplicates.DuplicateGroup
import seeyuer.yingli.player.domain.duplicates.DuplicateRepository

/**
 * 等价类读模型。
 *
 * **重复组不是表**（设计稿 §4.7）：它就是 `media_locations` 上
 * `GROUP BY contentHash, sizeBytes` 的结果，因此这里没有任何写入路径会造组，
 * 唯一的写入是「忽略某个组」。
 *
 * 只报告**尚未归并**的等价类：同一 `contentHash` 下出现 ≥ 2 个不同的 `media_items`
 * 行才说明这次重复还没处理。同一 `media_items` 行持有两份相同内容的多个位置不算重复
 * ——它们已经是一个条目了。
 */
class RoomDuplicateRepository(
    database: YingLiDatabase,
    private val algorithmVersion: Int = DUPLICATE_HASH_ALGORITHM_VERSION,
) : DuplicateRepository {
    private val dao = database.duplicateDao()

    override val groups: Flow<List<DuplicateGroup>> = combine(
        dao.observeHashedLocations(algorithmVersion),
        dao.observeIgnores(),
    ) { rows, ignores -> buildGroups(rows, ignores) }

    override suspend fun ignore(group: DuplicateGroup, ignoredAtEpochMillis: Long) {
        dao.upsertIgnore(
            DuplicateIgnoreEntity(
                contentHash = group.contentHash,
                sizeBytes = group.sizeBytes,
                // 记下被忽略时的成员数：成员数一变，这个组会重新出现（修 G26）。
                memberCount = group.candidates.size,
                ignoredAtEpochMillis = ignoredAtEpochMillis,
            ),
        )
    }

    private fun buildGroups(
        rows: List<DuplicateCandidateRow>,
        ignores: List<DuplicateIgnoreEntity>,
    ): List<DuplicateGroup> {
        val ignoredByKey = ignores.associateBy { it.contentHash to it.sizeBytes }
        return rows.asSequence()
            .groupBy { it.contentHash to it.sizeBytes }
            .mapNotNull { (key, groupRows) ->
                val contentHash = key.first ?: return@mapNotNull null
                // 等价类要「待处理」必须跨越两个条目。
                if (groupRows.map(DuplicateCandidateRow::mediaItemId).distinct().size < 2) {
                    return@mapNotNull null
                }
                if (ignoredByKey[key]?.memberCount == groupRows.size) return@mapNotNull null
                DuplicateGroup(
                    contentHash = contentHash,
                    sizeBytes = key.second,
                    candidates = groupRows.map { it.toDomain() },
                )
            }
            .sortedWith(
                compareByDescending<DuplicateGroup> { it.reclaimableBytes(emptySet()) }
                    .thenBy { it.contentHash },
            )
            .toList()
    }

    private fun DuplicateCandidateRow.toDomain() = DuplicateCandidate(
        locationId = MediaLocationId(locationId),
        mediaItemId = MediaItemId(mediaItemId),
        uri = MediaUri(uri),
        fileName = fileName,
        relativePath = relativePath,
        sourceMode = runCatching { MediaSourceMode.valueOf(sourceMode) }
            .getOrDefault(MediaSourceMode.MEDIA_STORE),
        sizeBytes = sizeBytes,
        modifiedEpochMillis = modifiedEpochMillis,
        durationMillis = durationMillis,
        width = width,
        height = height,
        missingScanCount = missingScanCount,
        lastSeenEpochMillis = lastSeenEpochMillis,
    )
}
