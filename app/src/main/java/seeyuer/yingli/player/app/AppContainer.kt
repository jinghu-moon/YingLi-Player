package seeyuer.yingli.player.app

import seeyuer.yingli.player.data.preferences.ThemeRepository
import seeyuer.yingli.player.core.model.AppFailureMapper
import seeyuer.yingli.player.core.common.AppClock
import seeyuer.yingli.player.core.common.AppDispatchers
import seeyuer.yingli.player.core.common.AppLogger
import seeyuer.yingli.player.core.common.DiagnosticLogStore
import seeyuer.yingli.player.core.common.IdGenerator

interface AppContainer {
    val dispatchers: AppDispatchers
    val clock: AppClock
    val idGenerator: IdGenerator
    val logger: AppLogger
    val diagnosticLogStore: DiagnosticLogStore
    val failureMapper: AppFailureMapper
    val themeRepository: ThemeRepository
}

class DefaultAppContainer(
    override val dispatchers: AppDispatchers,
    override val clock: AppClock,
    override val idGenerator: IdGenerator,
    override val logger: AppLogger,
    override val diagnosticLogStore: DiagnosticLogStore,
    override val failureMapper: AppFailureMapper,
    override val themeRepository: ThemeRepository,
) : AppContainer
