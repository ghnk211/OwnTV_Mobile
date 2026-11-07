package tv.own.owntv.mobile.ui.screens.settings.customize

import tv.own.owntv.mobile.ui.components.MobileIcons
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import tv.own.owntv.core.customize.SpanSelector
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.stage.StageTool
import tv.own.owntv.mobile.ui.screens.settings.settingsNote

/**
 * The inside of one folder: hide, rename, reorder and regroup the channels or films it holds.
 *
 * The system back gesture returns to the folder list, so the header's arrow is a second way home
 * rather than the only one.
 */
@Composable
fun CustomizeItemsPane(
    vm: CustomizeItemsViewModel,
    categoryName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The page's crumb stays while a folder is open: "‹ Settings · Sources & guide".
    tv.own.owntv.mobile.ui.screens.settings.SettingsCrumb(null, tv.own.owntv.mobile.ui.screens.settings.SettingsGroup.SOURCES)
    val catInfo by vm.catInfo.collectAsStateWithLifecycle()
    val items = vm.items.collectAsLazyPagingItems()
    val visibilityFilter by vm.visibilityFilter.collectAsStateWithLifecycle()
    val anchorKey by vm.rangeAnchorKey.collectAsStateWithLifecycle()
    val rangeMode by vm.rangeMode.collectAsStateWithLifecycle()
    val endKey by vm.rangeEndKey.collectAsStateWithLifecycle()
    val selectedKeys by vm.rangeSelectedKeys.collectAsStateWithLifecycle()
    val moveTargets by vm.moveTargets.collectAsStateWithLifecycle()

    val mediaType = catInfo?.mediaType ?: MediaType.LIVE
    var menuFor by remember { mutableStateOf<CustomizeItemRow?>(null) }
    var renaming by remember { mutableStateOf<CustomizeItemRow?>(null) }
    var movingTo by remember { mutableStateOf<CustomizeItemRow?>(null) }
    var creatingCategory by remember { mutableStateOf<CustomizeItemRow?>(null) }
    var rangeEnd by remember { mutableStateOf<CustomizeItemRow?>(null) }
    var showFilter by remember { mutableStateOf(false) }

    tv.own.owntv.mobile.ui.screens.settings.SettingsStagePage(modifier) {
        item(key = "header") {
            // The folder this level shows, as a tool that goes back to the folders (the page keeps its own title).
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp)) {
                StageTool(onClick = onBack, icon = MobileIcons.ArrowBack, label = categoryName, contentDescription = stringResource(R.string.settings_customize_back))
            }
        }
        settingsNote(mediaType.itemsDescriptionRes())
        item(key = "actions") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StageTool(
                    onClick = { showFilter = true },
                    icon = MobileIcons.VisibilityOff,
                    label = plainLabel(stringResource(R.string.settings_customize_filter_button, stringResource(visibilityFilter.labelRes()))),
                )
                // Renaming a whole folder of films or shows at once is the only practical way to
                // clean up a provider's naming; channels are renamed one at a time instead.
                if (mediaType != MediaType.LIVE) {
                    StageTool(onClick = { vm.bulkRenameAll(autocleanup = false) }, label = stringResource(R.string.settings_customize_rename_items))
                    StageTool(onClick = { vm.bulkRenameAll(autocleanup = true) }, label = stringResource(R.string.settings_bulk_rename_auto_cleanup))
                }
            }
        }
        if (anchorKey != null) {
            item(key = "span") {
                SpanBar(
                    mode = rangeMode,
                    hasEnd = endKey != null,
                    selectedCount = selectedKeys.size,
                    onMove = vm::moveRangeBlock,
                    onCancel = { vm.cancelRange() },
                )
            }
        }
        items(
            count = items.itemCount,
            key = items.itemKey { it.key },
        ) { index ->
            val row = items[index] ?: return@items
            // A Stage row: a tick while it is in a span, the span itself drawn as the current rows.
            val inSpan = row.key in selectedKeys
            tv.own.owntv.mobile.ui.stage.StageSettingRow(
                title = row.displayName,
                subtitle = row.provenance(),
                icon = if (inSpan) tv.own.owntv.mobile.ui.components.MobileIcons.Check else null,
                current = inSpan,
                onClick = {
                    when {
                        anchorKey == null -> menuFor = row
                        row.key == anchorKey -> vm.cancelRange()
                        rangeMode == SpanSelector.Mode.HIDE -> rangeEnd = row
                        rangeMode == SpanSelector.Mode.RENAME -> {
                            if (vm.finishRenameRange(row) == null) renaming = row
                        }
                        else -> vm.setRangeEnd(row)
                    }
                },
                onLongClick = { menuFor = row },
            )
        }
    }

    menuFor?.let { row ->
        RowActionsSheet(
            title = row.displayName,
            onDismiss = { menuFor = null },
            hiddenNow = row.hidden,
            onToggleHidden = { menuFor = null; vm.setItemHidden(row, !row.hidden) },
            onMove = { kind -> menuFor = null; vm.moveSingle(row, kind) },
            onSpanHide = { menuFor = null; vm.beginRange(row) },
            onSpanMove = { menuFor = null; vm.beginMoveRange(row) },
            onSpanRename = { menuFor = null; vm.beginRenameRange(row) },
            // A channel's name is its own; films and shows are renamed in batches instead.
            onRename = if (mediaType == MediaType.LIVE) {
                { menuFor = null; renaming = row }
            } else {
                null
            },
            onMoveTo = { menuFor = null; movingTo = row },
            onRemoveFromCategory = if (catInfo?.isCustom == true) ({ menuFor = null; vm.removeFromCategory(row) }) else null,
        )
    }

    renaming?.let { row ->
        TextPromptDialog(
            title = stringResource(R.string.settings_customize_rename),
            initial = row.displayName,
            hint = stringResource(R.string.settings_customize_rename_item_hint, row.originalName),
            onConfirm = { vm.renameItem(row, it.takeIf { t -> t.isNotBlank() }); renaming = null },
            onDismiss = { renaming = null },
        )
    }

    movingTo?.let { row ->
        MoveToCategoryDialog(
            moveTargets = moveTargets,
            originName = categoryName,
            onNewCategory = { creatingCategory = row; movingTo = null },
            onMove = { targetId, keepInOrigin ->
                vm.moveTo(row, targetId, keepInOrigin)
                movingTo = null
            },
            onDismiss = { movingTo = null },
        )
    }

    creatingCategory?.let { row ->
        TextPromptDialog(
            title = stringResource(R.string.settings_customize_new_category_title),
            initial = "",
            description = stringResource(R.string.settings_customize_new_category_description),
            confirmLabel = stringResource(R.string.common_create),
            allowBlank = false,
            onConfirm = { vm.createCustomCategory(it); creatingCategory = null; movingTo = row },
            onDismiss = { creatingCategory = null },
        )
    }

    rangeEnd?.let { row ->
        SpanHideDialog(
            title = stringResource(R.string.settings_customize_hide_show_items),
            count = vm.keysInRange(row)?.size ?: 0,
            isCategories = false,
            onHide = { vm.applyRange(row, hidden = true); rangeEnd = null },
            onShow = { vm.applyRange(row, hidden = false); rangeEnd = null },
            onDismiss = { vm.cancelRange(); rangeEnd = null },
        )
    }

    if (showFilter) {
        FilterSheet(
            current = visibilityFilter,
            onSelect = { vm.setVisibilityFilter(it) },
            onDismiss = { showFilter = false },
        )
    }

    BulkRenameFlow(vm.bulk)
}

/** The one-line note under an item's name: hidden, and what the playlist originally called it. */
@Composable
private fun CustomizeItemRow.provenance(): String? {
    val parts = listOfNotNull(
        if (hidden) stringResource(R.string.settings_customize_hidden) else null,
        if (renamed) stringResource(R.string.settings_customize_item_was, originalName) else null,
    )
    return parts.takeIf { it.isNotEmpty() }
        ?.joinToString(stringResource(R.string.settings_customize_metadata_separator))
}

private fun MediaType.itemsDescriptionRes() = when (this) {
    MediaType.MOVIE -> R.string.settings_customize_movies_description
    MediaType.SERIES -> R.string.settings_customize_series_description
    else -> R.string.settings_customize_channels_description
}
