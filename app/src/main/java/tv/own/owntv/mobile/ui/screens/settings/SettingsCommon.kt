package tv.own.owntv.mobile.ui.screens.settings

import tv.own.owntv.mobile.ui.components.MobileIcons
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * The settings groups, in the order the root page shows them, each a page of rows. A group only
 * earns its own card when it holds more than a handful of rows: Picture lives inside Player, Layout
 * inside Appearance, and Content & metadata inside Sources.
 */
enum class SettingsGroup(
    slug: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val cardRes: Int,
    val icon: ImageVector,
) {
    QUICK("quick", R.string.settings_group_quick, R.string.settings_group_summary_quick, MobileIcons.AutoAwesome),
    SOURCES("sources", R.string.settings_group_sources, R.string.settings_card_sources, MobileIcons.FormatListBulleted),
    PLAYER("player", R.string.settings_vp_cat_player, R.string.settings_card_player, MobileIcons.PlayCircle),
    SOUND("sound", R.string.settings_group_sound_subtitles, R.string.settings_card_sound, MobileIcons.Headphones),
    LIVE("live", R.string.settings_live_tv, R.string.settings_card_live, MobileIcons.LiveTv),
    WATCHING("watching", R.string.settings_group_watching_recording, R.string.settings_card_watching_mobile, MobileIcons.Record),
    APPEARANCE("appearance", R.string.settings_appearance_group, R.string.settings_card_appearance, MobileIcons.Palette),
    APP("app", R.string.settings_app_group, R.string.settings_card_app, MobileIcons.Info),
    ;

    val route: String = "settings/$slug"
}

/** Collect a settings flow for the row that displays it. */
@Composable
fun <T> Flow<T>.pref(initial: T): T = collectAsStateWithLifecycle(initial).value

/** The scrolling body every full page shares: the Stage page's 6 dp sides, room under the bar. */
@Composable
fun SettingsPage(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 100.dp),
        content = content,
    )
}

/**
 * A heading inside a group page — "Mobile", "Subtitles", "Guide".
 *
 * A small label standing above its rows, not a title with a rule across the page: the rows below it
 * are already separated from each other by their own hairlines, and a second full-width line only
 * chops the page into slabs. It is what makes a long settings page scannable at a glance.
 */
fun LazyListScope.settingsSection(@StringRes titleRes: Int) {
    item(key = "section-$titleRes") { SettingsSectionLabel(stringResource(titleRes)) }
}

/**
 * A heading and the run of rows under it, as one rounded block.
 *
 * The block is the group, so everything inside it is flat. Rows go in directly rather than through
 * `item {}`: a settings group is a handful of lines, and holding them in one lazy item is what lets
 * them share a single pane. A page whose rows are a long dynamic list keeps the heading-only form
 * above and stays lazy.
 */
fun LazyListScope.settingsSection(
    @StringRes titleRes: Int,
    rows: @Composable ColumnScope.() -> Unit,
) {
    settingsSection(titleRes)
    settingsGroup(key = "group-$titleRes", rows = rows)
}

/** A run of rows with no heading of its own: Stage rows sit flat on the page, no pane round them. */
fun LazyListScope.settingsGroup(key: String, rows: @Composable ColumnScope.() -> Unit) {
    item(key = key) { Column(content = rows) }
}

/** The heading itself (`.shd`). */
@Composable
fun SettingsSectionLabel(title: String, modifier: Modifier = Modifier) {
    tv.own.owntv.mobile.ui.stage.StageSectionHead(title, modifier)
}

/** Explanatory text under a heading, for the settings that need a sentence rather than a line. */
fun LazyListScope.settingsNote(@StringRes textRes: Int) {
    item(key = "note-$textRes") { PageNote(stringResource(textRes)) }
}

/**
 * A value a finger drags (`.srow` with the size bar under it): the title, the live value at the end in the
 * accent, the line, then the 4 dp Stage bar. The value is shown while the finger moves.
 */
@Composable
fun SettingsSlider(
    title: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    valueLabel: String = stringResource(R.string.common_percent, value),
    steps: Int = 0,
) {
    val span = (range.last - range.first).coerceAtLeast(1)
    // Lands on the slider's stops (steps + 1 intervals) instead of anywhere between two of them.
    val snap = { f: Float ->
        val raw = range.first + f * span
        val stops = steps + 1
        val stepped = if (steps > 0) range.first + kotlin.math.round((raw - range.first) / span * stops) * span / stops else raw
        onValueChange(kotlin.math.round(stepped).toInt().coerceIn(range))
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, style = stageText(14.5f, 700), color = LocalStagePalette.current.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            tv.own.owntv.mobile.ui.stage.StageRowValue(valueLabel)
        }
        subtitle?.let { Text(it, style = stageText(11.5f, 500, lineHeight = 1.3f), color = LocalStagePalette.current.muted) }
        tv.own.owntv.mobile.ui.stage.StageSizeBar((value - range.first).toFloat() / span, snap, Modifier.padding(top = 4.dp))
    }
}

/** One option in a [SettingsChoiceSheet]. */
data class SettingsChoice<T>(val value: T, val label: String, val description: String? = null)

/**
 * A "one of these" sheet: the title, an optional [description], CHOICES with a radio each.
 * Choosing dismisses it. [footer] is for the one setting whose answer is not always in the list.
 */
@Composable
fun <T> SettingsChoiceSheet(
    title: String,
    choices: List<SettingsChoice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    description: String? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    StageChoiceSheet(null, title, description, choices, selected, onSelect, onDismiss, footer = footer)
}

/** A slider's stops, so it lands on core's step size instead of anywhere between two of them. */
internal fun stepsFor(min: Int, max: Int, step: Int): Int = ((max - min) / step) - 1

