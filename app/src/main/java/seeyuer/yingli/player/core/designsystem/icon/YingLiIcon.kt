package seeyuer.yingli.player.core.designsystem.icon

import androidx.compose.ui.graphics.vector.ImageVector
import composeicons.tabler.TablerIcons
import composeicons.tabler.outline.Activity
import composeicons.tabler.outline.AlertCircle
import composeicons.tabler.outline.ArrowLeft
import composeicons.tabler.outline.ArrowRight
import composeicons.tabler.outline.BrandGithub
import composeicons.tabler.outline.Camera
import composeicons.tabler.outline.Check
import composeicons.tabler.outline.Dots
import composeicons.tabler.outline.DotsVertical
import composeicons.tabler.outline.Download
import composeicons.tabler.outline.Eye
import composeicons.tabler.outline.EyeOff
import composeicons.tabler.outline.FileText
import composeicons.tabler.outline.Folder
import composeicons.tabler.outline.GripVertical
import composeicons.tabler.outline.Home
import composeicons.tabler.outline.LayoutGrid
import composeicons.tabler.outline.List
import composeicons.tabler.outline.Loader
import composeicons.tabler.outline.Lock
import composeicons.tabler.outline.LockOpen
import composeicons.tabler.outline.Movie
import composeicons.tabler.outline.PictureInPicture
import composeicons.tabler.outline.PlayerPause
import composeicons.tabler.outline.PlayerPlay
import composeicons.tabler.outline.PlayerSkipBack
import composeicons.tabler.outline.PlayerSkipForward
import composeicons.tabler.outline.Refresh
import composeicons.tabler.outline.Search
import composeicons.tabler.outline.Settings
import composeicons.tabler.outline.Upload

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
    BREADCRUMB_OVERFLOW(IconProvider.TABLER),
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
        YingLiIcon.HOME -> TablerIcons.Outline.Home
        YingLiIcon.LIBRARY -> TablerIcons.Outline.Movie
        YingLiIcon.ORGANIZE -> TablerIcons.Outline.Folder
        YingLiIcon.PROCESSING -> TablerIcons.Outline.Activity
        YingLiIcon.SETTINGS -> TablerIcons.Outline.Settings
        YingLiIcon.SEARCH -> TablerIcons.Outline.Search
        YingLiIcon.OVERFLOW -> TablerIcons.Outline.DotsVertical
        YingLiIcon.BREADCRUMB_OVERFLOW -> TablerIcons.Outline.Dots
        YingLiIcon.GRID -> TablerIcons.Outline.LayoutGrid
        YingLiIcon.LIST -> TablerIcons.Outline.List
        YingLiIcon.DRAG_HANDLE -> TablerIcons.Outline.GripVertical
        YingLiIcon.SUCCESS -> TablerIcons.Outline.Check
        YingLiIcon.WARNING -> TablerIcons.Outline.AlertCircle
        YingLiIcon.VISIBILITY -> TablerIcons.Outline.Eye
        YingLiIcon.VISIBILITY_OFF -> TablerIcons.Outline.EyeOff
        YingLiIcon.LOADING -> TablerIcons.Outline.Loader
        YingLiIcon.LOCK -> TablerIcons.Outline.Lock
        YingLiIcon.BACK -> TablerIcons.Outline.ArrowLeft
        YingLiIcon.ARROW_RIGHT -> TablerIcons.Outline.ArrowRight
        YingLiIcon.PLAY -> TablerIcons.Outline.PlayerPlay
        YingLiIcon.PAUSE -> TablerIcons.Outline.PlayerPause
        YingLiIcon.REPLAY -> TablerIcons.Outline.Refresh
        YingLiIcon.SCREENSHOT -> TablerIcons.Outline.Camera
        YingLiIcon.PICTURE_IN_PICTURE -> TablerIcons.Outline.PictureInPicture
        YingLiIcon.SEEK_BACKWARD -> TablerIcons.Outline.PlayerSkipBack
        YingLiIcon.SEEK_FORWARD -> TablerIcons.Outline.PlayerSkipForward
        YingLiIcon.UNLOCK -> TablerIcons.Outline.LockOpen
        YingLiIcon.BACKUP_EXPORT -> TablerIcons.Outline.Download
        YingLiIcon.BACKUP_IMPORT -> TablerIcons.Outline.Upload
        YingLiIcon.DIAGNOSTICS -> TablerIcons.Outline.FileText
        YingLiIcon.UPDATE -> TablerIcons.Outline.BrandGithub
    }
