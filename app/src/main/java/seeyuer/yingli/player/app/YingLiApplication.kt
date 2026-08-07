package seeyuer.yingli.player.app

import android.app.Application
import seeyuer.yingli.player.core.foundation.AppContainer

class YingLiApplication : Application() {
    lateinit var container: AppContainer
        private set
    lateinit var mediaContainer: MediaContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = ProductionAppContainerFactory.create(this)
        mediaContainer = ProductionMediaContainerFactory.create(this, container)
    }
}
