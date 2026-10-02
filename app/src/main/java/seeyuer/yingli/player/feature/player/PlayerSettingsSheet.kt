package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import seeyuer.yingli.player.R
import androidx.compose.ui.res.stringResource
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.icon.imageVector
import seeyuer.yingli.player.domain.playback.VideoScaleMode
import seeyuer.yingli.player.domain.playback.VideoRotation
import seeyuer.yingli.player.domain.playback.PlaybackOrder
import seeyuer.yingli.player.domain.playback.PlayerControlLayout
import seeyuer.yingli.player.domain.playback.PlayerControlId
import seeyuer.yingli.player.domain.playback.PlayerControlSurface
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
internal fun PlayerSettingsSheet(
    state: PlayerUiState,
    onSetScaleMode: (VideoScaleMode) -> Unit,
    onSelectAudioTrack: (String) -> Unit,
    onSelectSubtitleTrack: (String?) -> Unit,
    onSetOrder: (PlaybackOrder) -> Unit = {},
    onSetRotation: (VideoRotation) -> Unit = {},
    onOpenVideoInfo: () -> Unit = {},
    onScreenshot: () -> Unit = {},
    onOpenAbTool: () -> Unit = {},
    onSetLayout: (PlayerControlLayout) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.player_scale_mode), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoScaleMode.entries.forEach { mode ->
                FilterChip(state.scaleMode == mode, { onSetScaleMode(mode) }, { Text(mode.name.lowercase()) })
            }
        }
        if (state.audioTracks.isNotEmpty()) {
            Text(stringResource(R.string.player_audio_track), style = MaterialTheme.typography.titleMedium)
            state.audioTracks.forEach { track ->
                FilterChip(track.selected, { onSelectAudioTrack(track.id) }, { Text(track.label) })
            }
        }
        Text(stringResource(R.string.player_subtitle_track), style = MaterialTheme.typography.titleMedium)
        FilterChip(
            selected = state.subtitleTracks.none { it.selected },
            onClick = { onSelectSubtitleTrack(null) },
            label = { Text(stringResource(R.string.player_subtitles_off)) },
        )
        state.subtitleTracks.forEach { track ->
            FilterChip(track.selected, { onSelectSubtitleTrack(track.id) }, { Text(track.label) })
        }
        Text(stringResource(R.string.player_order), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlaybackOrder.entries.forEach { order ->
                FilterChip(
                    selected = state.playbackOrder == order,
                    onClick = { onSetOrder(order) },
                    label = { Text(stringResource(playbackOrderLabelRes(order))) },
                )
            }
        }
        Text(stringResource(R.string.player_rotation), style = MaterialTheme.typography.titleMedium)
        // 横屏设置面板只有 340dp 宽，四个角度标签排一行会溢出，按速度分组的先例拆成两行。
        VideoRotation.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { rotation ->
                    FilterChip(
                        selected = state.rotation == rotation,
                        onClick = { onSetRotation(rotation) },
                        label = { Text(stringResource(videoRotationLabelRes(rotation))) },
                    )
                }
            }
        }
        Text("工具", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = false,
                onClick = onScreenshot,
                label = { Text("截图") },
                leadingIcon = {
                    androidx.compose.material3.Icon(
                        YingLiIcon.SCREENSHOT.imageVector,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
            FilterChip(
                selected = state.abLoop.pointA != null,
                onClick = onOpenAbTool,
                label = { Text("A-B 循环") },
            )
        }
        androidx.compose.material3.TextButton(onClick = onOpenVideoInfo) { Text("视频信息") }
        Text("控件布局", style = MaterialTheme.typography.titleMedium)
        PlayerControlLayoutEditor(state.controlLayout, onSetLayout)
    }
}

@Composable
private fun PlayerControlLayoutEditor(layout: PlayerControlLayout, onChange: (PlayerControlLayout) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PlayerControlSurface.entries.forEach { surface ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(surfaceLabel(surface), style = MaterialTheme.typography.labelLarge)
                Text("${layout.controls(surface).size}/${surface.capacity}", style = MaterialTheme.typography.labelSmall)
            }
            val listState = rememberLazyListState()
            val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
                onChange(layout.move(surface, from.index, to.index))
            }
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(layout.controls(surface), key = { _, id -> id.name }) { _, id ->
                    ReorderableItem(reorderableState, key = id.name) { isDragging ->
                        ControlLayoutChip(
                            id = id,
                            isDragging = isDragging,
                            onRemove = { onChange(layout.remove(surface, id)) },
                        )
                    }
                }
            }
        }
        Text("添加到槽位", style = MaterialTheme.typography.labelLarge)
        PlayerControlSurface.entries.forEach { surface ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(surfaceLabel(surface), modifier = Modifier.widthIn(min = 96.dp))
                PlayerControlId.entries.filter { layout.canAdd(surface, it) || it in layout.controls(surface) }.forEach { id ->
                    FilterChip(
                        selected = false,
                        onClick = { onChange(layout.add(surface, id)) },
                        enabled = layout.canAdd(surface, id),
                        label = { Text("+${controlLabel(id)}") },
                    )
                }
            }
        }
        androidx.compose.material3.TextButton(onClick = { onChange(PlayerControlLayout()) }) { Text("恢复推荐布局") }
    }
}

@Composable
private fun ReorderableCollectionItemScope.ControlLayoutChip(
    id: PlayerControlId,
    isDragging: Boolean,
    onRemove: () -> Unit,
) {
    FilterChip(
        selected = true,
        onClick = onRemove,
        label = { Text(controlLabel(id), maxLines = 1) },
        modifier = Modifier.widthIn(min = 88.dp).then(if (isDragging) Modifier else Modifier),
        trailingIcon = {
            androidx.compose.material3.IconButton(
                onClick = onRemove,
                modifier = Modifier.size(48.dp).longPressDraggableHandle(),
            ) {
                androidx.compose.material3.Icon(
                    YingLiIcon.DRAG_HANDLE.imageVector,
                    contentDescription = "拖动${controlLabel(id)}",
                )
            }
        },
    )
}

private fun surfaceLabel(surface: PlayerControlSurface) = when (surface) {
    PlayerControlSurface.LANDSCAPE_TOP_RIGHT -> "横屏右上"
    PlayerControlSurface.LANDSCAPE_BOTTOM_LEFT -> "横屏左下"
    PlayerControlSurface.LANDSCAPE_BOTTOM_RIGHT -> "横屏右下"
    PlayerControlSurface.PORTRAIT_BOTTOM -> "竖屏底部"
}

private fun controlLabel(id: PlayerControlId) = id.name.lowercase().replace('_', ' ')
