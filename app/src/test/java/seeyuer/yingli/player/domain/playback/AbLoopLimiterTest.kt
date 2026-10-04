package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbLoopLimiterTest {
    private val limiter = AbLoopLimiter()

    /**
     * **B 侧本批口径**：`B = max(播放头, A + 1s)`（demo 的事实）。
     *
     * 旧口径是"不足一帧 → 拒绝"，它有一个真实缺陷：用户把播放头停在 A 附近按 B 时，
     * 区间**永远设不上**，而用户完全不知道阈值在哪。现在兜底到 `A + 1s`，
     * "按了 B 却什么也没发生"变成"B 落在 A 之后 1 秒处"。
     */
    @Test
    fun `B before A falls back to one second after A instead of being rejected`() {
        val withA = limiter.setPoint(AbLoopState(), AbPoint.A, 1_000, 25f).applied()

        // 播放头 == A：兜底到 A + 1s（1000 + 1000 = 2000ms，25fps 下正好落在帧上）。
        assertEquals(AbLoopState(1_000, 2_000), limiter.setPoint(withA, AbPoint.B, 1_000, 25f).applied())
        // 播放头在 A 之前：同样是 A + 1s。
        assertEquals(AbLoopState(1_000, 2_000), limiter.setPoint(withA, AbPoint.B, 0, 25f).applied())
        // 播放头离 A 不足一帧（旧口径下会被拒绝）：仍然兜底到 A + 1s。
        assertEquals(AbLoopState(1_000, 2_000), limiter.setPoint(withA, AbPoint.B, 1_019, 25f).applied())
        // 兜底间隔本身**不等于**"一帧"：1s 在进度条上看得见、在读数条上读得出（Δ 00:01）。
        assertEquals(1_000L, AB_POINT_B_MIN_GAP_MILLIS)

        // 播放头已经在 A + 1s 之后：B 就落在播放头上（兜底只是**下限**，不是目标值）。
        assertEquals(AbLoopState(1_000, 5_000), limiter.setPoint(withA, AbPoint.B, 5_000, 25f).applied())
    }

    @Test
    fun `B requires A and at least one frame`() {
        // 还没设 A：设 B 无意义，直接拒绝（既有规则不变）。
        assertTrue(limiter.setPoint(AbLoopState(), AbPoint.B, 1_000, 25f) is AbLoopSetPointResult.Rejected)
        val withA = limiter.setPoint(AbLoopState(), AbPoint.A, 1_000, 25f).applied()

        // 播放头在 A + 1s 之前（1039、1019 都一样）：兜底到 2000ms（第 50 帧，正好落在帧上）。
        assertEquals(
            AbLoopState(1_000, 2_000),
            limiter.setPoint(withA, AbPoint.B, 1_039, 25f).applied(),
        )
        // 播放头在 A + 1s 之后：落在播放头吸附后的那一帧上。
        assertEquals(
            AbLoopState(1_000, 2_040),
            limiter.setPoint(withA, AbPoint.B, 2_030, 25f).applied(),
        )
    }

    /**
     * 兜底值必须**再吸附到帧**：`A + 1s` 落在半帧位置时，区间长度必须是整帧数 ——
     * 否则"至少一帧"的校验会作用在一个吸附后会塌掉的值上（D5/D13 的帧口径）。
     */
    @Test
    fun `the one second fallback is snapped to the frame grid`() {
        // 29.97fps：A = 1001ms（第 30 帧），A + 1s = 2001ms 落在两帧之间
        //（第 60 帧 = round(60 × 1000 / 29.97) = 2002ms）→ 吸附到 2002ms。
        val withA = limiter.setPoint(AbLoopState(), AbPoint.A, 1_000, 29.97f).applied()
        assertEquals(1_001L, withA.pointA)
        assertEquals(AbLoopState(1_001, 2_002), limiter.setPoint(withA, AbPoint.B, 1_001, 29.97f).applied())
    }

    /**
     * **夹到片长**：兜底值在片尾附近会越界（A 已在片尾附近时 `A + 1s` 超过时长）。
     * 越界的 B 会让引擎在片尾反复回跳，所以必须夹回时长之内。
     */
    @Test
    fun `a fallback past the end of the media is clamped to the duration`() {
        val withA = limiter.setPoint(AbLoopState(), AbPoint.A, 9_800, 25f).applied()

        // 时长 10_000ms：A + 1s = 10_800ms 越界 → 夹到 10_000ms（正好在第 250 帧上）。
        assertEquals(
            AbLoopState(9_800, 10_000),
            limiter.setPoint(withA, AbPoint.B, 9_800, 25f, durationMillis = 10_000).applied(),
        )
        // 时长未知（null）：不夹（调用方拿不到时长时不做无依据的截断）。
        assertEquals(
            AbLoopState(9_800, 10_800),
            limiter.setPoint(withA, AbPoint.B, 9_800, 25f, durationMillis = null).applied(),
        )
    }

    /**
     * **夹完之后仍不足一帧 → 拒绝**（由会话回流成用户可见提示）。
     *
     * 场景：A 已经贴在片尾（9950ms，第 249 帧），时长 9960ms —— 夹到片长之后与 A 只差 10ms，
     * 不足一帧，无法构成合法区间。这一条是"B 侧不再拒绝"的**例外**，必须保留：
     * 否则会构造出 `pointA < pointB` 不成立的非法状态。
     */
    @Test
    fun `a clamped fallback that still lacks one frame is rejected`() {
        val withA = limiter.setPoint(AbLoopState(), AbPoint.A, 9_950, 25f).applied()

        val result = limiter.setPoint(withA, AbPoint.B, 9_950, 25f, durationMillis = 9_960)

        assertTrue(result is AbLoopSetPointResult.Rejected)
        assertEquals(PlaybackCommandRejection.INVALID_AB_RANGE, (result as AbLoopSetPointResult.Rejected).rejection)
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

    /**
     * **B 侧本批口径**：`B = max(播放头, A + 1s)`（demo 的事实）。
     *
     * 旧口径是"不足一帧 → 拒绝"（本用例原来钉的就是"1019ms 被拒"）。它有一个真实缺陷：
     * 用户把播放头停在 A 附近按 B 时，区间**永远设不上**，而用户完全不知道阈值在哪。
     * 现在兜底到 `A + 1s`，"按了 B 却什么也没发生"变成"B 落在 A 之后 1 秒处"。
     */
    @Test
    fun `snapping that would collapse the range falls back to one second after A`() {
        val withA = AbLoopState(pointA = 1_000)

        // 旧口径下这一条是**拒绝**（1019ms 吸附回 1000ms，与 A 同帧）；
        // 本批口径下它是"兜底到 A + 1s"，区间成立。
        assertEquals(AbLoopState(1_000, 2_000), limiter.setPoint(withA, AbPoint.B, 1_019, 25f).applied())
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
        // 播放头 1_036ms 在 A + 1s 之前 → 兜底到 2_000ms（25fps 的第 50 帧，正好落在帧上）。
        assertEquals(AbLoopState(1_000, 2_000), limiter.setPoint(withA, AbPoint.B, 1_036, 25f).applied())
        // 播放头已经在 A + 1s 之后：B 落在播放头吸附后的帧上（2_030 → 第 51 帧 = 2_040ms）。
        assertEquals(AbLoopState(1_000, 2_040), limiter.setPoint(withA, AbPoint.B, 2_036, 25f).applied())
    }

    /**
     * 没有帧率时：位置原样保留，而 B 侧的兜底（`A + 1s`）仍然生效（没有帧可吸附，所以是精确的 +1s）。
     *
     * 注意**"至少 1ms"这条退化判据在没有帧率时已经几乎不可达**：B 侧现在总是取
     * `max(播放头, A + 1s)`，而 1s ≫ 1ms。唯一还能触发拒绝的路径是"夹到片长之后不足一帧"
     *（见 `a clamped fallback that still lacks one frame is rejected`），
     * 所以这里只钉住兜底行为与帧时长兜底值。
     */
    @Test
    fun `unknown frame rate keeps the raw millisecond position`() {
        val withA = limiter.setPoint(AbLoopState(), AbPoint.A, 1_007, null).applied()

        assertEquals(1_007L, withA.pointA)
        // A 之后不足 1s → 兜底到 2_007ms（没有帧率，不吸附）。
        assertEquals(AbLoopState(1_007, 2_007), limiter.setPoint(withA, AbPoint.B, 1_008, null).applied())
        assertEquals(AbLoopState(1_007, 2_007), limiter.setPoint(withA, AbPoint.B, 1_007, null).applied())
        // 播放头在 A + 1s 之后：原样落点（这里就是那条"位置原样保留"）。
        assertEquals(AbLoopState(1_007, 3_333), limiter.setPoint(withA, AbPoint.B, 3_333, null).applied())
        assertEquals(34L, limiter.frameDurationMillis(null))
    }

    /**
     * 拒绝分支（B 侧的唯一一条）：**夹到片长之后仍不足一帧**。
     *
     * 场景：A 已经贴在片尾（9950ms = 第 249 帧），片长 9960ms（正好第 249 帧）
     * —— 兜底值 10_950ms 越界，夹到 9960ms 之后与 A 只差 10ms，不足一帧。
     * 这一条必须保留拒绝：否则会构造出 `pointA < pointB` 不成立的非法状态。
     */
    @Test
    fun `reducer reports invalid B only when the clamped fallback still lacks one frame`() {
        val reducer = AbLoopReducer(limiter)
        val withA = reducer.reduce(
            AbLoopState(),
            AbLoopEvent.SetPoint(AbPoint.A, 1_000, 25f),
        )
        // 播放头 1_010ms 在 A + 1s 之前 → 兜底到 2_000ms（本批口径：不再拒绝）。
        val applied = reducer.reduce(
            withA.state,
            AbLoopEvent.SetPoint(AbPoint.B, 1_010, 25f),
        )
        assertNull(applied.rejection)
        assertEquals(AbLoopState(1_000, 2_000), applied.state)

        // 拒绝分支：A 贴片尾 + 片长只够再放 10ms。
        val tight = AbLoopState(9_950, 9_960)
        val rejected = reducer.reduce(
            tight,
            AbLoopEvent.SetPoint(AbPoint.B, 9_950, 25f, durationMillis = 9_960),
        )
        assertEquals("拒绝时状态必须原样保留（宁可不改，也不制造非法区间）", tight, rejected.state)
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
