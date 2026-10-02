package seeyuer.yingli.player.feature.player

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.assertFalse
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.core.model.media.MediaUri
import seeyuer.yingli.player.domain.library.LibraryMedia
import seeyuer.yingli.player.domain.library.LibraryBrowseMode
import seeyuer.yingli.player.domain.library.LibraryPage
import seeyuer.yingli.player.domain.library.LibraryPageDirection
import seeyuer.yingli.player.domain.library.LibraryPagingRepository
import seeyuer.yingli.player.domain.library.LibraryQuery
import seeyuer.yingli.player.domain.library.LibraryResult
import seeyuer.yingli.player.domain.playback.PlaybackCommandResult
import seeyuer.yingli.player.domain.playback.AdvancedPlaybackController
import seeyuer.yingli.player.domain.playback.PlaybackConnectionState
import seeyuer.yingli.player.domain.playback.PlaybackController
import seeyuer.yingli.player.domain.playback.PlaybackRequest
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackSourceRepository
import seeyuer.yingli.player.domain.playback.RequestedOrientation
import seeyuer.yingli.player.domain.playback.PlaybackState
import seeyuer.yingli.player.domain.playback.ResolvedPlaybackSource
import seeyuer.yingli.player.domain.playback.PlaybackSpeed
import seeyuer.yingli.player.domain.playback.PlaybackQueue
import seeyuer.yingli.player.domain.playback.PlaybackQueueRepository
import seeyuer.yingli.player.domain.playback.PlaybackQueueSource
import seeyuer.yingli.player.domain.playback.PlayerPanel
import seeyuer.yingli.player.domain.playback.ScreenshotGateway
import seeyuer.yingli.player.domain.playback.ScreenshotFileGateway
import seeyuer.yingli.player.domain.playback.ScreenshotResult
import seeyuer.yingli.player.domain.playback.ScreenshotUiState
import seeyuer.yingli.player.domain.playback.AbPoint
import seeyuer.yingli.player.domain.playback.TrackChoice
import seeyuer.yingli.player.domain.playback.TrackPreference
import seeyuer.yingli.player.domain.playback.TrackPreferenceRepository
import seeyuer.yingli.player.domain.playback.TrackPreferenceSet
import seeyuer.yingli.player.domain.playback.VideoScaleMode
import seeyuer.yingli.player.domain.playback.VideoRotation
import seeyuer.yingli.player.R
import seeyuer.yingli.player.domain.playback.ScreenshotDeleteFailure
import seeyuer.yingli.player.app.playback.PlaybackSessionClientBridge
import seeyuer.yingli.player.testing.MainDispatcherRule
import seeyuer.yingli.player.feature.player.PlayerUiEvent
import seeyuer.yingli.player.domain.playback.PlaybackOrder

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `playlist media is restored in queue order`() {
        val first = libraryMedia("media_1", "first.mp4")
        val second = libraryMedia("media_2", "second.mp4")

        val ordered = orderPlaylistItems(
            queueIds = listOf(second.id, first.id),
            items = listOf(first, second),
        )

        assertEquals(listOf("second.mp4", "first.mp4"), ordered.map { it.fileName })
    }

    @Test
    fun `latest queue source wins when an older queue query finishes later`() = runTest {
        val controller = FakePlaybackController()
        val sourceRepository = FakePlaybackSourceRepository(
            ResolvedPlaybackSource(
                PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME),
                "content://media/1",
                "影片",
            ),
        )
        val queueRepository = FakePlaybackQueueRepository()
        val folderQueryStarted = CompletableDeferred<Unit>()
        val folderQueryGate = CompletableDeferred<Unit>()
        val folderMedia = libraryMedia("folder_media", "folder.mp4")
        val allMedia = libraryMedia("all_media", "all.mp4")
        val library = QueueSourceLibraryRepository(
            folderQueryStarted = folderQueryStarted,
            folderQueryGate = folderQueryGate,
            folderMedia = folderMedia,
            allMedia = allMedia,
        )
        val dispatchers = TestDispatchers(StandardTestDispatcher(testScheduler))
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers, queueRepository),
            dispatchers,
            playbackQueueRepository = queueRepository,
            libraryRepository = library,
        )

        viewModel.open("media_1", PlaybackSourceContext.HOME, PlaybackQueueSource.folderTree("Movies"))
        runCurrent()
        folderQueryStarted.await()

        viewModel.open("media_1", PlaybackSourceContext.HOME, PlaybackQueueSource.allVideos())
        advanceUntilIdle()

        assertEquals(listOf("all_media", "media_1"), queueRepository.queue.first()?.mediaIds?.map { it.value })
        folderQueryGate.complete(Unit)
    }

    @Test
    fun `opening a media route resolves a pure request and prepares fake controller`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(
            MediaItemId("media_1"),
            MediaLocationId("location_1"),
            7_000,
            PlaybackSourceContext.HOME,
        )
        val sourceRepository = FakePlaybackSourceRepository(
            ResolvedPlaybackSource(request, "content://media/video/1", "测试影片"),
        )
        val viewModel = PlayerViewModel(PlaybackSessionClientBridge(controller, sourceRepository, TestDispatchers(StandardTestDispatcher(testScheduler))), TestDispatchers(StandardTestDispatcher(testScheduler)))

        viewModel.open("media_1", PlaybackSourceContext.HOME)
        advanceUntilIdle()

        assertEquals(request, controller.preparedRequest)
        assertTrue(controller.state.value is PlaybackState.Preparing)
    }

    private fun libraryMedia(id: String, fileName: String) = LibraryMedia(
        id = MediaItemId(id),
        locationId = MediaLocationId("location_$id"),
        uri = MediaUri("content://media/$id"),
        title = fileName,
        fileName = fileName,
        folderAlias = "Movies",
        extension = "mp4",
        durationMillis = 1_000L,
        width = 1920,
        height = 1080,
        modifiedEpochMillis = 1L,
        playbackPositionMillis = 0L,
        completed = false,
        sizeBytes = 100L,
    )

    @Test
    fun `opening a vault route delegates to secure playback controller`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers),
            dispatchers,
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }

        viewModel.openVault("vault_item", "保险库视频")
        advanceUntilIdle()

        assertEquals("vault_item", controller.vaultPrepared?.value)
        assertEquals("vault-vault_item", viewModel.state.value.playback.request?.mediaId?.value)
        assertEquals("保险库视频", viewModel.state.value.title)
        assertFalse(viewModel.state.value.sourceUnavailable)
        stateCollector.cancel()
    }

    @Test
    fun `changing playback order persists order and resets shuffle history`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, seeyuer.yingli.player.domain.playback.PlaybackTimeline(0, 10_000)))
        val queueRepository = FakePlaybackQueueRepository()
        queueRepository.setQueue(
            PlaybackQueue(
                listOf(MediaItemId("media_1"), MediaItemId("media_2")),
                currentIndex = 0,
                continuousPlayback = true,
                order = seeyuer.yingli.player.domain.playback.PlaybackOrder.SHUFFLE,
                shuffleHistory = listOf(0),
            ),
        )
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers, queueRepository),
            dispatchers,
            playbackQueueRepository = queueRepository,
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }

        advanceUntilIdle()
        viewModel.setPlaybackOrder(seeyuer.yingli.player.domain.playback.PlaybackOrder.SINGLE_REPEAT)
        advanceUntilIdle()

        assertEquals(seeyuer.yingli.player.domain.playback.PlaybackOrder.SINGLE_REPEAT, queueRepository.queue.first()?.order)
        assertTrue(queueRepository.queue.first()?.shuffleHistory.isNullOrEmpty())
        stateCollector.cancel()
    }

    @Test
    fun `changing speed preserves other per media preferences`() = runTest {
        val controller = PreferencePlaybackController()
        val preferences = InMemoryTrackPreferences(
            TrackPreferenceSet(perMedia = mapOf(
                MediaItemId("media_1") to TrackPreference(
                    audioLanguage = "ja",
                    subtitleLanguage = "en",
                    subtitlesEnabled = true,
                ),
            )),
        )
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.prepare(request)
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val viewModel = PlayerViewModel(PlaybackSessionClientBridge(controller, sourceRepository, dispatchers), dispatchers, trackPreferenceRepository = preferences)
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.setSpeed(PlaybackSpeed.of(2f))
        advanceUntilIdle()
        stateCollector.cancel()

        val saved = preferences.trackPreferences.value.resolve(request.mediaId)
        assertEquals("ja", saved.audioLanguage)
        assertEquals("en", saved.subtitleLanguage)
        assertTrue(saved.subtitlesEnabled)
        assertEquals(2f, saved.speed.value)
    }

    @Test
    fun `rotating video cycles angle emits feedback and persists per media`() = runTest {
        val controller = PreferencePlaybackController()
        val preferences = InMemoryTrackPreferences()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.prepare(request)
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers),
            dispatchers,
            trackPreferenceRepository = preferences,
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        val events = mutableListOf<PlayerUiEvent>()
        val eventCollector = backgroundScope.launch { viewModel.event.collect { events += it } }

        advanceUntilIdle()
        viewModel.rotateVideo()
        advanceUntilIdle()
        runCurrent()

        assertEquals(VideoRotation.DEGREES_90, viewModel.state.value.rotation)
        assertEquals(
            listOf(
                PlayerUiEvent.TransientMessage(
                    messageRes = R.string.player_rotation_state,
                    argumentRes = R.string.player_rotation_90,
                ),
            ),
            events,
        )
        assertEquals(
            VideoRotation.DEGREES_90,
            preferences.trackPreferences.value.resolve(request.mediaId).rotation,
        )
        stateCollector.cancel()
        eventCollector.cancel()
    }

    @Test
    fun `cycling scale mode walks fit fill original then wraps and persists per media`() = runTest {
        val controller = PreferencePlaybackController()
        val preferences = InMemoryTrackPreferences()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.prepare(request)
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers),
            dispatchers,
            trackPreferenceRepository = preferences,
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        val events = mutableListOf<PlayerUiEvent>()
        val eventCollector = backgroundScope.launch { viewModel.event.collect { events += it } }

        advanceUntilIdle()
        val observed = mutableListOf<VideoScaleMode>()
        repeat(4) {
            viewModel.cycleScaleMode()
            advanceUntilIdle()
            runCurrent()
            observed += viewModel.state.value.scaleMode
        }

        // 底栏一个按钮循环三态并回到起点。
        assertEquals(
            listOf(
                VideoScaleMode.FILL,
                VideoScaleMode.ORIGINAL,
                VideoScaleMode.FIT,
                VideoScaleMode.FILL,
            ),
            observed,
        )
        assertEquals(
            listOf(
                PlayerUiEvent.TransientMessage(
                    messageRes = R.string.player_scale_state,
                    argumentRes = R.string.player_scale_fill,
                ),
                PlayerUiEvent.TransientMessage(
                    messageRes = R.string.player_scale_state,
                    argumentRes = R.string.player_scale_original,
                ),
                PlayerUiEvent.TransientMessage(
                    messageRes = R.string.player_scale_state,
                    argumentRes = R.string.player_scale_fit,
                ),
                PlayerUiEvent.TransientMessage(
                    messageRes = R.string.player_scale_state,
                    argumentRes = R.string.player_scale_fill,
                ),
            ),
            events,
        )
        assertEquals(
            VideoScaleMode.FILL,
            preferences.trackPreferences.value.resolve(request.mediaId).scaleMode,
        )
        stateCollector.cancel()
        eventCollector.cancel()
    }

    @Test
    fun `opening media restores stored rotation`() = runTest {
        val controller = PreferencePlaybackController()
        val preferences = InMemoryTrackPreferences(
            TrackPreferenceSet(
                perMedia = mapOf(
                    MediaItemId("media_1") to TrackPreference(rotation = VideoRotation.DEGREES_270),
                ),
            ),
        )
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        // 先停在另一个媒体上，避免 open() 因为“已经在播同一个媒体”提前返回而跳过偏好恢复。
        controller.prepare(PlaybackRequest(MediaItemId("media_other"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME))
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers),
            dispatchers,
            trackPreferenceRepository = preferences,
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.open("media_1", PlaybackSourceContext.HOME)
        advanceUntilIdle()

        assertEquals(VideoRotation.DEGREES_270, viewModel.state.value.rotation)
        stateCollector.cancel()
    }

    @Test
    fun `capturing screenshot passes the current rotation to the gateway`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, seeyuer.yingli.player.domain.playback.PlaybackTimeline(2_000, 10_000)))
        val gateway = ScreenshotGatewayFake(ScreenshotResult.Saved("frame.jpg", "content://media/1"))
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers),
            dispatchers,
            screenshotGateway = gateway,
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.setRotation(VideoRotation.DEGREES_90)
        viewModel.armScreenshot()
        viewModel.captureScreenshot()
        runCurrent()

        assertEquals(listOf(VideoRotation.DEGREES_90), gateway.capturedRotations)
        stateCollector.cancel()
    }

    @Test
    fun `fullscreen request drives the window gateway and mirrors its real state`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        val gateway = WindowPlaybackGatewayFake()
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(
            ResolvedPlaybackSource(request, "content://media/1", "影片", width = 1920, height = 1080),
        )
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers),
            dispatchers,
            windowPlaybackGateway = gateway,
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()
        viewModel.open("media_1", PlaybackSourceContext.HOME)
        advanceUntilIdle()

        viewModel.toggleFullscreen()
        advanceUntilIdle()

        // 竖屏 + 横版视频：请求横屏全屏，不做视图层填充。
        assertEquals(RequestedOrientation.LANDSCAPE, gateway.requestedOrientations.single())
        assertEquals(listOf(true), gateway.fullscreenRequests)
        assertEquals(false, viewModel.state.value.fillScreen)
        // 全屏状态来自网关回传，而不是点击后乐观更新。
        assertTrue(viewModel.state.value.isFullscreen)

        viewModel.exitFullscreen()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isFullscreen)
        assertEquals(RequestedOrientation.SENSOR, gateway.requestedOrientations.last())
        stateCollector.cancel()
    }

    @Test
    fun `portrait video fullscreen fills the screen without touching scale mode`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        val gateway = WindowPlaybackGatewayFake()
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(
            ResolvedPlaybackSource(request, "content://media/1", "影片", width = 1080, height = 1920),
        )
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers),
            dispatchers,
            windowPlaybackGateway = gateway,
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()
        viewModel.open("media_1", PlaybackSourceContext.HOME)
        advanceUntilIdle()

        viewModel.toggleFullscreen()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.fillScreen)
        assertEquals(RequestedOrientation.SENSOR, gateway.requestedOrientations.single())
        // 用户的画面比例偏好不被改动，退出全屏自然恢复。
        assertEquals(VideoScaleMode.FIT, viewModel.state.value.scaleMode)
        stateCollector.cancel()
    }

    @Test
    fun `failed orientation request reports feedback and keeps confirmed state`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        val gateway = WindowPlaybackGatewayFake(failOrientation = true)
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(
            ResolvedPlaybackSource(request, "content://media/1", "影片", width = 1920, height = 1080),
        )
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers),
            dispatchers,
            windowPlaybackGateway = gateway,
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        val events = mutableListOf<PlayerUiEvent>()
        val eventCollector = backgroundScope.launch { viewModel.event.collect { events += it } }
        advanceUntilIdle()
        viewModel.open("media_1", PlaybackSourceContext.HOME)
        advanceUntilIdle()

        viewModel.toggleFullscreen()
        advanceUntilIdle()
        runCurrent()

        assertEquals(listOf(PlayerUiEvent.TransientMessage(R.string.player_orientation_failed)), events)
        assertFalse(viewModel.state.value.isFullscreen)
        assertFalse(viewModel.state.value.fillScreen)
        stateCollector.cancel()
        eventCollector.cancel()
    }

    @Test
    fun `speed panel opens in the bar and closes on the second tap`() = runTest {
        val controller = PreferencePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.prepare(request)
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(PlaybackSessionClientBridge(controller, sourceRepository, dispatchers), dispatchers)
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        runCurrent()

        viewModel.toggleSpeedPanel()
        runCurrent()
        assertEquals(PlayerPanel.SPEED, viewModel.state.value.panel)

        // 选档后档位条保持展开，方便连续比较。
        viewModel.setSpeed(PlaybackSpeed.of(1.5f))
        runCurrent()
        assertEquals(1.5f, viewModel.state.value.speed.value)
        assertEquals(PlayerPanel.SPEED, viewModel.state.value.panel)

        viewModel.toggleSpeedPanel()
        runCurrent()
        assertEquals(PlayerPanel.NONE, viewModel.state.value.panel)
        stateCollector.cancel()
    }

    @Test
    fun `hiding controls also leaves the speed strip`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, seeyuer.yingli.player.domain.playback.PlaybackTimeline(2_000, 10_000)))
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(PlaybackSessionClientBridge(controller, sourceRepository, dispatchers), dispatchers)
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        runCurrent()

        viewModel.toggleSpeedPanel()
        runCurrent()
        assertEquals(PlayerPanel.SPEED, viewModel.state.value.panel)

        // 先让控件处于可见状态（构造后可能已经被自动隐藏），再手动收起控件。
        viewModel.registerInteraction()
        runCurrent()
        assertTrue(viewModel.state.value.overlay.controlsVisible)
        viewModel.toggleOverlay()
        runCurrent()

        assertFalse(viewModel.state.value.overlay.controlsVisible)
        assertEquals(PlayerPanel.NONE, viewModel.state.value.panel)
        stateCollector.cancel()
    }

    @Test
    fun `paused playback keeps the controls visible until the user hides them`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(
            PlaybackState.Paused(request, seeyuer.yingli.player.domain.playback.PlaybackTimeline(2_000, 10_000)),
        )
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(PlaybackSessionClientBridge(controller, sourceRepository, dispatchers), dispatchers)
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        runCurrent()

        viewModel.registerInteraction()
        runCurrent()
        assertTrue(viewModel.state.value.overlay.controlsVisible)

        // 规格 §8「暂停默认保持显示」：跨过自动隐藏时长后仍然可见。
        advanceUntilIdle()
        assertTrue("暂停时控件应保持可见", viewModel.state.value.overlay.controlsVisible)

        // 用户主动隐藏后尊重该状态，不被"暂停保持显示"抢回来。
        viewModel.toggleOverlay()
        runCurrent()
        assertFalse(viewModel.state.value.overlay.controlsVisible)
        advanceUntilIdle()
        assertFalse("用户主动隐藏后不应被抢回", viewModel.state.value.overlay.controlsVisible)
        stateCollector.cancel()
    }

    @Test
    fun `locked playback still auto hides the entry while paused`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(
            PlaybackState.Paused(request, seeyuer.yingli.player.domain.playback.PlaybackTimeline(2_000, 10_000)),
        )
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(PlaybackSessionClientBridge(controller, sourceRepository, dispatchers), dispatchers)
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        runCurrent()

        viewModel.toggleLock()
        runCurrent()
        assertTrue(viewModel.state.value.overlay.locked)
        assertTrue(viewModel.state.value.overlay.controlsVisible)

        // 锁定态是"暂停保持显示"的例外（§5.13）：仍要在超时后隐藏解锁/播放入口，防误触。
        advanceUntilIdle()
        assertFalse("锁定态即使暂停也应自动隐藏", viewModel.state.value.overlay.controlsVisible)
        stateCollector.cancel()
    }

    @Test
    fun `locking only keeps the unlock entry which auto hides and reports state`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(
            PlaybackState.Paused(request, seeyuer.yingli.player.domain.playback.PlaybackTimeline(2_000, 10_000)),
        )
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(PlaybackSessionClientBridge(controller, sourceRepository, dispatchers), dispatchers)
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        val events = mutableListOf<PlayerUiEvent>()
        val eventCollector = backgroundScope.launch { viewModel.event.collect { events += it } }
        runCurrent()

        viewModel.toggleLock()
        runCurrent()
        assertTrue(viewModel.state.value.overlay.locked)
        // 锁定时立刻显示解锁入口，给出"已锁定"的可见反馈。
        assertTrue(viewModel.state.value.overlay.controlsVisible)
        assertEquals(listOf(PlayerUiEvent.TransientMessage(R.string.player_locked)), events)

        // 解锁入口同样吃 3 秒自动隐藏，不再像旧实现那样常驻。
        advanceUntilIdle()
        assertFalse(viewModel.state.value.overlay.controlsVisible)

        // 锁定态单击只唤出解锁入口，再点一次不会把它隐藏。
        viewModel.toggleOverlay()
        runCurrent()
        assertTrue(viewModel.state.value.overlay.controlsVisible)
        viewModel.toggleOverlay()
        runCurrent()
        assertTrue(viewModel.state.value.overlay.controlsVisible)
        assertTrue(viewModel.state.value.overlay.locked)

        viewModel.toggleLock()
        runCurrent()
        assertFalse(viewModel.state.value.overlay.locked)
        assertEquals(PlayerUiEvent.TransientMessage(R.string.player_unlocked), events.last())
        stateCollector.cancel()
        eventCollector.cancel()
    }

    @Test
    fun `disabling subtitles persists per media preference`() = runTest {
        val controller = PreferencePlaybackController().also {
            it.subtitleChoices.value = listOf(TrackChoice("en", "English", "en", true))
        }
        val preferences = InMemoryTrackPreferences()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.prepare(request)
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val viewModel = PlayerViewModel(PlaybackSessionClientBridge(controller, sourceRepository, dispatchers), dispatchers, trackPreferenceRepository = preferences)
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.selectSubtitleTrack(null)
        advanceUntilIdle()
        stateCollector.cancel()

        assertEquals(false, preferences.trackPreferences.value.resolve(request.mediaId).subtitlesEnabled)
    }

    @Test
    fun `opening and closing panels updates the single panel state`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(PlaybackSessionClientBridge(controller, sourceRepository, dispatchers), dispatchers)
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }

        viewModel.openPanel(PlayerPanel.SETTINGS)
        advanceUntilIdle()
        assertEquals(PlayerPanel.SETTINGS, viewModel.state.value.panel)
        viewModel.openPanel(PlayerPanel.PLAYLIST)
        advanceUntilIdle()
        assertEquals(PlayerPanel.PLAYLIST, viewModel.state.value.panel)
        viewModel.closePanel()
        advanceUntilIdle()
        assertEquals(PlayerPanel.NONE, viewModel.state.value.panel)
        stateCollector.cancel()
    }

    @Test
    fun `paused media can arm and capture a screenshot`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, seeyuer.yingli.player.domain.playback.PlaybackTimeline(2_000, 10_000)))
        var captures = 0
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers),
            dispatchers,
            screenshotGateway = object : ScreenshotGateway {
                override suspend fun capture(
                    videoTitle: String,
                    positionMillis: Long,
                    rotation: VideoRotation,
                ): ScreenshotResult {
                    captures++
                    return ScreenshotResult.Saved("frame.jpg")
                }
            },
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.armScreenshot()
        viewModel.captureScreenshot()

        assertEquals(1, captures)
        assertEquals("frame.jpg", (viewModel.state.value.screenshot as ScreenshotUiState.Preview).displayName)
        stateCollector.cancel()
    }

    @Test
    fun `deleting saved screenshot clears preview and emits success event`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, seeyuer.yingli.player.domain.playback.PlaybackTimeline(0, 10_000)))
        val gateway = ScreenshotGatewayFake(ScreenshotResult.Saved("frame.jpg", "content://media/1"))
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers),
            dispatchers,
            screenshotGateway = gateway,
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        val events = mutableListOf<PlayerUiEvent>()
        val eventCollector = backgroundScope.launch { viewModel.event.collect { events += it } }

        advanceUntilIdle()
        viewModel.armScreenshot()
        viewModel.captureScreenshot()
        runCurrent()
        viewModel.deleteScreenshot()
        advanceUntilIdle()

        assertEquals(ScreenshotUiState.Idle, viewModel.state.value.screenshot)
        assertEquals(listOf("content://media/1"), gateway.deletedUris)
        assertEquals(
            PlayerUiEvent.TransientMessage(R.string.player_screenshot_deleted),
            events.single(),
        )
        stateCollector.cancel()
        eventCollector.cancel()
    }

    @Test
    fun `deleting screenshot permission failure clears preview and emits stable failure event`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, seeyuer.yingli.player.domain.playback.PlaybackTimeline(0, 10_000)))
        val gateway = ScreenshotGatewayFake(
            ScreenshotResult.Saved("frame.jpg", "content://media/1"),
            Result.failure(IllegalStateException(ScreenshotDeleteFailure.PERMISSION_DENIED.code)),
        )
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers),
            dispatchers,
            screenshotGateway = gateway,
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        val events = mutableListOf<PlayerUiEvent>()
        val eventCollector = backgroundScope.launch { viewModel.event.collect { events += it } }

        advanceUntilIdle()
        viewModel.armScreenshot()
        viewModel.captureScreenshot()
        runCurrent()
        viewModel.deleteScreenshot()
        advanceUntilIdle()

        assertEquals(ScreenshotUiState.Idle, viewModel.state.value.screenshot)
        assertEquals(
            PlayerUiEvent.TransientMessage(R.string.player_screenshot_delete_permission_denied),
            events.single(),
        )
        stateCollector.cancel()
        eventCollector.cancel()
    }

    @Test
    fun `deleting preview without uri does not access file gateway or emit event`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, seeyuer.yingli.player.domain.playback.PlaybackTimeline(0, 10_000)))
        val gateway = ScreenshotGatewayFake(ScreenshotResult.Saved("frame.jpg"))
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers),
            dispatchers,
            screenshotGateway = gateway,
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        val events = mutableListOf<PlayerUiEvent>()
        val eventCollector = backgroundScope.launch { viewModel.event.collect { events += it } }

        advanceUntilIdle()
        viewModel.armScreenshot()
        viewModel.captureScreenshot()
        runCurrent()
        viewModel.deleteScreenshot()
        advanceUntilIdle()

        assertTrue(gateway.deletedUris.isEmpty())
        assertTrue(events.isEmpty())
        assertEquals(ScreenshotUiState.Idle, viewModel.state.value.screenshot)
        stateCollector.cancel()
        eventCollector.cancel()
    }

    @Test
    fun `changing playback order emits a message naming the new mode`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, seeyuer.yingli.player.domain.playback.PlaybackTimeline(2_000, 10_000)))
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(PlaybackSessionClientBridge(controller, sourceRepository, dispatchers), dispatchers)
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        val events = mutableListOf<PlayerUiEvent>()
        val eventCollector = backgroundScope.launch { viewModel.event.collect { events += it } }

        advanceUntilIdle()
        val result = viewModel.setPlaybackOrder(PlaybackOrder.SHUFFLE)
        // 命令反馈用的是同步 trySend，需要 runCurrent 把收集协程排入的投递任务跑完。
        runCurrent()

        assertEquals(PlaybackCommandResult.Accepted, result)
        assertEquals(
            listOf(
                PlayerUiEvent.TransientMessage(
                    messageRes = R.string.player_order_state,
                    argumentRes = R.string.player_order_shuffle,
                ),
            ),
            events,
        )
        stateCollector.cancel()
        eventCollector.cancel()
    }

    @Test
    fun `rejected command surfaces a transient message instead of failing silently`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, seeyuer.yingli.player.domain.playback.PlaybackTimeline(2_000, 10_000)))
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        // 没有队列仓储，next() 必定被会话层拒绝（NO_CANDIDATE）。
        val viewModel = PlayerViewModel(PlaybackSessionClientBridge(controller, sourceRepository, dispatchers), dispatchers)
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        val events = mutableListOf<PlayerUiEvent>()
        val eventCollector = backgroundScope.launch { viewModel.event.collect { events += it } }

        advanceUntilIdle()
        viewModel.next()
        runCurrent()

        assertEquals(
            listOf(PlayerUiEvent.TransientMessage(R.string.player_reject_no_candidate)),
            events,
        )
        stateCollector.cancel()
        eventCollector.cancel()
    }

    @Test
    fun `ab points constrain seek and reset on media open`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, seeyuer.yingli.player.domain.playback.PlaybackTimeline(2_000, 10_000)))
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(PlaybackSessionClientBridge(controller, sourceRepository, dispatchers), dispatchers)
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        assertTrue(viewModel.setAbPoint(AbPoint.A))
        controller.setState(PlaybackState.Paused(request, seeyuer.yingli.player.domain.playback.PlaybackTimeline(6_000, 10_000)))
        advanceUntilIdle()
        assertTrue(viewModel.setAbPoint(AbPoint.B))
        assertEquals(2_000L, viewModel.state.value.abLoop.pointA)
        assertEquals(6_000L, viewModel.state.value.abLoop.pointB)
        viewModel.seekTo(9_000)
        assertEquals(6_000L, controller.lastSeekPosition)

        stateCollector.cancel()
    }

    @Test
    fun `previous restarts current media after five seconds`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, seeyuer.yingli.player.domain.playback.PlaybackTimeline(6_000, 10_000)))
        val queueRepository = FakePlaybackQueueRepository()
        queueRepository.setQueue(
            PlaybackQueue(listOf(MediaItemId("media_1"), MediaItemId("media_2")), 1, continuousPlayback = true),
        )
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers, queueRepository),
            dispatchers,
            playbackQueueRepository = queueRepository,
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.previous()

        assertEquals(0L, controller.lastSeekPosition)
        assertEquals(1, queueRepository.queue.first()?.currentIndex)
        stateCollector.cancel()
    }

    private class TestDispatchers(private val dispatcher: CoroutineDispatcher) : AppDispatchers {
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
    }

    private class FakePlaybackSourceRepository(
        private val source: ResolvedPlaybackSource,
    ) : PlaybackSourceRepository {
        override suspend fun resolve(
            mediaId: MediaItemId,
            sourceContext: PlaybackSourceContext,
            incognito: Boolean,
        ): ResolvedPlaybackSource = source

        override suspend fun resolve(request: PlaybackRequest): ResolvedPlaybackSource = source
    }

    private class FakePlaybackController : PlaybackController,
        seeyuer.yingli.player.domain.security.SecurePlaybackController {
        private val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val state: StateFlow<PlaybackState> = mutableState
        override val connectionState = MutableStateFlow(PlaybackConnectionState.CONNECTED)
        var preparedRequest: PlaybackRequest? = null
        var lastSeekPosition: Long? = null
        var vaultPrepared: seeyuer.yingli.player.domain.security.VaultItemId? = null

        fun setState(value: PlaybackState) {
            mutableState.value = value
        }

        override fun prepare(request: PlaybackRequest): PlaybackCommandResult {
            preparedRequest = request
            mutableState.value = PlaybackState.Preparing(request)
            return PlaybackCommandResult.Accepted
        }

        override fun play() = PlaybackCommandResult.Accepted
        override fun pause() = PlaybackCommandResult.Accepted
        override fun seekTo(positionMillis: Long): PlaybackCommandResult {
            lastSeekPosition = positionMillis
            return PlaybackCommandResult.Accepted
        }
        override fun stop() = PlaybackCommandResult.Accepted
        override fun retry() = PlaybackCommandResult.Accepted

        override fun prepare(itemId: seeyuer.yingli.player.domain.security.VaultItemId): Boolean {
            vaultPrepared = itemId
            mutableState.value = PlaybackState.Preparing(
                PlaybackRequest(
                    MediaItemId("vault-${itemId.value}"),
                    MediaLocationId("vault-${itemId.value}"),
                    0,
                    PlaybackSourceContext.DETAIL,
                    incognito = true,
                ),
            )
            return true
        }

        override fun invalidateSecureSession() = Unit
    }

    private class FakePlaybackQueueRepository : PlaybackQueueRepository {
        private val mutableQueue = MutableStateFlow<PlaybackQueue?>(null)
        override val queue: StateFlow<PlaybackQueue?> = mutableQueue
        override suspend fun setQueue(queue: PlaybackQueue?) {
            mutableQueue.value = queue
        }
    }

    private class QueueSourceLibraryRepository(
        private val folderQueryStarted: CompletableDeferred<Unit>,
        private val folderQueryGate: CompletableDeferred<Unit>,
        private val folderMedia: LibraryMedia,
        private val allMedia: LibraryMedia,
    ) : LibraryPagingRepository {
        override fun observe(query: LibraryQuery) = emptyFlow<LibraryResult<LibraryPage>>()

        override suspend fun query(query: LibraryQuery) = LibraryResult.Success(LibraryPage(emptyList(), null, 0))

        override suspend fun page(query: LibraryQuery, direction: LibraryPageDirection): LibraryPage {
            if (query.browseMode == LibraryBrowseMode.FOLDER) {
                folderQueryStarted.complete(Unit)
                folderQueryGate.await()
                return LibraryPage(listOf(folderMedia), null, 1)
            }
            return LibraryPage(listOf(allMedia), null, 1)
        }

        override fun observeCount(query: LibraryQuery) = flowOf(0)

        override fun observeFolderTreeVideoCount(path: String) = flowOf(0)

        override fun observeInvalidations() = emptyFlow<Unit>()
    }

    private class WindowPlaybackGatewayFake(
        private val failOrientation: Boolean = false,
    ) : seeyuer.yingli.player.domain.playback.WindowPlaybackGateway {
        private val mutableState = MutableStateFlow(
            seeyuer.yingli.player.domain.playback.WindowPlaybackState(isPortrait = true),
        )
        override val state: StateFlow<seeyuer.yingli.player.domain.playback.WindowPlaybackState> = mutableState
        val requestedOrientations = mutableListOf<seeyuer.yingli.player.domain.playback.RequestedOrientation>()
        val fullscreenRequests = mutableListOf<Boolean>()

        override fun setFullscreen(enabled: Boolean): Result<Unit> {
            fullscreenRequests += enabled
            mutableState.value = mutableState.value.copy(isFullscreen = enabled)
            return Result.success(Unit)
        }

        override fun requestOrientation(
            orientation: seeyuer.yingli.player.domain.playback.RequestedOrientation,
        ): Result<Unit> {
            requestedOrientations += orientation
            return if (failOrientation) {
                Result.failure(IllegalStateException("ORIENTATION_REJECTED"))
            } else {
                mutableState.value = mutableState.value.copy(orientation = orientation)
                Result.success(Unit)
            }
        }

        override fun enterPictureInPicture(): Result<Unit> = Result.success(Unit)
    }

    private class ScreenshotGatewayFake(        private val captureResult: ScreenshotResult,
        private val deleteResult: Result<Unit> = Result.success(Unit),
    ) : ScreenshotGateway, ScreenshotFileGateway {
        val deletedUris = mutableListOf<String>()
        val capturedRotations = mutableListOf<VideoRotation>()

        override suspend fun capture(
            videoTitle: String,
            positionMillis: Long,
            rotation: VideoRotation,
        ): ScreenshotResult {
            capturedRotations += rotation
            return captureResult
        }

        override suspend fun delete(uri: String): Result<Unit> {
            deletedUris += uri
            return deleteResult
        }
    }

    private class PreferencePlaybackController : AdvancedPlaybackController {
        private val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val state: StateFlow<PlaybackState> = mutableState
        override val connectionState = MutableStateFlow(PlaybackConnectionState.CONNECTED)
        val subtitleChoices = MutableStateFlow<List<TrackChoice>>(emptyList())
        override val audioTracks = MutableStateFlow<List<TrackChoice>>(emptyList())
        override val subtitleTracks: StateFlow<List<TrackChoice>> = subtitleChoices
        override val speed = MutableStateFlow(PlaybackSpeed.Normal)
        override val scaleMode = MutableStateFlow(VideoScaleMode.FIT)

        override fun prepare(request: PlaybackRequest): PlaybackCommandResult {
            mutableState.value = PlaybackState.Preparing(request)
            return PlaybackCommandResult.Accepted
        }
        override fun play() = PlaybackCommandResult.Accepted
        override fun pause() = PlaybackCommandResult.Accepted
        override fun seekTo(positionMillis: Long) = PlaybackCommandResult.Accepted
        override fun stop() = PlaybackCommandResult.Accepted
        override fun retry() = PlaybackCommandResult.Accepted
        override fun seekBy(offsetMillis: Long) = PlaybackCommandResult.Accepted
        override fun setSpeed(speed: PlaybackSpeed): PlaybackCommandResult { this.speed.value = speed; return PlaybackCommandResult.Accepted }
        override fun selectAudioTrack(id: String) = PlaybackCommandResult.Accepted
        override fun selectSubtitleTrack(id: String?): PlaybackCommandResult = PlaybackCommandResult.Accepted
        override fun setScaleMode(mode: VideoScaleMode): PlaybackCommandResult { scaleMode.value = mode; return PlaybackCommandResult.Accepted }
    }

    private class InMemoryTrackPreferences(initial: TrackPreferenceSet = TrackPreferenceSet()) : TrackPreferenceRepository {
        private val mutablePreferences = MutableStateFlow(initial)
        override val trackPreferences: StateFlow<TrackPreferenceSet> = mutablePreferences
        override suspend fun setGlobal(preference: TrackPreference) {
            mutablePreferences.value = mutablePreferences.value.copy(global = preference)
        }
        override suspend fun setForMedia(mediaId: MediaItemId, preference: TrackPreference?) {
            mutablePreferences.value = mutablePreferences.value.copy(
                perMedia = mutablePreferences.value.perMedia.toMutableMap().apply {
                    if (preference == null) remove(mediaId) else put(mediaId, preference)
                },
            )
        }
    }
}
