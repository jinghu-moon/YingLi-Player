package seeyuer.yingli.player.feature.player

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlayerGestureRecognizer] 的行为规格测试。
 *
 * 统一用"1dp = 0.5px"（[density]）与 1000x2000 的画面，让阈值和分区边界都是整齐的整数：
 * 起手 3px、锁定 5px、长按取消 4px、系统边缘 10px；左右侧区 0..400 / 600..1000，中间 400..600。
 */
class PlayerGestureRecognizerTest {
    private companion object {
        const val DENSITY = 0.5f
        const val DOWN_TIME = 1_000L
        const val WIDTH = 1000f
        const val HEIGHT = 2000f
        const val START_SLOP_PX = PlayerGestureSpec.START_SLOP_DP * DENSITY
        const val AXIS_LOCK_PX = PlayerGestureSpec.AXIS_LOCK_DP * DENSITY
        const val LONG_PRESS_CANCEL_PX = PlayerGestureSpec.LONG_PRESS_MOVE_CANCEL_DP * DENSITY
        const val EDGE_INSET_PX = PlayerGestureSpec.SYSTEM_EDGE_INSET_DP * DENSITY
        const val SIDE_ZONE_PX = WIDTH * PlayerGestureSpec.VERTICAL_GESTURE_ZONE_FRACTION

        /** 左/右侧区各取一个"避开系统边缘、也落在侧区内"的起点。 */
        const val LEFT_X = 300f
        const val RIGHT_X = 700f

        /** 中央 20% 内、且离左右侧区分界最远的起点。 */
        const val MIDDLE_X = 500f
    }

    // ---------- 1. 起手阈值与 2. 轴向锁定 ----------

    @Test
    fun `movement within start slop does not decide a target`() {
        val recognizer = recognizer()

        recognizer.down(LEFT_X)
        assertTrue(recognizer.move(LEFT_X, 1000f - (START_SLOP_PX - 1f)).isEmpty())
        assertTrue(recognizer.up(LEFT_X, 1000f - (START_SLOP_PX - 1f)).isEmpty())

        // 起手阈值内也没锁定主轴：紧接着的斜向移动仍能按真实方向锁定。
        recognizer.down(LEFT_X, y = 1000f)
        assertEquals(
            listOf(PlayerGestureAction.Begin(PlayerGestureTarget.VOLUME)),
            recognizer.moves(LEFT_X + 40f, 1000f - 40f),
        )
    }

    @Test
    fun `movement between start slop and axis lock still decides nothing`() {
        val recognizer = recognizer()

        recognizer.down(LEFT_X)
        val travel = (START_SLOP_PX + AXIS_LOCK_PX) / 2f

        assertTrue(recognizer.move(LEFT_X, 1000f - travel).isEmpty())
        assertTrue(recognizer.up(LEFT_X, 1000f - travel).isEmpty())
    }

    @Test
    fun `axis locks to the dominant direction and never flips`() {
        val recognizer = recognizer()

        recognizer.down(MIDDLE_X)
        assertEquals(
            listOf(PlayerGestureAction.Begin(PlayerGestureTarget.SEEK)),
            recognizer.moves(MIDDLE_X + 200f, 1000f - 20f),
        )
        // 之后即使纵向位移远大，目标依然是锁定时判定的 SEEK。
        assertEquals(
            listOf(PlayerGestureAction.Update(PlayerGestureTarget.SEEK, 0.25f, 0.3f)),
            recognizer.moves(MIDDLE_X + 250f, 1000f - 600f),
        )
    }

    @Test
    fun `vertical axis wins when both deltas are equal`() {
        val recognizer = recognizer()

        recognizer.down(LEFT_X)
        assertEquals(
            listOf(PlayerGestureAction.Begin(PlayerGestureTarget.VOLUME)),
            recognizer.moves(LEFT_X + AXIS_LOCK_PX, 1000f - AXIS_LOCK_PX),
        )
    }

