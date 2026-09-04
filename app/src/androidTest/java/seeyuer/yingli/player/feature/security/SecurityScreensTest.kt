package seeyuer.yingli.player.feature.security

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.security.AppLockMachine
import seeyuer.yingli.player.domain.security.AppLockMode
import seeyuer.yingli.player.domain.security.AppLockPolicy
import seeyuer.yingli.player.domain.security.AppLockSessionState
import seeyuer.yingli.player.domain.security.VaultItem
import seeyuer.yingli.player.domain.security.VaultItemId

class SecurityScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun lockedScreenShowsPinPadAndBiometricFallbackWithoutPrimaryNavigation() {
        composeRule.setContent {
            YingLiTheme(darkTheme = false) {
                AppLockScreen(
                    state = SecurityUiState(
                        initialized = true,
                        machine = AppLockMachine(
                            AppLockPolicy(AppLockMode.BIOMETRIC),
                            AppLockSessionState.Locked(),
                        ),
                    ),
                    onUnlock = {},
                    onBiometric = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.app_lock_title)).assertIsDisplayed()
        composeRule.onNodeWithText("1").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.app_lock_biometric)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.nav_home)).assertDoesNotExist()
    }

    @Test
    fun vaultDeletionRequiresExplicitConfirmation() {
        val item = VaultItem(
            VaultItemId("vault-item"),
            "vault-item.ylv",
            "vault-item.meta.ylv",
            1_024,
            1,
            1,
        )
        composeRule.setContent {
            YingLiTheme(darkTheme = false) {
                VaultScreen(
                    state = VaultUiState(items = listOf(item), pendingDelete = item.id),
                    onImport = {},
                    onPlay = {},
                    onExport = {},
                    onDelete = {},
                    onDismissDelete = {},
                    onConfirmDelete = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.vault_delete_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.vault_delete_message)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.action_cancel)).assertIsDisplayed()
    }
}
