package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 布局编解码与"按版本回填"的契约：
 * 老数据（无版本前缀，或版本号小于当前值）补齐缺失的回填按钮；当前版本原样返回。
 */
class PlayerControlLayoutCodecTest {
    @Test
    fun `encoded value carries the current version prefix`() {
        val encoded = PlayerControlLayoutCodec.encode(PlayerControlLayout())

        assertTrue(encoded.startsWith("${PlayerControlLayoutCodec.CURRENT_LAYOUT_VERSION};"))
    }

    @Test
    fun `encode then decode round trips the default layout`() {
        val layout = PlayerControlLayout()

        assertEquals(layout, PlayerControlLayoutCodec.decode(PlayerControlLayoutCodec.encode(layout)))
    }

    @Test
    fun `encode then decode round trips a customized layout`() {
        // 用户移除过回填按钮、也调换过槽位内顺序：往返必须逐字节还原用户的选择。
        val customized = PlayerControlLayout()
            .remove(PlayerControlId.MIRROR_HORIZONTAL)
            .remove(PlayerControlId.SCREENSHOT)
            .move(PlayerControlSurface.LANDSCAPE_BOTTOM_LEFT, 0, 1)

        assertEquals(customized, PlayerControlLayoutCodec.decode(PlayerControlLayoutCodec.encode(customized)))
    }

    /** legacy：v2 / v3 键写下的值没有版本前缀，第一段是 "SURFACE=ID,ID"。 */
    @Test
    fun `legacy value without a version prefix backfills the tools tray`() {
        val legacy = "LANDSCAPE_TOP_RIGHT=PLAYLIST,AUDIO,SUBTITLE,SETTINGS" +
            ";LANDSCAPE_BOTTOM_LEFT=SPEED,ORDER" +
            ";LANDSCAPE_BOTTOM_RIGHT=PIP,FULLSCREEN,LOCK" +
            ";PORTRAIT_BOTTOM=SPEED,SCALE,ORIENTATION,PIP,FULLSCREEN,LOCK,MORE" +
            ";TOOLS=SCREENSHOT,AB_LOOP,INFO"

        val decoded = PlayerControlLayoutCodec.decode(legacy)

        assertEquals(
            listOf(
                PlayerControlId.SCREENSHOT,
                PlayerControlId.AB_LOOP,
                PlayerControlId.INFO,
                PlayerControlId.MIRROR_HORIZONTAL,
                PlayerControlId.MIRROR_VERTICAL,
                PlayerControlId.BACKGROUND_PLAYBACK,
            ),
            decoded.controls(PlayerControlSurface.TOOLS),
        )
        // 其它槽位（含用户调换过的顺序）保持原样。
        assertEquals(
            listOf(PlayerControlId.SPEED, PlayerControlId.ORDER),
            decoded.controls(PlayerControlSurface.LANDSCAPE_BOTTOM_LEFT),
        )
        assertEquals(
            listOf(PlayerControlId.PLAYLIST, PlayerControlId.AUDIO, PlayerControlId.SUBTITLE, PlayerControlId.SETTINGS),
            decoded.controls(PlayerControlSurface.LANDSCAPE_TOP_RIGHT),
        )
        assertEquals(
            listOf(
                PlayerControlId.SPEED,
                PlayerControlId.SCALE,
                PlayerControlId.ORIENTATION,
                PlayerControlId.PIP,
                PlayerControlId.FULLSCREEN,
                PlayerControlId.LOCK,
                PlayerControlId.MORE,
            ),
            decoded.controls(PlayerControlSurface.PORTRAIT_BOTTOM),
        )
    }

    @Test
    fun `legacy value carrying only the tools slot backfills it and leaves other slots empty`() {
        val decoded = PlayerControlLayoutCodec.decode("TOOLS=SCREENSHOT")

        assertEquals(
            listOf(
                PlayerControlId.SCREENSHOT,
                PlayerControlId.MIRROR_HORIZONTAL,
                PlayerControlId.MIRROR_VERTICAL,
                PlayerControlId.BACKGROUND_PLAYBACK,
            ),
            decoded.controls(PlayerControlSurface.TOOLS),
        )
        assertTrue(decoded.controls(PlayerControlSurface.LANDSCAPE_TOP_RIGHT).isEmpty())
    }

