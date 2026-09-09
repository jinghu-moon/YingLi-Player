package seeyuer.yingli.player.core.designsystem.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme

/** Shared structural top bar; callers own navigation state, actions, and menus. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YingLiTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier.fillMaxWidth(),
        title = title,
        navigationIcon = { navigationIcon?.invoke() },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = YingLiTheme.colors.surface,
            scrolledContainerColor = YingLiTheme.colors.surface,
            navigationIconContentColor = YingLiTheme.colors.textPrimary,
            titleContentColor = YingLiTheme.colors.textPrimary,
            actionIconContentColor = YingLiTheme.colors.textPrimary,
        ),
    )
}
