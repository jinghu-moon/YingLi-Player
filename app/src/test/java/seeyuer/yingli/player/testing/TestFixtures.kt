package seeyuer.yingli.player.testing

import seeyuer.yingli.player.core.foundation.AppClock
import seeyuer.yingli.player.core.foundation.AppContainer
import seeyuer.yingli.player.core.foundation.AppDispatchers
import seeyuer.yingli.player.core.foundation.AppLogRecord
import seeyuer.yingli.player.core.foundation.AppLogSink
import seeyuer.yingli.player.core.foundation.AppLogger
import seeyuer.yingli.player.core.foundation.DefaultAppContainer
import seeyuer.yingli.player.core.foundation.IdGenerator
import seeyuer.yingli.player.core.foundation.RedactingAppLogger
import seeyuer.yingli.player.core.foundation.SensitiveValueRedactor
import seeyuer.yingli.player.core.datastore.AppearanceSettings
import seeyuer.yingli.player.core.datastore.ThemePreference
import seeyuer.yingli.player.core.datastore.ThemeRepository
import seeyuer.yingli.player.core.model.AppFailureMapper
import seeyuer.yingli.player.core.model.DefaultAppFailureMapper
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

fun interface FixtureBuilder<T> {
    fun build(): T
}

class FakeAppClock(
    var current: Instant = Instant.parse("2026-08-07T00:00:00Z"),
) : AppClock {
    override fun now(): Instant = current
}

class SequenceIdGenerator(
    private var next: Int = 1,
) : IdGenerator {
    override fun newId(): String = "test-id-${next++}"
}

class CapturingLogSink : AppLogSink {
    val records: MutableList<AppLogRecord> = mutableListOf()

    override fun emit(record: AppLogRecord) {
        records += record
    }
}

class TestAppDispatchers(
    scheduler: TestCoroutineScheduler = TestCoroutineScheduler(),
) : AppDispatchers {
    private val testDispatcher = StandardTestDispatcher(scheduler)

    override val main: CoroutineDispatcher = testDispatcher
    override val io: CoroutineDispatcher = testDispatcher
    override val default: CoroutineDispatcher = testDispatcher
}

class FakeThemeRepository(
    initial: AppearanceSettings = AppearanceSettings(),
) : ThemeRepository {
    private val mutableSettings = MutableStateFlow(initial)
    override val settings: Flow<AppearanceSettings> = mutableSettings

    override suspend fun setThemePreference(preference: ThemePreference) {
        mutableSettings.value = mutableSettings.value.copy(themePreference = preference)
    }

    override suspend fun setDynamicColorEnabled(enabled: Boolean) {
        mutableSettings.value = mutableSettings.value.copy(dynamicColorEnabled = enabled)
    }

    override suspend fun setProcessingPinned(pinned: Boolean) {
        mutableSettings.value = mutableSettings.value.copy(processingPinned = pinned)
    }
}

class AppContainerFixtureBuilder : FixtureBuilder<AppContainer> {
    var dispatchers: AppDispatchers = TestAppDispatchers()
    var clock: AppClock = FakeAppClock()
    var idGenerator: IdGenerator = SequenceIdGenerator()
    var redactor: SensitiveValueRedactor = SensitiveValueRedactor { it }
    var logSink: AppLogSink = CapturingLogSink()
    var failureMapper: AppFailureMapper = DefaultAppFailureMapper
    var themeRepository: ThemeRepository = FakeThemeRepository()

    override fun build(): AppContainer {
        val logger: AppLogger = RedactingAppLogger(redactor, logSink)
        return DefaultAppContainer(
            dispatchers = dispatchers,
            clock = clock,
            idGenerator = idGenerator,
            logger = logger,
            failureMapper = failureMapper,
            themeRepository = themeRepository,
        )
    }
}
