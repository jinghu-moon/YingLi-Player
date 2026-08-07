package seeyuer.yingli.player.core.designsystem.tokens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YingLiTokenTest {
    @Test
    fun `neutral scale has thirteen ordered tones`() {
        val tones = YingLiPrimitiveTokens.neutralScale

        assertEquals(13, tones.size)
        assertTrue(tones.zipWithNext().all { (lighter, darker) -> lighter.luminance() > darker.luminance() })
    }

    @Test
    fun `light and dark themes use frozen functional grades`() {
        assertEquals(FunctionalBrightnessGrade.B, YingLiSemanticTokens.LightFunctional.brightnessGrade)
        assertEquals(FunctionalBrightnessGrade.A, YingLiSemanticTokens.DarkFunctional.brightnessGrade)
    }

    @Test
    fun `body text and focus boundaries meet contrast gates`() {
        assertContrastAtLeast(
            YingLiSemanticTokens.LightColors.textPrimary,
            YingLiSemanticTokens.LightColors.page,
            BODY_TEXT_MINIMUM,
        )
        assertContrastAtLeast(
            YingLiSemanticTokens.DarkColors.textPrimary,
            YingLiSemanticTokens.DarkColors.page,
            BODY_TEXT_MINIMUM,
        )
        assertContrastAtLeast(
            YingLiSemanticTokens.LightColors.focusRing,
            YingLiSemanticTokens.LightColors.page,
            UI_MINIMUM,
        )
        assertContrastAtLeast(
            YingLiSemanticTokens.DarkColors.focusRing,
            YingLiSemanticTokens.DarkColors.page,
            UI_MINIMUM,
        )
    }

    @Test
    fun `state and scrim opacity values match Draft 03`() {
        val light = YingLiSemanticTokens.LightColors
        val dark = YingLiSemanticTokens.DarkColors

        assertAlpha(light.stateHover, 0.06f)
        assertAlpha(light.statePressed, 0.10f)
        assertAlpha(light.stateDragged, 0.14f)
        assertAlpha(dark.stateHover, 0.08f)
        assertAlpha(dark.statePressed, 0.12f)
        assertAlpha(dark.stateDragged, 0.16f)
        assertAlpha(light.scrimSubtle, 0.32f)
        assertAlpha(light.scrimDefault, 0.56f)
        assertAlpha(light.scrimStrong, 0.72f)
        assertAlpha(dark.scrimSubtle, 0.40f)
        assertAlpha(dark.scrimDefault, 0.64f)
        assertAlpha(dark.scrimStrong, 0.80f)
    }

    @Test
    fun `player canvas and controls use fixed achromatic mapping`() {
        val player = YingLiSemanticTokens.Player

        assertEquals(Color.Black, player.canvas)
        assertEquals(Color.White, player.controlPrimary)
        assertAlpha(player.controlSecondary, 0.72f)
        assertAlpha(player.controlDisabled, 0.40f)
        val worstCaseEdge = player.edgeScrim.compositeOver(Color.White)
        assertContrastAtLeast(player.controlSecondary.compositeOver(worstCaseEdge), worstCaseEdge, BODY_TEXT_MINIMUM)
    }

    @Test
    fun `dark surface elevation becomes lighter`() {
        val colors = YingLiSemanticTokens.DarkColors
        val levels = listOf(colors.surfaceLevel0, colors.surfaceLevel1, colors.surfaceLevel2, colors.surfaceLevel3)

        assertTrue(levels.zipWithNext().all { (lower, higher) -> lower.luminance() < higher.luminance() })
    }

    private fun assertAlpha(color: Color, expected: Float) {
        assertEquals(expected, color.alpha, ALPHA_TOLERANCE)
    }

    private fun assertContrastAtLeast(foreground: Color, background: Color, minimum: Float) {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        val ratio = (lighter + 0.05f) / (darker + 0.05f)
        assertTrue("Contrast $ratio is below $minimum", ratio >= minimum)
    }

    private companion object {
        const val BODY_TEXT_MINIMUM = 4.5f
        const val UI_MINIMUM = 3f
        const val ALPHA_TOLERANCE = 0.005f
    }
}