    @Test
    fun `older explicit version is migrated like legacy data`() {
        val decoded = PlayerControlLayoutCodec.decode("3;TOOLS=SCREENSHOT")

        assertEquals(
            listOf(
                PlayerControlId.SCREENSHOT,
                PlayerControlId.MIRROR_HORIZONTAL,
                PlayerControlId.MIRROR_VERTICAL,
                PlayerControlId.BACKGROUND_PLAYBACK,
            ),
            decoded.controls(PlayerControlSurface.TOOLS),
        )
    }

    @Test
    fun `backfill does not duplicate a control the user already placed elsewhere`() {
        val legacy = "PORTRAIT_BOTTOM=SPEED,SCALE,ORIENTATION,PIP,FULLSCREEN,LOCK,MIRROR_HORIZONTAL;TOOLS=SCREENSHOT"

        val decoded = PlayerControlLayoutCodec.decode(legacy)

        assertFalse(PlayerControlId.MIRROR_HORIZONTAL in decoded.controls(PlayerControlSurface.TOOLS))
        assertEquals(
            listOf(PlayerControlId.SCREENSHOT, PlayerControlId.MIRROR_VERTICAL, PlayerControlId.BACKGROUND_PLAYBACK),
            decoded.controls(PlayerControlSurface.TOOLS),
        )
    }

    @Test
    fun `backfill respects the tools capacity instead of throwing`() {
        // 托盘已满（8/8）且缺少 BACKGROUND_PLAYBACK：补不进去时保持原样，不能让读取失败。
        val legacy = "TOOLS=SCREENSHOT,AB_LOOP,MIRROR_HORIZONTAL,MIRROR_VERTICAL,INFO,PLAYLIST,PIP,SETTINGS"

        val decoded = PlayerControlLayoutCodec.decode(legacy)

        assertEquals(PlayerControlSurface.TOOLS.capacity, decoded.controls(PlayerControlSurface.TOOLS).size)
        assertFalse(PlayerControlId.BACKGROUND_PLAYBACK in decoded.controls(PlayerControlSurface.TOOLS))
    }

    /** 当前版本原样返回：用户主动移除过的回填按钮不会被反复塞回，移除是永久的。 */
    @Test
    fun `current version layout keeps the backfilled control the user removed`() {
        val stored = PlayerControlLayout().remove(PlayerControlId.BACKGROUND_PLAYBACK)

        val decoded = PlayerControlLayoutCodec.decode(PlayerControlLayoutCodec.encode(stored))

        assertEquals(stored, decoded)
        assertFalse(PlayerControlId.BACKGROUND_PLAYBACK in decoded.controls(PlayerControlSurface.TOOLS))
        // 幂等：再次编解码结果不变。
        assertEquals(decoded, PlayerControlLayoutCodec.decode(PlayerControlLayoutCodec.encode(decoded)))
    }

    /** 版本比当前新（例如从更高版本备份恢复）：不认识就不要动用户数据。 */
    @Test
    fun `newer version value is returned verbatim`() {
        val newer = "${PlayerControlLayoutCodec.CURRENT_LAYOUT_VERSION + 1};TOOLS=SCREENSHOT"

        val decoded = PlayerControlLayoutCodec.decode(newer)

        assertEquals(listOf(PlayerControlId.SCREENSHOT), decoded.controls(PlayerControlSurface.TOOLS))
    }

    @Test
    fun `malformed values fall back to the default layout`() {
        val overflowedTools = PlayerControlId.entries
            .filterNot { it.fixed }
            .take(PlayerControlSurface.TOOLS.capacity + 1)
            .joinToString(",")
        val malformed = listOf(
            "",
            "4",
            "NOT_A_SURFACE=SCREENSHOT",
            "TOOLS=NOT_A_CONTROL",
            "TOOLS=SCREENSHOT,SCREENSHOT",
            "LANDSCAPE_TOP_RIGHT=SCREENSHOT;LANDSCAPE_BOTTOM_LEFT=SCREENSHOT",
            "TOOLS=$overflowedTools",
            "4;TOOLS=NOT_A_CONTROL",
        )

        malformed.forEach { raw ->
            assertEquals("raw=$raw", PlayerControlLayout(), PlayerControlLayoutCodec.decode(raw))
        }
    }
}
