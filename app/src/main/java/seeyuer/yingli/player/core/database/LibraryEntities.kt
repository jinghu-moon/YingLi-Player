package seeyuer.yingli.player.core.database

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "trash_entries",
    primaryKeys = ["mediaItemId"],
    indices = [Index("locationId"), Index("purgeAtEpochMillis")],
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
