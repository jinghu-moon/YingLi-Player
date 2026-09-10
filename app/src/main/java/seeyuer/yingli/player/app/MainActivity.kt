package seeyuer.yingli.player.app

import android.os.Bundle
import android.content.pm.ActivityInfo
import android.os.StatFs
import android.view.WindowManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import seeyuer.yingli.player.app.playback.ActivityPictureInPictureGateway
import seeyuer.yingli.player.engine.media3.Media3ScreenshotGateway
import seeyuer.yingli.player.engine.media3.Media3VideoSurface
import seeyuer.yingli.player.engine.media3.Media3PlaybackController
import seeyuer.yingli.player.feature.shell.YingLiApp
import seeyuer.yingli.player.feature.shell.YingLiAppViewModel
import seeyuer.yingli.player.feature.library.MediaLibraryViewModel
import seeyuer.yingli.player.feature.library.LibraryViewModel
import seeyuer.yingli.player.feature.player.PlayerViewModel
import seeyuer.yingli.player.feature.organize.OrganizeViewModel
import seeyuer.yingli.player.feature.home.HomeViewModel
import seeyuer.yingli.player.feature.settings.SettingsViewModel
import seeyuer.yingli.player.feature.processing.ProcessingViewModel
import seeyuer.yingli.player.feature.security.SecurityViewModel
import seeyuer.yingli.player.feature.security.VaultViewModel
import seeyuer.yingli.player.domain.security.AppLockMode

