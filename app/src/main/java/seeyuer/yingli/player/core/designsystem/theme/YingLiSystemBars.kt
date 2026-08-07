package seeyuer.yingli.player.core.designsystem.theme

import android.app.Activity
import android.view.Window
import android.view.WindowInsetsController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import seeyuer.yingli.player.core.designsystem.tokens.SystemBarMode

@Composable
fun YingLiSystemBars(mode: SystemBarMode) {
    val view = LocalView.current
    val appearance = YingLiTheme.systemBars.appearance(mode)
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            applySystemBarAppearance(window, appearance)
        }
    }
}

@Suppress("DEPRECATION")
private fun applySystemBarAppearance(
    window: Window,
    appearance: seeyuer.yingli.player.core.designsystem.tokens.SystemBarAppearance,
) {
    window.statusBarColor = appearance.statusBarBackground.toArgb()
    window.navigationBarColor = appearance.navigationBarBackground.toArgb()
    val statusMask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
    val navigationMask = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    window.insetsController?.setSystemBarsAppearance(
        if (appearance.useDarkStatusIcons) statusMask else 0,
        statusMask,
    )
    window.insetsController?.setSystemBarsAppearance(
        if (appearance.useDarkNavigationIcons) navigationMask else 0,
        navigationMask,
    )
}
