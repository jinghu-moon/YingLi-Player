package seeyuer.yingli.player.feature.organize

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import seeyuer.yingli.player.R
import seeyuer.yingli.player.core.designsystem.component.BannerKind
import seeyuer.yingli.player.core.designsystem.component.YingLiBanner
import seeyuer.yingli.player.core.designsystem.component.YingLiButton
import seeyuer.yingli.player.core.designsystem.component.YingLiCheckbox
import seeyuer.yingli.player.core.designsystem.component.YingLiIconButton
import seeyuer.yingli.player.core.designsystem.component.YingLiSegmentedControl
import seeyuer.yingli.player.core.designsystem.component.YingLiTextField
import seeyuer.yingli.player.core.designsystem.icon.YingLiIcon
import seeyuer.yingli.player.core.designsystem.icon.imageVector
import seeyuer.yingli.player.core.designsystem.theme.YingLiTheme
import seeyuer.yingli.player.core.designsystem.tokens.YingLiTagColorTokens
import seeyuer.yingli.player.core.model.media.MediaItemId
import seeyuer.yingli.player.core.model.media.MediaLocationId
import seeyuer.yingli.player.domain.duplicates.DuplicateGroup
import seeyuer.yingli.player.domain.duplicates.DuplicateGroupId
import seeyuer.yingli.player.domain.duplicates.DuplicateMode
import seeyuer.yingli.player.domain.recycle.TrashEntry
import seeyuer.yingli.player.domain.organize.OrganizeMutationResult
import seeyuer.yingli.player.domain.organize.OrganizedAction
import seeyuer.yingli.player.domain.organize.TagColor
import java.util.Locale

