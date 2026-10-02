package seeyuer.yingli.player.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.domain.playback.PlaybackSpeed

class PlayerSpeedRailMathTest {
    @Test
    fun `supported speeds keep the domain order`() {
        assertEquals(
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f),
            supportedSpeeds().map(PlaybackSpeed::value),
        )
    }

    @Test
    fun `index and speed round trip for every step`() {
        supportedSpeeds().forEachIndexed { index, speed ->
            assertEquals(index, speedIndex(speed))
            assertEquals(speed, speedAt(index.toFloat()))
        }
    }

    @Test
    fun `fractional rail positions snap to the nearest step`() {
        assertEquals(PlaybackSpeed.of(0.5f), speedAt(0f))
        assertEquals(PlaybackSpeed.of(0.5f), speedAt(0.4f))
        assertEquals(PlaybackSpeed.of(0.75f), speedAt(0.6f))
        assertEquals(PlaybackSpeed.of(1f), speedAt(2.49f))
        // 正好落在两档中间时按 roundToInt 的规则取靠后一档。
        assertEquals(PlaybackSpeed.of(1.25f), speedAt(2.5f))
        assertEquals(PlaybackSpeed.of(1.5f), speedAt(3.5f))
        assertEquals(PlaybackSpeed.of(4f), speedAt(7f))
        // 越界（手指滑出胶囊）夹到两端，不会崩。
        assertEquals(PlaybackSpeed.of(0.5f), speedAt(-3f))
        assertEquals(PlaybackSpeed.of(4f), speedAt(99f))
    }

    @Test
    fun `rail height matches the bottom bar button size`() {
        assertEquals(48f, SpeedRailHeight.value, 0f)
    }

    @Test
    fun `snapping a released position always lands on a tick index`() {
        // 松手后旋钮必须落在刻度上：任何小数位置吸附回来都是整数下标，且始终在 0..7 内。
        var fractional = 0f
        while (fractional <= 7f) {
            val snapped = speedIndex(speedAt(fractional)).toFloat()
            assertEquals("position=$fractional", snapped, snapped.toInt().toFloat(), 0f)
            assertTrue("position=$fractional snapped=$snapped", snapped in 0f..7f)
            fractional += 0.05f
        }
    }
}
