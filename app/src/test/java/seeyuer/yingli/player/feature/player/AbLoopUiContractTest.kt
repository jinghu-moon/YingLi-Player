package seeyuer.yingli.player.feature.player

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.playback.AbLoopSession
import seeyuer.yingli.player.domain.playback.AbLoopState
import seeyuer.yingli.player.domain.playback.ScreenshotUiState
import seeyuer.yingli.player.domain.playback.isScreenshotCaptureResultCurrent

/**
 * AB 循环 UI 里那些**纯判定**的可验证部分：辅助带那一格归谁、AB 是否生效、
 * 胶囊的横向排版（按钮一律定尺寸）、读数行的文本组装，以及截图捕获"异步晚到"的发布契约。
 *
 * 它们都是纯函数/纯数据类，所以能在 JVM 上逐条钉住；"画出来是什么样"归
 * `androidTest/.../PlayerAbLoopScreenTest`，区间与标记的几何归 `AbLoopMathTest`，
 * "三分支的时序"归 `PlayerViewModelTest`。这一分层的意义：把"谁该出现 / 文字是什么"
 *（这里）与"点下去之后状态怎么变"（ViewModel 测试）分开断言，哪一层坏了都能一眼定位。
 */
class AbLoopUiContractTest {

    @Test
    fun `the auxiliary band renders exactly one tool capsule`() {
        // 截图工具与 AB 工具共用辅助带那一格，所以"谁在里面"必须是一个判定的结果。
        assertEquals(AuxiliaryToolCapsule.NONE, auxiliaryToolCapsule(false, ScreenshotUiState.Idle))
        assertEquals(AuxiliaryToolCapsule.NONE, auxiliaryToolCapsule(false, ScreenshotUiState.Failed(seeyuer.yingli.player.domain.playback.ScreenshotFailure.UNKNOWN)))
        // Armed / Capturing 才有截图胶囊；Preview 让位给预览卡（它不在辅助带里）。
        assertEquals(AuxiliaryToolCapsule.SCREENSHOT, auxiliaryToolCapsule(false, ScreenshotUiState.Armed))
        assertEquals(AuxiliaryToolCapsule.SCREENSHOT, auxiliaryToolCapsule(false, ScreenshotUiState.Capturing))
        assertEquals(AuxiliaryToolCapsule.NONE, auxiliaryToolCapsule(false, preview()))
        // AB 打开时它占这一格：截图胶囊绝不能同时在场（同一帧的中间态也归 AB —— 用户点的是它）。
        assertEquals(AuxiliaryToolCapsule.AB_LOOP, auxiliaryToolCapsule(true, ScreenshotUiState.Idle))
        assertEquals(AuxiliaryToolCapsule.AB_LOOP, auxiliaryToolCapsule(true, ScreenshotUiState.Armed))
        assertEquals(AuxiliaryToolCapsule.AB_LOOP, auxiliaryToolCapsule(true, ScreenshotUiState.Capturing))
        assertEquals(AuxiliaryToolCapsule.AB_LOOP, auxiliaryToolCapsule(true, preview()))
    }

    @Test
    fun `the ab capsule takes the chrome over without destroying the screenshot preview`() {
        // Preview 分支（§3.2）：AB 接管播放页 chrome，但**不动截图状态本身** ——
        // 预览卡与倒计时就是靠"状态还在 Preview"活着的，所以这里必须原样保留。
        val preview = preview()
        val state = PlayerUiState(screenshot = preview, abToolOpen = true)

        assertFalse("AB 打开时截图工具模式必须让位", state.isScreenshotToolActive())
        assertEquals(preview, state.screenshot)
        // 反向：AB 关掉之后截图工具模式自然回来（预览卡一直在，不需要恢复任何东西）。
        assertTrue(state.copy(abToolOpen = false).isScreenshotToolActive())
    }