@Composable
fun OrganizeRoute(viewModel: OrganizeViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 系统授权对话框只能由前台拉起（§8.4 第 9 步）。sender 由存储层按 token 现算，
    // 页面只负责把它交给系统、再把结果回报给状态机。
    var pendingResult by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val callback = pendingResult
        pendingResult = null
        callback?.invoke(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(state.pendingTrashAuthorization?.token) {
        val request = state.pendingTrashAuthorization ?: return@LaunchedEffect
        val sender = viewModel.authorizationIntent(request.token)
        if (sender == null) {
            // 拿不到系统对话框（进程重启导致 token 失效）：按拒绝处理。
            // 拒绝是合法结局——源文件保持原样，副本会被退回 recovery/，不会丢唯一副本。
            viewModel.resolveTrashAuthorization(false)
            return@LaunchedEffect
        }
        pendingResult = { granted -> viewModel.resolveTrashAuthorization(granted) }
        launcher.launch(IntentSenderRequest.Builder(sender).build())
    }
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
        viewModel::openTrash,
        viewModel::closeTrash,
        viewModel::restore,
        viewModel::requestPurge,
        viewModel::dismissPurge,
        viewModel::confirmPurge,
        viewModel::requestClearTrash,
        viewModel::dismissClearTrash,
        viewModel::confirmClearTrash,
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
    onToggleDuplicateTrash: (DuplicateGroupId, MediaLocationId) -> Unit,
    onIgnoreDuplicateGroup: (DuplicateGroupId) -> Unit,
    onRequestDuplicateDeletion: (DuplicateGroupId) -> Unit,
    onDismissDuplicateDeletion: () -> Unit,
    onConfirmDuplicateDeletion: () -> Unit,
    onOpenTrash: () -> Unit,
    onCloseTrash: () -> Unit,
    onRestore: (TrashEntry) -> Unit,
    onRequestPurge: (TrashEntry) -> Unit,
    onDismissPurge: () -> Unit,
    onConfirmPurge: () -> Unit,
    onRequestClearTrash: () -> Unit,
    onDismissClearTrash: () -> Unit,
    onConfirmClearTrash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = PAGE_HORIZONTAL_PADDING, vertical = PAGE_HORIZONTAL_PADDING),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OrganizePageHeading()
            Spacer(Modifier.height(19.dp))
            OrganizeOverviewCard(
                videoCount = state.libraryVideoCount,
                videoBytes = state.libraryVideoBytes,
                onOpenTrash = onOpenTrash,
            )
            Spacer(Modifier.height(25.dp))
            OrganizeSectionHead()
            Spacer(Modifier.height(11.dp))
            RecycleBinToolCard(trashItemCount = state.trashItems.size, onClick = onOpenTrash)
        }
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
            Text(
                text = stringResource(R.string.duplicates_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.W700,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
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
                    message = duplicateStatusMessage(code, state.duplicateGroups.size),
                    kind = if (
                        code.startsWith("TRASH_COMPLETED") ||
                        code == OrganizeViewModel.SCAN_ENQUEUED ||
                        code == OrganizeViewModel.SCAN_COMPLETED
                    ) {
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
        val visibleGroups = if (state.duplicateMode == DuplicateMode.EXACT) state.duplicateGroups else emptyList()
        items(
            visibleGroups,
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
    if (state.trashSheetOpen) {
        TrashSheet(
            state = state,
            onClose = onCloseTrash,
            onRestore = onRestore,
            onRequestPurge = onRequestPurge,
            onDismissPurge = onDismissPurge,
            onConfirmPurge = onConfirmPurge,
            onRequestClearTrash = onRequestClearTrash,
            onDismissClearTrash = onDismissClearTrash,
            onConfirmClearTrash = onConfirmClearTrash,
        )
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
    selectedForTrash: Set<MediaLocationId>,
    onToggle: (DuplicateGroupId, MediaLocationId) -> Unit,
    onIgnore: (DuplicateGroupId) -> Unit,
    onDelete: (DuplicateGroupId) -> Unit,
) {
    Surface(color = YingLiTheme.colors.surfaceComponent, shape = YingLiTheme.components.componentCorner) {
        Column(
            Modifier.fillMaxWidth().padding(YingLiTheme.components.pagePadding),
            verticalArrangement = Arrangement.spacedBy(YingLiTheme.components.itemSpacing),
        ) {
            Text(group.summary(), style = MaterialTheme.typography.titleMedium)
            group.sortedCandidates().forEach { candidate ->
                YingLiCheckbox(
                    label = stringResource(
                        R.string.duplicates_candidate,
                        candidate.fileName,
                        candidate.width ?: 0,
                        candidate.height ?: 0,
                        candidate.sizeBytes.toMegabytes(),
                    ),
                    checked = candidate.locationId in selectedForTrash,
                    onCheckedChange = { onToggle(group.id, candidate.locationId) },
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
private fun DuplicateGroup.summary(): String =
    stringResource(R.string.duplicates_exact_evidence, contentHash.take(12))

@Composable
private fun duplicateStatusMessage(code: String, groupCount: Int): String = when {
    code.startsWith("TRASH_COMPLETED_") -> stringResource(
        R.string.duplicates_trash_completed,
        code.substringAfterLast('_').toIntOrNull() ?: 0,
    )
    code == OrganizeViewModel.SCAN_ENQUEUED -> stringResource(R.string.duplicates_scan_enqueued)
    code == OrganizeViewModel.SCAN_ENQUEUE_FAILED -> stringResource(R.string.duplicates_scan_enqueue_failed)
    code == OrganizeViewModel.SCAN_COMPLETED -> stringResource(R.string.duplicates_scan_completed, groupCount)
    code == "SIMILAR_EXPERIMENT_DISABLED" -> stringResource(R.string.duplicates_similar_disabled)
    code == OrganizeViewModel.SCAN_CANCELED -> stringResource(R.string.duplicates_scan_canceled)
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

// ---------------------------------------------------------------------------
// 整理页页头 / 概览 / 工具网格（对齐 organize-page-demo.html）
// ---------------------------------------------------------------------------

/** 设计稿 `.scroll-area` 在手机宽度（≤430px）下的内边距，1 CSS px ≈ 1 dp。 */
private val PAGE_HORIZONTAL_PADDING = 18.dp

/**
 * 设计稿页头：`.eyebrow` + `.page-title` + `.page-subtitle`。
 *
 * 设计稿的 eyebrow 文案是英文 "VIDEO WORKSPACE"，本仓没有英文 UI 文案，改用中文「视频整理」。
 */
@Composable
private fun OrganizePageHeading() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.organize_eyebrow),
            color = YingLiTheme.colors.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.W600,
            letterSpacing = 1.4.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.nav_organize),
            color = YingLiTheme.colors.textPrimary,
            fontSize = 30.sp,
            fontWeight = FontWeight.W700,
            letterSpacing = (-1.35).sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.organize_subtitle),
            color = YingLiTheme.colors.textSecondary,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun OrganizeOverviewCard(videoCount: Int, videoBytes: Long, onOpenTrash: () -> Unit) {
    val inverse = YingLiTheme.colors.textInverse
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = YingLiTheme.colors.surfaceInverse,
        contentColor = inverse,
        shape = RoundedCornerShape(21.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 17.dp, end = 17.dp, top = 17.dp, bottom = 15.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.organize_library_label),
                        color = inverse.copy(alpha = 0.72f),
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(text = libraryCountLabel(videoCount), color = inverse)
                }
                OverviewIconBox()
            }
            Spacer(Modifier.height(15.dp))
            HorizontalDivider(color = inverse.copy(alpha = 0.16f))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = libraryUsageLabel(videoBytes),
                    modifier = Modifier.weight(1f),
                    color = inverse.copy(alpha = 0.78f),
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .clickable(role = Role.Button, onClick = onOpenTrash)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.organize_open_trash),
                        color = inverse,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.W600,
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        imageVector = YingLiIcon.CHEVRON_RIGHT.imageVector,
                        contentDescription = null,
                        tint = inverse,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/** 概览卡右上角图标盒（设计稿 `.over-icon`：43dp、圆角 14dp、深色底 + 浅边框）。 */
@Composable
private fun OverviewIconBox() {
    val inverse = YingLiTheme.colors.textInverse
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .size(43.dp)
            .clip(shape)
            .background(inverse.copy(alpha = 0.08f))
            .border(1.dp, inverse.copy(alpha = 0.16f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = YingLiIcon.LIBRARY.imageVector,
            contentDescription = null,
            tint = inverse,
            modifier = Modifier.size(23.dp),
        )
    }
}

/** 设计稿 `.over-count`：「128」30sp 粗体 + 「个视频」13sp 浅色。 */
@Composable
private fun libraryCountLabel(videoCount: Int): AnnotatedString {
    val suffixColor = YingLiTheme.colors.textInverse.copy(alpha = 0.78f)
    return buildAnnotatedString {
        withStyle(SpanStyle(fontSize = 30.sp, fontWeight = FontWeight.W700)) {
            append(videoCount.toString())
        }
        append(' ')
        withStyle(SpanStyle(fontSize = 13.sp, color = suffixColor)) {
            append(stringResource(R.string.organize_library_count_suffix))
        }
    }
}

/** 设计稿 `.space-info`：「视频占用 **24.6 GB** · 回收站可恢复」，体积部分加粗。 */
@Composable
private fun libraryUsageLabel(videoBytes: Long): AnnotatedString {
    val strong = SpanStyle(fontWeight = FontWeight.W600, color = YingLiTheme.colors.textInverse)
    return buildAnnotatedString {
        append(stringResource(R.string.organize_library_usage_prefix))
        withStyle(strong) { append(formatFileSize(videoBytes)) }
        append(stringResource(R.string.organize_library_usage_suffix))
    }
}

@Composable
private fun OrganizeSectionHead() {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.organize_tools_title),
            modifier = Modifier.weight(1f),
            color = YingLiTheme.colors.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.W700,
        )
        Text(
            text = stringResource(R.string.organize_tools_note),
            color = YingLiTheme.colors.textSecondary,
            fontSize = 11.sp,
        )
    }
}

/**
 * 设计稿 `.tool-card.wide` 的回收站卡。
 *
 * 设计稿有四张工具卡（视频压缩 / 视频转码 / 查找重复视频 / 回收站）；本页只承载「回收站」——
 * 压缩与转码需要先选中媒体、其唯一入口是壳层的处理中心路由，在此加卡等于新增一条入口；
 * 「查找重复视频」本页已有内联区块，加卡即重复。这是与设计稿的已知偏差。
 */
@Composable
private fun RecycleBinToolCard(trashItemCount: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(19.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 98.dp)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick),
        color = YingLiTheme.colors.surface,
        contentColor = YingLiTheme.colors.textPrimary,
        shape = shape,
        border = BorderStroke(1.dp, YingLiTheme.colors.borderDefault),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 15.dp, end = 14.dp, top = 14.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolIconBox()
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.library_trash),
                    color = YingLiTheme.colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.W600,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (trashItemCount == 0) {
                        stringResource(R.string.organize_recycle_card_description_empty)
                    } else {
                        stringResource(R.string.organize_recycle_card_description)
                    },
                    color = YingLiTheme.colors.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(7.dp))
                MiniTag(stringResource(R.string.organize_recycle_card_count, trashItemCount))
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = YingLiIcon.CHEVRON_RIGHT.imageVector,
                contentDescription = null,
                tint = YingLiTheme.colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 设计稿 `.tool-icon`：38dp、圆角 13dp、浅灰底。 */
