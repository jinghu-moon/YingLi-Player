package seeyuer.yingli.player.data.preferences

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import seeyuer.yingli.player.domain.playback.PlayerControlId
import seeyuer.yingli.player.domain.playback.PlayerControlLayout
import seeyuer.yingli.player.domain.playback.PlayerControlLayoutCodec
import seeyuer.yingli.player.domain.playback.PlayerControlSurface

/**
 * 键的读取顺序与老数据的处理。键名在这里按字面量重复声明，是为了钉住存储契约：
 * 老设备上的 `layout_v3` / `layout_v2` 必须继续被读到，否则用户布局会凭空丢失。
 */
class DataStorePlayerControlLayoutRepositoryTest {
    private val layoutV4 = stringPreferencesKey("layout_v4")
    private val layoutV3 = stringPreferencesKey("layout_v3")
    private val layoutV2 = stringPreferencesKey("layout_v2")

    private fun tools(preferences: Preferences): List<PlayerControlId> =
        preferences.readPlayerControlLayout().controls(PlayerControlSurface.TOOLS)

    /** 本次修复的核心回归：曾经在设置页动过布局（写过 v3）的设备，新增按钮也必须自动可见。 */
    @Test
    fun `legacy v3 value written by the previous implementation is backfilled`() {
        val preferences = preferencesOf(layoutV3 to "TOOLS=SCREENSHOT,AB_LOOP,INFO")

        assertEquals(
            listOf(
                PlayerControlId.SCREENSHOT,
                PlayerControlId.AB_LOOP,
                PlayerControlId.INFO,
                PlayerControlId.MIRROR_HORIZONTAL,
                PlayerControlId.MIRROR_VERTICAL,
                PlayerControlId.BACKGROUND_PLAYBACK,
            ),
            tools(preferences),
        )
    }

    @Test
    fun `legacy v2 value is backfilled when it is the only stored key`() {
        val preferences = preferencesOf(layoutV2 to "TOOLS=SCREENSHOT")

        assertEquals(
            listOf(
                PlayerControlId.SCREENSHOT,
                PlayerControlId.MIRROR_HORIZONTAL,
                PlayerControlId.MIRROR_VERTICAL,
                PlayerControlId.BACKGROUND_PLAYBACK,
            ),
            tools(preferences),
        )
    }

    /** 用户已经在新键上表达过取舍（例如移除了回填按钮），老键不得再影响结果。 */
    @Test
    fun `current key wins over legacy keys and is returned verbatim`() {
        val current = PlayerControlLayoutCodec.encode(
            PlayerControlLayout().remove(PlayerControlId.BACKGROUND_PLAYBACK),
        )
        val preferences = mutablePreferencesOf()

        // 先写老键再写新键，模拟"用过老版本、之后又改过布局"的设备。
        preferences[layoutV3] = "TOOLS=SCREENSHOT"
        preferences[layoutV2] = "TOOLS=AB_LOOP"
        preferences[layoutV4] = current

        assertEquals(PlayerControlLayoutCodec.decode(current), preferences.readPlayerControlLayout())
        assertFalse(PlayerControlId.BACKGROUND_PLAYBACK in tools(preferences))
    }

    @Test
    fun `v3 wins over v2`() {
        val preferences = preferencesOf(
            layoutV3 to "TOOLS=AB_LOOP",
            layoutV2 to "TOOLS=SCREENSHOT",
        )

        val controls = tools(preferences)

        assertEquals(PlayerControlId.AB_LOOP, controls.first())
        assertFalse(PlayerControlId.SCREENSHOT in controls)
    }

    @Test
    fun `missing keys yield the default layout`() {
        assertEquals(PlayerControlLayout(), preferencesOf().readPlayerControlLayout())
    }

    /** 老键内容被写坏时不能让读取失败：回落到默认布局。 */
    @Test
    fun `damaged legacy value falls back to the default layout`() {
        val preferences = preferencesOf(layoutV3 to "TOOLS=NOT_A_CONTROL")

        assertEquals(PlayerControlLayout(), preferences.readPlayerControlLayout())
    }
}
