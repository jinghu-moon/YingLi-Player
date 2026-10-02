package seeyuer.yingli.player.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import composeicons.core.IconSize
import composeicons.core.ViewBox
import composeicons.core.addPathData
import composeicons.core.iconBuilder

/**
 * 本地维护的 Tabler 风格图标。
 *
 * 只用于本地生成的 Tabler 图标集（`icons-tabler:0.1.0-local.1`，基于 Tabler Icons 3.46.0）
 * 中不存在的语义图标。构建方式与生成的图标完全一致（同一 `iconBuilder` + 24dp/24 视图 + 2dp 圆头描边），
 * 因此描边粗细、端点与其余图标一致；原素材保存在 `design/assets/icons/`，转换日期与来源写在各自条目上。
 */
internal object YingLiLocalIcons {
    /**
     * 顺序播放。Tabler Icons 3.46.0 没有对应图标，由 `design/assets/icons/play-mode-sequence.svg`
     * （24dp 视图、`stroke-width: 2`、圆角端点）转换而来。
     */
    val PlayModeSequence: ImageVector by lazy {
        iconBuilder(
            name = "PlayModeSequence",
            size = IconSize(width = 24.dp, height = 24.dp),
            viewBox = ViewBox(width = 24f, height = 24f),
        ) {
            addPathData(
                pathData = "M4 7h13 M17 4l3 3l-3 3 M4 17h13 M17 17h3",
                pathFillType = PathFillType.NonZero,
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }
}
