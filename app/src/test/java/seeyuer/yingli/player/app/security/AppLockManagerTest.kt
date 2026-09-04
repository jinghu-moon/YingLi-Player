package seeyuer.yingli.player.app.security

import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import seeyuer.yingli.player.core.foundation.AppDispatchers
import seeyuer.yingli.player.core.security.CredentialHasher
import seeyuer.yingli.player.core.security.StoredCredential
import seeyuer.yingli.player.domain.security.AppLockMode
import seeyuer.yingli.player.domain.security.AppLockPolicy
import seeyuer.yingli.player.domain.security.AppLockRepository
import seeyuer.yingli.player.domain.security.AppLockSessionState
import seeyuer.yingli.player.testing.FakeAppClock

@OptIn(ExperimentalCoroutinesApi::class)
class AppLockManagerTest {
    @Test
    fun `biometric failure preserves pin fallback without consuming pin attempts`() = runTest {
        val repository = FakeAppLockRepository()
        val clock = FakeAppClock()
        val manager = AppLockManager(
            repository,
            FakeCredentialHasher,
            clock,
            TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
            backgroundScope,
        )
        runCurrent()

        manager.configurePin("1234".toCharArray(), timeoutMillis = 0, immediate = true)
        assertTrue(manager.configureBiometric())
        manager.unlockWithBiometric(false)

        assertEquals(AppLockSessionState.Locked(), manager.machine.value.state)
        assertTrue(manager.unlock("1234".toCharArray()))
        assertTrue(manager.machine.value.state is AppLockSessionState.Unlocked)
    }

    @Test
    fun `pin failures rate limit and foreground releases only after retry deadline`() = runTest {
        val repository = FakeAppLockRepository()
        val clock = FakeAppClock(Instant.ofEpochMilli(1_000))
        val manager = AppLockManager(
            repository,
            FakeCredentialHasher,
            clock,
            TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
            backgroundScope,
        )
        runCurrent()
        manager.configurePin("1234".toCharArray(), timeoutMillis = 0, immediate = true)

        repeat(5) { assertFalse(manager.unlock("9999".toCharArray())) }
        val limited = manager.machine.value.state as AppLockSessionState.RateLimited
        clock.current = Instant.ofEpochMilli(limited.retryAtEpochMillis - 1)
        manager.onForeground()
        assertTrue(manager.machine.value.state is AppLockSessionState.RateLimited)

        clock.current = Instant.ofEpochMilli(limited.retryAtEpochMillis)
        manager.onForeground()
        assertEquals(AppLockSessionState.Locked(5), manager.machine.value.state)
    }

    private class FakeAppLockRepository : AppLockRepository {
        private val mutablePolicy = MutableStateFlow(AppLockPolicy())
        override val policy: Flow<AppLockPolicy> = mutablePolicy
        private var storedCredential: StoredCredential? = null

        override suspend fun credential(): StoredCredential? = storedCredential

        override suspend fun configure(policy: AppLockPolicy, credential: StoredCredential?) {
            mutablePolicy.value = policy
            storedCredential = credential
        }
    }

    private object FakeCredentialHasher : CredentialHasher {
        private const val EXPECTED_HASH = "MTIzNA=="

        override fun create(secret: CharArray, nowEpochMillis: Long): StoredCredential = try {
            StoredCredential("c2FsdA==", EXPECTED_HASH, 120_000, nowEpochMillis)
        } finally {
            secret.fill('\u0000')
        }

        override fun verify(secret: CharArray, credential: StoredCredential): Boolean = try {
            credential.hashBase64 == EXPECTED_HASH && secret.concatToString() == "1234"
        } finally {
            secret.fill('\u0000')
        }
    }

    private class TestDispatchers(dispatcher: CoroutineDispatcher) : AppDispatchers {
        override val main: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
    }
}