@Composable
private fun ToolIconBox() {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(YingLiTheme.colors.surfaceMuted),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = YingLiIcon.DELETE.imageVector,
            contentDescription = null,
            tint = YingLiTheme.colors.textPrimary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 设计稿 `.mini-tag`：10sp、圆角 7dp、细边框。 */
@Composable
private fun MiniTag(text: String) {
    Surface(
        color = Color.Transparent,
        contentColor = YingLiTheme.colors.textSecondary,
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(1.dp, YingLiTheme.colors.borderDefault),
    ) {
        Text(text = text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 10.sp)
    }
}

// ---------------------------------------------------------------------------
// 回收站底部面板（对齐设计稿的单个 .sheet 容器：确认态替换面板内容，而非再叠一层）
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashSheet(
    state: OrganizeUiState,
    onClose: () -> Unit,
    onRestore: (TrashEntry) -> Unit,
    onRequestPurge: (TrashEntry) -> Unit,
    onDismissPurge: () -> Unit,
    onConfirmPurge: () -> Unit,
    onRequestClearTrash: () -> Unit,
    onDismissClearTrash: () -> Unit,
    onConfirmClearTrash: () -> Unit,
) {
    val purgeTarget = state.pendingPurge
    val confirming = purgeTarget != null || state.clearTrashConfirmOpen
    val dismissConfirm = if (purgeTarget != null) onDismissPurge else onDismissClearTrash
    val acceptConfirm = if (purgeTarget != null) onConfirmPurge else onConfirmClearTrash
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = YingLiTheme.colors.surfaceSubtle,
        shape = RoundedCornerShape(topStart = 25.dp, topEnd = 25.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TrashSheetHandle()
            TrashSheetHead(
                title = if (confirming) {
                    stringResource(R.string.organize_trash_confirm_title)
                } else {
                    stringResource(R.string.library_trash)
                },
                subtitle = if (confirming) null else stringResource(R.string.organize_trash_sheet_subtitle),
                onClose = onClose,
            )
            when {
                purgeTarget != null -> TrashConfirmBody(
                    modifier = Modifier.weight(1f, fill = false),
                    title = stringResource(R.string.organize_trash_purge_confirm_title),
                    message = stringResource(
                        R.string.organize_trash_purge_confirm_message,
                        purgeTarget.entry.displayName(),
                    ),
                )
                state.clearTrashConfirmOpen -> TrashConfirmBody(
                    modifier = Modifier.weight(1f, fill = false),
                    title = stringResource(R.string.organize_trash_clear_confirm_title),
                    message = stringResource(
                        R.string.organize_trash_clear_confirm_message,
                        state.trashItems.size,
                    ),
                )
                else -> TrashListBody(
                    modifier = Modifier.weight(1f, fill = false),
                    entries = state.trashItems,
                    statusCode = state.trashStatusCode,
                    onRestore = onRestore,
                    onRequestPurge = onRequestPurge,
                )
            }
            TrashSheetFoot(
                confirming = confirming,
                empty = state.trashItems.isEmpty(),
                onDismissConfirm = dismissConfirm,
                onAcceptConfirm = acceptConfirm,
                onClose = onClose,
                onRequestClearTrash = onRequestClearTrash,
            )
        }
    }
}

