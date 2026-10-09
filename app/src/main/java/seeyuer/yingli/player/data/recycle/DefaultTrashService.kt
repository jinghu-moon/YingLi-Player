package seeyuer.yingli.player.data.recycle

import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.processing.PROCESSING_FREE_SPACE_RESERVE_BYTES
import seeyuer.yingli.player.domain.processing.ProcessingProjectType
import seeyuer.yingli.player.domain.recycle.MediaOperationGuard
import seeyuer.yingli.player.domain.recycle.ReconcileReport
import seeyuer.yingli.player.domain.recycle.RecycleBinStorage
import seeyuer.yingli.player.domain.recycle.RecycleCatalogGateway
import seeyuer.yingli.player.domain.recycle.TrashBackend
import seeyuer.yingli.player.domain.recycle.TrashEntry
import seeyuer.yingli.player.domain.recycle.TrashOperationOutcome
import seeyuer.yingli.player.domain.recycle.TrashOperationReport
import seeyuer.yingli.player.domain.recycle.TrashRepository
import seeyuer.yingli.player.domain.recycle.TrashService
import seeyuer.yingli.player.domain.recycle.TrashState
import kotlinx.coroutines.flow.Flow

/**
 * 回收站的状态机与事务边界（§8.3–§8.7、§10）。
 *
 * 这个类的职责边界刻意很窄：**它不碰文件，也不碰 SQL**。
 * - 文件与系统对话框全在 [RecycleBinStorage]；
 * - `trash_entries` 的行读写全在 [TrashRepository]；
 * - `media_locations` / `media_items` 的痕量全在 [RecycleCatalogGateway]；
 * - 它自己只回答一个问题：**现在允许把状态推进到哪一格**。
 *
 * 因此每条路径都遵循 §10.1 的跨域规则：**先在 Room 里落「打算做什么」，再动文件，
 * 最后落「实际发生了什么」**。任何一步失败都不允许删源文件。
 */
