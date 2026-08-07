package seeyuer.yingli.player.core.designsystem.icon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YingLiIconTest {
    @Test
    fun `semantic icons prefer Tabler and keep Material fallback explicit`() {
        val tablerIcons = YingLiIcon.entries.filter { it.provider == IconProvider.TABLER }
        val fallbacks = YingLiIcon.entries.filter { it.provider == IconProvider.MATERIAL_FALLBACK }

        assertTrue(tablerIcons.size > fallbacks.size)
        assertEquals(listOf(YingLiIcon.DYNAMIC_COLOR), fallbacks)
    }

    @Test
    fun `every semantic icon resolves to a vector`() {
        YingLiIcon.entries.forEach { icon ->
            assertTrue(icon.imageVector.name.isNotBlank())
        }
    }
}
