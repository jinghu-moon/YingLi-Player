package seeyuer.yingli.player.feature.security

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.component.BannerKind
import seeyuer.yingli.player.core.designsystem.component.YingLiBanner
import seeyuer.yingli.player.core.designsystem.component.YingLiButton
import seeyuer.yingli.player.core.designsystem.component.YingLiEmptyState
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.security.AppLockSessionState
import seeyuer.yingli.player.domain.security.VaultItem
import seeyuer.yingli.player.domain.security.VaultItemId

@Composable
fun AppLockRoute(
    viewModel: SecurityViewModel,
    modifier: Modifier = Modifier,
    onBiometric: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AppLockScreen(state, viewModel::unlock, onBiometric, modifier)
}

@Composable
fun AppLockScreen(
    state: SecurityUiState,
    onUnlock: (String) -> Unit,
    onBiometric: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = true) {}
    var pin by remember { mutableStateOf("") }
    Surface(modifier.fillMaxSize(), color = YingLiTheme.colors.page) {
        Column(
            modifier = Modifier.fillMaxSize().padding(YingLiTheme.components.pagePadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.app_lock_title), style = MaterialTheme.typography.titleLarge)
            Text("*".repeat(pin.length), style = MaterialTheme.typography.headlineMedium)
            val limited = state.machine.state as? AppLockSessionState.RateLimited
            if (limited != null) {
                YingLiBanner(stringResource(R.string.app_lock_rate_limited), BannerKind.WARNING)
            } else if (state.statusCode != null) {
                YingLiBanner(stringResource(R.string.app_lock_error), BannerKind.ERROR)
            }
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing),
                ) {
                    row.forEach { digit ->
                        YingLiButton(
                            text = digit,
                            onClick = { if (pin.length < MAX_PIN_LENGTH) pin += digit },
                            modifier = Modifier.weight(1f),
                            enabled = limited == null,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing),
            ) {
                YingLiButton(
                    text = stringResource(R.string.app_lock_delete_digit),
                    onClick = { pin = pin.dropLast(1) },
                    modifier = Modifier.weight(1f),
                    enabled = pin.isNotEmpty() && limited == null,
                )
                YingLiButton(
                    text = "0",
                    onClick = { if (pin.length < MAX_PIN_LENGTH) pin += "0" },
                    modifier = Modifier.weight(1f),
                    enabled = limited == null,
                )
                YingLiButton(
                    text = stringResource(R.string.app_lock_unlock),
                    onClick = { onUnlock(pin); pin = "" },
                    modifier = Modifier.weight(1f),
                    enabled = pin.length >= MIN_PIN_LENGTH && limited == null,
                )
            }
            if (state.machine.policy.mode == seeyuer.yingli.player.domain.security.AppLockMode.BIOMETRIC) {
                YingLiButton(
                    text = stringResource(R.string.app_lock_biometric),
                    onClick = onBiometric,
                    enabled = limited == null,
                    leadingIcon = YingLiIcon.LOCK,
                )
            }
        }
    }
}

@Composable
fun VaultRoute(
    viewModel: VaultViewModel,
    onImport: () -> Unit,
    onPlay: (VaultItemId) -> Unit,
    onExport: (VaultItemId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    VaultScreen(
        state,
        onImport,
        onPlay,
        onExport,
        viewModel::requestDelete,
        viewModel::dismissDelete,
        viewModel::confirmDelete,
        modifier,
    )
}

@Composable
fun VaultScreen(
    state: VaultUiState,
    onImport: () -> Unit,
    onPlay: (VaultItemId) -> Unit,
    onExport: (VaultItemId) -> Unit,
    onDelete: (VaultItemId) -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(YingLiTheme.components.pagePadding),
        verticalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing),
    ) {
        YingLiButton(
            text = stringResource(R.string.vault_import),
            onClick = onImport,
            enabled = !state.working,
            leadingIcon = YingLiIcon.BACKUP_IMPORT,
        )
        state.statusCode?.let {
            YingLiBanner(
                message = stringResource(R.string.vault_status, it),
                kind = if (it.endsWith("SUCCEEDED")) BannerKind.SUCCESS else BannerKind.WARNING,
            )
        }
        if (state.items.isEmpty()) {
            YingLiEmptyState(
                title = stringResource(R.string.vault_empty_title),
                message = stringResource(R.string.vault_empty_message),
                modifier = Modifier.weight(1f),
            )
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing),
            ) {
                items(state.items.size, key = { state.items[it].id.value }) { index ->
                    VaultItemRow(state.items[index], onPlay, onExport, onDelete)
                }
            }
        }
    }
    state.pendingDelete?.let {
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text(stringResource(R.string.vault_delete_title)) },
            text = { Text(stringResource(R.string.vault_delete_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) { Text(stringResource(R.string.vault_delete)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissDelete) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun VaultItemRow(
    item: VaultItem,
    onPlay: (VaultItemId) -> Unit,
    onExport: (VaultItemId) -> Unit,
    onDelete: (VaultItemId) -> Unit,
) {
    Surface(color = YingLiTheme.colors.surfaceComponent, shape = YingLiTheme.components.componentCorner) {
        Column(
            Modifier.fillMaxWidth().padding(YingLiTheme.components.pagePadding),
            verticalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing),
        ) {
            Text(stringResource(R.string.vault_item, item.id.value.take(8)), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.vault_encrypted_size, item.encryptedBytes / (1024 * 1024)))
            Row(horizontalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing)) {
                YingLiButton(stringResource(R.string.vault_play), { onPlay(item.id) }, leadingIcon = YingLiIcon.PLAY)
                YingLiButton(stringResource(R.string.vault_export), { onExport(item.id) }, leadingIcon = YingLiIcon.BACKUP_EXPORT)
                YingLiButton(stringResource(R.string.vault_delete), { onDelete(item.id) }, leadingIcon = YingLiIcon.WARNING)
            }
        }
    }
}

private const val MIN_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 12
