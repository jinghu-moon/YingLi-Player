package seeyuer.yingli.player.app

import android.app.Application
import seeyuer.yingli.player.core.foundation.AppContainer

class YingLiApplication : Application() {
    lateinit var container: AppContainer
        private set
    lateinit var mediaContainer: MediaContainer
        private set
    lateinit var playbackController: Media3PlaybackController
        private set

    override fun onCreate() {
        super.onCreate()
        container = ProductionAppContainerFactory.create(this)
        mediaContainer = ProductionMediaContainerFactory.create(this, container)
        playbackController = Media3PlaybackController(
            this,
            mediaContainer.playbackSourceRepository,
            container.dispatchers,
            container.logger,
        )
    }

    override fun onTerminate() {
        mediaContainer.processingLifecycle.close()
        playbackController.close()
        super.onTerminate()
    }
}
