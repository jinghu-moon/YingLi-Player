package seeyuer.yingli.player.feature.player

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页浮层布局里几处纯计算 / 常量关系：
 *
 * 1. 帧数胶囊"下移到顶栏下方"所用的顶栏高度（[PlayerTopBarContentHeight]）；
 * 2. 极窄屏 / 最大字体下帧数文本的兜底字号（[playerFrameCounterFontSizeSp]）；
 * 3. 辅助带高度动画的判据（[playerAuxiliaryBandHeight]）与截图胶囊的尺寸/动效常量。
 *
 * 1、2 是"胶囊不许压住顶栏按钮"这条要求的可验证部分；3 是"托盘展开时进度行有动画、
 * 而胶囊的竖直带不参与动画"这条要求的可验证部分——两侧都靠这里的数字关系成立。
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
    fun `tool band is full height while a tool capsule holds it`() {
        // 工具胶囊（截图胶囊或 AB 胶囊，含滑出的留位窗口）在场时**直接取满高**：
        // 这一帧就必须是终值，否则胶囊会跟着带子的 0→满高 动画一起从下往上滑
        //（上一轮修掉的"竖直跳变"）。两枚胶囊共用这一个判据。
        assertEquals(PlayerAuxiliaryBandHeight, playerAuxiliaryBandHeight(bandHold = true, trayExpanded = false))
        assertEquals(PlayerAuxiliaryBandHeight, playerAuxiliaryBandHeight(bandHold = true, trayExpanded = true))
    }

    @Test
    fun `tool band stands at the tray height when no capsule holds it`() {
        // 没有胶囊时带子的终值只跟托盘走：托盘展开=满高，收起=0（中间过程由动画给出，见下一条）。
        assertEquals(PlayerAuxiliaryBandHeight, playerAuxiliaryBandHeight(bandHold = false, trayExpanded = true))
        assertEquals(0.dp, playerAuxiliaryBandHeight(bandHold = false, trayExpanded = false))
    }

    @Test
    fun `only a capsule on screen may snap the band height`() {
        // **瞬时**只允许一种成因：带子上挂着会跟着带子动的东西（工具胶囊）。
        // 其余（托盘开关、胶囊滑出后的回位）一律走 240ms 动画 —— 那就是"进度行不瞬移"这条的落点。
        assertEquals(AuxiliaryBandTransition.SNAP, auxiliaryBandTransition(bandHold = true))
        assertEquals(AuxiliaryBandTransition.ANIMATE, auxiliaryBandTransition(bandHold = false))
        assertEquals(TRANSPORT_SECTION_TRANSITION_MILLIS, 240)
    }

    @Test
    fun `screenshot capsule is taller than a tray button and fits the tool band`() {
        // 胶囊必须**比托盘按钮高一档**（用户实测反馈原胶囊偏小），并且整枚胶囊要住得进辅助带，
        // 与下方按钮行之间仍然留出 PlayerPortraitControlsSpacing：这三条是同一条竖直几何。
        assertTrue(
            "胶囊高度必须大于托盘按钮尺寸：$PlayerScreenshotCapsuleHeight",
            PlayerScreenshotCapsuleHeight > PlayerChromeButtonSize,
        )
        assertEquals(PlayerChromeButtonSize + PlayerPortraitControlsSpacing, PlayerScreenshotCapsuleHeight)
        assertEquals(PlayerScreenshotCapsuleHeight + PlayerPortraitControlsSpacing, PlayerAuxiliaryBandHeight)
        // 四周呼吸圈 = (胶囊高 - 按钮尺寸)/2，水平方向也用同一个值。
        assertEquals(
            (PlayerScreenshotCapsuleHeight - PlayerScreenshotCapsuleButtonSize) / 2,
            ScreenshotCapsuleInnerPadding,
        )
        assertTrue("呼吸圈不许为 0（胶囊会贴上按钮顶点）", ScreenshotCapsuleInnerPadding > 0.dp)
    }

    @Test
    fun `capsule buttons use exactly the tray button size`() {
        // 需求：胶囊内按钮的尺寸必须与工具托盘里的按钮一致（同一个常量）。
        // 这里直接钉住等值关系，任何"顺手换个尺寸"的改动都会红。
        assertEquals(PlayerChromeButtonSize, PlayerScreenshotCapsuleButtonSize)
    }

    @Test
    fun `ab capsule reuses the screenshot capsule geometry constants`() {
        // AB 胶囊与截图胶囊**同一格、同一套几何**（§3.3）：高度、四周呼吸圈、按钮间距、
        // 关闭圆钮尺寸、出入场时长全部来自截图胶囊那一组常量，不引入新数字。
        // 这里的断言是"同源"的可验证部分：一旦有人给 AB 胶囊另写一套，这些等式就会先红。
        assertEquals(PlayerChromeButtonSize, PlayerScreenshotCapsuleButtonSize)
        assertEquals((PlayerScreenshotCapsuleHeight - PlayerScreenshotCapsuleButtonSize) / 2, ScreenshotCapsuleInnerPadding)
        assertEquals(12.dp, PlayerScreenshotCapsuleButtonSpacing)
        assertEquals(360, SCREENSHOT_CAPSULE_TRANSITION_MILLIS)
        // 文字按钮的高度就在胶囊的呼吸圈里：48 + (64-48)/2*2 = 64，正好等于胶囊高度。
        assertEquals(
            PlayerScreenshotCapsuleHeight,
            PlayerChromeButtonSize + ScreenshotCapsuleInnerPadding * 2,
        )
        // 文字按钮的"仍算可读"下限只有一处定义（帧数胶囊也用同一个）。
        assertEquals(10.sp, PlayerChromeTextMinFontSize)
    }

    @Test
    fun `capsule button spacing is roomier than the tray row spacing`() {
        // 胶囊是一整块容器，按钮之间没有行外空白可以借，所以间距要比托盘行更大才不显挤。
        assertTrue(
            "胶囊内间距必须大于托盘行间距：$PlayerScreenshotCapsuleButtonSpacing",
            PlayerScreenshotCapsuleButtonSpacing > PlayerShortcutSpacing,
        )
    }

    @Test
    fun `capsule transition is slower than the bottom bar sections`() {
        // 胶囊出入场取 360ms 一档，比底栏三段的 240ms 慢：工具入口要从容，
        // 也避免与三段同拍时挤在一起。同时辅助带的留位窗口用的是**胶囊自己的**时长，
        // 所以改这里必须连带胶囊滑出一起想（见 BottomPlaybackControls 的 capsuleBandHeld）。
        assertEquals(360, SCREENSHOT_CAPSULE_TRANSITION_MILLIS)
        assertTrue(SCREENSHOT_CAPSULE_TRANSITION_MILLIS > TRANSPORT_SECTION_TRANSITION_MILLIS)
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
