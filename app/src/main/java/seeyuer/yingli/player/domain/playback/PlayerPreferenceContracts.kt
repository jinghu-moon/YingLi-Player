package seeyuer.yingli.player.domain.playback

import kotlinx.coroutines.flow.Flow

data class PlayerPreferences(
    val miniPlayerEnabled: Boolean = true,
    val autoPictureInPicture: Boolean = false,
    val longPressSpeed: PlaybackSpeed = PlaybackSpeed.of(2f),
)

interface PlayerPreferenceRepository {
    val playerPreferences: Flow<PlayerPreferences>
    suspend fun setMiniPlayerEnabled(enabled: Boolean)
    suspend fun setAutoPictureInPicture(enabled: Boolean)
}