    // ---------- 3. 目标判定（按起点 x） ----------

    @Test
    fun `horizontal drag always seeks regardless of start side`() {
        listOf(LEFT_X, MIDDLE_X, RIGHT_X).forEach { startX ->
            val recognizer = recognizer()

            recognizer.down(startX)
            assertEquals(
                "起点 $startX 的水平拖动应判定为 SEEK",
                listOf(PlayerGestureAction.Begin(PlayerGestureTarget.SEEK)),
                recognizer.moves(startX + 60f, 1000f),
            )
        }
    }

    @Test
    fun `vertical drag maps left to volume and right to brightness`() {
        assertEquals(
            listOf(PlayerGestureAction.Begin(PlayerGestureTarget.VOLUME)),
            verticalDrag(LEFT_X),
        )
        assertEquals(
            listOf(PlayerGestureAction.Begin(PlayerGestureTarget.BRIGHTNESS)),
            verticalDrag(RIGHT_X),
        )
    }

    @Test
    fun `left volume mapping can be swapped with brightness`() {
        val config = PlayerGestureConfig(leftSideIsVolume = false, densityPxPerDp = DENSITY)

        assertEquals(
            listOf(PlayerGestureAction.Begin(PlayerGestureTarget.BRIGHTNESS)),
            verticalDrag(LEFT_X, config),
        )
        assertEquals(
            listOf(PlayerGestureAction.Begin(PlayerGestureTarget.VOLUME)),
            verticalDrag(RIGHT_X, config),
        )
    }

    @Test
    fun `vertical drag anywhere on the canvas responds`() {
        // 规格变更（用户反馈 + 借鉴 NextPlayer）：竖向手势以画面中线二分、整屏无死区。
        val recognizer = recognizer()

        recognizer.down(MIDDLE_X)
        assertEquals(
            listOf(PlayerGestureAction.Begin(PlayerGestureTarget.VOLUME)),
            recognizer.move(MIDDLE_X, 1000f - 200f),
        )
        assertEquals(
            listOf(PlayerGestureAction.End(PlayerGestureTarget.VOLUME, 0f, 0.2f)),
            recognizer.up(MIDDLE_X, 1000f - 400f),
        )
    }

    @Test
    fun `vertical gesture zones split exactly at the canvas center`() {
        assertEquals(
            listOf(PlayerGestureAction.Begin(PlayerGestureTarget.VOLUME)),
            verticalDrag(WIDTH * 0.49f),
        )
        assertEquals(
            listOf(PlayerGestureAction.Begin(PlayerGestureTarget.BRIGHTNESS)),
            verticalDrag(WIDTH * 0.51f),
        )
        // 正好落在中线也有归属（用 <= / >= 消除那一像素死区）。
        assertEquals(
            listOf(PlayerGestureAction.Begin(PlayerGestureTarget.VOLUME)),
            verticalDrag(WIDTH * 0.5f),
        )
    }

    @Test
    fun `vertical drag starting inside the system edge inset does not respond`() {
        listOf(EDGE_INSET_PX - 1f, WIDTH - EDGE_INSET_PX + 1f).forEach { startX ->
            val recognizer = recognizer()

            recognizer.down(startX)
            assertTrue("起点 $startX 落在系统边缘内不应判定目标", recognizer.move(startX, 1000f - 200f).isEmpty())
            assertTrue(recognizer.up(startX, 1000f - 200f).isEmpty())
        }
    }

    @Test
    fun `target is decided by the down position not the current position`() {
        val recognizer = recognizer()

        // 起点在左侧区，拖动过程中手指滑到右半屏；主轴仍是垂直（|dy| 远大于 |dx|），
        // 因此目标由 downX 的分区决定，仍是 VOLUME（不是 SEEK）。
        recognizer.down(LEFT_X)
        assertEquals(
            listOf(PlayerGestureAction.Begin(PlayerGestureTarget.VOLUME)),
            recognizer.moves(WIDTH - EDGE_INSET_PX - 1f, 1000f - 800f),
        )
    }

