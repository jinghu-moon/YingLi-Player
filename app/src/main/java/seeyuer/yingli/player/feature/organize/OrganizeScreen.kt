package seeyuer.yingli.player.feature.organize

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.component.YingLiButton
import seeyuer.yingli.player.core.designsystem.component.YingLiBanner
import seeyuer.yingli.player.core.designsystem.component.YingLiCheckbox
import seeyuer.yingli.player.core.designsystem.component.YingLiSegmentedControl
import seeyuer.yingli.player.core.designsystem.component.YingLiTextField
import seeyuer.yingli.player.core.designsystem.component.BannerKind
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.core.designsystem.tokens.YingLiTagColorTokens
import seeyuer.yingli.player.domain.organize.OrganizeMutationResult
import seeyuer.yingli.player.domain.organize.OrganizedAction
import seeyuer.yingli.player.domain.organize.TagColor
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.domain.duplicates.DuplicateEvidence
import seeyuer.yingli.player.domain.duplicates.DuplicateGroup
import seeyuer.yingli.player.domain.duplicates.DuplicateGroupId
import seeyuer.yingli.player.domain.duplicates.DuplicateMode

@Composable
fun OrganizeRoute(viewModel: OrganizeViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OrganizeScreen(
        state,
        viewModel::openEditor,
        viewModel::closeEditor,
        viewModel::setName,
        viewModel::setTagColor,
        viewModel::save,
        viewModel::setDuplicateMode,
        viewModel::scanDuplicates,
        viewModel::cancelDuplicateScan,
        viewModel::toggleDuplicateTrash,
        viewModel::ignoreDuplicateGroup,
        viewModel::requestDuplicateDeletion,
        viewModel::dismissDuplicateDeletion,
        viewModel::confirmDuplicateDeletion,
        modifier,
    )
}

