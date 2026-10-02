package seeyuer.yingli.player.domain.playback

enum class ScreenshotFailure {
    EMPTY_FRAME,
    PERMISSION,
    STORAGE_FULL,
    READ_ONLY,
    UNKNOWN,
}

sealed interface ScreenshotResult {
    data class Saved(val displayName: String, val uri: String = "") : ScreenshotResult
    data class Failed(val reason: ScreenshotFailure) : ScreenshotResult
}

interface ScreenshotGateway {
    /**
     * 捕获当前帧并保存。[rotation] 是当前的画面旋转角度：保存的图片要与用户看到的朝向一致，
     * 所以由调用方传入，而不是让网关去猜。
     */
    suspend fun capture(
        videoTitle: String,
        positionMillis: Long,
        rotation: VideoRotation,
    ): ScreenshotResult
}

interface ScreenshotFileGateway {
    suspend fun delete(uri: String): Result<Unit>
}

/**
 * 截图删除失败码。网关用它作为异常 message，UI 用它决定提示文案；
 * 两侧共用同一份定义，避免各写一个字符串而漂移。
 */
enum class ScreenshotDeleteFailure(val code: String) {
    URI_INVALID("SCREENSHOT_URI_INVALID"),
    PERMISSION_DENIED("SCREENSHOT_DELETE_PERMISSION_DENIED"),
    FAILED("SCREENSHOT_DELETE_FAILED"),
}

sealed interface ScreenshotUiState {
    data object Idle : ScreenshotUiState
    data object Armed : ScreenshotUiState
    data object Capturing : ScreenshotUiState
    data class Preview(
        val displayName: String,
        val uri: String = "",
        val remainingMillis: Long = PREVIEW_DURATION_MILLIS,
        val expiryPaused: Boolean = false,
    ) : ScreenshotUiState {
        init { require(displayName.isNotBlank() && remainingMillis in 0..PREVIEW_DURATION_MILLIS) }
    }
    data class Failed(val reason: ScreenshotFailure) : ScreenshotUiState

    companion object {
        const val PREVIEW_DURATION_MILLIS = 3_000L
    }
}

sealed interface ScreenshotUiEvent {
    data object Arm : ScreenshotUiEvent
    data object CaptureStarted : ScreenshotUiEvent
    data class CaptureCompleted(val result: ScreenshotResult) : ScreenshotUiEvent
    data class TimeElapsed(val millis: Long) : ScreenshotUiEvent {
        init { require(millis >= 0) }
    }
    data object ToggleExpiryPause : ScreenshotUiEvent
    data object Close : ScreenshotUiEvent
    data object MediaChanged : ScreenshotUiEvent
}

object ScreenshotUiReducer {
    fun reduce(state: ScreenshotUiState, event: ScreenshotUiEvent): ScreenshotUiState = when (event) {
        ScreenshotUiEvent.Arm -> ScreenshotUiState.Armed
        ScreenshotUiEvent.CaptureStarted -> if (state == ScreenshotUiState.Armed) {
            ScreenshotUiState.Capturing
        } else {
            state
        }
        is ScreenshotUiEvent.CaptureCompleted -> when (val result = event.result) {
            is ScreenshotResult.Saved -> ScreenshotUiState.Preview(result.displayName, result.uri)
            is ScreenshotResult.Failed -> ScreenshotUiState.Failed(result.reason)
        }
        is ScreenshotUiEvent.TimeElapsed -> if (state is ScreenshotUiState.Preview && !state.expiryPaused) {
            val remaining = (state.remainingMillis - event.millis).coerceAtLeast(0)
            if (remaining == 0L) ScreenshotUiState.Idle else state.copy(remainingMillis = remaining)
        } else {
            state
        }
        ScreenshotUiEvent.ToggleExpiryPause -> if (state is ScreenshotUiState.Preview) {
            state.copy(expiryPaused = !state.expiryPaused)
        } else {
            state
        }
        ScreenshotUiEvent.Close, ScreenshotUiEvent.MediaChanged -> ScreenshotUiState.Idle
    }
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
