package seeyuer.yingli.player.feature.shell

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import seeyuer.yingli.player.core.datastore.AppearanceSettings
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.navigation.AppRoute
import seeyuer.yingli.player.domain.navigation.NavigationState
import seeyuer.yingli.player.domain.navigation.RootDestination
import seeyuer.yingli.player.feature.library.MediaLibraryUiState

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Suppress("DEPRECATION")
class AdaptiveAppShellTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactUsesThreeFixedDestinationsAndHomeTopActions() {
        setShell(WindowWidthSizeClass.Compact)

        composeRule.onNodeWithTag(ShellTestTags.BOTTOM_NAVIGATION).assertExists()
        composeRule.onNodeWithTag(ShellTestTags.NAVIGATION_RAIL).assertDoesNotExist()
        composeRule.onNode(hasText("首页") and isSelected()).assertExists()
        composeRule.onNodeWithText("视频").assertExists()
        composeRule.onNodeWithText("整理").assertExists()
        composeRule.onNodeWithText("设置").assertDoesNotExist()
        composeRule.onNodeWithText("处理").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("首页", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("搜索").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("更多").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("打开处理中心").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("设置", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("打开设置").assertDoesNotExist()
    }

    @Test
    fun mediumUsesNavigationRail() {
        setShell(WindowWidthSizeClass.Medium)

        composeRule.onNodeWithTag(ShellTestTags.NAVIGATION_RAIL).assertExists()
        composeRule.onNodeWithTag(ShellTestTags.BOTTOM_NAVIGATION).assertDoesNotExist()
    }

    @Test
    fun expandedUsesNavigationRail() {
        setShell(WindowWidthSizeClass.Expanded)

        composeRule.onNodeWithTag(ShellTestTags.NAVIGATION_RAIL).assertExists()
        composeRule.onNodeWithTag(ShellTestTags.BOTTOM_NAVIGATION).assertDoesNotExist()
    }

    @Test
    fun processingPreferenceDoesNotAddAFourthDestination() {
        setShell(
            width = WindowWidthSizeClass.Compact,
            navigationState = NavigationState(),
            settings = AppearanceSettings(),
        )

        composeRule.onNodeWithText("处理").assertDoesNotExist()
    }

    @Test
    fun libraryUsesTopOverflowForViewSettings() {
        setShell(
            width = WindowWidthSizeClass.Compact,
            navigationState = NavigationState(currentRoot = RootDestination.LIBRARY),
        )

        composeRule.onNodeWithContentDescription("视频视图设置").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("打开设置").assertDoesNotExist()
    }

    @Test
    fun playerRouteHidesPrimaryNavigation() {
        val state = NavigationState(
            stacks = NavigationState.initialStacks() + (
                RootDestination.HOME to listOf(
                    AppRoute.Root(RootDestination.HOME),
                    AppRoute.Player("movie-1", RootDestination.HOME),
                )
                ),
        )
        setShell(WindowWidthSizeClass.Compact, navigationState = state)

        composeRule.onNodeWithTag(ShellTestTags.BOTTOM_NAVIGATION).assertDoesNotExist()
        composeRule.onNodeWithTag(ShellTestTags.NAVIGATION_RAIL).assertDoesNotExist()
        composeRule.onNodeWithText("播放画布").assertIsDisplayed()
    }

    @Test
    fun onboardingHidesPrimaryNavigationAndUsesAppNameTopBar() {
        setShell(
            width = WindowWidthSizeClass.Compact,
            mediaState = MediaLibraryUiState(onboarding = true),
        )

        composeRule.onNodeWithTag(ShellTestTags.BOTTOM_NAVIGATION).assertDoesNotExist()
        composeRule.onNodeWithTag(ShellTestTags.NAVIGATION_RAIL).assertDoesNotExist()
        composeRule.onNodeWithText("影里").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("关闭").assertDoesNotExist()
    }

    @Test
    fun settingsRemainUsableAtTwoHundredPercentFontScale() {
        setShell(
            width = WindowWidthSizeClass.Compact,
            navigationState = NavigationState(overlay = AppRoute.Settings),
            fontScale = 2f,
        )

        composeRule.onNodeWithText("媒体库").assertIsDisplayed()
        composeRule.onNode(hasText("首页") and isSelected()).assertExists()
        composeRule.onNodeWithText("自动画中画").performScrollTo().assertIsDisplayed()
    }

    private fun setShell(
        width: WindowWidthSizeClass,
        navigationState: NavigationState = NavigationState(),
        settings: AppearanceSettings = AppearanceSettings(),
        mediaState: MediaLibraryUiState = MediaLibraryUiState(onboarding = false),
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            WithFontScale(fontScale) {
                YingLiTheme(darkTheme = false) {
                    AdaptiveAppShell(
                        navigationState = navigationState,
                        settings = settings,
                        mediaState = mediaState,
                        windowWidthSizeClass = width,
                        onRootSelected = {},
                        onGlobalAction = {},
                        onBack = {},
                    )
                }
            }
        }
    }

    @Composable
    private fun WithFontScale(fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density = density.density, fontScale = fontScale),
            content = content,
        )
    }
}
