package seeyuer.yingli.player.app

import android.content.Context
import seeyuer.yingli.player.data.preferences.DataStoreThemeRepository
import seeyuer.yingli.player.core.common.DefaultAppDispatchers
import seeyuer.yingli.player.core.common.DefaultSensitiveValueRedactor
import seeyuer.yingli.player.core.common.CompositeAppLogSink
import seeyuer.yingli.player.core.common.RedactingAppLogger
import seeyuer.yingli.player.core.common.RollingDiagnosticLogStore
import seeyuer.yingli.player.core.common.SystemAppClock
import seeyuer.yingli.player.core.common.UuidGenerator
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
