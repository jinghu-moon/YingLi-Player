package seeyuer.yingli.player.data.room

import androidx.room.Entity
import androidx.room.Index

/**
 * 回收站条目（设计稿 §8.2 的完整字段集）。
 *
 * **主键是 `locationId` 而不是 `mediaItemId`**（设计稿 §14.4 第 6 项、§8.2）：
 * 一个 `media_items` 行是内容等价类，它可以有多个位置（`media_item_locations`），
 * 回收的是**具体位置上的那份字节**，而不是整个等价类。
 *
 * **不加外键到 `media_locations`**（§10.3）：位置行可能被扫描标记为 missing 后清理，
 * 而回收站记录必须存活以便恢复；外键会级联删掉唯一的恢复线索。
 *
 * `trashedAtEpochMillis` / `expiresAtEpochMillis` **可空**：只有「源文件删除已确认、副本完整、
 * DB 记录可恢复」时才进入 `ACTIVE` 并开始计时（§8.4 第 10 步）。旧模型只要 rename 成功就写
 * `purgeAt`，是错的（G17），已随 R3 一起删除。
 */
@Entity(
    tableName = "trash_entries",
    primaryKeys = ["locationId"],
    indices = [Index("mediaItemId"), Index("state"), Index("contentHash")],
)
data class TrashEntryEntity(
    val locationId: String,
    val mediaItemId: String,
    val backend: String,
    val state: String,
    val originalUri: String,
    val originalDisplayName: String,
    val originalSizeBytes: Long,
    val updatedAtEpochMillis: Long,
    val sourceId: String? = null,
    // —— 源文件引用快照 ——
    val originalVolumeId: String? = null,
    val originalDocumentId: String? = null,
    val originalRelativePath: String? = null,
    val originalMimeType: String? = null,
    val originalModifiedEpochMillis: Long? = null,
    val originalDurationMillis: Long? = null,
    val originalWidth: Int? = null,
    val originalHeight: Int? = null,
    // —— 内容校验 ——
    val contentHash: String? = null,
    val hashAlgorithmVersion: Int? = null,
    // —— 副本（仅 R2） ——
    val copyRelativePath: String? = null,
    val copySizeBytes: Long? = null,
    val copyVerifiedAtEpochMillis: Long? = null,
    // —— 生命周期 ——
    val trashedAtEpochMillis: Long? = null,
    val expiresAtEpochMillis: Long? = null,
    val systemExpiresAtEpochMillis: Long? = null,
    // —— 恢复 ——
    val restoreUri: String? = null,
    val restoredAtEpochMillis: Long? = null,
    // —— 失败诊断 ——
    val lastErrorCode: String? = null,
    val lastErrorDetail: String? = null,
    val retryCount: Int = 0,
)

data class LibraryMediaRow(
    val id: String,
    val title: String,
    val playbackPositionMillis: Long,
    val completed: Boolean,
    val locationId: String,
    val uri: String,
    val fileName: String,
    val folderAlias: String,
    val sizeBytes: Long,
    val durationMillis: Long?,
    val width: Int?,
    val height: Int?,
    val modifiedEpochMillis: Long,
    val playCount: Int,
)

data class LibraryFolderRow(
    val path: String,
    val name: String,
    val videoCount: Int,
    val sizeBytes: Long,
    val totalDurationMillis: Long,
    val unwatchedCount: Int,
    val newCount: Int,
)
