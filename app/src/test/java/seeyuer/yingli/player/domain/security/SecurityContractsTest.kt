package seeyuer.yingli.player.domain.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityContractsTest {
    @Test
    fun `failed unlocks are rate limited and time rollback cannot unlock`() {
        var machine = AppLockReducer.reduce(
            AppLockMachine(),
            AppLockEvent.Configure(AppLockPolicy(AppLockMode.PIN), 0),
        )
        repeat(5) { machine = AppLockReducer.reduce(machine, AppLockEvent.UnlockFailed(1_000)) }

        val limited = machine.state as AppLockSessionState.RateLimited
        assertFalse(AppLockReducer.canAttempt(limited, 999))
        assertFalse(AppLockReducer.canAttempt(limited, limited.retryAtEpochMillis - 1))
        assertTrue(AppLockReducer.canAttempt(limited, limited.retryAtEpochMillis))
    }

    @Test
    fun `background timeout and immediate policy lock unlocked session`() {
        val delayed = AppLockMachine(
            AppLockPolicy(AppLockMode.PIN, timeoutMillis = 10_000, lockImmediatelyOnBackground = false),
            AppLockSessionState.Unlocked(0),
        )
        val shortVisit = AppLockReducer.reduce(
            AppLockReducer.reduce(delayed, AppLockEvent.EnterBackground(1_000)),
            AppLockEvent.EnterForeground(5_000),
        )
        assertTrue(shortVisit.state is AppLockSessionState.Unlocked)
        val expired = AppLockReducer.reduce(
            AppLockReducer.reduce(delayed, AppLockEvent.EnterBackground(1_000)),
            AppLockEvent.EnterForeground(20_000),
        )
        assertEquals(AppLockSessionState.Locked(), expired.state)
    }

    @Test
    fun `clock rollback while backgrounded locks fail closed`() {
        val machine = AppLockMachine(
            AppLockPolicy(AppLockMode.PIN, timeoutMillis = 10_000, lockImmediatelyOnBackground = false),
            AppLockSessionState.Unlocked(0),
        )

        val foreground = AppLockReducer.reduce(
            AppLockReducer.reduce(machine, AppLockEvent.EnterBackground(5_000)),
            AppLockEvent.EnterForeground(4_000),
        )

        assertEquals(AppLockSessionState.Locked(), foreground.state)
    }
}
