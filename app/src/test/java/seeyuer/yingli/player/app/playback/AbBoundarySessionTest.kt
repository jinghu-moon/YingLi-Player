package seeyuer.yingli.player.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.domain.playback.EngineAbLoop

/**
 * 引擎侧边界检测的判定规则（纯状态机，无 Android / Media3 依赖）。
 *
 * 这些用例是"循环计数不是按位置猜出来的"这一结论的**直接证据**：
 * 自然抵达、用户 seek 越界、用户往回拖、暂停/缓冲期间的越界、旧 generation 晚到，
 * 五种情形必须给出五种不同的结果。
 */
class AbBoundarySessionTest {
    private val loop = EngineAbLoop(generation = 3, pointAMillis = 2_000, pointBMillis = 4_000)

    @Test
    fun `natural playback reaching B reports the boundary for the configured generation`() {
        val session = AbBoundarySession()
        session.configure(loop, POSITION_BELOW_B, alreadyPastBoundary = false)

        assertEquals(AbBoundarySession.SampleAction.NONE, session.onPositionSample(3_900, playbackSpeed = 1f))
        val action = session.onPositionSample(4_000, playbackSpeed = 1f)

        val signal = (action as AbBoundarySession.SampleAction.REPORT_NOW).signal
        assertEquals(3L, signal.generation)
        assertEquals(4_000L, signal.positionMillis)
        assertEquals(2_000L, signal.pointAMillis)
        assertEquals(4_000L, signal.pointBMillis)
    }

    @Test
    fun `the boundary is reported only once until the position rewinds`() {
        val session = AbBoundarySession()
        session.configure(loop, POSITION_BELOW_B, alreadyPastBoundary = false)
        session.onPositionSample(4_100, playbackSpeed = 1f)

        assertEquals(AbBoundarySession.SampleAction.NONE, session.onPositionSample(4_500, playbackSpeed = 1f))
        assertEquals(AbBoundarySession.SampleAction.NONE, session.onPositionSample(5_000, playbackSpeed = 1f))
    }

    /**
     * 用户从 B 之前 seek 到 B 之后：**不产生**自然边界事件（否则用户一拖进度条就白加一次循环）。
     * 落点在 B 之前则重新武装，等播放自然推进到 B。
     */
    @Test
    fun `a user seek past B does not report a natural boundary`() {
        val session = AbBoundarySession()
        session.configure(loop, POSITION_BELOW_B, alreadyPastBoundary = false)

        val forward = session.onDiscontinuity(positionMillis = 6_000, playbackSpeed = 1f)
        assertEquals(AbBoundarySession.SampleAction.NONE, forward)
        assertEquals(AbBoundarySession.SampleAction.NONE, session.onPositionSample(6_100, playbackSpeed = 1f))

        val backward = session.onDiscontinuity(positionMillis = 3_000, playbackSpeed = 1f)
        assertEquals(AbBoundarySession.SampleAction.ARM, backward)
        assertTrue(session.onPositionSample(4_000, playbackSpeed = 1f) is AbBoundarySession.SampleAction.REPORT_NOW)
    }

    /** 落点正好等于 B 的 seek 同样算"已经越过"，不能立刻补报一次假的自然事件。 */
    @Test
    fun `a user seek landing exactly on B is treated as already visited`() {
        val session = AbBoundarySession()
        session.configure(loop, POSITION_BELOW_B, alreadyPastBoundary = false)

        assertEquals(AbBoundarySession.SampleAction.NONE, session.onDiscontinuity(positionMillis = 4_000, playbackSpeed = 1f))
        assertEquals(AbBoundarySession.SampleAction.NONE, session.onPositionSample(4_000, playbackSpeed = 1f))
    }

    /** 位置回退（用户往回拖 / 重缓冲回退）：重新武装，回到 B 时再次计数。 */
    @Test
    fun `a rewind re-arms the boundary detection`() {
        val session = AbBoundarySession()
        session.configure(loop, POSITION_BELOW_B, alreadyPastBoundary = false)
        session.onPositionSample(4_000, playbackSpeed = 1f)

        val rewind = session.onPositionSample(3_500, playbackSpeed = 1f)

        assertEquals(AbBoundarySession.SampleAction.ARM, rewind)
        assertTrue(session.onPositionSample(4_000, playbackSpeed = 1f) is AbBoundarySession.SampleAction.REPORT_NOW)
    }

