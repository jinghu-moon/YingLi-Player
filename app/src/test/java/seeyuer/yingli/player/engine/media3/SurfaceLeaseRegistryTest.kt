package seeyuer.yingli.player.engine.media3

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.domain.playback.SurfaceOwner

class SurfaceLeaseRegistryTest {
    @Test
    fun newerLeaseCannotBeClearedByOlderLease() {
        val guard = SurfaceLeaseGuard()
        val first = guard.acquire(SurfaceOwner.REGULAR_PLAYER, "first")
        val second = guard.acquire(SurfaceOwner.REGULAR_PLAYER, "second")

        assertFalse(guard.release(first))
        assertTrue(guard.release(second))
        assertFalse(guard.release(second))

        val third = guard.acquire(SurfaceOwner.REGULAR_PLAYER, "first")
        assertTrue(third.generation > second.generation)
        assertFalse(guard.release(first))
    }
}