    // ---------- 3. 开关关闭时不判定、不回退 ----------

    @Test
    fun `seek is skipped when disabled and does not fall back to a vertical gesture`() {
        val recognizer = recognizer(PlayerGestureConfig(seekEnabled = false, densityPxPerDp = DENSITY))

        recognizer.down(LEFT_X)
        assertTrue(recognizer.move(LEFT_X + 200f, 1000f - 20f).isEmpty())
        // 水平主轴已锁定，之后向下滑也不会去改音量。
        assertTrue(recognizer.move(LEFT_X + 200f, 1000f + 400f).isEmpty())
        assertTrue(recognizer.up(LEFT_X + 200f, 1000f + 400f).isEmpty())
    }

    @Test
    fun `left vertical gesture does not fall back to brightness when volume is disabled`() {
        val recognizer = recognizer(PlayerGestureConfig(volumeEnabled = false, densityPxPerDp = DENSITY))

        recognizer.down(LEFT_X)
        assertTrue(recognizer.move(LEFT_X, 1000f - 200f).isEmpty())
        // 即使在左侧区起手后滑到右半屏，也不会退化成亮度。
        assertTrue(recognizer.move(RIGHT_X, 1000f - 300f).isEmpty())
        assertTrue(recognizer.up(RIGHT_X, 1000f - 300f).isEmpty())
    }

    @Test
    fun `right vertical gesture does not fall back to volume when brightness is disabled`() {
        val recognizer = recognizer(PlayerGestureConfig(brightnessEnabled = false, densityPxPerDp = DENSITY))

        recognizer.down(RIGHT_X)
        assertTrue(recognizer.move(RIGHT_X, 1000f - 200f).isEmpty())
        assertTrue(recognizer.up(RIGHT_X, 1000f - 200f).isEmpty())
    }

    // ---------- 4. 归一化（5. 拖动开始 / 6. 抬手） ----------

    @Test
    fun `fractions are normalized by stage size with up and right positive`() {
        val recognizer = recognizer()

        recognizer.down(LEFT_X)
        assertEquals(
            listOf(PlayerGestureAction.Begin(PlayerGestureTarget.SEEK)),
            recognizer.moves(LEFT_X + 200f, 1000f - 20f),
        )
        assertEquals(
            listOf(PlayerGestureAction.Update(PlayerGestureTarget.SEEK, 0.3f, 0.3f)),
            recognizer.moves(LEFT_X + 300f, 1000f - 600f),
        )
        assertEquals(
            listOf(PlayerGestureAction.End(PlayerGestureTarget.SEEK, 0.3f, 0.3f)),
            recognizer.up(LEFT_X + 300f, 1000f - 600f),
        )
    }

    @Test
    fun `downward and leftward movement yields negative fractions`() {
        val recognizer = recognizer()

        recognizer.down(RIGHT_X)
        assertEquals(
            listOf(PlayerGestureAction.Begin(PlayerGestureTarget.BRIGHTNESS)),
            recognizer.moves(RIGHT_X, 1000f + 20f),
        )
        assertEquals(
            listOf(PlayerGestureAction.Update(PlayerGestureTarget.BRIGHTNESS, -0.2f, -0.25f)),
            recognizer.moves(RIGHT_X - 200f, 1000f + 500f),
        )
        assertEquals(
            listOf(PlayerGestureAction.End(PlayerGestureTarget.BRIGHTNESS, -0.2f, -0.25f)),
            recognizer.up(RIGHT_X - 200f, 1000f + 500f),
        )
    }

    @Test
    fun `repeated move with the same offset is not reported twice`() {
        val recognizer = recognizer()

        recognizer.down(LEFT_X)
        recognizer.move(LEFT_X, 1000f - 100f)

        assertTrue(recognizer.moves(LEFT_X, 1000f - 100f).isEmpty())
        // 回到按下位置也是一次真实的位移变化，要下发。
        assertEquals(
            listOf(PlayerGestureAction.Update(PlayerGestureTarget.VOLUME, 0f, 0f)),
            recognizer.moves(LEFT_X, 1000f),
        )
    }