/** 设计稿 `.sheet-handle`：35×4、圆角 99。 */
@Composable
private fun TrashSheetHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(35.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(YingLiTheme.colors.borderStrong),
        )
    }
}

@Composable
private fun TrashSheetHead(title: String, subtitle: String?, onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 21.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = YingLiTheme.colors.textPrimary,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.W700,
                    letterSpacing = (-0.8).sp,
                )
                subtitle?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(text = it, color = YingLiTheme.colors.textSecondary, fontSize = 12.sp)
                }
            }
            YingLiIconButton(
                icon = YingLiIcon.CLOSE,
                contentDescription = stringResource(R.string.organize_trash_close),
                onClick = onClose,
                tint = YingLiTheme.colors.textPrimary,
            )
        }
        HorizontalDivider(color = YingLiTheme.colors.borderDivider)
    }
}

@Composable
private fun TrashListBody(
    modifier: Modifier,
    entries: List<TrashItemUi>,
    statusCode: String?,
    onRestore: (TrashEntry) -> Unit,
    onRequestPurge: (TrashEntry) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 17.dp),
    ) {
        statusCode?.let { code ->
            item(key = "status") {
                YingLiBanner(
                    message = trashStatusMessage(code),
                    kind = trashStatusKind(code),
                    modifier = Modifier.padding(bottom = 17.dp),
                )
            }
        }
        if (entries.isEmpty()) {
            item(key = "empty") { TrashEmptyState(Modifier.fillMaxWidth()) }
        } else {
            item(key = "summary") {
                TrashSummaryCard(entries.size)
                Spacer(Modifier.height(17.dp))
                Text(
                    text = stringResource(R.string.organize_trash_recent),
                    modifier = Modifier.fillMaxWidth(),
                    color = YingLiTheme.colors.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W600,
                )
            }
            itemsIndexed(entries, key = { _, entry -> entry.entry.locationId.value }) { index, entry ->
                TrashItemRow(
                    item = entry,
                    showDivider = index != entries.lastIndex,
                    onRestore = onRestore,
                    onRequestPurge = onRequestPurge,
                )
            }
        }
    }
}

