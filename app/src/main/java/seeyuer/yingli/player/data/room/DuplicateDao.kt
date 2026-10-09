package seeyuer.yingli.player.data.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** L0 的一个大小分桶。 */
data class DuplicateSizeBucket(
    val sizeBytes: Long,
    val memberCount: Int,
)

/** L1 的一个快速指纹分桶。 */
data class DuplicateHashPair(
    val sizeBytes: Long,
    val fastFingerprint: String,
    val memberCount: Int,
)

/**
 * 去重扫描的读写通道。
 *
 * 设计要点（设计稿 §7.1）：
 * - 分层由 **SQL 完成**，Kotlin 侧只负责「取一页、算一个哈希、写回一行」，因此内存占用与
 *   库大小无关；
 * - 结果就是 `media_locations` 上的三列（`fastFingerprint` / `contentHash` /
 *   `hashAlgorithmVersion`），不新建任何去重结果表；
 * - 已回收的位置（`trash_entries`）与已缺失的位置（`missingScanCount != 0`）一律不参与。
 */
@Dao
interface DuplicateDao {
    /** L0：出现过两次以上的文件大小。整表扫一遍 SQL，不把行带进内存。 */
    @Query(
        "SELECT sizeBytes, COUNT(*) AS memberCount FROM media_locations " +
            "WHERE missingScanCount = 0 AND " + LibrarySql.VISIBLE_LOCATION + " " +
            "GROUP BY sizeBytes HAVING COUNT(*) >= 2 ORDER BY sizeBytes",
    )
    suspend fun duplicateSizeBuckets(): List<DuplicateSizeBucket>

    /**
     * 一个大小分桶里**尚需快速指纹**的位置。
     *
     * 用 `id > :afterId` 的键游标分页，不用 `OFFSET`：本查询的筛选条件会被写入操作
     * 改变（算完就离开结果集），`OFFSET` 会让被跳过的行永远取不到。
     */
    @Query(
        "SELECT * FROM media_locations WHERE sizeBytes = :sizeBytes AND id > :afterId AND " +
            "(fastFingerprint IS NULL OR hashAlgorithmVersion IS NULL OR hashAlgorithmVersion <> :version) AND " +
            "missingScanCount = 0 AND " + LibrarySql.VISIBLE_LOCATION + " ORDER BY id LIMIT :limit",
    )
    suspend fun pendingQuickHashLocations(
        sizeBytes: Long,
        afterId: String,
        version: Int,
        limit: Int,
    ): List<MediaLocationEntity>

    /** L2：快速指纹相同且成员数 ≥ 2 的分桶。 */
    @Query(
        "SELECT sizeBytes, fastFingerprint, COUNT(*) AS memberCount FROM media_locations " +
            "WHERE fastFingerprint IS NOT NULL AND hashAlgorithmVersion = :version AND " +
            "missingScanCount = 0 AND " + LibrarySql.VISIBLE_LOCATION + " " +
            "GROUP BY sizeBytes, fastFingerprint HAVING COUNT(*) >= 2 ORDER BY sizeBytes",
    )
    suspend fun duplicateFastFingerprintPairs(version: Int): List<DuplicateHashPair>

    /** 某个快速指印分桶里**尚未**算出完整哈希的位置，同样用键游标分页。 */
    @Query(
        "SELECT * FROM media_locations WHERE sizeBytes = :sizeBytes AND fastFingerprint = :fastFingerprint " +
            "AND id > :afterId AND hashAlgorithmVersion = :version AND contentHash IS NULL AND missingScanCount = 0 AND " +
            LibrarySql.VISIBLE_LOCATION + " ORDER BY id LIMIT :limit",
    )
    suspend fun pendingFullHashLocations(
        sizeBytes: Long,
        fastFingerprint: String,
        afterId: String,
        version: Int,
        limit: Int,
    ): List<MediaLocationEntity>

    /**
     * 重新计算快速指纹。
     *
     * **同时清掉 `contentHash`**：走到这里只有两种可能——要么从来没算过，要么算的是旧算法
     * 版本的哈希，两种情况下旧 `contentHash` 都不可信。留着它会让 L3 把一个陈旧哈希
     * 当成当前版本的结论。
     */
    @Query(
        "UPDATE media_locations SET fastFingerprint = :fastFingerprint, contentHash = NULL, " +
            "hashAlgorithmVersion = :version WHERE id = :locationId",
    )
    suspend fun updateFastFingerprint(locationId: String, fastFingerprint: String, version: Int)

    @Query("UPDATE media_locations SET contentHash = :contentHash, hashAlgorithmVersion = :version WHERE id = :locationId")
    suspend fun updateContentHash(locationId: String, contentHash: String, version: Int)