    @Test
    fun `end reports the final edge position even when no move event reached it`() {
        val recognizer = recognizer()

        recognizer.down(LEFT_X, y = 1_000f)
        assertEquals(
            listOf(PlayerGestureAction.Begin(PlayerGestureTarget.VOLUME)),
            recognizer.move(LEFT_X, 900f),
        )
        assertEquals(
            listOf(PlayerGestureAction.End(PlayerGestureTarget.VOLUME, 0f, 0.5f)),
            recognizer.up(LEFT_X, 0f),
        )
    }

    @Test
    fun `up without a decided target reports nothing`() {
        val recognizer = recognizer()

        recognizer.down(MIDDLE_X)
        assertTrue(recognizer.up(MIDDLE_X, 1000f - (START_SLOP_PX - 1f)).isEmpty())
        assertTrue(recognizer.up(MIDDLE_X, 1000f - (START_SLOP_PX - 1f)).isEmpty())
    }

    // ---------- 7. 长按倍速 ----------

    @Test
    fun `long press begins at the timeout and ends on release`() {
        val recognizer = recognizer()

        recognizer.down(MIDDLE_X)
        assertTrue(recognizer.longPressTimeout(DOWN_TIME + PlayerGestureSpec.LONG_PRESS_MILLIS - 1).isEmpty())
        assertEquals(
            listOf(PlayerGestureAction.LongPressBegin),
            recognizer.longPressTimeout(DOWN_TIME + PlayerGestureSpec.LONG_PRESS_MILLIS),
        )
        // 计时器重复到点不会重复开始。
        assertTrue(recognizer.longPressTimeout(DOWN_TIME + 1_000L).isEmpty())
        assertEquals(listOf(PlayerGestureAction.LongPressEnd), recognizer.up(MIDDLE_X, 1000f))
    }

    @Test
    fun `long press is cancelled on release and on cancel event`() {
        assertEquals(listOf(PlayerGestureAction.LongPressEnd), longPressed().up(MIDDLE_X, 1000f))
        assertEquals(listOf(PlayerGestureAction.LongPressEnd), longPressed().cancel())
    }

    @Test
    fun `long press does not start when the finger already moved or the timer is early`() {
        val moved = recognizer()
        moved.down(MIDDLE_X)
        moved.move(MIDDLE_X, 1000f - LONG_PRESS_CANCEL_PX - 1f)
        assertTrue(moved.longPressTimeout(DOWN_TIME + PlayerGestureSpec.LONG_PRESS_MILLIS).isEmpty())

        val early = recognizer()
        early.down(MIDDLE_X)
        assertTrue(early.longPressTimeout(DOWN_TIME + PlayerGestureSpec.LONG_PRESS_MILLIS - 1).isEmpty())
        // 计时到点后仍应触发：早到的一次不消耗长按机会（调用方每次按压只问一次，这里是防御性语义）。
        assertEquals(
            listOf(PlayerGestureAction.LongPressBegin),
            early.longPressTimeout(DOWN_TIME + 10_000L),
        )
    }

    @Test
    fun `long press never reports a tap`() {
        val recognizer = longPressed()

        assertTrue(recognizer.tap().isEmpty())
        assertTrue(recognizer.moves(MIDDLE_X, 1000f - 2f).isEmpty())
        assertTrue(recognizer.tap().isEmpty())
    }

