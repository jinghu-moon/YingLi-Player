package seeyuer.yingli.player.feature.player

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import seeyuer.yingli.player.R
import seeyuer.yingli.player.app.playback.PlaybackSessionClientBridge
import seeyuer.yingli.player.core.common.DefaultAppDispatchers
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.playback.AbLoopCommandOutcome
import seeyuer.yingli.player.domain.playback.AbLoopLimiter
import seeyuer.yingli.player.domain.playback.AbLoopPlaybackControl
import seeyuer.yingli.player.domain.playback.AbLoopSession
import seeyuer.yingli.player.domain.playback.AbLoopSetPointResult
import seeyuer.yingli.player.domain.playback.AbLoopState
import seeyuer.yingli.player.domain.playback.AbPoint
import seeyuer.yingli.player.domain.playback.MutableAbLoopSessionStore
import seeyuer.yingli.player.domain.playback.PauseReason
import seeyuer.yingli.player.domain.playback.PlaybackCommandId
import seeyuer.yingli.player.domain.playback.PlaybackCommandRejection
import seeyuer.yingli.player.domain.playback.PlaybackCommandResult
import seeyuer.yingli.player.domain.playback.PlaybackConnectionState
import seeyuer.yingli.player.domain.playback.PlaybackController
import seeyuer.yingli.player.domain.playback.PlaybackMediaInfo
import seeyuer.yingli.player.domain.playback.PlaybackRequest
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackSourceRepository
import seeyuer.yingli.player.domain.playback.PlaybackState
import seeyuer.yingli.player.domain.playback.PlaybackTimeline
import seeyuer.yingli.player.domain.playback.ResolvedPlaybackSource
import kotlin.math.abs

/**
 * AB 胶囊四个按钮的**端到端命令接线**（instrumented：真机布局 + 真实 `PlayerViewModel`
 * + 真实 `PlaybackSessionClientBridge` 命令路径）。
 *
 * ## 为什么必须有这一层
 *
 * 之前三层测试都不覆盖"用户按下胶囊里的按钮"：
 *  - `PlayerAbLoopScreenTest` 用 `PlayerUiState` 直接摆状态，`onSetAbPoint` 走的是 `PlayerScreen`
 *    的**默认空实现** —— 按钮接没接到命令，它一个字都断言不到；
 *  - `AbLoopActivationInstrumentedTest` / `AbBoundaryLoopInstrumentedTest` 驱动的是**会话命令**，
 *    从没经过一次真实的点击；
 *  - 播放页在本机无法用 `adb shell input tap` 命中控件（`uiautomator dump` 只返回 9 个无文本节点）。
 *
 * 于是"点了没反应"在整条测试链上是**空白**。这里补上：`performClick()` 之后断言**状态**。
 *
 * ## 断言"状态"而不是"回调被调用"
 *
 * 点击 → `PlayerViewModel.setAbPoint` → `PlaybackSessionClientBridge.dispatch(SetAbPoint)`
 * → 控制器（[FakeAbController]，域规则直接用 [AbLoopLimiter]）→ `abLoop` 状态回流
 * → `PlayerUiState.abLoop`。因此断言 `pointA/pointB` 等于断言"这条命令真的走完了整条链"，
 * 而不是"某个 lambda 被叫了一次"。
 *
 * **这条链也是这组用例的主要防线**：`PlayerScreen` 的四个按钮回调一旦被传成默认空实现
 *（`onSetAbPoint = {}` 之类），或者胶囊被同格/父层的透明层吃掉点击，第一个用例就会红。
 */
