package seeyuer.yingli.player.core.designsystem.tokens

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class YingLiComponentTokens(
    val minimumTouchTarget: Dp,
    val iconSize: Dp,
    val topBarHeight: Dp,
    val bottomNavigationHeight: Dp,
    val navigationRailWidth: Dp,
    val navigationItemHeight: Dp,
    val pagePadding: Dp,
    val sectionSpacing: Dp,
    val itemSpacing: Dp,
    val compactCorner: RoundedCornerShape,
    val componentCorner: RoundedCornerShape,
)

object YingLiComponentTokenDefaults {
    val Default = YingLiComponentTokens(
        minimumTouchTarget = 48.dp,
        iconSize = 24.dp,
        topBarHeight = 64.dp,
        bottomNavigationHeight = 80.dp,
        navigationRailWidth = 80.dp,
        navigationItemHeight = 64.dp,
        pagePadding = 16.dp,
        sectionSpacing = 24.dp,
        itemSpacing = 8.dp,
        compactCorner = RoundedCornerShape(4.dp),
        componentCorner = RoundedCornerShape(8.dp),
    )
}
