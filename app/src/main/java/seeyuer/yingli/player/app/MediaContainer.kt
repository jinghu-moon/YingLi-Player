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
import seeyuer.yingli.player.domain.library.LibraryMutationRepository
import seeyuer.yingli.player.domain.library.LibraryPreferenceRepository
import seeyuer.yingli.player.domain.library.LibraryRepository
import seeyuer.yingli.player.domain.library.LibraryPagingRepository
import seeyuer.yingli.player.domain.library.SearchRepository
import seeyuer.yingli.player.domain.library.TrashRepository
import seeyuer.yingli.player.domain.organize.HistoryRepository
import seeyuer.yingli.player.domain.organize.OrganizeRepository
import seeyuer.yingli.player.domain.playback.PlayerPreferenceRepository
import seeyuer.yingli.player.domain.playback.TrackPreferenceRepository
import seeyuer.yingli.player.domain.playback.PlaybackQueueRepository
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
import seeyuer.yingli.player.domain.clips.TimelineFrameProvider
import seeyuer.yingli.player.domain.transcode.MediaCapabilityProbe
import seeyuer.yingli.player.domain.transcode.TranscodeQueue
import seeyuer.yingli.player.domain.duplicates.DuplicateDeletionExecutor
import seeyuer.yingli.player.domain.duplicates.DuplicateRepository
import seeyuer.yingli.player.domain.duplicates.DuplicateScanner
import seeyuer.yingli.player.data.security.AppLockManager
import seeyuer.yingli.player.domain.security.SecurePlaybackSource
import seeyuer.yingli.player.domain.security.VaultRepository
import seeyuer.yingli.player.data.organize.RoomOrganizeRepository
import seeyuer.yingli.player.data.preferences.DataStorePlayerPreferenceRepository
import seeyuer.yingli.player.data.preferences.DataStoreLibraryPreferenceRepository
import seeyuer.yingli.player.data.preferences.InMemoryPlaybackQueueRepository
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
    val timelineFrameProvider: TimelineFrameProvider,
    val mediaCapabilityProbe: MediaCapabilityProbe,
    val transcodeQueue: TranscodeQueue,
    val duplicateRepository: DuplicateRepository,
    val duplicateScanner: DuplicateScanner,
    val duplicateDeletionExecutor: DuplicateDeletionExecutor,
    val appLockManager: AppLockManager,
    val vaultRepository: VaultRepository,
    val securePlaybackSource: SecurePlaybackSource,
)

object ProductionMediaContainerFactory {
    @OptIn(DelicateCoilApi::class)
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    fun create(context: Context, foundation: AppContainer): MediaContainer {
        val database = Room.databaseBuilder(context, YingLiDatabase::class.java, "yingli-media.db")
            .addMigrations(
                YingLiDatabase.MIGRATION_1_2,
                YingLiDatabase.MIGRATION_2_3,
                YingLiDatabase.MIGRATION_3_4,
                YingLiDatabase.MIGRATION_4_5,
                YingLiDatabase.MIGRATION_5_6,
                YingLiDatabase.MIGRATION_6_7,
                YingLiDatabase.MIGRATION_7_8,
            )
            .build()
        val sourceRepository = RoomMediaSourceRepository(database.mediaSourceDao())
        val catalogRepository = RoomMediaCatalogRepository(database)
        val permissionGateway = AndroidMediaPermissionGateway(context, foundation.dispatchers)
        val dataSources = setOf(
            MediaStoreDiscoveryDataSource(context, foundation.dispatchers),
            SafTreeDiscoveryDataSource(context, foundation.dispatchers),
        )
        val scannerDelegate = DefaultMediaScanner(
            dataSources,
            sourceRepository,
            catalogRepository,
            DefaultMediaIdentityResolver,
            AndroidMediaContentHasher(context, foundation.dispatchers),
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
        val mutationRepository = DefaultLibraryMutationRepository(
            AndroidFileOperationGateway(context, foundation.dispatchers),
            trashRepository,
            foundation.clock,
            retentionDays = { foundation.themeRepository.settings.first().trashRetentionDays },
        )
        val organizeRepository = RoomOrganizeRepository(database, foundation.clock, foundation.idGenerator)
        val duplicateRepository = seeyuer.yingli.player.data.duplicates.RoomDuplicateRepository(database)
        val fingerprintGenerator = seeyuer.yingli.player.data.duplicates.AndroidDuplicateFingerprintGenerator(
            context,
            foundation.dispatchers,
        )
        val duplicateScanner = seeyuer.yingli.player.data.duplicates.DefaultDuplicateScanner(
            libraryRepository,
            duplicateRepository,
            fingerprintGenerator,
            foundation.clock,
        )
        val duplicateDeletionExecutor = seeyuer.yingli.player.data.duplicates.DefaultDuplicateDeletionExecutor(
            duplicateRepository,
            libraryRepository,
            mutationRepository,
            fingerprintGenerator,
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
        val clipEngine = seeyuer.yingli.player.data.processing.clips.PlatformClipEngine(context, foundation.dispatchers)
        val mediaCapabilityProbe = seeyuer.yingli.player.data.processing.transcode.AndroidMediaCapabilityProbe(
            context,
            foundation.dispatchers,
            foundation.clock,
        )
        val transcodeQueue = seeyuer.yingli.player.data.processing.transcode.TranscodeCoordinator(
            processingRepository,
            foundation.idGenerator,
            foundation.clock,
        )
        val clipExecutor = seeyuer.yingli.player.data.processing.clips.ClipProcessingExecutor(
            processingRepository,
            clipRepository,
            playbackRepository,
            clipEngine,
            artifactStore,
        )
        val transcodeExecutor = seeyuer.yingli.player.data.processing.transcode.TranscodeProcessingExecutor(
            processingRepository,
            mediaCapabilityProbe,
            seeyuer.yingli.player.data.processing.transcode.Media3TranscodeEngine(context, foundation.dispatchers),
            seeyuer.yingli.player.data.processing.transcode.MediaExtractorOutputVerifier(foundation.dispatchers),
            artifactStore,
            availableBytes = { runCatching { StatFs(context.cacheDir.absolutePath).availableBytes }.getOrDefault(0) },
        )
        val processingScope = CoroutineScope(SupervisorJob() + foundation.dispatchers.main)
        val scheduler = seeyuer.yingli.player.data.processing.InAppProcessingScheduler(
            processingScope,
            processingRepository,
            seeyuer.yingli.player.data.processing.RoutingProcessingExecutor(
                processingRepository,
                clipExecutor,
                transcodeExecutor,
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
            foundation.idGenerator,
            foundation.clock,
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
            InMemoryPlaybackQueueRepository(),
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
            seeyuer.yingli.player.data.processing.clips.AndroidTimelineFrameProvider(context, foundation.dispatchers),
            mediaCapabilityProbe,
            transcodeQueue,
            duplicateRepository,
            duplicateScanner,
            duplicateDeletionExecutor,
            appLockManager,
            vaultRepository,
            vaultRepository,
        )
    }

    private fun Context.schedulerConditions(): SchedulerConditions {
        val capacity = getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?: -1
        val availableBytes = runCatching { StatFs(cacheDir.absolutePath).availableBytes }.getOrDefault(0)
        return SchedulerConditions(
            batteryLow = capacity in 0..LOW_BATTERY_PERCENT,
            storageAvailable = availableBytes >= MINIMUM_FREE_BYTES,
        )
    }

    private const val LOW_BATTERY_PERCENT = 15
    private const val MINIMUM_FREE_BYTES = 256L * 1024 * 1024
}
