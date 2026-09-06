package seeyuer.yingli.player.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import seeyuer.yingli.player.data.preferences.AppearanceSettings
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.settings.BackupPreview

class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun backupRestorePreviewShowsCountsAndBothConflictStrategies() {
        composeRule.setContent {
            YingLiTheme(darkTheme = false) {
                SettingsScreen(
                    settings = AppearanceSettings(),
                    tools = SettingsToolActions(
                        state = SettingsToolsState(
                            preview = BackupPreview(1, 2, 3, 4, 5, 6, 7),
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("确认恢复内容").assertIsDisplayed()
        composeRule.onNodeWithText("保留现有").assertIsDisplayed()
        composeRule.onNodeWithText("替换").assertIsDisplayed()
        composeRule.onNodeWithText("取消").assertIsDisplayed()
    }
}
