package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbLoopLimiterTest {
    private val limiter = AbLoopLimiter()

    @Test
    fun `B requires A and at least one frame`() {
        // 还没设 A：设 B 无意义，直接拒绝。
        assertTrue(limiter.setPoint(AbLoopState(), AbPoint.B, 1_000, 25f) is AbLoopSetPointResult.Rejected)
        val withA = limiter.setPoint(AbLoopState(), AbPoint.A, 1_000, 25f).applied()

        // 距离不足一帧（25fps → 40ms）：拒绝并保留原状态，绝不能产生 A == B。
        assertTrue(limiter.setPoint(withA, AbPoint.B, 1_019, 25f) is AbLoopSetPointResult.Rejected)
        // 1039ms 吸附到第 26 帧 = 1040ms，正好一帧，允许。
        assertEquals(
            AbLoopState(1_000, 1_040),
            limiter.setPoint(withA, AbPoint.B, 1_039, 25f).applied(),
        )
    }

    /**
     * 相等边界（`position == B` 时设 A）必须**拒绝/无操作**：互换会立刻产生 `A == B`，
     * 违反 `AbLoopState` 的 `pointA < pointB` 不变量。这里同时钉住"不互换、不改状态"。
     */
    @Test
    fun `setting A exactly at B is rejected instead of producing an invalid range`() {
        val active = AbLoopState(2_000, 8_000)

        val result = limiter.setPoint(active, AbPoint.A, 8_000, 25f)

        assertTrue(result is AbLoopSetPointResult.Rejected)
        assertEquals(PlaybackCommandRejection.INVALID_AB_RANGE, (result as AbLoopSetPointResult.Rejected).rejection)
    }

    /** A/B 互换：位置落在 B **之后**时，新点成为 B，旧的 B 成为 A（而不是像旧实现那样清空 B）。 */
    @Test
    fun `setting A after B swaps the two points`() {
        val active = AbLoopState(2_000, 8_000)

        assertEquals(
            AbLoopState(8_000, 9_000),
            limiter.setPoint(active, AbPoint.A, 9_000, 25f).applied(),
        )
    }

    /** 互换后如果两点落在同一帧（距离不足一帧），同样拒绝，不能产生 A == B。 */
    @Test
    fun `swapping onto the same frame is rejected`() {
        val active = AbLoopState(2_000, 8_000)

        // 8000ms 设 A：位置 == B → 拒绝（相等边界）。
        assertTrue(limiter.setPoint(active, AbPoint.A, 8_000, 25f) is AbLoopSetPointResult.Rejected)
        // 8001ms 吸附到第 200 帧 = 8000ms → 仍然等于旧的 B → 拒绝。
        assertTrue(limiter.setPoint(active, AbPoint.A, 8_001, 25f) is AbLoopSetPointResult.Rejected)
    }

    /** 吸附把 B 拉回 A：区间塌缩成一帧以下 → 拒绝，而不是留下非法状态。 */
    @Test
    fun `snapping that collapses the range is rejected`() {
        val withA = AbLoopState(pointA = 1_000)

        assertTrue(limiter.setPoint(withA, AbPoint.B, 1_019, 25f) is AbLoopSetPointResult.Rejected)
    }

    /**
     * 帧索引吸附：`frameIndex = round(ms * fps / 1000)` → `snappedMs = round(frameIndex * 1000 / fps)`。
     * 非整数帧率（29.97）下必须按帧索引往返，不能写成 `round(ms / frameDuration) * frameDuration`。
     */
    @Test
    fun `snapping uses the frame index so non integer frame rates round trip`() {
        assertEquals(1_000L, snapAbMillisToFrame(1_007, 25f))
        assertEquals(1_040L, snapAbMillisToFrame(1_020, 25f))
        // 29.97fps：第 30 帧 = round(30 * 1000 / 29.97) = 1001ms
        assertEquals(1_001L, snapAbMillisToFrame(1_000, 29.97f))
        // 29.97fps：第 31 帧 = round(31 * 1000 / 29.97) = 1034ms
        assertEquals(1_034L, snapAbMillisToFrame(1_033, 29.97f))
        // 没有可用帧率：原样返回，不猜一个间隔把用户设的点挪走。
        assertEquals(1_033L, snapAbMillisToFrame(1_033, null))
        assertEquals(1_033L, snapAbMillisToFrame(1_033, 0f))
        assertEquals(1_033L, snapAbMillisToFrame(1_033, -1f))
    }

    @Test
    fun `snapped points are what the state stores`() {
        val withA = limiter.setPoint(AbLoopState(), AbPoint.A, 1_007, 25f).applied()

        assertEquals(1_000L, withA.pointA)
        assertEquals(AbLoopState(1_000, 1_040), limiter.setPoint(withA, AbPoint.B, 1_036, 25f).applied())
    }

    /** 没有帧率时：位置原样保留，但"不足 1ms"这种非法区间仍要拒绝（域不变量）。 */
    @Test
    fun `unknown frame rate keeps the raw millisecond position`() {
        val withA = limiter.setPoint(AbLoopState(), AbPoint.A, 1_007, null).applied()

        assertEquals(1_007L, withA.pointA)
        assertEquals(AbLoopState(1_007, 1_008), limiter.setPoint(withA, AbPoint.B, 1_008, null).applied())
        assertNull(limiter.setPoint(withA, AbPoint.B, 1_007, null).stateOrNull())
        assertEquals(34L, limiter.frameDurationMillis(null))
    }

    @Test
    fun `reducer rejects invalid B and clears on media change`() {
        val reducer = AbLoopReducer(limiter)
        val withA = reducer.reduce(
            AbLoopState(),
            AbLoopEvent.SetPoint(AbPoint.A, 1_000, 25f),
        )
        // 1010ms 吸附回第 25 帧 = 1000ms，与 A 同帧 → 拒绝。
        val rejected = reducer.reduce(
            withA.state,
            AbLoopEvent.SetPoint(AbPoint.B, 1_010, 25f),
        )

        assertEquals(withA.state, rejected.state)
        assertEquals(PlaybackCommandRejection.INVALID_AB_RANGE, rejected.rejection)
        assertEquals(AbLoopState(), reducer.reduce(withA.state, AbLoopEvent.MediaChanged).state)
        assertEquals(AbLoopState(), reducer.reduce(withA.state, AbLoopEvent.Clear).state)
    }

    /**
     * 启用策略：**未知时长或不可 seek → 禁用**；否则可用。
     *
     * 它不看来源类型（本地/网络/受保护都一样）：判定输入是播放器准备完成后的**真实 timeline**
     *（`durationMillis` = `player.duration`、`isSeekable` = `isCurrentMediaItemSeekable`），
     * 因为保险库媒体打开时 handle 的时长可能是未知值，准备完成后才有真值。
     */
    @Test
    fun `ab availability depends on the prepared timeline only`() {
        assertEquals(AbLoopAvailability.ENABLED, abLoopAvailability(10_000, isSeekable = true))
        assertEquals(AbLoopAvailability.UNKNOWN_DURATION, abLoopAvailability(null, isSeekable = true))
        assertEquals(AbLoopAvailability.UNKNOWN_DURATION, abLoopAvailability(0, isSeekable = true))
        assertEquals(AbLoopAvailability.UNKNOWN_DURATION, abLoopAvailability(-1, isSeekable = true))
        assertEquals(AbLoopAvailability.NOT_SEEKABLE, abLoopAvailability(10_000, isSeekable = false))
        // 既不可 seek 又时长未知：报"时长未知"（区间本身都定义不了，先修这个前提）。
        assertEquals(AbLoopAvailability.UNKNOWN_DURATION, abLoopAvailability(null, isSeekable = false))
    }

    private fun AbLoopSetPointResult.applied(): AbLoopState {
        assertTrue("expected applied but was $this", this is AbLoopSetPointResult.Applied)
        return (this as AbLoopSetPointResult.Applied).state
    }

    private fun AbLoopSetPointResult.stateOrNull(): AbLoopState? =
        (this as? AbLoopSetPointResult.Applied)?.state
}
