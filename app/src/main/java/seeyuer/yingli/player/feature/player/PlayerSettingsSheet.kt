package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import seeyuer.yingli.player.R
import androidx.compose.ui.res.stringResource
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.VideoScaleMode

@Composable
internal fun PlayerSettingsSheet(
    state: PlayerUiState,
    onSetSpeed: (PlaybackSpeed) -> Unit,
    onSetScaleMode: (VideoScaleMode) -> Unit,
    onSelectAudioTrack: (String) -> Unit,
    onSelectSubtitleTrack: (String?) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.player_speed), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlaybackSpeed.supportedValues.take(4).forEach { value ->
                val speed = PlaybackSpeed.of(value)
                FilterChip(state.speed == speed, { onSetSpeed(speed) }, { Text("${value}x") })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlaybackSpeed.supportedValues.drop(4).forEach { value ->
                val speed = PlaybackSpeed.of(value)
                FilterChip(state.speed == speed, { onSetSpeed(speed) }, { Text("${value}x") })
            }
        }
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
    }
}
