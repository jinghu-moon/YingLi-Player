package seeyuer.yingli.player.feature.shell

import org.junit.Assert.assertEquals
import org.junit.Test
import seeyuer.yingli.player.domain.navigation.AppRoute
import seeyuer.yingli.player.domain.navigation.RootDestination

class PageTransitionTest {
    @Test
    fun `rightward root navigation uses forward direction`() {
        assertEquals(
            PageTransitionDirection.FORWARD,
            pageTransitionDirection(AppRoute.Root(RootDestination.HOME), AppRoute.Root(RootDestination.LIBRARY)),
        )
        assertEquals(1080, pageEnterOffset(width = 1080, forward = true))
        assertEquals(-1080, pageExitOffset(width = 1080, forward = true))
    }

    @Test
    fun `leftward root navigation uses backward direction`() {
        assertEquals(
            PageTransitionDirection.BACKWARD,
            pageTransitionDirection(AppRoute.Root(RootDestination.ORGANIZE), AppRoute.Root(RootDestination.HOME)),
        )
        assertEquals(-1080, pageEnterOffset(width = 1080, forward = false))
        assertEquals(1080, pageExitOffset(width = 1080, forward = false))
    }

    @Test
    fun `nested navigation moves forward and back moves backward`() {
        val root = AppRoute.Root(RootDestination.LIBRARY)
        val detail = AppRoute.Detail("media-1", RootDestination.LIBRARY)

        assertEquals(PageTransitionDirection.FORWARD, pageTransitionDirection(root, detail))
        assertEquals(PageTransitionDirection.BACKWARD, pageTransitionDirection(detail, root))
    }
}
