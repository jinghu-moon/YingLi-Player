package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerControlLayoutTest {
    @Test fun `fixed controls cannot be removed and optional controls can`() {
        val defaults = PlayerControlLayout()
        assertTrue(defaults.remove(PlayerControlId.FULLSCREEN).contains(PlayerControlId.FULLSCREEN))
        assertFalse(defaults.remove(PlayerControlId.SCREENSHOT).contains(PlayerControlId.SCREENSHOT))
    }

    @Test fun `duplicate add and slot limit are ignored`() {
        val defaults = PlayerControlLayout()
        val surface = PlayerControlSurface.LANDSCAPE_BOTTOM_RIGHT
        assertEquals(defaults, defaults.add(surface, PlayerControlId.PIP))
        val full = defaults.add(surface, PlayerControlId.ORIENTATION).add(surface, PlayerControlId.LOCK)
        assertEquals(full, full.add(surface, PlayerControlId.AB_LOOP))
    }

    @Test fun `default portrait bottom carries the lock shortcut`() {
        val defaults = PlayerControlLayout()
        val controls = defaults.controls(PlayerControlSurface.PORTRAIT_BOTTOM)
        assertEquals(7, controls.size)
        assertTrue(PlayerControlId.SCALE in controls)
        // “更多”已从控件模型删除：设置等入口只保留顶栏常驻溢出菜单。
        assertTrue(PlayerControlId.LOCK in controls)
        assertTrue(PlayerControlId.LOCK in defaults.controls(PlayerControlSurface.LANDSCAPE_BOTTOM_RIGHT))
        assertTrue(controls.size <= PlayerControlSurface.PORTRAIT_BOTTOM.capacity)
    }

    @Test fun `move reorders within a slot and can move between slots`() {
        val defaults = PlayerControlLayout()
        val surface = PlayerControlSurface.LANDSCAPE_BOTTOM_LEFT
        // 默认槽位现在是 [ORDER, SPEED]：截图与 A-B 循环已移入"工具"弹窗，不再占用底栏。
        val moved = defaults.move(surface, 0, 1)
        assertEquals(PlayerControlId.SPEED, moved.controls(surface)[0])
        val relocated = moved.move(
            PlayerControlSurface.LANDSCAPE_BOTTOM_LEFT,
            0,
            PlayerControlSurface.LANDSCAPE_BOTTOM_RIGHT,
        )
        assertFalse(PlayerControlId.SPEED in relocated.controls(surface))
        assertTrue(PlayerControlId.SPEED in relocated.controls(PlayerControlSurface.LANDSCAPE_BOTTOM_RIGHT))
    }
}
