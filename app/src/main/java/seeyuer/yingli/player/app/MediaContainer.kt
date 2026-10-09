package seeyuer.yingli.player.app

import android.content.Context
import android.os.BatteryManager
import android.os.StatFs
import androidx.room.Room
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import coil3.annotation.DelicateCoilApi
import coil3.disk.DiskCache
import okio.Path.Companion.toPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import seeyuer.yingli.player.data.room.RoomMediaCatalogRepository
import seeyuer.yingli.player.data.room.RoomMediaSourceRepository
import seeyuer.yingli.player.data.room.YingLiDatabase
import seeyuer.yingli.player.data.preferences.DataStoreMediaOnboardingRepository
import seeyuer.yingli.player.data.preferences.MediaOnboardingRepository
import seeyuer.yingli.player.data.sources.*
import seeyuer.yingli.player.engine.thumbnail.*
import seeyuer.yingli.player.engine.thumbnail.frame.Media3FrameThumbnailSource
import seeyuer.yingli.player.engine.thumbnail.system.ContentResolverThumbnailSource
import seeyuer.yingli.player.engine.thumbnail.system.ArtworkThumbnailSource
import seeyuer.yingli.player.data.library.RoomLibraryRepository
import seeyuer.yingli.player.data.library.DefaultLibraryMutationRepository
import seeyuer.yingli.player.data.library.RoomTrashRepository
import seeyuer.yingli.player.data.recycle.RecycleAuthorizationLauncher
import seeyuer.yingli.player.data.recycle.RecycleBinMaintenance
import seeyuer.yingli.player.data.filesystem.AndroidFileOperationGateway
import seeyuer.yingli.player.data.filesystem.AndroidMediaContentHasher
import seeyuer.yingli.player.data.home.AndroidDeviceStorageRepository
import seeyuer.yingli.player.data.home.DataStoreHomeLayoutRepository
import seeyuer.yingli.player.data.home.RoomHomeRepository
import seeyuer.yingli.player.domain.home.DeviceStorageRepository
import seeyuer.yingli.player.domain.home.HomeLayoutRepository
import seeyuer.yingli.player.domain.home.HomeRepository
import seeyuer.yingli.player.domain.catalog.DefaultMediaIdentityResolver
import seeyuer.yingli.player.domain.catalog.DefaultMediaScanner
import seeyuer.yingli.player.domain.catalog.MediaScanCoordinator
import seeyuer.yingli.player.domain.catalog.MediaCatalogRepository
import seeyuer.yingli.player.domain.catalog.MediaPermissionGateway
import seeyuer.yingli.player.domain.catalog.MediaScanner
import seeyuer.yingli.player.domain.catalog.MediaSourceRepository
import seeyuer.yingli.player.domain.thumbnail.ThumbnailLoader
import seeyuer.yingli.player.domain.playback.PlaybackProgressRepository
import seeyuer.yingli.player.domain.playback.PlaybackSourceRepository
import seeyuer.yingli.player.domain.playback.ElapsedTimeSource
import seeyuer.yingli.player.domain.playback.FrameCalibrationControl
import seeyuer.yingli.player.domain.playback.MutableSeekPrecisionControl
import seeyuer.yingli.player.domain.playback.SeekPrecisionControl
import seeyuer.yingli.player.engine.media3.frame.AndroidFrameCountProbe
import seeyuer.yingli.player.engine.media3.frame.LocalMediaFrameCounter
import seeyuer.yingli.player.domain.library.LibraryMutationRepository
import seeyuer.yingli.player.domain.library.LibraryPreferenceRepository
import seeyuer.yingli.player.domain.library.LibraryRepository
import seeyuer.yingli.player.domain.library.LibraryPagingRepository
import seeyuer.yingli.player.domain.library.SearchRepository
import seeyuer.yingli.player.domain.recycle.TrashRepository
import seeyuer.yingli.player.domain.recycle.RecycleQueue
import seeyuer.yingli.player.domain.recycle.TrashService
import seeyuer.yingli.player.domain.organize.HistoryRepository
import seeyuer.yingli.player.domain.organize.OrganizeRepository
import seeyuer.yingli.player.domain.playback.PlayerPreferenceRepository
import seeyuer.yingli.player.domain.playback.TrackPreferenceRepository
import seeyuer.yingli.player.domain.playback.PlaybackQueueRepository
import seeyuer.yingli.player.domain.playback.PlayerControlLayoutRepository
import seeyuer.yingli.player.domain.settings.BackupGateway
import seeyuer.yingli.player.domain.settings.DiagnosticsReporter
import seeyuer.yingli.player.domain.settings.SettingsDocumentGateway
import seeyuer.yingli.player.domain.settings.UpdateSource
import seeyuer.yingli.player.domain.processing.ProcessingArtifactStore
import seeyuer.yingli.player.domain.processing.ProcessingRepository
import seeyuer.yingli.player.domain.processing.ProcessingController
import seeyuer.yingli.player.domain.processing.SchedulerConditions
import seeyuer.yingli.player.domain.clips.ClipProjectRepository
import seeyuer.yingli.player.domain.clips.ClipExportQueue
import seeyuer.yingli.player.domain.clips.ClipFastExportProbe
import seeyuer.yingli.player.domain.clips.TimelineFrameProvider
import seeyuer.yingli.player.domain.processing.MediaCapabilityProbe
import seeyuer.yingli.player.domain.processing.PROCESSING_FREE_SPACE_RESERVE_BYTES
import seeyuer.yingli.player.domain.processing.ProcessingQueue
import seeyuer.yingli.player.domain.duplicates.DuplicateDeletionExecutor
import seeyuer.yingli.player.domain.duplicates.DuplicateRepository
import seeyuer.yingli.player.domain.duplicates.DuplicateScanner
import seeyuer.yingli.player.domain.duplicates.DuplicateScanQueue
import seeyuer.yingli.player.data.security.AppLockManager
import seeyuer.yingli.player.domain.security.SecurePlaybackSource
import seeyuer.yingli.player.domain.security.VaultRepository
import seeyuer.yingli.player.domain.shorts.ShortsPreferenceRepository
import seeyuer.yingli.player.data.preferences.DataStoreShortsPreferenceRepository
import seeyuer.yingli.player.data.organize.RoomOrganizeRepository
import seeyuer.yingli.player.data.preferences.DataStorePlayerPreferenceRepository
import seeyuer.yingli.player.data.preferences.DataStorePlayerControlLayoutRepository
import seeyuer.yingli.player.data.preferences.DataStoreLibraryPreferenceRepository
import seeyuer.yingli.player.data.preferences.DataStorePlaybackQueueRepository
import seeyuer.yingli.player.data.room.RoomPlaybackRepository

