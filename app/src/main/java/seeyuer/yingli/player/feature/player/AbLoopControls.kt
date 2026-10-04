package seeyuer.yingli.player.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
 * 未设置 = 半透明底 + 描边。数值（`00:12`）一律不进按钮，全部交给进度行的读数行
 *（见 [abReadoutSegments]）—— 按钮只回答"这一端设没设"，读数行只回答"设在哪、区间多长、循环了几次"。
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
 * 区间读数行的**文字分段**：定长的几段文本，或"什么都还没设"。
 *
 * 为什么返回分段而不是一整行字符串：读数行需要给"区间时长"和"循环计数"分别上色 / 挂测试标记，
 * 拼成一整行就没法单独定位了（而这一行正是本批的验收重点）。
 */
internal data class AbReadoutSegments(
    /** 按左右顺序排列的分段文本；空列表 = 什么都不显示。 */
    val texts: List<String>,
    /** "区间时长 + 循环次数"那一段在 [texts] 里的下标；没有（区间不完整）时为 -1。 */
    val intervalIndex: Int,
    /** "循环 ×N"那一段在 [texts] 里的下标；没有时为 -1。 */
    val loopCountIndex: Int,
)

/**
 * 区间读数行的**箭头**：`A 00:12 → B 00:17`。
 *
 * 它是用户可见标点，但**刻意不走字符串资源**：项目里凡是不需要翻译的符号都直接写在纯函数里
 *（见 `docs/16` §3.3 的字距/字形口径），而资源化的唯一好处是翻译，代价是纯函数要多收一个参数、
 * 判定与文案多一处可能漂移的地方。需要翻译的整词（`循环 ×N`）仍然走资源。
 */
internal const val AbReadoutArrow = "→"

/** 区间读数行的**分隔符**：`… · 5.0s · 循环 ×12`。理由同 [AbReadoutArrow]。 */
internal const val AbReadoutSeparator = "·"

/**
 * 组装进度行下方的读数行文本（纯函数，可在 JVM 上逐条钉住）。
 *
 * 格式（本批定稿，`docs/16` §5.11 的几何表要写进去）：
 *
 *     A 00:12 → B 00:17 · 5.0s · 循环 ×12
 *
 * 三段各自存在的条件：
 *  1. **A / B 时刻**：设了就显示，没设就不出现 —— 未设时那一端在胶囊里是"未选中的圆钮"，
 *     读数行不重复表达"没设"，只表达"设在哪"；
 *  2. **区间时长**（[formatAbIntervalDuration]，**两档**：`< 60s` 是一位小数秒 `5.0s`，
 *     `≥ 60s` 用与端点同源的 `mm:ss`，如 `30:00`）：只有**区间完整**时才有值。
 *     为什么必须显式写出时长而不是只给两端时刻：短区间被进度条放大之后（见 [abRangeGeometry]），
 *     它的渲染长度已经不代表真实长度了，**真实长度必须在文字上有一个落点**，否则用户没有任何
 *     可读的依据。这也是"夸大必须可辨识"这条要求的一半（另一半在绘制侧）；
 *  3. **循环 ×N**：同样只在区间完整时出现 —— 只设了 A 时循环还没开始，显示 `循环 ×0`
 *     会让人以为"已经在循环但一次没跑"。它由调用方从字符串资源格式化好（计数的事实来源是
 *     `AbLoopSession.loopCount`），纯函数只负责"什么时候显示、排在哪"。
 *
 * [arrow] / [separator] / [loopCountLabel] 都由调用方传入（前两个有默认值 = 上文那两个常量）：
 * 纯函数不碰资源，`循环 ×N` 这类需要翻译的整词由调用方从字符串资源取好了再给。
 */
