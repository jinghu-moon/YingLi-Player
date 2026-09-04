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

data class PlaybackQueue(
    val mediaIds: List<MediaItemId>,
    val currentIndex: Int,
    val continuousPlayback: Boolean = false,
) {
    init {
        require(mediaIds.isNotEmpty())
        require(mediaIds.distinct().size == mediaIds.size)
        require(currentIndex in mediaIds.indices)
    }

    val current: MediaItemId get() = mediaIds[currentIndex]
    fun next(): MediaItemId? = if (continuousPlayback) mediaIds.getOrNull(currentIndex + 1) else null
}

interface PlaybackQueueRepository {
    val queue: Flow<PlaybackQueue?>
    suspend fun setQueue(queue: PlaybackQueue?)
}

interface TrackPreferenceRepository {
    val trackPreferences: Flow<TrackPreferenceSet>
    suspend fun setGlobal(preference: TrackPreference)
    suspend fun setForMedia(mediaId: MediaItemId, preference: TrackPreference?)
}

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

data class PlayerOverlayState(
    val controlsVisible: Boolean = true,
    val locked: Boolean = false,
    val dragging: Boolean = false,
    val bufferedFraction: Float = 0f,
    val playedFraction: Float = 0f,
    val lastInteractionEpochMillis: Long = 0,
) {
    init {
        require(bufferedFraction in 0f..1f)
        require(playedFraction in 0f..1f)
    }
}

sealed interface PlayerOverlayEvent {
    data class Tap(val nowEpochMillis: Long) : PlayerOverlayEvent
    data class Interaction(val nowEpochMillis: Long) : PlayerOverlayEvent
    data object ToggleLock : PlayerOverlayEvent
    data object DragStarted : PlayerOverlayEvent
    data class DragUpdated(val playedFraction: Float, val bufferedFraction: Float) : PlayerOverlayEvent
    data object DragEnded : PlayerOverlayEvent
    data class Timeout(val nowEpochMillis: Long) : PlayerOverlayEvent
}

object PlayerOverlayReducer {
    const val AUTO_HIDE_MILLIS = 3_000L

    fun reduce(state: PlayerOverlayState, event: PlayerOverlayEvent): PlayerOverlayState = when (event) {
        is PlayerOverlayEvent.Tap -> if (state.locked) state else state.copy(
            controlsVisible = !state.controlsVisible,
            lastInteractionEpochMillis = event.nowEpochMillis,
        )
        is PlayerOverlayEvent.Interaction -> state.copy(
            controlsVisible = true,
            lastInteractionEpochMillis = event.nowEpochMillis,
        )
        PlayerOverlayEvent.ToggleLock -> state.copy(
            locked = !state.locked,
            controlsVisible = true,
            dragging = false,
        )
        PlayerOverlayEvent.DragStarted -> if (state.locked) state else state.copy(dragging = true, controlsVisible = true)
        is PlayerOverlayEvent.DragUpdated -> if (!state.dragging || state.locked) state else state.copy(
            playedFraction = event.playedFraction.coerceIn(0f, 1f),
            bufferedFraction = event.bufferedFraction.coerceIn(state.bufferedFraction, 1f),
        )
        PlayerOverlayEvent.DragEnded -> state.copy(dragging = false)
        is PlayerOverlayEvent.Timeout -> if (
            state.locked || state.dragging || event.nowEpochMillis - state.lastInteractionEpochMillis < AUTO_HIDE_MILLIS
        ) state else state.copy(controlsVisible = false)
    }
}

enum class ScreenshotFailure {
    EMPTY_FRAME,
    PERMISSION,
    STORAGE_FULL,
    READ_ONLY,
    UNKNOWN,
}

sealed interface ScreenshotResult {
    data class Saved(val displayName: String) : ScreenshotResult
    data class Failed(val reason: ScreenshotFailure) : ScreenshotResult
}

interface ScreenshotGateway {
    suspend fun capture(videoTitle: String, positionMillis: Long): ScreenshotResult
}

interface PictureInPictureGateway {
    fun isAvailable(): Boolean
    fun enter(): Boolean
}

enum class AudioFocusEvent {
    GAIN,
    LOSS,
    LOSS_TRANSIENT,
    DUCK,
    HEADSET_DISCONNECTED,
}

fun interface AudioFocusGateway {
    fun observe(listener: (AudioFocusEvent) -> Unit): AutoCloseable
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
