package seeyuer.yingli.player.app.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test
import seeyuer.yingli.player.app.playback.ServicePlaybackEngine.EngineStateKind

/**
 * 引擎状态分类的回归测试。
 *
 * 这里锁定的是一条**用户可见**的行为：只有首次准备才允许显示全屏加载圈；
 * seek（快进/快退/拖进度条）造成的瞬时重缓冲不能触发它，否则画面会一直闪加载圈。
 */
class EngineStateClassifierTest {

    @Test
    fun `buffering before ever being ready is the first preparation`() {
        assertEquals(
            EngineStateKind.PREPARING,
            ServicePlaybackEngine.classifyEngineState(
                playbackState = Player.STATE_BUFFERING,
                isPlaying = false,
                hasEverBeenReady = false,
            ),
        )
    }

    @Test
    fun `buffering after having been ready is a rebuffer`() {
        assertEquals(
            EngineStateKind.REBUFFER,
            ServicePlaybackEngine.classifyEngineState(
                playbackState = Player.STATE_BUFFERING,
                isPlaying = true,
                hasEverBeenReady = true,
            ),
        )
    }

    /**
     * 回归用例：旧实现用 `currentPosition > 0` 判断重缓冲，快退到接近 0（或拖到开头）时
     * position == 0，于是 seek 引发的 BUFFERING 被当成首次准备 → 全屏加载圈。
     * 现在只看"是否曾经就绪过"，与位置无关。
     */
    @Test
    fun `rebuffer classification does not depend on the seek target position`() {
        assertEquals(
            EngineStateKind.REBUFFER,
            ServicePlaybackEngine.classifyEngineState(
                playbackState = Player.STATE_BUFFERING,
                isPlaying = true,
                hasEverBeenReady = true,
            ),
        )
        assertEquals(
            EngineStateKind.PREPARING,
            ServicePlaybackEngine.classifyEngineState(
                playbackState = Player.STATE_BUFFERING,
                isPlaying = true,
                hasEverBeenReady = false,
            ),
        )
    }

    @Test
    fun `ready and playing map to playing while ready and paused map to paused`() {
        assertEquals(
            EngineStateKind.PLAYING,
            ServicePlaybackEngine.classifyEngineState(Player.STATE_READY, isPlaying = true, hasEverBeenReady = true),
        )
        assertEquals(
            EngineStateKind.PAUSED,
            ServicePlaybackEngine.classifyEngineState(Player.STATE_READY, isPlaying = false, hasEverBeenReady = true),
        )
    }

    @Test
    fun `ended wins over the playing flag and idle covers the rest`() {
        assertEquals(
            EngineStateKind.ENDED,
            ServicePlaybackEngine.classifyEngineState(Player.STATE_ENDED, isPlaying = true, hasEverBeenReady = true),
        )
        assertEquals(
            EngineStateKind.IDLE,
            ServicePlaybackEngine.classifyEngineState(Player.STATE_IDLE, isPlaying = false, hasEverBeenReady = false),
        )
    }
}
