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
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.home.HomeCardId
import seeyuer.yingli.player.domain.home.HomeCardLayout
import seeyuer.yingli.player.domain.home.HomeCollectionPreview
import seeyuer.yingli.player.domain.home.HomeFolderPreview
import seeyuer.yingli.player.domain.home.HomeMediaPreview
import seeyuer.yingli.player.domain.home.HomeStats
import seeyuer.yingli.player.domain.home.MaintenanceItem
import seeyuer.yingli.player.domain.home.MaintenanceKind
import seeyuer.yingli.player.domain.organize.CollectionId
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

    @Test
    fun dashboardUsesConfiguredOrderAndHidesEmptyCards() {
        setDashboard(dashboard())

        val statsTop = composeRule.onNodeWithText("媒体概览").fetchSemanticsNode().boundsInRoot.top
        val recentTop = composeRule.onNodeWithText("最近添加").fetchSemanticsNode().boundsInRoot.top
        assert(statsTop < recentTop)
        composeRule.onNodeWithText("继续观看").assertDoesNotExist()
        composeRule.onNodeWithText("我的合集").assertExists()
        composeRule.onNodeWithText("需要处理").assertExists()
    }

    @Test
    fun userHiddenCardIsNotRenderedButOtherCardsRemain() {
        val hidden = HomeCardLayout.Default.setVisible(HomeCardId.RECENTLY_ADDED, false)
        setDashboard(dashboard().copy(layout = hidden))

        composeRule.onNodeWithText("最近添加").assertDoesNotExist()
        composeRule.onNodeWithText("媒体概览").assertExists()
    }

    @Test
    fun editorShowsEveryCardIncludingHiddenCards() {
        setDashboard(dashboard().copy(editorVisible = true))

        composeRule.onNodeWithText("自定义首页").assertIsDisplayed()
        composeRule.onNodeWithText("统计卡片").assertIsDisplayed()
        composeRule.onNodeWithText("继续观看").assertIsDisplayed()
        composeRule.onNodeWithText("恢复默认").assertIsDisplayed()
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

    private fun setDashboard(dashboard: HomeUiState) {
        composeRule.setContent {
            YingLiTheme(darkTheme = false) {
                HomeScreen(
                    state = MediaLibraryUiState(
                        onboarding = false,
                        items = listOf(MediaItem(MediaItemId("item_1"), "首页测试")),
                    ),
                    onRecommendedSource = {},
                    onSafSource = {},
                    onSkip = {},
                    onRescan = {},
                    dashboardState = dashboard,
                )
            }
        }
    }

    private fun dashboard() = HomeUiState(
        stats = HomeStats(1, 1_024, 8_192, 4_096),
        recentlyAdded = listOf(
            HomeMediaPreview(
                MediaItemId("item_1"),
                MediaLocationId("location_1"),
                MediaUri("content://item/1"),
                "最近影片",
                "Movies",
                1_024,
                60_000,
                1_920,
                1_080,
                10,
                0,
            ),
        ),
        collections = listOf(HomeCollectionPreview(CollectionId("collection_1"), "周末", 1, 10)),
        frequentFolders = listOf(HomeFolderPreview("source_1", "Movies", 1, 1_024)),
        maintenance = listOf(MaintenanceItem(MaintenanceKind.TRASH, 1)),
    )
}
