package tv.own.owntv.mobile.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.sheetListHeight
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageButton
import tv.own.owntv.mobile.ui.stage.StageButtonStyle
import tv.own.owntv.mobile.ui.stage.StageChevron
import tv.own.owntv.mobile.ui.stage.StageGroupLabel
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StageRadio
import tv.own.owntv.mobile.ui.stage.StageRecommended
import tv.own.owntv.mobile.ui.stage.StageRowValue
import tv.own.owntv.mobile.ui.stage.StageSectionHead
import tv.own.owntv.mobile.ui.stage.StageSegmented
import tv.own.owntv.mobile.ui.stage.StageSettingRow
import tv.own.owntv.mobile.ui.stage.StageSheetHeader
import tv.own.owntv.mobile.ui.stage.StageStepper
import tv.own.owntv.mobile.ui.stage.StageSwitch
import tv.own.owntv.mobile.ui.stage.StageTag
import tv.own.owntv.mobile.ui.stage.StageTagTone
import tv.own.owntv.mobile.ui.stage.stageClickable
import tv.own.owntv.mobile.ui.stage.stageText

/*
 * Stage Settings: a group page is sections of rows (`.srow`). A row says on its right what it is set to
 * and what a tap does — a switch flips, a stepper steps, a segmented control picks, ▾ opens the choices in a
 * sheet with the TV panel's explanation, › opens a page. Every row is one [SettingItem], so the group
 * page, Quick, the root's counts and the settings search all read the same list.
 */

/** What a row shows on its right. */
sealed interface SettingValue {
    /** `.sw`: a tap anywhere on the row flips it. */
    class Switch(val on: Boolean) : SettingValue
    /** A value picked in a sheet: accent text and ▾. */
    class Choice(val text: String) : SettingValue
    /** Opens a page: accent text (may be empty) and ›. */
    class Opens(val text: String?) : SettingValue
    /** `.step`: − value +, changed in place. */
    class Stepper(
        val text: String,
        val onMinus: () -> Unit,
        val onPlus: () -> Unit,
        val canMinus: Boolean = true,
        val canPlus: Boolean = true,
    ) : SettingValue
    /** `.seg`: the options side by side, picked in place. */
    class Segmented(val options: List<String>, val selected: Int, val onSelect: (Int) -> Unit) : SettingValue
    /** "19 saved · Reset", or "None saved" when there is nothing to forget. */
    class Saved(val count: Int) : SettingValue
    /** A plain action word in the text colour ("Forget", "Check now"). */
    class Action(val text: String) : SettingValue
    /** An accent tag (a profile's IN USE). */
    class Tag(val text: String) : SettingValue
    /** Anything else drawn there (the accent swatches). */
    class Custom(val content: @Composable () -> Unit) : SettingValue
}

/**
 * One settings row. [key] is the one Quick stores when the row is pinned — the TV app's key where the TV has
 * the same row, so a pin restored from a television lands on it. A tap opens [sheet] when there is one,
 * otherwise runs [onClick].
 */
class SettingItem(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val line: String? = null,
    val value: SettingValue? = null,
    val onClick: (() -> Unit)? = null,
    val sheet: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
    val pinnable: Boolean = true,
    val leading: (@Composable () -> Unit)? = null,
    /** A hold that does something else than Pin to Quick (a profile opens its editor). */
    val onLongClick: (() -> Unit)? = null,
)

/** The same row under other words (Quick's own, or "Pinned from …"). */
fun SettingItem.reworded(title: String, line: String?) =
    SettingItem(key, icon, title, line, value, onClick, sheet, pinnable, leading, onLongClick)

/**
 * A page's sub-heading (`.shd`, with its row count) and the rows under it. No [title]: the rows stand alone.
 * [note] is a sentence under the heading that is about the whole section (this phone's inexact alarms).
 */
class SettingSection(val title: String?, val items: List<SettingItem>, val note: String? = null)

/** One row, drawn as `.srow`. */
@Composable
fun SettingItemRow(item: SettingItem, onOpenSheet: () -> Unit, onLongClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    StageSettingRow(
        title = item.title,
        modifier = modifier,
        icon = item.icon,
        subtitle = item.line,
        leading = item.leading,
        onClick = { if (item.sheet != null) onOpenSheet() else item.onClick?.invoke() },
        onLongClick = onLongClick,
    ) { SettingValueView(item.value) }
}

