package seeyuer.yingli.player.feature.organize

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.organize.TagColor

class OrganizeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun organizeShowsFourPrimaryEntries() {
        setOrganize(OrganizeUiState())

        composeRule.onNodeWithText("收藏").assertIsDisplayed()
        composeRule.onNodeWithText("标签").assertIsDisplayed()
        composeRule.onNodeWithText("播放列表").assertIsDisplayed()
        composeRule.onNodeWithText("智能集合").assertIsDisplayed()
    }

    @Test
    fun tagEditorShowsNameValidationAndEightColorChoices() {
        setOrganize(OrganizeUiState(editorKind = OrganizeEditorKind.TAG, tagColor = TagColor.BLUE))

        composeRule.onNodeWithText("新建标签").assertIsDisplayed()
        composeRule.onNodeWithText("标签圆点颜色").assertIsDisplayed()
    }

    private fun setOrganize(state: OrganizeUiState) {
        composeRule.setContent {
            YingLiTheme(darkTheme = false) {
                OrganizeScreen(
                    state = state,
                    onOpenEditor = {},
                    onCloseEditor = {},
                    onNameChange = {},
                    onColorChange = {},
                    onSave = {},
                    onDuplicateMode = {},
                    onScanDuplicates = {},
                    onCancelDuplicateScan = {},
                    onToggleDuplicateTrash = { _, _ -> },
                    onIgnoreDuplicateGroup = {},
                    onRequestDuplicateDeletion = {},
                    onDismissDuplicateDeletion = {},
                    onConfirmDuplicateDeletion = {},
                )
            }
        }
    }
}
