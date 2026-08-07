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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import seeyuer.yingli.player.feature.home.HomeScreen
import seeyuer.yingli.player.feature.library.LibraryScreen
import seeyuer.yingli.player.feature.library.MediaLibraryEffect
import seeyuer.yingli.player.feature.library.MediaLibraryUiState
import seeyuer.yingli.player.feature.library.MediaLibraryViewModel
import seeyuer.yingli.player.feature.organize.OrganizeScreen
import seeyuer.yingli.player.feature.processing.ProcessingScreen
import seeyuer.yingli.player.feature.settings.SettingsScreen

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun YingLiApp(
    viewModel: YingLiAppViewModel,
    mediaLibraryViewModel: MediaLibraryViewModel,
    windowWidthSizeClass: WindowWidthSizeClass,
) {
    val settings by viewModel.appearanceSettings.collectAsStateWithLifecycle()
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
    LaunchedEffect(mediaLibraryViewModel) {
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

    YingLiTheme(
        darkTheme = darkTheme,
        dynamicColor = settings.dynamicColorEnabled,
    ) {
        val navigationState by viewModel.navigationState.collectAsStateWithLifecycle()
        val mediaState by mediaLibraryViewModel.state.collectAsStateWithLifecycle()
        val systemBarMode = when {
            navigationState.currentRoute is AppRoute.Player -> SystemBarMode.PLAYER
            darkTheme -> SystemBarMode.DARK_APP
            else -> SystemBarMode.LIGHT_APP
        }
        YingLiSystemBars(systemBarMode)
        BackHandler(enabled = navigationState.canNavigateBack, onBack = viewModel::navigateBack)
        AdaptiveAppShell(
            navigationState = navigationState,
            settings = settings,
            windowWidthSizeClass = windowWidthSizeClass,
            onRootSelected = viewModel::selectRoot,
            onGlobalAction = viewModel::openGlobalAction,
            onBack = viewModel::navigateBack,
            onThemePreferenceChanged = viewModel::setThemePreference,
            onDynamicColorChanged = viewModel::setDynamicColorEnabled,
            onProcessingPinnedChanged = viewModel::setProcessingPinned,
            mediaState = mediaState,
            onRecommendedSource = mediaLibraryViewModel::addRecommendedSource,
            onSafSource = mediaLibraryViewModel::addSafSource,
            onSkipMediaOnboarding = mediaLibraryViewModel::skipOnboarding,
            onRescan = mediaLibraryViewModel::rescan,
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
    windowWidthSizeClass: WindowWidthSizeClass,
    onRootSelected: (RootDestination) -> Unit,
    onGlobalAction: (GlobalAppAction) -> Unit,
    onBack: () -> Unit,
    onThemePreferenceChanged: (ThemePreference) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onProcessingPinnedChanged: (Boolean) -> Unit,
    mediaState: MediaLibraryUiState = MediaLibraryUiState(onboarding = false),
    onRecommendedSource: () -> Unit = {},
    onSafSource: () -> Unit = {},
    onSkipMediaOnboarding: () -> Unit = {},
    onRescan: () -> Unit = {},
) {
    val usesNavigationRail = windowWidthSizeClass != WindowWidthSizeClass.Compact
    val destinations = navigationState.primaryDestinations

    if (!navigationState.showPrimaryNavigation) {
        FullScreenRoute(navigationState.currentRoute, onBack)
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
                onGlobalAction = onGlobalAction,
                onBack = onBack,
                onThemePreferenceChanged = onThemePreferenceChanged,
                onDynamicColorChanged = onDynamicColorChanged,
                onProcessingPinnedChanged = onProcessingPinnedChanged,
                mediaState = mediaState,
                onRecommendedSource = onRecommendedSource,
                onSafSource = onSafSource,
                onSkipMediaOnboarding = onSkipMediaOnboarding,
                onRescan = onRescan,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        AppScaffold(
            navigationState = navigationState,
            settings = settings,
            onGlobalAction = onGlobalAction,
            onBack = onBack,
            onThemePreferenceChanged = onThemePreferenceChanged,
            onDynamicColorChanged = onDynamicColorChanged,
            onProcessingPinnedChanged = onProcessingPinnedChanged,
            mediaState = mediaState,
            onRecommendedSource = onRecommendedSource,
            onSafSource = onSafSource,
            onSkipMediaOnboarding = onSkipMediaOnboarding,
            onRescan = onRescan,
            bottomBar = {
                PrimaryBottomNavigation(
                    destinations = destinations,
                    selected = navigationState.currentRoot,
                    onSelected = onRootSelected,
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    navigationState: NavigationState,
    settings: AppearanceSettings,
    onGlobalAction: (GlobalAppAction) -> Unit,
    onBack: () -> Unit,
    onThemePreferenceChanged: (ThemePreference) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onProcessingPinnedChanged: (Boolean) -> Unit,
    mediaState: MediaLibraryUiState,
    onRecommendedSource: () -> Unit,
    onSafSource: () -> Unit,
    onSkipMediaOnboarding: () -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
) {
    val route = navigationState.currentRoute
    Scaffold(
        modifier = modifier,
        containerColor = YingLiTheme.colors.page,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(route.title()) },
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
                    if (route != AppRoute.Settings) {
                        YingLiIconButton(
                            icon = YingLiIcon.SETTINGS,
                            contentDescription = stringResource(R.string.action_open_settings),
                            onClick = { onGlobalAction(GlobalAppAction.OPEN_SETTINGS) },
                        )
                    }
                },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        RouteContent(
            route = route,
            settings = settings,
            onThemePreferenceChanged = onThemePreferenceChanged,
            onDynamicColorChanged = onDynamicColorChanged,
            onProcessingPinnedChanged = onProcessingPinnedChanged,
            mediaState = mediaState,
            onRecommendedSource = onRecommendedSource,
            onSafSource = onSafSource,
            onSkipMediaOnboarding = onSkipMediaOnboarding,
            onRescan = onRescan,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun RouteContent(
    route: AppRoute,
    settings: AppearanceSettings,
    onThemePreferenceChanged: (ThemePreference) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onProcessingPinnedChanged: (Boolean) -> Unit,
    mediaState: MediaLibraryUiState,
    onRecommendedSource: () -> Unit,
    onSafSource: () -> Unit,
    onSkipMediaOnboarding: () -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (route) {
        AppRoute.Processing, AppRoute.Root(RootDestination.PROCESSING) -> ProcessingScreen(modifier)
        AppRoute.Settings -> SettingsScreen(
            settings = settings,
            onThemePreferenceChanged = onThemePreferenceChanged,
            onDynamicColorChanged = onDynamicColorChanged,
            onProcessingPinnedChanged = onProcessingPinnedChanged,
            modifier = modifier,
        )
        is AppRoute.Root -> when (route.destination) {
            RootDestination.HOME -> HomeScreen(
                mediaState,
                onRecommendedSource,
                onSafSource,
                onSkipMediaOnboarding,
                onRescan,
                modifier,
            )
            RootDestination.LIBRARY -> LibraryScreen(modifier)
            RootDestination.ORGANIZE -> OrganizeScreen(modifier)
            RootDestination.PROCESSING -> ProcessingScreen(modifier)
        }
        is AppRoute.Detail -> YingLiEmptyState(
            title = stringResource(R.string.detail_title),
            message = stringResource(R.string.detail_placeholder),
            modifier = modifier.fillMaxSize(),
        )
        is AppRoute.Player, AppRoute.AppLock -> FullScreenRoute(route, onBack = {})
    }
}

@Composable
private fun FullScreenRoute(route: AppRoute, onBack: () -> Unit) {
    when (route) {
        is AppRoute.Player -> Box(
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
    is AppRoute.Detail -> stringResource(R.string.detail_title)
    is AppRoute.Player -> stringResource(R.string.player_placeholder)
    AppRoute.AppLock -> stringResource(R.string.app_lock_title)
}

object ShellTestTags {
    const val BOTTOM_NAVIGATION = "shell.bottom_navigation"
    const val NAVIGATION_RAIL = "shell.navigation_rail"
}
