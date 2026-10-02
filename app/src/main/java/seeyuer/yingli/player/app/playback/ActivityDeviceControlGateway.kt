package seeyuer.yingli.player.app.playback

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.WindowManager
import kotlin.math.roundToInt
import seeyuer.yingli.player.domain.playback.DeviceControlGateway

/**
 * 画面手势的设备控制实现。
 *
 * 音量走 **系统媒体流**（`STREAM_MUSIC`）：手势与音量键必须是同一套音量，否则用户会看到
 * "按了音量键手势没反应"这类两套状态。系统策略（勿扰等）拦截时返回 failure 由 UI 反馈。
 *
 * 亮度走 **窗口级 `screenBrightness`**（0f..1f，`BRIGHTNESS_OVERRIDE_NONE` 表示跟随系统）：
 * 不需要 `WRITE_SETTINGS`，不改系统设置，退出播放页恢复。
 */
class ActivityDeviceControlGateway(private val activity: Activity) : DeviceControlGateway {
    private val audioManager: AudioManager?
        get() = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun currentVolume(): Float {
        val manager = audioManager ?: return 0f
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0f
        return (manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max).coerceIn(0f, 1f)
    }

    override fun setVolume(fraction: Float): Result<Unit> = runCatching {
        val manager = audioManager ?: error("AudioManager unavailable")
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        require(max > 0) { "music stream has no volume steps" }
        // 系统音量是离散档位，四舍五入比截断更接近手指位置；截断会让接近顶端的
        // 0.95..0.99 长按/拖动始终停在倒数第二档，HUD 看起来不可能到 100%。
        val target = (fraction.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    override fun currentBrightness(): Float {
        val override = activity.window.attributes.screenBrightness
        // -1f（BRIGHTNESS_OVERRIDE_NONE）表示跟随系统，此时用系统当前亮度作为手势起点，
        // 避免第一次上滑从 0 开始跳变。
        if (override >= 0f) return override.coerceIn(0f, 1f)
        return runCatching { systemBrightness() }.getOrDefault(0.5f)
    }

    override fun isFollowingSystemBrightness(): Boolean = activity.window.attributes.screenBrightness < 0f

    override fun setBrightness(fraction: Float): Result<Unit> = runCatching {
        val attributes: WindowManager.LayoutParams = activity.window.attributes
        attributes.screenBrightness = fraction.coerceIn(MIN_BRIGHTNESS, 1f)
        activity.window.attributes = attributes
    }

    override fun resetBrightness() {
        runCatching {
            val attributes: WindowManager.LayoutParams = activity.window.attributes
            attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            activity.window.attributes = attributes
        }
    }

    private fun systemBrightness(): Float {
        val resolver = activity.contentResolver
        val value = android.provider.Settings.System.getInt(
            resolver,
            android.provider.Settings.System.SCREEN_BRIGHTNESS,
            (DEFAULT_SYSTEM_BRIGHTNESS * 255).toInt(),
        )
        return (value / 255f).coerceIn(0f, 1f)
    }

    private companion object {
        /** 最低亮度：完全 0 会让用户以为黑屏，保留一点可见度。 */
        const val MIN_BRIGHTNESS = 0.02f
        const val DEFAULT_SYSTEM_BRIGHTNESS = 0.5f
    }
}
