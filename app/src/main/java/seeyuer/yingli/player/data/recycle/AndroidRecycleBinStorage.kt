package seeyuer.yingli.player.data.recycle

import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.common.IdGenerator
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.duplicates.DUPLICATE_HASH_ALGORITHM_VERSION
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.recycle.RecycleAction
import seeyuer.yingli.player.domain.recycle.RecycleAuthorizationRequest
import seeyuer.yingli.player.domain.recycle.RecycleBinStorage
import seeyuer.yingli.player.domain.recycle.TrashBackend
import seeyuer.yingli.player.domain.recycle.TrashEntry
import seeyuer.yingli.player.domain.recycle.TrashOperationOutcome
import seeyuer.yingli.player.domain.recycle.TrashRetentionPolicy
import seeyuer.yingli.player.domain.recycle.TrashState

/**
 * 把系统授权对话框暴露给 UI 的 Android 侧接口。
 *
 * 它单独存在（而不是塞进 `RecycleBinStorage`）是为了守住领域层不导入 `android.*` 的边界：
 * `RecycleBinStorage` 只回 `RecycleAuthorizationRequest(token, action, uris, reason)`，
 * 真正要 launch 的 `PendingIntent` 由这里按 token 取。
 */
interface RecycleAuthorizationLauncher {
    /**
     * 取得 token 对应的系统授权 `PendingIntent`。
     *
     * `null` 表示 token 已失效（进程被重启过，系统的一次性授权无法恢复）——
     * 此时 UI 必须重新发起一次移入/删除，而不是假装授权可用。
     */
    fun authorizationIntent(token: String): PendingIntent?
}

/**
 * R1（系统回收站）+ R2（应用私有副本）两个后端。
 *
 * 目录布局（§8.2）：`filesDir/recycle-bin/{staging,items,recovery}`。
 * **文件名一律是内部生成的 UUID**，原始文件名只用于显示 —— 这样路径冲突与注入都不存在，
 * 而且 `items/<uuid>` 与 `trash_entries.copyRelativePath` 是同一份事实，不会被用户改名影响。
 *
 * 关键顺序约束（§8.4/§8.6）：**复制中断或校验失败绝不删源**；
 * **物理删除成功后才允许调用方删数据库记录**（因此删文件成功时回 `Purged`）。
 */