@Composable
private fun SettingValueView(value: SettingValue?) {
    when (value) {
        null -> Unit
        is SettingValue.Switch -> StageSwitch(value.on)
        is SettingValue.Choice -> StageRowValue(value.text, StageChevron.DOWN)
        is SettingValue.Opens -> StageRowValue(value.text.orEmpty(), StageChevron.FORWARD)
        is SettingValue.Stepper -> StageStepper(value.text, value.onMinus, value.onPlus, minusEnabled = value.canMinus, plusEnabled = value.canPlus)
        is SettingValue.Segmented -> StageSegmented(value.options, value.selected, value.onSelect)
        is SettingValue.Saved -> if (value.count > 0) {
            StageRowValue(
                stringResource(R.string.common_reset),
                accentPart = pluralStringResource(R.plurals.settings_reset_player_choices_count, value.count, value.count),
            )
        } else {
            StageRowValue(stringResource(R.string.settings_reset_player_choices_none), plain = true)
        }
        is SettingValue.Action -> StageRowValue(value.text, plain = true)
        is SettingValue.Tag -> StageTag(value.text, tone = StageTagTone.ACCENT)
        is SettingValue.Custom -> value.content()
    }
}

/**
 * The rows of a group page: each section's heading with its count, then its rows. Long-press offers Pin to
 * Quick (with Move up / down once pinned); a row's sheet opens over the page.
 */
fun LazyListScope.settingSections(
    sections: List<SettingSection>,
    pins: QuickPins,
    onOpen: (SettingItem) -> Unit,
) {
    sections.forEachIndexed { s, section ->
        section.title?.let { title ->
            item(key = "shd-$s") { StageSectionHead(title, count = section.items.size) }
        }
        section.note?.let { note -> item(key = "note-$s") { PageNote(note, Modifier.padding(top = 0.dp)) } }
        section.items.forEach { row ->
            item(key = "row-${row.key}-$s") {
                SettingItemRow(
                    item = row,
                    onOpenSheet = { onOpen(row) },
                    onLongClick = row.onLongClick ?: if (row.pinnable) ({ pins.menuFor = row }) else null,
                )
            }
        }
    }
}

/** The scrolling page every settings page shares: 6 dp sides (`left:6px;right:6px`), room under the bar. */
@Composable
fun SettingsStagePage(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 100.dp),
        content = content,
    )
}

/** Which row's sheet is open on a page, and the sheet itself. */
@Composable
fun rememberOpenSheet(): androidx.compose.runtime.MutableState<SettingItem?> = remember { mutableStateOf(null) }

@Composable
fun OpenSheetHost(open: androidx.compose.runtime.MutableState<SettingItem?>, sections: List<SettingSection>) {
    // Re-read by key so the sheet sees the row's current value, not the one captured when it opened.
    val key = open.value?.key ?: return
    val row = sections.flatMap { it.items }.firstOrNull { it.key == key } ?: open.value ?: return
    row.sheet?.invoke { open.value = null }
}

/**
 * The TV's context panel as a sheet: the group as the eyebrow, the row's name, its explanation, then
 * CHOICES — a radio each, RECOMMENDED beside the default. A pick applies and closes.
 */
@Composable
fun <T> StageChoiceSheet(
    eyebrow: String?,
    title: String,
    text: String?,
    choices: List<SettingsChoice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    recommended: T? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    MobileBottomSheet(onDismissRequest = onDismiss) {
        StageSheetHeader(title, eyebrow = eyebrow)
        SheetText(text)
        StageGroupLabel(stringResource(R.string.settings_panel_choices))
        // A long list (UTC-12 … UTC+14, every language) opens with the current choice in view, two rows below
        // the top so what is around it shows too (#3).
        val at = choices.indexOfFirst { it.value == selected }
        val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (at - 2).coerceAtLeast(0))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = sheetListHeight()), state = list) {
            items(choices.size) { i ->
                val choice = choices[i]
                ChoiceItem(
                    label = choice.label,
                    description = choice.description,
                    on = choice.value == selected,
                    recommended = recommended != null && choice.value == recommended,
                    onClick = { onSelect(choice.value); onDismiss() },
                )
            }
            footer?.let { item { it() } }
        }
    }
}

/** A sheet's explanation (12.5 / 1.5, body colour, 8 dp sides). */
@Composable
fun SheetText(text: String?) {
    if (text.isNullOrBlank()) return
    Text(
        text,
        style = stageText(12.5f, 500, lineHeight = 1.5f),
        color = LocalStagePalette.current.body,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
    )
}

/** `.mi` with a radio: the chosen one in the accent; RECOMMENDED right after the label. */
@Composable
internal fun ChoiceItem(label: String, description: String?, on: Boolean, recommended: Boolean, onClick: () -> Unit) {
    val palette = LocalStagePalette.current
    val accent = tv.own.owntv.mobile.ui.stage.stageAccent.accent
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .stageClickable(interaction, onClick, null, androidx.compose.ui.semantics.Role.RadioButton)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StageRadio(on)
        Column(Modifier.weight(1f, fill = false)) {
            Text(label, style = stageText(14.5f, 600), color = if (on) accent else palette.item, maxLines = 2, overflow = TextOverflow.Ellipsis)
            description?.let { Text(it, style = stageText(11.5f, 500, lineHeight = 1.3f), color = palette.muted) }
        }
        if (recommended) StageRecommended(Modifier.padding(start = 6.dp))
    }
}