    @Test
    fun `dragging after a long press first ends the speed boost then drags`() {
        val recognizer = longPressed()

        assertEquals(
            listOf(
                PlayerGestureAction.LongPressEnd,
                PlayerGestureAction.Begin(PlayerGestureTarget.SEEK),
            ),
            recognizer.moves(MIDDLE_X + 200f, 1000f),
        )
        assertEquals(
            listOf(PlayerGestureAction.Update(PlayerGestureTarget.SEEK, 0.4f, 0f)),
            recognizer.moves(MIDDLE_X + 400f, 1000f),
        )
        assertEquals(
            listOf(PlayerGestureAction.End(PlayerGestureTarget.SEEK, 0.4f, 0f)),
            recognizer.up(MIDDLE_X + 400f, 1000f),
        )
    }

    @Test
    fun `long press begins anywhere on the canvas`() {
        // 规格变更（借鉴 NextPlayer）：长按倍速不再限制在中心窄带——长按不移动，
        // 与音量/亮度/进度不冲突，限制区域等于几乎按不出来。
        val side = recognizer()
        side.down(LEFT_X)
        assertEquals(
            listOf(PlayerGestureAction.LongPressBegin),
            side.longPressTimeout(DOWN_TIME + PlayerGestureSpec.LONG_PRESS_MILLIS),
        )

        val center = recognizer()
        center.down(MIDDLE_X)
        assertEquals(
            listOf(PlayerGestureAction.LongPressBegin),
            center.longPressTimeout(DOWN_TIME + PlayerGestureSpec.LONG_PRESS_MILLIS),
        )
    }

    // ---------- 8. 锁定态 ----------

    @Test
    fun `locked state ignores every gesture but still reports taps and double taps`() {
        val recognizer = recognizer(PlayerGestureConfig(locked = true, densityPxPerDp = DENSITY))

        recognizer.down(LEFT_X)
        assertTrue(recognizer.move(LEFT_X + 200f, 1000f - 20f).isEmpty())
        assertTrue(recognizer.move(LEFT_X, 1000f - 200f).isEmpty())
        assertTrue(recognizer.longPressTimeout(DOWN_TIME + PlayerGestureSpec.LONG_PRESS_MILLIS).isEmpty())
        assertTrue(recognizer.up(LEFT_X, 1000f - 200f).isEmpty())

        // 单击仍要能唤出解锁入口，双击仍按左/中/右分类。
        assertEquals(listOf(PlayerGestureAction.Tap), recognizer.tap())
        assertEquals(listOf(PlayerGestureAction.DoubleTapCenter), recognizer.doubleTap(MIDDLE_X))
        assertEquals(listOf(PlayerGestureAction.DoubleTapBackward), recognizer.doubleTap(LEFT_X))
        assertEquals(listOf(PlayerGestureAction.DoubleTapForward), recognizer.doubleTap(RIGHT_X))
    }

    @Test
    fun `locking during a drag ends the target and stops single finger actions`() {
        val recognizer = recognizer()

        recognizer.down(LEFT_X)
        recognizer.move(LEFT_X, 1000f - 200f)

        assertEquals(
            listOf(PlayerGestureAction.End(PlayerGestureTarget.VOLUME, 0f, 0.1f)),
            recognizer.update(PlayerGestureConfig(locked = true, densityPxPerDp = DENSITY)),
        )
        assertTrue(recognizer.move(LEFT_X, 1000f - 400f).isEmpty())

        recognizer.down(LEFT_X)
        assertTrue(recognizer.move(LEFT_X, 1000f - 400f).isEmpty())
        assertTrue(recognizer.up(LEFT_X, 1000f - 400f).isEmpty())
    }

    @Test
    fun `disabling the active gesture during a drag ends it`() {
        val recognizer = recognizer()

        recognizer.down(LEFT_X)
        recognizer.move(LEFT_X, 1000f - 200f)

        assertEquals(
            listOf(PlayerGestureAction.End(PlayerGestureTarget.VOLUME, 0f, 0.1f)),
            recognizer.update(PlayerGestureConfig(volumeEnabled = false, densityPxPerDp = DENSITY)),
        )
    }

    // ---------- 9. 双击分类 ----------

