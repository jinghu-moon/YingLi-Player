package seeyuer.yingli.player.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import seeyuer.yingli.player.core.designsystem.tokens.FunctionalColorTokens
import seeyuer.yingli.player.core.designsystem.tokens.PlayerScrimTokens
import seeyuer.yingli.player.core.designsystem.tokens.SystemBarTokens
import seeyuer.yingli.player.core.designsystem.tokens.YingLiComponentTokenDefaults
import seeyuer.yingli.player.core.designsystem.tokens.YingLiComponentTokens
import seeyuer.yingli.player.core.designsystem.tokens.YingLiSemanticColors
import seeyuer.yingli.player.core.designsystem.tokens.YingLiSemanticTokens

@Immutable
data class YingLiThemeValues(
    val colors: YingLiSemanticColors,
    val functional: FunctionalColorTokens,
    val player: PlayerScrimTokens,
    val systemBars: SystemBarTokens,
    val components: YingLiComponentTokens,
)

val LocalYingLiTheme = staticCompositionLocalOf {
    YingLiThemeValues(
        colors = YingLiSemanticTokens.LightColors,
        functional = YingLiSemanticTokens.LightFunctional,
        player = YingLiSemanticTokens.Player,
        systemBars = YingLiSemanticTokens.SystemBars,
        components = YingLiComponentTokenDefaults.Default,
    )
}

object YingLiTheme {
    val colors: YingLiSemanticColors
        @Composable get() = LocalYingLiTheme.current.colors
    val functional: FunctionalColorTokens
        @Composable get() = LocalYingLiTheme.current.functional
    val player: PlayerScrimTokens
        @Composable get() = LocalYingLiTheme.current.player
    val systemBars: SystemBarTokens
        @Composable get() = LocalYingLiTheme.current.systemBars
    val components: YingLiComponentTokens
        @Composable get() = LocalYingLiTheme.current.components
}

@Composable
fun YingLiTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val semantic = if (darkTheme) YingLiSemanticTokens.DarkColors else YingLiSemanticTokens.LightColors
    val functional = if (darkTheme) YingLiSemanticTokens.DarkFunctional else YingLiSemanticTokens.LightFunctional
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = semantic.actionPrimary,
            onPrimary = semantic.actionOnPrimary,
            primaryContainer = semantic.actionPrimarySoft,
            onPrimaryContainer = semantic.textPrimary,
            secondary = semantic.actionSecondary,
            onSecondary = semantic.actionOnSecondary,
            secondaryContainer = semantic.actionSecondaryContainer,
            onSecondaryContainer = semantic.actionOnSecondaryContainer,
            tertiary = semantic.actionTertiary,
            onTertiary = semantic.actionOnTertiary,
            tertiaryContainer = semantic.actionTertiaryContainer,
            onTertiaryContainer = semantic.actionOnTertiaryContainer,
            background = semantic.page,
            onBackground = semantic.textPrimary,
            surface = semantic.surface,
            onSurface = semantic.textPrimary,
            surfaceVariant = semantic.surfaceComponent,
            onSurfaceVariant = semantic.textSecondary,
            surfaceDim = semantic.surfaceLevel0,
            surfaceBright = semantic.surfaceLevel3,
            surfaceContainerLowest = semantic.surfaceLevel0,
            surfaceContainerLow = semantic.surfaceLevel1,
            surfaceContainer = semantic.surfaceLevel2,
            surfaceContainerHigh = semantic.surfaceLevel3,
            surfaceContainerHighest = semantic.surfaceComponentHover,
            outline = semantic.borderControl,
            outlineVariant = semantic.borderDefault,
            error = functional.error.base,
            onError = semantic.actionOnPrimary,
            errorContainer = functional.error.container,
            onErrorContainer = functional.error.onContainer,
            inverseSurface = semantic.surfaceInverse,
            inverseOnSurface = semantic.textInverse,
            inversePrimary = semantic.inversePrimary,
            scrim = semantic.scrimDefault,
        )
    } else {
        lightColorScheme(
            primary = semantic.actionPrimary,
            onPrimary = semantic.actionOnPrimary,
            primaryContainer = semantic.actionPrimarySoft,
            onPrimaryContainer = semantic.textPrimary,
            secondary = semantic.actionSecondary,
            onSecondary = semantic.actionOnSecondary,
            secondaryContainer = semantic.actionSecondaryContainer,
            onSecondaryContainer = semantic.actionOnSecondaryContainer,
            tertiary = semantic.actionTertiary,
            onTertiary = semantic.actionOnTertiary,
            tertiaryContainer = semantic.actionTertiaryContainer,
            onTertiaryContainer = semantic.actionOnTertiaryContainer,
            background = semantic.page,
            onBackground = semantic.textPrimary,
            surface = semantic.surface,
            onSurface = semantic.textPrimary,
            surfaceVariant = semantic.surfaceComponent,
            onSurfaceVariant = semantic.textSecondary,
            surfaceDim = semantic.surfaceLevel0,
            surfaceBright = semantic.surfaceLevel3,
            surfaceContainerLowest = semantic.surfaceLevel0,
            surfaceContainerLow = semantic.surfaceLevel1,
            surfaceContainer = semantic.surfaceLevel2,
            surfaceContainerHigh = semantic.surfaceLevel3,
            surfaceContainerHighest = semantic.surfaceComponentHover,
            outline = semantic.borderControl,
            outlineVariant = semantic.borderDefault,
            error = functional.error.base,
            onError = semantic.actionOnPrimary,
            errorContainer = functional.error.container,
            onErrorContainer = functional.error.onContainer,
            inverseSurface = semantic.surfaceInverse,
            inverseOnSurface = semantic.textInverse,
            inversePrimary = semantic.inversePrimary,
            scrim = semantic.scrimDefault,
        )
    }
    val values = YingLiThemeValues(
        colors = semantic,
        functional = functional,
        player = YingLiSemanticTokens.Player,
        systemBars = YingLiSemanticTokens.SystemBars,
        components = YingLiComponentTokenDefaults.Default,
    )

    CompositionLocalProvider(LocalYingLiTheme provides values) {
        MaterialTheme(
            colorScheme = scheme,
            typography = YingLiTypography,
            shapes = YingLiShapes,
            content = content,
        )
    }
}

private val YingLiTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 0.sp),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
)

private val YingLiShapes = Shapes(
    extraSmall = YingLiComponentTokenDefaults.Default.compactCorner,
    small = YingLiComponentTokenDefaults.Default.compactCorner,
    medium = YingLiComponentTokenDefaults.Default.componentCorner,
    large = YingLiComponentTokenDefaults.Default.componentCorner,
    extraLarge = YingLiComponentTokenDefaults.Default.componentCorner,
)
