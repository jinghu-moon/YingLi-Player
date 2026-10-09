package seeyuer.yingli.player.feature.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.library.LibraryDisplayPreference
import seeyuer.yingli.player.domain.library.LibraryBrowseMode
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryViewMode

class LibraryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun gridLayoutShowsIndexedMediaAndSearch() {
        setLibrary(LibraryViewMode.GRID)
        composeRule.onNodeWithTag(LibraryTestTags.GRID).assertIsDisplayed()
        composeRule.onNodeWithText("本地影片").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("搜索本地视频、文件夹或标签").performClick()
        composeRule.onNodeWithText("搜索本地视频、文件夹或标签").assertIsDisplayed()
    }

    @Test
    fun listLayoutIsReachableWithoutChangingResult() {
        setLibrary(LibraryViewMode.LIST)
        composeRule.onNodeWithTag(LibraryTestTags.LIST).assertIsDisplayed()
        composeRule.onNodeWithText("movie.mp4").assertIsDisplayed()
        composeRule.onNodeWithText("/storage/emulated/0/DCIM/Camera").assertIsDisplayed()
        // 两条元数据断言的正确预期（2026-10-03 真机核对，文本树实测值）：
        // 列表行的两个信息胶囊由 MediaListRow 渲染 —— `formatMediaListFileSize` 对 MB 及以上
        // 一律保留一位小数（64 MiB → "64.0 MB"），分辨率用完整宽高（1920×1080 → "1920 × 1080"）。
        // 本用例原来断言 "64 MB" / "1080P"：那是这两个数字从 LibraryScreen 内联实现
        // （formatFileSize 省小数、resolutionLabel 取短边）搬到共享的 MediaListRow 之前的口径，
        // 断言没有跟着更新，所以本批之前就一直在失败。断言强度不变：仍是精确文本匹配。
        composeRule.onNodeWithText("64.0 MB").assertIsDisplayed()
        composeRule.onNodeWithText("1920 × 1080").assertIsDisplayed()
    }

    private fun setLibrary(mode: LibraryViewMode) {
        composeRule.setContent {
            YingLiTheme(darkTheme = false) {
                var searchOpen by remember { mutableStateOf(false) }
                val pagingItems = flowOf(PagingData.from(listOf(MEDIA))).collectAsLazyPagingItems()
                LibraryScreen(
                    state = LibraryUiState(
                        totalCount = 1,
                        browseMode = LibraryBrowseMode.ALL_VIDEOS,
                        searchOpen = searchOpen,
                        preference = LibraryDisplayPreference(mode),
                    ),
                    pagingItems = pagingItems,
                    isWide = false,
                    onKeywordChange = {},
                    onGroupChange = {},
                    onViewModeChange = {},
                    onThumbnailScaleChange = {},
                    onToggleFilter = {},
                    onSort = {},
                    onResolutionFilter = {},
                    onDurationFilter = {},
                    onResetFilter = {},
                    onToggleSelection = {},
                    onClearSelection = {},
                    onTrashSelected = {},
                    onMediaSelected = {},
                    onToggleSearch = { searchOpen = !searchOpen },
                )
            }
        }
    }

    private companion object {
        val MEDIA = LibraryMedia(
            MediaItemId("media_1"), MediaLocationId("location_1"),
            MediaUri("content://com.android.externalstorage.documents/tree/primary%3ADCIM/document/primary%3ADCIM%2FCamera%2Fmovie.mp4"),
            "本地影片", "movie.mp4", "相机", "mp4", 60_000, 1_920, 1_080,
            100, 20_000, completed = false, sizeBytes = 64L * 1_024 * 1_024,
        )
    }
}