    @Test
    fun `double tap splits into center left and right`() {
        val recognizer = recognizer()

        assertEquals(listOf(PlayerGestureAction.DoubleTapBackward), recognizer.doubleTap(LEFT_X))
        assertEquals(listOf(PlayerGestureAction.DoubleTapCenter), recognizer.doubleTap(MIDDLE_X))
        assertEquals(listOf(PlayerGestureAction.DoubleTapForward), recognizer.doubleTap(RIGHT_X))
        assertEquals(
            listOf(PlayerGestureAction.DoubleTapBackward),
            recognizer.doubleTap(WIDTH * (0.5f - PlayerGestureSpec.DOUBLE_TAP_SIDE_ZONE_FRACTION / 2f) - 1f),
        )
        assertEquals(
            listOf(PlayerGestureAction.DoubleTapForward),
            recognizer.doubleTap(WIDTH * (0.5f + PlayerGestureSpec.DOUBLE_TAP_SIDE_ZONE_FRACTION / 2f)),
        )
    }

    @Test
    fun `double tap inside the system edge inset counts as the nearest side`() {
        val recognizer = recognizer()

        assertEquals(listOf(PlayerGestureAction.DoubleTapBackward), recognizer.doubleTap(1f))
        assertEquals(listOf(PlayerGestureAction.DoubleTapBackward), recognizer.doubleTap(EDGE_INSET_PX - 1f))
        assertEquals(listOf(PlayerGestureAction.DoubleTapForward), recognizer.doubleTap(WIDTH - 1f))
        assertEquals(listOf(PlayerGestureAction.DoubleTapForward), recognizer.doubleTap(WIDTH - EDGE_INSET_PX + 1f))
    }

    @Test
    fun `double tap with a zero sized stage reports nothing`() {
        assertTrue(recognizer().doubleTap(MIDDLE_X, widthPx = 0f).isEmpty())
    }

    // ---------- 10. 多指 ----------

    @Test
    fun `multi finger gesture never reports single finger targets`() {
        val recognizer = recognizer()

        recognizer.down(LEFT_X, pointerCount = 2)
        assertTrue(recognizer.move(LEFT_X + 100f, 1000f - 100f, pointerCount = 2).isEmpty())
        assertTrue(recognizer.up(LEFT_X + 100f, 1000f - 100f).isEmpty())

        // 抬起一根手指后仍按多指处理，直到下一次单指按下重新开始。
        recognizer.down(LEFT_X, pointerCount = 2)
        assertTrue(recognizer.move(LEFT_X + 100f, 1000f - 100f, pointerCount = 1).isEmpty())
        recognizer.longPressTimeout(DOWN_TIME + PlayerGestureSpec.LONG_PRESS_MILLIS)
        assertTrue(recognizer.tap().isEmpty())
        assertTrue(recognizer.up(LEFT_X + 100f, 1000f - 100f).isEmpty())
    }

    @Test
    fun `a second finger arriving later cancels the single finger gesture`() {
        val recognizer = recognizer()

        recognizer.down(LEFT_X)
        recognizer.down(LEFT_X + 5f, pointerCount = 2)
        assertTrue(recognizer.move(LEFT_X + 200f, 1000f - 200f, pointerCount = 2).isEmpty())
        assertTrue(recognizer.up(LEFT_X + 200f, 1000f - 200f).isEmpty())
    }

    @Test
    fun `multi pointer interruption cancels active volume without committing an end`() {
        val recognizer = recognizer()
        recognizer.down(LEFT_X)
        assertEquals(
            listOf(PlayerGestureAction.Begin(PlayerGestureTarget.VOLUME)),
            recognizer.moves(LEFT_X, 1000f - 200f),
        )

        assertEquals(
            listOf(PlayerGestureAction.Cancel(PlayerGestureTarget.VOLUME)),
            recognizer.onMultiPointer(),
        )
        assertTrue(recognizer.up(LEFT_X, 0f).isEmpty())
    }

    // ---------- 11. 配置变更与重置 ----------

