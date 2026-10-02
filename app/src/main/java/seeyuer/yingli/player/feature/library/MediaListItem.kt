package seeyuer.yingli.player.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.core.model.media.ThumbnailPriority
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.thumbnail.ThumbnailLoader

/** UI-facing media fields shared by the library and player queue list. */
data class MediaListItem(
    val id: MediaItemId,
    val locationId: MediaLocationId,
    val uri: MediaUri,
    val fileName: String,
    val durationMillis: Long?,
    val width: Int?,
    val height: Int?,
    val modifiedEpochMillis: Long,
    val sizeBytes: Long,
)

fun LibraryMedia.toMediaListItem(): MediaListItem = MediaListItem(
    id = id,
    locationId = locationId,
    uri = uri,
    fileName = fileName,
    durationMillis = durationMillis,
    width = width,
    height = height,
    modifiedEpochMillis = modifiedEpochMillis,
    sizeBytes = sizeBytes,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MediaListRow(
    item: MediaListItem,
    selected: Boolean,
    onClick: () -> Unit,
    thumbnailRepository: ThumbnailLoader?,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    secondaryText: String? = null,
    showMetadataCapsules: Boolean = true,
    dark: Boolean = false,
    thumbnailWidth: androidx.compose.ui.unit.Dp = LIST_MEDIA_THUMBNAIL_WIDTH,
    thumbnailHeight: androidx.compose.ui.unit.Dp = LIST_MEDIA_THUMBNAIL_HEIGHT,
    rowHeight: androidx.compose.ui.unit.Dp = LIST_MEDIA_ROW_HEIGHT,
    thumbnailWidthPixels: Int = 320,
    thumbnailHeightPixels: Int = 200,
) {
    val primaryText = if (dark) YingLiTheme.player.controlPrimary else YingLiTheme.colors.textPrimary
    val secondaryColor = if (dark) YingLiTheme.player.controlSecondary else YingLiTheme.colors.textSecondary
    val rowColor = when {
        selected && dark -> YingLiTheme.player.controlPrimary.copy(alpha = 0.14f)
        selected -> YingLiTheme.colors.selectionStructural
        dark -> YingLiTheme.player.canvas
        else -> Color.Transparent
    }
    val thumbnail = ThumbnailRequestFactory.forMedia(item, thumbnailWidthPixels, thumbnailHeightPixels)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight)
            .background(rowColor)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics {
                role = Role.Button
                this.selected = selected
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(thumbnailWidth)
                .height(thumbnailHeight)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            YingLiThumbnail(thumbnail, thumbnailRepository)
            Surface(
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(4.dp),
                color = YingLiTheme.player.edgeScrim,
                shape = RoundedCornerShape(3.dp),
            ) {
                Text(
                    formatMediaListDuration(item.durationMillis),
                    color = YingLiTheme.player.controlPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .then(if (showMetadataCapsules) Modifier.fillMaxHeight() else Modifier.wrapContentHeight())
                .padding(horizontal = 12.dp),
        ) {
            Text(
                item.fileName.ifBlank { "未知文件" },
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = primaryText,
                style = MaterialTheme.typography.titleSmall.copy(lineHeight = 18.sp),
            )
            secondaryText?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    color = secondaryColor,
                    style = MaterialTheme.typography.labelSmall.copy(lineHeight = 14.sp),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (showMetadataCapsules) {
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MediaMetadataCapsule(formatMediaListFileSize(item.sizeBytes), dark)
                    MediaMetadataCapsule(formatMediaListResolution(item.width, item.height), dark)
                }
            }
        }
    }
}

@Composable
private fun MediaMetadataCapsule(text: String, dark: Boolean) {
    Surface(
        color = if (dark) YingLiTheme.player.edgeScrim else YingLiTheme.colors.surfaceMuted,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text,
            color = if (dark) YingLiTheme.player.controlSecondary else YingLiTheme.colors.textSecondary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
        )
    }
}

private object ThumbnailRequestFactory {
    fun forMedia(item: MediaListItem, widthPixels: Int, heightPixels: Int) = seeyuer.yingli.player.core.model.media.ThumbnailRequest(
        mediaItemId = item.id,
        locationId = item.locationId,
        uri = item.uri,
        widthPixels = widthPixels,
        heightPixels = heightPixels,
        priority = ThumbnailPriority.VISIBLE,
        modifiedEpochMillis = item.modifiedEpochMillis,
        sizeBytes = item.sizeBytes,
    )
}

private fun formatMediaListDuration(value: Long?): String = value?.let {
    val totalSeconds = (it / 1000L).coerceAtLeast(0L)
    "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
} ?: "--:--"

private fun formatMediaListFileSize(bytes: Long): String {
    if (bytes <= 0L) return "未知大小"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "${bytes} ${units[index]}" else "%.1f ${units[index]}".format(value)
}

private fun formatMediaListResolution(width: Int?, height: Int?): String =
    if (width != null && height != null && width > 0 && height > 0) "$width × $height" else "未知分辨率"

private val LIST_MEDIA_THUMBNAIL_WIDTH = 140.dp
private val LIST_MEDIA_THUMBNAIL_HEIGHT = 90.dp
private val LIST_MEDIA_ROW_HEIGHT = 106.dp
