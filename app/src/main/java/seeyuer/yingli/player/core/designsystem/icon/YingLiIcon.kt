package seeyuer.yingli.player.core.designsystem.icon

import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.TablerIcons
import compose.icons.tablericons.Activity
import compose.icons.tablericons.AlertCircle
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.ArrowRight
import compose.icons.tablericons.Check
import compose.icons.tablericons.DotsVertical
import compose.icons.tablericons.Folder
import compose.icons.tablericons.Home
import compose.icons.tablericons.GripVertical
import compose.icons.tablericons.LayoutGrid
import compose.icons.tablericons.List
import compose.icons.tablericons.Loader
import compose.icons.tablericons.Lock
import compose.icons.tablericons.Movie
import compose.icons.tablericons.PlayerPause
import compose.icons.tablericons.PlayerPlay
import compose.icons.tablericons.Refresh
import compose.icons.tablericons.Camera
import compose.icons.tablericons.PictureInPicture
import compose.icons.tablericons.PlayerSkipBack
import compose.icons.tablericons.PlayerSkipForward
import compose.icons.tablericons.LockOpen
import compose.icons.tablericons.Search
import compose.icons.tablericons.Settings
import compose.icons.tablericons.Download
import compose.icons.tablericons.Upload
import compose.icons.tablericons.FileText
import compose.icons.tablericons.BrandGithub
import compose.icons.tablericons.Eye
import compose.icons.tablericons.EyeOff

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
    DRAG_HANDLE(IconProvider.TABLER),
    SUCCESS(IconProvider.TABLER),
    WARNING(IconProvider.TABLER),
    VISIBILITY(IconProvider.TABLER),
    VISIBILITY_OFF(IconProvider.TABLER),
    LOADING(IconProvider.TABLER),
    LOCK(IconProvider.TABLER),
    BACK(IconProvider.TABLER),
    ARROW_RIGHT(IconProvider.TABLER),
    PLAY(IconProvider.TABLER),
    PAUSE(IconProvider.TABLER),
    REPLAY(IconProvider.TABLER),
    SCREENSHOT(IconProvider.TABLER),
    PICTURE_IN_PICTURE(IconProvider.TABLER),
    SEEK_BACKWARD(IconProvider.TABLER),
    SEEK_FORWARD(IconProvider.TABLER),
    UNLOCK(IconProvider.TABLER),
    BACKUP_EXPORT(IconProvider.TABLER),
    BACKUP_IMPORT(IconProvider.TABLER),
    DIAGNOSTICS(IconProvider.TABLER),
    UPDATE(IconProvider.TABLER),
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
        YingLiIcon.DRAG_HANDLE -> TablerIcons.GripVertical
        YingLiIcon.SUCCESS -> TablerIcons.Check
        YingLiIcon.WARNING -> TablerIcons.AlertCircle
        YingLiIcon.VISIBILITY -> TablerIcons.Eye
        YingLiIcon.VISIBILITY_OFF -> TablerIcons.EyeOff
        YingLiIcon.LOADING -> TablerIcons.Loader
        YingLiIcon.LOCK -> TablerIcons.Lock
        YingLiIcon.BACK -> TablerIcons.ArrowLeft
        YingLiIcon.ARROW_RIGHT -> TablerIcons.ArrowRight
        YingLiIcon.PLAY -> TablerIcons.PlayerPlay
        YingLiIcon.PAUSE -> TablerIcons.PlayerPause
        YingLiIcon.REPLAY -> TablerIcons.Refresh
        YingLiIcon.SCREENSHOT -> TablerIcons.Camera
        YingLiIcon.PICTURE_IN_PICTURE -> TablerIcons.PictureInPicture
        YingLiIcon.SEEK_BACKWARD -> TablerIcons.PlayerSkipBack
        YingLiIcon.SEEK_FORWARD -> TablerIcons.PlayerSkipForward
        YingLiIcon.UNLOCK -> TablerIcons.LockOpen
        YingLiIcon.BACKUP_EXPORT -> TablerIcons.Download
        YingLiIcon.BACKUP_IMPORT -> TablerIcons.Upload
        YingLiIcon.DIAGNOSTICS -> TablerIcons.FileText
        YingLiIcon.UPDATE -> TablerIcons.BrandGithub
    }
