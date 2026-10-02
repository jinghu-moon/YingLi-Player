package seeyuer.yingli.player.core.designsystem.icon

import androidx.compose.ui.graphics.vector.ImageVector
import composeicons.tabler.TablerIcons
import composeicons.tabler.outline.Activity
import composeicons.tabler.outline.AlertCircle
import composeicons.tabler.outline.Aperture
import composeicons.tabler.outline.DotsVertical
import composeicons.tabler.outline.ArrowRight
import composeicons.tabler.outline.ArrowsHorizontal
import composeicons.tabler.outline.ArrowsMaximize
import composeicons.tabler.outline.ArrowsShuffle
import composeicons.tabler.outline.AspectRatio
import composeicons.tabler.outline.BrandGithub
import composeicons.tabler.outline.Brightness
import composeicons.tabler.outline.BrandSpeedtest
import composeicons.tabler.outline.Camera
import composeicons.tabler.outline.Cast
import composeicons.tabler.outline.ChevronLeft
import composeicons.tabler.outline.ChevronRight
import composeicons.tabler.outline.Check
import composeicons.tabler.outline.Crop
import composeicons.tabler.outline.Dots
import composeicons.tabler.outline.Download
import composeicons.tabler.outline.Eye
import composeicons.tabler.outline.EyeOff
import composeicons.tabler.outline.FileText
import composeicons.tabler.outline.Folder
import composeicons.tabler.outline.FlipHorizontal
import composeicons.tabler.outline.FlipVertical
import composeicons.tabler.outline.Ban
import composeicons.tabler.outline.GripVertical
import composeicons.tabler.outline.Home
import composeicons.tabler.outline.LayoutGrid
import composeicons.tabler.outline.List
import composeicons.tabler.outline.Loader
import composeicons.tabler.outline.Lock
import composeicons.tabler.outline.LockOpen
import composeicons.tabler.outline.Minus
import composeicons.tabler.outline.Minimize
import composeicons.tabler.outline.Movie
import composeicons.tabler.outline.PictureInPicture
import composeicons.tabler.outline.PlayerPause
import composeicons.tabler.outline.PlayerPlay
import composeicons.tabler.outline.PlayerSkipBack
import composeicons.tabler.outline.PlayerSkipForward
import composeicons.tabler.outline.Playlist
import composeicons.tabler.outline.Plus
import composeicons.tabler.outline.Refresh
import composeicons.tabler.outline.Repeat
import composeicons.tabler.outline.RepeatOnce
import composeicons.tabler.outline.Rotate2
import composeicons.tabler.outline.Search
import composeicons.tabler.outline.Settings
import composeicons.tabler.outline.Share
import composeicons.tabler.outline.Star
import composeicons.tabler.outline.Subtitles
import composeicons.tabler.outline.Trash
import composeicons.tabler.outline.Upload
import composeicons.tabler.outline.Volume
import composeicons.tabler.outline.X
import composeicons.tabler.filled.Star as StarFilled

enum class IconProvider {
    TABLER,
    LOCAL_VECTOR,
    MATERIAL_FALLBACK,
}

enum class YingLiIcon(
    val provider: IconProvider,
) {
    HOME(IconProvider.TABLER),
    LIBRARY(IconProvider.TABLER),
    SHORTS(IconProvider.TABLER),
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
    CHEVRON_RIGHT(IconProvider.TABLER),
    PLAY(IconProvider.TABLER),
    PAUSE(IconProvider.TABLER),
    REPLAY(IconProvider.TABLER),
    PLAY_MODE_SEQUENCE(IconProvider.LOCAL_VECTOR),
    ARROWS_SHUFFLE(IconProvider.TABLER),
    REPEAT(IconProvider.TABLER),
    REPEAT_ONCE(IconProvider.TABLER),
    PLAYLIST(IconProvider.TABLER),
    SUBTITLES(IconProvider.TABLER),
    ROTATE(IconProvider.TABLER),
    SPEED(IconProvider.TABLER),
    ASPECT_RATIO(IconProvider.TABLER),
    BRIGHTNESS(IconProvider.TABLER),
    CROP(IconProvider.TABLER),
    STRETCH(IconProvider.TABLER),
    // 镜像翻转：左右/上下成对的方向变换图标，与旋转、缩放同属画面变换一族。
    FLIP_HORIZONTAL(IconProvider.TABLER),
    FLIP_VERTICAL(IconProvider.TABLER),
    EXIT_FULLSCREEN(IconProvider.TABLER),
    SCREENSHOT(IconProvider.TABLER),
    FAVORITE(IconProvider.TABLER),
    FAVORITE_FILLED(IconProvider.TABLER),
    BLOCK(IconProvider.TABLER),
    SHARE(IconProvider.TABLER),
    DELETE(IconProvider.TABLER),
    PICTURE_IN_PICTURE(IconProvider.TABLER),
    CAST(IconProvider.TABLER),
    VOLUME(IconProvider.TABLER),
    FULLSCREEN(IconProvider.TABLER),
    SEEK_BACKWARD(IconProvider.TABLER),
    SEEK_FORWARD(IconProvider.TABLER),
    PREVIOUS(IconProvider.TABLER),
    NEXT(IconProvider.TABLER),
    CLOSE(IconProvider.TABLER),
    UNLOCK(IconProvider.TABLER),
    BACKUP_EXPORT(IconProvider.TABLER),
    BACKUP_IMPORT(IconProvider.TABLER),
    DIAGNOSTICS(IconProvider.TABLER),
    UPDATE(IconProvider.TABLER),
    MINUS(IconProvider.TABLER),
    PLUS(IconProvider.TABLER),
}