    /**
     * 演示用/清理用：把某个位置的哈希整体作废（`DefaultMediaScanner` 检测到
     * 大小或修改时间变化时调用，见 `media_locations` 的不变量说明）。
     */
    @Query("UPDATE media_locations SET fastFingerprint = NULL, contentHash = NULL, hashAlgorithmVersion = NULL WHERE id = :locationId")
    suspend fun clearHashes(locationId: String)

    /**
     * 读模型：全部「已按当前算法版本算过完整哈希、且位置未被回收」的位置行。
     *
     * 调用方在 Kotlin 里按 `(contentHash, sizeBytes)` 分组并只保留
     * 涉及 ≥ 2 个 `media_items` 行的组——**重复组不是实体**，它就是这个 `GROUP BY` 的结果。
     * 一行一个位置，因此读模型的内存占用与「重复文件数」成正比，而不是与库大小成正比。
     */
    @Query(
        "SELECT media_locations.id AS locationId, media_item_locations.mediaItemId AS mediaItemId, " +
            "media_locations.contentHash AS contentHash, " +
            "media_locations.uri AS uri, media_locations.fileName AS fileName, " +
            "media_locations.relativePath AS relativePath, media_sources.mode AS sourceMode, " +
            "media_locations.sizeBytes AS sizeBytes, media_locations.modifiedEpochMillis AS modifiedEpochMillis, " +
            "media_locations.durationMillis AS durationMillis, media_locations.width AS width, " +
            "media_locations.height AS height, media_locations.missingScanCount AS missingScanCount, " +
            "media_locations.lastSeenEpochMillis AS lastSeenEpochMillis " +
            "FROM media_locations " +
            "INNER JOIN media_item_locations ON media_item_locations.locationId = media_locations.id " +
            "INNER JOIN media_sources ON media_sources.id = media_locations.sourceId " +
            "WHERE media_locations.contentHash IS NOT NULL AND media_locations.hashAlgorithmVersion = :version " +
            "AND media_locations.missingScanCount = 0 AND " + LibrarySql.VISIBLE_LOCATION + " " +
            "ORDER BY media_locations.contentHash, media_locations.sizeBytes, media_locations.fileName COLLATE NOCASE, media_locations.id",
    )
    fun observeHashedLocations(version: Int): Flow<List<DuplicateCandidateRow>>

    /** 复核用：按位置 id 读原始行。 */
    @Query("SELECT * FROM media_locations WHERE id IN (:locationIds)")
    suspend fun locations(locationIds: List<String>): List<MediaLocationEntity>

    /** 扫描期间的前后一致性校验：重读同一行。 */
    @Query("SELECT * FROM media_locations WHERE id = :locationId")
    suspend fun location(locationId: String): MediaLocationEntity?

    /** L3：当前算法版本下成员数 ≥ 2 的等价类个数（由 SQL 数，不把行带进内存）。 */
    @Query(
        "SELECT COUNT(*) FROM (SELECT media_locations.contentHash, media_locations.sizeBytes " +
            "FROM media_locations WHERE contentHash IS NOT NULL AND hashAlgorithmVersion = :version AND " +
            "missingScanCount = 0 AND " + LibrarySql.VISIBLE_LOCATION + " " +
            "GROUP BY contentHash, sizeBytes HAVING COUNT(*) >= 2)",
    )
    suspend fun duplicateGroupCount(version: Int): Int

    @Query("SELECT * FROM duplicate_ignores")
    fun observeIgnores(): Flow<List<DuplicateIgnoreEntity>>

    @Query("SELECT * FROM duplicate_ignores WHERE contentHash = :contentHash AND sizeBytes = :sizeBytes")
    suspend fun ignore(contentHash: String, sizeBytes: Long): DuplicateIgnoreEntity?

    @Upsert
    suspend fun upsertIgnore(ignore: DuplicateIgnoreEntity)

    @Query("DELETE FROM duplicate_ignores WHERE contentHash = :contentHash AND sizeBytes = :sizeBytes")
    suspend fun deleteIgnore(contentHash: String, sizeBytes: Long)
}

/** 读模型行：一个位置 + 它所属的条目 + 来源模式。 */
data class DuplicateCandidateRow(
    val locationId: String,
    val mediaItemId: String,
    /** `observeHashedLocations` 一定非空；其它查询不带这一列时才是 null。 */
    val contentHash: String?,
    val uri: String,
    val fileName: String,
    val relativePath: String?,
    val sourceMode: String,
    val sizeBytes: Long,
    val modifiedEpochMillis: Long,
    val durationMillis: Long?,
    val width: Int?,
    val height: Int?,
    val missingScanCount: Int,
    val lastSeenEpochMillis: Long,
)