/**
 * A question before something is forgotten or switched on: the title, what will happen, then
 * Cancel and the action side by side.
 */
@Composable
fun StageConfirmSheet(
    title: String,
    text: String?,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    eyebrow: String? = null,
    danger: Boolean = false,
    confirmIcon: ImageVector? = null,
    cancel: String = stringResource(R.string.common_cancel),
) {
    MobileBottomSheet(onDismissRequest = onDismiss) {
        StageSheetHeader(title, eyebrow = eyebrow)
        SheetText(text)
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StageButton(onClick = onDismiss, text = cancel, modifier = Modifier.weight(1f))
            StageButton(
                onClick = { onConfirm(); onDismiss() },
                text = confirm,
                icon = confirmIcon,
                style = if (danger) StageButtonStyle.DANGER else StageButtonStyle.PRIMARY,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The pinned list and the row whose Pin menu is open. Keys the TV pins by default under its own Quick names
 * ([QUICK_ALIASES]) count as the phone row they stand for.
 */
class QuickPins(val keys: List<String>, private val save: (List<String>) -> Unit) {
    var menuFor by mutableStateOf<SettingItem?>(null)

    fun indexOf(key: String): Int = keys.indexOfFirst { it == key || QUICK_ALIASES[it] == key }
    fun pin(key: String) = save(keys + key)
    fun unpin(key: String) = save(keys.filterNot { it == key || QUICK_ALIASES[it] == key })
    /** The rows Quick draws, in order — set by the Quick page; moves step over keys it does not draw. */
    var shown: List<String> = emptyList()

    /** The stored positions of the drawn rows: a TV-only pin in between is never a step. */
    private fun shownIndices() = keys.indices.filter { (QUICK_ALIASES[keys[it]] ?: keys[it]) in shown }
    fun position(key: String): Int = shownIndices().indexOfFirst { (QUICK_ALIASES[keys[it]] ?: keys[it]) == key }
    fun canStep(key: String, delta: Int): Boolean = position(key).let { it >= 0 && it + delta in shownIndices().indices }

    /** Swap [key] with the next drawn row above (-1) or below (+1). */
    fun step(key: String, delta: Int) {
        val at = shownIndices()
        val p = position(key)
        val to = at.getOrNull(p + delta) ?: return
        save(keys.toMutableList().apply { add(to, removeAt(at[p])) })
    }
}

/**
 * The TV app's Quick rows that are the same setting as a phone row. The TV pins six of them out of the box,
 * so a fresh install — and every TV backup — lists them here too.
 */
val QUICK_ALIASES: Map<String, String> = mapOf(
    "quick_hdr" to "vp_hdr",
    "quick_check_update" to "update_startup",
    "detailed_diagnostics" to "vp_logging",
)

@Composable
fun rememberQuickPins(vm: SettingsViewModel): QuickPins {
    val keys = vm.settings.quickPinnedKeys.pref(emptyList())
    return remember(keys) { QuickPins(keys) { next -> vm.edit { setQuickPinnedKeys(next) } } }
}

/** Long-press on a row: Pin to Quick, or Unpin and move it within Quick. */
@Composable
fun QuickPinSheet(pins: QuickPins) {
    val row = pins.menuFor ?: return
    val close = { pins.menuFor = null }
    val at = pins.indexOf(row.key)
    MobileBottomSheet(onDismissRequest = close) {
        StageSheetHeader(row.title, eyebrow = stringResource(R.string.settings_group_quick))
        if (at < 0) {
            StageMenuItem(stringResource(R.string.settings_row_menu_pin), onClick = { pins.pin(row.key); close() }, icon = MobileIcons.Add)
        } else {
            StageMenuItem(stringResource(R.string.settings_row_menu_unpin), onClick = { pins.unpin(row.key); close() }, icon = MobileIcons.Close)
            if (pins.canStep(row.key, -1)) StageMenuItem(stringResource(R.string.settings_row_menu_move_up), onClick = { pins.step(row.key, -1); close() }, icon = MobileIcons.KeyboardArrowUp)
            if (pins.canStep(row.key, 1)) StageMenuItem(stringResource(R.string.settings_row_menu_move_down), onClick = { pins.step(row.key, 1); close() }, icon = MobileIcons.KeyboardArrowDown)
        }
        Box(Modifier.padding(bottom = 4.dp))
    }
}

/** A stepper through a list of values: the stored one, or the nearest the list has. */
fun <T : Comparable<T>> List<T>.stepFrom(current: T, delta: Int): T {
    val at = indexOf(current).takeIf { it >= 0 } ?: indexOfFirst { it >= current }.takeIf { it >= 0 } ?: lastIndex
    return this[(at + delta).coerceIn(0, lastIndex)]
}

/** A note on a page (`11px dim, 10 12`): what a list leaves out, or how a page works. */
@Composable
fun PageNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = stageText(11.5f, 500, lineHeight = 1.4f),
        color = LocalStagePalette.current.dim,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 10.dp),
    )
}
