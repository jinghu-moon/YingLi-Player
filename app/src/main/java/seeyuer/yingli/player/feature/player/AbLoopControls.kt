package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.playback.AbLoopSession

/**
 * AB 工具胶囊的**内容**（A / B / 清除 / 关闭）。
 *
 * 它是一个"插槽内容"，不是浮层：位置、几何、材质、出入场全部由底栏的
 * [AuxiliaryToolCapsuleSlot] 负责（与截图胶囊**同源、同一格**），所以这里
 * **不许**再挂 `align` / `offset` / 自己的高度等位置修饰符 —— 那会立刻打破
 * "与截图胶囊同源"这条要求（两枚胶囊的竖直带必须逐像素一致）。
 *
 * ## 四个按钮一律是**定尺寸圆钮**（`PlayerChromeIconButton`，48dp）
 *
 * 真机实测的缺陷正是这条的反面：A / B / 清除三枚曾是**弹性宽度文字按钮**
 *（`PlayerChromeTextButton`，标签 `A 00:12` / `B 00:37` / `清除`），文字一长就把圆钮撑成**椭圆**，
 * 同屏四枚按钮圆径不一。现在四个位置**只有一种按钮**：宽度恒为 [PlayerChromeButtonSize]，
 * 内容只能是图标 —— **内容永远不会改写尺寸**。
 *
 * A / B 的字形用图标库里的 `letter-a` / `letter-b`（见 [YingLiIcon.LETTER_A] /
 * [YingLiIcon.LETTER_B]，已解包 aar 核对真实名）。用字形而不是在按钮里画文字，是同一个理由：
 * 字形随按钮固定尺寸缩放，不参与测量。
 *
 * ## "是否已设置"用 `filled` 表达，**数值从按钮里移除**
 *
 * 设点按钮是**开关**（设了 / 没设），所以走项目既有的 `filled` + `selected` 语义
 *（与镜像、后台播放、AB 入口**同一套**，不新造高亮样式）：已设置 = 实心强调色 + 语义选中态，
 * 未设置 = 半透明底 + 描边。数值（`06:12`）一律不进按钮，全部交给读数条
 *（见 [abReadoutSegments]）—— 按钮只回答"这一端设没设"，读数条只回答"设在哪、区间多长、循环了几次"。
 *
 * 两处与截图胶囊的**唯一**差别按设计稿保留：
 *  1. 内容是 A/B/清除/关闭这四枚**语义按钮**而不是上一帧/捕获/下一帧/取消；
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
    val hasPointA = session.pointA != null
    val hasPointB = session.pointB != null
    PlayerChromeCapsuleSurface(
        modifier = modifier.height(PlayerScreenshotCapsuleHeight),
        // 一排按钮必须**整行**在胶囊里居中：胶囊比按钮高一圈（四周 [ScreenshotCapsuleInnerPadding]），
        // 这一行贴顶就会变成"按钮在上、下面空一圈"（真机实测反馈的"没有垂直居中"）。
        // 行自己的 `verticalAlignment` 只负责按钮彼此对齐，管不到整行在胶囊里的位置。
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxWithConstraints {
            val innerPadding = capsuleInnerPadding(maxWidth, AbCapsuleButtonCount)
            Row(
                modifier = Modifier.padding(horizontal = innerPadding),
                horizontalArrangement = Arrangement.spacedBy(PlayerScreenshotCapsuleButtonSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerChromeIconButton(
                    icon = YingLiIcon.LETTER_A,
                    contentDescription = stringResource(R.string.player_ab_set_point_a),
                    onClick = onSetA,
                    // 尺寸与胶囊里其余按钮、以及截图胶囊的圆钮**同一个常量**。
                    // **不要在这里写死别的尺寸，也不要让它随内容变化。**
                    size = PlayerScreenshotCapsuleButtonSize,
                    // 开关已生效 = 实心强调色；同时写进语义（读屏念"已选中"，测试据此断言）。
                    filled = hasPointA,
                    selectedState = hasPointA,
                )
                PlayerChromeIconButton(
                    icon = YingLiIcon.LETTER_B,
                    contentDescription = stringResource(R.string.player_ab_set_point_b),
                    onClick = onSetB,
                    // 没有 A 就没有区间可言，B 先禁用（与旧胶囊同一条禁用规则）。
                    enabled = hasPointA,
                    size = PlayerScreenshotCapsuleButtonSize,
                    filled = hasPointB,
                    selectedState = hasPointB,
                )
                PlayerChromeIconButton(
                    icon = YingLiIcon.ERASER,
                    contentDescription = stringResource(R.string.player_ab_clear),
                    onClick = onClear,
                    enabled = hasPointA,
                    size = PlayerScreenshotCapsuleButtonSize,
                )
                PlayerChromeIconButton(
                    icon = YingLiIcon.CLOSE,
                    contentDescription = stringResource(R.string.player_ab_close),
                    onClick = onClose,
                    size = PlayerScreenshotCapsuleButtonSize,
                )
            }
        }
    }
}

/**
 * 胶囊里的按钮数量（A / B / 清除 / 关闭）。
 *
 * 它是 [capsuleInnerPadding] 的输入之一：横向内边距要按"这一排能放下几枚按钮"倒推，
 * 所以按钮数量必须是一个**说出名字**的值，而不是在公式里写 `4`。
 */
