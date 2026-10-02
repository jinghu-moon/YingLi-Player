package seeyuer.yingli.player.core.designsystem.icon

import androidx.compose.ui.graphics.vector.ImageVector
import composeicons.tabler.TablerIcons
import composeicons.tabler.outline.Activity
import composeicons.tabler.outline.AlertCircle
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
import composeicons.tabler.outline.Ban
import composeicons.tabler.outline.GripVertical
import composeicons.tabler.outline.Headphones
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
import composeicons.tabler.filled.Camera as CameraFilled
import composeicons.tabler.filled.PlayerSkipBack as PlayerSkipBackFilled
import composeicons.tabler.filled.PlayerSkipForward as PlayerSkipForwardFilled
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
    FLIP_HORIZONTAL(IconProvider.LOCAL_VECTOR),
    FLIP_VERTICAL(IconProvider.LOCAL_VECTOR),
    EXIT_FULLSCREEN(IconProvider.TABLER),
    /** 截图**入口**（进入截图模式）：设计稿 §2.3 的 `ti-camera`，线性。 */
    SCREENSHOT(IconProvider.TABLER),
    /** 截图胶囊中间的**捕获按钮**：设计稿 §2.3 的 `ti-camera-filled`，实心。 */
    SCREENSHOT_CAPTURE(IconProvider.TABLER),
    FAVORITE(IconProvider.TABLER),
    FAVORITE_FILLED(IconProvider.TABLER),
    BLOCK(IconProvider.TABLER),
    SHARE(IconProvider.TABLER),
    DELETE(IconProvider.TABLER),
    PICTURE_IN_PICTURE(IconProvider.TABLER),
    /** 后台播放：耳机字形表达"画面看不见了，声音还在继续"。 */
    BACKGROUND_PLAYBACK(IconProvider.TABLER),
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
        // 镜像翻转与旋转/缩放同属"画面方向变换"：字形以设计资产 design/assets/icons/flip-*.svg 为准，
        // 逐路径移植，避免图标库版本漂移改变已定稿的字形。
        YingLiIcon.FLIP_HORIZONTAL -> YingLiCustomIcons.FlipHorizontal
        YingLiIcon.FLIP_VERTICAL -> YingLiCustomIcons.FlipVertical
        YingLiIcon.EXIT_FULLSCREEN -> TablerIcons.Outline.Minimize
        // 设计稿 §2.3：截图入口 = `ti-camera`（线性），捕获按钮 = `ti-camera-filled`（实心）。
        YingLiIcon.SCREENSHOT -> TablerIcons.Outline.Camera
        YingLiIcon.SCREENSHOT_CAPTURE -> TablerIcons.Filled.CameraFilled
        YingLiIcon.FAVORITE -> TablerIcons.Outline.Star
        YingLiIcon.FAVORITE_FILLED -> TablerIcons.Filled.StarFilled
        YingLiIcon.BLOCK -> TablerIcons.Outline.Ban
        YingLiIcon.SHARE -> TablerIcons.Outline.Share
        YingLiIcon.DELETE -> TablerIcons.Outline.Trash
        YingLiIcon.PICTURE_IN_PICTURE -> TablerIcons.Outline.PictureInPicture
        YingLiIcon.BACKGROUND_PLAYBACK -> TablerIcons.Outline.Headphones
        YingLiIcon.CAST -> TablerIcons.Outline.Cast
        YingLiIcon.VOLUME -> TablerIcons.Outline.Volume
        YingLiIcon.FULLSCREEN -> TablerIcons.Outline.ArrowsMaximize
        // 逐帧步进用**无填充**的 skip 字形，刻意区别于"上一项/下一项"（设计稿 §2.3）。
        YingLiIcon.SEEK_BACKWARD -> TablerIcons.Outline.PlayerSkipBack
        YingLiIcon.SEEK_FORWARD -> TablerIcons.Outline.PlayerSkipForward
        // 上一项/下一项用**实心**的 skip 字形：图标库里没有 track 变体，靠填充与否把
        // "切上/下一个视频"和截图工具里的"上一帧/下一帧"区分开（设计稿 §2.3 的口径）。
        YingLiIcon.PREVIOUS -> TablerIcons.Filled.PlayerSkipBackFilled
        YingLiIcon.NEXT -> TablerIcons.Filled.PlayerSkipForwardFilled
        YingLiIcon.CLOSE -> TablerIcons.Outline.X
        YingLiIcon.UNLOCK -> TablerIcons.Outline.LockOpen
        YingLiIcon.BACKUP_EXPORT -> TablerIcons.Outline.Download
        YingLiIcon.BACKUP_IMPORT -> TablerIcons.Outline.Upload
        YingLiIcon.DIAGNOSTICS -> TablerIcons.Outline.FileText
        YingLiIcon.UPDATE -> TablerIcons.Outline.BrandGithub
        YingLiIcon.MINUS -> TablerIcons.Outline.Minus
        YingLiIcon.PLUS -> TablerIcons.Outline.Plus
    }
