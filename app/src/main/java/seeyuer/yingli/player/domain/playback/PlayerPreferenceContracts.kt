package seeyuer.yingli.player.domain.playback

import kotlinx.coroutines.flow.Flow

data class PlayerPreferences(
    val miniPlayerEnabled: Boolean = true,
    val autoPictureInPicture: Boolean = false,
    val longPressSpeed: PlaybackSpeed = DEFAULT_LONG_PRESS_SPEED,
    val gestureSeekEnabled: Boolean = true,
    val gestureVolumeEnabled: Boolean = true,
    val gestureBrightnessEnabled: Boolean = true,
    val gestureZoomEnabled: Boolean = true,
    val gestureLeftSideIsVolume: Boolean = true,
    val gestureDoubleTapSeekMillis: Int = DEFAULT_DOUBLE_TAP_SEEK_MILLIS,

    /**
     * "上一个" 的行为：true = 当前已播放超过 [_PREVIOUS_RESTART_THRESHOLD_MILLIS] 时先回到本集开头，
     * 再按一次才切上一项（多数播放器的惯例）；false = 永远直接切上一项。
     */
    val previousRestartsCurrentItem: Boolean = true,
    val gestureSwipeDownToExitEnabled: Boolean = false,
    /** 常规播放页的一次性手势提示是否已展示过（规格 §5.15 / §19.3）。 */
    val gestureHintShown: Boolean = false,
) {
    companion object {
        /**
         * 长按倍速的可选档位。这里是唯一来源：持久化层用它清洗存量值，
         * 设置界面用它生成选项，越界值一律回退到 [DEFAULT_LONG_PRESS_SPEED]。
         */
        val LONG_PRESS_SPEEDS: List<PlaybackSpeed> = listOf(1.5f, 2f, 3f).map { PlaybackSpeed.of(it) }
        val DEFAULT_LONG_PRESS_SPEED: PlaybackSpeed = PlaybackSpeed.of(2f)

        /** 双击快进/快退的可选步长（毫秒），越界值一律回退到 [DEFAULT_DOUBLE_TAP_SEEK_MILLIS]。 */
        val DOUBLE_TAP_SEEK_MILLIS_OPTIONS: List<Int> = listOf(5_000, 10_000, 15_000, 30_000)
        const val DEFAULT_DOUBLE_TAP_SEEK_MILLIS: Int = 10_000

        fun longPressSpeedOrDefault(stored: Float?): PlaybackSpeed =
            LONG_PRESS_SPEEDS.firstOrNull { it.value == stored } ?: DEFAULT_LONG_PRESS_SPEED

        fun isSupportedLongPressSpeed(stored: Float): Boolean = LONG_PRESS_SPEEDS.any { it.value == stored }

        fun doubleTapSeekMillisOrDefault(stored: Int?): Int =
            stored?.takeIf { it in DOUBLE_TAP_SEEK_MILLIS_OPTIONS } ?: DEFAULT_DOUBLE_TAP_SEEK_MILLIS
    }
}

interface PlayerPreferenceRepository {
    val playerPreferences: Flow<PlayerPreferences>
    suspend fun setMiniPlayerEnabled(enabled: Boolean)
    suspend fun setAutoPictureInPicture(enabled: Boolean)
    suspend fun setGestureSeekEnabled(enabled: Boolean)
    suspend fun setGestureVolumeEnabled(enabled: Boolean)
    suspend fun setGestureBrightnessEnabled(enabled: Boolean)
    suspend fun setGestureZoomEnabled(enabled: Boolean)
    suspend fun setGestureLeftSideIsVolume(enabled: Boolean)
    suspend fun setGestureDoubleTapSeekMillis(millis: Int)

    /** 见 [previousRestartsCurrentItem]。 */
    suspend fun setPreviousRestartsCurrentItem(enabled: Boolean)
    suspend fun setGestureSwipeDownToExitEnabled(enabled: Boolean)
    suspend fun setGestureHintShown(shown: Boolean)
    suspend fun setGestureLongPressSpeed(speed: PlaybackSpeed)
}