private const val AbCapsuleButtonCount = 4

/**
 * 读数条的**文字分段**（纯数据，可在 JVM 上逐条钉住）。
 *
 * 为什么不是一整行字符串：A / B 两个数值各自要成为**独立点击目标**，还要分别取色
 *（数值 = 强调色，标点与计数 = 次文字色），拼成一整行就既定位不了也上不了色。
 */
internal data class AbReadoutSegment(
    val text: String,
    val kind: AbReadoutSegmentKind,
    /** 这一段的时长位置（毫秒）；[AbReadoutSegmentKind.POINT_A] / `POINT_B` 之外为 null。 */
    val pointMillis: Long? = null,
)

/** 读数条分段的种类：决定取色与"能不能点"。 */
internal enum class AbReadoutSegmentKind {
    /** `A 06:12`：可点，跳到 A。 */
    POINT_A,

    /** `B 09:48`：可点，跳到 B。 */
    POINT_B,

    /** `—` / `·` 这类标点：不点、次色。 */
    PUNCTUATION,

    /** `Δ 03:36`：不点（它是一段长度，不是位置），次色。 */
    DELTA,

    /** `循环 ×12`：不点、次色。 */
    LOOP_COUNT,

    /** 未设置态的引导文案 / 仅 A 时的 `B 待落点`：不点、次色。 */
    HINT,
}

/** 读数条的分段结果：`texts` 是按左右顺序排列的分段文本，供绘制侧连成一句。 */
internal data class AbReadoutSegments(
    val segments: List<AbReadoutSegment>,
) {
    val texts: List<String> get() = segments.map { it.text }
}

/**
 * 读数条里 A 与 B 之间的**区间分隔符**：长破折号 `—`（U+2014）。
 *
 * 它是 demo 的**事实**（`A 06:12 — B 09:48`），不是 en dash（`–`），也不是我们旧实现里的箭头
 *（`→`）。旧箭头的问题在语义上：`A → B` 读起来像"从 A 播放到 B"这个动作，
 * 而这一行回答的是"区间从哪到哪"这个**范围**，破折号才是范围的写法。
 *
 * 与 [AbReadoutSeparator] 一样刻意不走字符串资源：它是标点，不需要翻译；
 * 需要翻译的整词（`循环 ×N`、引导文案）仍然走资源。
 */
internal const val AbReadoutRangeSeparator = "—"

/** 次级分隔符 `·`（U+00B7）：分隔 `Δ` 与循环计数。理由同 [AbReadoutRangeSeparator]。 */
internal const val AbReadoutSeparator = "·"

/**
 * 组装读数条的文本（纯函数，可在 JVM 上逐条钉住）。
 *
 * 四态（demo 的"四态文案"，第 4 态"编辑中"随编辑态不采纳而下线）：
 *
 *     未设置：  未设置循环 · 点 A 在播放头落点          （[noneLabel]，**不留空白**）
 *     仅 A：    A 06:12 — B 待落点
 *     已锁定：  A 06:12 — B 09:48 · Δ 03:36 · 循环 ×12
 *
 * 各段的显示条件：
 *  1. **A / B 时刻**：设了就显示。两端都在时中间是 [rangeSeparator]（`—`）；
 *     只设了 A 时中间是"B 待落点"（箭头指向虚空的问题因此不存在了）；
 *  2. **Δ 时长**（[deltaPrefix] + [formatAbDelta]）：只在区间完整时出现。**必须显式写出来** ——
 *     短区间被进度条放大之后（见 [abRangeGeometry]），渲染长度不再代表真实长度，
 *     真实长度只能在文字上有一个落点；
 *  3. **循环 ×N**：同样只在区间完整时出现，且**附在末尾**（demo 的已锁定态没有计数，
 *     但 D9 要求显示它；"放在哪"由本批定在末尾，因为它是这一行里唯一会随时间自己变的数）。
 *
 * [pendingLabel] 是"仅 A"那一态的中段文案，由调用方从字符串资源取好传进来（本函数因此保持纯函数）。
 * 纯函数不碰资源：`循环 ×N`、引导文案、`Δ` 前缀都由调用方取好了再给。
 */
