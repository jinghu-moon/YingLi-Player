package seeyuer.yingli.player.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerBackActionResolverTest {
    @Test
    fun `transient tools win over lock and fullscreen`() {
        assertEquals(
            PlayerBackAction.CLOSE_TOOL,
            resolvePlayerBack(hasTransientTool = true, locked = true, isFullscreen = true),
        )
    }

    @Test
    fun `lock is resolved before fullscreen`() {
        assertEquals(
            PlayerBackAction.UNLOCK,
            resolvePlayerBack(hasTransientTool = false, locked = true, isFullscreen = true),
        )
    }

    @Test
    fun `fullscreen exits before leaving the route`() {
        assertEquals(
            PlayerBackAction.EXIT_FULLSCREEN,
            resolvePlayerBack(hasTransientTool = false, locked = false, isFullscreen = true),
        )
    }

    @Test
    fun `plain player lets the route stack handle back`() {
        assertEquals(
            PlayerBackAction.LEAVE_PLAYER,
            resolvePlayerBack(hasTransientTool = false, locked = false, isFullscreen = false),
        )
    }

    @Test
    fun `lock without fullscreen still unlocks first`() {
        assertEquals(
            PlayerBackAction.UNLOCK,
            resolvePlayerBack(hasTransientTool = false, locked = true, isFullscreen = false),
        )
    }
}