    @Test
    fun `screenshot tool mode is off whenever no screenshot session owns the player`() {
        assertTrue(PlayerUiState(screenshot = ScreenshotUiState.Armed).isScreenshotToolActive())
        assertTrue(PlayerUiState(screenshot = ScreenshotUiState.Capturing).isScreenshotToolActive())
        assertTrue(PlayerUiState(screenshot = preview()).isScreenshotToolActive())
        // Armed / Capturing 分支的真实投影：会话被结束（Idle）+ AB 打开。
        assertFalse(PlayerUiState(screenshot = ScreenshotUiState.Idle, abToolOpen = true).isScreenshotToolActive())
        assertFalse(PlayerUiState(screenshot = ScreenshotUiState.Idle).isScreenshotToolActive())
    }

    @Test
    fun `one predicate answers whether the ab loop is live`() {
        // 托盘按钮实心、顶栏快捷槽实心、设置面板 chip 选中、进度行画区间与计数：四处共用这一个判定。
        // 判定只看"区间是否完整"，**与 AB 胶囊是否打开无关**（D3：关闭 ≠ 取消）。
        assertFalse(PlayerUiState().abLoopActive)
        assertFalse(PlayerUiState(abLoop = AbLoopSession(state = AbLoopState(pointA = 1_000))).abLoopActive)
        val active = PlayerUiState(
            abLoop = AbLoopSession(state = AbLoopState(1_000, 2_000), loopCount = 12),
            abToolOpen = false,
        )
        assertTrue(active.abLoopActive)
        // 胶囊关掉了、甚至计数还是 0，都仍然算生效。
        assertTrue(active.copy(abToolOpen = true).abLoopActive)
        assertTrue(active.copy(abLoop = active.abLoop.copy(loopCount = 0)).abLoopActive)
    }

    // ---- 胶囊的横向排版：四枚定尺寸圆钮 --------------------------------------

    @Test
    fun `the capsule keeps the same inner padding as the screenshot capsule whenever it fits`() {
        // 正常机型（竖屏 360dp 上下、带宽 336dp）：四枚 48dp 圆钮 + 3 个 12dp 间距 = 228dp，
        // 两侧各留出完整的 8dp 呼吸圈（与竖直方向同值）。
        val padding = capsuleInnerPadding(maxWidth = 336.dp, buttonCount = 4)
        assertEquals(ScreenshotCapsuleInnerPadding, padding)
        assertEquals(8.dp, padding)
    }

    @Test
    fun `a narrow band narrows the capsule padding instead of overflowing the screen`() {
        // 320dp 窄屏的带宽 = 320 − 12×2 = 296dp：仍按常规档（8dp），因为 296 − 228 = 68 ≥ 16。
        assertEquals(ScreenshotCapsuleInnerPadding, capsuleInnerPadding(maxWidth = 296.dp, buttonCount = 4))
        // 比这更窄时只收留白：按钮尺寸是触控尺寸（48dp 最小触控），**不可以缩**。
        // 240dp 带宽放得下 228dp 的按钮行 → 每侧还剩 6dp。
        assertEquals(6.dp, capsuleInnerPadding(maxWidth = 240.dp, buttonCount = 4))
        // 连按钮行都放不下时不给负内边距（宁可让外层的裁剪兜住，也不要算出负 padding 把内容拉出去）。
        assertEquals(0.dp, capsuleInnerPadding(maxWidth = 200.dp, buttonCount = 4))
    }

    @Test
    fun `both tool capsules derive their padding from the same function and the same width`() {
        // 两枚胶囊住在同一格里、由同一个槽位渲染：同一宽度下必须得到**同一个**内边距，
        // 否则切换工具时胶囊宽度会跳一下。这里把取值范围内的每一档都过一遍。
        // 注意"放不下"的那一档（< 228dp）本来就无解：四枚 48dp 圆钮是触控尺寸、不可缩，
        // 那时内边距已经收到 0，剩下的溢出只能由外层裁剪兜住 —— 所以只断言"内边距不为负"。
        listOf(200.dp, 240.dp, 264.dp, 296.dp, 336.dp, 768.dp).forEach { band ->
            val padding = capsuleInnerPadding(band, 4)
            assertTrue("band=$band padding=$padding 不允许为负", padding >= 0.dp)
            if (228.dp + ScreenshotCapsuleInnerPadding * 2 <= band) {
                assertEquals("band=$band 在常规档内必须用满呼吸圈", ScreenshotCapsuleInnerPadding, padding)
                assertTrue("band=$band padding=$padding 放不下按钮行", 228.dp + padding * 2 <= band)
            }
        }
    }

