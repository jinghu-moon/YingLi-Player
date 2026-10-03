package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.domain.playback.AbLoopSession

/**
 * AB 工具胶囊的**内容**（A / B / 清除 / 关闭）。
 *
 * 它是一个"插槽内容"，不是浮层：位置、几何、材质、出入场全部由底栏的
 * [AuxiliaryToolCapsuleSlot] 负责（与截图胶囊**同源、同一格**），所以这里
 * **不许**再挂 `align` / `offset` / 自己的高度等位置修饰符 —— 那会立刻打破
 * "与截图胶囊同源"这条要求（两枚胶囊的竖直带必须逐像素一致）。
 *
 * 两处与截图胶囊的**唯一**差别按设计稿保留：
 *  1. 内容是带文字的按钮（[PlayerChromeTextButton]，弹性宽度）而不是纯图标圆钮；
 *  2. 关闭按钮用 [YingLiIcon.CLOSE] 图标（读屏文案"关闭"），语义上**关闭 ≠ 取消**（D3）：
 *     它只收起胶囊，循环继续生效，只有"清除"才取消循环，所以文案绝不能写成"取消"。
 */
@Composable
internal fun AbLoopToolCapsule(
    session: AbLoopSession,
    onSetA: () -> Unit,
    onSetB: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    PlayerChromeCapsuleSurface(
        modifier = modifier.height(PlayerScreenshotCapsuleHeight),
        // 一排按钮必须**整行**在胶囊里居中：胶囊比按钮高一圈（四周 [ScreenshotCapsuleInnerPadding]），
        // 这一行贴顶就会变成"按钮在上、下面空一圈"（真机实测反馈的"没有垂直居中"）。
        // 行自己的 `verticalAlignment` 只负责按钮彼此对齐，管不到整行在胶囊里的位置。
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val clearLabel = stringResource(R.string.player_ab_clear)
        val labels = listOf(
            abPointLabel("A", session.pointA, stringResource(R.string.player_ab_point_unset)),
            abPointLabel("B", session.pointB, stringResource(R.string.player_ab_point_unset)),
            clearLabel,
        )
        BoxWithConstraints {
            val baseFontSize = MaterialTheme.typography.labelLarge.fontSize
            val layout = abCapsuleTextLayout(
                availableWidth = maxWidth,
                labels = labels,
                baseFontSize = baseFontSize,
                fontScale = fontScale,
            )
            Row(
                // 内边距**也取自同一处排版决策**：紧凑档会把它从 8dp 收到 4dp，
                // 布局若还写死常规档，模型算出来的"放得下"就与真正画出来的不一致（实测的溢出就是这么来的）。
                modifier = Modifier.padding(horizontal = layout.innerPadding),
                horizontalArrangement = Arrangement.spacedBy(layout.spacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerChromeTextButton(
                    label = labels[0],
                    contentDescription = abPointDescription(
                        value = session.pointA?.let(::formatAbTime),
                        setDescription = stringResource(R.string.player_ab_set_point_a),
                        valueDescription = stringResource(R.string.player_ab_point_a_value),
                    ),
                    onClick = onSetA,
                    baseFontSize = layout.fontSize,
                    horizontalPadding = layout.textPadding,
                )
                PlayerChromeTextButton(
                    label = labels[1],
                    contentDescription = abPointDescription(
                        value = session.pointB?.let(::formatAbTime),
                        setDescription = stringResource(R.string.player_ab_set_point_b),
                        valueDescription = stringResource(R.string.player_ab_point_b_value),
                    ),
                    onClick = onSetB,
                    // 没有 A 就没有区间可言，B 先禁用（与旧胶囊同一条禁用规则）。
                    enabled = session.pointA != null,
                    baseFontSize = layout.fontSize,
                    horizontalPadding = layout.textPadding,
                )
                PlayerChromeTextButton(
                    label = clearLabel,
                    contentDescription = clearLabel,
                    onClick = onClear,
                    enabled = session.pointA != null,
                    baseFontSize = layout.fontSize,
                    horizontalPadding = layout.textPadding,
                )
                PlayerChromeIconButton(
                    icon = YingLiIcon.CLOSE,
                    contentDescription = stringResource(R.string.player_ab_close),
                    onClick = onClose,
                    // 尺寸与胶囊里其余按钮、以及截图胶囊的圆钮**同一个常量**。
                    size = PlayerScreenshotCapsuleButtonSize,
                )
            }
        }
    }
}

/**
 * AB 胶囊里那三段文字的水平排版决策（**唯一**一处）：内边距档 + 间距档 + 文字按钮内边距档 + 统一字号。
 *
 * 为什么要把这几件事放在一个纯函数里：它们互相约束 —— 胶囊内边距、按钮间距、按钮自己的内边距
 * 都是"文字可用宽度"的一部分，而字号又由可用宽度推出。分开算就会出现"算字号时没扣某一项、
 * 布局时却要扣"这类漂移（实测两次：一次是漏了文字按钮内边距，一次是漏了系统字号缩放，
 * 症状都是 2 倍系统字号下三枚按钮把关闭圆钮挤成 0 宽、整排溢出辅助带而被裁掉）。
 *
 * 决策次序（可读性优先，其次才是留白）：
 *  1. **常规档**（[ScreenshotCapsuleInnerPadding] / [PlayerScreenshotCapsuleButtonSpacing] /
 *     [PlayerChromeTextButtonHorizontalPadding]，与截图胶囊同源）：能放下就用它；
 *  2. 常规档放不下 → **紧凑档**（[ScreenshotCapsuleInnerPaddingCompact] /
 *     [PlayerScreenshotCapsuleButtonSpacingCompact] / [PlayerChromeTextButtonCompactHorizontalPadding]）：
 *     先收留白，别急着缩字；
 *  3. 两档都放不下（比 320dp 还窄 **且** 系统字号 ≥2 倍这种极端组合）→ 用紧凑档，
 *     字号被 [PlayerChromeTextMinFontSize] 托住：以"可读"为先，不再收留白。
 *
 * [availableWidth] 是胶囊**自身的最大宽度**（= 辅助带宽度）；[fontScale] 必须传系统字号缩放，
 * 否则"系统字号放大"这一档算出来的字号会偏大（见 [playerChromeTextEstimatedWidthDp] 的说明）。
 */
internal fun abCapsuleTextLayout(
    availableWidth: Dp,
    labels: List<String>,
    baseFontSize: TextUnit,
    fontScale: Float,
): AbCapsuleTextLayout {
    fun layout(innerPadding: Dp, spacing: Dp, textPadding: Dp): AbCapsuleTextLayout {
        // 文字可用宽度 = 胶囊宽 - 胶囊内边距 - 各按钮间距 - 关闭圆钮 - 三枚文字按钮自己的内边距。
        val textWidth = availableWidth -
            innerPadding * 2 -
            spacing * labels.size -
            PlayerScreenshotCapsuleButtonSize -
            textPadding * labels.size * 2
        return AbCapsuleTextLayout(
            innerPadding = innerPadding,
            spacing = spacing,
            textPadding = textPadding,
            fontSize = playerChromeTextFontSizeSp(textWidth, labels, baseFontSize, fontScale),
            textWidth = textWidth,
        )
    }

    val roomy = layout(
        innerPadding = ScreenshotCapsuleInnerPadding,
        spacing = PlayerScreenshotCapsuleButtonSpacing,
        textPadding = PlayerChromeTextButtonHorizontalPadding,
    )
    if (roomy.labelsFit(labels, fontScale)) return roomy
    return layout(
        innerPadding = ScreenshotCapsuleInnerPaddingCompact,
        spacing = PlayerScreenshotCapsuleButtonSpacingCompact,
        textPadding = PlayerChromeTextButtonCompactHorizontalPadding,
    )
}

/**
 * 一次排版决策的结果：胶囊内边距、按钮间距、文字按钮内边距、三段文字统一的字号（sp），
 * 以及这一档下"三段文字"能用的宽度。
 *
 * 四个几何值与字号**必须一起用**：布局只取其中一部分就会让"模型说放得下"与"实际画出来"分叉
 *（实测：字号按紧凑档算了，内边距却还写常规档，2 倍字号下整排溢出辅助带、关闭圆钮被裁）。
 *
 * [labelsFit] 是"这一档下算出来的字号确实放得进"的自检。**它就是"窄屏 / 横屏 / 系统字号放大
 * 不截断"这条验收的可验证形式。**
 */
internal data class AbCapsuleTextLayout(
    val innerPadding: Dp,
    val spacing: Dp,
    val textPadding: Dp,
    val fontSize: TextUnit,
    val textWidth: Dp,
) {
    fun labelsFit(labels: List<String>, fontScale: Float): Boolean =
        // 0.01dp 的容差：字号是"可用宽度 ÷ 单位宽度"再落到 sp 的浮点结果，回流相乘时可能差最后一位。
        playerChromeTextEstimatedWidthDp(labels, fontSize, fontScale).value <= textWidth.value + 0.01f
}

/**
 * 设置点按钮的文案：设好了显示时间（`A 00:12`），没设显示"设置"（`A 设置`）。
 *
 * 为什么是"时间"而不是刻度/图标：这两个点没有别的表达方式 —— 用户要核对的正是
 * "我设的是不是这一刻"，`00:12` 与进度条上的时间读数同源同格式（[formatAbTime]），
 * 一眼就能对上。纯函数，便于单测（与 UI 无关）。
 */
internal fun abPointLabel(point: String, valueMillis: Long?, unsetLabel: String): String =
    if (valueMillis == null) "$point $unsetLabel" else "$point ${formatAbTime(valueMillis)}"

/** 同一枚按钮的**读屏文案**：没设时是动作（"设置 A 点"），设好了是状态（"A 点 00:12"）。 */
internal fun abPointDescription(
    value: String?,
    setDescription: String,
    valueDescription: String,
): String = if (value == null) setDescription else valueDescription.format(value)

/**
 * A/B 时间读数：`mm:ss`，与进度行的时间文本**同一格式**（[formatDuration]）。
 *
 * 旧胶囊用的是 `m:ss`（`0:12`），与进度条上的 `00:12` 不一致 —— 同一段视频的同一个时间点
 * 在两处显示成两个样子，用户会怀疑自己设错了点。这里统一到进度条的格式（含小时会进位到分钟，
 * 与 `formatDuration` 的行为一致）。
 */
internal fun formatAbTime(valueMillis: Long): String = formatDuration(valueMillis)
