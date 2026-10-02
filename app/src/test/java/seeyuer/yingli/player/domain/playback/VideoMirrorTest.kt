package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画面镜像的语义测试。
 *
 * 锁定的行为：水平与垂直**相互独立**可切换；两者都关时视为未生效（用于决定是否需要在
 * 视图层叠加镜像，以及托盘按钮的选中态）。
 */
class VideoMirrorTest {

    @Test
    fun `default is not active`() {
        assertFalse(VideoMirror.Default.isActive)
        assertEquals(VideoMirror.Default, VideoMirror())
    }

    @Test
    fun `toggling horizontal flips only horizontal`() {
        val mirrored = VideoMirror.Default.toggleHorizontal()
        assertTrue(mirrored.horizontal)
        assertFalse(mirrored.vertical)
        assertTrue(mirrored.isActive)
        assertFalse(mirrored.toggleHorizontal().isActive)
    }

    @Test
    fun `toggling vertical flips only vertical`() {
        val mirrored = VideoMirror.Default.toggleVertical()
        assertTrue(mirrored.vertical)
        assertFalse(mirrored.horizontal)
        assertTrue(mirrored.isActive)
        assertFalse(mirrored.toggleVertical().isActive)
    }

    @Test
    fun `both directions can be active at once`() {
        val both = VideoMirror.Default.toggleHorizontal().toggleVertical()
        assertTrue(both.horizontal)
        assertTrue(both.vertical)
        assertTrue(both.isActive)
        assertEquals(both, VideoMirror(horizontal = true, vertical = true))
    }
}