    // ---- 读数行文本（本批起住在 **AB 胶囊首行**）-------------------------------

    @Test
    fun `the readout shows both times the real interval duration and the loop count`() {
        // 本批定稿格式：`A 00:12 — B 00:17 · Δ 00:05 · 循环 ×12`
        // 三处都是 demo 的事实口径：区间两端用**长破折号** `—`（U+2014，不是 en dash，也不是旧箭头）、
        // `·` 作次级分隔、时长写成 `Δ mm:ss`；`循环 ×N` 按 D9 附在末尾（demo 没有它）。
        val segments = abReadoutSegments(
            pointAMillis = 12_000,
            pointBMillis = 17_000,
            loopCountLabel = "循环 ×12",
            deltaPrefix = "Δ",
        )
        assertEquals("A 00:12 — B 00:17 · Δ 00:05 · 循环 ×12", segments.texts.joinToString(" "))
        // 七段：A / `—` / B / `·` / `Δ …` / `·` / `循环 ×N`。
        // **`Δ` 与计数必须分成两段**（旧实现把它们合成一段，于是"区间时长"会被跟着上一档次色）：
        // 它们回答的问题不同，而且只有计数是"会一直变"的那个数。
        assertEquals(7, segments.segments.size)
        assertEquals("Δ 00:05", segments.segments[4].text)
        assertEquals(AbReadoutSegmentKind.DELTA, segments.segments[4].kind)
        assertEquals("循环 ×12", segments.segments[6].text)
        assertEquals(AbReadoutSegmentKind.LOOP_COUNT, segments.segments[6].kind)
        // A / B 两段各自带着"跳到哪"的时刻：它们就是读数条里的独立点击目标。
        assertEquals(AbReadoutSegmentKind.POINT_A, segments.segments[0].kind)
        assertEquals(12_000L, segments.segments[0].pointMillis)
        assertEquals(AbReadoutSegmentKind.POINT_B, segments.segments[2].kind)
        assertEquals(17_000L, segments.segments[2].pointMillis)
        // 分隔符是**区间范围**的写法：长破折号，不是箭头。
        assertEquals("—", AbReadoutRangeSeparator)
        assertEquals(AbReadoutRangeSeparator, segments.segments[1].text)
    }

    @Test
    fun `a one minute or longer interval reads as mm ss exactly like the endpoints`() {
        // 125.4s 走与端点同源的 `mm:ss`（`02:05`），不再写成 `125.4s`。
        val segments = abReadoutSegments(
            pointAMillis = 0,
            pointBMillis = 125_400,
            loopCountLabel = "循环 ×1",
            deltaPrefix = "Δ",
        )
        assertEquals("A 00:00 — B 02:05 · Δ 02:05 · 循环 ×1", segments.texts.joinToString(" "))
    }

    @Test
    fun `a half hour interval on a ninety five minute video reads as mm ss`() {
        // 用户实际会看到的那条：95 分钟的片子上设一段 30 分钟的区间。
        // 旧实现会写成 `1800.0s`（读起来像出错），现在与两个端点同一份格式化 → `30:00`。
        val segments = abReadoutSegments(
            pointAMillis = 600_000,
            pointBMillis = 2_400_000,
            loopCountLabel = "循环 ×3",
            deltaPrefix = "Δ",
        )
        assertEquals("A 10:00 — B 40:00 · Δ 30:00 · 循环 ×3", segments.texts.joinToString(" "))
    }

