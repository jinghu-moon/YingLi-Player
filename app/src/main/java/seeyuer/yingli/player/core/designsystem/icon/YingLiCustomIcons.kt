package seeyuer.yingli.player.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.ImageVector.Builder
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 直接移植的设计资产图标：**以 `design/assets/icons` 里的 SVG 原素材为准，逐路径移植，避免依赖图标库版本漂移**。
 *
 * 与 [YingLiLocalIcons] 的区别：[YingLiLocalIcons] 用生成图标集自带的 `iconBuilder`/`addPathData`，
 * 只解决"图标库里没有该语义"的问题；本文件用于**即便图标库有同名图标也照抄设计稿**的情况，
 * 这样图标库升级后已定稿的字形不会跟着变。
 *
 * ## 新增设计图标时的移植步骤
 *
 * 1. 把设计给的 SVG 放进 `design/assets/icons/`，原文件不改动；
 * 2. 读 SVG 的 `width`/`height`/`viewBox`，确定尺寸与视图（通常 24dp、`0 0 24 24`）；
 * 3. 读 SVG 的**每一条** path 元素里的 `d` 属性，一条 path 对应一次 [AssetIconBuilder.addAssetPath]
 *    （顺序保持一致），path 字符串原样保留，不做手工改写；
 * 4. 描边参数与 SVG 根元素对齐并保持全项目一致：`fill = null`、`stroke = SolidColor(Color.Black)`、
 *    `strokeLineWidth = 2f`、`strokeLineCap = StrokeCap.Round`、`strokeLineJoin = StrokeJoin.Round`
 *    （渲染时 Icon 组件传入的 tint 会覆盖这里的黑色）；
 * 5. 在 [YingLiIcon] 里加语义条目，把映射指向本文件的常量，并把该条目标为 `IconProvider.LOCAL_VECTOR`。
 */
internal object YingLiCustomIcons {
    /** 左右镜像翻转，由 `design/assets/icons/flip-horizontal.svg` 移植（5 条 path）。 */
    val FlipHorizontal: ImageVector by lazy {
        assetIcon(name = "FlipHorizontal") {
            addAssetPath("M12 3V21")
            addAssetPath("M8 5H6C4.9 5 4 5.9 4 7V17C4 18.1 4.9 19 6 19H8")
            addAssetPath("M16 5H18C19.1 5 20 5.9 20 7V8")
            addAssetPath("M20 11V13")
            addAssetPath("M20 16V17C20 18.1 19.1 19 18 19H16")
        }
    }

    /** 上下镜像翻转，由 `design/assets/icons/flip-vertical.svg` 移植（5 条 path）。 */
    val FlipVertical: ImageVector by lazy {
        assetIcon(name = "FlipVertical") {
            addAssetPath("M3 12H21")
            addAssetPath("M19 8V6C19 4.9 18.1 4 17 4H7C5.9 4 5 4.9 5 6V8")
            addAssetPath("M19 16V18C19 19.1 18.1 20 17 20H16")
            addAssetPath("M13 20H11")
            addAssetPath("M8 20H7C5.9 20 5 19.1 5 18V16")
        }
    }
}

/** 用 24dp / 24×24 视图构建一个设计资产图标，尺寸与 SVG 的 `width="24" height="24" viewBox="0 0 24 24"` 对齐。 */
private fun assetIcon(
    name: String,
    block: AssetIconBuilder.() -> Unit,
): ImageVector = AssetIconBuilder(name).apply(block).build()

/** 按 SVG 的写法逐条累积 `<path>` 的构建器。 */
private class AssetIconBuilder(
    name: String,
) {
    private val builder: Builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )

    /**
     * 移植 SVG 中的一条 path 元素。
     *
     * 路径字符串在这里（首次访问图标时）由 [PathParser] 一次性解析成 PathNode 列表，
     * 之后固定复用，不在每次绘制时重新解析文本。
     */
    fun addAssetPath(pathData: String) {
        builder.addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            pathFillType = PathFillType.NonZero,
            name = pathData,
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    fun build(): ImageVector = builder.build()
}
