package seeyuer.yingli.player.app.playback

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import seeyuer.yingli.player.domain.playback.PictureInPictureGateway
import seeyuer.yingli.player.domain.playback.RequestedOrientation
import seeyuer.yingli.player.domain.playback.WindowPlaybackGateway
import seeyuer.yingli.player.domain.playback.WindowPlaybackState

/**
 * 窗口层真实状态：全屏（系统栏可见性）、用户请求的方向、真实配置方向和 PiP。
 *
 * 规格要求「旋转/全屏必须以系统事实为准」：这里只在系统调用成功、或配置变化真正回调后
 * 才更新状态，失败返回 [Result.failure] 由 UI 提示，不做乐观更新。
 */
class ActivityWindowPlaybackGateway(
    private val activity: Activity,
    private val pictureInPicture: PictureInPictureGateway,
) : WindowPlaybackGateway {
    private val mutableState = MutableStateFlow(
        WindowPlaybackState(
            isFullscreen = false,
            orientation = RequestedOrientation.SENSOR,
            isPortrait = activity.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE,
            isInPictureInPicture = activity.isInPictureInPictureMode,
        ),
    )
    override val state: StateFlow<WindowPlaybackState> = mutableState.asStateFlow()

    override fun setFullscreen(enabled: Boolean): Result<Unit> = runCatching {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        mutableState.value = mutableState.value.copy(isFullscreen = enabled)
    }

    override fun requestOrientation(orientation: RequestedOrientation): Result<Unit> = runCatching {
        activity.requestedOrientation = when (orientation) {
            RequestedOrientation.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            RequestedOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            RequestedOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        mutableState.value = mutableState.value.copy(orientation = orientation)
    }

    override fun enterPictureInPicture(): Result<Unit> =
        if (pictureInPicture.enter()) Result.success(Unit) else Result.failure(IllegalStateException("PIP_UNAVAILABLE"))

    /** Activity 的真实配置变化回传（Manifest 已声明自行处理这些配置）。 */
    fun onConfigurationChanged(configuration: Configuration) {
        val isPortrait = configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        if (isPortrait != mutableState.value.isPortrait) {
            mutableState.value = mutableState.value.copy(isPortrait = isPortrait)
        }
    }

    fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        if (isInPictureInPictureMode != mutableState.value.isInPictureInPicture) {
            mutableState.value = mutableState.value.copy(isInPictureInPicture = isInPictureInPictureMode)
        }
    }
}
