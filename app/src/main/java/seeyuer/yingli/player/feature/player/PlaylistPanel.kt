package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.LoadState
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.thumbnail.ThumbnailLoader
import seeyuer.yingli.player.feature.library.MediaListRow
import seeyuer.yingli.player.feature.library.toMediaListItem
import seeyuer.yingli.player.domain.playback.PlaybackQueue

@Composable
internal fun PlaylistPanel(
    queue: PlaybackQueue?,
    items: List<LibraryMedia>,
    pagingItems: LazyPagingItems<PlaylistMediaItem>? = null,
    currentMediaId: String?,
    thumbnailRepository: ThumbnailLoader?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = YingLiTheme.player.canvas, contentColor = YingLiTheme.player.controlPrimary) {
        if (queue == null || queue.mediaIds.isEmpty()) {
            Text("播放队列为空", modifier = Modifier.padding(24.dp), color = YingLiTheme.player.controlSecondary)
        } else {
            val itemsById = items.associateBy { it.id }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                item(key = "playlist-title") {
                    Text(
                        "播放列表 · ${queue.mediaIds.size}",
                        style = MaterialTheme.typography.titleMedium,
                        color = YingLiTheme.player.controlPrimary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                if (pagingItems != null) {
                    items(pagingItems.itemCount, key = { index -> queue.mediaIds[index].value }) { index ->
                        val entry = pagingItems[index]
                        PlaylistEntryRow(entry, currentMediaId, thumbnailRepository, onSelect)
                    }
                } else itemsIndexed(queue.mediaIds, key = { _, id -> id.value }) { index, id ->
                    PlaylistEntryRow(
                        PlaylistMediaItem(index, id, itemsById[id]),
                        currentMediaId,
                        thumbnailRepository,
                        onSelect,
                    )
                }
                pagingItems?.let { loaded ->
                    when {
                        loaded.loadState.refresh is LoadState.Loading && loaded.itemCount == 0 -> item(key = "playlist-refresh-loading") {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = YingLiTheme.player.controlPrimary)
                            }
                        }
                        loaded.loadState.append is LoadState.Loading -> item(key = "playlist-append-loading") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = YingLiTheme.player.controlSecondary)
                            }
                        }
                        loaded.loadState.refresh is LoadState.Error || loaded.loadState.append is LoadState.Error -> item(key = "playlist-load-error") {
                            Text(
                                "媒体信息加载失败",
                                color = YingLiTheme.player.controlSecondary,
                                modifier = Modifier.fillMaxWidth().padding(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistEntryRow(
    entry: PlaylistMediaItem?,
    currentMediaId: String?,
    thumbnailRepository: ThumbnailLoader?,
    onSelect: (Int) -> Unit,
) {
    val index = entry?.index ?: return
    val item = entry.media
    if (item == null) {
        Text(
            "媒体信息不可用",
            color = YingLiTheme.player.controlSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
        )
    } else {
        MediaListRow(
            item = item.toMediaListItem(),
            selected = entry.mediaId.value == currentMediaId,
            onClick = { onSelect(index) },
            thumbnailRepository = thumbnailRepository,
            secondaryText = formatResolution(item.width, item.height),
            showMetadataCapsules = false,
            dark = true,
            thumbnailWidth = 112.dp,
            thumbnailHeight = 70.dp,
            rowHeight = 82.dp,
            thumbnailWidthPixels = 256,
            thumbnailHeightPixels = 160,
        )
    }
}

private fun formatResolution(width: Int?, height: Int?): String =
    if (width != null && height != null && width > 0 && height > 0) "$width × $height" else "未知分辨率"
