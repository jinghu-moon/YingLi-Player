package seeyuer.yingli.player.feature.player

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- 读数行文本 -----------------------------------------------------------

    @Test
    fun `the readout shows both times the real interval duration and the loop count`() {
        // 本批定稿格式：`A 00:12 → B 00:17 · 5.0s · 循环 ×12`
        val segments = abReadoutSegments(
            pointAMillis = 12_000,
            pointBMillis = 17_000,
            loopCountLabel = "循环 ×12",
        )
        assertEquals("A 00:12 → B 00:17 · 5.0s · 循环 ×12", segments.texts.joinToString(" "))
        // "区间时长 + 循环次数"是同一段（用 `·` 分隔），它只在区间完整时存在：
        // 4 段 = A / 箭头 / B / （时长 · 循环次数）。
        assertEquals(4, segments.texts.size)
        assertEquals("· 5.0s · 循环 ×12", segments.texts[segments.intervalIndex])
        assertEquals(segments.intervalIndex, segments.loopCountIndex)
    }

    @Test
    fun `a one minute or longer interval reads as mm ss exactly like the endpoints`() {
        // 125.4s 已经不是"短区间"：它走第二档，与端点同源（`02:05`），不再写成 `125.4s`。
        val segments = abReadoutSegments(
            pointAMillis = 0,
            pointBMillis = 125_400,
            loopCountLabel = "循环 ×1",
        )
        assertEquals("A 00:00 → B 02:05 · 02:05 · 循环 ×1", segments.texts.joinToString(" "))
    }

    @Test
    fun `a half hour interval on a ninety five minute video reads as mm ss`() {
        // 用户实际会看到的那条：95 分钟的片子上设一段 30 分钟的区间。
        // 旧实现会写成 `1800.0s`（读起来像出错），现在与两个端点同一份格式化 → `30:00`。
        val segments = abReadoutSegments(
            pointAMillis = 600_000,
            pointBMillis = 2_400_000,
            loopCountLabel = "循环 ×3",
        )
        assertEquals("A 10:00 → B 40:00 · 30:00 · 循环 ×3", segments.texts.joinToString(" "))
    }

    @Test
    fun `an incomplete interval shows the points it has and no duration or count`() {
        // 只设了 A：循环还没开始，显示 `循环 ×0` 会让人以为"已经在循环但一次没跑"，
        // 时长同理（没有 B 就没有区间长度）。
        val onlyA = abReadoutSegments(pointAMillis = 12_000, pointBMillis = null, loopCountLabel = "循环 ×0")
        assertEquals("A 00:12", onlyA.texts.joinToString(" "))
        assertEquals(-1, onlyA.intervalIndex)
        assertEquals(-1, onlyA.loopCountIndex)

        // 只设了 B（理论上不该出现，但纯函数不能因此崩掉）：箭头不出现（它指向虚空）。
        val onlyB = abReadoutSegments(pointAMillis = null, pointBMillis = 37_000, loopCountLabel = "循环 ×0")
        assertEquals("B 00:37", onlyB.texts.joinToString(" "))
        assertEquals(-1, onlyB.intervalIndex)
    }

    @Test
    fun `nothing is set means no readout at all`() {
        val segments = abReadoutSegments(pointAMillis = null, pointBMillis = null, loopCountLabel = "循环 ×0")
        assertTrue(segments.texts.isEmpty())
        assertEquals(-1, segments.intervalIndex)
        assertEquals(-1, segments.loopCountIndex)
    }

    @Test
    fun `the readout uses the same time format as the progress row`() {
        // 与进度行的时间文本同源同格式：同一段视频的同一时刻在两处不能显示成两个样子。
        assertEquals("00:12", formatAbTime(12_000))
        assertEquals(formatDuration(12_000), formatAbTime(12_000))
        assertEquals("--:--", formatDuration(null))
    }

    @Test
    fun `interval under a minute rounds to the nearest tenth of a second and never goes negative`() {
        assertEquals("5.0s", formatAbIntervalDuration(5_000))
        assertEquals("5.0s", formatAbIntervalDuration(4_950))
        assertEquals("5.1s", formatAbIntervalDuration(5_050))
        assertEquals("0.0s", formatAbIntervalDuration(0))
        // 越界输入（不该出现）不许产出 `-1.0s` 这种读不通的文案。
        assertEquals("0.0s", formatAbIntervalDuration(-500))
    }

    @Test
    fun `the two interval formats switch exactly at sixty seconds`() {
        // 边界是"说出名字"的常量：`>= 60_000ms` 走 `mm:ss`。
        // 59_999ms 仍在第一档 —— 它四舍五入之后就是 `60.0s`（一位小数秒的边界形态）。
        assertEquals("60.0s", formatAbIntervalDuration(AbIntervalDurationSecondsFormatThresholdMillis - 1))
        assertEquals("01:00", formatAbIntervalDuration(AbIntervalDurationSecondsFormatThresholdMillis))
    }

    @Test
    fun `a one minute or longer interval uses the same formatter as the progress row`() {
        assertEquals("01:00", formatAbIntervalDuration(60_000))
        assertEquals("10:00", formatAbIntervalDuration(600_000))
        assertEquals("30:00", formatAbIntervalDuration(1_800_000))
        // **不另写 `h:mm:ss`**：一小时以上照样是 `mm:ss`，与 formatDuration（进度行左端的总时长）
        // 同一口径 —— 同一个进度行里区间写 `1:35:00`、端点写 `95:00` 才是真的不一致。
        assertEquals("60:00", formatAbIntervalDuration(3_600_000))
        // 同源不是"看起来像"：长区间这一档必须逐字符等于 formatDuration 的输出。
        listOf(60_000L, 125_400L, 600_000L, 1_800_000L, 3_600_000L, 5_700_000L).forEach { millis ->
            assertEquals(formatDuration(millis), formatAbIntervalDuration(millis))
        }
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
     * 读数行的字号只能有一个来源：`playerChromeTextFontSizeSp`（与帧数胶囊同一套模型）。
     *
     * 这一条守着一个**跨模块**的一致性：AB 读数行用的是**整行**文本，所以只要
     * "算出的字号确实放得下整行"成立，窄屏 / 2 倍字号下就不会被裁。
     *
     * 起点取 `236dp`：整行在 1 倍字号下需要约 225dp，比它更窄时字号已经撞到 10sp 下限
     *（那种组合下的行为由下一条用例单独断言，不在这里假装放得下）。
     */
    @Test
    fun `the readout font size always fits the whole readout line`() {
        listOf(236.dp, 320.dp, 336.dp, 400.dp).forEach { available ->
            val labels = listOf("A 00:12 → B 00:17 · 5.0s · 循环 ×12")
            val fontSize = playerChromeTextFontSizeSp(available, labels, 14.sp)
            assertTrue(
                "available=$available fontSize=$fontSize",
                playerChromeTextEstimatedWidthDp(labels, fontSize).value <= available.value + 0.01f,
            )
        }
    }

    @Test
    fun `a readable floor keeps the readout from shrinking into nothing`() {
        // 极窄屏 + 大字号：字号被 10sp 下限托住 —— 宁可溢出（由上游裁剪）也不缩到看不清。
        // 这是本项目唯一的"仍算可读"下限，与帧数胶囊共用同一个常量。
        assertEquals(
            10.sp,
            playerChromeTextFontSizeSp(
                availableWidth = 40.dp,
                labels = listOf("A 00:12 → B 00:17 · 5.0s · 循环 ×12"),
                baseFontSize = 14.sp,
            ),
        )
        assertEquals(PlayerChromeTextMinFontSize, 10.sp)
    }

    private fun preview() = ScreenshotUiState.Preview(
        displayName = "frame.jpg",
        uri = "content://frame",
        location = "Pictures/YingLi/frame.jpg",
    )
}