class MainActivity : ComponentActivity() {
    private var landscapeRequested = false
    private var secureContent = true
    private val viewModel: YingLiAppViewModel by viewModels {
        YingLiAppViewModel.factory((application as YingLiApplication).container.themeRepository)
    }
    private val mediaLibraryViewModel: MediaLibraryViewModel by viewModels {
        val media = (application as YingLiApplication).mediaContainer
        MediaLibraryViewModel.factory(
            media.sourceRepository,
            media.catalogRepository,
            media.permissionGateway,
            media.scanner,
            media.onboardingRepository,
        )
    }
    private val playerViewModel: PlayerViewModel by viewModels {
        val app = application as YingLiApplication
        PlayerViewModel.factory(
            app.playbackController,
            app.mediaContainer.playbackSourceRepository,
            app.container.dispatchers,
            app.mediaContainer.playerPreferenceRepository,
            app.mediaContainer.trackPreferenceRepository,
            Media3ScreenshotGateway(this, app.playbackController, app.container.dispatchers, app.container.clock),
            ActivityPictureInPictureGateway(this),
        )
    }
    private val libraryViewModel: LibraryViewModel by viewModels {
        val media = (application as YingLiApplication).mediaContainer
        LibraryViewModel.factory(
            media.libraryRepository,
            media.libraryPreferenceRepository,
            media.libraryMutationRepository,
            media.trashRepository,
        )
    }
    private val organizeViewModel: OrganizeViewModel by viewModels {
        val app = application as YingLiApplication
        val media = app.mediaContainer
        OrganizeViewModel.factory(
            media.organizeRepository,
            media.duplicateRepository,
            media.duplicateScanner,
            media.duplicateDeletionExecutor,
            app.container.clock,
        )
    }
    private val homeViewModel: HomeViewModel by viewModels {
        val media = (application as YingLiApplication).mediaContainer
        HomeViewModel.factory(
            media.homeRepository,
            media.libraryRepository,
            media.homeLayoutRepository,
            media.deviceStorageRepository,
            media.duplicateRepository,
            media.trashRepository,
        )
    }
    private val settingsViewModel: SettingsViewModel by viewModels {
        val media = (application as YingLiApplication).mediaContainer
        SettingsViewModel.factory(
            media.backupGateway,
            media.diagnosticsReporter,
            media.settingsDocumentGateway,
            media.updateSource,
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
        )
    }
    private val processingViewModel: ProcessingViewModel by viewModels {
        val app = application as YingLiApplication
        val media = app.mediaContainer
        ProcessingViewModel.factory(
            media.processingRepository,
            media.processingController,
            media.clipProjectRepository,
            media.clipExportQueue,
            media.libraryRepository,
            app.container.idGenerator,
            app.container.clock,
            media.timelineFrameProvider,
            media.mediaCapabilityProbe,
            media.transcodeQueue,
            availableBytes = { runCatching { StatFs(cacheDir.absolutePath).availableBytes }.getOrDefault(0) },
        )
    }
    private val securityViewModel: SecurityViewModel by viewModels {
        SecurityViewModel.factory((application as YingLiApplication).mediaContainer.appLockManager)
    }
    private val vaultViewModel: VaultViewModel by viewModels {
        VaultViewModel.factory((application as YingLiApplication).mediaContainer.vaultRepository)
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition {
            !securityViewModel.state.value.initialized || !mediaLibraryViewModel.state.value.initialized
        }
        super.onCreate(savedInstanceState)
        mediaLibraryViewModel.initialize()
        setSecureContent(true)
        enableEdgeToEdge()
        setContent {
            YingLiApp(
                viewModel = viewModel,
                mediaLibraryViewModel = mediaLibraryViewModel,
                playerViewModel = playerViewModel,
                libraryViewModel = libraryViewModel,
                organizeViewModel = organizeViewModel,
                homeViewModel = homeViewModel,
                settingsViewModel = settingsViewModel,
                processingViewModel = processingViewModel,
                securityViewModel = securityViewModel,
                vaultViewModel = vaultViewModel,
                windowWidthSizeClass = calculateWindowSizeClass(this).widthSizeClass,
                videoSurface = {
                    Media3VideoSurface((application as YingLiApplication).playbackController)
                },
                onToggleOrientation = ::toggleOrientation,
                onSecureContentChanged = ::setSecureContent,
                onSecureSessionLocked = (application as YingLiApplication).playbackController::invalidateSecureSession,
                biometricAvailable = biometricAvailable(),
                onBiometricUnlock = ::authenticateBiometric,
                thumbnailRepository = (application as YingLiApplication).mediaContainer.thumbnailRepository,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        (application as YingLiApplication).playbackController.setVideoOutputEnabled(true)
        (application as YingLiApplication).mediaContainer.appLockManager.onForeground()
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            val app = application as YingLiApplication
            val manager = app.mediaContainer.appLockManager
            if (!isInPictureInPictureMode) app.playbackController.setVideoOutputEnabled(false)
            app.playbackController.invalidateSecureSession()
            if (manager.machine.value.policy.mode != AppLockMode.OFF) setSecureContent(true)
            manager.onBackground()
        }
        super.onStop()
    }

    override fun onUserLeaveHint() {
        if (!secureContent && playerViewModel.state.value.preferences.autoPictureInPicture &&
            playerViewModel.state.value.playback.request != null
        ) {
            playerViewModel.enterPictureInPicture()
        }
        super.onUserLeaveHint()
    }

    private fun setSecureContent(enabled: Boolean) {
        if (secureContent == enabled && (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) == enabled) {
            return
        }
        secureContent = enabled
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun biometricAvailable(): Boolean =
        getSystemService(BiometricManager::class.java)?.canAuthenticate(BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    private fun authenticateBiometric() {
        if (!biometricAvailable()) {
            securityViewModel.onBiometricResult(false)
            return
        }
        BiometricPrompt.Builder(this)
            .setTitle(getString(seeyuer.yingli.player.R.string.app_lock_biometric_title))
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButton(
                getString(seeyuer.yingli.player.R.string.app_lock_use_pin),
                mainExecutor,
            ) { _, _ -> securityViewModel.onBiometricResult(false) }
            .build()
            .authenticate(
                CancellationSignal(),
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        securityViewModel.onBiometricResult(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        securityViewModel.onBiometricResult(false)
                    }

                    override fun onAuthenticationFailed() {
                        securityViewModel.onBiometricResult(false)
                    }
                },
            )
    }

    private fun toggleOrientation() {
        landscapeRequested = !landscapeRequested
        requestedOrientation = if (landscapeRequested) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
