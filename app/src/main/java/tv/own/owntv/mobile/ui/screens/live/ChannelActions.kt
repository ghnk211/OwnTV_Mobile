package tv.own.owntv.mobile.ui.screens.live

import tv.own.owntv.mobile.ui.screens.home.formatYear
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.live.ProviderTags
import tv.own.owntv.mobile.ui.components.ChannelLogoImage
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.StageRadii
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StageSearchField
import tv.own.owntv.mobile.ui.stage.StageSettingRow
import tv.own.owntv.mobile.ui.stage.StageSheetHeader
import tv.own.owntv.mobile.ui.stage.StageStepper
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageText
import tv.own.owntv.mobile.ui.components.MobileIcons
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.GuideCandidate
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.model.ContentMenu
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.ContentMenuSheet
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MoveToCategorySheet
import tv.own.owntv.mobile.ui.components.NewCategoryDialog
import tv.own.owntv.mobile.ui.components.SheetAction
import tv.own.owntv.mobile.ui.components.sheetListHeight

/** Which follow-up the sheet handed off to. Only ever one at a time. */
private enum class ChannelDialog { RENAME, MOVE_TO_CATEGORY, NEW_CATEGORY, SCHEDULE_RECORDING }

/**
 * The long-press menu for a channel, and everything it opens.
 *
 * The ten actions, their keys and their gating are the TV app's, because the arrangement the user
 * saved in Settings is keyed by exactly those strings. What differs is where they land: a sheet
 * instead of a focused column, and dialogs a thumb can reach.
 *
 * The sheet closes the moment an action is tapped, so [onDismiss] is deliberately *not* called from
 * it — the caller is released only once the sheet is gone **and** nothing it opened is still up.
 */
