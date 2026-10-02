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
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerChromeIconButton(
            icon = YingLiIcon.BACK,
            contentDescription = stringResource(R.string.action_back),
            onClick = onBack,
        )
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.weight(1f).padding(start = 12.dp, end = 8.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
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
        color = if (filled) YingLiTheme.player.controlPrimary else YingLiTheme.player.controlPrimary.copy(alpha = 0.10f),
        contentColor = if (filled) YingLiTheme.player.canvas else tint,
        border = if (filled) null else BorderStroke(1.dp, YingLiTheme.player.controlPrimary.copy(alpha = 0.12f)),
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

private fun PlayerUiState.playerSubtitle(): String? {
    val info = mediaInfo ?: return null
    return listOfNotNull(
        if (info.width != null && info.height != null) "${info.width} × ${info.height}" else null,
        info.videoCodec?.uppercase(),
        info.frameRate?.let { "${it.toInt()} fps" },
    ).takeIf(List<String>::isNotEmpty)?.joinToString(" · ")
}
