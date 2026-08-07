package seeyuer.yingli.player.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.datastore.AppearanceSettings
import seeyuer.yingli.player.core.datastore.ThemePreference
import seeyuer.yingli.player.core.designsystem.component.YingLiSegmentedControl
import seeyuer.yingli.player.core.designsystem.component.YingLiSwitch
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme

@Composable
fun SettingsScreen(
    settings: AppearanceSettings,
    onThemePreferenceChanged: (ThemePreference) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onProcessingPinnedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val preferences = ThemePreference.entries
    val labels = listOf(
        stringResource(R.string.theme_light),
        stringResource(R.string.theme_dark),
        stringResource(R.string.theme_system),
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(YingLiTheme.components.pagePadding),
        verticalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing),
    ) {
        Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.labelLarge)
        YingLiSegmentedControl(
            options = labels,
            selectedIndex = preferences.indexOf(settings.themePreference),
            onSelected = { index -> onThemePreferenceChanged(preferences[index]) },
        )
        YingLiSwitch(
            label = stringResource(R.string.settings_dynamic_color),
            checked = settings.dynamicColorEnabled,
            onCheckedChange = onDynamicColorChanged,
        )
        YingLiSwitch(
            label = stringResource(R.string.settings_pin_processing),
            checked = settings.processingPinned,
            onCheckedChange = onProcessingPinnedChanged,
        )
    }
}
