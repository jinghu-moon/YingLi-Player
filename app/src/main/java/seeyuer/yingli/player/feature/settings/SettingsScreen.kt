package seeyuer.yingli.player.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import seeyuer.yingli.player.R
import seeyuer.yingli.player.data.preferences.AppearanceSettings
import seeyuer.yingli.player.data.preferences.LibraryLayoutPreference
import seeyuer.yingli.player.core.designsystem.component.YingLiBanner
import seeyuer.yingli.player.core.designsystem.component.YingLiButton
import seeyuer.yingli.player.core.designsystem.component.YingLiCheckbox
import seeyuer.yingli.player.core.designsystem.component.YingLiSegmentedControl
import seeyuer.yingli.player.core.designsystem.component.YingLiSlider
import seeyuer.yingli.player.core.designsystem.component.YingLiSwitch
import seeyuer.yingli.player.core.designsystem.component.BannerKind
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.domain.playback.PlayerPreferences
import seeyuer.yingli.player.domain.settings.BackupConflictStrategy
import seeyuer.yingli.player.domain.settings.BackupSelection

data class SettingsToolActions(
    val state: SettingsToolsState = SettingsToolsState(),
    val onLibraryLayoutChanged: (LibraryLayoutPreference) -> Unit = {},
    val onThumbnailScaleChanged: (Float) -> Unit = {},
    val onTrashRetentionDaysChanged: (Int) -> Unit = {},
    val onBackupSelectionChanged: (BackupSelection) -> Unit = {},
    val onBackup: () -> Unit = {},
    val onRestore: () -> Unit = {},
    val onConfirmRestore: (BackupConflictStrategy) -> Unit = {},
    val onDismissRestore: () -> Unit = {},
    val onDiagnostics: () -> Unit = {},
    val onCheckUpdates: () -> Unit = {},
    val onOpenRelease: (String) -> Unit = {},
)

data class SecuritySettingsActions(
    val lockEnabled: Boolean = false,
    val statusCode: String? = null,
    val onEnablePin: (String) -> Unit = {},
    val onDisableLock: () -> Unit = {},
    val biometricAvailable: Boolean = false,
    val onEnableBiometric: () -> Unit = {},
    val onOpenVault: () -> Unit = {},
)

