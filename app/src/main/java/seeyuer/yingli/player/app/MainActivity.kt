package seeyuer.yingli.player.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import seeyuer.yingli.player.feature.shell.YingLiApp
import seeyuer.yingli.player.feature.shell.YingLiAppViewModel
import seeyuer.yingli.player.feature.library.MediaLibraryViewModel
import seeyuer.yingli.player.feature.player.PlayerViewModel

class MainActivity : ComponentActivity() {
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
        )
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YingLiApp(
                viewModel = viewModel,
                mediaLibraryViewModel = mediaLibraryViewModel,
                playerViewModel = playerViewModel,
                windowWidthSizeClass = calculateWindowSizeClass(this).widthSizeClass,
                videoSurface = {
                    Media3VideoSurface((application as YingLiApplication).playbackController)
                },
            )
        }
    }
}