data class MediaContainer(
    val sourceRepository: MediaSourceRepository,
    val catalogRepository: MediaCatalogRepository,
    val permissionGateway: MediaPermissionGateway,
    val scanner: MediaScanner,
    val thumbnailRepository: ThumbnailLoader,
    val onboardingRepository: MediaOnboardingRepository,
    val playbackSourceRepository: PlaybackSourceRepository,
    val playbackProgressRepository: PlaybackProgressRepository,
    val libraryRepository: LibraryPagingRepository,
    val searchRepository: SearchRepository,
    val homeRepository: HomeRepository,
    val homeLayoutRepository: HomeLayoutRepository,
    val deviceStorageRepository: DeviceStorageRepository,
    val libraryPreferenceRepository: LibraryPreferenceRepository,
    val trashRepository: TrashRepository,
    val libraryMutationRepository: LibraryMutationRepository,
    val organizeRepository: OrganizeRepository,
    val historyRepository: HistoryRepository,
    val playerPreferenceRepository: PlayerPreferenceRepository,
    val trackPreferenceRepository: TrackPreferenceRepository,
    val playbackQueueRepository: PlaybackQueueRepository,
    val playerControlLayoutRepository: PlayerControlLayoutRepository,
    val backupGateway: BackupGateway,
    val diagnosticsReporter: DiagnosticsReporter,
    val settingsDocumentGateway: SettingsDocumentGateway,
    val updateSource: UpdateSource,
    val processingRepository: ProcessingRepository,
    val processingArtifactStore: ProcessingArtifactStore,
    val clipProjectRepository: ClipProjectRepository,
    val processingController: ProcessingController,
    val processingLifecycle: AutoCloseable,
    val clipExportQueue: ClipExportQueue,
    /**
     * 「这个源能不能用无损复制导出成 MP4」的判定（§14.6 步骤 13 / G11）。
     *
     * 放容器里而不是让播放页自己查：能力表只有一份（[seeyuer.yingli.player.data.processing.muxer.MuxerContainer]），
     * 谁能导出必须由**同一个真源**回答，否则播放页与切片引擎会对同一个文件给出不同答案。
     */
    val clipFastExportProbe: ClipFastExportProbe,
    val timelineFrameProvider: TimelineFrameProvider,
    val mediaCapabilityProbe: MediaCapabilityProbe,
    val processingQueue: ProcessingQueue,
    val duplicateRepository: DuplicateRepository,
    val duplicateScanner: DuplicateScanner,
    val duplicateDeletionExecutor: DuplicateDeletionExecutor,
    /** 去重扫描的入队入口（设计稿 §11.1：扫描必须经任务中心）。 */
    val duplicateScanQueue: DuplicateScanQueue,
    val appLockManager: AppLockManager,
    val vaultRepository: VaultRepository,
    val securePlaybackSource: SecurePlaybackSource,
    val shortsPreferenceRepository: ShortsPreferenceRepository,
    /**
     * 截图模式的跳转精度开关。放在容器里是因为要**跨宿主共享同一个实例**：
     * 写它的是播放页（Activity 进程里的 ViewModel），读它的是真正执行 seek 的 service 引擎
     *（见 ServicePlaybackEngine），两者必须在同一份状态上。
     */
    val seekPrecisionControl: SeekPrecisionControl,
    /** 帧号后台校准组件（MediaExtractor 统计视频 sample 数）；也同样要跨宿主共享。 */
    val frameCalibrationControl: FrameCalibrationControl,
    /**
     * 回收站业务入口（状态机 + 后端分派，§8.9）。**页面不得直接写 `trash_entries`**，
     * 一切处置动作都要经它，由它的状态机决定「现在允许把状态推进到哪一格」。
     */
    val trashService: TrashService,
    /** 批量回收站操作的入队入口（§11.3：批量与清空必须进任务中心）。 */
    val recycleQueue: RecycleQueue,
    /**
     * R1 的授权对话框需要 `PendingIntent`（Android 类型，不能进 domain 层）。
     * UI 拿到 [TrashService] 返回的 token，用它换出 `IntentSender` 再交给系统对话框。
     */
    val recycleAuthorizationLauncher: RecycleAuthorizationLauncher,
    /**
     * 启动/进前台时跑的回收站维护（先对账、再清理到期）。
     * 由宿主在 `onStart` 调 [RecycleBinMaintenance.start]（幂等：上一轮没跑完就不会重入）。
     */
    val recycleMaintenance: RecycleBinMaintenance,
) {
    /**
     * 容器级回收：进程/应用结束（`YingLiApplication.onTerminate`）或测试收尾时调用。
     *
     * 为什么由容器负责：这里的组件都是**跨宿主单例**（Activity 与 Service 共享同一个实例），
     * 它们的作用域不属于任何单个页面，页面销毁时不能收，只能由容器自己的生命周期收尾。
     */
    fun shutdown() {
        // 校准：取消在跑的扫描并回收它自己的作用域（在跑的 MediaExtractor 会被释放）。
        frameCalibrationControl.shutdown()
        // 回收站维护：取消这一轮对账/清理（下一轮会在下次进前台时重跑）。
        recycleMaintenance.close()
        // 处理队列调度器：停止观察任务并取消在跑的执行。
        processingLifecycle.close()
    }
}

