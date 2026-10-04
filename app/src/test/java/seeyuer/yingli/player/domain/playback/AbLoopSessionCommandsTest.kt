package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AB 状态与命令回执的线格式：编解码各只有一对实现，两端共用。
 *
 * 为什么要单独测它：这两条线格式是"用户能不能看到状态变化 / 能不能看到提示"的唯一依据。
 * 编解码错一位就会退化成静默失败或错误提示 —— 已经踩过两次：
 *  - 拒绝码所在 extras 被结果码重载吞掉 → 客户端只剩 `INVALID_STATE` 可读；
 *  - 状态通道要求 A、B 同时存在才能重建区间 → "只设了 A"被丢成空区间，用户按了「A 设置」
 *    却看不到任何变化，而命令**确实成功**，所以也没有任何提示。
 */
class AbLoopSessionCommandsTest {
    @Test
    fun `every rejection round trips through the wire format`() {
        for (rejection in PlaybackCommandRejection.entries) {
            val encoded = AbLoopSessionCommands.encodeRejection(rejection)
            assertEquals(rejection.name, encoded)
            assertEquals(rejection, AbLoopSessionCommands.decodeRejection(encoded))
        }
    }

    @Test
    fun `unknown and missing rejection codes decode to null so the caller can fall back`() {
        assertNull(AbLoopSessionCommands.decodeRejection(null))
        assertNull(AbLoopSessionCommands.decodeRejection(""))
        assertNull(AbLoopSessionCommands.decodeRejection("SOMETHING_ELSE"))
    }

    /**
     * 每一种**会话可能持有**的状态都必须原样往返。
     *
     * 真机用例只能证明"走这条路径时状态回得来"，证明不了"每一种状态都回得来"；
     * 而这次的缺口正好只在"只设了 A"这一种状态上。
     */
    @Test
    fun `every session state round trips through the wire format`() {
        val sessions = listOf(
            AbLoopSession.EMPTY,
            AbLoopSession(state = AbLoopState(pointA = 9_680)),
            AbLoopSession(state = AbLoopState(pointA = 12_000, pointB = 30_000), loopCount = 7),
        )

        for (session in sessions) {
            val wire = AbLoopSessionCommands.encodeState(session)
            assertTrue(
                "线格式不许出现状态通道之外的键（多一个键就是两端漂移）：$wire",
                AbLoopSessionCommands.STATE_KEYS.containsAll(wire.keys),
            )
            assertEquals(session, AbLoopSessionCommands.decodeState(wire))
        }
    }

    /** **缺陷的原始现场**：只设了 A 时必须原样回来（B 还没设不是"没有区间"）。 */
    @Test
    fun `a single A point survives the round trip because B may not be set yet`() {
        val wire = AbLoopSessionCommands.encodeState(AbLoopSession(state = AbLoopState(pointA = 9_680)))

        assertEquals("9680", wire[AbLoopSessionCommands.EXTRA_POINT_A])
        assertNull("B 还没设时不许伪造一个 B", wire[AbLoopSessionCommands.EXTRA_POINT_B])
        val decoded = AbLoopSessionCommands.decodeState(wire)
        assertEquals(AbLoopState(pointA = 9_680), decoded.state)
        assertNull(decoded.state.pointB)
        assertFalse("区间不完整时 active 必须为假", decoded.active)
    }

    /**
     * 只丢弃**会话根本不可能持有**的载荷：只有 B、A ≥ B、负数位置。
     *
     * 这些一律退回"没有区间"并让计数归零：宁可少显示一个区间，也不要为了显示它构造
     * 一个非法状态（[AbLoopState] 的不变量会直接抛，或者让胶囊显示一个永远循环不了的区间）。
     */
    @Test
    fun `payloads the session can never hold decode to an empty range with zero count`() {
        val lonelyB = mapOf(
            AbLoopSessionCommands.EXTRA_POINT_B to "5000",
            AbLoopSessionCommands.EXTRA_LOOP_COUNT to "3",
        )
        assertEquals(AbLoopSession.EMPTY, AbLoopSessionCommands.decodeState(lonelyB))

        val inverted = mapOf(
            AbLoopSessionCommands.EXTRA_POINT_A to "5000",
            AbLoopSessionCommands.EXTRA_POINT_B to "4000",
            AbLoopSessionCommands.EXTRA_LOOP_COUNT to "3",
        )
        assertEquals(AbLoopSession.EMPTY, AbLoopSessionCommands.decodeState(inverted))

        val negative = mapOf(
            AbLoopSessionCommands.EXTRA_POINT_A to "-1",
            AbLoopSessionCommands.EXTRA_LOOP_COUNT to "-4",
        )
        assertEquals(AbLoopSession.EMPTY, AbLoopSessionCommands.decodeState(negative))

        // 完全空的 extras（从未设过点）也必须解码成"没有区间、计数为 0"，而不是抛。
        assertEquals(AbLoopSession.EMPTY, AbLoopSessionCommands.decodeState(emptyMap()))
    }
}
