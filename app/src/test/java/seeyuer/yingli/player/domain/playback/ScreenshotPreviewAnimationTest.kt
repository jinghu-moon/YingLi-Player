package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sign

/**
 * 预览卡飞入轨迹的单测。
 *
 * 用户实测反馈的缺陷是"卡片先从左上角向下移动、然后又回到左上角"，也就是轨迹**非单调**。
 * 这里把"起点 → 终点"的换算抽成纯函数，并从三个互补的角度把单调性钉住：
 * 1. 到终点的距离随进度**单调不增**（不允许出现反向段）；
 * 2. 每一步的位移方向**恒定**（不允许拐弯回头）；
 * 3. 两个端点必须精确落在"视频画面区域的右下角"与"卡片静止位置"上（否则会先跑偏再回来）。
 *
 * 只断言"某一帧长什么样"是防不住这类回归的——非单调恰恰只在中间某几帧看得出来。
 *
 * 起点口径已按需求改为**视频画面（不含黑边）的右下角**（此前是捕获按钮中心）：
 * 下面的坐标取"1080x2400 画布 + 16:9 视频"的真实量级——画面是居中一条 1080x607.5 的横带，
 * 右下角落在 (1080, 1503.75)，明显高于屏幕右下角 (1080, 2400)。
 */
class ScreenshotPreviewAnimationTest {
    /** 一段真实量级的几何：卡片 116dp 宽、起点在画布中下部偏右的视频画面右下角。 */
    private val size = ScreenshotPreviewSize(width = 300f, height = 187.5f)
    private val targetX = 12f
    private val targetY = 160f
    private val pictureX = 1_080f
    private val pictureY = 1_503.75f

    private fun track(): ScreenshotPreviewTrack =
        requireNotNull(
            screenshotPreviewTrack(
                startCenterX = pictureX,
                startCenterY = pictureY,
                targetX = targetX,
                targetY = targetY,
                size = size,
            ),
        )

    /** 逐帧采样：UI 的 `graphicsLayer` 用同一对输出，等价于"这一帧卡片画在哪里"。 */
    private fun frames(steps: Int = 120): List<ScreenshotPreviewPoint> {
        val track = track()
        return (0..steps).map { index ->
            val progress = index.toFloat() / steps
            val (dx, dy) = screenshotPreviewEnterTranslation(track, size, targetX, targetY, progress)
            val scale = screenshotPreviewEnterScale(progress)
            // 与 UI 完全一致的合成公式：center = target + scale * (translation * size + size / 2)
            ScreenshotPreviewPoint(
                x = targetX + scale * (dx * size.width + size.width / 2f),
                y = targetY + scale * (dy * size.height + size.height / 2f),
            )
        }
    }

    @Test
    fun trackStartsAtTheVideoPictureBottomRightAndEndsAtTheRestingSpot() {
        val track = track()

        assertEquals(pictureX, track.startCenter.x, 0.01f)
        assertEquals(pictureY, track.startCenter.y, 0.01f)
        assertEquals(targetX + size.width / 2f, track.endCenter.x, 0.01f)
        assertEquals(targetY + size.height / 2f, track.endCenter.y, 0.01f)
    }

    @Test
    fun enteringFramesTouchBothEndpointsExactly() {
        val sampled = frames()

        // 首帧必须正好在画面右下角、末帧必须正好在静止位置：任何一段"跑偏"都表现为端点对不上。
        assertEquals(pictureX, sampled.first().x, 0.01f)
        assertEquals(pictureY, sampled.first().y, 0.01f)
        assertEquals(targetX + size.width / 2f, sampled.last().x, 0.01f)
        assertEquals(targetY + size.height / 2f, sampled.last().y, 0.01f)
    }

    @Test
    fun distanceToTheDestinationNeverIncreases() {
        val end = track().endCenter
        val distances = frames().map { hypot(it.x - end.x, it.y - end.y) }

        distances.zipWithNext().forEachIndexed { index, (previous, next) ->
            assertTrue(
                "第 $index 帧到终点的距离变大了（$previous → $next）：轨迹出现反向段",
                next <= previous + 1e-3f,
            )
        }
        // 而且是**真的在靠近**：起点距离明显大于终点距离，否则动画等于没动。
        assertTrue(distances.first() > distances.last() + 100f)
    }