internal fun abReadoutSegments(
    pointAMillis: Long?,
    pointBMillis: Long?,
    loopCountLabel: String,
    noneLabel: String = "",
    pendingLabel: String = "",
    deltaPrefix: String = "",
    rangeSeparator: String = AbReadoutRangeSeparator,
    separator: String = AbReadoutSeparator,
): AbReadoutSegments {
    // 未设置：**不留空白**，显示引导文案（demo 的第 1 态）。一个点都没设时整行仍然在场，
    // 用户才知道"这里可以设点"，而不是对着一条空带子猜。
    if (pointAMillis == null && pointBMillis == null) {
        return AbReadoutSegments(listOf(AbReadoutSegment(noneLabel, AbReadoutSegmentKind.HINT)))
    }

    val complete = pointAMillis != null && pointBMillis != null
    val segments = mutableListOf<AbReadoutSegment>()
    pointAMillis?.let {
        segments += AbReadoutSegment("A ${formatAbTime(it)}", AbReadoutSegmentKind.POINT_A, it)
    }
    if (complete) {
        segments += AbReadoutSegment(rangeSeparator, AbReadoutSegmentKind.PUNCTUATION)
    } else if (pointBMillis == null && pendingLabel.isNotEmpty()) {
        // 只有 A：中段换成"B 待落点"。**分隔符一件不少**（`A 00:12 — B 待落点`）——
        // 读数条的骨架在三种状态下必须一致，否则用户切换设置时整行会跳一下。
        segments += AbReadoutSegment(rangeSeparator, AbReadoutSegmentKind.PUNCTUATION)
        segments += AbReadoutSegment(pendingLabel, AbReadoutSegmentKind.HINT)
    }
    pointBMillis?.let {
        segments += AbReadoutSegment("B ${formatAbTime(it)}", AbReadoutSegmentKind.POINT_B, it)
    }
    if (complete) {
        // Δ 与循环计数**分成两段**：它们回答的问题不同（"区间多长" vs "循环了几次"），
        // 而且只有计数是"会一直变"的那个数——分成两段才可能只给它压一档色阶。
        segments += AbReadoutSegment(separator, AbReadoutSegmentKind.PUNCTUATION)
        segments += AbReadoutSegment(
            "$deltaPrefix ${formatAbDelta(pointBMillis - pointAMillis)}",
            AbReadoutSegmentKind.DELTA,
        )
        segments += AbReadoutSegment(separator, AbReadoutSegmentKind.PUNCTUATION)
        segments += AbReadoutSegment(loopCountLabel, AbReadoutSegmentKind.LOOP_COUNT)
    }
    return AbReadoutSegments(segments)
}

/**
 * 区间时长（`Δ` 后面那一段）：**与两个端点、进度行两端同一份**格式化（[formatDuration]）。
 *
 * **有意偏离 demo**：demo 的 `fmtP` 在 1 小时以上**不补零**（`1:03:36`），我们沿用全站口径
 * 补零成 `01:35:00` —— 同一个进度区里"端点 01:35:00 / 区间 01:35:00"必须逐字符同源，
 * 补零与不补零混用会让两处看起来像两种格式。不足 1 小时同样是 `mm:ss`（`Δ 00:25`）。
 *
 * **另一处有意偏离**：旧实现有两档（< 60s 用一位小数秒 `25.0s`）。本批统一到 `mm:ss` ——
 * demo 的事实就是 `Δ 03:36` 这一种写法，而且 `Δ` 本身已经声明了它是**时长**，
 * 不需要再用 `s` 后缀重复一次。
 */
