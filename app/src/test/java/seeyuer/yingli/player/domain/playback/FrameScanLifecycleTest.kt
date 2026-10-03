package seeyuer.yingli.player.domain.playback

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取消/完成/释放归属的状态机用例。
 *
 * 这里测的就是"取消之后不许发布结果、重复取消幂等、取消与完成竞态只有一个赢家"这几条：
 * 真机上 `MediaExtractor` 的释放只能发生一次，所以"谁负责释放"必须是被证明过的唯一判定。
 */
class FrameScanLifecycleTest {
    @Test
    fun `cancel wins once and runs the interrupt exactly once`() {
        val lifecycle = FrameScanLifecycle()
        val interrupts = AtomicInteger(0)
        lifecycle.attachInterrupt { interrupts.incrementAndGet() }
        assertTrue(lifecycle.canPublishResult())
        assertEquals(FrameScanLifecycle.Phase.SCANNING, lifecycle.phase)

        assertTrue(lifecycle.cancel())
        assertTrue(lifecycle.isCancelled)
        assertEquals(FrameScanLifecycle.Phase.CANCELLED, lifecycle.phase)
        assertEquals(1, interrupts.get())
        // 取消之后结果一律作废：宿主协程即使刚拿到返回值也不许写回。
        assertFalse(lifecycle.canPublishResult())
    }

    @Test
    fun `repeated cancel is idempotent and never releases twice`() {
        val lifecycle = FrameScanLifecycle()
        val interrupts = AtomicInteger(0)
        lifecycle.attachInterrupt { interrupts.incrementAndGet() }

        assertTrue(lifecycle.cancel())
        assertFalse(lifecycle.cancel())
        assertFalse(lifecycle.cancel())

        assertEquals(1, interrupts.get())
    }

    @Test
    fun `finish wins and a later cancel does not release again`() {
        val lifecycle = FrameScanLifecycle()
        val interrupts = AtomicInteger(0)
        lifecycle.attachInterrupt { interrupts.incrementAndGet() }

        assertTrue(lifecycle.finish())
        assertEquals(FrameScanLifecycle.Phase.FINISHED, lifecycle.phase)
        // 资源已经由扫描线程释放：取消方什么都不要做，否则就是二次释放。
        assertFalse(lifecycle.cancel())
        assertEquals(0, interrupts.get())
    }

    @Test
    fun `cancel after finish still discards the result`() {
        // 扫描已经跑完，宿主才来取消（退出截图模式正好赶在这一刻）：没有资源要释放，
        // 但这份结果同样作废——否则界面上会出现一份属于上一份媒体的帧数。
        val lifecycle = FrameScanLifecycle()
        assertTrue(lifecycle.finish())

        assertFalse(lifecycle.cancel())
        assertFalse(lifecycle.canPublishResult())
    }

    @Test
    fun `attach after cancel releases immediately`() {
        // 取消先到（任务还排在 IO 队列里），资源随后才被打开：这时没有任何并发访问者，
        // 登记方自己释放是安全且唯一的释放机会。
        val lifecycle = FrameScanLifecycle()
        assertTrue(lifecycle.cancel())

        var releasedOnAttachingThread = false
        lifecycle.attachInterrupt { releasedOnAttachingThread = true }

        assertTrue(releasedOnAttachingThread)
    }

    @Test
    fun `attach after finish never releases`() {
        // finish 已经赢过：资源由扫描线程释放，登记必须被忽略，否则就是二次释放。
        val lifecycle = FrameScanLifecycle()
        assertTrue(lifecycle.finish())

        var released = false
        lifecycle.attachInterrupt { released = true }

        assertFalse(released)
    }

    @Test
    fun `cancel and finish racing always produce exactly one winner`() {
        repeat(RACE_ROUNDS) {
            val lifecycle = FrameScanLifecycle()
            val interrupts = AtomicInteger(0)
            lifecycle.attachInterrupt { interrupts.incrementAndGet() }
            val barrier = CyclicBarrier(2)
            var cancelWon = false
            var finishWon = false

            val canceller = Thread {
                barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                cancelWon = lifecycle.cancel()
            }
            val finisher = Thread {
                barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                finishWon = lifecycle.finish()
            }
            canceller.start()
            finisher.start()
            canceller.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            finisher.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))

            assertTrue("取消与完成必须恰好有一个赢家：cancel=$cancelWon finish=$finishWon", cancelWon != finishWon)
            // 赢家是谁，释放就由谁做，且只做一次。
            assertEquals(if (cancelWon) 1 else 0, interrupts.get())
        }
    }

    @Test
    fun `release ownership stays with the scan thread when it finishes first`() {
        // 扫描线程的收尾顺序：先 finish() 判定归属，再由赢家释放。
        // 这里断言"判定结果可以直接当成释放义务"这一契约。
        val lifecycle = FrameScanLifecycle()
        var released = false
        lifecycle.attachInterrupt { released = true }

        val scanThreadOwnsRelease = lifecycle.finish()
        if (scanThreadOwnsRelease) released = true

        assertTrue(scanThreadOwnsRelease)
        assertTrue(released)
    }

    @Test
    fun `never cancelled scan is a usable read-only cancellation`() {
        assertFalse(NeverCancelledFrameScan.isCancelled)
    }

    private companion object {
        const val RACE_ROUNDS = 200
        const val WAIT_SECONDS = 5L
    }
}