object ProductionMediaContainerFactory {
    @OptIn(DelicateCoilApi::class)
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    fun create(context: Context, foundation: AppContainer): MediaContainer {
        val database = Room.databaseBuilder(context, YingLiDatabase::class.java, "yingli-media.db")
            .addMigrations(*YingLiDatabase.ALL_MIGRATIONS)
            .build()
        val sourceRepository = RoomMediaSourceRepository(database.mediaSourceDao())
        val catalogRepository = RoomMediaCatalogRepository(database)
        val permissionGateway = AndroidMediaPermissionGateway(context, foundation.dispatchers)
        val dataSources = setOf(
            MediaStoreDiscoveryDataSource(context, foundation.dispatchers),
            SafTreeDiscoveryDataSource(context, foundation.dispatchers),
        )
        val mediaContentHasher = AndroidMediaContentHasher(context, foundation.dispatchers)
        val scannerDelegate = DefaultMediaScanner(
            dataSources,
            sourceRepository,
            catalogRepository,
            DefaultMediaIdentityResolver,
            mediaContentHasher,
            foundation.idGenerator,
            foundation.clock,
        )
        val scanner = MediaScanCoordinator(
            delegate = scannerDelegate,
            sourceRepository = sourceRepository,
            clock = foundation.clock,
            dispatcher = foundation.dispatchers.io,
        )
        val thumbnailScope = CoroutineScope(SupervisorJob() + foundation.dispatchers.io)
        val thumbnailImageLoader = ImageLoader.Builder(context.applicationContext)
            .components { add(VideoFrameDecoder.Factory()) }
            .diskCache(
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("coil").absolutePath.toPath())
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            )
            .build()
        SingletonImageLoader.setUnsafe(thumbnailImageLoader)
        val playbackRepository = RoomPlaybackRepository(database)
        val libraryRepository = RoomLibraryRepository(database, foundation.dispatchers)
        val homeRepository = RoomHomeRepository(database.homeDao())
        val trashRepository = RoomTrashRepository(database)
        // 回收站：存储后端（R1/R2，R3 已删除）→ 互斥守卫 → 目录痕量 → 业务服务。
        // `trashRepository` 保留为**服务之下的仓储**：页面不再直接用它做写操作。
        val recycleStorage = seeyuer.yingli.player.data.recycle.AndroidRecycleBinStorage(
            context,
            foundation.dispatchers,
            foundation.idGenerator,
            foundation.clock,
            retentionDays = { foundation.themeRepository.settings.first().trashRetentionDays },
        )
        val mediaOperationGuard = seeyuer.yingli.player.data.recycle.RoomMediaOperationGuard(database.processingDao())
        val recycleCatalogGateway = seeyuer.yingli.player.data.recycle.RoomRecycleCatalogGateway(database)
        val trashService = seeyuer.yingli.player.data.recycle.DefaultTrashService(
            trashRepository,
            recycleStorage,
            recycleCatalogGateway,
            mediaOperationGuard,
            foundation.clock,
        )
        val mutationRepository = DefaultLibraryMutationRepository(trashService)
        val organizeRepository = RoomOrganizeRepository(database, foundation.clock, foundation.idGenerator)
        val duplicateRepository = seeyuer.yingli.player.data.duplicates.RoomDuplicateRepository(database)
        val duplicateScanner = seeyuer.yingli.player.data.duplicates.DefaultDuplicateScanner(
            database,
            mediaContentHasher,
            foundation.logger,
        )
        val duplicateMergeRepository =
            seeyuer.yingli.player.data.duplicates.RoomDuplicateMergeRepository(database, foundation.logger)
        val duplicateDeletionExecutor = seeyuer.yingli.player.data.duplicates.DefaultDuplicateDeletionExecutor(
            duplicateRepository,
            duplicateMergeRepository,
            libraryRepository,
            mutationRepository,
            mediaContentHasher,
        )
        val securityScope = CoroutineScope(SupervisorJob() + foundation.dispatchers.main)
        val appLockManager = seeyuer.yingli.player.data.security.AppLockManager(
            seeyuer.yingli.player.data.security.DataStoreAppLockRepository(context),
            seeyuer.yingli.player.core.security.Pbkdf2CredentialHasher(),
            foundation.clock,
            foundation.dispatchers,
            securityScope,
        )
        val vaultKeys = seeyuer.yingli.player.data.security.AndroidKeystoreKeyManagementGateway(
            context,
            foundation.dispatchers,
        )
        val vaultRepository = seeyuer.yingli.player.data.security.AndroidVaultRepository(
            context,
            database,
            vaultKeys,
            seeyuer.yingli.player.core.security.ChunkedAeadVaultCipher(),
            foundation.idGenerator,
            foundation.clock,
            foundation.dispatchers,
        )
        val playerPreferences = DataStorePlayerPreferenceRepository(context, foundation.themeRepository)
        val shortsPreferences = DataStoreShortsPreferenceRepository(context)
        val backupGateway = seeyuer.yingli.player.data.settings.RoomBackupGateway(
            database,
            foundation.themeRepository,
            foundation.clock,
        )
        val processingRepository = seeyuer.yingli.player.data.processing.RoomProcessingRepository(database)
        val artifactStore = seeyuer.yingli.player.data.processing.AndroidProcessingArtifactStore(
            context,
            foundation.dispatchers,
            foundation.idGenerator,
        )
        val clipRepository = seeyuer.yingli.player.data.processing.clips.RoomClipProjectRepository(database)
        val mediaCapabilityProbe = seeyuer.yingli.player.data.processing.transcode.AndroidMediaCapabilityProbe(
            context,
            foundation.dispatchers,
            foundation.clock,
        )
        val processingQueue = seeyuer.yingli.player.data.processing.transcode.TranscodeCoordinator(
            processingRepository,
            foundation.idGenerator,
            foundation.clock,
        )
        val outputVerifier = seeyuer.yingli.player.data.processing.transcode.MediaExtractorOutputVerifier(
            foundation.dispatchers,
        )
        // 一个引擎对象走遍全部四个产品入口：由 planner 的 `operation` 决定走哪条实现，
        // 调用方（切片执行器、压缩执行器）只交计划，不选引擎（设计稿 §6.2）。
        val processingEngine = seeyuer.yingli.player.data.processing.RoutingProcessingEngine(
            seeyuer.yingli.player.data.processing.InAppRemuxEngine(context, foundation.dispatchers, foundation.logger),
            seeyuer.yingli.player.data.processing.transcode.Media3ProcessingEngine(context, foundation.dispatchers),
        )
        val availableBytes = {
            runCatching { StatFs(context.cacheDir.absolutePath).availableBytes }.getOrDefault(0)
        }
        val clipExecutor = seeyuer.yingli.player.data.processing.clips.ClipProcessingExecutor(
            processingRepository,
            clipRepository,
            playbackRepository,
            mediaCapabilityProbe,
            processingEngine,
            outputVerifier,
            artifactStore,
            availableBytes = availableBytes,
        )
        val transcodeExecutor = seeyuer.yingli.player.data.processing.transcode.TranscodeProcessingExecutor(
            processingRepository,
            mediaCapabilityProbe,
            processingEngine,
            outputVerifier,
            artifactStore,
            availableBytes = availableBytes,
        )
        val processingScope = CoroutineScope(SupervisorJob() + foundation.dispatchers.main)
        val deduplicateExecutor = seeyuer.yingli.player.data.duplicates.DeduplicateProcessingExecutor(
            processingRepository,
            duplicateScanner,
        )
        val deduplicateCoordinator = seeyuer.yingli.player.data.duplicates.DeduplicateCoordinator(
            processingRepository,
            foundation.idGenerator,
            foundation.clock,
        )
        val recycleExecutor = seeyuer.yingli.player.data.recycle.RecycleProcessingExecutor(
            processingRepository,
            trashService,
            libraryRepository,
            foundation.clock,
        )
        val recycleQueue = seeyuer.yingli.player.data.recycle.RecycleCoordinator(
            processingRepository,
            foundation.idGenerator,
            foundation.clock,
        )
        val recycleMaintenance = seeyuer.yingli.player.data.recycle.RecycleBinMaintenance(
            processingScope,
            trashService,
            foundation.clock,
            foundation.logger,
        )
        recycleMaintenance.start()
        val scheduler = seeyuer.yingli.player.data.processing.InAppProcessingScheduler(
            processingScope,
            processingRepository,
            seeyuer.yingli.player.data.processing.RoutingProcessingExecutor(
                processingRepository,
                clipExecutor,
                transcodeExecutor,
                deduplicateExecutor,
                recycleExecutor,
            ),
            foundation.clock,
            foundation.logger,
            conditions = { context.schedulerConditions() },
            onActiveChanged = { active ->
                seeyuer.yingli.player.app.processing.YingLiProcessingService.setActive(context, active)
            },
        ).also { it.start() }
        val clipExportQueue = seeyuer.yingli.player.data.processing.clips.ClipExportCoordinator(
            processingRepository,
            clipRepository,
            foundation.idGenerator,
            foundation.clock,
        )
        // 「这个源能不能用无损复制导出成 MP4」（§14.6 步骤 13 / G11）：问的是**同一份**
        // 容器能力表（`MuxerContainer`），播放页只拿到一个是/否的答案，不抄任何 mime 白名单。
        val clipFastExportProbe = seeyuer.yingli.player.data.processing.clips.MuxerClipFastExportProbe(
            mediaCapabilityProbe,
        )
        val thumbnailExtractor = FallbackThumbnailExtractor(
            ArtworkThumbnailSource(context),
            FallbackThumbnailExtractor(
                ContentResolverThumbnailSource(context),
                Media3FrameThumbnailSource(context, foundation.dispatchers),
            ),
        )
        val thumbnailCache = LayeredThumbnailCache(
            memory = MemoryThumbnailCache(),
            disk = DiskThumbnailCache(context.cacheDir.resolve(ThumbnailStorage.DIRECTORY_NAME)),
        )
        // 帧数校准：MediaExtractor 只读容器、不解码（样本计数）。
        // **不传作用域**：组件自己持有并回收它（见 LocalMediaFrameCounter.shutdown），
        // 这样"谁持有、谁回收"没有歧义——容器关掉它，它关掉自己的作用域。
        val frameCalibrationControl = LocalMediaFrameCounter(
            probe = AndroidFrameCountProbe(context),
            dispatchers = foundation.dispatchers,
            logger = foundation.logger,
            elapsedTime = ElapsedTimeSource(android.os.SystemClock::elapsedRealtime),
        )
        return MediaContainer(
            sourceRepository,
            catalogRepository,
            permissionGateway,
            scanner,
            PriorityThumbnailRepository(thumbnailScope, thumbnailExtractor, cache = thumbnailCache),
            DataStoreMediaOnboardingRepository(context),
            playbackRepository,
            playbackRepository,
            libraryRepository,
            libraryRepository,
            homeRepository,
            DataStoreHomeLayoutRepository(context),
            AndroidDeviceStorageRepository(),
            DataStoreLibraryPreferenceRepository(foundation.themeRepository),
            trashRepository,
            mutationRepository,
            organizeRepository,
            organizeRepository,
            playerPreferences,
            playerPreferences,
            DataStorePlaybackQueueRepository(context),
            DataStorePlayerControlLayoutRepository(context),
            backupGateway,
            seeyuer.yingli.player.data.settings.LocalDiagnosticsReporter(
                context,
                foundation.diagnosticLogStore,
                foundation.dispatchers,
            ),
            seeyuer.yingli.player.data.settings.AndroidSettingsDocumentGateway(context, foundation.dispatchers),
            seeyuer.yingli.player.data.settings.GitHubReleaseUpdateSource(foundation.dispatchers, foundation.clock),
            processingRepository,
            artifactStore,
            clipRepository,
            scheduler,
            scheduler,
            clipExportQueue,
            clipFastExportProbe,
            seeyuer.yingli.player.data.processing.clips.AndroidTimelineFrameProvider(context, foundation.dispatchers),
            mediaCapabilityProbe,
            processingQueue,
            duplicateRepository,
            duplicateScanner,
            duplicateDeletionExecutor,
            deduplicateCoordinator,
            appLockManager,
            vaultRepository,
            vaultRepository,
            shortsPreferences,
            MutableSeekPrecisionControl(),
            frameCalibrationControl,
            trashService,
            recycleQueue,
            recycleStorage,
            recycleMaintenance,
        )
    }

    private fun Context.schedulerConditions(): SchedulerConditions {
        val capacity = getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?: -1
        val availableBytes = runCatching { StatFs(cacheDir.absolutePath).availableBytes }.getOrDefault(0)
        return SchedulerConditions(
            batteryLow = capacity in 0..LOW_BATTERY_PERCENT,
            // 与 planner 的预留量共用同一个常量：调度侧与 plan 侧各写一个值会互相矛盾。
            storageAvailable = availableBytes >= PROCESSING_FREE_SPACE_RESERVE_BYTES,
            // F24：前台服务配额用尽后不再启动新任务（见 YingLiProcessingService.onTimeout）。
            foregroundServiceUnavailable =
                seeyuer.yingli.player.app.processing.YingLiProcessingService.isForegroundTimeExhausted(),
        )
    }

    private const val LOW_BATTERY_PERCENT = 15
}
