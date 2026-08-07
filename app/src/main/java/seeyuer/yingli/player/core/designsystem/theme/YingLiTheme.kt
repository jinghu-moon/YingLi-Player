package seeyuer.yingli.player.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
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
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val semantic = if (darkTheme) YingLiSemanticTokens.DarkColors else YingLiSemanticTokens.LightColors
    val functional = if (darkTheme) YingLiSemanticTokens.DarkFunctional else YingLiSemanticTokens.LightFunctional
    val fixedScheme = if (darkTheme) {
        darkColorScheme(
            primary = semantic.actionPrimary,
            onPrimary = semantic.actionOnPrimary,
            primaryContainer = semantic.actionPrimarySoft,
            onPrimaryContainer = semantic.textPrimary,
            background = semantic.page,
            onBackground = semantic.textPrimary,
            surface = semantic.surface,
            onSurface = semantic.textPrimary,
            surfaceVariant = semantic.surfaceComponent,
            onSurfaceVariant = semantic.textSecondary,
            outline = semantic.borderControl,
            outlineVariant = semantic.borderDefault,
            error = functional.error.base,
            errorContainer = functional.error.container,
            onErrorContainer = functional.error.onContainer,
        )
    } else {
        lightColorScheme(
            primary = semantic.actionPrimary,
            onPrimary = semantic.actionOnPrimary,
            primaryContainer = semantic.actionPrimarySoft,
            onPrimaryContainer = semantic.textPrimary,
            background = semantic.page,
            onBackground = semantic.textPrimary,
            surface = semantic.surface,
            onSurface = semantic.textPrimary,
            surfaceVariant = semantic.surfaceComponent,
            onSurfaceVariant = semantic.textSecondary,
            outline = semantic.borderControl,
            outlineVariant = semantic.borderDefault,
            error = functional.error.base,
            errorContainer = functional.error.container,
            onErrorContainer = functional.error.onContainer,
        )
    }
    val context = LocalContext.current
    val scheme = if (dynamicColor) {
        val dynamic = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        fixedScheme.copy(
            background = dynamic.background.toNeutral(),
            surface = dynamic.surface.toNeutral(),
            surfaceVariant = dynamic.surfaceVariant.toNeutral(),
        )
    } else {
        fixedScheme
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

private fun Color.toNeutral(): Color {
    val lightness = luminance()
    return Color(lightness, lightness, lightness, alpha)
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
