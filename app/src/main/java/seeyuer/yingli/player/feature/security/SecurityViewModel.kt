package seeyuer.yingli.player.feature.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import seeyuer.yingli.player.domain.security.AppLockController
import seeyuer.yingli.player.domain.security.AppLockMachine
import seeyuer.yingli.player.domain.security.AppLockMode
import seeyuer.yingli.player.domain.security.AppLockSessionState

data class SecurityUiState(
    val initialized: Boolean = false,
    val machine: AppLockMachine = AppLockMachine(),
    val statusCode: String? = null,
) {
    val locked: Boolean get() = !initialized || machine.state is AppLockSessionState.Locked ||
        machine.state is AppLockSessionState.RateLimited
    val enabled: Boolean get() = machine.policy.mode != AppLockMode.OFF
}

class SecurityViewModel(
    private val manager: AppLockController,
) : ViewModel() {
    private val status = MutableStateFlow<String?>(null)
    val state: StateFlow<SecurityUiState> = combine(
        manager.initialized,
        manager.machine,
        status,
        ::SecurityUiState,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), SecurityUiState())

    fun enablePin(value: String) {
        if (!value.matches(Regex("[0-9]{4,12}"))) {
            status.value = "INVALID_PIN"
            return
        }
        viewModelScope.launch {
            runCatching { manager.configurePin(value.toCharArray(), timeoutMillis = 0, immediate = true) }
                .onSuccess { status.value = null }
                .onFailure { status.value = "LOCK_CONFIGURATION_FAILED" }
        }
    }

    fun disable() {
        viewModelScope.launch {
            runCatching { manager.disable() }
                .onSuccess { status.value = null }
                .onFailure { status.value = "LOCK_CONFIGURATION_FAILED" }
        }
    }

    fun enableBiometric() {
        viewModelScope.launch {
            status.value = if (manager.configureBiometric()) null else "PIN_REQUIRED"
        }
    }

    fun onBiometricResult(success: Boolean) {
        manager.unlockWithBiometric(success)
        status.value = if (success) null else "UNLOCK_FAILED"
    }

    fun unlock(value: String) {
        if (!value.matches(Regex("[0-9]{4,12}"))) {
            status.value = "INVALID_PIN"
            return
        }
        viewModelScope.launch {
            if (manager.unlock(value.toCharArray())) status.value = null else status.value = "UNLOCK_FAILED"
        }
    }

    companion object {
        private const val STOP_TIMEOUT = 5_000L
        fun factory(manager: AppLockController) = viewModelFactory {
            initializer { SecurityViewModel(manager) }
        }
    }
}
