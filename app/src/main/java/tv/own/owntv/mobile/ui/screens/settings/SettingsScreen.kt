package tv.own.owntv.mobile.ui.screens.settings

import androidx.compose.runtime.setValue
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.shell.PageCrumb
import tv.own.owntv.mobile.ui.shell.ProvidePageCrumb
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageChevron
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.StageRowValue
import tv.own.owntv.mobile.ui.stage.StageSearchField
import tv.own.owntv.mobile.ui.stage.StageSettingRow
import tv.own.owntv.mobile.ui.stage.drawInnerRing
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageClickable
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * Settings: the line "Hold a row to pin it to Quick" under the title, the search over every setting,
 * then the eight groups as cards in two columns — icon, count, name and what the group holds. Searching
 * lists every setting whose own name matches (names only), its path above it and its value beside.
 */
@Composable
fun SettingsScreen(
    onOpenRoute: (String) -> Unit,
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = koinViewModel(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    val all = allGroupSections(vm)
    val pins = rememberQuickPins(vm)
    val hint = stringResource(R.string.settings_root_hint_mobile)
    ProvidePageCrumb { owner -> PageCrumb(accent = "", rest = null, chevron = false, owner = owner, styled = AnnotatedString(hint), onClick = {}) }

    val results = settingsSearch(query, all)
    val quickCount = quickRows(all, pins).size
    SettingsStagePage(modifier) {
        item(key = "search") {
            StageSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.more_settings_search),
                onClear = { query = "" },
                clearLabel = stringResource(R.string.common_clear),
                trailingText = if (query.isNotBlank()) pluralStringResource(R.plurals.settings_match_count, results.size, results.size) else null,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 14.dp),
            )
        }
        if (query.isNotBlank()) {
            if (results.isEmpty()) {
                item(key = "none") { PageNote(stringResource(R.string.settings_no_settings_match, query.trim())) }
            }
            results.forEachIndexed { i, hit ->
                item(key = "hit-$i") {
                    StageSettingRow(
                        title = hit.title,
                        styledTitle = highlighted(hit.title, query),
                        overline = hit.path,
                        icon = hit.icon,
                        onClick = { onOpenRoute(hit.route) },
                    ) { StageRowValue(hit.value.orEmpty(), StageChevron.FORWARD) }
                }
            }
            return@SettingsStagePage
        }
        val counts = SettingsGroup.entries.associateWith { group ->
            if (group == SettingsGroup.QUICK) quickCount else all.getValue(group).sumOf { it.items.size }
        }
        SettingsGroup.entries.chunked(2).forEachIndexed { r, pair ->
            item(key = "cards-$r") {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { group ->
                        GroupCard(group, counts.getValue(group), Modifier.weight(1f)) { onOpenRoute(group.route) }
                    }
                }
            }
        }
    }
    QuickPinSheet(pins)
}

/** `.gcard`: 18 dp corners on ink 4.5%; the icon, the count at the top right, the name (13.5 / 800), what it holds. */
@Composable
private fun GroupCard(group: SettingsGroup, count: Int, modifier: Modifier, onClick: () -> Unit) {
    val palette = LocalStagePalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(18.dp)
    val focus = stageAccent.focus
    val accent = stageAccent.accent
    Box(
        modifier
            .heightIn(min = 92.dp)
            .clip(shape)
            .background(if (pressed) accent.copy(alpha = 0.12f) else palette.ink.copy(alpha = 0.045f))
            // A pressed card takes the selected card's look (`.gcard.sel`): accent 12% and the focus ring.
            .then(if (pressed) Modifier.drawBehind { drawInnerRing(focus, 1.5.dp.toPx(), 18.dp.toPx()) } else Modifier)
            .stageClickable(interaction, onClick, null)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            StageIcon(group.icon, 18.dp, palette.muted)
            Text(stringResource(group.titleRes), style = stageText(13.5f, 800), color = palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            Text(stringResource(group.cardRes), style = stageText(10.5f, 500, lineHeight = 1.25f), color = palette.muted, modifier = Modifier.padding(top = 2.dp))
        }
        Text(count.toString(), style = stageText(11f, 700), color = palette.dim, modifier = Modifier.align(Alignment.TopEnd))
    }
}