    @Test
    fun `update takes effect immediately and reset returns to the initial state`() {
        val recognizer = recognizer()

        recognizer.down(LEFT_X)
        assertTrue(recognizer.moves(LEFT_X, 1000f - 200f).isNotEmpty())

        recognizer.reconfigure(PlayerGestureConfig(volumeEnabled = false, densityPxPerDp = DENSITY))
        assertTrue(recognizer.moves(LEFT_X + AXIS_LOCK_PX * 2f, 1000f - 200f).isEmpty())

        // reset 会连同设置快照一起回到默认值。
        recognizer.reset()
        recognizer.down(LEFT_X)
        assertEquals(
            listOf(PlayerGestureAction.Begin(PlayerGestureTarget.VOLUME)),
            recognizer.moves(LEFT_X, 1000f - 200f),
        )
    }

    @Test
    fun `reset drops the in-flight gesture`() {
        val recognizer = recognizer()

        recognizer.down(LEFT_X)
        recognizer.move(LEFT_X, 1000f - 200f)
        recognizer.reset()

        assertTrue(recognizer.move(LEFT_X, 1000f - 400f).isEmpty())
        assertTrue(recognizer.up(LEFT_X, 1000f - 400f).isEmpty())
    }

    @Test
    fun `density converts dp thresholds to pixels`() {
        val recognizer = recognizer(PlayerGestureConfig(densityPxPerDp = 3f))

        recognizer.down(LEFT_X)
        // 3px/dp 下起手阈值是 18px、锁定阈值是 30px：29px 仍然不判定。
        assertTrue(recognizer.moves(LEFT_X + 29f, 1000f).isEmpty())
        assertEquals(
            listOf(PlayerGestureAction.Begin(PlayerGestureTarget.SEEK)),
            recognizer.moves(LEFT_X + 30f, 1000f),
        )
    }

    @Test
    fun `a broken density falls back to one pixel per dp`() {
        val recognizer = recognizer(PlayerGestureConfig(densityPxPerDp = 0f))

        recognizer.down(LEFT_X)
        // density=0 会让所有阈值变成 0：必须回退成 1px/dp，否则"没动也算拖动"。
        assertTrue(recognizer.moves(LEFT_X + (PlayerGestureSpec.AXIS_LOCK_DP - 1f), 1000f).isEmpty())
        assertEquals(
            listOf(PlayerGestureAction.Begin(PlayerGestureTarget.SEEK)),
            recognizer.moves(LEFT_X + PlayerGestureSpec.AXIS_LOCK_DP, 1000f),
        )
    }

    // ---------- 辅助 ----------

    // ---------- 单击 / 双击（单一所有者重构后由识别器结算） ----------

    private fun tap(recognizer: PlayerGestureRecognizer, x: Float): List<PlayerGestureAction> {
        recognizer.down(x)
        recognizer.up(x, 1000f - 10f)
        return recognizer.onTapReleased(x, WIDTH, DOWN_TIME + 200L)
    }

    @Test
    fun `release without movement is a tap candidate`() {
        assertEquals(listOf(PlayerGestureAction.Tap), tap(recognizer(), MIDDLE_X))
    }

    @Test
    fun `two taps in the same zone inside the window report a double tap`() {
        val backward = recognizer()
        assertEquals(listOf(PlayerGestureAction.Tap), tap(backward, LEFT_X))
        assertEquals(listOf(PlayerGestureAction.DoubleTapBackward), tap(backward, LEFT_X))

        val forward = recognizer()
        assertEquals(listOf(PlayerGestureAction.Tap), tap(forward, RIGHT_X))
        assertEquals(listOf(PlayerGestureAction.DoubleTapForward), tap(forward, RIGHT_X))

        val center = recognizer()
        assertEquals(listOf(PlayerGestureAction.Tap), tap(center, MIDDLE_X))
        assertEquals(listOf(PlayerGestureAction.DoubleTapCenter), tap(center, MIDDLE_X))
    }

