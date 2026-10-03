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
 * 阶段 3（UI 与交互）里那些**纯判定**的可验证部分：辅助带那一格归谁、AB 是否生效、
 * 设置点按钮的文案、弹性宽度按钮的字号适配、以及截图捕获"异步晚到"的发布契约。
 *
 * 它们都是纯函数/纯数据类，所以能在 JVM 上逐条钉住；真正"画出来是什么样"归
 * `androidTest/.../PlayerAbLoopScreenTest`，"三分支的时序"归 `PlayerViewModelTest`。
 * 这一分层的意义：把"谁该出现"（这里）与"点下去之后状态怎么变"（ViewModel 测试）
 * 分开断言，哪一层坏了都能一眼定位。
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

    @Test
    fun `point buttons show the time once set and the setup label before that`() {
        assertEquals("A 00:12", abPointLabel("A", 12_000, "设置"))
        assertEquals("B 00:37", abPointLabel("B", 37_000, "设置"))
        assertEquals("A 设置", abPointLabel("A", null, "设置"))
        assertEquals("B 设置", abPointLabel("B", null, "设置"))
        // 与进度行的时间文本同源同格式：同一段视频的同一时刻在两处不能显示成两个样子。
        assertEquals("00:12", formatAbTime(12_000))
        assertEquals(formatDuration(12_000), formatAbTime(12_000))
        assertEquals("--:--", formatDuration(null))
    }

    @Test
    fun `point button descriptions describe the action before it is set and the value after`() {
        assertEquals("设置 A 点", abPointDescription(null, "设置 A 点", "A 点 %1\$s"))
        assertEquals("A 点 00:12", abPointDescription("00:12", "设置 A 点", "A 点 %1\$s"))
    }

    @Test
    fun `roomy width keeps the base font size`() {
        // 正常机型（竖屏 360dp 上下、基础字号 14sp）：整排标签远远放得下 → 不做任何缩放。
        assertEquals(
            14.sp,
            playerChromeTextFontSizeSp(
                availableWidth = 236.dp,
                labels = listOf("A 00:12", "B 00:37", "清除"),
                baseFontSize = 14.sp,
            ),
        )
    }

    @Test
    fun `narrow width shrinks the font instead of truncating the label`() {
        // 极窄屏 / 最大系统字号：可用宽度不够时缩字号。这里 3.85em 的 "A 00:12" 只有 20dp，
        // 算出来约 5.2sp，被 10sp 下限托住 —— 缩到下限为止，绝不产出 `A 00…` 这种省略。
        assertEquals(
            10.sp,
            playerChromeTextFontSizeSp(
                availableWidth = 20.dp,
                labels = listOf("A 00:12"),
                baseFontSize = 14.sp,
            ),
        )
        // 刚好放得下时按比例给：25dp / (2 个全角 × 1.1em × 1.03) ≈ 11.03sp，仍在基础字号之下。
        assertEquals(
            11.03f,
            playerChromeTextFontSizeSp(
                availableWidth = 25.dp,
                labels = listOf("清除"),
                baseFontSize = 14.sp,
            ).value,
            0.05f,
        )
    }

    @Test
    fun `wide characters cost more width than narrow ones`() {
        // 同一宽度下，全角（CJK）标签必须比半角标签更早撞到下限：
        // "清除"=2em，"AB"=1.1em。这是字号模型里唯一需要区分的两档。
        val cjk = playerChromeTextFontSizeSp(16.dp, listOf("清除"), 14.sp).value
        val latin = playerChromeTextFontSizeSp(16.dp, listOf("AB"), 14.sp).value
        assertEquals(10f, cjk)
        assertEquals(14f, latin)
    }

    @Test
    fun `no measurable width falls back to the base font size`() {
        // 尚未布局（0 / 负宽度）或标签为空时不做缩放：返回基础字号，避免出现"字号 0"的不可见文本。
        assertEquals(14.sp, playerChromeTextFontSizeSp(0.dp, listOf("A 00:12"), 14.sp))
        assertEquals(14.sp, playerChromeTextFontSizeSp((-1).dp, listOf("A 00:12"), 14.sp))
        assertEquals(14.sp, playerChromeTextFontSizeSp(200.dp, emptyList(), 14.sp))
        assertEquals(14.sp, playerChromeTextFontSizeSp(200.dp, listOf(""), 14.sp))
    }

    @Test
    fun `system font scale counts against the available width`() {
        // 同一处宽度：系统字号放大一倍，能放下的 sp 就少一半（上限放开到不影响判断的 200sp）。
        // 这一项漏掉的后果实测过：2 倍字号下字号算大了，三枚按钮把关闭圆钮挤成 0 宽。
        val labels = listOf("A 00:12")
        val single = playerChromeTextFontSizeSp(400.dp, labels, 200.sp, fontScale = 1f).value
        // 模型：7 个窄字符 × 0.55em × 1.03 的安全余量 = 3.9655em → 400 / 3.9655 ≈ 100.87sp。
        assertEquals(400f / (3.85f * 1.03f), single, 0.05f)
        assertEquals(single / 2f, playerChromeTextFontSizeSp(400.dp, labels, 200.sp, fontScale = 2f).value, 0.01f)
    }

    @Test
    fun `the estimated width is what the chosen font size actually needs`() {
        // 估算宽度与"选字号"用的是同一个模型，所以下式必须成立：按可用宽度选出的字号，其估算宽度 ≤ 可用宽度。
        listOf(120.dp, 180.dp, 236.dp, 400.dp).forEach { available ->
            val labels = listOf("A 00:12", "B 00:37", "清除")
            val fontSize = playerChromeTextFontSizeSp(available, labels, 14.sp)
            assertTrue(
                "available=$available fontSize=$fontSize",
                playerChromeTextEstimatedWidthDp(labels, fontSize).value <= available.value + 0.01f,
            )
        }
    }

    @Test
    fun `the ab capsule keeps the roomy metrics whenever they still fit`() {
        // 常规机型（竖屏 400dp 带宽 ≈ 376dp、1 倍字号）：常规档就放得下，字号就是基础字号。
        val labels = listOf("A 00:12", "B 00:37", "清除")
        val layout = abCapsuleTextLayout(376.dp, labels, 14.sp, fontScale = 1f)
        assertEquals(ScreenshotCapsuleInnerPadding, layout.innerPadding)
        assertEquals(PlayerScreenshotCapsuleButtonSpacing, layout.spacing)
        assertEquals(PlayerChromeTextButtonHorizontalPadding, layout.textPadding)
        assertEquals(14.sp, layout.fontSize)
        assertTrue(layout.labelsFit(labels, 1f))
    }

    @Test
    fun `a large system font scale first trades padding for readable text`() {
        // 360dp 带宽 + 2 倍系统字号：常规档在 10sp 下限上也放不下（实测会把关闭键挤没），
        // 于是整体切紧凑档 —— 内边距、间距、文字按钮内边距一起收窄，字号仍然 ≥ 可读下限。
        val labels = listOf("A 00:12", "B 00:37", "清除")
        val layout = abCapsuleTextLayout(336.dp, labels, 14.sp, fontScale = 2f)
        assertEquals(ScreenshotCapsuleInnerPaddingCompact, layout.innerPadding)
        assertEquals(PlayerScreenshotCapsuleButtonSpacingCompact, layout.spacing)
        assertEquals(PlayerChromeTextButtonCompactHorizontalPadding, layout.textPadding)
        assertTrue("字号必须仍可读：${layout.fontSize}", layout.fontSize.value >= PlayerChromeTextMinFontSize.value)
        assertTrue("紧凑档必须真的放得下：$layout", layout.labelsFit(labels, 2f))
    }

    @Test
    fun `narrow landscape and large font all fit at or above the readable floor`() {
        // 验收矩阵：三种验收场景（窄屏 / 横屏 / 系统字号放大）及其最坏组合，
        // 只要设备宽度 ≥ 320dp、系统字号 ≤ 2 倍，选出来的排版就必须真的放得下。
        val labels = listOf("A 00:12", "B 00:37", "清除")
        val cases = buildList {
            listOf(320.dp, 336.dp, 360.dp, 400.dp, 768.dp).forEach { width ->
                listOf(1f, 1.3f, 1.5f, 2f).forEach { fontScale ->
                    add(width to fontScale)
                }
            }
        }
        cases.forEach { (width, fontScale) ->
            // 带宽 = 屏幕宽 - 底栏两侧内边距（竖屏 12dp × 2）；横屏取更宽的档，这里直接用宽值代表。
            val band = width - 24.dp
            val layout = abCapsuleTextLayout(band, labels, 14.sp, fontScale)
            assertTrue(
                "width=$width fontScale=$fontScale layout=$layout",
                layout.fontSize.value >= PlayerChromeTextMinFontSize.value,
            )
            assertTrue(
                "width=$width fontScale=$fontScale 放不下：$layout",
                layout.labelsFit(labels, fontScale),
            )
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

    private fun preview() = ScreenshotUiState.Preview(
        displayName = "frame.jpg",
        uri = "content://frame",
        location = "Pictures/YingLi/frame.jpg",
    )
}
