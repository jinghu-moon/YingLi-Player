package seeyuer.yingli.player.core.database

data class HomeStatsRow(
    val videoCount: Int,
    val videoBytes: Long,
)

data class HomeMediaRow(
    val mediaId: String,
    val locationId: String,
    val uri: String,
    val title: String,
    val folderAlias: String,
    val sizeBytes: Long,
    val durationMillis: Long?,
    val width: Int?,
    val height: Int?,
    val modifiedEpochMillis: Long,
    val playbackPositionMillis: Long,
)

data class HomeCollectionRow(
    val collectionId: String,
    val name: String,
    val itemCount: Int,
    val updatedAtEpochMillis: Long,
)

data class HomeFolderRow(
    val sourceId: String,
    val name: String,
    val itemCount: Int,
    val sizeBytes: Long,
)