@Composable
fun OrganizeScreen(
    state: OrganizeUiState,
    onOpenEditor: (OrganizeEditorKind) -> Unit,
    onCloseEditor: () -> Unit,
    onNameChange: (String) -> Unit,
    onColorChange: (TagColor) -> Unit,
    onSave: () -> Unit,
    onDuplicateMode: (DuplicateMode) -> Unit,
    onScanDuplicates: () -> Unit,
    onCancelDuplicateScan: () -> Unit,
    onToggleDuplicateTrash: (DuplicateGroupId, MediaItemId) -> Unit,
    onIgnoreDuplicateGroup: (DuplicateGroupId) -> Unit,
    onRequestDuplicateDeletion: (DuplicateGroupId) -> Unit,
    onDismissDuplicateDeletion: () -> Unit,
    onConfirmDuplicateDeletion: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(YingLiTheme.components.pagePadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OrganizeEntry(stringResource(R.string.organize_favorites), state.snapshot.favorites.size, null)
            OrganizeEntry(stringResource(R.string.organize_tags), state.snapshot.tags.size) { onOpenEditor(OrganizeEditorKind.TAG) }
            OrganizeEntry(stringResource(R.string.organize_playlists), state.snapshot.playlists.size) { onOpenEditor(OrganizeEditorKind.PLAYLIST) }
            OrganizeEntry(stringResource(R.string.organize_smart_collections), state.snapshot.collections.count { it.filter != null }) {
                onOpenEditor(OrganizeEditorKind.SMART_COLLECTION)
            }
        }
        if (state.snapshot.tags.isNotEmpty()) {
            item { Text(stringResource(R.string.organize_tags), style = MaterialTheme.typography.titleMedium) }
            items(state.snapshot.tags, key = { it.id.value }) { tag ->
                Surface(color = YingLiTheme.colors.surfaceComponent) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ColorDot(tag.color, selected = false)
                        Text(tag.name, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
        if (state.snapshot.recentlyOrganized.isNotEmpty()) {
            item { Text(stringResource(R.string.organize_recent), style = MaterialTheme.typography.titleMedium) }
            items(state.snapshot.recentlyOrganized, key = { it.mediaId.value }) { entry ->
                Text(
                    text = "${entry.action.label()} · ${entry.mediaId.value}",
                    color = YingLiTheme.colors.textSecondary,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
        }
        item {
            Text(stringResource(R.string.duplicates_title), style = MaterialTheme.typography.titleLarge)
            YingLiSegmentedControl(
                options = listOf(
                    stringResource(R.string.duplicates_exact),
                    stringResource(R.string.duplicates_similar),
                ),
                selectedIndex = state.duplicateMode.ordinal,
                onSelected = { onDuplicateMode(DuplicateMode.entries[it]) },
            )
        }
        state.duplicateStatusCode?.let { code ->
            item {
                YingLiBanner(
                    message = duplicateStatusMessage(code),
                    kind = if (code.startsWith("SCAN_COMPLETED") || code.startsWith("TRASH_COMPLETED")) {
                        BannerKind.SUCCESS
                    } else {
                        BannerKind.WARNING
                    },
                )
            }
        }
        item {
            YingLiButton(
                text = if (state.duplicateScanning) stringResource(R.string.duplicates_cancel_scan)
                else stringResource(R.string.duplicates_scan),
                onClick = if (state.duplicateScanning) onCancelDuplicateScan else onScanDuplicates,
                leadingIcon = if (state.duplicateScanning) YingLiIcon.WARNING else YingLiIcon.PROCESSING,
            )
        }
        if (state.duplicateMode == DuplicateMode.SIMILAR) {
            item {
                YingLiBanner(
                    message = stringResource(R.string.duplicates_similar_disabled),
                    kind = BannerKind.INFO,
                )
            }
        }
        items(
            state.duplicateGroups.filter { it.mode == state.duplicateMode },
            key = { it.id.value },
        ) { group ->
            DuplicateGroupItem(
                group,
                state.duplicateSelections[group.id].orEmpty(),
                onToggleDuplicateTrash,
                onIgnoreDuplicateGroup,
                onRequestDuplicateDeletion,
            )
        }
    }
    state.editorKind?.let { kind ->
        AlertDialog(
            onDismissRequest = onCloseEditor,
            title = { Text(kind.title()) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    YingLiTextField(
                        value = state.editorName,
                        onValueChange = onNameChange,
                        label = stringResource(R.string.organize_name),
                    )
                    if (kind == OrganizeEditorKind.TAG) {
                        Text(stringResource(R.string.organize_tag_color))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            TagColor.entries.forEach { color ->
                                ColorDot(
                                    color = color,
                                    selected = color == state.tagColor,
                                    onClick = { onColorChange(color) },
                                )
                            }
                        }
                    }
                    when (state.mutationResult) {
                        OrganizeMutationResult.NameConflict -> Text(
                            stringResource(R.string.organize_name_conflict),
                            color = MaterialTheme.colorScheme.error,
                        )
                        OrganizeMutationResult.InvalidInput -> Text(
                            stringResource(R.string.organize_invalid_name),
                            color = MaterialTheme.colorScheme.error,
                        )
                        OrganizeMutationResult.RetryableFailure -> Text(
                            stringResource(R.string.organize_save_failed),
                            color = MaterialTheme.colorScheme.error,
                        )
                        else -> Unit
                    }
                }
            },
            confirmButton = { TextButton(onClick = onSave) { Text(stringResource(R.string.organize_save)) } },
            dismissButton = { TextButton(onClick = onCloseEditor) { Text(stringResource(R.string.library_cancel)) } },
        )
    }
    state.pendingDeletion?.let {
        AlertDialog(
            onDismissRequest = onDismissDuplicateDeletion,
            title = { Text(stringResource(R.string.duplicates_confirm_title)) },
            text = { Text(stringResource(R.string.duplicates_confirm_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmDuplicateDeletion) {
                    Text(stringResource(R.string.duplicates_move_to_trash))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDuplicateDeletion) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun DuplicateGroupItem(
    group: DuplicateGroup,
    selectedForTrash: Set<MediaItemId>,
    onToggle: (DuplicateGroupId, MediaItemId) -> Unit,
    onIgnore: (DuplicateGroupId) -> Unit,
    onDelete: (DuplicateGroupId) -> Unit,
) {
    Surface(color = YingLiTheme.colors.surfaceComponent, shape = YingLiTheme.components.componentCorner) {
        Column(
            Modifier.fillMaxWidth().padding(YingLiTheme.components.pagePadding),
            verticalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing),
        ) {
            Text(group.evidence.summary(), style = MaterialTheme.typography.titleMedium)
            group.candidates.forEach { candidate ->
                val fingerprint = candidate.fingerprint
                YingLiCheckbox(
                    label = stringResource(
                        R.string.duplicates_candidate,
                        candidate.mediaId.value,
                        fingerprint.width ?: 0,
                        fingerprint.height ?: 0,
                        fingerprint.sizeBytes.toMegabytes(),
                    ),
                    checked = candidate.mediaId in selectedForTrash,
                    onCheckedChange = { onToggle(group.id, candidate.mediaId) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing),
            ) {
                YingLiButton(
                    text = stringResource(R.string.duplicates_ignore),
                    onClick = { onIgnore(group.id) },
                    modifier = Modifier.weight(1f),
                )
                YingLiButton(
                    text = stringResource(R.string.duplicates_move_to_trash),
                    onClick = { onDelete(group.id) },
                    modifier = Modifier.weight(1f),
                    enabled = selectedForTrash.isNotEmpty(),
                    leadingIcon = YingLiIcon.WARNING,
                )
            }
        }
    }
}

@Composable
private fun DuplicateEvidence.summary(): String = when (this) {
    is DuplicateEvidence.Exact -> stringResource(R.string.duplicates_exact_evidence, fullHash.take(12))
    is DuplicateEvidence.Similar -> stringResource(R.string.duplicates_similar_evidence, (overallScore * 100).toInt())
}

@Composable
private fun duplicateStatusMessage(code: String): String = when {
    code.startsWith("SCAN_COMPLETED_") -> stringResource(
        R.string.duplicates_scan_completed,
        code.substringAfterLast('_').toIntOrNull() ?: 0,
    )
    code.startsWith("TRASH_COMPLETED_") -> stringResource(
        R.string.duplicates_trash_completed,
        code.substringAfterLast('_').toIntOrNull() ?: 0,
    )
    code == "SIMILAR_EXPERIMENT_DISABLED" -> stringResource(R.string.duplicates_similar_disabled)
    code == "SCAN_CANCELED" -> stringResource(R.string.duplicates_scan_canceled)
    else -> stringResource(R.string.duplicates_operation_failed, code)
}

private fun Long.toMegabytes(): Long = (this + 1024 * 1024 - 1) / (1024 * 1024)

@Composable
private fun OrganizeEntry(title: String, count: Int, onCreate: (() -> Unit)?) {
    Surface(color = YingLiTheme.colors.surfaceComponent) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.organize_item_count, count), color = YingLiTheme.colors.textSecondary)
            }
            onCreate?.let { YingLiButton(stringResource(R.string.organize_create), it) }
        }
    }
}

@Composable
private fun ColorDot(color: TagColor, selected: Boolean, onClick: (() -> Unit)? = null) {
    val modifier = Modifier
        .size(if (selected) 32.dp else 28.dp)
        .background(color.value(), CircleShape)
        .semantics {
            role = Role.RadioButton
            this.selected = selected
            contentDescription = color.name.lowercase()
        }
        .let { base -> if (onClick == null) base else base.clickable(onClick = onClick) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (selected) Box(Modifier.size(10.dp).background(YingLiTheme.player.controlPrimary, CircleShape))
    }
}

private fun TagColor.value(): Color = when (this) {
    TagColor.NEUTRAL -> YingLiTagColorTokens.Neutral
    TagColor.RED -> YingLiTagColorTokens.Red
    TagColor.ORANGE -> YingLiTagColorTokens.Orange
    TagColor.YELLOW -> YingLiTagColorTokens.Yellow
    TagColor.GREEN -> YingLiTagColorTokens.Green
    TagColor.BLUE -> YingLiTagColorTokens.Blue
    TagColor.INDIGO -> YingLiTagColorTokens.Indigo
    TagColor.VIOLET -> YingLiTagColorTokens.Violet
}

@Composable
private fun OrganizeEditorKind.title(): String = stringResource(when (this) {
    OrganizeEditorKind.TAG -> R.string.organize_create_tag
    OrganizeEditorKind.PLAYLIST -> R.string.organize_create_playlist
    OrganizeEditorKind.SMART_COLLECTION -> R.string.organize_create_smart_collection
})

@Composable
private fun OrganizedAction.label(): String = stringResource(when (this) {
    OrganizedAction.FAVORITED -> R.string.organize_favorited
    OrganizedAction.TAGGED -> R.string.organize_tagged
    OrganizedAction.PLAYLISTED -> R.string.organize_playlisted
    OrganizedAction.COLLECTED -> R.string.organize_collected
})