class AndroidRecycleBinStorage(
    context: Context,
    private val dispatchers: AppDispatchers,
    private val idGenerator: IdGenerator,
    private val clock: AppClock,
    private val retentionDays: suspend () -> Int = { TrashRetentionPolicy().retentionDays },
) : RecycleBinStorage, RecycleAuthorizationLauncher {
    private val applicationContext = context.applicationContext
    private val resolver: ContentResolver = applicationContext.contentResolver
    private val root = File(applicationContext.filesDir, RECYCLE_DIRECTORY)
    private val stagingDirectory = File(root, STAGING_DIRECTORY)
    private val itemsDirectory = File(root, ITEMS_DIRECTORY)
    private val recoveryDirectory = File(root, RECOVERY_DIRECTORY)

    /** token → 待兑现的授权。一次性：兑现或丢弃后立刻移除。 */
    private val authorizations = HashMap<String, PendingAuthorization>()

    override suspend fun systemTrashSupported(item: LibraryMedia): Boolean =
        withContext(dispatchers.io) { item.uri.value.isMediaStoreUri() }

    override suspend fun stage(entry: TrashEntry): TrashOperationOutcome = withContext(dispatchers.io) {
        when (entry.backend) {
            TrashBackend.R1_SYSTEM -> stageWithSystemTrash(entry)
            TrashBackend.R2_APP_COPY -> stageWithAppCopy(entry)
        }
    }

    override suspend fun deleteSource(entry: TrashEntry): TrashOperationOutcome = withContext(dispatchers.io) {
        when (entry.backend) {
            // 系统已经把文件接管了：没有「源」可删，直接确认。
            TrashBackend.R1_SYSTEM -> entry.activate(systemExpiresAt = readSystemExpiresAt(entry.originalUri))
            TrashBackend.R2_APP_COPY -> deleteSourceOfAppCopy(entry)
        }
    }

    override suspend fun restore(entry: TrashEntry): TrashOperationOutcome = withContext(dispatchers.io) {
        when (entry.backend) {
            TrashBackend.R1_SYSTEM -> restoreSystemTrashed(entry)
            TrashBackend.R2_APP_COPY -> restoreFromAppCopy(entry)
        }
    }

    override suspend fun purge(entry: TrashEntry): TrashOperationOutcome = withContext(dispatchers.io) {
        when (entry.backend) {
            TrashBackend.R1_SYSTEM -> purgeSystemTrashed(entry)
            TrashBackend.R2_APP_COPY -> purgeAppCopy(entry)
        }
    }

    override suspend fun resolveAuthorization(
        token: String,
        entry: TrashEntry,
        granted: Boolean,
    ): TrashOperationOutcome? = withContext(dispatchers.io) {
        val pending = synchronized(authorizations) { authorizations.remove(token) } ?: return@withContext null
        if (!granted) return@withContext denied(entry, pending)
        when (pending.action) {
            RecycleAction.MOVE -> entry.activate(systemExpiresAt = readSystemExpiresAt(entry.originalUri))
            // 系统对话框批准后由 MediaStore 自己完成删除：这里不再调用 delete，否则会二次报错。
            RecycleAction.PURGE, RecycleAction.CLEANUP -> TrashOperationOutcome.Purged(entry.locationId)
            RecycleAction.RESTORE -> TrashOperationOutcome.Completed(
                entry.copy(
                    restoreUri = entry.originalUri,
                    restoredAtEpochMillis = now(),
                    systemExpiresAtEpochMillis = null,
                    lastErrorCode = null,
                    lastErrorDetail = null,
                    updatedAtEpochMillis = now(),
                ),
            )
        }
    }

    override fun discardAuthorization(token: String) {
        synchronized(authorizations) { authorizations.remove(token) }
    }

    override fun authorizationIntent(token: String): PendingIntent? =
        synchronized(authorizations) { authorizations[token]?.intent }

    /**
     * 还能为副本分配多少字节。
     *
     * 用 `StorageManager.getAllocatableBytes` 而不是 `File.usableSpace`：后者不考虑
     * 「系统可以清掉的缓存数据」，会把一个其实放得下的文件判成空间不足（lint 的
     * `UsableSpace` 检查也是这个理由）。拿不到 UUID 时退回 `usableSpace`，宁可保守。
     */
    override suspend fun availableBytes(): Long = withContext(dispatchers.io) {
        val manager = applicationContext.getSystemService(StorageManager::class.java)
        val allocatable = runCatching {
            val uuid = manager.getUuidForPath(root)
            manager.getAllocatableBytes(uuid)
        }.getOrNull()
        (allocatable ?: root.usableSpace).coerceAtLeast(0L)
    }

    /**
     * 只做一次「打开并读 1 字节」，不做解码。
     *
     * 对账需要回答的是「这个地址现在还能不能用」，而不是「这个文件还是不是合法视频」：
     * 后者会为每个条目付一次解码器初始化，而且在磁盘正在被卸载时会把「暂时不可达」
     * 误当成「文件坏了」。真正的完整性判断在移入/恢复流程里用长度 + SHA-256 做。
     */
    override suspend fun isReadable(uri: MediaUri): Boolean = withContext(dispatchers.io) {
        runCatching {
            resolver.openInputStream(Uri.parse(uri.value))?.use { it.read() } != null
        }.getOrDefault(false)
    }

    override suspend fun quarantineCopy(entry: TrashEntry): Boolean = withContext(dispatchers.io) {
        val relative = entry.copyRelativePath ?: return@withContext false
        val file = resolveWithin(itemsDirectory, relative.substringAfterLast('/')) ?: return@withContext false
        if (!file.isFile) return@withContext false
        recoveryDirectory.mkdirs()
        file.renameTo(File(recoveryDirectory, file.name))
    }

    override suspend fun quarantineOrphanCopy(name: String): Boolean = withContext(dispatchers.io) {
        val file = resolveWithin(itemsDirectory, name) ?: return@withContext false
        if (!file.isFile) return@withContext false
        recoveryDirectory.mkdirs()
        file.renameTo(File(recoveryDirectory, file.name))
    }

    override suspend fun copyFileNames(): Set<String> = withContext(dispatchers.io) {
        itemsDirectory.listFiles().orEmpty().filter { it.isFile }.mapTo(mutableSetOf()) { it.name }
    }

    override suspend fun removeCopyFile(name: String): Boolean = withContext(dispatchers.io) {
        removeFile(itemsDirectory, name)
    }

    override suspend fun removeStagingFile(name: String): Boolean = withContext(dispatchers.io) {
        removeFile(stagingDirectory, name)
    }

    // ---- R1：系统回收站 ----------------------------------------------------

    /**
     * 直接置 `IS_TRASHED` 并读回 `DATE_EXPIRES`。
     *
     * **刻意不先查 `owner_package_name` 判断所有权**：阶段 0 实测该列在 API 36 上不可靠
     * （§20.2.6）。所有权这件事本身就是「写一次试试看」——自己创建的文件会成功，
     * 别人的文件会抛 `RecoverableSecurityException`，那条路径正是我们要走授权分支的信号。
     */
    private suspend fun stageWithSystemTrash(entry: TrashEntry): TrashOperationOutcome {
        val uri = Uri.parse(entry.originalUri.value)
        return try {
            val updated = resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) },
                null,
                null,
            )
            if (updated <= 0) {
                TrashOperationOutcome.Failed(entry.failed("SOURCE_TRASH_FAILED"), "SOURCE_TRASH_FAILED")
            } else {
                entry.activate(systemExpiresAt = readSystemExpiresAt(entry.originalUri))
            }
        } catch (recoverable: RecoverableSecurityException) {
            authorization(
                entry = entry,
                action = RecycleAction.MOVE,
                uris = listOf(uri),
                intent = MediaStore.createTrashRequest(resolver, listOf(uri), true),
                reason = "MOVE_FOREIGN_MEDIA",
            )
        } catch (denied: SecurityException) {
            TrashOperationOutcome.Failed(entry.failed("PERMISSION_DENIED"), "PERMISSION_DENIED", denied.message)
        }
    }

    private fun restoreSystemTrashed(entry: TrashEntry): TrashOperationOutcome {
        val uri = Uri.parse(entry.originalUri.value)
        return try {
            val updated = resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) },
                null,
                null,
            )
            if (updated <= 0) {
                TrashOperationOutcome.Failed(entry.failed("SOURCE_RESTORE_FAILED"), "SOURCE_RESTORE_FAILED")
            } else {
                TrashOperationOutcome.Completed(
                    entry.copy(
                        restoreUri = entry.originalUri,
                        restoredAtEpochMillis = now(),
                        systemExpiresAtEpochMillis = null,
                        lastErrorCode = null,
                        lastErrorDetail = null,
                        updatedAtEpochMillis = now(),
                    ),
                )
            }
        } catch (recoverable: RecoverableSecurityException) {
            // 当初是用 createTrashRequest 移入的，逆操作就是同一 API 传 false。
            authorization(
                entry = entry,
                action = RecycleAction.RESTORE,
                uris = listOf(uri),
                intent = MediaStore.createTrashRequest(resolver, listOf(uri), false),
                reason = "RESTORE_FOREIGN_MEDIA",
            )
        } catch (denied: SecurityException) {
            TrashOperationOutcome.Failed(entry.failed("PERMISSION_DENIED"), "PERMISSION_DENIED", denied.message)
        }
    }

    private fun purgeSystemTrashed(entry: TrashEntry): TrashOperationOutcome {
        val uri = Uri.parse(entry.originalUri.value)
        return try {
            resolver.delete(uri, null, null)
            TrashOperationOutcome.Purged(entry.locationId)
        } catch (recoverable: RecoverableSecurityException) {
            authorization(
                entry = entry,
                action = RecycleAction.PURGE,
                uris = listOf(uri),
                intent = MediaStore.createDeleteRequest(resolver, listOf(uri)),
                reason = "PURGE_REQUIRES_CONSENT",
            )
        } catch (denied: SecurityException) {
            TrashOperationOutcome.Failed(entry.failed("PERMISSION_DENIED"), "PERMISSION_DENIED", denied.message)
        }
    }

    // ---- R2：应用私有副本 --------------------------------------------------

    private fun stageWithAppCopy(entry: TrashEntry): TrashOperationOutcome {
        if (!root.isDirectory && !root.mkdirs()) {
            return TrashOperationOutcome.Failed(entry.failed("STORAGE_UNAVAILABLE"), "STORAGE_UNAVAILABLE")
        }
        val itemName = idGenerator.newId()
        val stagingFile = File(stagingDirectory.also { it.mkdirs() }, "$itemName$STAGING_SUFFIX")
        val digest = MessageDigest.getInstance(SHA_256)
        var copiedBytes = 0L
        try {
            val source = resolver.openInputStream(Uri.parse(entry.originalUri.value))
                ?: return TrashOperationOutcome.Failed(entry.failed("SOURCE_UNREADABLE"), "SOURCE_UNREADABLE")
            source.use { input ->
                FileOutputStream(stagingFile).use { sink ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copiedBytes += read
                    }
                }
            }
        } catch (failure: Exception) {
            stagingFile.delete()
            return TrashOperationOutcome.Failed(entry.failed("COPY_FAILED"), "COPY_FAILED", failure.message)
        }
        // 第 7 步：长度必须与入库时的快照一致，否则源在复制期间被改过。
        if (entry.originalSizeBytes > 0 && copiedBytes != entry.originalSizeBytes) {
            stagingFile.delete()
            return TrashOperationOutcome.Failed(
                entry.failed("COPY_SIZE_MISMATCH"),
                "COPY_SIZE_MISMATCH",
                "copied=$copiedBytes expected=${entry.originalSizeBytes}",
            )
        }
        val hash = digest.digest().toHex()
        // 已有哈希时这是**一致性校验**：内容变了就不许继续，源必须原样保留。
        if (entry.contentHash != null && !entry.contentHash.equals(hash, ignoreCase = true)) {
            stagingFile.delete()
            return TrashOperationOutcome.Failed(
                entry.failed("CONTENT_CHANGED"),
                "CONTENT_CHANGED",
                "expected=${entry.contentHash} actual=$hash",
            )
        }
        // 第 8 步：同目录内 rename 是原子的；只有到这里副本才被承认。
        val itemFile = File(itemsDirectory.also { it.mkdirs() }, itemName)
        if (!stagingFile.renameTo(itemFile)) {
            stagingFile.delete()
            return TrashOperationOutcome.Failed(entry.failed("COPY_COMMIT_FAILED"), "COPY_COMMIT_FAILED")
        }
        return TrashOperationOutcome.Completed(
            entry.copy(
                state = TrashState.WAITING_SOURCE_DELETE_AUTH,
                contentHash = hash,
                hashAlgorithmVersion = DUPLICATE_HASH_ALGORITHM_VERSION,
                copyRelativePath = "$ITEMS_DIRECTORY/$itemName",
                copySizeBytes = copiedBytes,
                copyVerifiedAtEpochMillis = now(),
                lastErrorCode = null,
                lastErrorDetail = null,
                updatedAtEpochMillis = now(),
            ),
        )
    }

    private suspend fun deleteSourceOfAppCopy(entry: TrashEntry): TrashOperationOutcome {
        val uri = Uri.parse(entry.originalUri.value)
        return when (uri.scheme?.lowercase()) {
            "file" -> {
                val file = uri.path?.let(::File)
                val removed = file != null && (file.delete() || !file.exists())
                if (removed) {
                    entry.activate()
                } else {
                    TrashOperationOutcome.Failed(entry.failed("SOURCE_DELETE_FAILED"), "SOURCE_DELETE_FAILED")
                }
            }
            else -> try {
                resolver.delete(uri, null, null)
                entry.activate()
            } catch (recoverable: RecoverableSecurityException) {
                // 副本已经验证通过，此刻只差把源交出去；授权被拒就必须回滚副本（§8.4 规则 2）。
                authorization(
                    entry = entry,
                    action = RecycleAction.MOVE,
                    uris = listOf(uri),
                    intent = MediaStore.createDeleteRequest(resolver, listOf(uri)),
                    reason = "SOURCE_DELETE_REQUIRES_CONSENT",
                )
            } catch (denied: SecurityException) {
                TrashOperationOutcome.Failed(entry.failed("PERMISSION_DENIED"), "PERMISSION_DENIED", denied.message)
            }
        }
    }

    private fun restoreFromAppCopy(entry: TrashEntry): TrashOperationOutcome {
        val copy = entry.copyRelativePath
            ?.let { resolveWithin(itemsDirectory, it.substringAfterLast('/')) }
        if (copy == null) return TrashOperationOutcome.Blocked("NO_COPY")
        if (!copy.isFile) return TrashOperationOutcome.Failed(entry.failed("COPY_MISSING"), "COPY_MISSING")
        // `file://` 来源没有 MediaStore 行可插。走 MediaStore 分支等于把它塞进 `Movies/` 并换掉
        // URI —— 那是**静默搬家**，不是恢复（§8.5「恢复到最后已知位置」）。
        val original = Uri.parse(entry.originalUri.value)
        if (original.scheme?.lowercase() == FILE_SCHEME) return restoreToOriginalPath(entry, copy, original)
        return restoreViaMediaStore(entry, copy)
    }

    private fun restoreViaMediaStore(entry: TrashEntry, copy: File): TrashOperationOutcome {
        val displayName = uniqueDisplayName(entry)
        val relativePath = entry.originalRelativePath?.takeIf { it.isNotBlank() } ?: RESTORE_RELATIVE_PATH
        val mimeType = entry.originalMimeType ?: displayName.guessMimeType()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = try {
            checkNotNull(resolver.insert(mimeType.mediaCollection(), values))
        } catch (failure: Exception) {
            return TrashOperationOutcome.Failed(
                entry.failed("RESTORE_INSERT_FAILED"),
                "RESTORE_INSERT_FAILED",
                failure.message,
            )
        }
        val digest = MessageDigest.getInstance(SHA_256)
        var writtenBytes = 0L
        try {
            val sink = checkNotNull(resolver.openOutputStream(target, "w")) { "no output stream" }
            sink.use { output ->
                copy.inputStream().use { input ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        writtenBytes += read
                    }
                }
            }
        } catch (failure: Exception) {
            // 第 6 步失败：**不得删副本**（副本是此刻唯一可靠的来源），只清掉半成品。
            resolver.delete(target, null, null)
            return TrashOperationOutcome.Failed(
                entry.failed("RESTORE_WRITE_FAILED"),
                "RESTORE_WRITE_FAILED",
                failure.message,
            )
        }
        val hash = digest.digest().toHex()
        val expectedSize = entry.copySizeBytes ?: entry.originalSizeBytes
        if (expectedSize > 0 && writtenBytes != expectedSize) {
            resolver.delete(target, null, null)
            return TrashOperationOutcome.Failed(
                entry.failed("RESTORE_SIZE_MISMATCH"),
                "RESTORE_SIZE_MISMATCH",
                "written=$writtenBytes expected=$expectedSize",
            )
        }
        if (entry.contentHash != null && !entry.contentHash.equals(hash, ignoreCase = true)) {
            resolver.delete(target, null, null)
            return TrashOperationOutcome.Failed(
                entry.failed("RESTORE_HASH_MISMATCH"),
                "RESTORE_HASH_MISMATCH",
                "expected=${entry.contentHash} actual=$hash",
            )
        }
        // 第 7 步：校验通过之后才发布（与 AndroidProcessingArtifactStore 同一套 IS_PENDING 契约）。
        resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        // 第 9 步：恢复已经落地，副本才可以删。
        copy.delete()
        return TrashOperationOutcome.Completed(
            entry.copy(
                restoreUri = MediaUri(target.toString()),
                restoredAtEpochMillis = now(),
                lastErrorCode = null,
                lastErrorDetail = null,
                updatedAtEpochMillis = now(),
            ),
        )
    }

    /**
     * `file://` 来源的恢复：写回**原目录**，不经过 MediaStore。
     *
     * 与 MediaStore 分支同样的顺序约束：写完 → 长度/哈希校验 → 校验通过才删副本。
     * 同名不覆盖（§8.5 规则 2）：目标已存在就自动改名（非破坏性默认）。
     */
    private fun restoreToOriginalPath(entry: TrashEntry, copy: File, original: Uri): TrashOperationOutcome {
        val target = original.path?.let(::File)
            ?: return TrashOperationOutcome.Failed(entry.failed("RESTORE_TARGET_INVALID"), "RESTORE_TARGET_INVALID")
        val directory = target.parentFile
            ?: return TrashOperationOutcome.Failed(entry.failed("RESTORE_TARGET_INVALID"), "RESTORE_TARGET_INVALID")
        if ((!directory.exists() && !directory.mkdirs()) || !directory.canWrite()) {
            return TrashOperationOutcome.Blocked("RESTORE_LOCATION_UNAVAILABLE", directory.absolutePath)
        }
        val destination = uniqueFileTarget(directory, target.name)
        val digest = MessageDigest.getInstance(SHA_256)
        var writtenBytes = 0L
        try {
            copy.inputStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        writtenBytes += read
                    }
                }
            }
        } catch (failure: Exception) {
            // 与 MediaStore 分支同一条规则：写失败只清半成品，**绝不删副本**。
            destination.delete()
            return TrashOperationOutcome.Failed(
                entry.failed("RESTORE_WRITE_FAILED"),
                "RESTORE_WRITE_FAILED",
                failure.message,
            )
        }
        val hash = digest.digest().toHex()
        val expectedSize = entry.copySizeBytes ?: entry.originalSizeBytes
        if (expectedSize > 0 && writtenBytes != expectedSize) {
            destination.delete()
            return TrashOperationOutcome.Failed(
                entry.failed("RESTORE_SIZE_MISMATCH"),
                "RESTORE_SIZE_MISMATCH",
                "written=$writtenBytes expected=$expectedSize",
            )
        }
        if (entry.contentHash != null && !entry.contentHash.equals(hash, ignoreCase = true)) {
            destination.delete()
            return TrashOperationOutcome.Failed(
                entry.failed("RESTORE_HASH_MISMATCH"),
                "RESTORE_HASH_MISMATCH",
                "expected=${entry.contentHash} actual=$hash",
            )
        }
        copy.delete()
        return TrashOperationOutcome.Completed(
            entry.copy(
                restoreUri = MediaUri(Uri.fromFile(destination).toString()),
                restoredAtEpochMillis = now(),
                lastErrorCode = null,
                lastErrorDetail = null,
                updatedAtEpochMillis = now(),
            ),
        )
    }

    private fun uniqueFileTarget(directory: File, name: String): File {
        val direct = File(directory, name)
        if (!direct.exists()) return direct
        val base = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "")
        for (index in 1..MAX_NAME_ATTEMPTS) {
            val candidate = if (extension.isBlank()) "$base ($index)" else "$base ($index).$extension"
            val file = File(directory, candidate)
            if (!file.exists()) return file
        }
        val suffix = if (extension.isBlank()) "" else ".$extension"
        return File(directory, "$base-${idGenerator.newId().take(8)}$suffix")
    }

    private fun purgeAppCopy(entry: TrashEntry): TrashOperationOutcome {
        val relative = entry.copyRelativePath
            ?: return TrashOperationOutcome.Blocked("UNMANAGED_COPY", "条目没有副本路径")
        // 旧 R3 条目与任何非 items/ 路径都不由本应用管理：拒绝而不是猜。
        if (!relative.startsWith("$ITEMS_DIRECTORY/")) {
            return TrashOperationOutcome.Blocked("UNMANAGED_COPY", "副本不在应用管理目录内：$relative")
        }
        val file = resolveWithin(itemsDirectory, relative.substringAfterLast('/'))
            ?: return TrashOperationOutcome.Blocked("UNMANAGED_COPY", "非法副本路径：$relative")
        if (!file.exists()) {
            // 幂等：文件已经不在了，删除资格已核实，按成功处理（§8.7 规则 3）。
            return TrashOperationOutcome.Purged(entry.locationId)
        }
        val expectedSize = entry.copySizeBytes
        if (expectedSize != null && expectedSize > 0 && file.length() != expectedSize) {
            return TrashOperationOutcome.Blocked(
                "COPY_IDENTITY_MISMATCH",
                "文件大小与条目不一致：${file.length()} != $expectedSize",
            )
        }
        return if (file.delete()) {
            TrashOperationOutcome.Purged(entry.locationId)
        } else {
            TrashOperationOutcome.Failed(entry.failed("COPY_DELETE_FAILED"), "COPY_DELETE_FAILED")
        }
    }

    // ---- 共用件 ------------------------------------------------------------

    /**
     * 记录一次性授权。`intent` 用 `PendingIntent` 而非 `Intent`：只有它带得出
     * `intentSender`，Compose 侧才能用 `IntentSenderRequest` launch 它。
     */
    private fun authorization(
        entry: TrashEntry,
        action: RecycleAction,
        uris: List<Uri>,
        intent: PendingIntent,
        reason: String,
    ): TrashOperationOutcome {
        val token = idGenerator.newId()
        synchronized(authorizations) { authorizations[token] = PendingAuthorization(action, intent) }
        // 条目本身先落一个可诊断的码：用户拒绝之后它会被改写成 AUTHORIZATION_DENIED。
        return TrashOperationOutcome.AuthorizationRequired(
            RecycleAuthorizationRequest(
                token = token,
                action = action,
                uris = uris.map { MediaUri(it.toString()) },
                reason = reason,
            ),
        )
    }

    private fun denied(entry: TrashEntry, pending: PendingAuthorization): TrashOperationOutcome {
        // 授权被拒时源文件保持原样；R2 的副本必须离开 items/（它不再被任何「已移入」的条目引用）。
        if (entry.backend == TrashBackend.R2_APP_COPY && pending.action == RecycleAction.MOVE) {
            val relative = entry.copyRelativePath
            val file = relative?.let { resolveWithin(itemsDirectory, it.substringAfterLast('/')) }
            if (file != null && file.isFile) {
                recoveryDirectory.mkdirs()
                file.renameTo(File(recoveryDirectory, file.name))
            }
        }
        return TrashOperationOutcome.Failed(
            entry.copy(
                state = TrashState.FAILED,
                lastErrorCode = "AUTHORIZATION_DENIED",
                lastErrorDetail = "用户拒绝了系统授权请求（${pending.action.name}）",
                updatedAtEpochMillis = now(),
            ),
            "AUTHORIZATION_DENIED",
            pending.action.name,
        )
    }

    private suspend fun TrashEntry.activate(systemExpiresAt: Long? = null): TrashOperationOutcome.Completed {
        val trashedAt = now()
        return TrashOperationOutcome.Completed(
            copy(
                state = TrashState.ACTIVE,
                trashedAtEpochMillis = trashedAt,
                // 只有 R2 才有应用自己的期限；R1 的期限完全由系统决定（§8.7）。
                expiresAtEpochMillis = if (backend == TrashBackend.R2_APP_COPY) {
                    // 保留天数来自设置（U5）：**每次移入都重新读**，用户在设置里改完立即生效。
                    TrashEntry.expiresAt(trashedAt, TrashRetentionPolicy(retentionDays()).retentionDays)
                } else {
                    null
                },
                systemExpiresAtEpochMillis = systemExpiresAt ?: systemExpiresAtEpochMillis,
                lastErrorCode = null,
                lastErrorDetail = null,
                updatedAtEpochMillis = trashedAt,
            ),
        )
    }

    private fun TrashEntry.failed(code: String): TrashEntry = copy(
        lastErrorCode = code,
        retryCount = retryCount + 1,
        updatedAtEpochMillis = now(),
    )

    /** `DATE_EXPIRES` 是**秒**；读不到就返回 `null`（UI 显示「由系统管理」，而不是编一个 30 天）。 */
    private fun readSystemExpiresAt(originalUri: MediaUri): Long? = runCatching {
        resolver.query(
            Uri.parse(originalUri.value),
            arrayOf(MediaStore.MediaColumns.DATE_EXPIRES),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) * 1_000L else null
        }
    }.getOrNull()

    private fun uniqueDisplayName(entry: TrashEntry): String {
        val original = entry.originalDisplayName.takeIf { it.isNotBlank() }
            ?: "restored-${entry.locationId.value.take(8)}${entry.originalMimeType.orEmpty().extensionSuffix()}"
        val relativePath = entry.originalRelativePath?.takeIf { it.isNotBlank() } ?: RESTORE_RELATIVE_PATH
        val existing = runCatching {
            resolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(relativePath),
                null,
            )?.use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.let(::add)
                    }
                }
            }
        }.getOrNull().orEmpty()
        if (original !in existing) return original
        // §8.5 规则 2：不得静默覆盖。自动改名是**非破坏性**的默认选择；
        // 「恢复到应用视频目录 / 取消」这两个显式选项属于 UI（阶段 6），不改这里的行为。
        val base = original.substringBeforeLast('.')
        val extension = original.substringAfterLast('.', "")
        for (index in 1..MAX_NAME_ATTEMPTS) {
            val candidate = if (extension.isBlank()) "$base ($index)" else "$base ($index).$extension"
            if (candidate !in existing) return candidate
        }
        return if (extension.isBlank()) {
            "$base (${entry.locationId.value.take(6)})"
        } else {
            "$base (${entry.locationId.value.take(6)}).$extension"
        }
    }

    private fun removeFile(directory: File, name: String): Boolean {
        if (name.isBlank() || name != File(name).name) return false
        val file = File(directory, name)
        return !file.exists() || file.delete()
    }

    /** 目录内解析 + 规范化包含校验：任何越界路径都返回 `null`（不抛，交给调用方给 Blocked）。 */
    private fun resolveWithin(directory: File, name: String): File? {
        if (name.isBlank() || name != File(name).name) return null
        val candidate = File(directory, name).canonicalFile
        val directoryPath = directory.canonicalFile.toPath()
        return if (candidate.toPath().startsWith(directoryPath)) candidate else null
    }

    private fun String.guessMimeType(): String = when (substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        else -> "video/mp4"
    }

    /** 新建媒体行要落进对应集合，否则会出现「视频在音频库里」这种元数据错位。 */
    private fun String.mediaCollection(): Uri = when {
        startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Files.getContentUri("external")
    }

    private fun String.extensionSuffix(): String = when {
        contains("matroska") -> ".mkv"
        contains("webm") -> ".webm"
        contains("audio") -> ".m4a"
        else -> ".mp4"
    }

    private fun String.isMediaStoreUri(): Boolean =
        startsWith("content://media/") || startsWith("content://${MediaStore.AUTHORITY}/")

    private fun now(): Long = clock.now().toEpochMilli()

    private data class PendingAuthorization(val action: RecycleAction, val intent: PendingIntent)

    private companion object {
        const val RECYCLE_DIRECTORY = "recycle-bin"
        const val STAGING_DIRECTORY = "staging"
        const val ITEMS_DIRECTORY = "items"
        const val RECOVERY_DIRECTORY = "recovery"
        const val STAGING_SUFFIX = ".partial"
        const val FILE_SCHEME = "file"
        const val SHA_256 = "SHA-256"
        const val COPY_BUFFER_BYTES = 256 * 1024
        const val MAX_NAME_ATTEMPTS = 99

        /** 原目录已经不存在时的落点（用户仍能从设备上找到文件）。 */
        const val RESTORE_RELATIVE_PATH = "Movies/YingLi-Restore/"
    }
}

private fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(digits[value ushr 4]).append(digits[value and 0x0F])
    }
    return out.toString()
}
