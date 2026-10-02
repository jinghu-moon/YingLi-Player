package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 布局编解码与"按版本分代回填"的契约：
 * 老数据（无版本前缀，或版本号小于当前值）只补齐"比它新"的那几代回填按钮；
 * 数据版本等于当前版本时原样返回，用户移除过的旧代按钮不会复活。
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

    /**
     * 分代回填的核心契约：数据版本 4 的用户只收到"第 5 代"新登记的控件，
     * 第 4 代里被他们移除过的按钮不会因为版本推进而复活。
     */
    @Test
    fun `migration only backfills the generations newer than the stored data version`() {
        // 虚构登记表：第 4 代是本版本登记的控件，第 5 代相当于"将来"新增的控件。
        // currentVersion 仍取当前版本：回填只看"登记代 vs 数据版本"，与这个数字无关。
        val generations = mapOf(
            4 to listOf(
                PlayerControlId.MIRROR_HORIZONTAL,
                PlayerControlId.MIRROR_VERTICAL,
                PlayerControlId.BACKGROUND_PLAYBACK,
            ),
            5 to listOf(PlayerControlId.SCREENSHOT),
        )
        // v4 用户把第 4 代三个按钮全部移除，也顺手移除了截图。
        val stored = PlayerControlLayout()
            .remove(PlayerControlId.MIRROR_HORIZONTAL)
            .remove(PlayerControlId.MIRROR_VERTICAL)
            .remove(PlayerControlId.BACKGROUND_PLAYBACK)
            .remove(PlayerControlId.SCREENSHOT)

        val migrated = PlayerControlLayoutCodec.migrate(
            stored,
            dataVersion = 4,
            currentVersion = PlayerControlLayoutCodec.CURRENT_LAYOUT_VERSION,
            backfill = generations,
        )

        // 第 5 代补进来了……
        assertEquals(
            listOf(PlayerControlId.AB_LOOP, PlayerControlId.INFO, PlayerControlId.SCREENSHOT),
            migrated.controls(PlayerControlSurface.TOOLS),
        )
        // ……第 4 代一个都没复活。
        assertFalse(PlayerControlId.MIRROR_HORIZONTAL in migrated.controls(PlayerControlSurface.TOOLS))
        assertFalse(PlayerControlId.MIRROR_VERTICAL in migrated.controls(PlayerControlSurface.TOOLS))
        assertFalse(PlayerControlId.BACKGROUND_PLAYBACK in migrated.controls(PlayerControlSurface.TOOLS))
    }

    /** 数据版本就是当前版本、登记表里没有更新的代：完全不回填，保持"当前版本原样返回"的既有语义。 */
    @Test
    fun `migration backfills nothing when no registered generation is newer than the data`() {
        val stored = PlayerControlLayout()
            .remove(PlayerControlId.MIRROR_HORIZONTAL)
            .remove(PlayerControlId.MIRROR_VERTICAL)
            .remove(PlayerControlId.BACKGROUND_PLAYBACK)

        val migrated = PlayerControlLayoutCodec.migrate(
            stored,
            dataVersion = PlayerControlLayoutCodec.CURRENT_LAYOUT_VERSION,
            currentVersion = PlayerControlLayoutCodec.CURRENT_LAYOUT_VERSION,
            backfill = PlayerControlLayoutCodec.BACKFILLED_CONTROLS_BY_VERSION,
        )

        assertEquals(stored, migrated)
    }

    /** legacy（视为版本 0）：登记表里所有代都比它新，按引入版本升序逐代回填。 */
    @Test
    fun `legacy data backfills every registered generation in ascending order`() {
        val emptyLayout = PlayerControlLayout(PlayerControlSurface.entries.associateWith { emptyList() })
        // 登记表里第 5 代故意写在第 4 代前面：回填顺序必须按引入版本，而不是按书写顺序。
        val generations = mapOf(
            5 to listOf(PlayerControlId.PLAYLIST),
            4 to listOf(PlayerControlId.SCREENSHOT, PlayerControlId.AB_LOOP),
        )

        val migrated = PlayerControlLayoutCodec.migrate(
            emptyLayout,
            dataVersion = 0,
            currentVersion = 5,
            backfill = generations,
        )

        assertEquals(
            listOf(PlayerControlId.SCREENSHOT, PlayerControlId.AB_LOOP, PlayerControlId.PLAYLIST),
            migrated.controls(PlayerControlSurface.TOOLS),
        )
    }

    /** 分代回填同样受容量与"同方向不重复"约束：补不进去就跳过，不能让读取失败。 */
    @Test
    fun `generational backfill respects capacity and never duplicates a control placed elsewhere`() {
        // 托盘已满（8/8），镜像按钮已被用户放到横屏槽位：第 4 代三个都补不进来。
        val legacy = "LANDSCAPE_TOP_RIGHT=MIRROR_HORIZONTAL" +
            ";TOOLS=SCREENSHOT,AB_LOOP,INFO,PLAYLIST,PIP,SETTINGS,SPEED,ORDER"

        val decoded = PlayerControlLayoutCodec.decode(legacy)

        assertEquals(
            listOf(
                PlayerControlId.SCREENSHOT,
                PlayerControlId.AB_LOOP,
                PlayerControlId.INFO,
                PlayerControlId.PLAYLIST,
                PlayerControlId.PIP,
                PlayerControlId.SETTINGS,
                PlayerControlId.SPEED,
                PlayerControlId.ORDER,
            ),
            decoded.controls(PlayerControlSurface.TOOLS),
        )
        assertEquals(
            listOf(PlayerControlId.MIRROR_HORIZONTAL),
            decoded.controls(PlayerControlSurface.LANDSCAPE_TOP_RIGHT),
        )
    }
}