internal fun formatAbDelta(intervalMillis: Long): String = formatDuration(intervalMillis)

/**
 * A/B 时间读数：与进度行的时间文本**同一格式**（[formatDuration]：不足 1 小时 `mm:ss`，
 * 1 小时起 `hh:mm:ss`）。
 *
 * 旧胶囊用的是 `m:ss`（`0:12`），与进度条上的 `00:12` 不一致 —— 同一段视频的同一个时间点
 * 在两处显示成两个样子，用户会怀疑自己设错了点。这里统一到进度条的格式。
 */
internal fun formatAbTime(valueMillis: Long): String = formatDuration(valueMillis)

/**
 * 读数条里一个字符的宽度（em）——**读数条专用**的宽度模型。
 *
 * 为什么不复用 `playerChromeText` 那一套：那套是为**按钮标签**（`清除` / `播放速度：1.5×`）标定的，
 * 它把 em dash `—`、`·`、`Δ` 这类**读数条上高频出现**的标点当作窄字符，而 `—` 实际占满一个字身。
 * 用那套模型反推读数条的字号会**偏大**（算出来放得下、实际被裁）。这里按读数条的字符集标定。
 */
private const val AbReadoutNarrowAdvanceEm = 0.56f
private const val AbReadoutWidthSafetyFactor = 1.04f

/** 读数条里一个字符的宽度（em）：`—` 满字身、`·` 很窄、`Δ` 中等、CJK 全角、其余 0.56em。 */
private fun abReadoutAdvanceEm(character: Char): Float = when (character) {
    '—' -> 1.0f
    '·' -> 0.35f
    'Δ' -> 0.7f
    else -> if (character.code >= 0x2E80) 1.1f else AbReadoutNarrowAdvanceEm
}

/** 读数条文本 [text] 在 [fontSize] 下的**估算宽度**（dp，含安全余量）。 */
internal fun abReadoutWidthDp(text: String, fontSize: TextUnit, fontScale: Float = 1f): Dp {
    val advanceEm = text.fold(0f) { acc, character -> acc + abReadoutAdvanceEm(character) }
    return (advanceEm * AbReadoutWidthSafetyFactor * fontScale.coerceAtLeast(0.01f) * fontSize.value).dp
}

/**
 * 读数条的字号：**上限 13sp**（demo 的 `.readout`，`docs/21` §3.1）、
 * **下限 10sp**（[PlayerChromeTextMinFontSize]）。
 *
 * [availableWidth] 是整行的可用宽度；[chipBudget] 是两个数值点击目标先占掉的宽度
 *（它们是触控尺寸，不可缩）。
 */
internal fun abReadoutFontSizeSp(
    availableWidth: Dp,
    text: String,
    chipBudget: Dp,
    baseFontSize: TextUnit,
    fontScale: Float = 1f,
): TextUnit {
    val upperBound = minOf(baseFontSize.value, AbReadoutFontSize.value)
    val textBudget = (availableWidth - chipBudget).coerceAtLeast(0.dp)
    // 连文本预算都没有（极窄屏 / 最大字号）：取**可读下限**而不是上限 —— 这时整行无论如何都要
    // 溢出被上游裁剪，能决定的只有"字多大"，而下限是唯一与"读得出来"一致的那一档。
    if (textBudget <= 0.dp || text.isEmpty()) return PlayerChromeTextMinFontSize
    val unitWidth = abReadoutWidthDp(text, 1.sp, fontScale).value
    if (unitWidth <= 0f) return upperBound.sp
    return (textBudget.value / unitWidth)
        .coerceIn(PlayerChromeTextMinFontSize.value, upperBound)
        .sp
}

/**
 * 读数条的目标字号：**13sp**（demo 的 `.readout`）。
 *
 * 它只作**上限**：窄屏 / 2 倍系统字号下由 [abReadoutFontSizeSp] 按可用宽度反推（下限 10sp）。
 * **它压过主题字号**：`labelLarge` 是 14sp（为界面标签标定的），而读数条是 demo 明确定为 13sp
 * 的那一行，所以取 `min(主题字号, 13sp)`。
 */
private val AbReadoutFontSize = 13.sp

/** 读数条分段之间的间距：4dp（它分隔的是词，不是触控目标 —— 比胶囊按钮间距小两档）。 */
private val AbReadoutSpacing = 4.dp

