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

/**
 * 自由缩放（双指捏合）：[scale] 为倍率（1f = 适应），[offsetX]/[offsetY] 为相对画面宽高的
 * 归一化平移（0f 居中，±0.5f 为半个画面）。只做视图层变换，不进播放管线；规格要求
 * "缩放状态在当前播放内保留"，因此它随媒体切换重置。
 */
data class VideoZoom(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    /** 只要不是 1.0x（放大或缩小）都算生效：1.0x 是"适应"，也是复位目标。 */
    val isActive: Boolean get() = kotlin.math.abs(scale - 1f) > MIN_ACTIVE_DELTA

    companion object {
        /** 下限取 0.25f（借鉴 NextPlayer）：允许把画面缩到比"适应"更小，露出黑边由用户自己决定。 */
        const val MIN_SCALE = 0.25f
        const val MAX_SCALE = 4f
        private const val MIN_ACTIVE_DELTA = 0.001f
        val Default = VideoZoom()

        fun of(scale: Float, offsetX: Float, offsetY: Float): VideoZoom {
            val safeScale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
            // 平移上限 = 画面比视口多出来的那一半（放大为正、缩小为负，所以取绝对值）。
            // 与 NextPlayer 的 maxX = |(zoom - 1) * width / 2| 等价，只是这里用归一化写法。
            // 注意：**不要**在 scale 回到 1f 时直接返回 Default —— 那会把平移瞬间清零，
            // 用户捏合经过 1.0x 时会看到画面"抖一下"。这里只做夹紧，1.0x 时上限自然是 0。
            val maxOffset = kotlin.math.abs(safeScale - 1f) / (2f * safeScale)
            return VideoZoom(
                scale = safeScale,
                offsetX = offsetX.coerceIn(-maxOffset, maxOffset),
                offsetY = offsetY.coerceIn(-maxOffset, maxOffset),
            )
        }
    }
}

/**
 * 画面旋转：以 90° 为步进顺时针旋转，[DEGREES_0] 表示不旋转。
 * 这是纯画面变换（视图层），不请求系统方向，也不改变播放管线。
 */
enum class VideoRotation(val degrees: Int) {
    DEGREES_0(0),
    DEGREES_90(90),
    DEGREES_180(180),
    DEGREES_270(270),
    ;

    /** 快捷按钮点击时顺时针转到下一个角度。 */
    fun next(): VideoRotation = entries[(ordinal + 1) % entries.size]

    companion object {
        val Default = DEGREES_0
    }
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
    val rotation: VideoRotation = VideoRotation.Default,
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
