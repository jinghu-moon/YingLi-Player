package seeyuer.yingli.player.domain.security

import javax.crypto.SecretKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import seeyuer.yingli.player.core.security.SecureRandomAccessReader
import seeyuer.yingli.player.core.security.StoredCredential

enum class AppLockMode { OFF, PIN, PATTERN, BIOMETRIC }

data class AppLockPolicy(
    val mode: AppLockMode = AppLockMode.OFF,
    val timeoutMillis: Long = 0,
    val lockImmediatelyOnBackground: Boolean = true,
) {
    init {
        require(timeoutMillis >= 0)
        require(mode != AppLockMode.OFF || timeoutMillis == 0L)
    }
}

sealed interface AppLockSessionState {
    data object Disabled : AppLockSessionState
    data class Locked(val failedAttempts: Int = 0) : AppLockSessionState
    data class RateLimited(val failedAttempts: Int, val retryAtEpochMillis: Long) : AppLockSessionState
    data class Unlocked(val unlockedAtEpochMillis: Long) : AppLockSessionState
}

sealed interface AppLockEvent {
    data class Configure(val policy: AppLockPolicy, val atEpochMillis: Long) : AppLockEvent
    data class UnlockSucceeded(val atEpochMillis: Long) : AppLockEvent
    data class UnlockFailed(val atEpochMillis: Long) : AppLockEvent
    data class EnterBackground(val atEpochMillis: Long) : AppLockEvent
    data class EnterForeground(val atEpochMillis: Long) : AppLockEvent
    data class LockNow(val atEpochMillis: Long) : AppLockEvent
}

data class AppLockMachine(
    val policy: AppLockPolicy = AppLockPolicy(),
    val state: AppLockSessionState = AppLockSessionState.Disabled,
    val backgroundAtEpochMillis: Long? = null,
)

object AppLockReducer {
    fun reduce(machine: AppLockMachine, event: AppLockEvent): AppLockMachine = when (event) {
        is AppLockEvent.Configure -> if (event.policy.mode == AppLockMode.OFF) {
            AppLockMachine(event.policy, AppLockSessionState.Disabled)
        } else {
            AppLockMachine(event.policy, AppLockSessionState.Locked())
        }
        is AppLockEvent.UnlockSucceeded -> machine.copy(
            state = if (machine.policy.mode == AppLockMode.OFF) AppLockSessionState.Disabled
            else AppLockSessionState.Unlocked(event.atEpochMillis),
            backgroundAtEpochMillis = null,
        )
        is AppLockEvent.UnlockFailed -> {
            if (machine.policy.mode == AppLockMode.OFF) return machine
            val attempts = when (val state = machine.state) {
                is AppLockSessionState.Locked -> state.failedAttempts + 1
                is AppLockSessionState.RateLimited -> state.failedAttempts + 1
                else -> 1
            }
            if (attempts >= MAX_ATTEMPTS_BEFORE_DELAY) {
                val multiplier = (attempts - MAX_ATTEMPTS_BEFORE_DELAY + 1).coerceAtMost(MAX_DELAY_MULTIPLIER)
                machine.copy(state = AppLockSessionState.RateLimited(
                    attempts,
                    event.atEpochMillis + BASE_RETRY_DELAY_MILLIS * multiplier,
                ))
            } else {
                machine.copy(state = AppLockSessionState.Locked(attempts))
            }
        }
        is AppLockEvent.EnterBackground -> machine.copy(backgroundAtEpochMillis = event.atEpochMillis)
        is AppLockEvent.EnterForeground -> {
            val backgroundAt = machine.backgroundAtEpochMillis ?: return machine
            val clockRolledBack = event.atEpochMillis < backgroundAt
            val elapsed = (event.atEpochMillis - backgroundAt).coerceAtLeast(0)
            val shouldLock = machine.policy.mode != AppLockMode.OFF &&
                (clockRolledBack || machine.policy.lockImmediatelyOnBackground || elapsed >= machine.policy.timeoutMillis)
            machine.copy(
                state = if (shouldLock) AppLockSessionState.Locked() else machine.state,
                backgroundAtEpochMillis = null,
            )
        }
        is AppLockEvent.LockNow -> if (machine.policy.mode == AppLockMode.OFF) machine
        else machine.copy(state = AppLockSessionState.Locked(), backgroundAtEpochMillis = null)
    }

    fun canAttempt(state: AppLockSessionState, nowEpochMillis: Long): Boolean = when (state) {
        is AppLockSessionState.RateLimited -> nowEpochMillis >= state.retryAtEpochMillis
        is AppLockSessionState.Locked -> true
        else -> false
    }

    private const val MAX_ATTEMPTS_BEFORE_DELAY = 5
    private const val BASE_RETRY_DELAY_MILLIS = 30_000L
    private const val MAX_DELAY_MULTIPLIER = 10
}

interface AppLockRepository {
    val policy: Flow<AppLockPolicy>
    suspend fun credential(): StoredCredential?
    suspend fun configure(policy: AppLockPolicy, credential: StoredCredential?)
}

interface AppLockController {
    val machine: StateFlow<AppLockMachine>
    val initialized: StateFlow<Boolean>
    suspend fun configurePin(pin: CharArray, timeoutMillis: Long, immediate: Boolean)
    suspend fun disable()
    suspend fun configureBiometric(): Boolean
    suspend fun unlock(pin: CharArray): Boolean
    fun unlockWithBiometric(success: Boolean)
    fun onBackground()
    fun onForeground()
    fun lockNow()
}

@JvmInline
value class VaultItemId(val value: String) {
    init { require(value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }
}

data class VaultItem(
    val id: VaultItemId,
    val encryptedContentToken: String,
    val encryptedMetadataToken: String,
    val encryptedBytes: Long,
    val keyVersion: Int,
    val createdAtEpochMillis: Long,
) {
    init {
        require(encryptedContentToken.isNotBlank() && encryptedMetadataToken.isNotBlank())
        require(encryptedBytes > 0 && keyVersion > 0 && createdAtEpochMillis >= 0)
    }
}

sealed interface KeyAccessResult {
    data class Available(val key: SecretKey, val version: Int) : KeyAccessResult
    data object AuthenticationRequired : KeyAccessResult
    data object PermanentlyInvalidated : KeyAccessResult
    data object Unavailable : KeyAccessResult
}

interface KeyManagementGateway {
    suspend fun create(): KeyAccessResult
    suspend fun current(): KeyAccessResult
    suspend fun key(version: Int): KeyAccessResult
    suspend fun rotate(): KeyAccessResult
    suspend fun invalidate()
}

sealed interface VaultOperationResult {
    data class Success(val item: VaultItem, val sourceRemoved: Boolean = false) : VaultOperationResult
    data class Failure(val code: String) : VaultOperationResult
    data object Canceled : VaultOperationResult
}

interface VaultRepository {
    val items: Flow<List<VaultItem>>
    suspend fun import(uri: String, removeSourceAfterVerification: Boolean = false): VaultOperationResult
    suspend fun export(itemId: VaultItemId, outputUri: String): VaultOperationResult
    suspend fun delete(itemId: VaultItemId): Boolean
}

interface SecurePlaybackSource {
    suspend fun open(itemId: VaultItemId): SecureRandomAccessReader?
}

interface SecurePlaybackController {
    fun prepare(itemId: VaultItemId): Boolean
    fun invalidateSecureSession()
}