    /**
     * **仅 A** 那一态：中段是"B 待落点"，不是箭头、也不是空。
     *
     * 箭头（旧实现的 `→`）在只设了一端时"指向虚空"，而 demo 的第 2 态给的是明确的待落点文案。
     * 循环计数与时长都不出现：循环还没开始，显示 `循环 ×0` 会让人以为"已经在循环但一次没跑"。
     */
    @Test
    fun `an incomplete interval shows the points it has and no duration or count`() {
        val onlyA = abReadoutSegments(
            pointAMillis = 12_000,
            pointBMillis = null,
            loopCountLabel = "循环 ×0",
            pendingLabel = "B 待落点",
            deltaPrefix = "Δ",
        )
        assertEquals("A 00:12 — B 待落点", onlyA.texts.joinToString(" "))
        // 三段：A / `—` / `B 待落点`（分隔符在——三种状态的骨架一致，用户切换设置时整行不会跳）。
        assertEquals(3, onlyA.segments.size)
        assertEquals(
            listOf(
                AbReadoutSegmentKind.POINT_A,
                AbReadoutSegmentKind.PUNCTUATION,
                AbReadoutSegmentKind.HINT,
            ),
            onlyA.segments.map { it.kind },
        )
        // `B 待落点` 不是点击目标：没有 B 可跳。
        assertNull("待落点不是点击目标", onlyA.segments[2].pointMillis)
        assertTrue(
            "只设了 A 时不允许出现 Δ 或计数",
            onlyA.segments.none {
                it.kind == AbReadoutSegmentKind.DELTA || it.kind == AbReadoutSegmentKind.LOOP_COUNT
            },
        )

        // 只设了 B（理论上不该出现，但纯函数不能因此崩掉）：没有 A 就没有"从哪到哪"，中段不出现。
        val onlyB = abReadoutSegments(
            pointAMillis = null,
            pointBMillis = 37_000,
            loopCountLabel = "循环 ×0",
            pendingLabel = "B 待落点",
            deltaPrefix = "Δ",
        )
        assertEquals("B 00:37", onlyB.texts.joinToString(" "))
    }

    /**
     * **未设置**那一态：必须显示**引导文案**，不留空白（demo 的第 1 态）。
     *
     * 旧实现里"一个点都没设"= 整行不显示，用户对着一条空轨道只能猜"这里能不能设点"。
     */
    @Test
    fun `the empty state shows the guidance copy instead of a blank line`() {
        val segments = abReadoutSegments(
            pointAMillis = null,
            pointBMillis = null,
            loopCountLabel = "循环 ×0",
            noneLabel = "未设置循环 · 点 A 在播放头落点",
        )
        assertEquals(1, segments.segments.size)
        assertEquals("未设置循环 · 点 A 在播放头落点", segments.texts.joinToString(" "))
        assertEquals(AbReadoutSegmentKind.HINT, segments.segments.single().kind)
        assertNull("引导文案不是点击目标", segments.segments.single().pointMillis)
    }

    @Test
    fun `the readout uses the same time format as the progress row`() {
        // 与进度行的时间文本同源同格式：同一段视频的同一时刻在两处不能显示成两个样子。
        assertEquals("00:12", formatAbTime(12_000))
        assertEquals(formatDuration(12_000), formatAbTime(12_000))
        assertEquals("--:--", formatDuration(null))
    }

