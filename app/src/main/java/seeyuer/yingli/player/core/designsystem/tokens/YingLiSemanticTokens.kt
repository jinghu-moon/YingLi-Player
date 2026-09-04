package seeyuer.yingli.player.core.designsystem.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

enum class FunctionalBrightnessGrade {
    A,
    B,
}

@Immutable
data class FunctionalColorFamily(
    val base: Color,
    val strong: Color,
    val container: Color,
    val onContainer: Color,
)

@Immutable
data class FunctionalColorTokens(
    val brightnessGrade: FunctionalBrightnessGrade,
    val info: FunctionalColorFamily,
    val warning: FunctionalColorFamily,
    val error: FunctionalColorFamily,
    val success: FunctionalColorFamily,
)

@Immutable
data class PlayerScrimTokens(
    val canvas: Color,
    val controlPrimary: Color,
    val controlSecondary: Color,
    val controlDisabled: Color,
    val track: Color,
    val buffer: Color,
    val edgeScrim: Color,
    val centerScrim: Color,
)

enum class SystemBarMode {
    LIGHT_APP,
    DARK_APP,
    PLAYER,
}

@Immutable
data class SystemBarAppearance(
    val statusBarBackground: Color,
    val navigationBarBackground: Color,
    val useDarkStatusIcons: Boolean,
    val useDarkNavigationIcons: Boolean,
)

@Immutable
data class SystemBarTokens(
    val lightApp: SystemBarAppearance,
    val darkApp: SystemBarAppearance,
    val player: SystemBarAppearance,
) {
    fun appearance(mode: SystemBarMode): SystemBarAppearance = when (mode) {
        SystemBarMode.LIGHT_APP -> lightApp
        SystemBarMode.DARK_APP -> darkApp
        SystemBarMode.PLAYER -> player
    }
}

@Immutable
data class YingLiSemanticColors(
    val page: Color,
    val surface: Color,
    val surfaceSubtle: Color,
    val surfaceMuted: Color,
    val surfaceComponent: Color,
    val surfaceComponentHover: Color,
    val surfaceInverse: Color,
    val surfaceLevel0: Color,
    val surfaceLevel1: Color,
    val surfaceLevel2: Color,
    val surfaceLevel3: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textPlaceholder: Color,
    val textDisabled: Color,
    val textInverse: Color,
    val textSelectionBackground: Color,
    val textSelectionForeground: Color,
    val borderDivider: Color,
    val borderDefault: Color,
    val borderStrong: Color,
    val borderControl: Color,
    val focusRing: Color,
    val actionPrimary: Color,
    val actionPrimaryHover: Color,
    val actionPrimaryPressed: Color,
    val actionOnPrimary: Color,
    val actionPrimarySoft: Color,
    val actionSecondary: Color,
    val actionOnSecondary: Color,
    val actionSecondaryContainer: Color,
    val actionOnSecondaryContainer: Color,
    val actionTertiary: Color,
    val actionOnTertiary: Color,
    val actionTertiaryContainer: Color,
    val actionOnTertiaryContainer: Color,
    val selectionStructural: Color,
    val selectionOnStructural: Color,
    val inversePrimary: Color,
    val stateHover: Color,
    val statePressed: Color,
    val stateDragged: Color,
    val scrimSubtle: Color,
    val scrimDefault: Color,
    val scrimStrong: Color,
    val scrollbarThumb: Color,
    val accentFavorite: Color,
)

object YingLiSemanticTokens {
    private val primitive = YingLiPrimitiveTokens

