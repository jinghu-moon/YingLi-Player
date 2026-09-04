package seeyuer.yingli.player.app

import android.content.Context
import seeyuer.yingli.player.core.datastore.DataStoreThemeRepository
import seeyuer.yingli.player.core.foundation.AppContainer
import seeyuer.yingli.player.core.foundation.DefaultAppContainer
import seeyuer.yingli.player.core.foundation.DefaultAppDispatchers
import seeyuer.yingli.player.core.foundation.DefaultSensitiveValueRedactor
import seeyuer.yingli.player.core.foundation.CompositeAppLogSink
import seeyuer.yingli.player.core.foundation.RedactingAppLogger
import seeyuer.yingli.player.core.foundation.RollingDiagnosticLogStore
import seeyuer.yingli.player.core.foundation.SystemAppClock
import seeyuer.yingli.player.core.foundation.UuidGenerator
import seeyuer.yingli.player.core.model.DefaultAppFailureMapper

object ProductionAppContainerFactory {
    fun create(context: Context): AppContainer {
        val redactor = DefaultSensitiveValueRedactor()
        val diagnosticLogStore = RollingDiagnosticLogStore()
        val logger = RedactingAppLogger(
            redactor = redactor,
            sink = CompositeAppLogSink(AndroidLogSink(), diagnosticLogStore),
        )
        return DefaultAppContainer(
            dispatchers = DefaultAppDispatchers,
            clock = SystemAppClock,
            idGenerator = UuidGenerator,
            logger = logger,
            diagnosticLogStore = diagnosticLogStore,
            failureMapper = DefaultAppFailureMapper,
            themeRepository = DataStoreThemeRepository(context),
        )
    }
}
