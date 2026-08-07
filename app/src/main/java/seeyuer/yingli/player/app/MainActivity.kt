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

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YingLiApp(
                viewModel = viewModel,
                mediaLibraryViewModel = mediaLibraryViewModel,
                windowWidthSizeClass = calculateWindowSizeClass(this).widthSizeClass,
            )
        }
    }
}