    @Test
    fun stepDirectionStaysConstantWithoutBacktracking() {
        val sampled = frames()
        val stepsX = sampled.zipWithNext { previous, next -> next.x - previous.x }
        val stepsY = sampled.zipWithNext { previous, next -> next.y - previous.y }

        // 方向恒定：所有步长的符号一致（这里起点在终点右下方，因此 x、y 都在减小）。
        assertEquals(-1f, stepsX.first().sign, 0.01f)
        assertEquals(-1f, stepsY.first().sign, 0.01f)
        assertTrue("x 方向出现反向步", stepsX.all { it <= 1e-3f })
        assertTrue("y 方向出现反向步", stepsY.all { it <= 1e-3f })
        // 每一步都在真的移动（没有"停住再突然跳"的空档）。
        assertTrue(stepsX.all { it < 0f })
        assertTrue(stepsY.all { it < 0f })
    }

    @Test
    fun aStartBelowTheCardStillMovesMonotonically() {
        // 反向场景（卡片在下、起点在上）也要单调：单调性是轨迹的性质，不是这一组坐标的巧合。
        val track = requireNotNull(
            screenshotPreviewTrack(
                startCenterX = 120f,
                startCenterY = 40f,
                targetX = targetX,
                targetY = 600f,
                size = size,
            ),
        )
        val end = track.endCenter
        val distances = (0..60).map { index ->
            val center = screenshotPreviewCenterAt(track, index / 60f)
            hypot(center.x - end.x, center.y - end.y)
        }

        distances.zipWithNext().forEach { (previous, next) -> assertTrue(next <= previous + 1e-3f) }
    }

    @Test
    fun progressOutsideZeroToOneIsClamped() {
        val track = track()

        // 越界的进度不能把卡片甩到轨迹之外（动画库偶尔会在首末帧给出极小越界值）。
        assertEquals(track.startCenter, screenshotPreviewCenterAt(track, -0.5f))
        assertEquals(track.endCenter, screenshotPreviewCenterAt(track, 1.5f))
    }

    @Test
    fun trackIsUnavailableUntilBothMeasurementsExist() {
        // 几何没量到就不给轨迹：调用方据此**等几何到位再开始动画**，
        // 这正是"先跑动画、量到坐标后再纠正位置"（表现为先下后回）的根治办法。
        assertNull(screenshotPreviewTrack(null, null, targetX, targetY, size))
        assertNull(screenshotPreviewTrack(pictureX, pictureY, targetX, targetY, ScreenshotPreviewSize(0f, 0f)))
        assertNull(screenshotPreviewTrack(pictureX, pictureY, targetX, targetY, ScreenshotPreviewSize(300f, 0f)))
    }

    @Test
    fun scaleUsesTheDesignDocumentNumbers() {
        // 设计稿 §6「出现：scale(2.4)→1 飞入（0.42s）」「删除按钮弹入（0.2s）」，
        // §4.10「116px 宽，16:10」。这些数字是需求的一部分，改它们等于改需求。
        assertEquals(2.4f, ScreenshotPreviewSpec.ENTER_SCALE, 0.0001f)
        assertEquals(420, ScreenshotPreviewSpec.ENTER_DURATION_MILLIS)
        assertEquals(200, ScreenshotPreviewSpec.DELETE_BUTTON_DURATION_MILLIS)
        assertEquals(1.6f, ScreenshotPreviewSpec.ASPECT_RATIO, 0.0001f)
        assertEquals(3_000L, ScreenshotUiState.PREVIEW_DURATION_MILLIS)
        assertEquals(ScreenshotPreviewSpec.ENTER_SCALE, screenshotPreviewEnterScale(0f), 0.0001f)
        assertEquals(1f, screenshotPreviewEnterScale(1f), 0.0001f)
    }
}
