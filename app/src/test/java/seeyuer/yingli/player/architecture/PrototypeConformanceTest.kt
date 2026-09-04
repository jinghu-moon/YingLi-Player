package seeyuer.yingli.player.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.designsystem.icon.IconProvider
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.tokens.FunctionalBrightnessGrade
import seeyuer.yingli.player.core.designsystem.tokens.YingLiSemanticTokens
import seeyuer.yingli.player.domain.navigation.NavigationState
import seeyuer.yingli.player.domain.navigation.RootDestination

class PrototypeConformanceTest {
    private val productionRoot = File("src/main/java")

    @Test
    fun `scheme F keeps three fixed destinations and optional processing`() {
        val defaultState = NavigationState()

        assertEquals(
            listOf(RootDestination.HOME, RootDestination.LIBRARY, RootDestination.ORGANIZE),
            defaultState.primaryDestinations,
        )
        assertEquals(
            RootDestination.entries,
            defaultState.copy(processingPinned = true).primaryDestinations,
        )
    }

    @Test
    fun `Draft 03 uses neutral structural selection and fixed functional grades`() {
        assertEquals(
            YingLiSemanticTokens.LightColors.textPrimary,
            YingLiSemanticTokens.LightColors.selectionStructural,
        )
        assertEquals(
            YingLiSemanticTokens.DarkColors.textPrimary,
            YingLiSemanticTokens.DarkColors.selectionStructural,
        )
        assertEquals(FunctionalBrightnessGrade.B, YingLiSemanticTokens.LightFunctional.brightnessGrade)
        assertEquals(FunctionalBrightnessGrade.A, YingLiSemanticTokens.DarkFunctional.brightnessGrade)
        assertFalse(
            YingLiSemanticTokens.LightColors.selectionStructural == YingLiSemanticTokens.LightFunctional.success.base,
        )
    }

    @Test
    fun `feature code cannot bind primitive hex or third party icons`() {
        val featureSources = productionRoot.resolve("seeyuer/yingli/player/feature")
            .walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .toList()
        val forbidden = listOf("YingLiPrimitiveTokens", "Color(0x", "compose.icons.", "material.icons.")
        val violations = featureSources.flatMap { file ->
            forbidden.filter { token -> file.readText().contains(token) }
                .map { token -> "${file.invariantSeparatorsPath}: $token" }
        }

        assertTrue("Prototype token bypasses:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun `third party icon fallback stays centralized`() {
        val fallbackIcons = YingLiIcon.entries.filter { icon -> icon.provider == IconProvider.MATERIAL_FALLBACK }
        assertEquals(emptyList<YingLiIcon>(), fallbackIcons)
    }
}