@RunWith(AndroidJUnit4::class)
class PlayerAbLoopCapsuleCommandTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * 四个按钮各自的状态断言（A → B → 关闭 → 清除），顺序即用户真实操作顺序。
     *
     * 合成一个用例是刻意的：这四步本来就是**同一次操作序列**，拆开会丢掉
     * "B 是否因为刚才设了 A 才可用"这种跨步骤的状态传递。
     */
    @Test
    fun capsuleButtonsDriveTheRealAbCommands() {
        val controller = FakeAbController(
            startingPositionMillis = A_POSITION_MILLIS,
            // 播放继续往前走：两次设点读到的实时位置不同（真实播放器就是这样）。
            advanceMillis = B_POSITION_MILLIS - A_POSITION_MILLIS,
        )
        val harness = CapsuleHarness(controller)

        // 1) 「A 设置」：必须是"用实时位置设上 A"，而不是什么都没发生。
        composeRule.onNodeWithContentDescription(SET_A).performClick()
        harness.awaitState { it.abLoop.pointA == A_POSITION_MILLIS }
        assertEquals("A 必须设在实时位置上", A_POSITION_MILLIS, controller.pointA())
        assertNull("只设了 A 时 B 仍为空", controller.pointB())
        assertEquals("必须发出且只发出一次 SetAbPoint(A)", 1, controller.setPointCommands[AbPoint.A])
        // 设上了 A → 那枚圆钮进入"已设置"状态（filled 的语义表达，可断言）。
        composeRule.onNodeWithContentDescription(SET_A).assertIsSelected()
        // B 由禁用变为可用：这条只有"真实点击 + 真实状态回流"才能断言。
        composeRule.onNodeWithContentDescription(SET_B).assertIsEnabled()
        composeRule.onNodeWithContentDescription(CLEAR).assertIsEnabled()

        // 2) 「B 设置」：设上 B，并且区间生效（循环开始、回跳到 A）。
        composeRule.onNodeWithContentDescription(SET_B).performClick()
        harness.awaitState { it.abLoop.pointB == B_POSITION_MILLIS }
        assertEquals("B 必须设在实时位置上", B_POSITION_MILLIS, controller.pointB())
        assertEquals("必须发出一次 SetAbPoint(B)", 1, controller.setPointCommands[AbPoint.B])
        harness.awaitState { it.abLoop.active }
        assertTrue("区间完整后 active 必须为真", harness.state().abLoop.active)
        assertEquals("区间生效必须回跳到 A", listOf(A_POSITION_MILLIS), controller.seekTargets)

        // 3) 「关闭」：只收 UI（D3：关闭 ≠ 取消），区间必须留着。
        composeRule.onNodeWithContentDescription(CLOSE).performClick()
        harness.awaitState { !it.abToolOpen }
        composeRule.onNodeWithTag(PlayerTestTags.AB_CAPSULE).assertDoesNotExist()
        assertEquals(
            "关闭胶囊不得动区间",
            AbLoopState(pointA = A_POSITION_MILLIS, pointB = B_POSITION_MILLIS),
            controller.abLoopState(),
        )
        assertTrue("关闭胶囊后循环仍然生效", harness.state().abLoop.active)
        assertTrue("关闭不是清除：不得发出 ClearAb", controller.clearCommands == 0)

        // 4) 重新打开后「清除」：两点归零、循环结束，而胶囊自己仍然开着（清除 ≠ 关闭）。
        harness.openTool()
        harness.awaitState { it.abToolOpen }
        composeRule.onNodeWithContentDescription(CLEAR).performClick()
        harness.awaitState { !it.abLoop.active }
        assertNull("清除后 A 必须归零", controller.pointA())
        assertNull("清除后 B 必须归零", controller.pointB())
        assertEquals("清除必须发出一次 ClearAb", 1, controller.clearCommands)
        assertTrue("清除后胶囊仍然开着", harness.state().abToolOpen)
        composeRule.onNodeWithTag(PlayerTestTags.AB_CAPSULE).assertExists()
    }

    /**
     * 设点被会话拒绝（未知时长 / 不可 seek）时必须有**用户可见反馈**。
     *
     * 会话回拒绝码 → `PlaybackSessionClientBridge.feedback` → `OneShotFeedback`
     * → ViewModel 翻成 `PlayerUiEvent.TransientMessage` → `PlayerScreen` 渲染瞬时提示。
     * 这里断言这条链的终点（读到反馈事件），而不是只断言"拒绝码产生了"；
     * 同时断言被拒的设点**没有**写进状态。
     */
    @Test
    fun aRejectedSetPointSurfacesAUserVisibleMessage() {
        val controller = FakeAbController(
            startingPositionMillis = A_POSITION_MILLIS,
            rejection = PlaybackCommandRejection.AB_UNAVAILABLE,
        )
        val harness = CapsuleHarness(controller)

        composeRule.onNodeWithContentDescription(SET_A).performClick()
        val message = harness.awaitMessage()
        assertNotNull("设点被拒必须给出瞬时反馈，而不是静默无效", message)
        assertNull("被拒的设点不得写进状态（否则就是看起来成功了）", controller.pointA())
        harness.awaitState { !it.abLoop.active }
    }

    /**
     * 胶囊内按钮行的**竖直居中**：每个按钮的中心必须与胶囊中心重合（±1px），
     * 且按钮上下留白之差 ≤1px。
     *
     * 之前只断言了**外层**（胶囊顶边 / 高度与截图胶囊一致、完整落在辅助带内）——
     * 胶囊自己居中而内容贴在顶边时，那些断言全绿，用户看到的却是"按钮位置不对"。
     * 这里把"内容在胶囊里居中"变成可断言的事实。
     */
    @Test
    fun capsuleButtonsAreVerticallyCenteredInsideTheCapsule() {
        CapsuleHarness(FakeAbController(startingPositionMillis = A_POSITION_MILLIS))

        val capsule = composeRule.onNodeWithTag(PlayerTestTags.AB_CAPSULE).getUnclippedBoundsInRoot()
        listOf(SET_A, SET_B, CLEAR, CLOSE).forEach { label ->
            val button = composeRule.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
            val topGap = button.top.value - capsule.top.value
            val bottomGap = capsule.bottom.value - button.bottom.value
            assertTrue(
                "$label 的上下留白必须相等：上=$topGap 下=$bottomGap capsule=$capsule button=$button",
                abs(topGap - bottomGap) <= 1f,
            )
            val buttonCenter = (button.top.value + button.bottom.value) / 2f
            val capsuleCenter = (capsule.top.value + capsule.bottom.value) / 2f
            assertTrue(
                "$label 的中心必须与胶囊中心重合：按钮=$buttonCenter 胶囊=$capsuleCenter",
                abs(buttonCenter - capsuleCenter) <= 1f,
            )
        }
    }

    /** 未设 A 时 B 与清除都不可用；A 永远可用（"没有 A 就没有区间"这条规则只压 B/清除）。 */
    @Test
    fun clearAndBStayDisabledUntilAPointExists() {
        val controller = FakeAbController(startingPositionMillis = A_POSITION_MILLIS)
        val harness = CapsuleHarness(controller)

        composeRule.onNodeWithContentDescription(SET_A).assertIsEnabled()
        composeRule.onNodeWithContentDescription(SET_B).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(CLEAR).assertIsNotEnabled()

        composeRule.onNodeWithContentDescription(SET_A).performClick()
        harness.awaitState { it.abLoop.pointA != null }
        composeRule.onNodeWithContentDescription(SET_B).assertIsEnabled()
        composeRule.onNodeWithContentDescription(CLEAR).assertIsEnabled()
    }

    /**
     * 拒绝提示必须**看得见**：设点被拒时播放页上要真的出现一行提示。
     *
     * 为什么这条要单独存在：`aRejectedSetPointSurfacesAUserVisibleMessage` 断言的是
     * "拒绝码 → 瞬时反馈事件"；而"事件 → 屏幕上真的有字"是**另一跳**。用户报的现象里
     * 正好有一句"没有任何提示"，所以这一跳必须有自己的断言 —— 把"被拒那一刻"的 UI 输入
     *（AB 胶囊开着 + 一条拒绝提示）直接摆到 `PlayerScreen` 上，断言文案在屏幕上可见。
     */
    @Test
    fun aRejectedSetPointShowsItsMessageOnThePlayerPage() {
        val expected = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.player_reject_ab_unavailable)
        composeRule.setContent {
            YingLiTheme(darkTheme = true) {
                PlayerScreen(
                    state = PlayerUiState(
                        playback = PlaybackState.Paused(REQUEST, TIMELINE),
                        abToolOpen = true,
                    ),
                    onBack = {}, onPlay = {}, onPause = {}, onSeek = {}, onReplay = {}, onRetry = {},
                    onRecovery = {}, videoSurface = {},
                    transientMessage = PlayerUiEvent.TransientMessage(R.string.player_reject_ab_unavailable),
                )
            }
        }

        // 提示与 AB 胶囊同屏：用户正是"在胶囊里设点被拒"的那一刻。
        composeRule.onNodeWithTag(PlayerTestTags.AB_CAPSULE).assertExists()
        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }

    // ---- 夹具 ----------------------------------------------------------------

    /**
     * 真实 ViewModel + 真实桥接命令路径 + 真实 `PlayerScreen` 布局；
     * 按钮回调与 `YingLiApp` 的接线**逐字相同**：
     * `onSetAbPoint = { viewModel.setAbPoint(it) }`、`onClearAb = viewModel::clearAb`。
     */
    private inner class CapsuleHarness(private val controller: FakeAbController) {
        val client = PlaybackSessionClientBridge(
            controller = controller,
            sourceRepository = UnusedSourceRepository,
            dispatchers = DefaultAppDispatchers,
        )
        private val viewModel = PlayerViewModel(
            sessionClient = client,
            dispatchers = DefaultAppDispatchers,
            ownsSessionClient = false,
        )
        private val projected = mutableStateOf(PlayerUiState())
        private var lastMessage: PlayerUiEvent.TransientMessage? = null

        init {
            composeRule.setContent {
                LaunchedEffect(Unit) { viewModel.state.collect { projected.value = it } }
                LaunchedEffect(Unit) {
                    viewModel.event.collect { event ->
                        if (event is PlayerUiEvent.TransientMessage) lastMessage = event
                    }
                }
                YingLiTheme(darkTheme = true) {
                    PlayerScreen(
                        state = projected.value,
                        onBack = {}, onPlay = {}, onPause = {}, onSeek = {}, onReplay = {}, onRetry = {},
                        onRecovery = {}, videoSurface = {},
                        onOpenAbTool = viewModel::openAbTool,
                        onSetAbPoint = { viewModel.setAbPoint(it) },
                        onClearAb = viewModel::clearAb,
                        onCloseAbTool = viewModel::closeAbTool,
                    )
                }
            }
            composeRule.waitUntil(TIMEOUT_MILLIS) { projected.value.playback is PlaybackState.Paused }
            openTool()
            awaitState { it.abToolOpen }
        }

        /** 与"点托盘里的 AB 按钮"同一入口。 */
        fun openTool() {
            composeRule.runOnIdle { viewModel.openAbTool() }
        }

        fun state(): PlayerUiState = projected.value

        fun awaitState(predicate: (PlayerUiState) -> Boolean) {
            composeRule.waitUntil(TIMEOUT_MILLIS) { predicate(projected.value) }
            composeRule.waitForIdle()
        }

        /** 读一条瞬时反馈；超时算"没有反馈"（返回 null，让断言给出可读的失败）。 */
        fun awaitMessage(): PlayerUiEvent.TransientMessage? =
            runCatching {
                composeRule.waitUntil(MESSAGE_TIMEOUT_MILLIS) { lastMessage != null }
                lastMessage
            }.getOrNull()
    }

    /**
     * 控制器替身：实现 AB 命令口与 `PlaybackController` 的**必要部分**（状态与实时位置）。
     *
     * 设点逻辑不在这里重写：直接喂域层的 [AbLoopLimiter]（吸附 / 互换 / 相等边界 / 一帧间隔
     * 全在它里面），再按真实 `PlaybackSessionRuntime.applyAbPoint` 的做法把结果发布到
     * [abLoop]、设点是新一轮配置因此计数归零、区间设全时回跳一次。
     */
    private class FakeAbController(
        startingPositionMillis: Long,
        /** 每次设点后实时位置前进多少毫秒：真实播放器读两次不会得到同一个值。 */
        private val advanceMillis: Long = 0L,
        private val rejection: PlaybackCommandRejection? = null,
    ) : PlaybackController, AbLoopPlaybackControl {
        private val limiter = AbLoopLimiter(fallbackFrameRate = FRAME_RATE)
        private val abLoopStore = MutableAbLoopSessionStore()
        private var positionMillis = startingPositionMillis

        /** 每个点各被设了几次：由真实的 `requestSetAbPoint` 驱动，测试从不自己填。 */
        val setPointCommands = mutableMapOf<AbPoint, Int>()
        val seekTargets = mutableListOf<Long>()
        var clearCommands = 0
            private set

        private val mutableState = MutableStateFlow<PlaybackState>(
            PlaybackState.Paused(REQUEST, TIMELINE),
        )
        override val state: StateFlow<PlaybackState> = mutableState
        override val connectionState = MutableStateFlow(PlaybackConnectionState.CONNECTED)
        override val abLoop: StateFlow<AbLoopSession> = abLoopStore.abLoop

        fun pointA(): Long? = abLoopState().pointA
        fun pointB(): Long? = abLoopState().pointB
        fun abLoopState(): AbLoopState = abLoopStore.abLoop.value.state

        override suspend fun requestSetAbPoint(point: AbPoint): AbLoopCommandOutcome {
            setPointCommands[point] = (setPointCommands[point] ?: 0) + 1
            val code = rejection
            if (code != null) {
                // 能力不足（未知时长 / 不可 seek）：状态一律不动，只回拒绝。
                return AbLoopCommandOutcome.Rejected(code)
            }
            val update = limiter.setPoint(
                state = abLoopState(),
                point = point,
                positionMillis = positionMillis,
                frameRate = FRAME_RATE,
            )
            return when (update) {
                is AbLoopSetPointResult.Rejected -> AbLoopCommandOutcome.Rejected(update.rejection)
                is AbLoopSetPointResult.Applied -> {
                    // 设点是新一轮配置：计数从这一轮重新开始（真实 runtime 同一条规则）。
                    abLoopStore.publish(AbLoopSession(state = update.state, loopCount = 0))
                    // 区间刚刚设全 → 激活 = 立即精确回跳到 A。
                    if (update.state.active) update.state.pointA?.let(seekTargets::add)
                    // 播放器继续往前走：下一次设点读到的实时位置与这一次不同。
                    positionMillis += advanceMillis
                    AbLoopCommandOutcome.Applied
                }
            }
        }

        override fun requestClearAbLoop() {
            clearCommands++
            abLoopStore.publish(AbLoopSession.EMPTY)
        }

        override fun currentPositionMillis(): Long = positionMillis

        override fun prepare(request: PlaybackRequest): PlaybackCommandResult = PlaybackCommandResult.AlreadyApplied
        override fun play(): PlaybackCommandResult = PlaybackCommandResult.AlreadyApplied
        override fun pause(): PlaybackCommandResult = PlaybackCommandResult.AlreadyApplied
        override fun seekTo(positionMillis: Long): PlaybackCommandResult = PlaybackCommandResult.AlreadyApplied
        override fun stop(): PlaybackCommandResult = PlaybackCommandResult.AlreadyApplied
        override fun retry(): PlaybackCommandResult = PlaybackCommandResult.AlreadyApplied
    }

    /**
     * 来源解析替身：**AB 命令路径不允许解析来源**，因此两个方法都直接失败。
     * 这一层不只是"填参数"——它把"设点顺手解析一次来源"这类越界实现变成立刻红的测试。
     */
    private object UnusedSourceRepository : PlaybackSourceRepository {
        override suspend fun resolve(
            mediaId: MediaItemId,
            sourceContext: PlaybackSourceContext,
            incognito: Boolean,
        ): ResolvedPlaybackSource? = error("AB 命令路径不得解析来源")

        override suspend fun resolve(request: PlaybackRequest): ResolvedPlaybackSource? =
            error("AB 命令路径不得解析来源")
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val MESSAGE_TIMEOUT_MILLIS = 3_000L

        /** 两个设点位置都落在整帧上，吸附不改值：断言可以写精确数字。 */
        const val FRAME_RATE = 30f
        const val A_POSITION_MILLIS = 12_000L
        const val B_POSITION_MILLIS = 30_000L

        const val SET_A = "设置 A 点"
        const val SET_B = "设置 B 点"
        const val CLEAR = "清除"
        const val CLOSE = "关闭"

        val REQUEST = PlaybackRequest(
            MediaItemId("media_1"),
            MediaLocationId("location_1"),
            0,
            PlaybackSourceContext.HOME,
        )
        val TIMELINE = PlaybackTimeline(2_000, 600_000)
    }
}
