package seeyuer.yingli.player.core.designsystem.icon

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import java.io.File
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
        assertEquals(
            listOf(
                YingLiIcon.PLAY_MODE_SEQUENCE,
                YingLiIcon.FLIP_HORIZONTAL,
                YingLiIcon.FLIP_VERTICAL,
            ),
            localIcons,
        )

        // 同一个仓库里生成的 Tabler 图标作为风格参照：2dp 圆头圆角。
        val tablerReference = YingLiIcon.PLAYLIST.imageVector.root
            .filterIsInstance<VectorPath>()
            .last()

        localIcons.forEach { icon ->
            val vector = icon.imageVector
            assertEquals(24f, vector.viewportWidth, 0f)
            assertEquals(24f, vector.viewportHeight, 0f)

            // 设计资产图标逐条 <path> 移植，因此允许一个图标含多条 VectorPath；
            // 但每条路径都必须满足同一套描边约定。
            val paths = vector.root.filterIsInstance<VectorPath>()
            assertTrue("${icon.name} 至少要有一条描边路径", paths.isNotEmpty())
            paths.forEach { path ->
                assertEquals("${icon.name} 应为描边图标而非填充图标", null, path.fill)
                assertNotNull("${icon.name} 缺少描边", path.stroke)
                assertEquals(tablerReference.strokeLineWidth, path.strokeLineWidth, 0f)
                assertEquals(tablerReference.strokeLineCap, path.strokeLineCap)
                assertEquals(tablerReference.strokeLineJoin, path.strokeLineJoin)
            }
        }
    }

    @Test
    fun `play mode sequence keeps its four sub paths in a single vector path`() {
        val path = YingLiIcon.PLAY_MODE_SEQUENCE.imageVector.root
            .filterIsInstance<VectorPath>()
            .single()

        assertEquals("应保留 4 段子路径", 4, path.pathData.count { it is PathNode.MoveTo })
    }

    @Test
    fun `design asset icons keep every svg path as its own vector path`() {
        assertEquals(
            "flip-horizontal.svg 有 5 条 path",
            5,
            YingLiIcon.FLIP_HORIZONTAL.imageVector.root.filterIsInstance<VectorPath>().size,
        )
        assertEquals(
            "flip-vertical.svg 有 5 条 path",
            5,
            YingLiIcon.FLIP_VERTICAL.imageVector.root.filterIsInstance<VectorPath>().size,
        )
    }

    @Test
    fun `design asset icons match design assets path by path`() {
        // 这一条是"设计资产为准"的守门测试：谁改了 design/assets/icons 下的 SVG 却漏改
        // YingLiCustomIcons.kt，或者反过来改了转换结果，都会在这里失败。
        mapOf(
            "flip-horizontal.svg" to YingLiIcon.FLIP_HORIZONTAL,
            "flip-vertical.svg" to YingLiIcon.FLIP_VERTICAL,
        ).forEach { (fileName, icon) ->
            val svg = File(designAssetDir, fileName).readText()
            val svgPaths = SVG_PATH_DATA.findAll(svg).map { it.groupValues[1].trim() }.toList()
            val iconPaths = icon.imageVector.root
                .filterIsInstance<VectorPath>()
                .map { it.name.trim() }

            assertTrue("$fileName 没解析出 path", svgPaths.isNotEmpty())
            assertEquals("$fileName 的 path 数量与移植结果不一致", svgPaths, iconPaths)
        }
    }

    private companion object {
        /** `design/assets/icons` 相对 app 模块的路径（单元测试的工作目录是模块目录）。 */
        val designAssetDir = File("../design/assets/icons")

        /** 取 path 元素的 `d` 属性，兼容属性顺序与换行。 */
        val SVG_PATH_DATA = Regex("""d="([^"]+)"""")
    }
}
