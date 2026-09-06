package seeyuer.yingli.player.core.common

import seeyuer.yingli.player.data.preferences.ThemeRepository
import seeyuer.yingli.player.core.model.AppFailureMapper

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
