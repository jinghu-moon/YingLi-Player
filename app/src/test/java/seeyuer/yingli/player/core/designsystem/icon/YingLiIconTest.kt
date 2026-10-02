package seeyuer.yingli.player.core.designsystem.icon

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YingLiIconTest {
    @Test
    fun `semantic icons prefer Tabler and keep Material fallback explicit`() {
        val tablerIcons = YingLiIcon.entries.filter { it.provider == IconProvider.TABLER }
        val fallbacks = YingLiIcon.entries.filter { it.provider == IconProvider.MATERIAL_FALLBACK }

        assertTrue(tablerIcons.size > fallbacks.size)
        assertEquals(emptyList<YingLiIcon>(), fallbacks)
    }

    @Test
    fun `every semantic icon resolves to a vector`() {
        YingLiIcon.entries.forEach { icon ->
            assertTrue(icon.imageVector.name.isNotBlank())
        }
    }

    @Test
    fun `local vector icons follow the tabler outline stroke convention`() {
        val localIcons = YingLiIcon.entries.filter { it.provider == IconProvider.LOCAL_VECTOR }
        assertEquals(listOf(YingLiIcon.PLAY_MODE_SEQUENCE), localIcons)

        // 同一个仓库里生成的 Tabler 图标作为风格参照：单个描边路径、2dp 圆头圆角。
        val tablerReference = YingLiIcon.PLAYLIST.imageVector.root
            .filterIsInstance<VectorPath>()
            .single()

        localIcons.forEach { icon ->
            val vector = icon.imageVector
            assertEquals(24f, vector.viewportWidth, 0f)
            assertEquals(24f, vector.viewportHeight, 0f)

            val path = vector.root.filterIsInstance<VectorPath>().single()
            assertEquals("${icon.name} 应为描边图标而非填充图标", null, path.fill)
            assertNotNull("${icon.name} 缺少描边", path.stroke)
            assertEquals(tablerReference.strokeLineWidth, path.strokeLineWidth, 0f)
            assertEquals(tablerReference.strokeLineCap, path.strokeLineCap)
            assertEquals(tablerReference.strokeLineJoin, path.strokeLineJoin)
            assertEquals("${icon.name} 应保留 4 段子路径", 4, path.pathData.count { it is PathNode.MoveTo })
        }
    }
}
