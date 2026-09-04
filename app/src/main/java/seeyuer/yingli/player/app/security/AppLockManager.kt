package seeyuer.yingli.player.app.security

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import seeyuer.yingli.player.core.foundation.AppClock
import seeyuer.yingli.player.core.foundation.AppDispatchers
import seeyuer.yingli.player.core.security.CredentialHasher
import seeyuer.yingli.player.domain.security.AppLockEvent
import seeyuer.yingli.player.domain.security.AppLockController
import seeyuer.yingli.player.domain.security.AppLockMachine
import seeyuer.yingli.player.domain.security.AppLockMode
import seeyuer.yingli.player.domain.security.AppLockPolicy
import seeyuer.yingli.player.domain.security.AppLockReducer
import seeyuer.yingli.player.domain.security.AppLockRepository
import seeyuer.yingli.player.domain.security.AppLockSessionState

class AppLockManager(
    private val repository: AppLockRepository,
    private val credentialHasher: CredentialHasher,
    private val clock: AppClock,
    private val dispatchers: AppDispatchers,
    scope: CoroutineScope,
) : AppLockController {
    private val mutableMachine = MutableStateFlow(AppLockMachine())
    override val machine: StateFlow<AppLockMachine> = mutableMachine.asStateFlow()
    private val mutableInitialized = MutableStateFlow(false)
    override val initialized: StateFlow<Boolean> = mutableInitialized.asStateFlow()

    init {
        scope.launch {
            repository.policy.collect { policy ->
                mutableMachine.update { current ->
                    if (current.policy == policy) current
                    else AppLockReducer.reduce(current, AppLockEvent.Configure(policy, now()))
                }
                mutableInitialized.value = true
            }
        }
    }

    override suspend fun configurePin(pin: CharArray, timeoutMillis: Long, immediate: Boolean) {
        val credential = withContext(dispatchers.default) { credentialHasher.create(pin, now()) }
        val policy = AppLockPolicy(AppLockMode.PIN, timeoutMillis, immediate)
        repository.configure(policy, credential)
        mutableMachine.update { AppLockReducer.reduce(it, AppLockEvent.Configure(policy, now())) }
    }

    override suspend fun disable() {
        val policy = AppLockPolicy()
        repository.configure(policy, null)
        mutableMachine.update { AppLockReducer.reduce(it, AppLockEvent.Configure(policy, now())) }
    }

    override suspend fun configureBiometric(): Boolean {
        val credential = repository.credential() ?: return false
        val policy = AppLockPolicy(AppLockMode.BIOMETRIC, timeoutMillis = 0, lockImmediatelyOnBackground = true)
        repository.configure(policy, credential)
        mutableMachine.update { AppLockReducer.reduce(it, AppLockEvent.Configure(policy, now())) }
        return true
    }

    override suspend fun unlock(pin: CharArray): Boolean {
        val current = mutableMachine.value
        if (!AppLockReducer.canAttempt(current.state, now())) {
            pin.fill('\u0000')
            return false
        }
        val credential = repository.credential()
        val success = credential != null && withContext(dispatchers.default) {
            credentialHasher.verify(pin, credential)
        }
        mutableMachine.update {
            AppLockReducer.reduce(
                it,
                if (success) AppLockEvent.UnlockSucceeded(now()) else AppLockEvent.UnlockFailed(now()),
            )
        }
        return success
    }

    override fun unlockWithBiometric(success: Boolean) {
        if (!success) return
        mutableMachine.update {
            AppLockReducer.reduce(it, AppLockEvent.UnlockSucceeded(now()))
        }
    }

    override fun onBackground() = mutableMachine.update {
        AppLockReducer.reduce(it, AppLockEvent.EnterBackground(now()))
    }

    override fun onForeground() = mutableMachine.update {
        val foreground = AppLockReducer.reduce(it, AppLockEvent.EnterForeground(now()))
        val state = foreground.state
        if (state is AppLockSessionState.RateLimited && AppLockReducer.canAttempt(state, now())) {
            foreground.copy(state = AppLockSessionState.Locked(state.failedAttempts))
        } else {
            foreground
        }
    }

    override fun lockNow() = mutableMachine.update { AppLockReducer.reduce(it, AppLockEvent.LockNow(now())) }

    private fun now(): Long = clock.now().toEpochMilli()
}