@Composable
fun ChannelMenu(
    channel: ChannelEntity,
    selected: LiveKey,
    isFavorite: Boolean,
    originName: String,
    /** The playlist's name, only when more than one is active (the header's "category · playlist"). */
    providerName: String?,
    vm: LiveViewModel,
    onOpenCatchup: () -> Unit,
    onDismiss: () -> Unit,
) {
    var sheetOpen by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf<ChannelDialog?>(null) }

    LaunchedEffect(sheetOpen, dialog) { if (!sheetOpen && dialog == null) onDismiss() }

    // Multiview: offered only once it is switched on, and the confirmation says how many are kept.
    val settings = org.koin.compose.koinInject<tv.own.owntv.core.settings.SettingsRepository>()
    val tuner = org.koin.compose.koinInject<LiveTuner>()
    // Null until DataStore has answered — not `false`.
    //
    // A sheet is handed to the host as a lambda and drawn there, and the host keeps drawing the
    // lambda it was first given. Built while this was still the placeholder `false`, the menu was
    // built *without* the Multiview row and went on showing that version: the row never appeared,
    // however many times it was opened. Waiting for the real answer costs a frame and is correct.
    val multiviewSetting by settings.multiviewEnabled.collectAsStateWithLifecycle(null as Boolean?)
    val multiviewEnabled = multiviewSetting == true
    val multiviewTiles by settings.multiviewTiles.collectAsStateWithLifecycle(
        tv.own.owntv.core.live.DEFAULT_MULTIVIEW_TILES,
    )
    val context = androidx.compose.ui.platform.LocalContext.current
    // Configuration-aware, unlike context.getString: the toast below is formatted at click time with
    // a count that is not known at composition, so it cannot be a stringResource.
    val res = androidx.compose.ui.platform.LocalResources.current
    val recordingChannelIds by tuner.recordingChannelIds.collectAsStateWithLifecycle()
    val isRecording = channel.id in recordingChannelIds

    if (sheetOpen && multiviewSetting != null) {
        val canMove = vm.contextKeyOf(selected) != null
        // Group ids = the TV's four groups, in its order.
        val watch = 0; val chan = 1; val organise = 3
        val catchupValue = channel.catchupDays.takeIf { it > 0 }
            ?.let { pluralStringResource(R.plurals.settings_epg_guide_days_value, it, it) }
        val actions = buildList {
            if (channel.catchup) {
                add(SheetAction("catchup", stringResource(R.string.content_catchup), MobileIcons.Catchup, group = watch, value = catchupValue, onClick = onOpenCatchup))
            }
            // Record this channel from now. The guide's Record needs a programme, so a channel the
            // provider publishes no guide for can only be recorded from here. While it records, the
            // same row stops it — tapping Record again would start a second copy on another connection.
            add(
                SheetAction(
                    key = "record",
                    label = stringResource(if (isRecording) R.string.recording_stop else R.string.recording_record),
                    icon = if (isRecording) MobileIcons.Close else MobileIcons.Record,
                    group = watch,
                    onClick = { if (isRecording) tuner.stopRecordingOn(channel) else tuner.recordNow(channel) },
                ),
            )
            add(SheetAction("schedule_record", stringResource(R.string.recording_schedule), MobileIcons.Schedule, group = watch) { dialog = ChannelDialog.SCHEDULE_RECORDING })
            if (multiviewEnabled) {
                add(
                    SheetAction("add_to_multiview", stringResource(R.string.multiview_add_to), MobileIcons.Multiview, group = watch) {
                        tuner.addToMultiview(channel, multiviewTiles)
                        android.widget.Toast.makeText(
                            context,
                            res.getString(R.string.multiview_added, tuner.multiviewSelection.value.size, multiviewTiles),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
            add(
                SheetAction(
                    key = "favourite",
                    label = stringResource(if (isFavorite) R.string.content_remove_favourite else R.string.content_add_favourite),
                    icon = MobileIcons.FavoriteBorder,
                    group = chan,
                    onClick = { vm.toggleFavorite(channel) },
                ),
            )
            add(SheetAction("rename", stringResource(R.string.content_rename), MobileIcons.Edit, group = chan) { dialog = ChannelDialog.RENAME })
            if (selected == LiveKey.History) {
                add(SheetAction("remove_history", stringResource(R.string.content_remove_history), MobileIcons.History, group = chan) { vm.removeFromHistory(channel.id) })
            }
            if (canMove) {
                add(SheetAction("move_to_category", stringResource(R.string.content_move_to_category), MobileIcons.Folder, group = organise) { dialog = ChannelDialog.MOVE_TO_CATEGORY })
            }
            // Hide it out of every list; Settings → Customize → Hidden channels brings it back.
            add(
                SheetAction(
                    key = "hide",
                    label = stringResource(R.string.content_hide_channel),
                    icon = MobileIcons.VisibilityOff,
                    destructive = true,
                    group = organise,
                    onClick = { vm.hideChannel(channel) },
                ),
            )
        }
        val sep = stringResource(R.string.content_epg_bits_separator)
        val name = remember(channel.name) { ProviderTags.parse(channel.name).name }
        ContentMenuSheet(
            menu = ContentMenu.LIVE,
            title = listOfNotNull(channel.number?.takeIf { it > 0 }?.let(::formatYear), name).joinToString(sep),
            subtitle = listOfNotNull(originName.takeIf { it.isNotBlank() }, providerName).joinToString(sep).takeIf { it.isNotEmpty() },
            actions = actions,
            onDismiss = { sheetOpen = false },
            groupLabels = mapOf(
                watch to stringResource(R.string.content_menu_group_watch),
                chan to stringResource(R.string.content_menu_group_channel),
                organise to stringResource(R.string.content_menu_group_organise),
            ),
            leading = {
                Box(Modifier.size(44.dp, 32.dp).clip(RoundedCornerShape(StageRadii.Plate)), contentAlignment = Alignment.Center) {
                    ChannelLogoImage(
                        url = channel.displayLogoUrl,
                        modifier = Modifier.fillMaxSize(),
                        plateOn = LocalStagePalette.current.page,
                        fallback = { StageIcon(MobileIcons.LiveTv, 16.dp, LocalStagePalette.current.dim) },
                    )
                }
            },
        )
    }

    when (dialog) {
        ChannelDialog.RENAME -> RenameChannelDialog(
            channel = channel,
            onSet = { vm.renameChannel(channel, it) },
            onDismiss = { dialog = null },
        )
        ChannelDialog.MOVE_TO_CATEGORY -> MoveToCategorySheet(
            originName = originName,
            targets = vm.customCategories.collectAsStateWithLifecycle().value,
            onNewCategory = { dialog = ChannelDialog.NEW_CATEGORY },
            onMove = { targetId, keep ->
                vm.contextKeyOf(selected)?.let { vm.moveToCategory(channel, it, targetId, keep) }
            },
            onDismiss = { dialog = null },
        )
        ChannelDialog.NEW_CATEGORY -> NewCategoryDialog(
            onCreate = { vm.createCustomCategory(it) },
            // Back to the picker, where the category just created is waiting.
            onDismiss = { dialog = ChannelDialog.MOVE_TO_CATEGORY },
        )
        ChannelDialog.SCHEDULE_RECORDING -> ScheduleRecordingSheet(
            channel = channel,
            clashesFor = { start, stop -> tuner.recordingClashes(channel, start, stop) },
            onSchedule = { start, stop ->
                dialog = null
                tuner.scheduleRecording(channel, start, stop) { at ->
                    val whenText = android.text.format.DateUtils.formatDateTime(
                        context, at,
                        android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or android.text.format.DateUtils.FORMAT_SHOW_DATE or
                            android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_ABBREV_ALL,
                    )
                    android.widget.Toast.makeText(context, res.getString(R.string.recording_scheduled_for, whenText), android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

/** Rename for this profile only. An empty name is how the provider's own name comes back. */
@Composable
private fun RenameChannelDialog(
    channel: ChannelEntity,
    onSet: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(channel.name) }
    val title = stringResource(R.string.content_rename_channel)
    val save = { onSet(text.trim().takeIf { it.isNotEmpty() }); onDismiss() }
    // Stage sheet: the name field, Save, Reset (the provider's name back), Cancel.
    MobileBottomSheet(onDismissRequest = onDismiss, title = title) {
        StageSheetHeader(title = title, subtitle = remember(channel.name) { ProviderTags.parse(channel.name).name })
        StageSearchField(
            value = text,
            onValueChange = { text = it },
            placeholder = title,
            icon = null,
            imeAction = ImeAction.Done,
            onSearch = save,
            modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp),
        )
        StageMenuItem(stringResource(R.string.common_save), onClick = save, icon = MobileIcons.Check, filled = true)
        StageMenuItem(stringResource(R.string.common_reset), onClick = { onSet(null); onDismiss() }, icon = MobileIcons.Refresh)
        StageMenuItem(stringResource(R.string.common_cancel), onClick = onDismiss, icon = MobileIcons.Close)
    }
}

/**
 * Point a channel at a guide channel by hand.
 *
 * Providers name the same channel three different ways across a playlist and its XMLTV, and the
 * automatic match gives up on some of them. Searching here is a live query, debounced so a typed
 * word costs one lookup rather than one per letter.
 *
 * The Guide opens this same sheet, which is why it takes plain values rather than a view model.
 */
// `debounce` is still a preview API; one lookup per word rather than one per letter is worth it.
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun EpgMatchSheet(
    channelName: String,
    currentMatch: String?,
    search: suspend (String) -> List<GuideCandidate>,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GuideCandidate>?>(null) }
    val epgKeyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(channelName) {
        snapshotFlow { query }
            .debounce(SEARCH_DEBOUNCE_MS)
            .distinctUntilChanged()
            .collect { results = search(it) }
    }

    val palette = LocalStagePalette.current
    val title = stringResource(R.string.content_match_epg)
    MobileBottomSheet(onDismissRequest = onDismiss, title = title) {
        StageSheetHeader(
            title = title,
            subtitle = if (currentMatch != null) {
                stringResource(R.string.content_epg_match_prompt_current, channelName, currentMatch)
            } else {
                stringResource(R.string.content_epg_match_prompt, channelName)
            },
        )
        StageSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.content_search_guide_channels),
            onSearch = { epgKeyboard?.hide() },
            modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp),
        )
        val list = results
        when {
            list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = stageAccent.accent)
            }
            list.isEmpty() -> Text(
                text = if (query.isBlank()) {
                    stringResource(R.string.content_no_epg_data)
                } else {
                    stringResource(R.string.content_no_guide_channels, query)
                },
                style = stageText(13f, 500),
                color = palette.muted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
            else -> LazyColumn(Modifier.heightIn(max = sheetListHeight())) {
                items(list, key = { it.epgChannelId }) { epg ->
                    StageSettingRow(
                        title = epg.displayName ?: epg.epgChannelId,
                        subtitle = epg.epgChannelId,
                        minHeight = 52.dp,
                        current = epg.epgChannelId.equals(currentMatch, ignoreCase = true),
                        onClick = { onPick(epg.epgChannelId); onDismiss() },
                    )
                }
            }
        }
        if (currentMatch != null) {
            StageMenuItem(
                stringResource(R.string.content_clear_match),
                onClick = { onPick(null); onDismiss() },
                icon = MobileIcons.Close,
            )
        }
    }
}

/**
 * Shift one channel's guide.
 *
 * Providers often hang both the East and the West feed of a network off a single guide, so one of
 * them runs hours out. This moves that channel only; the global offset in Settings stays put.
 */
@Composable
internal fun EpgOffsetDialog(
    channelName: String,
    currentMinutes: Int?,
    globalMinutes: Int,
    onSet: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalStagePalette.current
    var minutes by remember { mutableStateOf(currentMinutes ?: globalMinutes) }
    val title = stringResource(R.string.content_epg_time_offset)
    MobileBottomSheet(onDismissRequest = onDismiss, title = title) {
        StageSheetHeader(
            title = title,
            subtitle = stringResource(R.string.content_epg_offset_channel_description, channelName),
        )
        StageSettingRow(
            title = remember(channelName) { ProviderTags.parse(channelName).name },
            subtitle = stringResource(
                if (currentMinutes == null) R.string.content_epg_offset_following_global else R.string.content_epg_offset_channel_only,
                epgShiftLabel(globalMinutes),
            ),
            icon = MobileIcons.Schedule,
            role = null,
        ) {
            StageStepper(
                value = epgShiftLabel(minutes),
                onMinus = { minutes = (minutes - STEP_MINUTES).coerceAtLeast(MIN_MINUTES) },
                onPlus = { minutes = (minutes + STEP_MINUTES).coerceAtMost(MAX_MINUTES) },
                minusEnabled = minutes > MIN_MINUTES,
                plusEnabled = minutes < MAX_MINUTES,
            )
        }
        Box(Modifier.size(1.dp, 6.dp))
        StageMenuItem(stringResource(R.string.common_done), onClick = { onSet(minutes); onDismiss() }, icon = MobileIcons.Check)
        if (currentMinutes != null) {
            StageMenuItem(stringResource(R.string.content_epg_offset_use_global), onClick = { onSet(null); onDismiss() }, icon = MobileIcons.Refresh)
        }
        StageMenuItem(stringResource(R.string.common_cancel), onClick = onDismiss, icon = MobileIcons.Close)
    }
}

/** "+2h", "−30m", or "Off". The digits go through the locale's own number format. */
@Composable
internal fun epgShiftLabel(minutes: Int): String {
    if (minutes == 0) return stringResource(R.string.common_off)
    val locale = LocalConfiguration.current.locales[0] ?: java.util.Locale.US
    val number = java.text.NumberFormat.getIntegerInstance(locale)
    val sign = if (minutes < 0) MINUS_SIGN else PLUS_SIGN
    val absolute = kotlin.math.abs(minutes)
    val hours = absolute / 60
    val remainder = absolute % 60
    return when {
        hours == 0 -> stringResource(R.string.content_epg_shift_minutes, sign, number.format(remainder))
        remainder == 0 -> stringResource(R.string.content_epg_shift_hours, sign, number.format(hours))
        else -> stringResource(
            R.string.content_epg_shift_hours_minutes,
            sign,
            number.format(hours),
            number.format(remainder),
        )
    }
}

private const val MINUS_SIGN = "−"
private const val PLUS_SIGN = "+"
private const val STEP_MINUTES = 30
private const val MIN_MINUTES = -12 * 60
private const val MAX_MINUTES = 14 * 60
private const val SEARCH_DEBOUNCE_MS = 250L
