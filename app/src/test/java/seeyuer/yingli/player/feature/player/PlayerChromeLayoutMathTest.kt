package seeyuer.yingli.player.feature.player

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页浮层布局里两处纯计算：
 *
 * 1. 帧数胶囊"下移到顶栏下方"所用的顶栏高度（[PlayerTopBarContentHeight]）；
 * 2. 极窄屏 / 最大字体下帧数文本的兜底字号（[playerFrameCounterFontSizeSp]）。
 *
 * 两者都是"胶囊不许压住顶栏按钮"这条要求的可验证部分：高度错了胶囊会重新贴回按钮上，
 * 字号算错则帧号会被裁掉。
 */
class PlayerChromeLayoutMathTest {

    @Test
    fun `frame counter top padding is built from the real top bar height`() {
        // 顶栏自身高度 = 上下内边距 8dp × 2 + 一枚 48dp 圆按钮 = 64dp。
        // 帧数胶囊的顶部内边距 = 状态栏 inset + 64dp + PlayerFrameCounterTopGap。
        assertEquals(48.dp, PlayerChromeButtonSize)
        assertEquals(64.dp, PlayerTopBarContentHeight)
        assertEquals(12.dp, PlayerFrameCounterTopGap)
    }

    @Test
    fun `roomy width keeps the base font size`() {
        // `488912 / 802008` 共 15 字符，可用 200dp 时按 0.95 字宽反推约 14.04sp，不超过基础字号。
        assertEquals(
            14.sp,
            playerFrameCounterFontSizeSp(
                availableWidth = 200.dp,
                characterCount = 15,
                baseFontSize = 14.sp,
            ),
        )
    }

    @Test
    fun `narrow width shrinks the font instead of dropping digits`() {
        // 可用 120dp / 15 字符 → 120 / 14.25 ≈ 8.42sp，被 10sp 下限托住：
        // 整体缩一圈，但不裁掉任何一位数字。
        assertEquals(
            10.sp,
            playerFrameCounterFontSizeSp(
                availableWidth = 120.dp,
                characterCount = 15,
                baseFontSize = 14.sp,
            ),
        )
    }

    @Test
    fun `font size never drops below the readable floor even with many digits`() {
        // 极端位数（10 位帧号共 21 字符）在很窄的宽度下也只到下限，不会缩到看不清。
        assertEquals(
            10.sp,
            playerFrameCounterFontSizeSp(
                availableWidth = 60.dp,
                characterCount = 21,
                baseFontSize = 14.sp,
            ),
        )
    }

    @Test
    fun `more digits never yield a larger font size`() {
        // 单调性：位数变多只能让字号持平或变小，否则长文本会溢出被裁。
        val base = 14.sp
        val fontSizes = (1..24).map { characterCount ->
            playerFrameCounterFontSizeSp(240.dp, characterCount, base).value
        }
        fontSizes.zipWithNext { shorter, longer ->
            assertTrue("字符数更多时字号不允许变大：$shorter -> $longer", longer <= shorter)
        }
    }

    @Test
    fun `unusable width falls back to the base font size`() {
        // 约束为 0 / 负（尚未布局完成）或字符数为 0 时不做缩放：
        // 返回基础字号而不是 0，避免出现"字号为 0"的不可见文本。
        assertEquals(14.sp, playerFrameCounterFontSizeSp(0.dp, 15, 14.sp))
        assertEquals(14.sp, playerFrameCounterFontSizeSp((-1).dp, 15, 14.sp))
        assertEquals(14.sp, playerFrameCounterFontSizeSp(200.dp, 0, 14.sp))
    }
}
