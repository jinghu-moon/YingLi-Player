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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import seeyuer.yingli.player.domain.playback.SCREENSHOT_PREVIEW_TICK_MILLIS
import seeyuer.yingli.player.domain.playback.FrameCalibration
import seeyuer.yingli.player.domain.playback.FrameCalibrationControl
import seeyuer.yingli.player.domain.playback.FrameCalibrationResult
import seeyuer.yingli.player.domain.playback.FrameCounterState
import seeyuer.yingli.player.domain.playback.PlaybackMediaInfo
import seeyuer.yingli.player.domain.playback.MutableSeekPrecisionControl
import seeyuer.yingli.player.domain.playback.SeekPrecision
import seeyuer.yingli.player.domain.playback.PlaybackTimeline
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
        // 收尾：预览卡的倒计时计时器是**持续运行**的（这正是"只有一个计时器"的实现方式），
        // 不主动结束会话，测试调度器就永远有下一个任务，runTest 会一直等下去。
        viewModel.closeScreenshot()
        stateCollector.cancel()
    }

    @Test
    fun `arming the screenshot tool pauses playback so the frame is stable`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        // 先落成"已暂停"再 advanceUntilIdle：Playing 状态下进度投影流按 PROGRESS_TICK_MILLIS
        // 无限自增，虚拟时间永远有下一个任务，advanceUntilIdle 不会返回。
        controller.setState(PlaybackState.Paused(request, seeyuer.yingli.player.domain.playback.PlaybackTimeline(2_000, 10_000)))
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers),
            dispatchers,
            screenshotGateway = ScreenshotGatewayFake(ScreenshotResult.Saved("frame.jpg", "content://media/1")),
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        // 用户先按下播放：进入截图模式之前确实在播。
        controller.setState(PlaybackState.Playing(request, seeyuer.yingli.player.domain.playback.PlaybackTimeline(2_000, 10_000)))
        runCurrent()

        viewModel.armScreenshot()
        // 进入截图模式即暂停：否则"当前帧"一直在动，捕获出来的不是用户看到的那一帧。
        assertEquals(1, controller.pauseCount)
        // 用户仍可手动按播放键恢复。
        viewModel.play()
        stateCollector.cancel()
    }

    @Test
    fun `preview countdown ticks down and only the natural expiry reports the saved location`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, seeyuer.yingli.player.domain.playback.PlaybackTimeline(0, 10_000)))
        val gateway = ScreenshotGatewayFake(
            ScreenshotResult.Saved("frame.jpg", "content://media/1", "Pictures/YingLi/frame.jpg"),
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
        val captured = viewModel.state.value.screenshot as ScreenshotUiState.Preview
        assertEquals("Pictures/YingLi/frame.jpg", captured.location)
        assertEquals(ScreenshotUiState.PREVIEW_DURATION_MILLIS, captured.remainingMillis)

        advanceTimeBy(1_000)
        val midway = viewModel.state.value.screenshot as ScreenshotUiState.Preview
        assertTrue("remaining=${midway.remainingMillis}", midway.remainingMillis < 3_000L)
        assertTrue("remaining=${midway.remainingMillis}", midway.remainingMillis > 1_000L)
        assertTrue("读条未走完就不该提示保存路径", events.isEmpty())

        advanceTimeBy(2_100)
        assertEquals(ScreenshotUiState.Idle, viewModel.state.value.screenshot)
        assertEquals(
            listOf(
                PlayerUiEvent.TransientMessage(
                    R.string.player_screenshot_saved_to,
                    stringArgument = "Pictures/YingLi/frame.jpg",
                ),
            ),
            events,
        )
        stateCollector.cancel()
        eventCollector.cancel()
    }

    @Test
    fun `expanding the preview freezes the readout and resumes it on collapse`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, seeyuer.yingli.player.domain.playback.PlaybackTimeline(0, 10_000)))
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers),
            dispatchers,
            screenshotGateway = ScreenshotGatewayFake(
                ScreenshotResult.Saved("frame.jpg", "content://media/1", "Pictures/YingLi/frame.jpg"),
            ),
        )
        // 收集卡片还挂着时推出去的读条值：展开期间有没有继续走，看这个列表就够了。
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.armScreenshot()
        viewModel.captureScreenshot()
        runCurrent()
        advanceTimeBy(1_000)
        val beforeExpand = (viewModel.state.value.screenshot as ScreenshotUiState.Preview).remainingMillis

        viewModel.setScreenshotPreviewExpanded(true)
        assertEquals(true, (viewModel.state.value.screenshot as ScreenshotUiState.Preview).expanded)
        // 展开那一刻读条定格（设计稿 §6）：卡片上的读条值停在手点下去时的那个数。
        val frozen = (viewModel.state.value.screenshot as ScreenshotUiState.Preview).remainingMillis
        // 看大图的这段时间照样算进 3 秒里：UI 上推出去的"还剩多久"会跟着走。
        advanceTimeBy(1_000)
        assertTrue(
            "expanded readout should keep ticking down",
            (viewModel.state.value.screenshot as ScreenshotUiState.Preview).remainingMillis < frozen,
        )

        viewModel.setScreenshotPreviewExpanded(false)
        val collapsed = viewModel.state.value.screenshot as ScreenshotUiState.Preview
        assertEquals(false, collapsed.expanded)
        // 收起后从"展开那一刻的读数 - 看图的 1 秒"接着走：既不会重新给满，
        // 也不会把看图的 1 秒白白退还给用户。
        assertTrue("frozen=$frozen collapsed=${collapsed.remainingMillis}", collapsed.remainingMillis < frozen)
        assertTrue("remaining=${collapsed.remainingMillis}", collapsed.remainingMillis > 0L)
        // 再走完剩下的时间就自动消失（会话结束，计时器随之收工）。
        advanceTimeBy(ScreenshotUiState.PREVIEW_DURATION_MILLIS)
        assertEquals(ScreenshotUiState.Idle, viewModel.state.value.screenshot)
        stateCollector.cancel()
    }

    @Test
    fun `countdown finishing while expanded keeps the preview and reports the location once`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, seeyuer.yingli.player.domain.playback.PlaybackTimeline(0, 10_000)))
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers),
            dispatchers,
            screenshotGateway = ScreenshotGatewayFake(
                ScreenshotResult.Saved("frame.jpg", "content://media/1", "Pictures/YingLi/frame.jpg"),
            ),
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        val events = mutableListOf<PlayerUiEvent>()
        val eventCollector = backgroundScope.launch { viewModel.event.collect { events += it } }
        advanceUntilIdle()

        viewModel.armScreenshot()
        viewModel.captureScreenshot()
        runCurrent()
        // 读条走掉 2 秒后再展开：只在大图里看一眼（0.5 秒），读条定格在 1 秒。
        advanceTimeBy(2_000)
        viewModel.setScreenshotPreviewExpanded(true)
        advanceTimeBy(500)
        // 定格期间不会有任何提示，卡片也一直在。
        assertTrue("定格期间不该提示保存路径", events.isEmpty())
        assertEquals(true, (viewModel.state.value.screenshot as ScreenshotUiState.Preview).expanded)

        // 大图一直开着看图：1 秒后读条到点——卡片不消失，但提示一次保存路径（需求一.3.2）。
        advanceTimeBy(1_000)
        val expanded = viewModel.state.value.screenshot as ScreenshotUiState.Preview
        assertEquals(true, expanded.expanded)
        assertEquals(0L, expanded.remainingMillis)
        assertEquals(
            listOf(
                PlayerUiEvent.TransientMessage(
                    R.string.player_screenshot_saved_to,
                    stringArgument = "Pictures/YingLi/frame.jpg",
                ),
            ),
            events,
        )

        // 关掉大图 = 按"倒计时到期"的正常流程收场：卡片消失，且不再重复提示。
        viewModel.setScreenshotPreviewExpanded(false)
        assertEquals(ScreenshotUiState.Idle, viewModel.state.value.screenshot)
        assertEquals(1, events.size)
        stateCollector.cancel()
        eventCollector.cancel()
    }

    @Test
    fun `deleting the preview removes the saved file and never reports the location`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, seeyuer.yingli.player.domain.playback.PlaybackTimeline(0, 10_000)))
        val gateway = ScreenshotGatewayFake(
            ScreenshotResult.Saved("frame.jpg", "content://media/1", "Pictures/YingLi/frame.jpg"),
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
        viewModel.setScreenshotPreviewExpanded(true)
        viewModel.deleteScreenshot()
        advanceTimeBy(5_000)

        // 真正删掉文件、取消倒计时、卡片消失，并且**不再**提示保存路径。
        assertEquals(listOf("content://media/1"), gateway.deletedUris)
        assertEquals(ScreenshotUiState.Idle, viewModel.state.value.screenshot)
        assertEquals(
            listOf(PlayerUiEvent.TransientMessage(R.string.player_screenshot_deleted)),
            events,
        )
        stateCollector.cancel()
        eventCollector.cancel()
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

    /**
     * AB 设点被拒的回执必须走到用户可见提示。
     *
     * 端到端链条：会话判定拒绝 → 命令回执（`SessionResult`）→ 控制器 → bridge 的
     * `OneShotFeedback` → ViewModel 的瞬时消息。这里锁定最后一跳（拒绝码 → 文案），
     * 前几跳分别由 bridge 单测与真机 instrumented 用例覆盖。
     */
    @Test
    fun `rejected ab set point surfaces the ab rejection message`() = runTest {
        val controller = FakePlaybackController()
        controller.setAbPointOutcome =
            seeyuer.yingli.player.domain.playback.AbLoopCommandOutcome.Rejected(
                seeyuer.yingli.player.domain.playback.PlaybackCommandRejection.AB_UNAVAILABLE,
            )
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, seeyuer.yingli.player.domain.playback.PlaybackTimeline(2_000, 10_000)))
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(PlaybackSessionClientBridge(controller, sourceRepository, dispatchers), dispatchers)
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        val events = mutableListOf<PlayerUiEvent>()
        val eventCollector = backgroundScope.launch { viewModel.event.collect { events += it } }

        advanceUntilIdle()
        viewModel.setAbPoint(AbPoint.A)
        runCurrent()

        assertEquals(
            listOf(PlayerUiEvent.TransientMessage(R.string.player_reject_ab_unavailable)),
            events,
        )
        stateCollector.cancel()
        eventCollector.cancel()
    }

    /**
     * AB 阶段 1：ViewModel **不再持有**任何 AB 状态或判定规则。
     *
     * 旧实现里 `setAbPoint` 在本地跑 `AbLoopLimiter`、`seekTo` 被 `clamp` 钳到 `[A,B]`。
     * 现在：命令只经会话送出（`requestSetAbPoint`），区间与计数只从会话快照读回来；
     * 用户 seek 也不再被区间钳制（D8-A：循环期间允许拖到区间外）。
     */
    @Test
    fun `ab commands go to the session and the interval is projected back without local clamping`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, PlaybackTimeline(2_000, 10_000)))
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(PlaybackSessionClientBridge(controller, sourceRepository, dispatchers), dispatchers)
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        assertTrue(viewModel.setAbPoint(AbPoint.A))
        advanceUntilIdle()
        // 命令下发给会话，而不是在 ViewModel 里就地改状态。
        assertEquals(listOf(AbPoint.A), controller.requestedAbPoints)
        assertFalse(viewModel.state.value.abLoop.active)

        // 会话回流区间与计数：UI 只投影会话的快照。
        controller.publishAbLoop(
            seeyuer.yingli.player.domain.playback.AbLoopSession(
                state = seeyuer.yingli.player.domain.playback.AbLoopState(2_000, 6_000),
                loopCount = 12,
            ),
        )
        advanceUntilIdle()
        assertEquals(2_000L, viewModel.state.value.abLoop.pointA)
        assertEquals(6_000L, viewModel.state.value.abLoop.pointB)

        // 用户拖到区间外：位置原样下发，不再被钳制到 B。
        viewModel.seekTo(9_000)
        advanceUntilIdle()
        assertEquals(9_000L, controller.lastSeekPosition)

        viewModel.clearAb()
        advanceUntilIdle()
        assertEquals(1, controller.clearAbRequests)

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

    /**
     * "上一项"的 5 秒判定用**实时位置**，不是会话快照里可能陈旧的显示位置。
     *
     * 现场：快照停在 1 秒（稳定播放期间引擎不重发状态，实测静置播放 60s 后仍是 0），
     * 实时位置已经 59 秒。用显示位置判定会落到"切上一项"，用实时位置才是"回到本集开头"。
     */
    @Test
    fun `previous restarts the current media based on the live position not the stale snapshot`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        // 快照最后一次跳变在 1 秒处。
        controller.setState(PlaybackState.Paused(request, PlaybackTimeline(1_000, 95_458)))
        // 播放推进了 59 秒，但没有发布新的播放状态。
        controller.setPosition(59_894)
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

        // 回到本集开头：位置被 seek 到 0，队列仍停在本项。
        assertEquals(0L, controller.lastSeekPosition)
        assertEquals(1, queueRepository.queue.first()?.currentIndex)
        stateCollector.cancel()
    }

    // ---- 截图模式第 2 步：帧精确跳转 + 逐帧步进 + 帧号校准 ----
    @Test
    fun `screenshot tool switches to frame accurate seeking and restores the duration rule on exit`() = runTest {
        // 长视频（>120s）的默认策略是关键帧跳转：进入截图工具后才允许改成精确跳转。
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, PlaybackTimeline(2_000, 600_000)))
        val control = MutableSeekPrecisionControl()
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers),
            dispatchers,
            screenshotGateway = ScreenshotGatewayFake(ScreenshotResult.Saved("frame.jpg")),
            seekPrecisionControl = control,
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()
        assertEquals(SeekPrecision.CLOSEST_SYNC, control.precision.value)

        viewModel.armScreenshot()
        runCurrent()
        assertEquals(SeekPrecision.FRAME_ACCURATE, control.precision.value)

        viewModel.closeScreenshot()
        runCurrent()
        // 退出后按时长还原（600s → 关键帧跳转），而不是无条件回到某一个固定档。
        assertEquals(SeekPrecision.CLOSEST_SYNC, control.precision.value)
        stateCollector.cancel()
    }

    @Test
    fun `leaving a short media screenshot session restores frame accurate seeking`() = runTest {
        // 短视频（≤120s）的默认策略本身就是精确跳转：还原要按同一规则算，不能一律写成关键帧。
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, PlaybackTimeline(1_000, 10_000)))
        val control = MutableSeekPrecisionControl(SeekPrecision.CLOSEST_SYNC)
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers),
            dispatchers,
            screenshotGateway = ScreenshotGatewayFake(ScreenshotResult.Saved("frame.jpg")),
            seekPrecisionControl = control,
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.armScreenshot()
        runCurrent()
        viewModel.closeScreenshot()
        runCurrent()

        assertEquals(SeekPrecision.FRAME_ACCURATE, control.precision.value)
        stateCollector.cancel()
    }

    @Test
    fun `stepping one frame seeks by a single frame duration and pauses`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, PlaybackTimeline(1_000, 10_000)))
        controller.setMediaInfo(PlaybackMediaInfo(title = "影片", durationMillis = 10_000, frameRate = 30f))
        val control = MutableSeekPrecisionControl()
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers),
            dispatchers,
            screenshotGateway = ScreenshotGatewayFake(ScreenshotResult.Saved("frame.jpg")),
            seekPrecisionControl = control,
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.armScreenshot()
        runCurrent()
        // 进入截图模式本身就暂停一次（截图定位时不应继续播放）。
        assertEquals(1, controller.pauseCount)
        viewModel.stepScreenshotFrame(forward = true)
        runCurrent()

        // 30fps → 一帧 33ms；步进必须走精确 seek 的目标位置，而不是固定 34ms 的估算偏移。
        assertEquals(1_033L, controller.lastSeekPosition)
        // 步进再暂停一次：两处暂停走的是同一条路径（pauseForFrameStepping），不会互相打架。
        assertEquals(2, controller.pauseCount)
        // 退出截图模式后步进必须无效（工具没打开时不该动播放位置）。
        viewModel.closeScreenshot()
        runCurrent()
        val positionAfterClose = controller.lastSeekPosition
        viewModel.stepScreenshotFrame(forward = true)
        runCurrent()
        assertEquals(positionAfterClose, controller.lastSeekPosition)
        stateCollector.cancel()
    }

    @Test
    fun `stepping anchors on the previous target while the position report lags`() = runTest {
        // seek 回报延迟时连续步进仍要每次前进一帧（否则会"点两下只走一帧"）。
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, PlaybackTimeline(1_000, 10_000)))
        controller.setMediaInfo(PlaybackMediaInfo(title = "影片", durationMillis = 10_000, frameRate = 30f))
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers),
            dispatchers,
            screenshotGateway = ScreenshotGatewayFake(ScreenshotResult.Saved("frame.jpg")),
            seekPrecisionControl = MutableSeekPrecisionControl(),
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.armScreenshot()
        runCurrent()
        viewModel.stepScreenshotFrame(forward = true)
        runCurrent()
        viewModel.stepScreenshotFrame(forward = true)
        runCurrent()

        // 控制器没有回报新位置（假控制器不更新 state），但锚点让第二步仍然从 1033 走到 1066。
        assertEquals(1_066L, controller.lastSeekPosition)
        stateCollector.cancel()
    }

    @Test
    fun `step target is clamped at the end of the media`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, PlaybackTimeline(9_990, 10_000)))
        controller.setMediaInfo(PlaybackMediaInfo(title = "影片", durationMillis = 10_000, frameRate = 30f))
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers),
            dispatchers,
            screenshotGateway = ScreenshotGatewayFake(ScreenshotResult.Saved("frame.jpg")),
            seekPrecisionControl = MutableSeekPrecisionControl(),
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.armScreenshot()
        runCurrent()
        viewModel.stepScreenshotFrame(forward = true)
        runCurrent()

        assertEquals(10_000L, controller.lastSeekPosition)
        stateCollector.cancel()
    }

    @Test
    fun `calibrated frame count replaces the estimate in the ui state`() = runTest {
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, PlaybackTimeline(0, 10_000)))
        controller.setMediaInfo(PlaybackMediaInfo(title = "影片", durationMillis = 10_000, frameRate = 30f))
        val calibration = MutableFrameCalibrationControlFake()
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(
                controller,
                sourceRepository,
                dispatchers,
                frameCalibrationControl = calibration,
            ),
            dispatchers,
            screenshotGateway = ScreenshotGatewayFake(ScreenshotResult.Saved("frame.jpg")),
            seekPrecisionControl = MutableSeekPrecisionControl(),
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()
        viewModel.armScreenshot()
        runCurrent()

        // 进入截图模式就发起后台校准（估算值此时仍在显示）。
        assertEquals(1, calibration.calibrateCalls)
        // 估算：10 秒 @30fps = 300 帧。
        assertEquals(FrameCounterState(1, 300), viewModel.state.value.frameCounter)

        // 后台校准完成：总帧数换成真实 sample 数，实测帧率也一起暴露给 UI（步进优先用它）。
        calibration.emit(FrameCalibrationResult.Calibrated(FrameCalibration(frameCount = 301, measuredFrameRate = 29.97f)))
        runCurrent()

        assertEquals(FrameCounterState(1, 301), viewModel.state.value.frameCounter)
        assertEquals(29.97f, viewModel.state.value.measuredFrameRate ?: 0f, 0.001f)

        // 退出截图模式：取消在跑的校准并回到估算口径（胶囊隐藏或退回估算，不再用旧媒体的精确值）。
        viewModel.closeScreenshot()
        runCurrent()
        assertEquals(1, calibration.closeCalls)
        assertNull(viewModel.state.value.measuredFrameRate)
        assertEquals(FrameCounterState(1, 300), viewModel.state.value.frameCounter)
        stateCollector.cancel()
    }

    @Test
    fun `long calibration marks the estimated frame counter until the exact value arrives`() = runTest {
        // 大文件（估算耗时远超阈值）实测要等 2.5s 以上：校准期间必须把"估算值"标出来；
        // 校准一完成就把标记摘掉（此时显示的是精确值）。
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, PlaybackTimeline(0, 10_000)))
        controller.setMediaInfo(
            PlaybackMediaInfo(
                title = "影片",
                durationMillis = 10_000,
                frameRate = 30f,
                // 10 秒 1080p 的文件不会这么大，但这里要的是"估算耗时超过阈值"这一条路径：
                // 体积项本身就能把它顶到阈值之上（模型与阈值依据见 frameCalibrationNoticeRequired）。
                fileSizeBytes = 2L * 1024 * 1024 * 1024,
            ),
        )
        val calibration = MutableFrameCalibrationControlFake()
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers, frameCalibrationControl = calibration),
            dispatchers,
            screenshotGateway = ScreenshotGatewayFake(ScreenshotResult.Saved("frame.jpg")),
            seekPrecisionControl = MutableSeekPrecisionControl(),
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()
        viewModel.armScreenshot()
        runCurrent()

        calibration.emit(FrameCalibrationResult.Calibrating)
        runCurrent()
        assertEquals(FrameCounterState(1, 300), viewModel.state.value.frameCounter)
        assertTrue("大文件校准中必须标出估算值", viewModel.state.value.frameCounterPending)

        calibration.emit(FrameCalibrationResult.Calibrated(FrameCalibration(frameCount = 301, measuredFrameRate = 29.97f)))
        runCurrent()
        assertEquals(FrameCounterState(1, 301), viewModel.state.value.frameCounter)
        assertFalse("精确值就位后不许再标估算", viewModel.state.value.frameCounterPending)
        stateCollector.cancel()
    }

    @Test
    fun `short calibration never marks the frame counter as estimated`() = runTest {
        // 小文件实测只有 100ms 级（比胶囊入场动画还快）：标出来只会在屏幕上闪一下，比不标更糟。
        val controller = FakePlaybackController()
        val request = PlaybackRequest(MediaItemId("media_1"), MediaLocationId("location_1"), 0, PlaybackSourceContext.HOME)
        controller.setState(PlaybackState.Paused(request, PlaybackTimeline(0, 10_000)))
        controller.setMediaInfo(
            PlaybackMediaInfo(title = "影片", durationMillis = 10_000, frameRate = 30f, fileSizeBytes = 64L * 1024 * 1024),
        )
        val calibration = MutableFrameCalibrationControlFake()
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val sourceRepository = FakePlaybackSourceRepository(ResolvedPlaybackSource(request, "content://media/1", "影片"))
        val viewModel = PlayerViewModel(
            PlaybackSessionClientBridge(controller, sourceRepository, dispatchers, frameCalibrationControl = calibration),
            dispatchers,
            screenshotGateway = ScreenshotGatewayFake(ScreenshotResult.Saved("frame.jpg")),
            seekPrecisionControl = MutableSeekPrecisionControl(),
        )
        val stateCollector = backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()
        viewModel.armScreenshot()
        runCurrent()

        calibration.emit(FrameCalibrationResult.Calibrating)
        runCurrent()

        assertFalse("小文件不该出现估算标记", viewModel.state.value.frameCounterPending)
        stateCollector.cancel()
    }

    /** 可注入的校准结果源：用来驱动"估算 → 精确"的升级路径。 */
    private class MutableFrameCalibrationControlFake : FrameCalibrationControl {
        private val mutableResult = MutableStateFlow<FrameCalibrationResult?>(null)
        override val result: StateFlow<FrameCalibrationResult?> = mutableResult
        var calibrateCalls = 0
        var closeCalls = 0

        fun emit(value: FrameCalibrationResult) {
            mutableResult.value = value
        }

        override fun calibrate(uri: String, mediaId: String) {
            calibrateCalls++
        }

        override fun close() {
            closeCalls++
            mutableResult.value = null
        }

        override fun shutdown() {
            close()
        }
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
        seeyuer.yingli.player.domain.security.SecurePlaybackController,
        seeyuer.yingli.player.domain.playback.PlaybackMediaInfoProvider,
        seeyuer.yingli.player.domain.playback.AbLoopPlaybackControl {
        private val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val state: StateFlow<PlaybackState> = mutableState
        override val connectionState = MutableStateFlow(PlaybackConnectionState.CONNECTED)
        private val mutableMediaInfo = MutableStateFlow<seeyuer.yingli.player.domain.playback.PlaybackMediaInfo?>(null)
        override val mediaInfo: StateFlow<seeyuer.yingli.player.domain.playback.PlaybackMediaInfo?> = mutableMediaInfo
        var preparedRequest: PlaybackRequest? = null
        var lastSeekPosition: Long? = null
        var pauseCount = 0
        var vaultPrepared: seeyuer.yingli.player.domain.security.VaultItemId? = null

        /**
         * 实时位置的独立来源：由 [setPosition] 显式驱动，**不**跟着 [state] 的 timeline 走。
         * 测试要能构造"快照停在旧位置、实时位置已经走远"的现场，而把两者绑在一起就构造不出来。
         */
        private var livePositionMillis = 0L

        override fun currentPositionMillis(): Long = livePositionMillis

        /** 播放推进到某个位置（只改实时位置；快照 timeline 由 [setState] 单独控制）。 */
        fun setPosition(positionMillis: Long) {
            livePositionMillis = positionMillis
        }

        /**
         * 会话侧 AB 状态的假实现：只记录命令并让状态流变化（模拟会话 runtime 的权威状态回流）。
         * 这里**不做**任何 AB 业务规则判定 —— 规则属于会话 runtime，属于 [PlaybackSessionRuntimeTest]。
         */
        private val mutableAbLoop = MutableStateFlow(seeyuer.yingli.player.domain.playback.AbLoopSession.EMPTY)
        override val abLoop: StateFlow<seeyuer.yingli.player.domain.playback.AbLoopSession> = mutableAbLoop
        val requestedAbPoints = mutableListOf<AbPoint>()
        var clearAbRequests = 0

        /** 会话对设点的判定结果：默认接受，测试按需改成拒绝。 */
        var setAbPointOutcome: seeyuer.yingli.player.domain.playback.AbLoopCommandOutcome =
            seeyuer.yingli.player.domain.playback.AbLoopCommandOutcome.Applied

        override suspend fun requestSetAbPoint(
            point: AbPoint,
        ): seeyuer.yingli.player.domain.playback.AbLoopCommandOutcome {
            requestedAbPoints += point
            return setAbPointOutcome
        }

        override fun requestClearAbLoop() {
            clearAbRequests++
            mutableAbLoop.value = seeyuer.yingli.player.domain.playback.AbLoopSession.EMPTY
        }

        /** 模拟会话回流：区间 + 计数。 */
        fun publishAbLoop(session: seeyuer.yingli.player.domain.playback.AbLoopSession) {
            mutableAbLoop.value = session
        }

        fun setState(value: PlaybackState) {
            mutableState.value = value
            // 状态跳变时实时位置随之对齐（真实播放器在 seek/换源后就是这样）；
            // 播放**推进**不会发布状态，那一路要用 [setPosition] 单独驱动 —— 这正是快照会陈旧的原因。
            livePositionMillis = value.timeline.positionMillis
        }

        fun setMediaInfo(value: seeyuer.yingli.player.domain.playback.PlaybackMediaInfo) {
            mutableMediaInfo.value = value
        }

        override fun prepare(request: PlaybackRequest): PlaybackCommandResult {
            preparedRequest = request
            mutableState.value = PlaybackState.Preparing(request)
            return PlaybackCommandResult.Accepted
        }

        override fun play() = PlaybackCommandResult.Accepted
        override fun pause(): PlaybackCommandResult {
            pauseCount++
            return PlaybackCommandResult.Accepted
        }
        override fun seekTo(positionMillis: Long): PlaybackCommandResult {
            lastSeekPosition = positionMillis
            livePositionMillis = positionMillis
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
        override fun currentPositionMillis(): Long = mutableState.value.timeline.positionMillis
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
