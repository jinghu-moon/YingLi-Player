package seeyuer.yingli.player.feature.shorts

import androidx.lifecycle.viewModelScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Rule
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.library.*
import seeyuer.yingli.player.domain.playback.*
import seeyuer.yingli.player.domain.shorts.ShortsFitMode
import seeyuer.yingli.player.domain.shorts.ShortsPreferenceRepository
import seeyuer.yingli.player.testing.MainDispatcherRule

/**
 * 短视频页 ViewModel 的行为契约。
 *
 * ## 测试契约：这个页面里有两台**不终止的 ticker**
 *
 * 1. 播放中收集 `displayPositionMillis`（按 `DISPLAY_POSITION_TICK_MILLIS` 无限 tick，
 *    理由见该函数的 KDoc）；
 * 2. 有截图预览时跑 `scheduleScreenshotExpiry()` 的 `while (true) { delay(100) ... }`
 *    （`ShortsViewModel.kt:372`）。
 *
 * 因此本类**不能**用 `advanceUntilIdle()` —— 它要求调度器进入静止状态，而这两台 ticker
 * 让"静止"永不成立。真机现场是 `testDebugUnitTest` 整个任务**挂死 40 分钟且无任何产物落盘**，
 * `jstack` 的唯一忙线程停在 `TestCoroutineScheduler.sendDispatchEvent`
 * （协程编号已经到 `#3629`）。
 *
 * 触发条件极容易达成：替身 `FakeSessionClient` 收到 `Play` 就把相位置为 `Playing`，
 * 而 `initialize()` 必然派发 `Play`，所以**每一次 `initialize()` 之后**调度器里都已经
 * 躺着一只永不静止的 ticker。
 *
 * ## 三条硬规则（缺一不可）
 *
 * 1. **调度器用 `StandardTestDispatcher`**（与 `PlayerDisplayPositionTest` 一致）。
 *    `UnconfinedTestDispatcher` 会把 ticker 的虚拟 `delay` **就地**跑掉并立刻重排，
 *    直接把工作线程烧成死循环 —— 实测只把 `advanceUntilIdle()` 换成 `runCurrent()`
 *    并不够，仍会挂死。
 * 2. **推进用 `runCurrent()`**：只跑"当前虚拟时刻"已就绪的任务，ticker 的下一拍还挂在
 *    未来时间上，不会被执行。需要跨过某个具体延时时用 `advanceTimeBy(...)` + `runCurrent()`；
 *    需要等某个状态出现时用有界轮询。**不要**退回 `advanceUntilIdle()`，
 *    也不要退回 `UnconfinedTestDispatcher`。
 * 3. **收尾必须在测试体之内取消 `viewModelScope`**。`runTest` 的收尾抽干
 *    （`TestBuilders.kt:370` → `advanceUntilIdleOr`）发生在**测试体返回之后、JUnit
 *    `@After` 之前**，所以 `@After` 里取消已经晚了：抽干那一刻 ticker 早已把下一拍排进
 *    调度器，抽干永不返回。本类的 [shortsTest] 用 `try/finally` 把取消放进测试体内，
 *    断言失败时也不会漏。
 *
 * 替身的状态变更都是同步写 `MutableStateFlow` 的（`FakeSessionClient.dispatch`、
 * `FakeShortsPreferences`），所以"改状态 → `runCurrent()` → 断言"这个节奏对
 * Standard 调度器同样成立。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ShortsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val createdViewModels = mutableListOf<ShortsViewModel>()

    /**
     * 统一用例外壳：`body` 抛异常也保证先取消掉本类造过的全部 ViewModel。
     *
     * 取消必须在这里做、不能放 `@After` —— 理由见类 KDoc 第 3 条。
     */
    private fun shortsTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            createdViewModels.forEach { it.viewModelScope.cancel() }
        }
    }

    /**
     * 注入的 dispatcher 与 `Dispatchers.Main` 是**同一个** `TestDispatcher` 实例，
     * 因此 ticker、`initialize()`、测试推进三者共用同一个虚拟时钟
     * （`runTest` 会采纳 `Dispatchers.Main` 的 scheduler）。
     */
    private fun shortsViewModel(
        session: PlaybackSessionClient,
        library: LibraryPagingRepository,
        preferences: ShortsPreferenceRepository? = null,
    ): ShortsViewModel = ShortsViewModel(
        sessionClient = session,
        libraryRepository = library,
        dispatchers = TestDispatchers(mainDispatcherRule.dispatcher),
        preferenceRepository = preferences,
    ).also(createdViewModels::add)

    @Test
    fun `initialization filters portrait media and opens first candidate`() = shortsTest {
        val session = FakeSessionClient()
        val vm = shortsViewModel(session, FakeLibraryRepository())

        vm.initialize()
        runCurrent()

        assertEquals(listOf("portrait"), vm.state.value.candidates.map { it.title })
        assertEquals(0, vm.state.value.currentIndex)
        assertEquals(2_500L, vm.state.value.progressMillis)
        assertTrue(session.commands.first() is PlaybackSessionCommand.Open)
        assertTrue(session.commands.any { it == PlaybackSessionCommand.Play })
    }

    @Test
    fun `toggle and navigation dispatch commands and preserve candidate position`() = shortsTest {
        val session = FakeSessionClient()
        val vm = shortsViewModel(session, FakeLibraryRepository())
        vm.initialize()
        runCurrent()
        session.snapshot.value = session.snapshot.value.copy(phase = PlaybackPhase.Playing(0))
        runCurrent()

        vm.togglePlayback()
        vm.next()
        runCurrent()

        assertTrue(session.commands.contains(PlaybackSessionCommand.Pause))
        assertEquals(0, vm.state.value.currentIndex)
    }

    @Test
    fun `ended playback automatically opens the next candidate once`() = shortsTest {
        val session = FakeSessionClient()
        val vm = shortsViewModel(session, FakeLibraryRepository(includeSecondPortrait = true))

        vm.initialize()
        runCurrent()
        val opensBeforeEnd = session.commands.count { it is PlaybackSessionCommand.Open }
        session.snapshot.value = session.snapshot.value.copy(phase = PlaybackPhase.Ended(null))
        runCurrent()

        assertEquals(1, vm.state.value.currentIndex)
        assertEquals(opensBeforeEnd + 1, session.commands.count { it is PlaybackSessionCommand.Open })
    }

    @Test
    fun `blocking current item removes it and opens the remaining queue`() = shortsTest {
        val session = FakeSessionClient()
        val preferences = FakeShortsPreferences()
        val vm = shortsViewModel(session, FakeLibraryRepository(includeSecondPortrait = true), preferences)

        vm.initialize()
        runCurrent()
        vm.toggleBlocked()
        runCurrent()

        assertEquals(listOf("second"), vm.state.value.candidates.map { it.title })
        assertEquals(setOf(MediaItemId("portrait")), preferences.blocked.value)
        assertEquals("second", vm.state.value.current?.title)
    }

    @Test
    fun `repeat current reopens the same item when playback ends`() = shortsTest {
        val session = FakeSessionClient()
        val preferences = FakeShortsPreferences()
        preferences.preferences.value = preferences.preferences.value.copy(autoNext = true, repeatCurrent = true)
        val vm = shortsViewModel(session, FakeLibraryRepository(includeSecondPortrait = true), preferences)

        vm.initialize()
        runCurrent()
        val opensBeforeEnd = session.commands.count { it is PlaybackSessionCommand.Open }
        session.snapshot.value = session.snapshot.value.copy(phase = PlaybackPhase.Ended(null))
        runCurrent()

        assertEquals(0, vm.state.value.currentIndex)
        assertEquals(opensBeforeEnd + 1, session.commands.count { it is PlaybackSessionCommand.Open })
    }

    @Test
    fun `empty library reports an explicit empty state without dispatching open`() = shortsTest {
        val session = FakeSessionClient()
        val vm = shortsViewModel(session, EmptyLibraryRepository())

        vm.initialize()
        runCurrent()

        assertTrue(vm.state.value.candidates.isEmpty())
        assertEquals(0, session.commands.count { it is PlaybackSessionCommand.Open })
        assertEquals(-1, vm.state.value.currentIndex)
    }

    private open class FakeLibraryRepository(private val includeSecondPortrait: Boolean = false) : LibraryPagingRepository {
        override fun observe(query: LibraryQuery): Flow<LibraryResult<LibraryPage>> = emptyFlow()
        override suspend fun query(query: LibraryQuery): LibraryResult<LibraryPage> = LibraryResult.Success(
            LibraryPage(
                items = buildList {
                    add(shortsMedia("portrait", 720, 1280, 2_500))
                    if (includeSecondPortrait) add(shortsMedia("second", 720, 1280, 0))
                    add(shortsMedia("landscape", 1920, 1080, 0))
                },
                nextCursor = null,
                totalCount = 2,
            ),
        )
        override suspend fun page(query: LibraryQuery, direction: LibraryPageDirection): LibraryPage = error("unused")
        override fun observeCount(query: LibraryQuery): Flow<Int> = flowOf(2)
        override fun observeFolderTreeVideoCount(path: String): Flow<Int> = flowOf(0)
        override fun observeInvalidations(): Flow<Unit> = emptyFlow()
    }

    private class EmptyLibraryRepository : FakeLibraryRepository() {
        override suspend fun query(query: LibraryQuery): LibraryResult<LibraryPage> = LibraryResult.Success(
            LibraryPage(emptyList(), nextCursor = null, totalCount = 0),
        )
    }

    private class FakeShortsPreferences : ShortsPreferenceRepository {
        val blocked = MutableStateFlow<Set<MediaItemId>>(emptySet())
        override val preferences = MutableStateFlow(seeyuer.yingli.player.domain.shorts.ShortsPreferences())
        override val blockedMediaIds: Flow<Set<MediaItemId>> = blocked
        override suspend fun setAutoNext(enabled: Boolean) { preferences.value = preferences.value.copy(autoNext = enabled) }
        override suspend fun setRepeatCurrent(enabled: Boolean) { preferences.value = preferences.value.copy(repeatCurrent = enabled) }
        override suspend fun setFitMode(mode: ShortsFitMode) { preferences.value = preferences.value.copy(fitMode = mode) }
        override suspend fun setLockedSpeed(speed: Float) { preferences.value = preferences.value.copy(lockedSpeed = speed) }
        override suspend fun setHintShown() { preferences.value = preferences.value.copy(hintShown = true) }
        override suspend fun setBlocked(mediaId: MediaItemId, blocked: Boolean) {
            this.blocked.value = if (blocked) this.blocked.value + mediaId else this.blocked.value - mediaId
        }
    }

    private class FakeSessionClient : PlaybackSessionClient {
        override val snapshot = MutableStateFlow(
            PlaybackSessionSnapshot(PlaybackSessionId("test"), null, null, PlaybackPhase.Idle),
        )
        override val events: Flow<PlaybackSessionEvent> = emptyFlow()
        val commands = mutableListOf<PlaybackSessionCommand>()

        /** Shorts 的替身不推进位置：实时位置与它自己发布的 timeline 保持一致（就是 0）。 */
        override fun currentPositionMillis(): Long = snapshot.value.timeline.positionMillis

        override fun dispatch(command: PlaybackSessionCommand): PlaybackCommandHandle {
            commands += command
            when (command) {
                is PlaybackSessionCommand.Open -> snapshot.value = snapshot.value.copy(
                    mediaId = command.request.mediaId,
                    phase = PlaybackPhase.Ready(true),
                    timeline = PlaybackTimeline(command.request.startPositionMillis, 10_000),
                )
                PlaybackSessionCommand.Play -> snapshot.value = snapshot.value.copy(phase = PlaybackPhase.Playing(0))
                PlaybackSessionCommand.Pause -> snapshot.value = snapshot.value.copy(phase = PlaybackPhase.Paused(PauseReason.USER))
                else -> Unit
            }
            return PlaybackCommandHandle(PlaybackCommandId(commands.size.toLong()))
        }
    }

    private class TestDispatchers(private val dispatcher: CoroutineDispatcher) : AppDispatchers {
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
    }
}

private fun shortsMedia(title: String, width: Int, height: Int, position: Long) = LibraryMedia(
    id = MediaItemId(title),
    locationId = MediaLocationId("location-$title"),
    uri = MediaUri("file:///storage/$title.mp4"),
    title = title,
    fileName = "$title.mp4",
    folderAlias = "videos",
    extension = "mp4",
    durationMillis = 10_000,
    width = width,
    height = height,
    modifiedEpochMillis = 0,
    playbackPositionMillis = position,
    completed = false,
)
