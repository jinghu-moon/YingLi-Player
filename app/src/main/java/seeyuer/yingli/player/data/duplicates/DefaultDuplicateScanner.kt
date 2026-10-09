package seeyuer.yingli.player.data.duplicates

import seeyuer.yingli.player.core.common.AppLogEvent
import seeyuer.yingli.player.core.common.AppLogLevel
import seeyuer.yingli.player.core.common.AppLogger
import seeyuer.yingli.player.core.common.LogValue
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.data.room.DuplicateDao
import seeyuer.yingli.player.data.room.MediaLocationEntity
import seeyuer.yingli.player.data.room.YingLiDatabase
import seeyuer.yingli.player.domain.catalog.MediaContentHasher
import seeyuer.yingli.player.domain.duplicates.DUPLICATE_HASH_ALGORITHM_VERSION
import seeyuer.yingli.player.domain.duplicates.DuplicateMode
import seeyuer.yingli.player.domain.duplicates.DuplicateScanProgress
import seeyuer.yingli.player.domain.duplicates.DuplicateScanResult
import seeyuer.yingli.player.domain.duplicates.DuplicateScanner

/**
 * L0–L4 分层扫描（设计稿 §7.1）。
 *
 * 分层全部由 **SQL 给出**，Kotlin 只做「取一页 → 算一个哈希 → 写回一行」：
 *
 * - **L0** `GROUP BY sizeBytes HAVING COUNT(*) >= 2`：单例大小直接跳过，绝不碰字节；
 * - **L1** 头尾各 64 KiB 的快速指纹，把「大小相同内容不同」剪掉；
 * - **L2** 只对 L1 之后仍 ≥ 2 的分桶算整文件流式 SHA-256；
 * - **L3** 等价类由 `GROUP BY contentHash, sizeBytes` 直接给出，**不建任何结果表**；
 * - **L4** 复核在提交处置之前做，见 `DefaultDuplicateDeletionExecutor`。
 *
 * 三条纪律：
 * 1. 读不出来就**不写哈希**，也不把它算作「已扫描成功」（失败数会回报给用户）；
 * 2. 算完前后各读一次元数据，`sizeBytes` 或 `modifiedEpochMillis` 变了就丢弃结果；
 * 3. 分页用 `id > cursor` 键游标而不是 `OFFSET`，因为筛选条件会被本方法自己的写入改变。
 */
