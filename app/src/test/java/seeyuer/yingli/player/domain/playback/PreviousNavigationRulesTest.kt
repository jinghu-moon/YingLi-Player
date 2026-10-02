package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "上一个"行为设置的回归测试。
 *
 * 锁定的用户可见行为：
 * - 默认口径：已播放超过 5 秒时先回到本集开头（避免误触丢失进度）；
 * - 用户把设置关掉后：任何位置都直接切上一项（不再被 5 秒惯例改写）。
 */
class PreviousNavigationRulesTest {

    @Test
    fun `default behaviour restarts the current item after the threshold`() {
        assertTrue(
            shouldRestartCurrentItemOnPrevious(
                positionMillis = PREVIOUS_RESTART_THRESHOLD_MILLIS + 1,
                previousRestartsCurrentItem = true,
            ),
        )
    }

    @Test
    fun `default behaviour navigates when still inside the threshold`() {
        assertFalse(
            shouldRestartCurrentItemOnPrevious(
                positionMillis = PREVIOUS_RESTART_THRESHOLD_MILLIS,
                previousRestartsCurrentItem = true,
            ),
        )
        assertFalse(
            shouldRestartCurrentItemOnPrevious(positionMillis = 0L, previousRestartsCurrentItem = true),
        )
    }

    /** 设置关掉后，无论播放到哪里都直接切上一项（5 秒惯例不再生效）。 */
    @Test
    fun `disabling the preference always navigates to the previous item`() {
        assertFalse(
            shouldRestartCurrentItemOnPrevious(
                positionMillis = PREVIOUS_RESTART_THRESHOLD_MILLIS * 10,
                previousRestartsCurrentItem = false,
            ),
        )
    }
}
