package tv.own.owntv.mobile.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tv.own.owntv.core.nav.MainSection
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageChevron
import tv.own.owntv.mobile.ui.stage.StageLabel
import tv.own.owntv.mobile.ui.stage.StageRowValue
import tv.own.owntv.mobile.ui.stage.StageSettingRow
import tv.own.owntv.mobile.ui.stage.StageSheetHeader
import tv.own.owntv.mobile.ui.stage.StageSwitch
import tv.own.owntv.mobile.ui.stage.drawInnerRing
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * Settings › Layout › Navigation, the phone's version of the television's Navigation popup: the
 * bottom bar with or without names, whether it slides away while scrolling, which places it shows
 * (Dynamic follows the playlist; Static lists the places to pick, right here), and counts in the sheets.
 */
@Composable
fun NavigationSheet(vm: SettingsViewModel, onDismiss: () -> Unit) {
    val labels = vm.settings.phoneNavLabels.pref(true)
    val hideOnScroll = vm.settings.phoneNavHideOnScroll.pref(true)
    val mode = vm.settings.navMenuMode.pref(SettingsRepository.NavMenuMode.STATIC)
    val hidden = vm.settings.navMenuHidden.pref(emptySet())
    val counts = vm.settings.sheetCounts.pref(true)
    val title = stringResource(R.string.settings_navigation)
    val dynamic = mode == SettingsRepository.NavMenuMode.DYNAMIC

    MobileBottomSheet(onDismissRequest = onDismiss, title = title) {
        StageSheetHeader(
            title = title,
            subtitle = stringResource(R.string.common_nav_settings) + " · " + stringResource(R.string.settings_group_layout),
        )
        StageLabel(stringResource(R.string.settings_nav_bar), Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 6.dp))
        Row(Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceButton(stringResource(R.string.settings_nav_labels), labels, Modifier.weight(1f)) { vm.edit { setPhoneNavLabels(true) } }
            ChoiceButton(stringResource(R.string.settings_nav_icons_only), !labels, Modifier.weight(1f)) { vm.edit { setPhoneNavLabels(false) } }
        }
        StageSettingRow(
            title = stringResource(R.string.settings_nav_hide_scrolling),
            icon = MobileIcons.OpenInFull,
            subtitle = stringResource(R.string.settings_nav_hide_scrolling_desc),
            role = Role.Switch,
            onClick = { vm.edit { setPhoneNavHideOnScroll(!hideOnScroll) } },
        ) { StageSwitch(hideOnScroll) }
        // Two choices, so a tap flips between them rather than opening a second sheet over this one.
        StageSettingRow(
            title = stringResource(R.string.settings_nav_menu_items),
            icon = MobileIcons.MoreHoriz,
            subtitle = stringResource(R.string.settings_nav_menu_items_desc),
            onClick = {
                vm.edit {
                    setNavMenuMode(if (dynamic) SettingsRepository.NavMenuMode.STATIC else SettingsRepository.NavMenuMode.DYNAMIC)
                }
            },
        ) { StageRowValue(stringResource(if (dynamic) R.string.settings_dynamic else R.string.settings_static), StageChevron.DOWN) }
        if (!dynamic) {
            NAV_MENU_TOGGLES.forEach { section ->
                val shown = section.name !in hidden
                StageSettingRow(
                    title = stringResource(section.labelRes),
                    role = Role.Switch,
                    onClick = { vm.edit { setNavMenuHidden(if (shown) hidden + section.name else hidden - section.name) } },
                ) { StageSwitch(shown) }
            }
        }
        StageSettingRow(
            title = stringResource(R.string.settings_sheet_counts),
            icon = MobileIcons.Tiles,
            subtitle = stringResource(R.string.settings_sheet_counts_desc),
            role = Role.Switch,
            onClick = { vm.edit { setSheetCounts(!counts) } },
        ) { StageSwitch(counts) }
    }
}

/**
 * The browse sections the sheet offers as switches. Home stays pinned (hiding it would leave a playlist
 * with nothing in the bar), and sports, movies, series and the guide are no longer offered at all.
 */
private val NAV_MENU_TOGGLES = listOf(MainSection.LIVE_TV, MainSection.DOWNLOADS)

/** `.row2 span`: one of two side-by-side choices, 40 dp; the chosen one accent-tinted with its ring. */
@Composable
private fun ChoiceButton(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val palette = LocalStagePalette.current
    val accent = stageAccent.accent
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (on) accent.copy(alpha = 0.18f) else palette.controlFill)
            .then(if (on) Modifier.drawBehind { drawInnerRing(accent.copy(alpha = 0.6f), 1.2.dp.toPx(), 12.dp.toPx()) } else Modifier)
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = stageText(13f, 700), color = if (on) accent else palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
