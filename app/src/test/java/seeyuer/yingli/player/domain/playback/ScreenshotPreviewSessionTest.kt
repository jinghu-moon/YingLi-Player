package seeyuer.yingli.player.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotPreviewSessionTest {
    private fun session() = ScreenshotPreviewSession.start(
        displayName = "frame.jpg",
        uri = "content://frame",
        location = "Pictures/YingLi/frame.jpg",
    )

    /** 把会话推 [totalMillis] 毫秒（每步 [stepMillis]），返回收场次数与最终会话。 */
    private fun run(
        start: ScreenshotPreviewSession,
        totalMillis: Long,
        stepMillis: Long = 100,
    ): Pair<Int, ScreenshotPreviewSession> {
        var current = start
        var expiredCount = 0
        var elapsed = 0L
        while (elapsed < totalMillis) {
            val tick = current.tick(stepMillis)
            current = tick.session
            if (tick.expired) expiredCount++
            elapsed += stepMillis
        }
        return expiredCount to current
    }

    @Test
    fun tickCountsDownAndExpiresExactlyOnce() {
        val (expired, final) = run(session(), totalMillis = 5_000)

        assertEquals(0L, final.remainingMillis)
        assertFalse(final.isCounting)
        assertEquals(1, expired)
    }

    @Test
    fun progressIsLinearInRemainingTime() {
        assertEquals(1f, session().progress, 0.0001f)
        assertEquals(0.5f, session().tick(1_500).session.progress, 0.0001f)
        assertEquals(0f, session().tick(3_000).session.progress, 0.0001f)
    }

    @Test
    fun expandedSessionFreezesTheReadoutAndKeepsTheTimeSpentLooking() {
        val expanded = session().tick(1_000).session.expand()
        val frozenReadout = expanded.displayRemainingMillis

        // 定格：再喂 500ms，读条（会话值）一动不动，但"看大图的时长"在累计。
        val peeked = expanded.tick(500).session
        assertEquals(2_000L, peeked.remainingMillis)
        assertEquals(500L, peeked.expandedElapsedMillis)
        assertFalse(peeked.isCounting)
        // 收起后按"余量 - 看图的时长"接着走，而不是把看图的 0.5 秒退还给用户。
        val resumed = peeked.collapse()
        assertEquals(1_500L, resumed?.remainingMillis)
        assertEquals(1_500L, resumed?.displayRemainingMillis)
        assertTrue(resumed?.isCounting == true)
        // 收起那一刻的读数必须小于展开那一刻的读数，且与收起后的会话值一致：
        // 否则 UI 上会出现"收起后读条反而变长"这种自相矛盾的跳变。
        assertTrue("frozen=$frozenReadout resumed=${resumed?.displayRemainingMillis}", frozenReadout > (resumed?.displayRemainingMillis ?: 0L))
    }

    @Test
    fun expandedReadoutTracksTheSessionExactly() {
        // 展开期间派发出去的读数必须与会话的"真实剩余"完全一致，
        // 否则收起时读条会从错误的数往下走（曾出现"收起后余量比展开前还大"）。
        var current = session().tick(1_000).session.expand()
        val published = mutableListOf<Long>()
        repeat(10) {
            val tick = current.tick(50)
            current = tick.session
            tick.publishRemainingMillis?.let { published += it }
        }

        assertEquals(listOf(1950L, 1900L, 1850L, 1800L, 1750L, 1700L, 1650L, 1600L, 1550L, 1500L), published)
        assertEquals(current.displayRemainingMillis, published.last())
        // 会话值（读条定格的那个数）始终不动，只有派发出去的读数在走。
        assertEquals(2_000L, current.remainingMillis)
    }

    /**
     * 真实时序：读条先走 [beforeExpandMillis]，再在**卡片还在的时候**展开（点一下卡片放大），
     * 然后按 [stepMillis] 喂 [totalMillis] 毫秒。
     *
     * 展开期间读条**定格**（[ScreenshotPreviewSession.remainingMillis] 不动），但走掉的时间记进
     * `expandedElapsedMillis`；所以这个 helper 同时覆盖"定格"与"时间仍在流逝"两件事。
     */
    private fun expandThenRun(
        beforeExpandMillis: Long,
        stepMillis: Long = 100,
        totalMillis: Long = 5_000,
    ): ScreenshotPreviewTick {
        var current = session().tick(beforeExpandMillis).session.expand()
        var elapsed = 0L
        var last = ScreenshotPreviewTick(current)
        while (elapsed < totalMillis) {
            last = current.tick(stepMillis)
            current = last.session
            elapsed += stepMillis
            // 到点之后 UI 会让循环自然结束（会话进入终态），helper 也照此收工，
            // 否则最后一次 tick 会把"已经到点"的会话又原样返回一次，覆盖掉判定结果。
            if (last.expired || last.notifyLocationOnly) break
        }
        return last
    }

    @Test
    fun expandedPreviewFreezesTheReadoutButStillCountsTheTimeSpentLooking() {
        // 展开前走掉 1 秒，只在大图里看 0.5 秒：读条定格在 2 秒，看图的时长单独记着。
        val peek = expandThenRun(beforeExpandMillis = 1_000, totalMillis = 500)

        assertEquals(2_000L, peek.session.remainingMillis)
        assertEquals(500L, peek.session.expandedElapsedMillis)
        assertTrue(peek.session.expanded)
        assertTrue(peek.session.isCountingWhileExpanded)
        assertFalse(peek.expired)
        assertFalse(peek.notifyLocationOnly)
    }

    @Test
    fun readoutFinishingWhileExpandedReportsTheLocationExactlyOnce() {
        // 展开后一直看图：时间照样流逝，读条在展开期间走完。
        val tick = expandThenRun(beforeExpandMillis = 1_000)
        println("DIAG tick=${tick.session} expired=${tick.expired} notify=${tick.notifyLocationOnly}")

        // 大图预览还开着，但读条已经到点：卡片不消失，只提示一次保存路径。
        assertFalse("expired should be false, session=${tick.session}", tick.expired)
        assertTrue("notifyLocationOnly should be true, session=${tick.session}", tick.notifyLocationOnly)
        assertTrue("expanded should be true, session=${tick.session}", tick.session.expanded)
        assertEquals("remaining should be 0", 0L, tick.session.remainingMillis)
        assertFalse("should have stopped counting", tick.session.isCountingWhileExpanded)
        assertFalse(
            "must notify only once, session=${tick.session.tick(5_000).session}",
            tick.session.tick(5_000).notifyLocationOnly,
        )
        // 提示过的这张卡收起时按正常流程收场（返回 null 表示"卡片消失"）。
        assertNull(tick.session.collapse())
    }

    @Test
    fun collapsingAfterAPeekResumesWithTheTimeSpentInThePreviewDeducted() {
        // 展开前走掉 1 秒，在大图里看了 0.5 秒：收起后读条从 1.5 秒接着走，
        // 而不是把看图的 0.5 秒白白退还给用户。
        val resumed = requireNotNull(expandThenRun(beforeExpandMillis = 1_000, totalMillis = 500).session.collapse())

        assertEquals(1_500L, resumed.remainingMillis)
        assertEquals(0L, resumed.expandedElapsedMillis)
        assertFalse(resumed.expanded)
        assertTrue(resumed.isCounting)
        // 再走完这 1.5 秒，会话进入"已到点"：收起来就该收场。
        assertNull(resumed.tick(1_500).session.collapse())
    }

    @Test
    fun deletedPreviewNeverNotifiesTheSavedLocation() {
        val deleted = session().tick(1_000).session.markDeleted()

        val tick = deleted.tick(5_000)
        assertFalse(tick.expired)
        assertFalse(tick.notifyLocationOnly)
        assertNull(deleted.collapse())
    }

    @Test
    fun everyTickPublishesTheExactReadoutSoTheUiNeverLagsTheSession() {
        var current = session()
        val published = mutableListOf<Long>()
        repeat(30) {
            val tick = current.tick(10)
            current = tick.session
            tick.publishRemainingMillis?.let { published += it }
        }

        // 每一步都必须把真实余量推给 UI：读条是"匀速缩短"的，UI 落后会话一格
        // 就会出现"收起大图后余量比展开前还大"这种自相矛盾的读数。
        assertEquals((1..30).map { 3_000L - it * 10L }, published)
        assertEquals(current.remainingMillis, published.last())
    }

    @Test
    fun defaultTickKeepsTheCountdownInRealTime() {
        // 计时器步长与"3 秒读条"必须整除：否则读条会多走/少走一格。
        assertEquals(0L, ScreenshotUiState.PREVIEW_DURATION_MILLIS % SCREENSHOT_PREVIEW_TICK_MILLIS)

        val (expired, _) = run(
            session(),
            totalMillis = ScreenshotUiState.PREVIEW_DURATION_MILLIS,
            stepMillis = SCREENSHOT_PREVIEW_TICK_MILLIS,
        )
        assertEquals(1, expired)
    }

    @Test
    fun theFinalTickAlwaysPublishesZero() {
        val tick = session().tick(3_000)

        // 到点那一次必须把 0 推给 UI，否则读条会停在最后一格上不动。
        assertEquals(0L, tick.publishRemainingMillis)
        assertTrue(tick.expired)
    }

    @Test
    fun sessionCanBeRebuiltFromAPreviewWithoutRefillingTheCountdown() {
        val preview = ScreenshotUiState.Preview("frame.jpg", "content://frame", remainingMillis = 800)

        val rebuilt = ScreenshotPreviewSession.fromPreview(preview)

        // 删除失败后重建会话：接着原来的余量走，不重新给满 3 秒。
        assertEquals(800L, rebuilt.remainingMillis)
        assertFalse(rebuilt.expanded)
        assertFalse(rebuilt.deleted)
    }
}
