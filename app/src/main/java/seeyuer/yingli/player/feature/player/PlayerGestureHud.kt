package seeyuer.yingli.player.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.icon.imageVector
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme

/** 缩放复位/回到 1.0x 的过渡时长：手势结束后才动画，避免跟手时被动画拖慢。 */
internal const val ZOOM_TRANSITION_MILLIS = 240

/**
 * 画面手势的即时反馈（规格 §14.4 / 决策 #339）：显示图标、数值和方向，短暂出现后消退，
 * 不使用大面积彩色遮罩，避免遮挡视频内容。
 */
sealed interface PlayerGestureHud {
    /** 音量：0f..1f。 */
    data class Volume(val fraction: Float) : PlayerGestureHud

    /** 亮度：0f..1f；[isAuto] 为 true 表示"交回系统"（跟随系统亮度，调的是无覆盖值）。 */
    data class Brightness(val fraction: Float, val isAuto: Boolean = false) : PlayerGestureHud

    /** 进度预览：拖动中显示目标时间。 */
    data class Seek(val positionMillis: Long, val durationMillis: Long?) : PlayerGestureHud

    /** 自由缩放倍率。 */
    data class Zoom(val scale: Float) : PlayerGestureHud

    companion object {
        /**
         * 手势停止变化后 HUD 的停留时间。取 1000ms 对齐 NextPlayer
         * （松手后先停留再淡出，用户来得及看清数值）；比控件自动隐藏（3s）短。
         */
        const val HUD_LINGER_MILLIS = 1_000L
    }
}

/**
 * 画面手势的即时反馈条（规格 FR-PLAYER-004 / 决策 #339）：图标 + 数值 + 方向提示，
 * 短暂出现后消退，**不使用大面积彩色遮罩**，因此只画一条很窄的浮岛。
 *
 * 音量贴左侧、亮度贴右侧（跟手势所在侧一致），进度与缩放居中，避免用户还要"猜哪边动了"。
 */
@Composable
internal fun PlayerGestureHudOverlay(
    hud: PlayerGestureHud,
    visible: Boolean,
    modifier: Modifier = Modifier,
    /** 点亮度条顶部图标切换"自动/手动"；为 null 时图标不可点。 */
    onToggleAutoBrightness: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        // 调用方保证退出动画期间传入的仍是最后一个非空值：
        // 之前这里用 "?: Volume(0f)" 顶替，会在屏幕中央闪出一条音量条。
        val current = hud
        when (current) {
            // 音量/亮度用竖向条：手势本身就是竖向的，条的方向与手势一致更直观。
            is PlayerGestureHud.Volume -> HudBar(
                icon = YingLiIcon.VOLUME,
                label = percentLabel(current.fraction),
                progress = current.fraction,
            )
            is PlayerGestureHud.Brightness -> HudBar(
                icon = YingLiIcon.BRIGHTNESS,
                // 自动状态显示"自动"而不是百分比：此时数值是系统亮度，不是本页手动值。
                label = if (current.isAuto) "自动" else percentLabel(current.fraction),
                progress = current.fraction,
                onIconClick = onToggleAutoBrightness,
            )
            is PlayerGestureHud.Seek -> HudPill(
                icon = YingLiIcon.SEEK_FORWARD,
                label = "${formatGestureDuration(current.positionMillis)} / ${formatGestureDuration(current.durationMillis)}",
                progress = current.durationMillis
                    ?.takeIf { it > 0 }
                    ?.let { (current.positionMillis.toFloat() / it).coerceIn(0f, 1f) },
            )
            is PlayerGestureHud.Zoom -> HudPill(
                icon = YingLiIcon.FULLSCREEN,
                label = "%.1fx".format(current.scale),
                progress = null,
            )
        }
    }
}

/** 竖向音量/亮度条：图标在上、竖向轨道（从下往上填充）、数值在下，贴合竖向手势。 */
@Composable
private fun HudBar(
    icon: YingLiIcon,
    label: String,
    progress: Float,
    onIconClick: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = YingLiTheme.player.edgeScrim,
        contentColor = YingLiTheme.player.controlPrimary,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 图标本身就是"自动/手动"的切换按钮：40dp 触控区，图标 20dp 视觉。
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .then(
                        if (onIconClick == null) {
                            Modifier
                        } else {
                            Modifier.clickable(onClick = onIconClick)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon.imageVector,
                    contentDescription = if (onIconClick == null) null else "$label，点击切换自动",
                    modifier = Modifier.size(20.dp),
                )
            }
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(HUD_BAR_HEIGHT)
                    .clip(CircleShape)
                    .background(YingLiTheme.player.controlPrimary.copy(alpha = 0.25f)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(HUD_BAR_HEIGHT * progress.coerceIn(0f, 1f))
                        .clip(CircleShape)
                        .background(YingLiTheme.player.controlPrimary),
                )
            }
            Text(text = label, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
        }
    }
}

/**
 * 长按临时倍速的可见反馈：只画一条窄胶囊，明确告诉用户"现在正在快进"，
 * 松手即消失（否则用户完全看不出长按生效了）。
 */
@Composable
internal fun PlayerSpeedBoostBadge(
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = YingLiTheme.player.edgeScrim,
        contentColor = YingLiTheme.player.controlPrimary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(YingLiIcon.SEEK_FORWARD.imageVector, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = "长按快进中 $label",
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            )
        }
    }
}

private val HUD_BAR_HEIGHT = 120.dp

/** 手势反馈浮岛（进度/缩放）：48dp 高、圆角胶囊，深色半透明底 + 细进度条。 */
@Composable
private fun HudPill(
    icon: YingLiIcon,
    label: String,
    progress: Float?,
) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = YingLiTheme.player.edgeScrim,
        contentColor = YingLiTheme.player.controlPrimary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon.imageVector, contentDescription = null, modifier = Modifier.size(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFeatureSettings = "tnum",
                    ),
                )
                if (progress != null) {
                    Box(
                        modifier = Modifier
                            .width(96.dp)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(YingLiTheme.player.controlPrimary.copy(alpha = 0.25f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(96.dp * progress.coerceIn(0f, 1f))
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(YingLiTheme.player.controlPrimary),
                        )
                    }
                }
            }
        }
    }
}

private fun percentLabel(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%"

private fun formatGestureDuration(millis: Long?): String {
    if (millis == null || millis < 0) return "--:--"
    val totalSeconds = millis / 1_000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