@Composable
fun SettingsScreen(
    settings: AppearanceSettings,
    modifier: Modifier = Modifier,
    playerPreferences: PlayerPreferences = PlayerPreferences(),
    onMiniPlayerChanged: (Boolean) -> Unit = {},
    onAutoPipChanged: (Boolean) -> Unit = {},
    tools: SettingsToolActions = SettingsToolActions(),
    security: SecuritySettingsActions = SecuritySettingsActions(),
) {
    var showPinDialog by remember { mutableStateOf(false) }
    var newPin by remember { mutableStateOf("") }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(YingLiTheme.components.pagePadding),
        verticalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing),
    ) {
        Text(stringResource(R.string.settings_security), style = MaterialTheme.typography.titleLarge)
        if (security.statusCode != null) {
            YingLiBanner(
                message = stringResource(R.string.settings_security_status, security.statusCode),
                kind = BannerKind.WARNING,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing),
        ) {
            YingLiButton(
                text = if (security.lockEnabled) stringResource(R.string.settings_change_pin)
                else stringResource(R.string.settings_enable_app_lock),
                onClick = { newPin = ""; showPinDialog = true },
                modifier = Modifier.weight(1f),
                leadingIcon = YingLiIcon.LOCK,
            )
            YingLiButton(
                text = stringResource(R.string.settings_disable_app_lock),
                onClick = security.onDisableLock,
                modifier = Modifier.weight(1f),
                enabled = security.lockEnabled,
                leadingIcon = YingLiIcon.WARNING,
            )
        }
        YingLiButton(
            text = stringResource(R.string.settings_open_vault),
            onClick = security.onOpenVault,
            leadingIcon = YingLiIcon.LOCK,
        )
        YingLiButton(
            text = stringResource(R.string.settings_enable_biometric),
            onClick = security.onEnableBiometric,
            enabled = security.lockEnabled && security.biometricAvailable,
            leadingIcon = YingLiIcon.LOCK,
        )

        Text(stringResource(R.string.settings_library), style = MaterialTheme.typography.titleLarge)
        YingLiSegmentedControl(
            options = listOf(
                stringResource(R.string.library_layout_grid),
                stringResource(R.string.library_layout_list),
            ),
            selectedIndex = LibraryLayoutPreference.entries.indexOf(settings.libraryLayout),
            onSelected = { tools.onLibraryLayoutChanged(LibraryLayoutPreference.entries[it]) },
        )
        YingLiSlider(
            value = settings.thumbnailScale,
            onValueChange = tools.onThumbnailScaleChanged,
            label = stringResource(R.string.settings_thumbnail_scale, settings.thumbnailScale),
            valueRange = AppearanceSettings.MIN_THUMBNAIL_SCALE..AppearanceSettings.MAX_THUMBNAIL_SCALE,
        )
        Text(stringResource(R.string.settings_trash_retention), style = MaterialTheme.typography.labelLarge)
        val retentionOptions = listOf(7, 30, 90)
        YingLiSegmentedControl(
            options = retentionOptions.map { stringResource(R.string.settings_days, it) },
            selectedIndex = retentionOptions.indexOf(settings.trashRetentionDays).coerceAtLeast(0),
            onSelected = { tools.onTrashRetentionDaysChanged(retentionOptions[it]) },
        )

        Text(stringResource(R.string.settings_playback), style = MaterialTheme.typography.titleLarge)
        YingLiSwitch(
            label = stringResource(R.string.settings_mini_player),
            checked = playerPreferences.miniPlayerEnabled,
            onCheckedChange = onMiniPlayerChanged,
        )
        YingLiSwitch(
            label = stringResource(R.string.settings_auto_pip),
            checked = playerPreferences.autoPictureInPicture,
            onCheckedChange = onAutoPipChanged,
        )

        Text(stringResource(R.string.settings_data), style = MaterialTheme.typography.titleLarge)
        YingLiCheckbox(
            label = stringResource(R.string.settings_backup_preferences),
            checked = tools.state.selection.preferences,
            onCheckedChange = { tools.onBackupSelectionChanged(tools.state.selection.copy(preferences = it)) },
        )
        YingLiCheckbox(
            label = stringResource(R.string.settings_backup_organize),
            checked = tools.state.selection.organize,
            onCheckedChange = { tools.onBackupSelectionChanged(tools.state.selection.copy(organize = it)) },
        )
        YingLiCheckbox(
            label = stringResource(R.string.settings_backup_history),
            checked = tools.state.selection.history,
            onCheckedChange = { tools.onBackupSelectionChanged(tools.state.selection.copy(history = it)) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing),
        ) {
            YingLiButton(
                text = stringResource(R.string.settings_export_backup),
                onClick = tools.onBackup,
                modifier = Modifier.weight(1f),
                enabled = !tools.state.selection.isEmpty && tools.state.status != SettingsOperationStatus.WORKING,
                leadingIcon = YingLiIcon.BACKUP_EXPORT,
            )
            YingLiButton(
                text = stringResource(R.string.settings_import_backup),
                onClick = tools.onRestore,
                modifier = Modifier.weight(1f),
                enabled = tools.state.status != SettingsOperationStatus.WORKING,
                leadingIcon = YingLiIcon.BACKUP_IMPORT,
            )
        }
        YingLiButton(
            text = stringResource(R.string.settings_export_diagnostics),
            onClick = tools.onDiagnostics,
            enabled = tools.state.status != SettingsOperationStatus.WORKING,
            leadingIcon = YingLiIcon.DIAGNOSTICS,
        )

        Text(stringResource(R.string.settings_updates), style = MaterialTheme.typography.titleLarge)
        YingLiButton(
            text = stringResource(R.string.settings_check_updates),
            onClick = tools.onCheckUpdates,
            enabled = tools.state.status != SettingsOperationStatus.WORKING,
            leadingIcon = YingLiIcon.UPDATE,
        )
        tools.state.release?.let { release ->
            Text(release.title, style = MaterialTheme.typography.bodyLarge)
            YingLiButton(
                text = stringResource(R.string.settings_open_release),
                onClick = { tools.onOpenRelease(release.pageUrl) },
                leadingIcon = YingLiIcon.UPDATE,
            )
        }
        statusMessage(tools.state.status)?.let { (message, kind) -> YingLiBanner(message, kind) }
    }

    tools.state.preview?.let { preview ->
        AlertDialog(
            onDismissRequest = tools.onDismissRestore,
            title = { Text(stringResource(R.string.settings_restore_preview_title)) },
            text = {
                Text(stringResource(
                    R.string.settings_restore_preview_message,
                    preview.preferenceCount,
                    preview.tagCount,
                    preview.favoriteCount,
                    preview.playlistCount,
                    preview.collectionCount,
                    preview.historyCount,
                    preview.conflictCount,
                ))
            },
            confirmButton = {
                TextButton(onClick = { tools.onConfirmRestore(BackupConflictStrategy.KEEP_EXISTING) }) {
                    Text(stringResource(R.string.settings_restore_keep))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { tools.onConfirmRestore(BackupConflictStrategy.REPLACE) }) {
                        Text(stringResource(R.string.settings_restore_replace))
                    }
                    TextButton(onClick = tools.onDismissRestore) { Text(stringResource(R.string.action_cancel)) }
                }
            },
            shape = YingLiTheme.components.componentCorner,
        )
    }
    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false; newPin = "" },
            title = { Text(stringResource(R.string.settings_set_pin)) },
            text = {
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { value -> newPin = value.filter(Char::isDigit).take(12) },
                    label = { Text(stringResource(R.string.settings_pin_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        security.onEnablePin(newPin)
                        newPin = ""
                        showPinDialog = false
                    },
                    enabled = newPin.length in 4..12,
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false; newPin = "" }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun statusMessage(status: SettingsOperationStatus): Pair<String, BannerKind>? = when (status) {
    SettingsOperationStatus.IDLE -> null
    SettingsOperationStatus.WORKING -> stringResource(R.string.settings_working) to BannerKind.INFO
    SettingsOperationStatus.BACKUP_SAVED -> stringResource(R.string.settings_backup_saved) to BannerKind.SUCCESS
    SettingsOperationStatus.RESTORE_COMPLETED -> stringResource(R.string.settings_restore_completed) to BannerKind.SUCCESS
    SettingsOperationStatus.DIAGNOSTICS_SAVED -> stringResource(R.string.settings_diagnostics_saved) to BannerKind.SUCCESS
    SettingsOperationStatus.INVALID_DOCUMENT -> stringResource(R.string.settings_invalid_document) to BannerKind.ERROR
    SettingsOperationStatus.OPERATION_FAILED -> stringResource(R.string.settings_operation_failed) to BannerKind.ERROR
    SettingsOperationStatus.UP_TO_DATE -> stringResource(R.string.settings_up_to_date) to BannerKind.SUCCESS
    SettingsOperationStatus.UPDATE_UNAVAILABLE -> stringResource(R.string.settings_update_unavailable) to BannerKind.WARNING
    SettingsOperationStatus.UPDATE_RATE_LIMITED -> stringResource(R.string.settings_update_rate_limited) to BannerKind.INFO
}