class DefaultDuplicateScanner(
    database: YingLiDatabase,
    private val hasher: MediaContentHasher,
    private val logger: AppLogger,
    private val algorithmVersion: Int = DUPLICATE_HASH_ALGORITHM_VERSION,
) : DuplicateScanner {
    private val dao: DuplicateDao = database.duplicateDao()

    @Volatile
    private var cancelled: Boolean = false

    override fun cancel() {
        cancelled = true
    }

    override suspend fun scan(
        mode: DuplicateMode,
        onProgress: suspend (DuplicateScanProgress) -> Unit,
    ): DuplicateScanResult {
        if (mode != DuplicateMode.EXACT) {
            // SIMILAR 维持关闭（D6）：没有实测的 precision/recall 就没有可采信的阈值。
            return DuplicateScanResult.Rejected(SIMILAR_EXPERIMENT_DISABLED)
        }
        cancelled = false

        val buckets = dao.duplicateSizeBuckets()
        val candidateCount = buckets.sumOf { it.memberCount }
        onProgress(DuplicateScanProgress(DuplicateScanProgress.BUCKETING, 0, buckets.size))

        var quickHashedCount = 0
        var fullHashedCount = 0
        var failureCount = 0
        var processed = 0

        // ---- L1：快速指纹 ----------------------------------------------------
        for ((index, bucket) in buckets.withIndex()) {
            var cursor = FIRST_LOCATION_ID
            while (true) {
                if (cancelled) return DuplicateScanResult.Canceled
                val page = dao.pendingQuickHashLocations(bucket.sizeBytes, cursor, algorithmVersion, PAGE_SIZE)
                if (page.isEmpty()) break
                for (row in page) {
                    cursor = row.id
                    if (cancelled) return DuplicateScanResult.Canceled
                    processed++
                    when (val fingerprint = quickFingerprint(row)) {
                        null -> failureCount++
                        else -> {
                            if (!metadataUnchanged(row)) {
                                failureCount++
                            } else {
                                dao.updateFastFingerprint(row.id, fingerprint, algorithmVersion)
                                quickHashedCount++
                            }
                        }
                    }
                    if (processed % PROGRESS_EVERY == 0) {
                        onProgress(DuplicateScanProgress(DuplicateScanProgress.QUICK_HASH, processed, null))
                    }
                }
            }
            onProgress(DuplicateScanProgress(DuplicateScanProgress.BUCKETING, index + 1, buckets.size))
        }
        onProgress(DuplicateScanProgress(DuplicateScanProgress.QUICK_HASH, processed, null))

        // ---- L2：完整哈希 ----------------------------------------------------
        val pairs = dao.duplicateFastFingerprintPairs(algorithmVersion)
        var pairIndex = 0
        for (pair in pairs) {
            var cursor = FIRST_LOCATION_ID
            while (true) {
                if (cancelled) return DuplicateScanResult.Canceled
                val page = dao.pendingFullHashLocations(
                    sizeBytes = pair.sizeBytes,
                    fastFingerprint = pair.fastFingerprint,
                    afterId = cursor,
                    version = algorithmVersion,
                    limit = PAGE_SIZE,
                )
                if (page.isEmpty()) break
                for (row in page) {
                    cursor = row.id
                    if (cancelled) return DuplicateScanResult.Canceled
                    processed++
                    val hash = fullHash(row)
                    if (hash == null || !metadataUnchanged(row)) {
                        failureCount++
                    } else {
                        dao.updateContentHash(row.id, hash, algorithmVersion)
                        fullHashedCount++
                    }
                    if (processed % PROGRESS_EVERY == 0) {
                        onProgress(DuplicateScanProgress(DuplicateScanProgress.FULL_HASH, processed, null))
                    }
                }
            }
            pairIndex++
            onProgress(DuplicateScanProgress(DuplicateScanProgress.FULL_HASH, pairIndex, pairs.size))
        }

        // ---- L3：分组（由 SQL 数，不把行带进内存） ----------------------------
        onProgress(DuplicateScanProgress(DuplicateScanProgress.GROUPING, 0, null))
        val groupCount = dao.duplicateGroupCount(algorithmVersion)

        return DuplicateScanResult.Completed(
            candidateCount = candidateCount,
            quickHashedCount = quickHashedCount,
            fullHashedCount = fullHashedCount,
            failureCount = failureCount,
            groupCount = groupCount,
        )
    }

    private suspend fun quickFingerprint(row: MediaLocationEntity): String? =
        read(row, stage = "quick") { hasher.quickFingerprint(MediaUri(row.uri), row.sizeBytes) }

    private suspend fun fullHash(row: MediaLocationEntity): String? =
        read(row, stage = "full") { hasher.sha256(MediaUri(row.uri)) }

    private suspend fun read(
        row: MediaLocationEntity,
        stage: String,
        block: suspend () -> String?,
    ): String? = try {
        block()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        logFailure(row, stage, error)
        null
    }

    /**
     * 读取失败或元数据在读取期间变化都**不产生结论**：宁可下次再算，
     * 也不能把一个对不上文件现状的哈希写进库。
     */
    private suspend fun metadataUnchanged(row: MediaLocationEntity): Boolean {
        val current = dao.location(row.id) ?: return false
        return current.sizeBytes == row.sizeBytes && current.modifiedEpochMillis == row.modifiedEpochMillis
    }

    private fun logFailure(row: MediaLocationEntity, stage: String, error: Exception? = null) {
        logger.log(
            AppLogLevel.WARNING,
            AppLogEvent(
                code = "DUPLICATE_HASH_FAILED",
                message = "duplicate scan could not hash a location",
                attributes = mapOf(
                    "stage" to LogValue.Public(stage),
                    "failureType" to LogValue.Public(error?.javaClass?.simpleName ?: "unreadable"),
                    "sizeBytes" to LogValue.Public(row.sizeBytes.toString()),
                    "locationId" to LogValue.Sensitive(row.id),
                ),
            ),
        )
    }

    private companion object {
        /** 键游标分页：每页取这么多行。 */
        const val PAGE_SIZE = 256

        /** 每处理这么多行回报一次进度，避免把回调淹没。 */
        const val PROGRESS_EVERY = 16

        const val SIMILAR_EXPERIMENT_DISABLED = "SIMILAR_EXPERIMENT_DISABLED"

        /** 空字符串小于任何真实位置 id，用作「从头开始」的游标。 */
        const val FIRST_LOCATION_ID = ""
    }
}
