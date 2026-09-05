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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.datastore.AppearanceSettings
import seeyuer.yingli.player.core.datastore.ThemePreference
import seeyuer.yingli.player.core.designsystem.component.YingLiEmptyState
import seeyuer.yingli.player.core.designsystem.component.YingLiIconButton
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
import seeyuer.yingli.player.feature.player.PlayerScreen
import seeyuer.yingli.player.feature.player.PlayerViewModel
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
import seeyuer.yingli.player.domain.security.VaultItemId
import seeyuer.yingli.player.core.media.ThumbnailLoader

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
    windowWidthSizeClass: WindowWidthSizeClass,
    videoSurface: @Composable () -> Unit,
    onToggleOrientation: () -> Unit = {},
    onSecureContentChanged: (Boolean) -> Unit = {},
    onSecureSessionLocked: () -> Unit = {},
    biometricAvailable: Boolean = false,
    onBiometricUnlock: () -> Unit = {},
    thumbnailRepository: ThumbnailLoader? = null,
) {
    val settings by viewModel.appearanceSettings.collectAsStateWithLifecycle()
    val securityState by securityViewModel.state.collectAsStateWithLifecycle()
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val darkTheme = when (settings.themePreference) {
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
        ThemePreference.SYSTEM -> systemDark
    }
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
        val mediaState by mediaLibraryViewModel.state.collectAsStateWithLifecycle()
        val playerState by playerViewModel.state.collectAsStateWithLifecycle()
        val settingsToolsState by settingsViewModel.state.collectAsStateWithLifecycle()
        SideEffect {
            onSecureContentChanged(
                navigationState.currentRoute == AppRoute.Vault || navigationState.currentRoute is AppRoute.VaultPlayer,
            )
        }
        val systemBarMode = when {
            navigationState.currentRoute is AppRoute.Player || navigationState.currentRoute is AppRoute.VaultPlayer ->
                SystemBarMode.PLAYER
            darkTheme -> SystemBarMode.DARK_APP
            else -> SystemBarMode.LIGHT_APP
        }
        YingLiSystemBars(systemBarMode)
        BackHandler(enabled = navigationState.canNavigateBack, onBack = viewModel::navigateBack)
        AdaptiveAppShell(
            navigationState = navigationState,
            settings = settings,
            playerPreferences = playerState.preferences,
            windowWidthSizeClass = windowWidthSizeClass,
            onRootSelected = viewModel::selectRoot,
            onGlobalAction = viewModel::openGlobalAction,
            onBack = viewModel::navigateBack,
            onThemePreferenceChanged = viewModel::setThemePreference,
            onProcessingPinnedChanged = viewModel::setProcessingPinned,
            onMiniPlayerChanged = playerViewModel::setMiniPlayerEnabled,
            onAutoPipChanged = playerViewModel::setAutoPictureInPicture,
            settingsTools = SettingsToolActions(
                state = settingsToolsState,
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
            playerContent = { route, onBack ->
                LaunchedEffect(route.mediaId) {
                    playerViewModel.open(route.mediaId, route.source.toPlaybackSourceContext())
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
                    videoSurface = videoSurface,
                    onSeekBackward = { playerViewModel.seekBackward() },
                    onSeekForward = { playerViewModel.seekForward() },
                    onToggleOverlay = playerViewModel::toggleOverlay,
                    onToggleLock = playerViewModel::toggleLock,
                    onSetSpeed = { playerViewModel.setSpeed(it) },
                    onSetScaleMode = { playerViewModel.setScaleMode(it) },
                    onSelectAudioTrack = { playerViewModel.selectAudioTrack(it) },
                    onSelectSubtitleTrack = { playerViewModel.selectSubtitleTrack(it) },
                    onPictureInPicture = { playerViewModel.enterPictureInPicture() },
                    onScreenshot = playerViewModel::captureScreenshot,
                    onToggleOrientation = onToggleOrientation,
                )
            },
            vaultPlayerContent = { route, onBack ->
                val vaultTitle = stringResource(R.string.vault_title)
                LaunchedEffect(route.itemId) { playerViewModel.openVault(route.itemId, vaultTitle) }
                DisposableEffect(route.itemId) {
                    onDispose(playerViewModel::closeVault)
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
                    videoSurface = videoSurface,
                    onSeekBackward = { playerViewModel.seekBackward() },
                    onSeekForward = { playerViewModel.seekForward() },
                    onToggleOverlay = playerViewModel::toggleOverlay,
                    onToggleLock = playerViewModel::toggleLock,
                    onSetSpeed = { playerViewModel.setSpeed(it) },
                    onSetScaleMode = { playerViewModel.setScaleMode(it) },
                    onSelectAudioTrack = { playerViewModel.selectAudioTrack(it) },
                    onSelectSubtitleTrack = { playerViewModel.selectSubtitleTrack(it) },
                    onToggleOrientation = onToggleOrientation,
                    allowPictureInPicture = false,
                    allowScreenshot = false,
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
    onThemePreferenceChanged: (ThemePreference) -> Unit,
    onProcessingPinnedChanged: (Boolean) -> Unit,
    onMiniPlayerChanged: (Boolean) -> Unit = {},
    onAutoPipChanged: (Boolean) -> Unit = {},
    settingsTools: SettingsToolActions = SettingsToolActions(),
    securitySettings: SecuritySettingsActions = SecuritySettingsActions(),
    processingViewModel: ProcessingViewModel? = null,
    vaultViewModel: VaultViewModel? = null,
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
    onMediaSelected: (String) -> Unit = {},
    thumbnailRepository: ThumbnailLoader? = null,
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
                settingsSelected = navigationState.currentRoute == AppRoute.Settings,
                onSettingsSelected = { onGlobalAction(GlobalAppAction.OPEN_SETTINGS) },
            )
            AppScaffold(
                navigationState = navigationState,
                settings = settings,
                playerPreferences = playerPreferences,
                onGlobalAction = onGlobalAction,
                onBack = onBack,
                onThemePreferenceChanged = onThemePreferenceChanged,
                onProcessingPinnedChanged = onProcessingPinnedChanged,
                onMiniPlayerChanged = onMiniPlayerChanged,
                onAutoPipChanged = onAutoPipChanged,
                settingsTools = settingsTools,
                securitySettings = securitySettings,
                processingViewModel = processingViewModel,
                vaultViewModel = vaultViewModel,
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
            )
        }
    } else {
        AppScaffold(
            navigationState = navigationState,
            settings = settings,
            playerPreferences = playerPreferences,
            onGlobalAction = onGlobalAction,
            onBack = onBack,
            onThemePreferenceChanged = onThemePreferenceChanged,
            onProcessingPinnedChanged = onProcessingPinnedChanged,
            onMiniPlayerChanged = onMiniPlayerChanged,
            onAutoPipChanged = onAutoPipChanged,
            settingsTools = settingsTools,
            securitySettings = securitySettings,
            processingViewModel = processingViewModel,
            vaultViewModel = vaultViewModel,
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
                    settingsSelected = navigationState.currentRoute == AppRoute.Settings,
                    onSettingsSelected = { onGlobalAction(GlobalAppAction.OPEN_SETTINGS) },
                )
                }
            },
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
    onBack: () -> Unit,
    onThemePreferenceChanged: (ThemePreference) -> Unit,
    onProcessingPinnedChanged: (Boolean) -> Unit,
    onMiniPlayerChanged: (Boolean) -> Unit,
    onAutoPipChanged: (Boolean) -> Unit,
    settingsTools: SettingsToolActions,
    securitySettings: SecuritySettingsActions,
    processingViewModel: ProcessingViewModel?,
    vaultViewModel: VaultViewModel?,
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
    onMediaSelected: (String) -> Unit,
    thumbnailRepository: ThumbnailLoader?,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
) {
    val route = navigationState.currentRoute
    val mediaOnboarding = route == AppRoute.Root(RootDestination.HOME) && mediaState.onboarding
    var homeSearchExpanded by remember(route) { mutableStateOf(false) }
    var homeSearchQuery by remember(route) { mutableStateOf("") }
    Scaffold(
        modifier = modifier,
        containerColor = YingLiTheme.colors.page,
        topBar = {
            if (mediaOnboarding) {
                androidx.compose.material3.TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                )
            } else CenterAlignedTopAppBar(
                title = {
                    if (route == AppRoute.Root(RootDestination.HOME) && homeSearchExpanded) {
                        OutlinedTextField(
                            value = homeSearchQuery,
                            onValueChange = {
                                homeSearchQuery = it
                                homeViewModel?.setKeyword(it)
                            },
                            placeholder = { Text(stringResource(R.string.library_search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(route.title())
                    }
                },
                navigationIcon = {
                    if (navigationState.canNavigateBack) {
                        YingLiIconButton(
                            icon = YingLiIcon.BACK,
                            contentDescription = stringResource(R.string.action_back),
                            onClick = onBack,
                        )
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
                    if (route == AppRoute.Root(RootDestination.HOME)) {
                        YingLiIconButton(
                            icon = if (homeSearchExpanded) YingLiIcon.BACK else YingLiIcon.SEARCH,
                            contentDescription = stringResource(R.string.library_search),
                            onClick = {
                                homeSearchExpanded = !homeSearchExpanded
                                if (!homeSearchExpanded) {
                                    homeSearchQuery = ""
                                    homeViewModel?.setKeyword("")
                                }
                            },
                        )
                    }
                    if (route == AppRoute.Root(RootDestination.LIBRARY)) {
                        YingLiIconButton(
                            icon = YingLiIcon.OVERFLOW,
                            contentDescription = stringResource(R.string.library_view_settings),
                            onClick = { libraryViewModel?.toggleFilterPanel() },
                        )
                    }
                },
            )
        },
        bottomBar = if (mediaOnboarding) ({}) else bottomBar,
    ) { padding ->
        RouteContent(
            route = route,
            settings = settings,
            playerPreferences = playerPreferences,
            onThemePreferenceChanged = onThemePreferenceChanged,
            onProcessingPinnedChanged = onProcessingPinnedChanged,
            onMiniPlayerChanged = onMiniPlayerChanged,
            onAutoPipChanged = onAutoPipChanged,
            settingsTools = settingsTools,
            securitySettings = securitySettings,
            processingViewModel = processingViewModel,
            vaultViewModel = vaultViewModel,
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
            thumbnailRepository = thumbnailRepository,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun RouteContent(
    route: AppRoute,
    settings: AppearanceSettings,
    playerPreferences: PlayerPreferences,
    onThemePreferenceChanged: (ThemePreference) -> Unit,
    onProcessingPinnedChanged: (Boolean) -> Unit,
    onMiniPlayerChanged: (Boolean) -> Unit,
    onAutoPipChanged: (Boolean) -> Unit,
    settingsTools: SettingsToolActions,
    securitySettings: SecuritySettingsActions,
    processingViewModel: ProcessingViewModel?,
    vaultViewModel: VaultViewModel?,
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
    onMediaSelected: (String) -> Unit,
    thumbnailRepository: ThumbnailLoader?,
    modifier: Modifier = Modifier,
) {
    when (route) {
        AppRoute.Processing, AppRoute.Root(RootDestination.PROCESSING) -> processingViewModel?.let {
            ProcessingRoute(it, onOpenProcessingOutput, modifier)
        } ?: ProcessingPlaceholder(modifier)
        AppRoute.Settings -> SettingsScreen(
            settings = settings,
            onThemePreferenceChanged = onThemePreferenceChanged,
            onProcessingPinnedChanged = onProcessingPinnedChanged,
            playerPreferences = playerPreferences,
            onMiniPlayerChanged = onMiniPlayerChanged,
            onAutoPipChanged = onAutoPipChanged,
            tools = settingsTools,
            security = securitySettings,
            modifier = modifier,
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
                    onMediaSelected,
                    modifier,
                )
            } ?: Unit
            RootDestination.LIBRARY -> libraryViewModel?.let { viewModel ->
                LibraryRoute(
                    viewModel = viewModel,
                    isWide = libraryIsWide,
                    onMediaSelected = onMediaSelected,
                    thumbnailRepository = thumbnailRepository,
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
    RootDestination.ORGANIZE, RootDestination.PROCESSING -> PlaybackSourceContext.DETAIL
}

@Composable
private fun PrimaryBottomNavigation(
    destinations: List<RootDestination>,
    selected: RootDestination,
    onSelected: (RootDestination) -> Unit,
    settingsSelected: Boolean,
    onSettingsSelected: () -> Unit,
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
                selected = destination == selected && !settingsSelected,
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
        NavigationBarItem(
            selected = settingsSelected,
            onClick = onSettingsSelected,
            icon = {
                Icon(
                    imageVector = YingLiIcon.SETTINGS.imageVector,
                    contentDescription = stringResource(R.string.nav_settings),
                    modifier = Modifier.size(YingLiTheme.components.iconSize),
                )
            },
            label = { Text(stringResource(R.string.nav_settings), maxLines = 1) },
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

@Composable
private fun PrimaryNavigationRail(
    destinations: List<RootDestination>,
    selected: RootDestination,
    onSelected: (RootDestination) -> Unit,
    settingsSelected: Boolean,
    onSettingsSelected: () -> Unit,
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
                selected = destination == selected && !settingsSelected,
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
        NavigationRailItem(
            selected = settingsSelected,
            onClick = onSettingsSelected,
            icon = {
                Icon(
                    imageVector = YingLiIcon.SETTINGS.imageVector,
                    contentDescription = stringResource(R.string.nav_settings),
                    modifier = Modifier.size(YingLiTheme.components.iconSize),
                )
            },
            label = { Text(stringResource(R.string.nav_settings), maxLines = 1) },
            colors = NavigationRailItemDefaults.colors(
                selectedIconColor = YingLiTheme.colors.selectionOnStructural,
                selectedTextColor = YingLiTheme.colors.textPrimary,
                indicatorColor = YingLiTheme.colors.selectionStructural,
                unselectedIconColor = YingLiTheme.colors.textSecondary,
                unselectedTextColor = YingLiTheme.colors.textSecondary,
            ),
        )
    }
}

@Composable
private fun RootDestination.label(): String = stringResource(
    when (this) {
        RootDestination.HOME -> R.string.nav_home
        RootDestination.LIBRARY -> R.string.nav_library
        RootDestination.ORGANIZE -> R.string.nav_organize
        RootDestination.PROCESSING -> R.string.nav_processing
    },
)

private fun RootDestination.icon(): YingLiIcon = when (this) {
    RootDestination.HOME -> YingLiIcon.HOME
    RootDestination.LIBRARY -> YingLiIcon.LIBRARY
    RootDestination.ORGANIZE -> YingLiIcon.ORGANIZE
    RootDestination.PROCESSING -> YingLiIcon.PROCESSING
}

@Composable
private fun AppRoute.title(): String = when (this) {
    is AppRoute.Root -> destination.label()
    AppRoute.Processing -> stringResource(R.string.nav_processing)
    AppRoute.Settings -> stringResource(R.string.nav_settings)
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