/** 设计稿 `.bin-summary`：深色卡 + 左「N 个项目」+ 右侧回收站符号。 */
@Composable
private fun TrashSummaryCard(count: Int) {
    val inverse = YingLiTheme.colors.textInverse
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = YingLiTheme.colors.surfaceInverse,
        contentColor = inverse,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.organize_trash_summary_count, count),
                    fontSize = 23.sp,
                    fontWeight = FontWeight.W700,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = stringResource(R.string.organize_trash_summary_note),
                    color = inverse.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                )
            }
            val shape = RoundedCornerShape(13.dp)
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(shape)
                    .background(inverse.copy(alpha = 0.08f))
                    .border(1.dp, inverse.copy(alpha = 0.16f), shape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = YingLiIcon.DELETE.imageVector,
                    contentDescription = null,
                    tint = inverse,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * 设计稿 `.bin-item`：缩略图 + 标题/元信息 + 「恢复」/「删除」行内动作。
 *
 * `TrashEntry` 不含文件名与体积，标题只能是媒体 id，元信息只能是「移入多久 · 剩余保留期」。
 */
@Composable
private fun TrashItemRow(
    item: TrashItemUi,
    showDivider: Boolean,
    onRestore: (TrashEntry) -> Unit,
    onRequestPurge: (TrashEntry) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 47.dp, height = 43.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(YingLiTheme.colors.surfaceMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = YingLiIcon.LIBRARY.imageVector,
                    contentDescription = null,
                    tint = YingLiTheme.colors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.entry.displayName(),
                    color = YingLiTheme.colors.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W600,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = item.meta(),
                    color = YingLiTheme.colors.textSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TrashMiniAction(stringResource(R.string.library_restore)) { onRestore(item.entry) }
            TrashMiniAction(stringResource(R.string.library_purge)) { onRequestPurge(item.entry) }
        }
        if (showDivider) {
            HorizontalDivider(color = YingLiTheme.colors.borderDivider)
        }
    }
}

