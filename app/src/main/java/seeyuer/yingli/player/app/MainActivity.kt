package seeyuer.yingli.player.app

import android.os.Bundle
import android.content.res.Configuration
import android.graphics.Rect
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import seeyuer.yingli.player.app.playback.ActivityDeviceControlGateway
import seeyuer.yingli.player.app.playback.ActivityPictureInPictureGateway
import seeyuer.yingli.player.app.playback.ActivityWindowPlaybackGateway
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
import seeyuer.yingli.player.feature.shorts.ShortsViewModel
import seeyuer.yingli.player.feature.security.SecurityViewModel
import seeyuer.yingli.player.feature.security.VaultViewModel
import seeyuer.yingli.player.domain.security.AppLockMode
import seeyuer.yingli.player.domain.playback.shouldAutoEnterPictureInPicture

class MainActivity : ComponentActivity() {
    /**
     * 窗口是否承载安全内容（保险库播放 / 应用锁）。
     *
     * 它有两个消费者：窗口的 `FLAG_SECURE`，以及自动画中画参数（私密内容不许弹进浮窗）。
     * 两者必须同源，所以状态只有这一份，[setSecureContent] 是唯一的写入点。
     */
    private val secureContent = MutableStateFlow(true)

    /**
     * 画中画入场动画的起点：视频输出视图在窗口里的矩形（拿不到时为 null）。
     * 取的是**真实渲染输出**的位置而不是某个固定比例，这样浮窗看起来是从画面里长出来的。
     */
    private fun pictureInPictureSourceRect(): Rect? =
        (application as YingLiApplication).playbackController.videoSurfaceBoundsInWindow()

    /**
     * 画中画入口的唯一实例：它同时保存着"自动进入"这份参数镜像（见
     * [ActivityPictureInPictureGateway] 的类注释），所以播放页、短视频页和窗口网关必须共用它。
     */
    private val pictureInPictureGateway by lazy {
        ActivityPictureInPictureGateway(this, ::pictureInPictureSourceRect)
    }

