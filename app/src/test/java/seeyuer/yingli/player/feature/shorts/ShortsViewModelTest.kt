package seeyuer.yingli.player.feature.shorts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ShortsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `initialization filters portrait media and opens first candidate`() = runTest {
        val session = FakeSessionClient()
        val vm = ShortsViewModel(session, FakeLibraryRepository(), TestDispatchers(UnconfinedTestDispatcher(testScheduler)))

        vm.initialize()
        advanceUntilIdle()

        assertEquals(listOf("portrait"), vm.state.value.candidates.map { it.title })
        assertEquals(0, vm.state.value.currentIndex)
        assertEquals(2_500L, vm.state.value.progressMillis)
        assertTrue(session.commands.first() is PlaybackSessionCommand.Open)
        assertTrue(session.commands.any { it == PlaybackSessionCommand.Play })
    }

    @Test
    fun `toggle and navigation dispatch commands and preserve candidate position`() = runTest {
        val session = FakeSessionClient()
        val vm = ShortsViewModel(session, FakeLibraryRepository(), TestDispatchers(UnconfinedTestDispatcher(testScheduler)))
        vm.initialize()
        advanceUntilIdle()
        session.snapshot.value = session.snapshot.value.copy(phase = PlaybackPhase.Playing(0))
        advanceUntilIdle()

        vm.togglePlayback()
        vm.next()
        advanceUntilIdle()

        assertTrue(session.commands.contains(PlaybackSessionCommand.Pause))
        assertEquals(0, vm.state.value.currentIndex)
    }

    @Test
    fun `ended playback automatically opens the next candidate once`() = runTest {
        val session = FakeSessionClient()
        val vm = ShortsViewModel(
            session,
            FakeLibraryRepository(includeSecondPortrait = true),
            TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
        )

        vm.initialize()
        advanceUntilIdle()
        val opensBeforeEnd = session.commands.count { it is PlaybackSessionCommand.Open }
        session.snapshot.value = session.snapshot.value.copy(phase = PlaybackPhase.Ended(null))
        advanceUntilIdle()

        assertEquals(1, vm.state.value.currentIndex)
        assertEquals(opensBeforeEnd + 1, session.commands.count { it is PlaybackSessionCommand.Open })
    }

    @Test
    fun `blocking current item removes it and opens the remaining queue`() = runTest {
        val session = FakeSessionClient()
        val preferences = FakeShortsPreferences()
        val vm = ShortsViewModel(
            session,
            FakeLibraryRepository(includeSecondPortrait = true),
            TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
            preferenceRepository = preferences,
        )

        vm.initialize()
        advanceUntilIdle()
        vm.toggleBlocked()
        advanceUntilIdle()

        assertEquals(listOf("second"), vm.state.value.candidates.map { it.title })
        assertEquals(setOf(MediaItemId("portrait")), preferences.blocked.value)
        assertEquals("second", vm.state.value.current?.title)
    }

    @Test
    fun `repeat current reopens the same item when playback ends`() = runTest {
        val session = FakeSessionClient()
        val preferences = FakeShortsPreferences()
        preferences.preferences.value = preferences.preferences.value.copy(autoNext = true, repeatCurrent = true)
        val vm = ShortsViewModel(
            session,
            FakeLibraryRepository(includeSecondPortrait = true),
            TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
            preferenceRepository = preferences,
        )

        vm.initialize()
        advanceUntilIdle()
        val opensBeforeEnd = session.commands.count { it is PlaybackSessionCommand.Open }
        session.snapshot.value = session.snapshot.value.copy(phase = PlaybackPhase.Ended(null))
        advanceUntilIdle()

        assertEquals(0, vm.state.value.currentIndex)
        assertEquals(opensBeforeEnd + 1, session.commands.count { it is PlaybackSessionCommand.Open })
    }

    @Test
    fun `empty library reports an explicit empty state without dispatching open`() = runTest {
        val session = FakeSessionClient()
        val vm = ShortsViewModel(
            session,
            EmptyLibraryRepository(),
            TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
        )

        vm.initialize()
        advanceUntilIdle()

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