/** 设计稿 `.mini-action`。视觉尺寸小，但触摸目标补足到 `minimumTouchTarget`。 */
@Composable
private fun TrashMiniAction(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = YingLiTheme.components.minimumTouchTarget)
            .clip(RoundedCornerShape(9.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = YingLiTheme.colors.textPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.W600,
        )
    }
}

/** 设计稿 `.bin-empty`：图标盒 + 强标题 + 说明。 */
@Composable
private fun TrashEmptyState(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(19.dp)
    Column(
        modifier = modifier.padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(shape)
                .background(YingLiTheme.colors.surface)
                .border(1.dp, YingLiTheme.colors.borderDefault, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = YingLiIcon.DELETE.imageVector,
                contentDescription = null,
                tint = YingLiTheme.colors.textSecondary,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.library_trash_empty),
            color = YingLiTheme.colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.W600,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.organize_trash_empty_message),
            modifier = Modifier.widthIn(max = 230.dp),
            color = YingLiTheme.colors.textSecondary,
            fontSize = 11.sp,
            lineHeight = 17.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** 设计稿 `.confirm-body`：居中图标盒 + 标题 + 说明。 */
@Composable
private fun TrashConfirmBody(modifier: Modifier, title: String, message: String) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(YingLiTheme.colors.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = YingLiIcon.DELETE.imageVector,
                contentDescription = null,
                tint = YingLiTheme.colors.textPrimary,
                modifier = Modifier.size(23.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            color = YingLiTheme.colors.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.W700,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            modifier = Modifier.widthIn(max = 270.dp),
            color = YingLiTheme.colors.textSecondary,
            fontSize = 11.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TrashSheetFoot(
    confirming: Boolean,
    empty: Boolean,
    onDismissConfirm: () -> Unit,
    onAcceptConfirm: () -> Unit,
    onClose: () -> Unit,
    onRequestClearTrash: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = YingLiTheme.colors.borderDivider)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 13.dp, bottom = 17.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                confirming -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    SheetSecondaryButton(
                        text = stringResource(R.string.action_back),
                        onClick = onDismissConfirm,
                        modifier = Modifier.weight(1f),
                    )
                    SheetDangerButton(
                        text = stringResource(R.string.organize_trash_confirm_action),
                        onClick = onAcceptConfirm,
                        modifier = Modifier.weight(1f),
                    )
                }
                empty -> YingLiButton(
                    text = stringResource(R.string.organize_trash_empty_action),
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> {
                    SheetSecondaryButton(
                        text = stringResource(R.string.organize_trash_clear),
                        onClick = onRequestClearTrash,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = YingLiIcon.DELETE,
                    )
                    Text(
                        text = stringResource(R.string.organize_trash_clear_note),
                        modifier = Modifier.fillMaxWidth(),
                        color = YingLiTheme.colors.textSecondary,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** 设计稿 `.secondary-btn`：白底 + 强边框。 */
@Composable
private fun SheetSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: YingLiIcon? = null,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 49.dp),
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, YingLiTheme.colors.borderStrong),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = YingLiTheme.colors.surface,
            contentColor = YingLiTheme.colors.textPrimary,
        ),
    ) {
        leadingIcon?.let {
            Icon(
                imageVector = it.imageVector,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(text = text, fontSize = 13.sp, fontWeight = FontWeight.W600)
    }
}

/**
 * 设计稿 `.danger-btn` 是 `#242424`，与主按钮 `#171717` 只差 13/255，同一行里两个按钮会看起来一模一样；
 * 因此破坏性动作改用项目既有的 `colorScheme.error`（视频页的「永久删除」、去重页的删除确认都是这个色）。
 */
@Composable
private fun SheetDangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 49.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Text(text = text, fontSize = 13.sp, fontWeight = FontWeight.W600)
    }
}

