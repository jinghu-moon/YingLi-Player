package seeyuer.yingli.player.core.designsystem.icon

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.TablerIcons
import compose.icons.tablericons.Activity
import compose.icons.tablericons.AlertCircle
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.Check
import compose.icons.tablericons.DotsVertical
import compose.icons.tablericons.Folder
import compose.icons.tablericons.Home
import compose.icons.tablericons.LayoutGrid
import compose.icons.tablericons.List
import compose.icons.tablericons.Loader
import compose.icons.tablericons.Lock
import compose.icons.tablericons.Movie
import compose.icons.tablericons.PlayerPause
import compose.icons.tablericons.PlayerPlay
import compose.icons.tablericons.Refresh
import compose.icons.tablericons.Search
import compose.icons.tablericons.Settings

enum class IconProvider {
    TABLER,
    MATERIAL_FALLBACK,
}

enum class YingLiIcon(
    val provider: IconProvider,
) {
    HOME(IconProvider.TABLER),
    LIBRARY(IconProvider.TABLER),
    ORGANIZE(IconProvider.TABLER),
    PROCESSING(IconProvider.TABLER),
    SETTINGS(IconProvider.TABLER),
    SEARCH(IconProvider.TABLER),
    OVERFLOW(IconProvider.TABLER),
    GRID(IconProvider.TABLER),
    LIST(IconProvider.TABLER),
    SUCCESS(IconProvider.TABLER),
    WARNING(IconProvider.TABLER),
    LOADING(IconProvider.TABLER),
    LOCK(IconProvider.TABLER),
    BACK(IconProvider.TABLER),
    PLAY(IconProvider.TABLER),
    PAUSE(IconProvider.TABLER),
    REPLAY(IconProvider.TABLER),
    DYNAMIC_COLOR(IconProvider.MATERIAL_FALLBACK),
}

val YingLiIcon.imageVector: ImageVector
    get() = when (this) {
        YingLiIcon.HOME -> TablerIcons.Home
        YingLiIcon.LIBRARY -> TablerIcons.Movie
        YingLiIcon.ORGANIZE -> TablerIcons.Folder
        YingLiIcon.PROCESSING -> TablerIcons.Activity
        YingLiIcon.SETTINGS -> TablerIcons.Settings
        YingLiIcon.SEARCH -> TablerIcons.Search
        YingLiIcon.OVERFLOW -> TablerIcons.DotsVertical
        YingLiIcon.GRID -> TablerIcons.LayoutGrid
        YingLiIcon.LIST -> TablerIcons.List
        YingLiIcon.SUCCESS -> TablerIcons.Check
        YingLiIcon.WARNING -> TablerIcons.AlertCircle
        YingLiIcon.LOADING -> TablerIcons.Loader
        YingLiIcon.LOCK -> TablerIcons.Lock
        YingLiIcon.BACK -> TablerIcons.ArrowLeft
        YingLiIcon.PLAY -> TablerIcons.PlayerPlay
        YingLiIcon.PAUSE -> TablerIcons.PlayerPause
        YingLiIcon.REPLAY -> TablerIcons.Refresh
        YingLiIcon.DYNAMIC_COLOR -> Icons.Outlined.Palette
    }
