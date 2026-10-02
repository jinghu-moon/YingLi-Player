package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.icon.imageVector
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.playback.PlaybackOrder
import seeyuer.yingli.player.domain.playback.PlayerControlId
import seeyuer.yingli.player.domain.playback.PlayerControlLayout
import seeyuer.yingli.player.domain.playback.PlayerControlSurface
import seeyuer.yingli.player.domain.playback.ScreenshotUiState

@Composable
internal fun PlayerTopBar(
    state: PlayerUiState,
    onBack: () -> Unit,
    onScreenshot: () -> Unit,
    onPictureInPicture: () -> Unit,
    onOpenPlaylist: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenVideoInfo: () -> Unit,
    onOpenAbTool: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleLock: () -> Unit,
    onSetPlaybackOrder: (PlaybackOrder) -> Unit,
    onCycleScaleMode: () -> Unit,
    controlLayout: PlayerControlLayout,
    landscape: Boolean,
    allowScreenshot: Boolean,
    allowPictureInPicture: Boolean,
    modifier: Modifier,
) {
    if (!state.overlay.controlsVisible) return
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(
                start = PlayerTopBarStartPadding,
                end = PlayerTopBarEndPadding,
                top = PlayerTopBarVerticalPadding,
                bottom = PlayerTopBarVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerChromeIconButton(
            icon = YingLiIcon.BACK,
            contentDescription = stringResource(R.string.action_back),
            onClick = onBack,
        )
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.weight(1f).padding(
                start = PlayerTopBarTitleStartPadding,
                end = PlayerTopBarTitleEndPadding,
            ),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            // 截图模式下标题不再让位：帧数胶囊已经下移到顶栏下方（见 PlayerScreen 里
            // 以 PlayerTopBarContentHeight 算出的顶部内边距），与标题不再争同一个槽位，两者可同时在场。
            Text(
                text = state.title.ifBlank { "未知视频" },
                color = YingLiTheme.player.controlPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
            state.playerSubtitle()?.let { subtitle ->
                Text(
                    text = subtitle,
                    color = YingLiTheme.player.controlSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
        }
        controlLayout.controls(PlayerControlSurface.LANDSCAPE_TOP_RIGHT)
            .filterNot { !landscape && it in setOf(PlayerControlId.AUDIO, PlayerControlId.SUBTITLE) }
            .forEach { id ->
                val action = when (id) {
                    PlayerControlId.PLAYLIST -> onOpenPlaylist
                    PlayerControlId.AUDIO, PlayerControlId.SUBTITLE -> onOpenSettings
                    PlayerControlId.SCALE -> onCycleScaleMode
                    PlayerControlId.INFO -> onOpenVideoInfo
                    PlayerControlId.SCREENSHOT -> onScreenshot
                    PlayerControlId.AB_LOOP -> onOpenAbTool
                    PlayerControlId.PIP -> onPictureInPicture
                    PlayerControlId.LOCK -> onToggleLock
                    PlayerControlId.SETTINGS -> onOpenSettings
                    PlayerControlId.PREVIOUS -> onPrevious
                    PlayerControlId.NEXT -> onNext
                    else -> onOpenSettings
                }
                val icon = when (id) {
                    PlayerControlId.PLAYLIST -> YingLiIcon.PLAYLIST
                    PlayerControlId.AUDIO -> YingLiIcon.VOLUME
                    PlayerControlId.SUBTITLE -> YingLiIcon.SUBTITLES
                    PlayerControlId.SCALE -> videoScaleModeIcon(state.scaleMode)
                    PlayerControlId.INFO -> YingLiIcon.DIAGNOSTICS
                    PlayerControlId.SCREENSHOT -> YingLiIcon.SCREENSHOT
                    PlayerControlId.AB_LOOP -> YingLiIcon.REPLAY
                    PlayerControlId.PIP -> YingLiIcon.PICTURE_IN_PICTURE
                    PlayerControlId.LOCK -> YingLiIcon.LOCK
                    PlayerControlId.SETTINGS -> YingLiIcon.SETTINGS
                    PlayerControlId.PREVIOUS -> YingLiIcon.PREVIOUS
                    PlayerControlId.NEXT -> YingLiIcon.NEXT
                    else -> YingLiIcon.SETTINGS
                }
                PlayerChromeIconButton(icon, id.name, action, size = PlayerChromeButtonSize)
            }
        Box {
            PlayerChromeIconButton(
                icon = YingLiIcon.OVERFLOW,
                contentDescription = "更多播放选项",
                onClick = { menuExpanded = true },
            )
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                if (allowScreenshot) menuItem("保存当前帧", onScreenshot) { menuExpanded = false }
                if (allowPictureInPicture) menuItem("画中画", onPictureInPicture) { menuExpanded = false }
                menuItem("播放列表", onOpenPlaylist) { menuExpanded = false }
                menuItem("播放设置", onOpenSettings) { menuExpanded = false }
                menuItem("视频信息", onOpenVideoInfo) { menuExpanded = false }
                menuItem("A-B 循环", onOpenAbTool) { menuExpanded = false }
                menuItem("上一项", onPrevious) { menuExpanded = false }
                menuItem("下一项", onNext) { menuExpanded = false }
                menuItem(if (state.overlay.locked) "解锁屏幕" else "锁定屏幕", onToggleLock) { menuExpanded = false }
                PlaybackOrder.entries.forEach { order ->
                    val orderLabel = stringResource(playbackOrderLabelRes(order))
                    menuItem(
                        stringResource(R.string.player_order_state, orderLabel),
                        { onSetPlaybackOrder(order) },
                    ) { menuExpanded = false }
                }
            }
        }
    }
}

/**
 * 顶栏与底栏共用的圆形按钮尺寸。两处必须引用同一常量，
 * 否则同一屏上的按钮圆径不一致。
 */
internal val PlayerChromeButtonSize = 48.dp

/**
 * 顶栏的水平内边距与标题槽两侧留白（都是渲染顶栏本身用的字面量）。
 *
 * 之所以抽成常量而不是留在 `padding(...)` 里：顶栏高度（[PlayerTopBarContentHeight]）要由这些
 * 数字算出来给帧数胶囊定位，写死字面量就会变成"改一处忘一处"，胶囊又会贴回顶栏按钮上。
 */
private val PlayerTopBarStartPadding = 16.dp
private val PlayerTopBarEndPadding = 12.dp

/** 顶栏上下内边距：与按钮尺寸一起决定顶栏的占用高度。 */
private val PlayerTopBarVerticalPadding = 8.dp

/** 标题槽与左侧返回按钮、右侧快捷按钮之间的留白。 */
private val PlayerTopBarTitleStartPadding = 12.dp
private val PlayerTopBarTitleEndPadding = 8.dp

/**
 * 帧数胶囊贴在顶栏下方时，与顶栏底边之间留出的间距（下移量 = 顶栏高度 + 它）。
 *
 * 取 12dp：与项目里既有的 8/12/16dp 一档间距一致（[PlayerShortcutSpacing] 8dp、
 * 顶栏右侧内边距 12dp）。这个间距同时也是"胶囊与顶栏按钮彻底分开"的视觉保险。
 */
internal val PlayerFrameCounterTopGap = 12.dp

/**
 * 顶栏**自身**占用的高度（不含状态栏内边距）：上下内边距 + 一枚快捷按钮的圆径。
 *
 * 顶栏是一个 `Row`，行高由最高子项决定，而子项里最高的就是 [PlayerChromeButtonSize] 的圆按钮
 * （标题那段是两行文本，在竖屏标题较短时也不会超过 48dp 的按钮）。所以这里就是顶栏的真实高度，
 * 而不是估出来的数字。
 */
internal val PlayerTopBarContentHeight = PlayerTopBarVerticalPadding * 2 + PlayerChromeButtonSize

/**
 * 底栏按钮的材质（底色 / 描边 / 形状）。截图胶囊与帧数胶囊必须与底栏按钮**同源**：
 * 它们与按钮出现在同一屏上，另造一套视觉会立刻看出色差与圆角不一致，
 * 因此这里抽出唯一一份定义，按钮与胶囊都只引用它，不再各写 alpha 字面量。
 */
internal val PlayerChromeControlFillAlpha = 0.10f

/** 与底栏按钮同源的细描边宽度与透明度。 */
internal val PlayerChromeControlBorderWidth = 1.dp
internal val PlayerChromeControlBorderAlpha = 0.12f

/** 与底栏按钮同源的圆角：全圆（胶囊形），对应设计稿 `border-radius: 999px`。 */
internal val PlayerChromeCapsuleShape = CircleShape

@Composable
internal fun PlayerChromeIconButton(
    icon: YingLiIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: androidx.compose.ui.unit.Dp = PlayerChromeButtonSize,
    tint: Color = YingLiTheme.player.controlPrimary,
    filled: Boolean = false,
    /** 非空时按钮内显示这段短文本而不是图标（例如倍速数值）。 */
    valueLabel: String? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(size),
        enabled = enabled,
        shape = CircleShape,
        color = if (filled) {
            YingLiTheme.player.controlPrimary
        } else {
            YingLiTheme.player.controlPrimary.copy(alpha = PlayerChromeControlFillAlpha)
        },
        contentColor = if (filled) YingLiTheme.player.canvas else tint,
        border = if (filled) {
            null
        } else {
            BorderStroke(PlayerChromeControlBorderWidth, YingLiTheme.player.controlPrimary.copy(alpha = PlayerChromeControlBorderAlpha))
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (valueLabel != null) {
                Text(
                    text = valueLabel,
                    color = if (filled) YingLiTheme.player.canvas else tint,
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    modifier = Modifier.semantics { this.contentDescription = contentDescription },
                )
            } else {
                Icon(
                    imageVector = icon.imageVector,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(if (size >= 64.dp) 30.dp else 22.dp),
                    tint = if (filled) YingLiTheme.player.canvas else tint,
                )
            }
        }
    }
}