    /**
     * 不连续回调是"用户 seek 越界"的唯一判定入口：用户 seek 到 B 之后、再自然播到更远处，
     * 都不产生第二个事件（否则一次拖动会变成一次计数）。
     *
     * 顺带钉住语义边界：采样本身无法区分"用户 seek 到 B 之后"与"自然播到 B 之后"，
     * 所以**任何位置跳变都必须先经过 [AbBoundarySession.onDiscontinuity]**，
     * 这正是检测器必须挂 `Player.Listener.onPositionDiscontinuity` 的原因。
     */
    @Test
    fun `a seek past B followed by natural playback never reports`() {
        val session = AbBoundarySession()
        session.configure(loop, POSITION_BELOW_B, alreadyPastBoundary = false)
        session.onDiscontinuity(positionMillis = 6_000, playbackSpeed = 1f)

        assertEquals(AbBoundarySession.SampleAction.NONE, session.onPositionSample(6_100, playbackSpeed = 1f))
        assertEquals(AbBoundarySession.SampleAction.NONE, session.onPositionSample(9_000, playbackSpeed = 1f))
    }

    /** 没有配置（loop == null）：任何采样都不产生事件（引擎在 stop/prepare 时会清空配置）。 */
    @Test
    fun `no configuration produces no events`() {
        val session = AbBoundarySession()

        assertEquals(AbBoundarySession.SampleAction.NONE, session.onPositionSample(9_000, playbackSpeed = 1f))
        assertEquals(AbBoundarySession.SampleAction.NONE, session.onDiscontinuity(9_000, playbackSpeed = 1f))

        session.configure(loop, POSITION_BELOW_B, alreadyPastBoundary = false)
        session.configure(null, POSITION_BELOW_B, alreadyPastBoundary = false)
        assertEquals(AbBoundarySession.SampleAction.NONE, session.onPositionSample(9_000, playbackSpeed = 1f))
    }

    /** 倍速变化：到 B 的剩余等待时间随之改变，必须重新武装（慢速下提前报告会是误报）。 */
    @Test
    fun `changing the playback speed re-arms the boundary timer`() {
        val session = AbBoundarySession()
        session.configure(loop, POSITION_BELOW_B, alreadyPastBoundary = false)
        session.onPositionSample(3_000, playbackSpeed = 1f)

        val action = session.onPlaybackSpeedChanged(0.5f)

        assertEquals(AbBoundarySession.SampleAction.ARM, action)
        assertEquals(1_996L, session.delayToBoundaryMillis(positionMillis = 3_000, playbackSpeed = 0.5f))
    }

    /** 到 B 的延迟按倍速换算，并提前一点点（宁可早到，由定时回调里的采样兜底）。 */
    @Test
    fun `delay to boundary converts remaining playback time by speed`() {
        val session = AbBoundarySession()
        session.configure(loop, POSITION_BELOW_B, alreadyPastBoundary = false)

        assertEquals(996L, session.delayToBoundaryMillis(positionMillis = 3_000, playbackSpeed = 1f))
        assertEquals(1_996L, session.delayToBoundaryMillis(positionMillis = 3_000, playbackSpeed = 0.5f))
        assertEquals(496L, session.delayToBoundaryMillis(positionMillis = 3_000, playbackSpeed = 2f))
        // 已经越过 B：延迟归零，定时回调立刻确认一次。
        assertEquals(0L, session.delayToBoundaryMillis(positionMillis = 4_500, playbackSpeed = 1f))
    }

    /** 定时回调不直接判定越界：它只再采一次位置，确认到了才报告。 */
    @Test
    fun `the timer callback only reports after confirming the position`() {
        val session = AbBoundarySession()
        session.configure(loop, POSITION_BELOW_B, alreadyPastBoundary = false)

        // 定时器到点但位置还没到 B（缓冲/卡顿让真实位置落后于估算）：不得报告，继续等。
        assertEquals(AbBoundarySession.SampleAction.NONE, session.onTimerFired(3_800, playbackSpeed = 1f))
        assertTrue(session.isTimerArmed)
        assertTrue(session.onTimerFired(4_000, playbackSpeed = 1f) is AbBoundarySession.SampleAction.REPORT_NOW)
    }