    private val windowPlaybackGateway by lazy {
        ActivityWindowPlaybackGateway(this, pictureInPictureGateway)
    }
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
            app.playbackSessionClient,
            app.container.dispatchers,
            app.mediaContainer.playerPreferenceRepository,
            app.mediaContainer.trackPreferenceRepository,
            Media3ScreenshotGateway(this, app.playbackController, app.container.dispatchers, app.container.clock),
            pictureInPictureGateway,
            app.mediaContainer.playbackQueueRepository,
            app.mediaContainer.playerControlLayoutRepository,
            app.mediaContainer.libraryRepository,
            windowPlaybackGateway,
            ActivityDeviceControlGateway(this),
            app.mediaContainer.seekPrecisionControl,
            // AB 区间导出（§14.6 步骤 11）：队列把「区间」固化成持久项目，探测回答「能否无损复制」，
            // id 与时钟由核心提供（架构规则禁止在实现里读系统时钟）。
            app.mediaContainer.clipExportQueue,
            app.mediaContainer.clipFastExportProbe,
            app.container.idGenerator,
            app.container.clock,
            ownsSessionClient = false,
        )
    }
    private val shortsViewModel: ShortsViewModel by viewModels {
        val app = application as YingLiApplication
        ShortsViewModel.factory(
            app.playbackSessionClient,
            app.mediaContainer.libraryRepository,
            app.container.dispatchers,
            app.mediaContainer.shortsPreferenceRepository,
            app.mediaContainer.organizeRepository,
            app.mediaContainer.libraryMutationRepository,
            Media3ScreenshotGateway(this, app.playbackController, app.container.dispatchers, app.container.clock),
            pictureInPictureGateway,
        )
    }
    private val libraryViewModel: LibraryViewModel by viewModels {
        val media = (application as YingLiApplication).mediaContainer
        LibraryViewModel.factory(
            media.libraryRepository,
            media.libraryPreferenceRepository,
            media.libraryMutationRepository,
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
            media.trashRepository,
            media.homeRepository,
            media.duplicateScanQueue,
            media.processingRepository,
            media.trashService,
            media.recycleQueue,
            media.recycleAuthorizationLauncher,
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
            media.processingQueue,
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
        observeAutoPictureInPicture()
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
                shortsViewModel = shortsViewModel,
                windowWidthSizeClass = calculateWindowSizeClass(this).widthSizeClass,
                videoSurface = {
                    Media3VideoSurface((application as YingLiApplication).playbackController)
                },
                playerVideoSurface = { transformed ->
                    Media3VideoSurface(
                        (application as YingLiApplication).playbackController,
                        transformed = transformed,
                    )
                },
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
        val app = application as YingLiApplication
        app.playbackController.setVideoOutputEnabled(true)
        app.mediaContainer.appLockManager.onForeground()
        // 回收站维护（§8.7 + D3-C2）：启动与每次回到前台时对账并清理到期条目。
        // 不引 WorkManager：物理清理确实发生在「下一次打开应用」，这一点在 UI 文案里写明。
        // 幂等：上一轮还没跑完时 start() 直接返回。
        app.mediaContainer.recycleMaintenance.start()
    }

    override fun onResume() {
        super.onResume()
        // 参数是**快照**：系统在用户离开应用的那一刻直接用最近一次下发的那份，
        // 所以每次回到前台都重发一遍，把 source rect 这份快照刷新到当前窗口几何
        //（期间可能转过屏、进出过全屏、被别的应用挡住过）。
        refreshPictureInPictureParams()
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            val app = application as YingLiApplication
            val manager = app.mediaContainer.appLockManager
            // 后台播放开关：关掉后一离开前台就暂停（画中画例外 —— 画面仍可见，暂停等于把 PiP 变成静态图）。
            // 放在 super.onStop() 之前读状态：此刻 collectAsStateWithLifecycle 的订阅还没停，
            // state.value 里是用户刚看到的那份偏好，而不是 StateFlow 的初始默认值。
            // 这里刻意不在 onStart 里恢复播放：回到前台要不要继续由用户决定，播放器不该替他按播放键。
            if (
                seeyuer.yingli.player.domain.playback.shouldPauseInBackground(
                    backgroundPlaybackEnabled = playerViewModel.state.value.preferences.backgroundPlaybackEnabled,
                    inPictureInPicture = isInPictureInPictureMode,
                )
            ) {
                playerViewModel.pause()
            }
            if (!isInPictureInPictureMode) app.playbackController.setVideoOutputEnabled(false)
            app.playbackController.invalidateSecureSession()
            if (manager.machine.value.policy.mode != AppLockMode.OFF) setSecureContent(true)
            manager.onBackground()
        }
        super.onStop()
    }

    /**
     * 自动画中画的**唯一参数下发点**。
     *
     * 为什么需要一个镜像：`setAutoEnterEnabled(true)` 必须在用户离开应用**之前**下发
     *（系统在切后台那一刻直接读最近一次下发的参数，事后补发来不及），而"该不该自动进入"
     * 由运行期状态决定（偏好 + 安全内容 + 有没有媒体）。镜像 = 把这几个输入收成一条流，
     * 每次变化就下发一次；判定本身只引用纯函数 [shouldAutoEnterPictureInPicture]，
     * 不在这里重写条件。
     *
     * 为什么不需要在 `onUserLeaveHint` 里再手动进一次（本类已删除那条路径）：
     * - 采用自动进入时，**系统在 auto-enter 路径上根本不会下发带 `userLeaving` 的 pause**，
     *   因此 `onUserLeaveHint` 不会被回调（AOSP `TaskFragment.startPausing` 在
     *   `shouldAutoPip` 成立时直接进入画中画，并在进入后以 `userLeaving=false` 补排 pause，
     *   见 `ActivityTaskManagerService.enterPictureInPictureMode` 尾部的 `schedulePauseActivity`）；
     * - 即使某个 ROM 仍然回调它，手动进入也会被系统挡掉：客户端参数携带
     *   `isAutoEnterEnabled() == true` 且 Activity 处于 PAUSING 时，
     *   `ActivityTaskManagerService.enterPictureInPictureMode` 明确早退返回 false
     *（"Skip client enterPictureInPictureMode request while pausing, auto-enter-pip is enabled"）。
     *   也就是说：武装了自动进入之后，`onUserLeaveHint` 那条手动路径既不会跑、跑了也无效，
     *   留着只会让人以为它还在兜底。
     */
    private fun observeAutoPictureInPicture() {
        lifecycleScope.launch {
            combine(
                playerViewModel.state,
                secureContent,
            ) { player, secure ->
                shouldAutoEnterPictureInPicture(
                    preferenceEnabled = player.preferences.autoPictureInPicture,
                    secureContent = secure,
                    hasMedia = player.playback.request != null,
                )
            }
                .distinctUntilChanged()
                .collect(pictureInPictureGateway::applyAutoEnter)
        }
        // 画面几何是第二类输入：source rect 这份快照要等视频输出视图量完才是最终值
        //（视频尺寸未知时它还是整窗大小，写进参数等于没有起点提示）。
        // StateFlow 自带"相等值不重复发射"，所以这里不需要再去重。
        lifecycleScope.launch {
            (application as YingLiApplication).playbackController.videoSurfaceBounds
                .collect { refreshPictureInPictureParams() }
        }
    }

    /** 画面几何变了（旋转 / 进出全屏 / 回到前台）时刷新参数快照；处于画中画时由网关自己跳过。 */
    private fun refreshPictureInPictureParams() {
        pictureInPictureGateway.refreshParams()
    }

    private fun setSecureContent(enabled: Boolean) {
        if (secureContent.value == enabled &&
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) == enabled
        ) {
            return
        }
        secureContent.value = enabled
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

    /** Manifest 声明自行处理方向/尺寸变化，因此这里把真实结果回传给窗口网关并刷新画中画参数。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        windowPlaybackGateway.onConfigurationChanged(newConfig)
        // 转屏/尺寸变化会改变视频画面在窗口里的矩形，而它是自动进入画中画时的入场（与退出）动画起点。
        // 进入/退出画中画的配置回调也会走到这里：网关在"已处于画中画"时不重发参数，
        // 否则会把浮窗大小当成起点写进去。
        refreshPictureInPictureParams()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        windowPlaybackGateway.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }
}
