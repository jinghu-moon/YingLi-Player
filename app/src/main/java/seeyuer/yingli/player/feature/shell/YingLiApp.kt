package seeyuer.yingli.player.feature.shell

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedContent

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import seeyuer.yingli.player.R
import seeyuer.yingli.player.data.preferences.AppearanceSettings
import seeyuer.yingli.player.core.designsystem.component.YingLiEmptyState
import seeyuer.yingli.player.core.designsystem.component.YingLiDropdownMenu
import seeyuer.yingli.player.core.designsystem.component.YingLiDropdownMenuItem
import seeyuer.yingli.player.core.designsystem.component.YingLiIconButton
import seeyuer.yingli.player.core.designsystem.component.YingLiTopBar
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.icon.imageVector
import seeyuer.yingli.player.core.designsystem.theme.YingLiSystemBars
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.core.designsystem.tokens.SystemBarMode
import seeyuer.yingli.player.domain.navigation.AppRoute
import seeyuer.yingli.player.domain.navigation.GlobalAppAction
import seeyuer.yingli.player.domain.navigation.NavigationState
import seeyuer.yingli.player.domain.navigation.RootDestination
import seeyuer.yingli.player.domain.playback.PlaybackSourceContext
import seeyuer.yingli.player.domain.playback.PlaybackQueueSource
import seeyuer.yingli.player.domain.playback.PlaybackRecoveryAction
import seeyuer.yingli.player.domain.playback.PlaybackState
import seeyuer.yingli.player.domain.playback.PlayerPreferences
import seeyuer.yingli.player.feature.home.HomeRoute
import seeyuer.yingli.player.feature.home.HomeViewModel
import seeyuer.yingli.player.feature.library.LibraryRoute
import seeyuer.yingli.player.feature.library.LibraryViewModel
import seeyuer.yingli.player.feature.library.MediaLibraryEffect
import seeyuer.yingli.player.feature.library.MediaLibraryUiState
import seeyuer.yingli.player.feature.library.MediaLibraryViewModel
import seeyuer.yingli.player.feature.organize.OrganizeRoute
import seeyuer.yingli.player.feature.organize.OrganizeViewModel
import seeyuer.yingli.player.feature.processing.ProcessingRoute
import seeyuer.yingli.player.feature.processing.ProcessingViewModel
import seeyuer.yingli.player.feature.player.PlayerGestureCallbacks
import seeyuer.yingli.player.feature.player.PlayerScreen
import seeyuer.yingli.player.feature.player.PlayerViewModel
import seeyuer.yingli.player.feature.player.PlayerUiEvent
import seeyuer.yingli.player.feature.player.MiniPlayerBar
import seeyuer.yingli.player.feature.settings.SettingsScreen
import seeyuer.yingli.player.feature.settings.SettingsEffect
import seeyuer.yingli.player.feature.settings.SettingsToolActions
import seeyuer.yingli.player.feature.settings.SettingsViewModel
import seeyuer.yingli.player.feature.settings.SecuritySettingsActions
import seeyuer.yingli.player.feature.security.AppLockRoute
import seeyuer.yingli.player.feature.security.SecurityViewModel
import seeyuer.yingli.player.feature.security.VaultRoute
import seeyuer.yingli.player.feature.security.VaultViewModel
import seeyuer.yingli.player.feature.shorts.ShortsViewModel
import seeyuer.yingli.player.feature.shorts.ShortsCandidate
import seeyuer.yingli.player.domain.security.VaultItemId
import seeyuer.yingli.player.domain.thumbnail.ThumbnailLoader

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun YingLiApp(
    viewModel: YingLiAppViewModel,
    mediaLibraryViewModel: MediaLibraryViewModel,
    playerViewModel: PlayerViewModel,
    libraryViewModel: LibraryViewModel,
    organizeViewModel: OrganizeViewModel,
    homeViewModel: HomeViewModel,
    settingsViewModel: SettingsViewModel,
    processingViewModel: ProcessingViewModel,
    securityViewModel: SecurityViewModel,
    vaultViewModel: VaultViewModel,
    shortsViewModel: ShortsViewModel,
    windowWidthSizeClass: WindowWidthSizeClass,
    videoSurface: @Composable () -> Unit,
    /**
     * 常规/Vault 播放页的视频输出。参数 `transformed` 表示当前画面会被视图层级变换（画面旋转），
     * 调用方需要据此选择可被变换的输出类型；Shorts 等不旋转的页面继续用 [videoSurface]。
     */
    playerVideoSurface: @Composable (transformed: Boolean) -> Unit = { videoSurface() },
    onSecureContentChanged: (Boolean) -> Unit = {},
    onSecureSessionLocked: () -> Unit = {},
    biometricAvailable: Boolean = false,
    onBiometricUnlock: () -> Unit = {},
    thumbnailRepository: ThumbnailLoader? = null,
) {
    val settings by viewModel.appearanceSettings.collectAsStateWithLifecycle()
    val securityState by securityViewModel.state.collectAsStateWithLifecycle()
    val mediaState by mediaLibraryViewModel.state.collectAsStateWithLifecycle()
    val darkTheme = false
    val context = LocalContext.current
    val allFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        mediaLibraryViewModel.onAllFilesSettingsReturned()
    }
    val mediaReadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        mediaLibraryViewModel.onMediaReadPermissionResult(grants.values.any { it })
    }
    val safLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        mediaLibraryViewModel.onSafTreeSelected(uri?.toString())
    }
    val backupCreateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        settingsViewModel.onDocumentCreated(it?.toString())
    }
    val diagnosticsCreateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        settingsViewModel.onDocumentCreated(it?.toString())
    }
    val backupOpenLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        settingsViewModel.onRestoreDocumentSelected(it?.toString())
    }
    val vaultImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        vaultViewModel.import(uri?.toString())
    }
    var pendingVaultExport by remember { mutableStateOf<VaultItemId?>(null) }
    val vaultExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        pendingVaultExport?.let { itemId -> vaultViewModel.export(itemId, uri?.toString()) }
        pendingVaultExport = null
    }
    LaunchedEffect(mediaLibraryViewModel) {
        mediaLibraryViewModel.initialize()
        mediaLibraryViewModel.effect.collect { effect ->
            when (effect) {
                MediaLibraryEffect.OpenAllFilesSettings -> allFilesLauncher.launch(Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ))
                MediaLibraryEffect.RequestMediaReadPermission -> mediaReadLauncher.launch(mediaReadPermissions())
                MediaLibraryEffect.OpenSafTreePicker -> safLauncher.launch(null)
            }
        }
    }
    LaunchedEffect(settingsViewModel) {
        settingsViewModel.effect.collect { effect ->
            when (effect) {
                is SettingsEffect.CreateDocument -> if (effect.mimeType == "application/json") {
                    backupCreateLauncher.launch(effect.displayName)
                } else {
                    diagnosticsCreateLauncher.launch(effect.displayName)
                }
                SettingsEffect.OpenBackupDocument -> backupOpenLauncher.launch(arrayOf("application/json", "text/json"))
            }
        }
    }

    if (securityState.locked) {
        YingLiTheme(darkTheme = darkTheme) {
            SideEffect {
                onSecureContentChanged(true)
                onSecureSessionLocked()
            }
            YingLiSystemBars(if (darkTheme) SystemBarMode.DARK_APP else SystemBarMode.LIGHT_APP)
            AppLockRoute(securityViewModel, onBiometric = onBiometricUnlock)
        }
        return
    }

        YingLiTheme(darkTheme = darkTheme) {
            val navigationState by viewModel.navigationState.collectAsStateWithLifecycle()
        val playerState by playerViewModel.state.collectAsStateWithLifecycle()
        val playlistItems = playerViewModel.playlistPagingData.collectAsLazyPagingItems()
        var playerTransientMessage by remember { mutableStateOf<PlayerUiEvent.TransientMessage?>(null) }
        LaunchedEffect(playerViewModel) {
            playerViewModel.event.collect { event ->
                if (event is PlayerUiEvent.TransientMessage) playerTransientMessage = event
            }
        }
        val settingsToolsState by settingsViewModel.state.collectAsStateWithLifecycle()
        SideEffect {
            onSecureContentChanged(
                navigationState.currentRoute == AppRoute.Vault || navigationState.currentRoute is AppRoute.VaultPlayer,
            )
        }
        val systemBarMode = when {
                navigationState.currentRoute is AppRoute.Player || navigationState.currentRoute is AppRoute.VaultPlayer ||
                    navigationState.currentRoute == AppRoute.Root(RootDestination.SHORTS) ->
                SystemBarMode.PLAYER
            darkTheme -> SystemBarMode.DARK_APP
            else -> SystemBarMode.LIGHT_APP
        }
        YingLiSystemBars(systemBarMode)
        // Global route stack first; fall back to Home tab instead of finishing the
        // activity when the user is on another root destination. Home root leaves
        // the handler disabled so the system can background/close the task.
        BackHandler(
            enabled = navigationState.canNavigateBack ||
                navigationState.currentRoot != RootDestination.HOME,
        ) {
            when {
                navigationState.canNavigateBack -> viewModel.navigateBack()
                else -> viewModel.selectRoot(RootDestination.HOME)
            }
        }
        AdaptiveAppShell(
            navigationState = navigationState,
            settings = settings,
            playerPreferences = playerState.preferences,
            windowWidthSizeClass = windowWidthSizeClass,
            onRootSelected = viewModel::selectRoot,
            onGlobalAction = viewModel::openGlobalAction,
            onBack = viewModel::navigateBack,
            onMiniPlayerChanged = playerViewModel::setMiniPlayerEnabled,
            onAutoPipChanged = playerViewModel::setAutoPictureInPicture,
            settingsTools = SettingsToolActions(
                onGestureSeekEnabled = playerViewModel::setGestureSeekEnabled,
                onGestureVolumeEnabled = playerViewModel::setGestureVolumeEnabled,
                onGestureBrightnessEnabled = playerViewModel::setGestureBrightnessEnabled,
                onGestureZoomEnabled = playerViewModel::setGestureZoomEnabled,
                onGestureLeftSideIsVolume = playerViewModel::setGestureLeftSideIsVolume,
                onGestureDoubleTapSeekMillis = playerViewModel::setGestureDoubleTapSeekMillis,
                onGestureSwipeDownToExitEnabled = playerViewModel::setGestureSwipeDownToExitEnabled,
                onGestureLongPressSpeed = playerViewModel::setGestureLongPressSpeed,
                state = settingsToolsState,
                mediaSources = mediaState.sources,
                allFilesAccess = mediaState.allFilesAccess,
                onSourceIncludeHiddenChanged = mediaLibraryViewModel::setSourceIncludeHidden,
                onSourceIncludeNomediaChanged = mediaLibraryViewModel::setIncludeNomedia,
                onLibraryLayoutChanged = viewModel::setLibraryLayout,
                onThumbnailScaleChanged = viewModel::setThumbnailScale,
                onTrashRetentionDaysChanged = viewModel::setTrashRetentionDays,
                onBackupSelectionChanged = settingsViewModel::setSelection,
                onBackup = settingsViewModel::requestBackup,
                onRestore = settingsViewModel::requestRestore,
                onConfirmRestore = settingsViewModel::confirmRestore,
                onDismissRestore = settingsViewModel::dismissRestorePreview,
                onDiagnostics = settingsViewModel::requestDiagnostics,
                onCheckUpdates = settingsViewModel::checkForUpdates,
                onOpenRelease = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
            ),
            securitySettings = SecuritySettingsActions(
                lockEnabled = securityState.enabled,
                statusCode = securityState.statusCode,
                onEnablePin = securityViewModel::enablePin,
                onDisableLock = securityViewModel::disable,
                biometricAvailable = biometricAvailable,
                onEnableBiometric = securityViewModel::enableBiometric,
                onOpenVault = viewModel::openVault,
            ),
            processingViewModel = processingViewModel,
            vaultViewModel = vaultViewModel,
            shortsViewModel = shortsViewModel,
            onVaultImport = { vaultImportLauncher.launch(arrayOf("video/*")) },
            onVaultPlay = { viewModel.openVaultPlayer(it.value) },
            onVaultExport = { itemId ->
                pendingVaultExport = itemId
                vaultExportLauncher.launch("YingLi-vault-export.mp4")
            },
            onOpenProcessingOutput = { token ->
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(token), "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            },
            mediaState = mediaState,
            libraryViewModel = libraryViewModel,
            organizeViewModel = organizeViewModel,
            homeViewModel = homeViewModel,
            onRecommendedSource = mediaLibraryViewModel::addRecommendedSource,
            onSafSource = mediaLibraryViewModel::addSafSource,
            onSkipMediaOnboarding = mediaLibraryViewModel::skipOnboarding,
            onRescan = mediaLibraryViewModel::rescan,
            onMediaSelected = viewModel::openPlayer,
            thumbnailRepository = thumbnailRepository,
            videoSurface = videoSurface,
            onShareShorts = { candidate ->
                context.startActivity(Intent(Intent.ACTION_SEND).apply {
                    type = "video/*"
                    putExtra(Intent.EXTRA_TEXT, candidate.title)
                    candidate.uri?.value?.let { putExtra(Intent.EXTRA_STREAM, Uri.parse(it)) }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            },
            playerContent = { route, onBack ->
                LaunchedEffect(route.mediaId, route.queueSource) {
                    playerViewModel.open(route.mediaId, route.source.toPlaybackSourceContext(), route.queueSource)
                }
                // 离开播放页必须还原系统栏与方向，否则全屏会泄漏到其他页面。
                DisposableEffect(Unit) {
                    onDispose(playerViewModel::exitFullscreen)
                }
                PlayerScreen(
                    state = playerState,
                    onBack = onBack,
                    onPlay = { playerViewModel.play() },
                    onPause = { playerViewModel.pause() },
                    onSeek = playerViewModel::seekTo,
                    onReplay = { playerViewModel.replay() },
                    onRetry = { playerViewModel.retry() },
                    onRecovery = { action ->
                        when (action) {
                            PlaybackRecoveryAction.REAUTHORIZE -> {
                                onBack()
                                mediaLibraryViewModel.addRecommendedSource()
                            }
                            PlaybackRecoveryAction.RELOCATE -> {
                                onBack()
                                mediaLibraryViewModel.rescan()
                            }
                            PlaybackRecoveryAction.VIEW_COMPATIBILITY,
                            PlaybackRecoveryAction.RETRY,
                            -> onBack()
                        }
                    },
                    videoSurface = playerVideoSurface,
                    onPrevious = playerViewModel::previous,
                    onNext = playerViewModel::next,
                    canNavigatePrevious = playerState.queue?.let { it.mediaIds.size > 1 && it.currentIndex > 0 } == true,
                    canNavigateNext = playerState.queue?.let { it.currentIndex < it.mediaIds.lastIndex } == true,
                    onSeekBackward = { playerViewModel.seekBackward() },
                    onSeekForward = { playerViewModel.seekForward() },
                    onToggleOverlay = playerViewModel::toggleOverlay,
                    onToggleLock = playerViewModel::toggleLock,
                    onSetSpeed = { playerViewModel.setSpeed(it) },
                    onSetScaleMode = { playerViewModel.setScaleMode(it) },
                    onSelectAudioTrack = { playerViewModel.selectAudioTrack(it) },
                    onSelectSubtitleTrack = { playerViewModel.selectSubtitleTrack(it) },
                    onSetPlaybackOrder = { playerViewModel.setPlaybackOrder(it) },
                    onRotateVideo = playerViewModel::rotateVideo,
                    onToggleSpeedPanel = playerViewModel::toggleSpeedPanel,
                    onCycleScaleMode = { playerViewModel.cycleScaleMode() },
                    onGestureHintShown = playerViewModel::markGestureHintShown,
                    gestureCallbacks = PlayerGestureCallbacks(
                        onVolumeBegin = playerViewModel::beginVolumeGesture,
                        onVolume = playerViewModel::applyVolumeGesture,
                        onVolumeEnd = playerViewModel::endVolumeGesture,
                        onBrightnessBegin = playerViewModel::beginBrightnessGesture,
                        onBrightness = playerViewModel::applyBrightnessGesture,
                        onBrightnessEnd = playerViewModel::endBrightnessGesture,
                        onSeekBegin = playerViewModel::beginSeekGesture,
                        onSeekPreview = playerViewModel::applySeekGesture,
                        onSeekEnd = playerViewModel::endSeekGesture,
                        onLongPressSpeedBegin = playerViewModel::beginTemporarySpeed,
                        onLongPressSpeedEnd = playerViewModel::endTemporarySpeed,
                        onZoomBegin = playerViewModel::captureZoomAnchor,
                        onZoom = playerViewModel::applyZoomGesture,
                        onPanZoom = playerViewModel::panZoom,
                        onResetZoom = playerViewModel::resetZoom,
                        onToggleAutoBrightness = playerViewModel::toggleAutoBrightness,
                    ),
                    onSetRotation = playerViewModel::setRotation,
                    onSetControlLayout = playerViewModel::setControlLayout,
                    onPictureInPicture = { playerViewModel.enterPictureInPicture() },
                    onScreenshot = playerViewModel::armScreenshot,
                    onCaptureScreenshot = playerViewModel::captureScreenshot,
                    onPreviousScreenshotFrame = { playerViewModel.stepScreenshotFrame(false) },
                    onNextScreenshotFrame = { playerViewModel.stepScreenshotFrame(true) },
                    onToggleScreenshotPreview = playerViewModel::toggleScreenshotExpiry,
                    onCloseScreenshot = playerViewModel::closeScreenshot,
                    onDeleteScreenshot = playerViewModel::deleteScreenshot,
                    onOpenAbTool = playerViewModel::openAbTool,
                    onSetAbPoint = { playerViewModel.setAbPoint(it) },
                    onClearAb = playerViewModel::clearAb,
                    onCloseAbTool = playerViewModel::closeAbTool,
                    onToggleFullscreen = playerViewModel::toggleFullscreen,
                    onExitFullscreen = playerViewModel::exitFullscreen,
                    onOpenPanel = playerViewModel::openPanel,
                    onClosePanel = playerViewModel::closePanel,
                    onOpenPlaylist = { playerViewModel.openPanel(seeyuer.yingli.player.domain.playback.PlayerPanel.PLAYLIST) },
                    onSelectPlaylistItem = playerViewModel::selectQueueItem,
                    playlistItems = playlistItems,
                    thumbnailRepository = thumbnailRepository,
                    transientMessage = playerTransientMessage,
                    onTransientMessageConsumed = { playerTransientMessage = null },
                )
            },
            vaultPlayerContent = { route, onBack ->
                val vaultTitle = stringResource(R.string.vault_title)
                LaunchedEffect(route.itemId) { playerViewModel.openVault(route.itemId, vaultTitle) }
                DisposableEffect(route.itemId) {
                    onDispose(playerViewModel::closeVault)
                }
                DisposableEffect(Unit) {
                    onDispose(playerViewModel::exitFullscreen)
                }
                PlayerScreen(
                    state = playerState,
                    onBack = onBack,
                    onPlay = { playerViewModel.play() },
                    onPause = { playerViewModel.pause() },
                    onSeek = playerViewModel::seekTo,
                    onReplay = { playerViewModel.replay() },
                    onRetry = { playerViewModel.retry() },
                    onRecovery = { onBack() },
                    videoSurface = playerVideoSurface,
                    onSeekBackward = { playerViewModel.seekBackward() },
                    onSeekForward = { playerViewModel.seekForward() },
                    onToggleOverlay = playerViewModel::toggleOverlay,
                    onToggleLock = playerViewModel::toggleLock,
                    onSetSpeed = { playerViewModel.setSpeed(it) },
                    onSetScaleMode = { playerViewModel.setScaleMode(it) },
                    onSelectAudioTrack = { playerViewModel.selectAudioTrack(it) },
                    onSelectSubtitleTrack = { playerViewModel.selectSubtitleTrack(it) },
                    onSetPlaybackOrder = { playerViewModel.setPlaybackOrder(it) },
                    onRotateVideo = playerViewModel::rotateVideo,
                    onToggleSpeedPanel = playerViewModel::toggleSpeedPanel,
                    onCycleScaleMode = { playerViewModel.cycleScaleMode() },
                    onGestureHintShown = playerViewModel::markGestureHintShown,
                    gestureCallbacks = PlayerGestureCallbacks(
                        onVolumeBegin = playerViewModel::beginVolumeGesture,
                        onVolume = playerViewModel::applyVolumeGesture,
                        onVolumeEnd = playerViewModel::endVolumeGesture,
                        onBrightnessBegin = playerViewModel::beginBrightnessGesture,
                        onBrightness = playerViewModel::applyBrightnessGesture,
                        onBrightnessEnd = playerViewModel::endBrightnessGesture,
                        onSeekBegin = playerViewModel::beginSeekGesture,
                        onSeekPreview = playerViewModel::applySeekGesture,
                        onSeekEnd = playerViewModel::endSeekGesture,
                        onLongPressSpeedBegin = playerViewModel::beginTemporarySpeed,
                        onLongPressSpeedEnd = playerViewModel::endTemporarySpeed,
                        onZoomBegin = playerViewModel::captureZoomAnchor,
                        onZoom = playerViewModel::applyZoomGesture,
                        onPanZoom = playerViewModel::panZoom,
                        onResetZoom = playerViewModel::resetZoom,
                        onToggleAutoBrightness = playerViewModel::toggleAutoBrightness,
                    ),
                    onSetRotation = playerViewModel::setRotation,
                    onSetControlLayout = playerViewModel::setControlLayout,
                    onToggleFullscreen = playerViewModel::toggleFullscreen,
                    onExitFullscreen = playerViewModel::exitFullscreen,
                    onOpenPanel = playerViewModel::openPanel,
                    onClosePanel = playerViewModel::closePanel,
                    allowPictureInPicture = false,
                    allowScreenshot = false,
                    transientMessage = playerTransientMessage,
                    onTransientMessageConsumed = { playerTransientMessage = null },
                )
            },
            miniPlayerContent = {
                val mediaId = playerState.playback.request?.mediaId?.value
                if (mediaId != null &&
                    playerState.playback !is PlaybackState.Idle &&
                    playerState.playback !is PlaybackState.Failed &&
                    playerState.preferences.miniPlayerEnabled &&
                    navigationState.currentRoute !is AppRoute.Player
                    && navigationState.currentRoute !is AppRoute.VaultPlayer
                    && navigationState.currentRoute != AppRoute.Vault
                ) {
                    MiniPlayerBar(
                        state = playerState,
                        onOpen = { viewModel.openPlayer(mediaId) },
                        onPlay = { playerViewModel.play() },
                        onPause = { playerViewModel.pause() },
                    )
                }
            },
        )
    }
}

private fun mediaReadPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
internal fun AdaptiveAppShell(
    navigationState: NavigationState,
    settings: AppearanceSettings,
    playerPreferences: PlayerPreferences = PlayerPreferences(),
    windowWidthSizeClass: WindowWidthSizeClass,
    onRootSelected: (RootDestination) -> Unit,
    onGlobalAction: (GlobalAppAction) -> Unit,
    onBack: () -> Unit,
    onMiniPlayerChanged: (Boolean) -> Unit = {},
    onAutoPipChanged: (Boolean) -> Unit = {},
    settingsTools: SettingsToolActions = SettingsToolActions(),
    securitySettings: SecuritySettingsActions = SecuritySettingsActions(),
    processingViewModel: ProcessingViewModel? = null,
    vaultViewModel: VaultViewModel? = null,
    shortsViewModel: ShortsViewModel? = null,
    onVaultImport: () -> Unit = {},
    onVaultPlay: (VaultItemId) -> Unit = {},
    onVaultExport: (VaultItemId) -> Unit = {},
    onOpenProcessingOutput: (String) -> Unit = {},
    mediaState: MediaLibraryUiState = MediaLibraryUiState(onboarding = false),
    libraryViewModel: LibraryViewModel? = null,
    organizeViewModel: OrganizeViewModel? = null,
    homeViewModel: HomeViewModel? = null,
    onRecommendedSource: () -> Unit = {},
    onSafSource: () -> Unit = {},
    onSkipMediaOnboarding: () -> Unit = {},
    onRescan: () -> Unit = {},
    onMediaSelected: (String, PlaybackQueueSource?) -> Unit = { _, _ -> },
    thumbnailRepository: ThumbnailLoader? = null,
    videoSurface: @Composable () -> Unit = {},
    onShareShorts: (ShortsCandidate) -> Unit = {},
    playerContent: @Composable (AppRoute.Player, () -> Unit) -> Unit = { _, onBack ->
        FullScreenPlayerPlaceholder(onBack)
    },
    vaultPlayerContent: @Composable (AppRoute.VaultPlayer, () -> Unit) -> Unit = { _, onBack ->
        FullScreenPlayerPlaceholder(onBack)
    },
    miniPlayerContent: @Composable () -> Unit = {},
) {
    val mediaOnboarding = navigationState.currentRoute == AppRoute.Root(RootDestination.HOME) && mediaState.onboarding
    val usesNavigationRail = !mediaOnboarding && windowWidthSizeClass != WindowWidthSizeClass.Compact
    val destinations = navigationState.primaryDestinations

    if (navigationState.currentRoute == AppRoute.Root(RootDestination.SHORTS) && shortsViewModel != null) {
        seeyuer.yingli.player.feature.shorts.ShortsRoute(
            viewModel = shortsViewModel,
            videoSurface = videoSurface,
            onBack = onBack,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    if (!navigationState.showPrimaryNavigation) {
        FullScreenRoute(navigationState.currentRoute, onBack, playerContent, vaultPlayerContent)
        return
    }

    if (usesNavigationRail) {
        Row(Modifier.fillMaxSize()) {
            PrimaryNavigationRail(
                destinations = destinations,
                selected = navigationState.currentRoot,
                onSelected = onRootSelected,
            )
            AppScaffold(
                navigationState = navigationState,
                settings = settings,
                playerPreferences = playerPreferences,
                onGlobalAction = onGlobalAction,
                onRootSelected = onRootSelected,
                onBack = onBack,
                onMiniPlayerChanged = onMiniPlayerChanged,
                onAutoPipChanged = onAutoPipChanged,
                settingsTools = settingsTools,
                securitySettings = securitySettings,
                processingViewModel = processingViewModel,
                vaultViewModel = vaultViewModel,
                shortsViewModel = shortsViewModel,
                onVaultImport = onVaultImport,
                onVaultPlay = onVaultPlay,
                onVaultExport = onVaultExport,
                onOpenProcessingOutput = onOpenProcessingOutput,
                mediaState = mediaState,
                libraryViewModel = libraryViewModel,
                organizeViewModel = organizeViewModel,
                homeViewModel = homeViewModel,
                libraryIsWide = usesNavigationRail,
                onRecommendedSource = onRecommendedSource,
                onSafSource = onSafSource,
                onSkipMediaOnboarding = onSkipMediaOnboarding,
                onRescan = onRescan,
                onMediaSelected = onMediaSelected,
                thumbnailRepository = thumbnailRepository,
                modifier = Modifier.weight(1f),
                bottomBar = miniPlayerContent,
                videoSurface = videoSurface,
                onShareShorts = onShareShorts,
            )
        }
    } else {
        AppScaffold(
            navigationState = navigationState,
            settings = settings,
            playerPreferences = playerPreferences,
            onGlobalAction = onGlobalAction,
            onRootSelected = onRootSelected,
            onBack = onBack,
            onMiniPlayerChanged = onMiniPlayerChanged,
            onAutoPipChanged = onAutoPipChanged,
            settingsTools = settingsTools,
            securitySettings = securitySettings,
            processingViewModel = processingViewModel,
            vaultViewModel = vaultViewModel,
            shortsViewModel = shortsViewModel,
            onVaultImport = onVaultImport,
            onVaultPlay = onVaultPlay,
            onVaultExport = onVaultExport,
            onOpenProcessingOutput = onOpenProcessingOutput,
            mediaState = mediaState,
            libraryViewModel = libraryViewModel,
            organizeViewModel = organizeViewModel,
            homeViewModel = homeViewModel,
            libraryIsWide = usesNavigationRail,
            onRecommendedSource = onRecommendedSource,
            onSafSource = onSafSource,
            onSkipMediaOnboarding = onSkipMediaOnboarding,
            onRescan = onRescan,
            onMediaSelected = onMediaSelected,
            thumbnailRepository = thumbnailRepository,
            bottomBar = {
                Column {
                    miniPlayerContent()
                PrimaryBottomNavigation(
                    destinations = destinations,
                    selected = navigationState.currentRoot,
                    onSelected = onRootSelected,
                )
                }
            },
            videoSurface = videoSurface,
            onShareShorts = onShareShorts,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    navigationState: NavigationState,
    settings: AppearanceSettings,
    playerPreferences: PlayerPreferences,
    onGlobalAction: (GlobalAppAction) -> Unit,
    onRootSelected: (RootDestination) -> Unit,
    onBack: () -> Unit,
    onMiniPlayerChanged: (Boolean) -> Unit,
    onAutoPipChanged: (Boolean) -> Unit,
    settingsTools: SettingsToolActions,
    securitySettings: SecuritySettingsActions,
    processingViewModel: ProcessingViewModel?,
    vaultViewModel: VaultViewModel?,
    shortsViewModel: ShortsViewModel?,
    onVaultImport: () -> Unit,
    onVaultPlay: (VaultItemId) -> Unit,
    onVaultExport: (VaultItemId) -> Unit,
    onOpenProcessingOutput: (String) -> Unit,
    mediaState: MediaLibraryUiState,
    libraryViewModel: LibraryViewModel?,
    organizeViewModel: OrganizeViewModel?,
    homeViewModel: HomeViewModel?,
    libraryIsWide: Boolean,
    onRecommendedSource: () -> Unit,
    onSafSource: () -> Unit,
    onSkipMediaOnboarding: () -> Unit,
    onRescan: () -> Unit,
    onMediaSelected: (String, PlaybackQueueSource?) -> Unit,
    thumbnailRepository: ThumbnailLoader?,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
    videoSurface: @Composable () -> Unit = {},
    onShareShorts: (ShortsCandidate) -> Unit = {},
) {
    val route = navigationState.currentRoute
    val mediaOnboarding = route == AppRoute.Root(RootDestination.HOME) && mediaState.onboarding
    Scaffold(
        modifier = modifier,
        containerColor = YingLiTheme.colors.page,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = if (mediaOnboarding) ({}) else bottomBar,
    ) { padding ->
        AnimatedContent(
            targetState = route,
            modifier = Modifier.fillMaxSize().padding(padding),
            transitionSpec = { pageContentTransform(initialState, targetState) },
            label = "AppPageTransition",
        ) { route ->
            Column(Modifier.fillMaxSize()) {
                AppRouteTopBar(
                    route = route,
                    mediaState = mediaState,
                    homeViewModel = homeViewModel,
                    libraryViewModel = libraryViewModel,
                    onBack = onBack,
                    onSafSource = onSafSource,
                    onRescan = onRescan,
                    onGlobalAction = onGlobalAction,
                )
                RouteContent(
                    route = route,
                    settings = settings,
                    playerPreferences = playerPreferences,
                    onMiniPlayerChanged = onMiniPlayerChanged,
                    onAutoPipChanged = onAutoPipChanged,
                    settingsTools = settingsTools,
                    securitySettings = securitySettings,
                    processingViewModel = processingViewModel,
                    vaultViewModel = vaultViewModel,
                    shortsViewModel = shortsViewModel,
                    onVaultImport = onVaultImport,
                    onVaultPlay = onVaultPlay,
                    onVaultExport = onVaultExport,
                    onOpenProcessingOutput = onOpenProcessingOutput,
                    mediaState = mediaState,
                    libraryViewModel = libraryViewModel,
                    organizeViewModel = organizeViewModel,
                    homeViewModel = homeViewModel,
                    libraryIsWide = libraryIsWide,
                    onRecommendedSource = onRecommendedSource,
                    onSafSource = onSafSource,
                    onSkipMediaOnboarding = onSkipMediaOnboarding,
                    onRescan = onRescan,
                    onMediaSelected = onMediaSelected,
                    onRootSelected = onRootSelected,
                    onGlobalAction = onGlobalAction,
                    onBack = onBack,
                    thumbnailRepository = thumbnailRepository,
                    videoSurface = videoSurface,
                    onShareShorts = onShareShorts,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AppRouteTopBar(
    route: AppRoute,
    mediaState: MediaLibraryUiState,
    homeViewModel: HomeViewModel?,
    libraryViewModel: LibraryViewModel? = null,
    onBack: () -> Unit,
    onSafSource: () -> Unit,
    onRescan: () -> Unit,
    onGlobalAction: (GlobalAppAction) -> Unit,
) {
    var homeSearchExpanded by remember { mutableStateOf(false) }
    var homeSearchQuery by remember { mutableStateOf("") }
    var homeMoreExpanded by remember { mutableStateOf(false) }
    val isHome = route == AppRoute.Root(RootDestination.HOME)
    when {
        isHome && mediaState.onboarding -> YingLiTopBar(
            title = { AppTitle() },
        )
        isHome -> Column {
            YingLiTopBar(
                title = { AppTitle() },
                actions = {
                    YingLiIconButton(
                        icon = if (homeSearchExpanded) YingLiIcon.BACK else YingLiIcon.SEARCH,
                        contentDescription = stringResource(R.string.library_search),
                        onClick = {
                            homeMoreExpanded = false
                            homeSearchExpanded = !homeSearchExpanded
                            if (!homeSearchExpanded) {
                                homeSearchQuery = ""
                                homeViewModel?.setKeyword("")
                            }
                        },
                    )
                    Box {
                        YingLiIconButton(
                            icon = YingLiIcon.OVERFLOW,
                            contentDescription = stringResource(R.string.home_more),
                            onClick = {
                                homeSearchExpanded = false
                                homeSearchQuery = ""
                                homeViewModel?.setKeyword("")
                                homeMoreExpanded = true
                            },
                        )
                        YingLiDropdownMenu(expanded = homeMoreExpanded, onDismissRequest = { homeMoreExpanded = false }) {
                            YingLiDropdownMenuItem(
                                text = stringResource(R.string.home_add_media_directory),
                                icon = YingLiIcon.ORGANIZE,
                                onClick = { onSafSource() },
                            )
                            YingLiDropdownMenuItem(
                                text = stringResource(R.string.media_rescan),
                                icon = YingLiIcon.REPLAY,
                                onClick = { onRescan() },
                            )
                            YingLiDropdownMenuItem(
                                text = stringResource(R.string.home_customize),
                                icon = YingLiIcon.GRID,
                                onClick = { homeViewModel?.showEditor() },
                            )
                            YingLiDropdownMenuItem(
                                text = stringResource(R.string.nav_settings),
                                icon = YingLiIcon.SETTINGS,
                                onClick = { onGlobalAction(GlobalAppAction.OPEN_SETTINGS) },
                            )
                        }
                    }
                },
            )
            if (mediaState.scanning) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = YingLiTheme.functional.info.base,
                    trackColor = YingLiTheme.colors.surfaceMuted,
                )
            }
            if (homeSearchExpanded) {
                Surface(color = YingLiTheme.colors.surface, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = homeSearchQuery,
                        onValueChange = {
                            homeSearchQuery = it
                            homeViewModel?.setKeyword(it)
                        },
                        placeholder = { Text(stringResource(R.string.library_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
        route == AppRoute.Root(RootDestination.LIBRARY) && libraryViewModel != null -> Unit
        route == AppRoute.Root(RootDestination.LIBRARY) -> LibraryFallbackTopBar(onGlobalAction)
        else -> YingLiTopBar(
            title = { Text(route.title()) },
            navigationIcon = {
                if (route !is AppRoute.Root) {
                    YingLiIconButton(YingLiIcon.BACK, stringResource(R.string.action_back), onBack)
                }
            },
            actions = {
                if (route != AppRoute.Processing && route != AppRoute.Root(RootDestination.PROCESSING)) {
                    YingLiIconButton(
                        icon = YingLiIcon.PROCESSING,
                        contentDescription = stringResource(R.string.action_open_processing),
                        onClick = { onGlobalAction(GlobalAppAction.OPEN_PROCESSING) },
                    )
                }
            },
        )
    }
}

@Composable
private fun LibraryFallbackTopBar(onGlobalAction: (GlobalAppAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    YingLiTopBar(
        title = { Text(stringResource(R.string.nav_library)) },
        actions = {
            Box {
                YingLiIconButton(
                    icon = YingLiIcon.OVERFLOW,
                    contentDescription = stringResource(R.string.home_more),
                    onClick = { expanded = true },
                )
                YingLiDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    YingLiDropdownMenuItem(
                        text = stringResource(R.string.library_view_settings),
                        onClick = { onGlobalAction(GlobalAppAction.OPEN_SETTINGS) },
                    )
                }
            }
        },
    )
}

@Composable
private fun AppTitle() {
    Text(
        text = stringResource(R.string.app_name),
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun RouteContent(
    route: AppRoute,
    settings: AppearanceSettings,
    playerPreferences: PlayerPreferences,
    onMiniPlayerChanged: (Boolean) -> Unit,
    onAutoPipChanged: (Boolean) -> Unit,
    settingsTools: SettingsToolActions,
    securitySettings: SecuritySettingsActions,
    processingViewModel: ProcessingViewModel?,
    vaultViewModel: VaultViewModel?,
    shortsViewModel: ShortsViewModel?,
    onVaultImport: () -> Unit,
    onVaultPlay: (VaultItemId) -> Unit,
    onVaultExport: (VaultItemId) -> Unit,
    onOpenProcessingOutput: (String) -> Unit,
    mediaState: MediaLibraryUiState,
    libraryViewModel: LibraryViewModel?,
    organizeViewModel: OrganizeViewModel?,
    homeViewModel: HomeViewModel?,
    libraryIsWide: Boolean,
    onRecommendedSource: () -> Unit,
    onSafSource: () -> Unit,
    onSkipMediaOnboarding: () -> Unit,
    onRescan: () -> Unit,
    onMediaSelected: (String, PlaybackQueueSource?) -> Unit,
    onRootSelected: (RootDestination) -> Unit,
    onGlobalAction: (GlobalAppAction) -> Unit,
    onBack: () -> Unit,
    thumbnailRepository: ThumbnailLoader?,
    modifier: Modifier = Modifier,
    videoSurface: @Composable () -> Unit = {},
    onShareShorts: (ShortsCandidate) -> Unit = {},
) {
    when (route) {
        AppRoute.Processing, AppRoute.Root(RootDestination.PROCESSING) -> processingViewModel?.let {
            ProcessingRoute(it, onOpenProcessingOutput, modifier)
        } ?: ProcessingPlaceholder(modifier)
        AppRoute.Settings -> SettingsScreen(
            onGestureSeekEnabledChanged = settingsTools.onGestureSeekEnabled,
            onGestureVolumeEnabledChanged = settingsTools.onGestureVolumeEnabled,
            onGestureBrightnessEnabledChanged = settingsTools.onGestureBrightnessEnabled,
            onGestureZoomEnabledChanged = settingsTools.onGestureZoomEnabled,
            onGestureLeftSideIsVolumeChanged = settingsTools.onGestureLeftSideIsVolume,
            onGestureDoubleTapSeekMillisChanged = settingsTools.onGestureDoubleTapSeekMillis,
            onGestureSwipeDownToExitEnabledChanged = settingsTools.onGestureSwipeDownToExitEnabled,
            onGestureLongPressSpeedChanged = settingsTools.onGestureLongPressSpeed,
            settings = settings,
            playerPreferences = playerPreferences,
            onMiniPlayerChanged = onMiniPlayerChanged,
            onAutoPipChanged = onAutoPipChanged,
            tools = settingsTools,
            security = securitySettings,
            modifier = modifier,
        )
        AppRoute.HomeStats -> YingLiEmptyState(
            title = stringResource(R.string.home_statistics_title),
            message = stringResource(R.string.home_statistics_placeholder),
            modifier = modifier.fillMaxSize(),
        )
        AppRoute.Vault -> vaultViewModel?.let {
            VaultRoute(it, onVaultImport, onVaultPlay, onVaultExport, modifier)
        } ?: YingLiEmptyState(
            title = stringResource(R.string.vault_empty_title),
            message = stringResource(R.string.vault_empty_message),
            modifier = modifier.fillMaxSize(),
        )
        is AppRoute.Root -> when (route.destination) {
            RootDestination.HOME -> homeViewModel?.let { viewModel ->
                HomeRoute(
                    mediaState,
                    viewModel,
                    onRecommendedSource,
                    onSafSource,
                    onSkipMediaOnboarding,
                    onRescan,
                    onMediaSelected = { mediaId -> onMediaSelected(mediaId, null) },
                    thumbnailRepository = thumbnailRepository,
                    onOpenLibrary = { onRootSelected(RootDestination.LIBRARY) },
                    onOpenOrganize = { onRootSelected(RootDestination.ORGANIZE) },
                    onOpenStats = { onGlobalAction(GlobalAppAction.OPEN_HOME_STATS) },
                    onFolderSelected = { onRootSelected(RootDestination.LIBRARY) },
                    modifier = modifier,
                )
            } ?: Unit
            RootDestination.LIBRARY -> libraryViewModel?.let { viewModel ->
                LibraryRoute(
                    viewModel = viewModel,
                    isWide = libraryIsWide,
                    onMediaSelected = onMediaSelected,
                    onAddDirectory = onSafSource,
                    onRescan = onRescan,
                    thumbnailRepository = thumbnailRepository,
                    modifier = modifier,
                )
            } ?: Unit
            RootDestination.SHORTS -> shortsViewModel?.let { viewModel ->
                seeyuer.yingli.player.feature.shorts.ShortsRoute(
                    viewModel = viewModel,
                    videoSurface = videoSurface,
                    onBack = onBack,
                    onShare = onShareShorts,
                    modifier = modifier,
                )
            } ?: Unit
            RootDestination.ORGANIZE -> organizeViewModel?.let { OrganizeRoute(it, modifier) } ?: Unit
            RootDestination.PROCESSING -> processingViewModel?.let {
                ProcessingRoute(it, onOpenProcessingOutput, modifier)
            } ?: ProcessingPlaceholder(modifier)
        }
        is AppRoute.Detail -> YingLiEmptyState(
            title = stringResource(R.string.detail_title),
            message = stringResource(R.string.detail_placeholder),
            modifier = modifier.fillMaxSize(),
        )
        is AppRoute.Player, is AppRoute.VaultPlayer, AppRoute.AppLock -> FullScreenRoute(route, onBack = {})
    }
}

@Composable
private fun ProcessingPlaceholder(modifier: Modifier = Modifier) {
    YingLiEmptyState(
        title = stringResource(R.string.processing_empty_title),
        message = stringResource(R.string.processing_empty_message),
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
private fun FullScreenRoute(
    route: AppRoute,
    onBack: () -> Unit,
    playerContent: @Composable (AppRoute.Player, () -> Unit) -> Unit = { _, back ->
        FullScreenPlayerPlaceholder(back)
    },
    vaultPlayerContent: @Composable (AppRoute.VaultPlayer, () -> Unit) -> Unit = { _, back ->
        FullScreenPlayerPlaceholder(back)
    },
) {
    when (route) {
        is AppRoute.Player -> playerContent(route, onBack)
        is AppRoute.VaultPlayer -> vaultPlayerContent(route, onBack)
        AppRoute.AppLock -> Surface(
            modifier = Modifier.fillMaxSize(),
            color = YingLiTheme.colors.page,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = YingLiIcon.LOCK.imageVector,
                    contentDescription = null,
                    modifier = Modifier.size(YingLiTheme.components.iconSize),
                )
                Text(stringResource(R.string.app_lock_title), style = MaterialTheme.typography.titleLarge)
            }
        }
        else -> Unit
    }
}

@Composable
private fun FullScreenPlayerPlaceholder(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(YingLiTheme.player.canvas),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.player_placeholder),
                color = YingLiTheme.player.controlSecondary,
            )
            YingLiIconButton(
                icon = YingLiIcon.BACK,
                contentDescription = stringResource(R.string.action_back),
                onClick = onBack,
                tint = YingLiTheme.player.controlPrimary,
            )
        }
    }
}

private fun RootDestination.toPlaybackSourceContext(): PlaybackSourceContext = when (this) {
    RootDestination.HOME -> PlaybackSourceContext.HOME
    RootDestination.LIBRARY -> PlaybackSourceContext.LIBRARY
    RootDestination.SHORTS -> PlaybackSourceContext.LIBRARY
    RootDestination.ORGANIZE, RootDestination.PROCESSING -> PlaybackSourceContext.DETAIL
}

@Composable
private fun PrimaryBottomNavigation(
    destinations: List<RootDestination>,
    selected: RootDestination,
    onSelected: (RootDestination) -> Unit,
) {
    NavigationBar(
        modifier = Modifier
            .height(YingLiTheme.components.bottomNavigationHeight)
            .testTag(ShellTestTags.BOTTOM_NAVIGATION),
        containerColor = YingLiTheme.colors.surface,
    ) {
        destinations.forEach { destination ->
            val label = destination.label()
            NavigationBarItem(
                selected = destination == selected,
                onClick = { onSelected(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon().imageVector,
                        contentDescription = label,
                        modifier = Modifier.size(YingLiTheme.components.iconSize),
                    )
                },
                label = { Text(label, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = YingLiTheme.colors.selectionOnStructural,
                    selectedTextColor = YingLiTheme.colors.textPrimary,
                    indicatorColor = YingLiTheme.colors.selectionStructural,
                    unselectedIconColor = YingLiTheme.colors.textSecondary,
                    unselectedTextColor = YingLiTheme.colors.textSecondary,
                ),
            )
        }
    }
}

@Composable
private fun PrimaryNavigationRail(
    destinations: List<RootDestination>,
    selected: RootDestination,
    onSelected: (RootDestination) -> Unit,
) {
    NavigationRail(
        modifier = Modifier
            .width(YingLiTheme.components.navigationRailWidth)
            .fillMaxHeight()
            .testTag(ShellTestTags.NAVIGATION_RAIL),
        containerColor = YingLiTheme.colors.surface,
    ) {
        destinations.forEach { destination ->
            val label = destination.label()
            NavigationRailItem(
                selected = destination == selected,
                onClick = { onSelected(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon().imageVector,
                        contentDescription = label,
                        modifier = Modifier.size(YingLiTheme.components.iconSize),
                    )
                },
                label = { Text(label, maxLines = 1) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = YingLiTheme.colors.selectionOnStructural,
                    selectedTextColor = YingLiTheme.colors.textPrimary,
                    indicatorColor = YingLiTheme.colors.selectionStructural,
                    unselectedIconColor = YingLiTheme.colors.textSecondary,
                    unselectedTextColor = YingLiTheme.colors.textSecondary,
                ),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun RootDestination.label(): String = stringResource(
    when (this) {
        RootDestination.HOME -> R.string.nav_home
        RootDestination.LIBRARY -> R.string.nav_library
        RootDestination.SHORTS -> R.string.nav_shorts
        RootDestination.ORGANIZE -> R.string.nav_organize
        RootDestination.PROCESSING -> R.string.nav_processing
    },
)

private fun RootDestination.icon(): YingLiIcon = when (this) {
    RootDestination.HOME -> YingLiIcon.HOME
    RootDestination.LIBRARY -> YingLiIcon.LIBRARY
    RootDestination.SHORTS -> YingLiIcon.SHORTS
    RootDestination.ORGANIZE -> YingLiIcon.ORGANIZE
    RootDestination.PROCESSING -> YingLiIcon.PROCESSING
}

@Composable
private fun AppRoute.title(): String = when (this) {
    is AppRoute.Root -> destination.label()
    AppRoute.Processing -> stringResource(R.string.nav_processing)
    AppRoute.Settings -> stringResource(R.string.nav_settings)
    AppRoute.HomeStats -> stringResource(R.string.home_statistics_title)
    AppRoute.Vault -> stringResource(R.string.vault_title)
    is AppRoute.Detail -> stringResource(R.string.detail_title)
    is AppRoute.Player -> stringResource(R.string.player_placeholder)
    is AppRoute.VaultPlayer -> stringResource(R.string.vault_title)
    AppRoute.AppLock -> stringResource(R.string.app_lock_title)
}

object ShellTestTags {
    const val BOTTOM_NAVIGATION = "shell.bottom_navigation"
    const val NAVIGATION_RAIL = "shell.navigation_rail"
}
