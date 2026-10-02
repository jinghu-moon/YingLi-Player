package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 后台播放开关的行为规则：只有"关掉开关且不在画中画"才在退到后台时暂停。 */
class BackgroundPlaybackPolicyTest {
    @Test
    fun `keeps playing in background when the switch is on`() {
        assertFalse(shouldPauseInBackground(backgroundPlaybackEnabled = true, inPictureInPicture = false))
    }

    @Test
    fun `pauses in background when the switch is off`() {
        assertTrue(shouldPauseInBackground(backgroundPlaybackEnabled = false, inPictureInPicture = false))
    }

    @Test
    fun `keeps playing in picture in picture even when the switch is off`() {
        assertFalse(shouldPauseInBackground(backgroundPlaybackEnabled = false, inPictureInPicture = true))
    }
}
