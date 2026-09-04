package seeyuer.yingli.player.feature.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.core.model.media.MediaItem
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.feature.library.MediaLibraryNotice
import seeyuer.yingli.player.feature.library.MediaLibraryUiState

class MediaHomeStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun onboardingOffersRecommendedSafAndLaterActions() {
        setHome(MediaLibraryUiState(onboarding = true))

        composeRule.onNodeWithText("使用全部文件访问").assertIsDisplayed()
        composeRule.onNodeWithText("添加目录").assertIsDisplayed()
        composeRule.onNodeWithText("稍后添加").assertIsDisplayed()
    }

    @Test
    fun onboardingSwitchesAccessModeAndPrimaryAction() {
        setHome(MediaLibraryUiState(onboarding = true))

        composeRule.onNodeWithText("继续使用全部文件访问").assertIsDisplayed()
        composeRule.onNodeWithText("添加目录").performClick()
        composeRule.onNodeWithText("继续添加目录").assertIsDisplayed()
    }

    @Test
    fun skippedOnboardingShowsRecoverableEmptyHome() {
        setHome(MediaLibraryUiState(onboarding = false))

        composeRule.onNodeWithText("还没有可播放的视频").assertIsDisplayed()
        composeRule.onNodeWithText("添加媒体源").assertIsDisplayed()
        composeRule.onNodeWithText("添加目录").assertIsDisplayed()
    }

    @Test
    fun permissionDenialKeepsSafFallbackVisible() {
        setHome(MediaLibraryUiState(
            onboarding = false,
            notice = MediaLibraryNotice.PERMISSION_DENIED,
        ))

        composeRule.onNodeWithText("未获得媒体访问权限。你仍可添加目录继续使用。").assertIsDisplayed()
        composeRule.onNodeWithText("添加目录").assertIsDisplayed()
    }

    @Test
    fun scanStatusNeverDisplaysInventedPercentage() {
        setHome(MediaLibraryUiState(onboarding = false, scanning = true))

        composeRule.onNodeWithText("正在更新媒体库，已有内容仍可使用").assertIsDisplayed()
        composeRule.onAllNodes(hasText("%", substring = true)).assertCountEquals(0)
    }

    @Test
    fun emptyLibraryShowsLoadingStateWhileIndexing() {
        setHome(MediaLibraryUiState(onboarding = false, scanning = true))

        composeRule.onNodeWithText("正在更新媒体库，已有内容仍可使用").assertIsDisplayed()
        composeRule.onAllNodes(hasText("还没有可播放的视频")).assertCountEquals(0)
    }

    @Test
    fun cachedContentRemainsVisibleDuringScan() {
        setHome(MediaLibraryUiState(
            onboarding = false,
            scanning = true,
            items = listOf(MediaItem(MediaItemId("item_1"), "缓存影片")),
        ))

        composeRule.onNodeWithText("缓存影片").assertIsDisplayed()
        composeRule.onNodeWithText("正在更新媒体库，已有内容仍可使用").assertIsDisplayed()
    }

    private fun setHome(state: MediaLibraryUiState) {
        composeRule.setContent {
            YingLiTheme(darkTheme = false) {
                HomeScreen(
                    state = state,
                    onRecommendedSource = {},
                    onSafSource = {},
                    onSkip = {},
                    onRescan = {},
                )
            }
        }
    }
}