/** The match in the accent, the rest of the name as it is. */
@Composable
private fun highlighted(title: String, query: String): AnnotatedString {
    val accent = stageAccent.accent
    return buildAnnotatedString {
        append(title)
        query.trim().lowercase().split(' ').filter { it.isNotBlank() }.forEach { word ->
            val at = title.lowercase().indexOf(word)
            if (at >= 0) addStyle(SpanStyle(color = accent), at, at + word.length)
        }
    }
}

/** One search result: where it lives, what it is called, what it is set to and where a tap goes. */
private class SearchHit(val title: String, val path: String, val value: String?, val icon: androidx.compose.ui.graphics.vector.ImageVector, val route: String)

/**
 * Settings search: every word must be in the setting's own name — not a bag of keywords, which listed whole
 * groups for "subt". The rows of every group page, then the rows inside the full pages.
 */
@Composable
private fun settingsSearch(query: String, all: Map<SettingsGroup, List<SettingSection>>): List<SearchHit> {
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    val sep = stringResource(R.string.content_epg_bits_separator)
    val on = stringResource(R.string.common_on)
    val off = stringResource(R.string.common_off)
    val leafRows = LEAF_ROWS.map { (leaf, titleRes) ->
        SearchHit(stringResource(titleRes), stringResource(leaf.group.titleRes) + sep + stringResource(leaf.titleRes), null, leaf.group.icon, leaf.route)
    }
    if (words.isEmpty()) return emptyList()
    val groupRows = all.filterKeys { it != SettingsGroup.QUICK }.flatMap { (group, sections) ->
        val path = stringResource(group.titleRes)
        sections.flatMap { it.items }.map { item ->
            val value = when (val v = item.value) {
                is SettingValue.Switch -> if (v.on) on else off
                is SettingValue.Choice -> v.text
                is SettingValue.Opens -> v.text
                is SettingValue.Stepper -> v.text
                is SettingValue.Segmented -> v.options.getOrNull(v.selected)
                else -> null
            }
            SearchHit(item.title, path, value, item.icon, group.route)
        }
    }
    return (groupRows + leafRows).filter { hit -> words.all { hit.title.lowercase().contains(it) } }
}

/** Rows inside the full pages that are worth finding by their own name. */
private val LEAF_ROWS: List<Pair<SettingsLeaf, Int>> = listOf(
    // Set per playlist, in its Names sheet: the hit opens the list of playlists to pick one from.
    SettingsLeaf.PLAYLISTS to R.string.settings_sources_clean_names_toggle,
    SettingsLeaf.EPG_SOURCES to R.string.settings_epg_sources_auto_refresh_title,
    SettingsLeaf.EPG_SOURCES to R.string.settings_epg_sources_use_logos,
    SettingsLeaf.FONTS to R.string.settings_font_size,
    SettingsLeaf.FONTS to R.string.settings_popup_font_size,
    SettingsLeaf.FONTS to R.string.settings_main_interface_font,
    SettingsLeaf.FONTS to R.string.settings_popup_font,
    SettingsLeaf.GLASS_EFFECT to R.string.settings_bg_background,
    SettingsLeaf.GLASS_EFFECT to R.string.settings_bg_picture,
    SettingsLeaf.GLASS_EFFECT to R.string.settings_bg_look,
    SettingsLeaf.GLASS_EFFECT to R.string.settings_bg_darken,
    SettingsLeaf.GLASS_EFFECT to R.string.settings_bg_blur,
    SettingsLeaf.GLASS_EFFECT to R.string.settings_glass_opacity,
    SettingsLeaf.HOME to R.string.home_row_now_trending,
    SettingsLeaf.HOME to R.string.home_trending_style,
    SettingsLeaf.HOME to R.string.settings_hero_preview,
    SettingsLeaf.HOME to R.string.settings_live_keep_watching,
    SettingsLeaf.HOME to R.string.settings_movies_keep_watching,
    SettingsLeaf.HOME to R.string.settings_series_keep_watching,
    SettingsLeaf.BROWSING to R.string.settings_browsing_last_category,
    SettingsLeaf.BROWSING to R.string.settings_guide_follows_live,
    SettingsLeaf.BROWSING to R.string.settings_browsing_last_item,
    SettingsLeaf.METADATA to R.string.settings_metadata_source,
    SettingsLeaf.METADATA to R.string.settings_metadata_language,
    SettingsLeaf.OPEN_SUBTITLES to R.string.player_subtitles_search_language,
    SettingsLeaf.OPEN_SUBTITLES to R.string.player_subtitles_filter_title,
    SettingsLeaf.OPEN_SUBTITLES to R.string.settings_delete_subtitles,
    SettingsLeaf.SUBTITLE_APPEARANCE to R.string.settings_subtitle_size,
    SettingsLeaf.SUBTITLE_APPEARANCE to R.string.settings_subtitle_font,
    SettingsLeaf.SUBTITLE_APPEARANCE to R.string.settings_subtitle_color_short,
    SettingsLeaf.SUBTITLE_APPEARANCE to R.string.settings_subtitle_position_short,
    SettingsLeaf.SUBTITLE_APPEARANCE to R.string.settings_subtitle_background_transparency,
    SettingsLeaf.PROXY to R.string.settings_use_proxy,
    SettingsLeaf.DNS to R.string.settings_dns_use_custom,
)

