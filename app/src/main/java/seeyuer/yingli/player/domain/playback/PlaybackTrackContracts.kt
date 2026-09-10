package seeyuer.yingli.player.domain.playback

import kotlinx.coroutines.flow.Flow
import seeyuer.yingli.player.core.model.media.MediaItemId

@JvmInline
value class PlaybackSpeed private constructor(val value: Float) {
    companion object {
        val supportedValues = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
        val Normal = PlaybackSpeed(1f)

        fun of(value: Float): PlaybackSpeed {
            require(value in supportedValues) { "Playback speed must use a supported fixed step." }
            return PlaybackSpeed(value)
        }
    }
}

enum class VideoScaleMode {
    FIT,
    FILL,
    ORIGINAL,
}

data class TrackChoice(
    val id: String,
    val label: String,
    val language: String?,
    val selected: Boolean,
) {
    init {
        require(id.isNotBlank())
        require(label.isNotBlank())
    }
}

data class TrackPreference(
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
    val subtitlesEnabled: Boolean = false,
    val speed: PlaybackSpeed = PlaybackSpeed.Normal,
    val scaleMode: VideoScaleMode = VideoScaleMode.FIT,
)

data class TrackPreferenceSet(
    val global: TrackPreference = TrackPreference(),
    val perMedia: Map<MediaItemId, TrackPreference> = emptyMap(),
) {
    fun resolve(mediaId: MediaItemId): TrackPreference = perMedia[mediaId] ?: global
}

interface TrackPreferenceRepository {
    val trackPreferences: Flow<TrackPreferenceSet>
    suspend fun setGlobal(preference: TrackPreference)
    suspend fun setForMedia(mediaId: MediaItemId, preference: TrackPreference?)
}

interface AdvancedPlaybackController : PlaybackController {
    val audioTracks: Flow<List<TrackChoice>>
    val subtitleTracks: Flow<List<TrackChoice>>
    val speed: Flow<PlaybackSpeed>
    val scaleMode: Flow<VideoScaleMode>
    fun seekBy(offsetMillis: Long): PlaybackCommandResult
    fun setSpeed(speed: PlaybackSpeed): PlaybackCommandResult
    fun selectAudioTrack(id: String): PlaybackCommandResult
    fun selectSubtitleTrack(id: String?): PlaybackCommandResult
    fun setScaleMode(mode: VideoScaleMode): PlaybackCommandResult
}