    val LightColors = YingLiSemanticColors(
        page = primitive.Neutral100,
        surface = primitive.Neutral0,
        surfaceSubtle = primitive.Neutral50,
        surfaceMuted = primitive.Neutral200,
        surfaceComponent = primitive.Neutral200,
        surfaceComponentHover = primitive.Neutral300,
        surfaceInverse = primitive.Neutral950,
        surfaceLevel0 = primitive.Neutral100,
        surfaceLevel1 = primitive.Neutral0,
        surfaceLevel2 = primitive.Neutral0,
        surfaceLevel3 = primitive.Neutral0,
        textPrimary = primitive.Neutral900,
        textSecondary = primitive.Neutral700,
        textPlaceholder = primitive.Neutral600,
        textDisabled = primitive.Neutral500,
        textInverse = primitive.Neutral0,
        textSelectionBackground = primitive.SteelBlueB.tone200,
        textSelectionForeground = primitive.SteelBlueB.tone900,
        borderDivider = primitive.Neutral200,
        borderDefault = primitive.Neutral300,
        borderStrong = primitive.Neutral600,
        borderControl = primitive.Neutral600,
        focusRing = primitive.SteelBlueB.tone600,
        actionPrimary = primitive.Neutral900,
        actionPrimaryHover = primitive.Neutral800,
        actionPrimaryPressed = primitive.Neutral1000,
        actionOnPrimary = primitive.Neutral0,
        actionPrimarySoft = primitive.Neutral200,
        actionSecondary = primitive.Neutral700,
        actionOnSecondary = primitive.Neutral0,
        actionSecondaryContainer = primitive.Neutral200,
        actionOnSecondaryContainer = primitive.Neutral900,
        actionTertiary = primitive.Neutral800,
        actionOnTertiary = primitive.Neutral0,
        actionTertiaryContainer = primitive.Neutral100,
        actionOnTertiaryContainer = primitive.Neutral900,
        selectionStructural = primitive.Neutral900,
        selectionOnStructural = primitive.Neutral0,
        inversePrimary = primitive.Neutral100,
        stateHover = Color(0x0F000000),
        statePressed = Color(0x1A000000),
        stateDragged = Color(0x24000000),
        scrimSubtle = Color(0x52000000),
        scrimDefault = Color(0x8F000000),
        scrimStrong = Color(0xB8000000),
        scrollbarThumb = primitive.Neutral500,
        accentFavorite = Color(0xFFBD3255),
    )

    val DarkColors = YingLiSemanticColors(
        page = Color(0xFF0F0F0F),
        surface = Color(0xFF171717),
        surfaceSubtle = Color(0xFF1F1F1F),
        surfaceMuted = Color(0xFF242424),
        surfaceComponent = Color(0xFF292929),
        surfaceComponentHover = Color(0xFF333333),
        surfaceInverse = primitive.Neutral100,
        surfaceLevel0 = Color(0xFF0F0F0F),
        surfaceLevel1 = Color(0xFF171717),
        surfaceLevel2 = Color(0xFF1F1F1F),
        surfaceLevel3 = Color(0xFF292929),
        textPrimary = primitive.Neutral100,
        textSecondary = Color(0xFFB8B8B8),
        textPlaceholder = Color(0xFF858585),
        textDisabled = Color(0xFF5F5F5F),
        textInverse = primitive.Neutral950,
        textSelectionBackground = primitive.SteelBlueA.tone800,
        textSelectionForeground = primitive.SteelBlueA.tone100,
        borderDivider = Color(0xFF292929),
        borderDefault = Color(0xFF353535),
        borderStrong = Color(0xFF5D5D5D),
        borderControl = Color(0xFF707070),
        focusRing = primitive.SteelBlueA.tone300,
        actionPrimary = primitive.Neutral100,
        actionPrimaryHover = primitive.Neutral0,
        actionPrimaryPressed = primitive.Neutral300,
        actionOnPrimary = primitive.Neutral950,
        actionPrimarySoft = Color(0xFF2B2B2B),
        actionSecondary = primitive.Neutral400,
        actionOnSecondary = primitive.Neutral950,
        actionSecondaryContainer = primitive.Neutral800,
        actionOnSecondaryContainer = primitive.Neutral100,
        actionTertiary = primitive.Neutral300,
        actionOnTertiary = primitive.Neutral950,
        actionTertiaryContainer = primitive.Neutral900,
        actionOnTertiaryContainer = primitive.Neutral100,
        selectionStructural = primitive.Neutral100,
        selectionOnStructural = primitive.Neutral950,
        inversePrimary = primitive.Neutral900,
        stateHover = Color(0x14FFFFFF),
        statePressed = Color(0x1FFFFFFF),
        stateDragged = Color(0x29FFFFFF),
        scrimSubtle = Color(0x66000000),
        scrimDefault = Color(0xA3000000),
        scrimStrong = Color(0xCC000000),
        scrollbarThumb = primitive.Neutral700,
        accentFavorite = Color(0xFFE89CAF),
    )