    @Test
    fun `taps in different zones stay single taps`() {
        val recognizer = recognizer()
        assertEquals(listOf(PlayerGestureAction.Tap), tap(recognizer, LEFT_X))
        assertEquals(listOf(PlayerGestureAction.Tap), tap(recognizer, RIGHT_X))
    }

    @Test
    fun `a drag is not a tap candidate`() {
        val recognizer = recognizer()
        recognizer.down(LEFT_X)
        recognizer.move(LEFT_X, 1000f - 400f)
        val upActions = recognizer.up(LEFT_X, 1000f - 400f)
        assertTrue(upActions.isNotEmpty())
        assertTrue(upActions.none { it is PlayerGestureAction.Tap })
    }

    private fun recognizer(config: PlayerGestureConfig = PlayerGestureConfig(densityPxPerDp = DENSITY)): PlayerGestureRecognizer =
        PlayerGestureRecognizer().apply { update(config) }

    /** 完成一次"按下 → 长按到点"的序列，起始 y 为 1000。 */
    private fun longPressed(): PlayerGestureRecognizer = recognizer().apply {
        down(MIDDLE_X)
        longPressTimeout(DOWN_TIME + PlayerGestureSpec.LONG_PRESS_MILLIS)
    }

    /** 在 [startX] 起手并向上滑动一段的首次响应，用于观察目标判定结果。 */
    private fun verticalDrag(
        startX: Float,
        config: PlayerGestureConfig = PlayerGestureConfig(densityPxPerDp = DENSITY),
    ): List<PlayerGestureAction> = recognizer(config).run {
        down(startX)
        moves(startX, 1000f - 200f)
    }

    /** 只做副作用、不关心返回值的按下（返回值统一用 [moves] 取）。 */
    private fun PlayerGestureRecognizer.down(
        x: Float,
        y: Float = 1000f,
        widthPx: Float = WIDTH,
        heightPx: Float = HEIGHT,
        pointerCount: Int = 1,
        nowMillis: Long = DOWN_TIME,
    ) = onDown(x, y, widthPx, heightPx, pointerCount, nowMillis)

    /** 只做副作用、不关心返回值的移动。 */
    private fun PlayerGestureRecognizer.move(
        x: Float,
        y: Float,
        widthPx: Float = WIDTH,
        heightPx: Float = HEIGHT,
        pointerCount: Int = 1,
    ) = onMove(x, y, widthPx, heightPx, pointerCount, DOWN_TIME + 100L)

    /** 记录一次移动并返回它产生的动作。 */
    private fun PlayerGestureRecognizer.moves(
        x: Float,
        y: Float,
        widthPx: Float = WIDTH,
        heightPx: Float = HEIGHT,
        pointerCount: Int = 1,
    ): List<PlayerGestureAction> = onMove(x, y, widthPx, heightPx, pointerCount, DOWN_TIME + 100L)

    private fun PlayerGestureRecognizer.up(
        x: Float,
        y: Float,
        widthPx: Float = WIDTH,
        heightPx: Float = HEIGHT,
    ): List<PlayerGestureAction> = onUp(x, y, widthPx, heightPx, DOWN_TIME + 200L)

    private fun PlayerGestureRecognizer.longPressTimeout(nowMillis: Long): List<PlayerGestureAction> =
        onLongPressTimeout(nowMillis)

    private fun PlayerGestureRecognizer.tap(): List<PlayerGestureAction> = onTap()

    private fun PlayerGestureRecognizer.doubleTap(x: Float, widthPx: Float = WIDTH): List<PlayerGestureAction> =
        onDoubleTap(x, widthPx)

    /** 记录一次设置快照更新并返回它产生的动作。 */
    private fun PlayerGestureRecognizer.reconfigure(config: PlayerGestureConfig): List<PlayerGestureAction> {
        update(config)
        return emptyList()
    }

    private fun PlayerGestureRecognizer.cancel(): List<PlayerGestureAction> = onCancel()
}