/** Quick: the pinned rows, in the pinned order, each drawn as it is on its own page. */
@Composable
private fun quickRows(all: Map<SettingsGroup, List<SettingSection>>, pins: QuickPins): List<SettingItem> {
    val home = all.entries.flatMap { (group, sections) -> sections.flatMap { it.items }.map { it.key to group } }.toMap()
    val byKey = all.values.flatten().flatMap { it.items }.associateBy { it.key }
    val rows = pins.keys.map { stored -> stored to (QUICK_ALIASES[stored] ?: stored) }
        .distinctBy { it.second }
        .mapNotNull { (stored, key) ->
            val row = byKey[key] ?: return@mapNotNull null
            // The six Quick starts with keep Quick's own short words (the TV's); anything else pinned
            // says where it lives, so a pin never loses its home.
            val words = QUICK_WORDS[stored]
            if (words != null) {
                row.reworded(stringResource(words.first), stringResource(words.second))
            } else {
                row.reworded(row.title, home[key]?.let { stringResource(R.string.settings_pinned_from, stringResource(it.titleRes)) })
            }
        }
    pins.shown = rows.map { it.key }
    return rows
}

/** Quick's own title and line for the pins it starts with, by their stored (TV) key. */
private val QUICK_WORDS: Map<String, Pair<Int, Int>> = mapOf(
    "quick_hdr" to (R.string.settings_quick_prefer_sdr to R.string.settings_line_prefer_sdr),
    "quick_check_update" to (R.string.settings_quick_check_update to R.string.settings_line_when_app_opens),
)

/**
 * A group page: "‹ Settings · 13 settings" under the title, then its sections. Quick is the
 * pinned rows of every other group, or a line saying how to pin one.
 */
@Composable
fun SettingsGroupScreen(group: SettingsGroup, modifier: Modifier = Modifier, vm: SettingsViewModel = koinViewModel()) {
    val pins = rememberQuickPins(vm)
    val sections = if (group == SettingsGroup.QUICK) {
        val rows = quickRows(allGroupSections(vm), pins)
        if (rows.isEmpty()) emptyList() else listOf(SettingSection(null, rows))
    } else {
        groupSections(group, vm)
    }
    // Every row counts, Add a profile included ("Settings · 4 settings").
    val count = sections.sumOf { it.items.size }
    SettingsCrumb(pluralStringResource(R.plurals.settings_setting_count, count, count))
    val open = rememberOpenSheet()
    SettingsStagePage(modifier) {
        if (group == SettingsGroup.QUICK && sections.isEmpty()) {
            item(key = "empty") {
                PageNote(stringResource(R.string.settings_quick_empty_title) + "\n" + stringResource(R.string.settings_quick_empty_hint_touch))
            }
        }
        settingSections(sections, pins) { open.value = it }
    }
    OpenSheetHost(open, sections)
    QuickPinSheet(pins)
}

/**
 * A settings page's crumb (`‹ Settings · 13 settings`): muted, a tap goes back. [detail] follows the path; a
 * full page adds its group ("Settings · Sources & guide · 3 playlists").
 */
@Composable
fun SettingsCrumb(detail: String?, group: SettingsGroup? = null) {
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val sep = stringResource(R.string.content_epg_bits_separator)
    val text = listOfNotNull(stringResource(R.string.common_nav_settings), group?.let { stringResource(it.titleRes) }, detail).joinToString(sep)
    ProvidePageCrumb { owner -> PageCrumb(accent = "", rest = null, owner = owner, styled = AnnotatedString(text), onClick = { back?.onBackPressed() }) }
}
