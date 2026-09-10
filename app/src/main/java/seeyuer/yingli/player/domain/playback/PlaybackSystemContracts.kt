package seeyuer.yingli.player.domain.playback

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