val YingLiIcon.imageVector: ImageVector
    get() = when (this) {
        YingLiIcon.HOME -> TablerIcons.Outline.Home
        YingLiIcon.LIBRARY -> TablerIcons.Outline.Movie
        YingLiIcon.SHORTS -> TablerIcons.Outline.PlayerPlay
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
        YingLiIcon.BACK -> TablerIcons.Outline.ChevronLeft
        YingLiIcon.ARROW_RIGHT -> TablerIcons.Outline.ArrowRight
        YingLiIcon.CHEVRON_RIGHT -> TablerIcons.Outline.ChevronRight
        YingLiIcon.PLAY -> TablerIcons.Outline.PlayerPlay
        YingLiIcon.PAUSE -> TablerIcons.Outline.PlayerPause
        YingLiIcon.REPLAY -> TablerIcons.Outline.Refresh
        YingLiIcon.PLAY_MODE_SEQUENCE -> YingLiLocalIcons.PlayModeSequence
        YingLiIcon.ARROWS_SHUFFLE -> TablerIcons.Outline.ArrowsShuffle
        YingLiIcon.REPEAT -> TablerIcons.Outline.Repeat
        YingLiIcon.REPEAT_ONCE -> TablerIcons.Outline.RepeatOnce
        YingLiIcon.PLAYLIST -> TablerIcons.Outline.Playlist
        YingLiIcon.SUBTITLES -> TablerIcons.Outline.Subtitles
        YingLiIcon.ROTATE -> TablerIcons.Outline.Rotate2
        YingLiIcon.SPEED -> TablerIcons.Outline.BrandSpeedtest
        // 画面比例三态：适应＝完整显示、裁剪＝裁满、拉伸＝按原始像素拉宽。
        YingLiIcon.ASPECT_RATIO -> TablerIcons.Outline.AspectRatio
        YingLiIcon.BRIGHTNESS -> TablerIcons.Outline.Brightness
        YingLiIcon.CROP -> TablerIcons.Outline.Crop
        YingLiIcon.STRETCH -> TablerIcons.Outline.ArrowsHorizontal
        // 镜像翻转与旋转/缩放同属"画面方向变换"：用 Tabler 的翻转字形，左右与上下各一个。
        YingLiIcon.FLIP_HORIZONTAL -> TablerIcons.Outline.FlipHorizontal
        YingLiIcon.FLIP_VERTICAL -> TablerIcons.Outline.FlipVertical
        YingLiIcon.EXIT_FULLSCREEN -> TablerIcons.Outline.Minimize
        YingLiIcon.SCREENSHOT -> TablerIcons.Outline.Aperture
        YingLiIcon.FAVORITE -> TablerIcons.Outline.Star
        YingLiIcon.FAVORITE_FILLED -> TablerIcons.Filled.StarFilled
        YingLiIcon.BLOCK -> TablerIcons.Outline.Ban
        YingLiIcon.SHARE -> TablerIcons.Outline.Share
        YingLiIcon.DELETE -> TablerIcons.Outline.Trash
        YingLiIcon.PICTURE_IN_PICTURE -> TablerIcons.Outline.PictureInPicture
        YingLiIcon.CAST -> TablerIcons.Outline.Cast
        YingLiIcon.VOLUME -> TablerIcons.Outline.Volume
        YingLiIcon.FULLSCREEN -> TablerIcons.Outline.ArrowsMaximize
        YingLiIcon.SEEK_BACKWARD -> TablerIcons.Outline.PlayerSkipBack
        YingLiIcon.SEEK_FORWARD -> TablerIcons.Outline.PlayerSkipForward
        // 上一项/下一项与"快退/快进 N 秒"共用 PlayerSkipBack/Forward 字形（图标库无 track 变体），
        // 因此靠画面按钮上的**秒数徽标**区分语义：带 "10" 的是跳秒，不带的是切上/下一项。
        YingLiIcon.PREVIOUS -> TablerIcons.Outline.PlayerSkipBack
        YingLiIcon.NEXT -> TablerIcons.Outline.PlayerSkipForward
        YingLiIcon.CLOSE -> TablerIcons.Outline.X
        YingLiIcon.UNLOCK -> TablerIcons.Outline.LockOpen
        YingLiIcon.BACKUP_EXPORT -> TablerIcons.Outline.Download
        YingLiIcon.BACKUP_IMPORT -> TablerIcons.Outline.Upload
        YingLiIcon.DIAGNOSTICS -> TablerIcons.Outline.FileText
        YingLiIcon.UPDATE -> TablerIcons.Outline.BrandGithub
        YingLiIcon.MINUS -> TablerIcons.Outline.Minus
        YingLiIcon.PLUS -> TablerIcons.Outline.Plus
    }