@Composable
internal fun PlayerUnlockButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    // 图标跟状态（锁定态显示闭合锁），文案跟动作（点击即解锁）。
    PlayerChromeIconButton(YingLiIcon.LOCK, stringResource(R.string.player_unlock), onClick, modifier)
}

/**
 * 锁定态保留的控件：**播放/暂停 + 解锁**（需求文档 FR-PLAYER-003 / 决策 #394）。
 * 二者都只在"单击唤出"的窗口内显示、随后随控件一起自动隐藏，因此锁定仍然是防误触的：
 * 想暂停需要先唤出、再点击，不会因为误碰一下就改变播放状态。
 */
@Composable
internal fun PlayerLockedControls(
    isPlaying: Boolean,
    onTogglePlayback: () -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(PlayerShortcutSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerChromeIconButton(
            icon = if (isPlaying) YingLiIcon.PAUSE else YingLiIcon.PLAY,
            contentDescription = if (isPlaying) "暂停" else "播放",
            onClick = onTogglePlayback,
        )
        PlayerUnlockButton(onClick = onUnlock)
    }
}

@Composable
private fun menuItem(
    label: String,
    action: () -> Unit,
    dismiss: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = {
            dismiss()
            action()
        },
    )
}

/**
 * 截图工具是否正在**占用**播放页（Armed / Capturing / Preview 三段）。
 *
 * 这三段的界面差别只是胶囊内容，对「底栏三段要不要收起让位、帧数胶囊要不要在场」而言是同一件事，
 * 所以判定集中在这里，避免各调用点各写一遍状态枚举、日后新增状态时漏改一处。
 */
internal fun PlayerUiState.isScreenshotToolActive(): Boolean = when (screenshot) {
    ScreenshotUiState.Armed, ScreenshotUiState.Capturing -> true
    is ScreenshotUiState.Preview -> true
    else -> false
}

private fun PlayerUiState.playerSubtitle(): String? {
    val info = mediaInfo ?: return null
    return listOfNotNull(
        if (info.width != null && info.height != null) "${info.width} × ${info.height}" else null,
        info.videoCodec?.uppercase(),
        info.frameRate?.let { "${it.toInt()} fps" },
    ).takeIf(List<String>::isNotEmpty)?.joinToString(" · ")
}