internal fun abReadoutSegments(
    pointAMillis: Long?,
    pointBMillis: Long?,
    loopCountLabel: String,
    arrow: String = AbReadoutArrow,
    separator: String = AbReadoutSeparator,
): AbReadoutSegments {
    if (pointAMillis == null && pointBMillis == null) return AbReadoutSegments(emptyList(), -1, -1)

    val texts = mutableListOf<String>()
    // A 段：`A 00:12`。两端用**同一个**时间格式（formatAbTime → formatDuration），
    // 与进度行左右两端的时间文本同源，用户能一眼对上。
    pointAMillis?.let { texts += "A ${formatAbTime(it)}" }
    // 箭头只在"两端都在"时出现：只设了一端时 `A 00:12 →` 会让箭头指向虚空。
    if (pointAMillis != null && pointBMillis != null) texts += arrow
    pointBMillis?.let { texts += "B ${formatAbTime(it)}" }

    val complete = pointAMillis != null && pointBMillis != null
    var intervalIndex = -1
    var loopCountIndex = -1
    if (complete) {
        // 区间时长与两端时刻**同一行**显示（用 `·` 分隔）：它们回答的是同一个问题
        //（"这段区间到底是什么"）。分隔符在这里由纯函数自己插入，读数行的可用宽度因此在
        // 组装时就已确定 —— 绘制侧不再需要"先量一遍、再决定要不要留间隔"。
        texts += "$separator ${formatAbIntervalDuration(pointBMillis - pointAMillis)} $separator $loopCountLabel"
        intervalIndex = texts.lastIndex
        loopCountIndex = intervalIndex
    }
    return AbReadoutSegments(texts, intervalIndex, loopCountIndex)
}

/**
 * 区间时长文本：**两档**（本批定稿）。
 *
 *  · **< [AbIntervalDurationSecondsFormatThresholdMillis]（60s）**：一位小数秒（`5.0s` / `12.3s`）。
 *    这一档回答的是"我设的这一段到底多短"：AB 帧吸附之后区间很少正好落在整秒上，0.1s 的精度
 *    足够回答"是不是我要的那一段"；反过来说，`00:05` 与端点读数（也是 `mm:ss`）看起来是同一类
 *    东西，用户还得自己做一次减法才知道区间多长。
 *  · **≥ 60s**：直接交给 [formatDuration]，也就是**与 A/B 两个端点、进度行两端同一份**格式化，
 *    例如 1800s → `30:00`。为什么不再用一位小数秒：95 分钟影片上的 30 分钟区间会显示成
 *    `1800.0s`，读起来像出错；改成同源格式之后，用户可以直接对着看"从 10:00 到 40:00"。
 *
 * **边界（明确并有测试钉住）**：`>= 60_000ms` 走第二档 —— 正好 60s 显示 `01:00`；
 * 59_999ms 仍走第一档（它四舍五入后是 `60.0s`）。
 *
 * 关于 `h:mm:ss`：**这里不另写一套小时格式**。一小时以上的区间照样输出 `mm:ss`
 *（3600s → `60:00`），因为 [formatDuration] 就是这么做的，而进度行左端的影片总时长
 *（95 分钟的片子显示 `95:00`）走的也正是它 —— 同一行里区间写 `1:35:00`、端点写 `95:00`
 * 才是真正的不一致。要改成 `h:mm:ss`，必须先改 [formatDuration] 本身（那会同时改掉端点读数）。
 *
 * 纯函数（不依赖资源）：`s` 是国际单位符号，`mm:ss` 只是数字与冒号，都不需要本地化。
 */
internal fun formatAbIntervalDuration(intervalMillis: Long): String =
    if (intervalMillis >= AbIntervalDurationSecondsFormatThresholdMillis) {
        // 与端点/进度行同一份格式化，**不要**在这里再写一套 `%d:%02d`。
        formatDuration(intervalMillis)
    } else {
        val tenths = (intervalMillis.coerceAtLeast(0) + 50) / 100
        "${tenths / 10}.${tenths % 10}s"
    }

/**
 * 区间时长从"一位小数秒"切到 [formatDuration]（`mm:ss`）的阈值（**含**）：60s。
 *
 * 为什么边界含 60s：`60_000ms` 正好是一分钟，`01:00` 比 `60.0s` 更像"一分钟"这个量。
 * 它是**说出名字**的常量而不是公式里的字面量：两档的切换点是这份格式契约的一部分，
 * `AbLoopUiContractTest` 直接钉住 `59_999 → 60.0s` 与 `60_000 → 01:00`。
 */
internal const val AbIntervalDurationSecondsFormatThresholdMillis = 60_000L

/**
 * A/B 时间读数：`mm:ss`，与进度行的时间文本**同一格式**（[formatDuration]）。
 *
 * 旧胶囊用的是 `m:ss`（`0:12`），与进度条上的 `00:12` 不一致 —— 同一段视频的同一个时间点
 * 在两处显示成两个样子，用户会怀疑自己设错了点。这里统一到进度条的格式（含小时会进位到分钟，
 * 与 `formatDuration` 的行为一致）。
 */
internal fun formatAbTime(valueMillis: Long): String = formatDuration(valueMillis)
