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
import seeyuer.yingli.player.domain.security.VaultItem
import seeyuer.yingli.player.domain.security.VaultItemId
import seeyuer.yingli.player.domain.security.VaultOperationResult
import seeyuer.yingli.player.domain.security.VaultRepository

data class VaultUiState(
    val items: List<VaultItem> = emptyList(),
    val working: Boolean = false,
    val statusCode: String? = null,
    val pendingDelete: VaultItemId? = null,
)

class VaultViewModel(private val repository: VaultRepository) : ViewModel() {
    private val working = MutableStateFlow(false)
    private val status = MutableStateFlow<String?>(null)
    private val pendingDelete = MutableStateFlow<VaultItemId?>(null)
    val state: StateFlow<VaultUiState> = combine(
        repository.items,
        working,
        status,
        pendingDelete,
        ::VaultUiState,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), VaultUiState())

    fun import(uri: String?) {
        if (uri == null || working.value) return
        runOperation { repository.import(uri) }
    }

    fun export(itemId: VaultItemId, uri: String?) {
        if (uri == null || working.value) return
        runOperation { repository.export(itemId, uri) }
    }

    fun requestDelete(itemId: VaultItemId) {
        pendingDelete.value = itemId
    }

    fun dismissDelete() {
        pendingDelete.value = null
    }

    fun confirmDelete() {
        val itemId = pendingDelete.value ?: return
        pendingDelete.value = null
        if (working.value) return
        working.value = true
        viewModelScope.launch {
            status.value = if (repository.delete(itemId)) "DELETE_SUCCEEDED" else "DELETE_FAILED"
            working.value = false
        }
    }

    private fun runOperation(block: suspend () -> VaultOperationResult) {
        working.value = true
        status.value = null
        viewModelScope.launch {
            status.value = when (val result = block()) {
                is VaultOperationResult.Success -> "OPERATION_SUCCEEDED"
                is VaultOperationResult.Failure -> result.code
                VaultOperationResult.Canceled -> "OPERATION_CANCELED"
            }
            working.value = false
        }
    }

    companion object {
        private const val STOP_TIMEOUT = 5_000L
        fun factory(repository: VaultRepository) = viewModelFactory {
            initializer { VaultViewModel(repository) }
        }
    }
}