    /** 换配置：新 generation 覆盖旧配置，旧配置的边界点不再产生事件。 */
    @Test
    fun `reconfiguring replaces the boundary target`() {
        val session = AbBoundarySession()
        session.configure(loop, POSITION_BELOW_B, alreadyPastBoundary = false)
        session.configure(EngineAbLoop(generation = 9, pointAMillis = 5_000, pointBMillis = 7_000), POSITION_BELOW_B, alreadyPastBoundary = false)

        assertEquals(AbBoundarySession.SampleAction.NONE, session.onPositionSample(4_500, playbackSpeed = 1f))
        val signal = (session.onPositionSample(7_000, playbackSpeed = 1f) as AbBoundarySession.SampleAction.REPORT_NOW).signal
        assertEquals(9L, signal.generation)
        assertEquals(5_000L, signal.pointAMillis)
    }

    /**
     * 配置生效时位置**已经在 B 之后**（用户先拖到区间之后，再打开/调整 AB）：
     * 调用方必须显式声明 `alreadyPastBoundary = true`，这一轮没有可行的自然抵达点，
     * 否则第一次采样会凭空补报一次循环。
     */
    @Test
    fun `configuring while already past B never reports`() {
        val session = AbBoundarySession()

        session.configure(loop, positionMillis = 6_000, alreadyPastBoundary = true)

        assertEquals(AbBoundarySession.SampleAction.NONE, session.onPositionSample(6_100, playbackSpeed = 1f))
        assertEquals(AbBoundarySession.SampleAction.NONE, session.onPositionSample(9_000, playbackSpeed = 1f))
        assertFalse(session.isTimerArmed)
    }

    /** 位置正好在 B 上配置：同样算已越过（与"用户 seek 落在 B"一致）。 */
    @Test
    fun `configuring exactly on B never reports`() {
        val session = AbBoundarySession()

        session.configure(loop, positionMillis = B_MILLIS, alreadyPastBoundary = true)

        assertEquals(AbBoundarySession.SampleAction.NONE, session.onPositionSample(B_MILLIS, playbackSpeed = 1f))
        assertFalse(session.isTimerArmed)
    }

    /**
     * 激活路径（`AbBoundaryWatcher.activate`）：位置已经被精确跳到 A，因此 `alreadyPastBoundary`
     * **必须是 false**，与读数无关。
     *
     * 这条用例正是缺陷 2 的回归防线：用户就是把 B 设在当前位置上，若配置那一刻按"读到的位置 >= B"
     * 判定，这一轮就会被标成已越过、定时器永不武装 —— 按正常流程设完 B 之后循环根本不启动。
     */
    @Test
    fun `activation arms the detection even though the pre-seek position was on B`() {
        val session = AbBoundarySession()

        // 激活的定义是"位置已经被跳到 A"，而 A < B，所以调用方按这一事实直接给 false。
        session.configure(loop, positionMillis = 1_200, alreadyPastBoundary = false)

        assertTrue(session.isTimerArmed)
        assertTrue(session.onPositionSample(B_MILLIS, playbackSpeed = 1f) is AbBoundarySession.SampleAction.REPORT_NOW)
    }

    /** 配置时位置在 B 之前：重新武装，等播放自然推进到 B（"设点后立即生效"）。 */
    @Test
    fun `configuring below B re-arms the detection`() {
        val session = AbBoundarySession()

        session.configure(loop, positionMillis = 1_200, alreadyPastBoundary = false)

        assertTrue(session.isTimerArmed)
        assertTrue(session.onPositionSample(B_MILLIS, playbackSpeed = 1f) is AbBoundarySession.SampleAction.REPORT_NOW)
    }

    private companion object {
        /** 一个明确落在 B 之前的位置，供"配置时位置"参数使用。 */
        const val POSITION_BELOW_B = 1_000L
        const val B_MILLIS = 4_000L
    }
}