class DefaultTrashService(
    private val repository: TrashRepository,
    private val storage: RecycleBinStorage,
    private val catalog: RecycleCatalogGateway,
    private val guard: MediaOperationGuard,
    private val clock: AppClock,
) : TrashService {

    /** token → 授权发生前那一步的条目。**只在内存里**：授权 token 本来就不跨进程存活。 */
    private val pendingEntries = HashMap<String, TrashEntry>()

    override fun observe(): Flow<List<TrashEntry>> = repository.observe()

    override suspend fun move(item: LibraryMedia): TrashOperationOutcome = moveOne(item, now())

    override suspend fun move(items: List<LibraryMedia>): TrashOperationReport {
        val outcomes = LinkedHashMap<MediaLocationId, TrashOperationOutcome>()
        val now = now()
        items.forEach { item -> outcomes[item.locationId] = moveOne(item, now) }
        return TrashOperationReport(outcomes)
    }

    override suspend fun restore(locationId: MediaLocationId): TrashOperationOutcome {
        val entry = repository.byLocation(locationId)
            ?: return TrashOperationOutcome.Blocked("ENTRY_NOT_FOUND")
        val now = now()
        if (entry.state != TrashState.ACTIVE) {
            return TrashOperationOutcome.Blocked("STATE_NOT_RESTORABLE", entry.state.name)
        }
        // §8.5 第 1 步：**过期即在业务层拒绝**，不等物理清理跑过。
        if (entry.isExpired(now)) {
            return TrashOperationOutcome.Blocked("RETENTION_EXPIRED")
        }
        if (entry.backend == TrashBackend.R2_APP_COPY && entry.copyRelativePath == null) {
            return TrashOperationOutcome.Blocked("NO_COPY")
        }
        blockingTask(entry.mediaItemId)?.let {
            return TrashOperationOutcome.Blocked("BLOCKED_BY_RUNNING_TASK", it.name)
        }
        // §10.1：先把「打算恢复」落盘，进程此时被杀也能被对账认出来（RESTORING 分支）。
        val restoring = entry.copy(state = TrashState.RESTORING, updatedAtEpochMillis = now)
        repository.update(restoring)
        return when (val outcome = storage.restore(restoring)) {
            is TrashOperationOutcome.Completed -> finishRestore(outcome.entry)
            // §8.3：恢复失败时**副本仍然安全**，退回 ACTIVE 让用户能再试一次或改为永久删除。
            is TrashOperationOutcome.Failed -> {
                val back = outcome.entry.copy(state = TrashState.ACTIVE, updatedAtEpochMillis = now())
                repository.update(back)
                TrashOperationOutcome.Failed(back, outcome.code, outcome.detail)
            }
            is TrashOperationOutcome.AuthorizationRequired -> {
                pendingEntries[outcome.request.token] = restoring
                outcome
            }
            is TrashOperationOutcome.Blocked -> {
                repository.update(entry)
                outcome
            }
            is TrashOperationOutcome.Purged -> outcome
            // 存储层不会返回 `Discarded`（那是本服务的动作，不是后端动作）；分支只为穷尽性存在。
            is TrashOperationOutcome.Discarded -> outcome
        }
    }

    override suspend fun purge(locationId: MediaLocationId): TrashOperationOutcome {
        val entry = repository.byLocation(locationId)
            ?: return TrashOperationOutcome.Blocked("ENTRY_NOT_FOUND")
        if (entry.state.isTransitional) {
            return TrashOperationOutcome.Blocked("STATE_NOT_PURGEABLE", entry.state.name)
        }
        blockingTask(entry.mediaItemId)?.let {
            return TrashOperationOutcome.Blocked("BLOCKED_BY_RUNNING_TASK", it.name)
        }
        return purgeEntry(entry)
    }

    override suspend fun purgeAll(): TrashOperationReport {
        val outcomes = LinkedHashMap<MediaLocationId, TrashOperationOutcome>()
        // 「清空」= 逐个走同一条 purge 路径：**每个文件都确认删除成功之后才删元数据**（§8.6 规则 2），
        // 因此不允许「先清表再删文件」那种一次事务吞掉全部失败的写法。
        repository.inStates(setOf(TrashState.ACTIVE)).forEach { entry ->
            outcomes[entry.locationId] = purgeEntry(entry)
        }
        return TrashOperationReport(outcomes)
    }

    override suspend fun discard(locationId: MediaLocationId): TrashOperationOutcome {
        val entry = repository.byLocation(locationId)
            ?: return TrashOperationOutcome.Blocked("ENTRY_NOT_FOUND")
        if (entry.state != TrashState.FAILED) {
            return TrashOperationOutcome.Blocked("STATE_NOT_DISCARDABLE", entry.state.name)
        }
        // 唯一副本守卫：源文件必须仍然在。源已经不在了说明隔离副本是唯一的一份，
        // 此时让用户「放弃」就等于删掉唯一副本 —— 必须走对账，而不是这里。
        if (!storage.isReadable(entry.originalUri)) {
            return TrashOperationOutcome.Blocked("SOURCE_MISSING")
        }
        blockingTask(entry.mediaItemId)?.let {
            return TrashOperationOutcome.Blocked("BLOCKED_BY_RUNNING_TASK", it.name)
        }
        entry.copyRelativePath?.substringAfterLast('/')?.let { name ->
            storage.removeCopyFile(name)
        }
        repository.remove(entry.locationId)
        return TrashOperationOutcome.Discarded(entry.locationId)
    }

    override suspend fun resolveAuthorization(token: String, granted: Boolean): TrashOperationOutcome? {
        val entry = pendingEntries.remove(token) ?: return null
        val outcome = storage.resolveAuthorization(token, entry, granted) ?: return null
        return when (outcome) {
            is TrashOperationOutcome.Completed -> {
                repository.update(outcome.entry)
                outcome
            }
            is TrashOperationOutcome.Purged -> {
                catalog.detachLocation(entry.locationId, entry.mediaItemId)
                repository.remove(entry.locationId)
                outcome
            }
            is TrashOperationOutcome.Failed -> {
                repository.update(outcome.entry)
                outcome
            }
            is TrashOperationOutcome.Blocked -> outcome
            is TrashOperationOutcome.AuthorizationRequired -> outcome
            // 授权流程不会产出「放弃记录」，分支只为穷尽性存在。
            is TrashOperationOutcome.Discarded -> {
                repository.remove(entry.locationId)
                outcome
            }
        }
    }

    override suspend fun reconcile(): ReconcileReport {
        val now = now()
        var repaired = 0
        var needsReview = 0
        var errors = 0
        val details = mutableListOf<String>()

        repository.inStates(setOf(TrashState.STAGING)).forEach { entry ->
            // 未验证的 `.partial` 副本不安全，只能丢弃；源文件从未被触碰，所以是 FAILED 而不是丢数据。
            storage.removeStagingFile("${entry.copyRelativePath.orEmpty().substringAfterLast('/')}")
            repository.update(
                entry.copy(
                    state = TrashState.FAILED,
                    lastErrorCode = "INTERRUPTED_STAGING",
                    lastErrorDetail = "复制在进程被杀时中断，未验证的副本已丢弃，源文件未改动",
                    updatedAtEpochMillis = now,
                ),
            )
            repaired += 1
        }

        repository.inStates(setOf(TrashState.WAITING_SOURCE_DELETE_AUTH)).forEach { entry ->
            when {
                !storage.isReadable(entry.originalUri) -> {
                    // 源已经不可读却还没确认删除：既不能删副本（可能是唯一副本），也不能当成功。
                    needsReview += 1
                    details += "WAITING 条目源不可读，等待人工决定：${entry.locationId.value}"
                    repository.update(entry.copy(state = TrashState.RECONCILIATION_REQUIRED, updatedAtEpochMillis = now))
                }
                else -> {
                    storage.quarantineCopy(entry)
                    repository.update(
                        entry.copy(
                            state = TrashState.FAILED,
                            lastErrorCode = "SOURCE_DELETE_UNCONFIRMED",
                            lastErrorDetail = "源文件仍在，副本已退回 recovery/，可重新发起移入",
                            updatedAtEpochMillis = now,
                        ),
                    )
                    repaired += 1
                }
            }
        }

        repository.inStates(setOf(TrashState.ACTIVE)).forEach { entry ->
            if (!storage.isReadable(entry.originalUri) && entry.lastErrorCode != SOURCE_MISSING) {
                // 只加诊断、不改状态：R1 的条目可能只是被系统提前清掉了，期限语义仍由后端决定。
                repository.update(
                    entry.copy(
                        lastErrorCode = SOURCE_MISSING,
                        lastErrorDetail = "源文件已消失（可能已被系统或其他应用删除）",
                        updatedAtEpochMillis = now,
                    ),
                )
                needsReview += 1
                details += "源文件已消失：${entry.locationId.value}"
            }
        }

        repository.inStates(setOf(TrashState.RESTORING)).forEach { entry ->
            val restoreUri = entry.restoreUri
            if (restoreUri != null && storage.isReadable(restoreUri)) {
                finishRestore(entry)
                repaired += 1
            } else {
                // 恢复没落地：副本与记录都还在（§8.5 规则 1），退回 ACTIVE 即可安全重试。
                repository.update(
                    entry.copy(
                        state = TrashState.ACTIVE,
                        lastErrorCode = "RESTORE_INTERRUPTED",
                        lastErrorDetail = "恢复过程被中断，副本与记录均保留",
                        updatedAtEpochMillis = now,
                    ),
                )
                repaired += 1
            }
        }

        repository.inStates(setOf(TrashState.CLEANUP_PENDING)).forEach { entry ->
            when (val outcome = storage.purge(entry)) {
                is TrashOperationOutcome.Purged -> {
                    catalog.detachLocation(entry.locationId, entry.mediaItemId)
                    repository.remove(entry.locationId)
                    repaired += 1
                }
                else -> errors += 1
            }
        }

        // RECONCILIATION_REQUIRED 一律**不自动处理**（§8.3）：歧义必须由人消解。
        val reviewNeeded = repository.inStates(setOf(TrashState.RECONCILIATION_REQUIRED))
        needsReview += reviewNeeded.size

        // `items/` 里没有记录引用的残留：退回 `recovery/`，**不删除**。
        val referenced = repository.inStates(TrashState.entries.toSet())
            .mapNotNull { it.copyRelativePath?.substringAfterLast('/') }
            .toSet()
        storage.copyFileNames().filterNot { it in referenced }.forEach { orphan ->
            if (storage.quarantineOrphanCopy(orphan)) {
                details += "孤立副本已退回 recovery/：$orphan"
            }
            needsReview += 1
        }

        val orphanItems = catalog.deleteItemsWithoutLocations()
        if (orphanItems > 0) details += "清理了 $orphanItems 个没有位置行的媒体条目"

        return ReconcileReport(repaired = repaired, needsReview = needsReview, errors = errors, details = details)
    }

    override suspend fun cleanupExpired(nowEpochMillis: Long): ReconcileReport {
        var repaired = 0
        var needsReview = 0
        var errors = 0
        val details = mutableListOf<String>()
        repository.expired(nowEpochMillis).forEach { entry ->
            when (val outcome = storage.purge(entry)) {
                is TrashOperationOutcome.Purged -> {
                    catalog.detachLocation(entry.locationId, entry.mediaItemId)
                    repository.remove(entry.locationId)
                    repaired += 1
                }
                is TrashOperationOutcome.Blocked -> {
                    // 例如旧 R3 条目：不该删的副本被挡住是正确的，记下来等人工。
                    needsReview += 1
                    details += "${entry.locationId.value}: ${outcome.code}"
                    repository.update(
                        entry.copy(
                            state = TrashState.RECONCILIATION_REQUIRED,
                            lastErrorCode = outcome.code,
                            lastErrorDetail = outcome.detail,
                            updatedAtEpochMillis = now(),
                        ),
                    )
                }
                else -> {
                    errors += 1
                    repository.update(
                        entry.copy(
                            state = TrashState.CLEANUP_PENDING,
                            lastErrorCode = (outcome as? TrashOperationOutcome.Failed)?.code ?: "CLEANUP_FAILED",
                            retryCount = entry.retryCount + 1,
                            updatedAtEpochMillis = now(),
                        ),
                    )
                }
            }
        }
        return ReconcileReport(repaired = repaired, needsReview = needsReview, errors = errors, details = details)
    }

    override suspend fun activeOperations(item: LibraryMedia): Set<ProcessingProjectType> =
        guard.activeOperations(item.id, item.locationId)

    // ---- 内部 --------------------------------------------------------------

    private suspend fun moveOne(item: LibraryMedia, now: Long): TrashOperationOutcome {
        blockingTask(item.id)?.let {
            return TrashOperationOutcome.Blocked("BLOCKED_BY_RUNNING_TASK", "正在执行：${it.name}")
        }
        // 幂等：已经有条目的位置直接回它。重复点击不得重做一遍文件操作，
        // 更不允许把一个 ACTIVE 条目打回 STAGING（`insertTrash` 用 IGNORE 是第二道闸）。
        repository.byLocation(item.locationId)?.let { existing ->
            if (existing.state != TrashState.FAILED) return TrashOperationOutcome.Completed(existing)
        }
        val useSystemTrash = storage.systemTrashSupported(item)
        val backend = if (useSystemTrash) TrashBackend.R1_SYSTEM else TrashBackend.R2_APP_COPY
        if (!useSystemTrash) {
            // §8.4 第 4 步：R2 需要约等于原文件大小的额外空间，**不允许「边复制边删源」绕过**。
            val available = storage.availableBytes()
            val required = item.sizeBytes + PROCESSING_FREE_SPACE_RESERVE_BYTES
            if (item.sizeBytes > 0 && available < required) {
                return TrashOperationOutcome.Blocked(
                    "INSUFFICIENT_SPACE",
                    "需要约 ${required / (1024 * 1024)} MiB，可用 ${available / (1024 * 1024)} MiB",
                )
            }
        }
        // §8.4 第 5 步：**先落 STAGING 记录再做文件操作**。顺序反了就会出现
        // 「文件已经被复制/移走，但数据库里没有记录」的孤儿副本（G17 的根因）。
        val staging = TrashEntry(
            locationId = item.locationId,
            mediaItemId = item.id,
            backend = backend,
            state = TrashState.STAGING,
            originalUri = item.uri,
            originalDisplayName = item.fileName,
            originalSizeBytes = item.sizeBytes,
            updatedAtEpochMillis = now,
            originalRelativePath = item.folderAlias,
            originalMimeType = item.extension,
            originalDurationMillis = item.durationMillis,
            originalWidth = item.width,
            originalHeight = item.height,
            originalModifiedEpochMillis = item.modifiedEpochMillis,
        )
        repository.insert(staging)
        return when (val staged = storage.stage(staging)) {
            is TrashOperationOutcome.Completed -> {
                repository.update(staged.entry)
                // R1：`IS_TRASHED` 本身就是移入完成，没有独立的「源删除」一步。
                if (staged.entry.backend == TrashBackend.R1_SYSTEM) {
                    staged
                } else {
                    confirmSourceDelete(staged.entry)
                }
            }
            is TrashOperationOutcome.AuthorizationRequired -> {
                // R1 走 createTrashRequest 时条目还停在 STAGING：授权没兑现就什么都没发生。
                pendingEntries[staged.request.token] = staging
                staged
            }
            is TrashOperationOutcome.Failed -> {
                repository.update(staged.entry)
                staged
            }
            is TrashOperationOutcome.Blocked -> {
                repository.update(staging.copy(state = TrashState.FAILED, lastErrorCode = staged.code))
                staged
            }
            is TrashOperationOutcome.Purged -> staged
            is TrashOperationOutcome.Discarded -> staged
        }
    }

    /** §8.4 第 9–10 步：源删除确认之后才允许写 `trashedAt`/`expiresAt` 并置 `ACTIVE`。 */
    private suspend fun confirmSourceDelete(staged: TrashEntry): TrashOperationOutcome =
        when (val outcome = storage.deleteSource(staged)) {
            is TrashOperationOutcome.Completed -> {
                repository.update(outcome.entry)
                outcome
            }
            is TrashOperationOutcome.AuthorizationRequired -> {
                pendingEntries[outcome.request.token] = staged
                outcome
            }
            is TrashOperationOutcome.Failed -> {
                // 源还在，副本退回 `recovery/`：整体回到「什么都没发生」的可重试状态。
                storage.quarantineCopy(staged)
                val failed = outcome.entry.copy(
                    state = TrashState.FAILED,
                    copyRelativePath = null,
                    updatedAtEpochMillis = now(),
                )
                repository.update(failed)
                TrashOperationOutcome.Failed(failed, outcome.code, outcome.detail)
            }
            is TrashOperationOutcome.Blocked -> outcome
            is TrashOperationOutcome.Purged -> outcome
            is TrashOperationOutcome.Discarded -> outcome
        }

    private suspend fun purgeEntry(entry: TrashEntry): TrashOperationOutcome =
        when (val outcome = storage.purge(entry)) {
            is TrashOperationOutcome.Purged -> {
                // §8.6 规则 7：物理删除成功之后才删元数据。
                catalog.detachLocation(entry.locationId, entry.mediaItemId)
                repository.remove(entry.locationId)
                outcome
            }
            is TrashOperationOutcome.Blocked -> outcome
            is TrashOperationOutcome.AuthorizationRequired -> {
                pendingEntries[outcome.request.token] = entry
                outcome
            }
            is TrashOperationOutcome.Completed -> outcome
            is TrashOperationOutcome.Failed -> {
                // 部分成功：条目留在库里等重试，不要让它看起来像删成功了。
                val pending = outcome.entry.copy(
                    state = TrashState.CLEANUP_PENDING,
                    retryCount = outcome.entry.retryCount + 1,
                    updatedAtEpochMillis = now(),
                )
                repository.update(pending)
                TrashOperationOutcome.Failed(pending, outcome.code, outcome.detail)
            }
            is TrashOperationOutcome.Discarded -> outcome
        }

    /** §8.5 第 8–9 步：先把位置行指向新 URI，再删记录；副本已在存储层删掉。 */
    private suspend fun finishRestore(restored: TrashEntry): TrashOperationOutcome {
        val restoreUri = restored.restoreUri
            ?: return TrashOperationOutcome.Blocked("RESTORE_URI_MISSING")
        catalog.markRestored(
            locationId = restored.locationId,
            restoreUri = restoreUri,
            contentHash = restored.contentHash,
            hashAlgorithmVersion = restored.hashAlgorithmVersion,
            nowEpochMillis = restored.restoredAtEpochMillis ?: now(),
        )
        repository.remove(restored.locationId)
        return TrashOperationOutcome.Completed(restored.copy(lastErrorCode = null, lastErrorDetail = null))
    }

    private suspend fun blockingTask(mediaItemId: seeyuer.yingli.player.core.model.media.MediaItemId): ProcessingProjectType? =
        guard.activeOperations(mediaItemId, PLACEHOLDER_LOCATION)
            .firstOrNull { guard.conflicts(it, ProcessingProjectType.RECYCLE) }

    private fun now(): Long = clock.now().toEpochMilli()

    private companion object {
        const val SOURCE_MISSING = "SOURCE_MISSING"

        /**
         * 互斥判定不使用位置维度：`processing_project_inputs` 里没有它。
         * 用一个**不会与真实 id 相同**的占位值，避免让调用方误以为守卫真的按位置判定。
         */
        val PLACEHOLDER_LOCATION = MediaLocationId("recycle-guard")
    }
}
