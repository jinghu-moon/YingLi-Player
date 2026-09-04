package seeyuer.yingli.player.feature.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.library.LibraryDisplayPreference
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
        composeRule.onNodeWithText("搜索本地视频、文件夹或标签").assertIsDisplayed()
    }

    @Test
    fun listLayoutIsReachableWithoutChangingResult() {
        setLibrary(LibraryViewMode.LIST)
        composeRule.onNodeWithTag(LibraryTestTags.LIST).assertIsDisplayed()
        composeRule.onNodeWithText("movie.mp4").assertIsDisplayed()
        composeRule.onNodeWithText("/storage/emulated/0/DCIM/Camera").assertIsDisplayed()
        composeRule.onNodeWithText("64 MB").assertIsDisplayed()
        composeRule.onNodeWithText("1080P").assertIsDisplayed()
    }

    private fun setLibrary(mode: LibraryViewMode) {
        composeRule.setContent {
            YingLiTheme(darkTheme = false) {
                LibraryScreen(
                    state = LibraryUiState(
                        loading = false,
                        items = listOf(MEDIA),
                        totalCount = 1,
                        preference = LibraryDisplayPreference(mode),
                    ),
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
                    onToggleTrash = {},
                    onRestore = {},
                    onPurge = {},
                    onMediaSelected = {},
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