@Composable
private fun TrashItemUi.meta(): String = if (ageDays <= 0L) {
    stringResource(R.string.organize_trash_meta_today, remainingDays)
} else {
    stringResource(R.string.organize_trash_meta, ageDays, remainingDays)
}

/**
 * 行标题。
 *
 * 原始文件名只用于显示（§8.2）：磁盘上的副本用应用生成的 UUID 命名，用户在这里看到的必须是
 * 原来的名字。**旧 R3 条目迁上来时没有文件名**（当时的记录里没存），退回条目 id。
 */
private fun TrashEntry.displayName(): String = originalDisplayName.ifBlank { mediaItemId.value }

/** 设计稿用 toast 反馈；壳层内没有页面级 Snackbar 宿主，改用项目既有的 banner。 */
@Composable
private fun trashStatusMessage(code: String): String = when {
    code == OrganizeViewModel.TRASH_RESTORED -> stringResource(R.string.organize_trash_restored)
    code == OrganizeViewModel.TRASH_PURGED -> stringResource(R.string.organize_trash_purged)
    code == OrganizeViewModel.TRASH_CLEARED -> stringResource(R.string.organize_trash_cleared)
    // 清空是批量操作，进任务中心：这里只能说「已排队」，完成与否由回收站列表自己变空来回答。
    code == OrganizeViewModel.TRASH_CLEAR_ENQUEUED -> stringResource(R.string.organize_trash_clear_enqueued)
    code == OrganizeViewModel.TRASH_AUTHORIZATION_REQUIRED -> stringResource(R.string.organize_trash_authorization_required)
    code.startsWith(OrganizeViewModel.TRASH_FAILED_PREFIX) -> stringResource(
        R.string.organize_trash_failed,
        code.removePrefix(OrganizeViewModel.TRASH_FAILED_PREFIX),
    )
    else -> stringResource(R.string.organize_trash_failed, code)
}

private fun trashStatusKind(code: String): BannerKind = when (code) {
    OrganizeViewModel.TRASH_RESTORED,
    OrganizeViewModel.TRASH_PURGED,
    OrganizeViewModel.TRASH_CLEARED,
    -> BannerKind.SUCCESS
    // 「已排队」不是结果，按提示色而不是成功色显示。
    else -> BannerKind.WARNING
}

/**
 * 与 `feature/library/LibraryScreen.kt` 的私有实现保持一致。
 *
 * 本仓已有三份同名拷贝（HomeScreen / LibraryScreen / MediaListItem），抽公共工具属于与本次
 * 回收站迁移无关的改动，暂不顺手重构。
 */
private fun formatFileSize(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    val pattern = if (unitIndex == 0 || value >= 10.0 || value % 1.0 == 0.0) "%.0f %s" else "%.1f %s"
    return String.format(Locale.US, pattern, value, units[unitIndex])
}
