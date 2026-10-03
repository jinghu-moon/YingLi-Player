package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动画中画的参数镜像规则：只有「用户偏好开着 + 不是安全内容 + 确实有媒体」三条同时成立，
 * 才允许把 `setAutoEnterEnabled(true)` 下发给系统。
 *
 * 这三条各自都必须是硬条件：关掉任一条就意味着"系统会在错误的时机把画面弹进浮窗"，
 * 所以每条都单独有用例，避免以后有人把条件合并成一个布尔量时丢掉其中一条。
 */
class AutoPictureInPicturePolicyTest {
    @Test
    fun `arms auto enter when the preference is on with media and no secure content`() {
        assertTrue(
            shouldAutoEnterPictureInPicture(
                preferenceEnabled = true,
                secureContent = false,
                hasMedia = true,
            ),
        )
    }

    @Test
    fun `does not arm auto enter when the preference is off`() {
        assertFalse(
            shouldAutoEnterPictureInPicture(
                preferenceEnabled = false,
                secureContent = false,
                hasMedia = true,
            ),
        )
    }

    @Test
    fun `does not arm auto enter for secure content even when the preference is on`() {
        assertFalse(
            shouldAutoEnterPictureInPicture(
                preferenceEnabled = true,
                secureContent = true,
                hasMedia = true,
            ),
        )
    }

    @Test
    fun `does not arm auto enter without media`() {
        assertFalse(
            shouldAutoEnterPictureInPicture(
                preferenceEnabled = true,
                secureContent = false,
                hasMedia = false,
            ),
        )
    }
}