/**
 * 读数条（AB 的**唯一**数值落点）。
 *
 * 各段的上色与可点性由 [AbReadoutSegmentKind] 一处决定：
 *  · **A / B 数值**取强调色，并且各自是一个 [AbValueTapTargetWidth] 宽、
 *    [AbValueTapTargetHeight] 高的点击目标，点按跳到该端点（与点轨道上那枚徽标同一个回调）；
 *  · 标点 / `Δ` / 循环计数取次文字色：它们不是"位置"，压一档色阶才不会抢走时刻的注意力。
 */
@Composable
internal fun AbReadoutRow(
    segments: AbReadoutSegments,
    availableWidth: Dp,
    lineBandHeight: Dp,
    onSeekToPoint: (Long) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val baseFontSize = MaterialTheme.typography.labelLarge.fontSize
    val valueColor = YingLiTheme.player.controlPrimary
    val secondaryColor = YingLiTheme.player.controlSecondary
    val readoutBand = maxOf(AbValueTapTargetHeight, lineBandHeight)
    val valueTargetCount = segments.segments.count {
        it.kind == AbReadoutSegmentKind.POINT_A || it.kind == AbReadoutSegmentKind.POINT_B
    }
    val fontSize = abReadoutFontSizeSp(
        availableWidth = availableWidth,
        text = segments.texts.joinToString(" "),
        chipBudget = AbValueTapTargetWidth * valueTargetCount,
        baseFontSize = baseFontSize,
        fontScale = fontScale,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // testTag 挂在**这一行**上：`onNodeWithTag(AB_RANGE_LABELS)` 拿到的就是整行，
            // 它下面的文字在该节点的合并语义里（instrumented 的 `assertTextEquals` 直接可读）。
            .testTag(PlayerTestTags.AB_RANGE_LABELS),
        horizontalArrangement = Arrangement.spacedBy(AbReadoutSpacing, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.segments.forEach { segment ->
            val pointMillis = segment.pointMillis
            val clickable = pointMillis != null
            Text(
                text = segment.text,
                color = if (clickable) valueColor else secondaryColor,
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                fontSize = fontSize,
                maxLines = 1,
                // 不换行、不省略：宽度由上面的字号适配保证（下有 10sp 下限），
                // 省略号会把"区间多长"这一段吃掉，而那正是这个数字存在的意义。
                softWrap = false,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .defaultMinSize(minWidth = AbValueTapTargetWidth, minHeight = readoutBand)
                    .then(
                        if (pointMillis != null) {
                            Modifier
                                .clickable(role = Role.Button) { onSeekToPoint(pointMillis) }
                                .testTag(
                                    if (segment.kind == AbReadoutSegmentKind.POINT_A) {
                                        PlayerTestTags.AB_READOUT_POINT_A
                                    } else {
                                        PlayerTestTags.AB_READOUT_POINT_B
                                    },
                                )
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

/**
 * 读数行的**行高天条**：`主题字号 × [PlayerAbReadoutBandHeightRatio]`，不低于
 * [PlayerPortraitControlsSpacing]。
 *
 * 为什么**不用** `labelLarge.lineHeight`（旧实现的做法）：那个值在本项目里可能是
 * [TextUnit.Unspecified]（主题只声明了字号），而且它描述的是"整段文字的行距"，含字体自身的
 * ascent/descent 余量 —— 拿它当"槽位高度"既不可靠、也偏高。改用"比字号高 15%"这条与字体无关的
 * 规则：14sp → 16.1dp（被间距档托到 16dp），2 倍系统字号 → 32.2dp（读数条不会被压扁），
 * 任何主题下都有确定值。
 *
 * 1 倍字号下它恰好是 [PlayerPortraitControlsSpacing]（16dp），所以**常规机型的几何完全不变**。
 */
internal fun abReadoutBandHeight(fontSize: TextUnit, fontScale: Float): Dp {
    if (fontSize == TextUnit.Unspecified) return PlayerPortraitControlsSpacing
    val resolved = (fontSize.value * fontScale * PlayerAbReadoutBandHeightRatio).dp
    return maxOf(PlayerPortraitControlsSpacing, resolved)
}

/** 读数行天条相对**字号**的倍数（见 [abReadoutBandHeight]）：Roboto 的 ascent+descent ≈ 1.17em。 */
internal const val PlayerAbReadoutBandHeightRatio = 1.15f
