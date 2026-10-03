package seeyuer.yingli.player.domain.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * 展示层位置投影的刷新间隔。
 *
 * 取值沿用被阶段 1 删掉的那条 UI ticker（`PROGRESS_TICK_MILLIS = 250`）：进度条与时间读数
 * 在这个粒度上看起来是连续的，而 4 次/秒的读取不构成可测量的开销。
 */
const val DISPLAY_POSITION_TICK_MILLIS: Long = 250

/**
 * **展示层**的播放位置流：播放中按 [DISPLAY_POSITION_TICK_MILLIS] 用
 * [PlaybackSessionClient.currentPositionMillis]（实时值）持续刷新，其它时候静止。
 *
 * ## 为什么必须有它（P1 回归的根因）
 *
 * 快照的 `timeline.positionMillis` 只在**引擎状态跳变**时刷新，稳定播放期间它停在上一次跳变的
 * 位置上。因此任何"把快照位置直接当位置显示"的地方都会冻结 —— 真机现场：进度行停在
 * `00:00 / 01:35`，`displayedPositionMillis` 三次 dump 都是 `61905`，而 `dumpsys media_session`
 * 的引擎位置已经从 61904 走到 82839。
 *
 * 阶段 1/2 修"设点用了陈旧快照位置"（`docs/19` 缺陷 1）时把 **11 处决策点**改成了实时位置，
 * 但展示/投影层没改；同一次收敛又删掉了原来的 UI ticker（`projectedPlayingState()`），
 * 于是展示用的位置推进也一起没了。这里把这条推进**恢复成一条只服务展示的投影**。
 *
 * ## 为什么它不违反"决策必须实时"
 *
 * 本函数产出的值**只允许**用于渲染（进度条、时间读数、帧号及其派生）。它以**实时值**为源，
 * 不做任何插值或预测，因此即使被误用到决策上也仍然是实时值 —— "决策必须实时"这条规则由
 * 决策点继续直接调用 [PlaybackSessionClient.currentPositionMillis] 来保证（契约注释见
 * `PlaybackController.currentPositionMillis` 与 `PlaybackSessionClient.currentPositionMillis`），
 * 不会因为这条展示通道而失守。
 *
 * ## 什么时候静止（不空转）
 *
 * - **暂停**（`Paused`）：位置不再变化，继续按 tick 读只是空转，因此停在这里 —— 停住的是
 *   **暂停那一刻读到的实时值**，不是某次状态跳变时的旧位置；
 * - **无媒体 / Idle / Preparing / Ready / Buffering / Ended / Failed**：没有正在推进的位置可显示，
 *   交出快照里该相位的权威位置（引擎在状态跳变时带上的真实位置）后挂起等待；
 * - 播放重新开始时**立即**再读一次，不需要等满一个 tick。
 */
fun displayPositionMillis(sessionClient: PlaybackSessionClient): Flow<Long> =
    flow {
        while (true) {
            if (sessionClient.snapshot.value.phase !is PlaybackPhase.Playing) {
                // 位置没有在推进：交出这一相位的权威位置，然后挂起等"开始播放"，不按 tick 空转。
                emit(sessionClient.snapshot.value.timeline.positionMillis)
                sessionClient.snapshot.first { it.phase is PlaybackPhase.Playing }
                continue
            }
            // 进入播放后的第一拍立即读，暂停后又播放时不必等满一个 tick。
            emit(sessionClient.currentPositionMillis())
            while (sessionClient.snapshot.value.phase is PlaybackPhase.Playing) {
                delay(DISPLAY_POSITION_TICK_MILLIS)
                if (sessionClient.snapshot.value.phase !is PlaybackPhase.Playing) break
                emit(sessionClient.currentPositionMillis())
            }
        }
    }.distinctUntilChanged()
