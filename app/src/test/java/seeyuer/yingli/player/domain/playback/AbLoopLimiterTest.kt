package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbLoopLimiterTest {
    private val limiter = AbLoopLimiter()

    @Test
    fun `B requires A and at least one frame`() {
        assertTrue(limiter.setPoint(AbLoopState(), AbPoint.B, 1_000, 10_000, 25f).isFailure)
        val withA = limiter.setPoint(AbLoopState(), AbPoint.A, 1_000, 10_000, 25f).getOrThrow()

        assertTrue(limiter.setPoint(withA, AbPoint.B, 1_039, 10_000, 25f).isFailure)
        assertEquals(
            AbLoopState(1_000, 1_040),
            limiter.setPoint(withA, AbPoint.B, 1_040, 10_000, 25f).getOrThrow(),
        )
    }

    @Test
    fun `unknown frame rate uses conservative thirty fps interval`() {
        assertEquals(34L, limiter.frameStepMillis(null))
    }

    @Test
    fun `all seek paths clamp only when both points are active`() {
        val onlyA = AbLoopState(pointA = 2_000)
        val active = AbLoopState(2_000, 8_000)

        assertEquals(500L, limiter.clamp(onlyA, 500, 10_000))
        assertEquals(2_000L, limiter.clamp(active, 500, 10_000))
        assertEquals(8_000L, limiter.seekBy(active, 7_500, 2_000, 10_000))
        assertEquals(2_000L, limiter.seekBy(active, 2_500, -2_000, 10_000))
    }

    @Test
    fun `loop position returns A at or after B`() {
        val active = AbLoopState(2_000, 8_000)

        assertNull(limiter.loopPosition(active, 7_999))
        assertEquals(2_000L, limiter.loopPosition(active, 8_000))
        assertFalse(limiter.clear().active)
    }

    @Test
    fun `reducer rejects invalid B and clears on media change`() {
        val reducer = AbLoopReducer(limiter)
        val withA = reducer.reduce(
            AbLoopState(),
            AbLoopEvent.SetPoint(AbPoint.A, 1_000, 10_000, 25f),
        )
        val rejected = reducer.reduce(
            withA.state,
            AbLoopEvent.SetPoint(AbPoint.B, 1_020, 10_000, 25f),
        )

        assertEquals(withA.state, rejected.state)
        assertEquals(PlaybackCommandRejection.INVALID_AB_RANGE, rejected.rejection)
        assertEquals(AbLoopState(), reducer.reduce(withA.state, AbLoopEvent.MediaChanged).state)
    }
}
