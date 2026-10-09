package seeyuer.yingli.player.data.room

import androidx.room.Entity
import androidx.room.Index

/**
 * 回收站条目。
 *
 * **主键是 `locationId` 而不是 `mediaItemId`**（设计稿 §14.4 第 6 项、§8.2）：
 * 一个 `media_items` 行是内容等价类，它可以有多个位置（`media_item_locations`），
 * 回收的是**具体位置上的那份字节**，而不是整个等价类。
 * 旧模型以 `mediaItemId` 为主键，导致「同一内容的两个位置」只能同时进回收站。
 * `mediaItemId` 保留为普通列：UI 要展示归属条目，恢复流程要按条目回查。
 */
@Entity(
    tableName = "trash_entries",
    primaryKeys = ["locationId"],
    indices = [Index("mediaItemId"), Index("purgeAtEpochMillis")],
)
data class TrashEntryEntity(
    val mediaItemId: String,
    val locationId: String,
    val originalUri: String,
    val trashedUri: String?,
    val deletedAtEpochMillis: Long,
    val purgeAtEpochMillis: Long,
    val state: String,
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
