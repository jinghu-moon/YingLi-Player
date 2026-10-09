package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.component.YingLiButton
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.clips.ClipExportMode

/**
 * AB 区间导出的**二选一**弹层（设计稿 §14.6 步骤 12 / §9.4）。
 *
 * ## 为什么不预设默认
 *
 * 快速（无损复制）与精确（重新编码）不是"质量档位"，而是**两种不同的产物**：
 * 快速只把已有样本搬进新容器（起点会对齐到前一个关键帧，因此实际起点 ≤ A），
 * 精确要重编码（精确到帧，慢且必然有一次质量损失）。用户按下的那一秒里，
 * 这两种产物的期望画面并不一样 —— 所以弹层必须当场问，而**不能**替他决定
 *（参考实现 `refer/REX-Player-master/.../ClipExportSheet.kt` 也是两张并列选项卡）。
 *
 * [defaultMode] 只是**预选高亮**，不等于已经导出：真正的提交是 footer 的「导出这段」。
 * 唯一的例外是 [fastUnavailable] —— 那时"快速"这个选项根本不存在，它被移出候选集
 * 并把预选切到精确，同时把理由写在卡片下方（§14.6 步骤 13：不支持时默认切精确模式**并给出理由**）。
 *
 * ## 区间读数是 `mm:ss.SSS`
 *
 * 与播放页其它时间读数（[formatDuration]，`mm:ss`）不同，这里刻意保留毫秒：
 * 用户此刻正在判断"我圈的这一段"对不对，而这一段的价值恰恰在两端那几帧上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AbExportSheet(
    startMillis: Long,
    endMillis: Long,
    defaultMode: ClipExportMode,
    fastUnavailable: Boolean,
    onDismiss: () -> Unit,
    onSelectMode: (ClipExportMode) -> Unit,
) {
    var selected by remember(startMillis, endMillis, defaultMode) { mutableStateOf(defaultMode) }
    // 快速不可行时把选择切到精确：这不是"替用户选"，而是把**不存在的选项**从候选集里去掉。
    LaunchedEffect(fastUnavailable) {
        if (fastUnavailable) selected = ClipExportMode.ACCURATE
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = YingLiTheme.player.canvas,
        contentColor = YingLiTheme.player.controlPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = YingLiTheme.player.controlSecondary) },
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.player_ab_export_title),
                color = YingLiTheme.player.controlPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.W700,
            )
            Spacer(Modifier.height(10.dp))
            AbExportRangeRow(startMillis = startMillis, endMillis = endMillis)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExportModeCard(
                    title = stringResource(R.string.player_ab_export_fast),
                    description = stringResource(R.string.player_ab_export_fast_description),
                    selected = selected == ClipExportMode.FAST,
                    enabled = !fastUnavailable,
                    onClick = { selected = ClipExportMode.FAST },
                    modifier = Modifier.weight(1f),
                )
                ExportModeCard(
                    title = stringResource(R.string.player_ab_export_accurate),
                    description = stringResource(R.string.player_ab_export_accurate_description),
                    selected = selected == ClipExportMode.ACCURATE,
                    enabled = true,
                    onClick = { selected = ClipExportMode.ACCURATE },
                    modifier = Modifier.weight(1f),
                )
            }
            if (fastUnavailable) {
                Spacer(Modifier.height(10.dp))
                // 理由必须挨着被禁用的那张卡：否则用户只看到"灰色"，不知道为什么。
                Text(
                    text = stringResource(R.string.player_ab_export_fast_unavailable),
                    color = YingLiTheme.player.controlSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = YingLiTheme.player.controlSecondary),
                ) {
                    Text(stringResource(R.string.player_ab_export_cancel))
                }
                Spacer(Modifier.weight(1f))
                YingLiButton(
                    text = stringResource(R.string.player_ab_export),
                    onClick = { onSelectMode(selected) },
                )
            }
        }
    }
}

/** 区间行：`00:06.123 → 00:09.480` + 时长胶囊（`Δ 00:03.357`）。 */
@Composable
private fun AbExportRangeRow(
    startMillis: Long,
    endMillis: Long,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(
                R.string.player_ab_export_range,
                formatAbExportMark(startMillis),
                formatAbExportMark(endMillis),
            ),
            color = YingLiTheme.player.controlPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.W600,
        )
        Spacer(Modifier.width(10.dp))
        Surface(
            shape = CircleShape,
            color = YingLiTheme.player.controlPrimary.copy(alpha = PlayerChromeControlFillAlpha),
            border = BorderStroke(
                PlayerChromeControlBorderWidth,
                YingLiTheme.player.controlPrimary.copy(alpha = PlayerChromeControlBorderAlpha),
            ),
        ) {
            Text(
                text = "${stringResource(R.string.player_ab_delta)} ${formatAbExportMark(endMillis - startMillis)}",
                color = YingLiTheme.player.controlSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * 一张模式卡：**整卡可点**（material3 的可点击 `Surface` 自带按钮语义），选中 = 实心 + 主色内容。
 *
 * 材质与底栏按钮**同源**：复用 [PlayerChromeControlFillAlpha] / [PlayerChromeControlBorderAlpha]
 * / [PlayerChromeControlBorderWidth] 这三个既有常量，不另写一套 alpha 字面量
 *（两处各写一遍 alpha 是历史上"同屏控件看出色差"的来源）。
 */
@Composable
private fun ExportModeCard(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = AbExportCardMinHeight),
        shape = RoundedCornerShape(AbExportCardCorner),
        color = if (selected) {
            YingLiTheme.player.controlPrimary
        } else {
            YingLiTheme.player.controlPrimary.copy(alpha = PlayerChromeControlFillAlpha)
        },
        contentColor = if (selected) YingLiTheme.player.canvas else YingLiTheme.player.controlPrimary,
        border = if (selected) {
            null
        } else {
            BorderStroke(
                PlayerChromeControlBorderWidth,
                YingLiTheme.player.controlPrimary.copy(alpha = PlayerChromeControlBorderAlpha),
            )
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.W700)
            Spacer(Modifier.height(6.dp))
            Text(
                text = description,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = if (selected) {
                    YingLiTheme.player.canvas.copy(alpha = 0.78f)
                } else {
                    YingLiTheme.player.controlSecondary
                },
            )
        }
    }
}

/**
 * 端点读数（`mm:ss.SSS`）。
 *
 * 分档规则与 [formatDuration] **刻意一致**（不足 1 小时用 `mm:ss`，1 小时起 `hh:mm:ss` 且小时补零），
 * 只多出毫秒三位 —— 同一屏内两套分档规则会让 `01:35:00` 和 `95:00.000` 看起来像两个 App。
 */
internal fun formatAbExportMark(millis: Long): String {
    val clamped = millis.coerceAtLeast(0L)
    val totalSeconds = clamped / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    val fraction = clamped % 1_000
    return if (hours > 0) {
        "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, fraction)
    } else {
        "%02d:%02d.%03d".format(minutes, seconds, fraction)
    }
}

/** 模式卡圆角：与首页/整理页卡片一族一致（16dp），不是胶囊（胶囊留给一排圆钮）。 */
private val AbExportCardCorner = 16.dp

/** 模式卡最小高度：两张卡必须等高，否则描述长短会把它们拉成不同高度。 */
private val AbExportCardMinHeight = 96.dp