    /**
     * 进度行时间格式的**两档边界**（本批定稿：不足 1 小时 `mm:ss`、1 小时起 `hh:mm:ss` 且小时补零）。
     *
     * 旧实现超过一小时**不进位**（95 分钟 → `95:00`）：分钟位超过 59 既不是 `mm:ss` 的语义，
     * 也让"这片子多长"要多做一次心算。这里把两档的**切换点**与小时补零一起钉住。
     */
    @Test
    fun `the progress time format switches to zero padded hours exactly at one hour`() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("00:12", formatDuration(12_000))
        // 切换点两侧：59:59 仍是 mm:ss，1:00:00 起进位成 hh:mm:ss。
        assertEquals("59:59", formatDuration(59 * 60_000L + 59_000L))
        assertEquals("01:00:00", formatDuration(60 * 60_000L))
        // 同一秒内的毫秒不进位（格式只到秒）。
        assertEquals("01:00:00", formatDuration(60 * 60_000L + 999L))
        // 用户给的例子：95 分钟的影片 → 01:35:00（小时补零）。
        assertEquals("01:35:00", formatDuration(95 * 60_000L))
        assertEquals("10:05:03", formatDuration((10 * 3_600L + 5 * 60L + 3L) * 1_000L))
        // 越界输入（不该出现）不许产出负号。
        assertEquals("00:00", formatDuration(-1))
        assertEquals("--:--", formatDuration(null))
    }

    /**
     * 区间时长只有**一档**：与端点、进度行两端同一份格式化（`mm:ss`，1 小时起 `hh:mm:ss` 且补零）。
     *
     * **有意偏离 demo**（demo 的 `fmtP` 在 1 小时以上不补零，输出 `1:03:36`）：同一个进度区里
     * "端点 / 区间"必须逐字符同源，补零与不补零混用会让两处看起来像两种格式。
     * **另一处有意偏离**：旧实现不足 60s 时用一位小数秒（`5.0s`），本批统一到 `mm:ss` ——
     * demo 的事实就是 `Δ 03:36` 这一种写法，`Δ` 本身已经声明了"这是时长"。
     */
    @Test
    fun `the interval duration has exactly one format and it is the same as the progress row`() {
        assertEquals("00:05", formatAbDelta(5_000))
        assertEquals("00:00", formatAbDelta(0))
        // 越界输入（不该出现）不许产出 `-1.0s` 这种读不通的文案。
        assertEquals("00:00", formatAbDelta(-500))
        // 短区间也走 `mm:ss`（不再是 `5.0s`）：格式只到秒，**截断**而不是四舍五入
        //（与端点、进度行两端同一份实现）。
        assertEquals("00:04", formatAbDelta(4_950))
        assertEquals("00:05", formatAbDelta(5_050))
        listOf(0L, 5_000L, 25_000L, 59_999L, 60_000L, 125_400L, 600_000L, 1_800_000L, 3_600_000L, 5_400_000L)
            .forEach { millis ->
                assertEquals("同源不是'看起来像'：${millis}ms", formatDuration(millis), formatAbDelta(millis))
            }
        // **一小时以上沿用我们的补零口径**（`Δ 01:35:00`），不采纳 demo 的 `1:03:36`。
        assertEquals("01:00:00", formatAbDelta(3_600_000))
        assertEquals("01:30:00", formatAbDelta(5_400_000))
        assertEquals("01:35:00", formatAbDelta(95 * 60_000L))
    }

    @Test
    fun `a capture result is published only while its generation media and state still match`() {
        val media = MediaItemId("media_1")
        val other = MediaItemId("media_2")

        assertTrue(
            isScreenshotCaptureResultCurrent(
                captureGeneration = 5,
                currentGeneration = 5,
                captureMediaId = media,
                currentMediaId = media,
                state = ScreenshotUiState.Capturing,
            ),
        )
        // 期间发生过任何一次"结束/重开截图会话、打开 AB 工具、切媒体" → generation 变了 → 丢弃。
        assertFalse(
            isScreenshotCaptureResultCurrent(5, 6, media, media, ScreenshotUiState.Capturing),
        )
        assertFalse(
            isScreenshotCaptureResultCurrent(5, 5, media, other, ScreenshotUiState.Capturing),
        )
        assertFalse(
            isScreenshotCaptureResultCurrent(5, 5, media, media, ScreenshotUiState.Idle),
        )
        // **这一条就是"就地比状态"挡不住的时序**：状态绕回了 Capturing，
        // 只有 generation 还记得"这是上一次会话的旧结果"。
        assertFalse(
            isScreenshotCaptureResultCurrent(4, 6, media, media, ScreenshotUiState.Capturing),
        )
    }

    /**
     * 读数条的字号只走一个出口：[abReadoutFontSizeSp]。它按**读数条自己的宽度模型**
     *（[abReadoutWidthDp]）反推，并保证"算出来的字号确实放得下整行 + 两枚数值点击目标"。
     *
     * 起点取 `320dp`（比 360dp 竖屏的进度区带宽 336dp 略窄）：那一档 13sp 上限放得下 ——
     * 窄屏的行为（字号落到 10sp 下限）由断言覆盖，不在这里假装放得下。
     */
    @Test
    fun `the readout font size always fits the whole readout line`() {
        val complete = abReadoutSegments(
            pointAMillis = 12_000,
            pointBMillis = 37_000,
            loopCountLabel = "循环 ×12",
            deltaPrefix = "Δ",
        )
        val text = complete.texts.joinToString(" ")
        listOf(320.dp, 400.dp, 600.dp).forEach { available ->
            val fontSize = abReadoutFontSizeSp(
                availableWidth = available,
                text = text,
                chipBudget = AbValueTapTargetWidth * 2,
                baseFontSize = 14.sp,
            )
            assertTrue(
                "available=$available fontSize=$fontSize：算出的字号必须真的放得下（含两枚 32dp 点击目标）",
                abReadoutWidthDp(text, fontSize).value + AbValueTapTargetWidth.value * 2 <= available.value + 0.01f,
            )
            // 13sp 是上限，而且它**压过主题字号**（labelLarge 14sp）。
            assertTrue("字号不许超过 demo 的 13sp 上限：$fontSize", fontSize.value <= 13f)
        }
        assertEquals(
            "宽到 600dp：13sp 的整行放得下 → 取到上限（而不是主题的 14sp）",
            13.sp,
            abReadoutFontSizeSp(
                availableWidth = 600.dp,
                text = text,
                chipBudget = AbValueTapTargetWidth * 2,
                baseFontSize = 14.sp,
            ),
        )
        // 数值点击目标的宽度下限（32dp）与端点徽标的热区是**同一个常量**：两处热区必须同档。
        assertEquals(32.dp, AbValueTapTargetWidth)
    }

    /**
     * 极窄屏 / 2 倍系统字号：字号被 10sp 下限托住 —— 宁可溢出（由上游裁剪）也不缩到看不清。
     *
     * 这是全项目唯一的"仍算可读"下限（与帧数胶囊共用同一个常量）。
     */
    @Test
    fun `a readable floor keeps the readout from shrinking into nothing`() {
        val text = abReadoutSegments(
            pointAMillis = 12_000,
            pointBMillis = 37_000,
            loopCountLabel = "循环 ×12",
            deltaPrefix = "Δ",
        ).texts.joinToString(" ")
        assertEquals(
            10.sp,
            abReadoutFontSizeSp(
                availableWidth = 40.dp,
                text = text,
                chipBudget = AbValueTapTargetWidth * 2,
                baseFontSize = 14.sp,
            ),
        )
        // 2 倍系统字号：整行需要的宽度翻倍，字号随之变小（但仍然不破下限）。
        val zoomed = abReadoutFontSizeSp(
            availableWidth = 320.dp,
            text = text,
            chipBudget = AbValueTapTargetWidth * 2,
            baseFontSize = 14.sp,
            fontScale = 2f,
        )
        assertTrue("2 倍系统字号下必须缩小：$zoomed", zoomed.value < 13f)
        assertEquals(PlayerChromeTextMinFontSize, 10.sp)
    }

    /** 读数行的验收句（本批格式；字号模型与 instrumented 断言共用同一句）。 */
    private val ReadoutLine = "A 00:12 — B 00:37 · Δ 00:25 · 循环 ×12"

    private fun preview() = ScreenshotUiState.Preview(
        displayName = "frame.jpg",
        uri = "content://frame",
        location = "Pictures/YingLi/frame.jpg",
    )
}