    val LightFunctional = FunctionalColorTokens(
        brightnessGrade = FunctionalBrightnessGrade.B,
        info = FunctionalColorFamily(
            base = primitive.SteelBlueB.tone600,
            strong = primitive.SteelBlueB.tone700,
            container = primitive.SteelBlueB.tone100,
            onContainer = primitive.SteelBlueB.tone800,
        ),
        warning = FunctionalColorFamily(
            base = primitive.AmberB.tone600,
            strong = primitive.AmberB.tone700,
            container = primitive.AmberB.tone100,
            onContainer = primitive.AmberB.tone800,
        ),
        error = FunctionalColorFamily(
            base = primitive.RedB.tone600,
            strong = primitive.RedB.tone700,
            container = primitive.RedB.tone100,
            onContainer = primitive.RedB.tone800,
        ),
        success = FunctionalColorFamily(
            base = primitive.GreenB.tone700,
            strong = primitive.GreenB.tone900,
            container = primitive.GreenB.tone200,
            onContainer = primitive.GreenB.tone900,
        ),
    )

    val DarkFunctional = FunctionalColorTokens(
        brightnessGrade = FunctionalBrightnessGrade.A,
        info = FunctionalColorFamily(
            base = primitive.SteelBlueA.tone300,
            strong = primitive.SteelBlueA.tone200,
            container = primitive.SteelBlueA.tone950,
            onContainer = primitive.SteelBlueA.tone200,
        ),
        warning = FunctionalColorFamily(
            base = primitive.AmberA.tone300,
            strong = primitive.AmberA.tone200,
            container = primitive.AmberA.tone950,
            onContainer = primitive.AmberA.tone200,
        ),
        error = FunctionalColorFamily(
            base = primitive.RedA.tone300,
            strong = primitive.RedA.tone200,
            container = primitive.RedA.tone950,
            onContainer = primitive.RedA.tone200,
        ),
        success = FunctionalColorFamily(
            base = primitive.GreenA.tone400,
            strong = primitive.GreenA.tone200,
            container = primitive.GreenA.tone800,
            onContainer = primitive.GreenA.tone100,
        ),
    )

    val Player = PlayerScrimTokens(
        canvas = primitive.Neutral1000,
        controlPrimary = primitive.Neutral0,
        controlSecondary = Color(0xB8FFFFFF),
        controlDisabled = Color(0x66FFFFFF),
        track = Color(0x3DFFFFFF),
        buffer = Color(0x66FFFFFF),
        edgeScrim = Color(0xB8000000),
        centerScrim = Color.Transparent,
    )

    val SystemBars = SystemBarTokens(
        lightApp = SystemBarAppearance(
            statusBarBackground = Color.Transparent,
            navigationBarBackground = Color.Transparent,
            useDarkStatusIcons = true,
            useDarkNavigationIcons = true,
        ),
        darkApp = SystemBarAppearance(
            statusBarBackground = Color.Transparent,
            navigationBarBackground = Color.Transparent,
            useDarkStatusIcons = false,
            useDarkNavigationIcons = false,
        ),
        player = SystemBarAppearance(
            statusBarBackground = Color.Transparent,
            navigationBarBackground = primitive.Neutral1000,
            useDarkStatusIcons = false,
            useDarkNavigationIcons = false,
        ),
    )
}
