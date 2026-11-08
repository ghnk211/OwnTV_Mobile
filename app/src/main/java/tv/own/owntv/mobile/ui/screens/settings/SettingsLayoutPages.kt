package tv.own.owntv.mobile.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.menu.MenuLayout
import tv.own.owntv.core.menu.applyMenuOrder
import tv.own.owntv.core.menu.catalogue
import tv.own.owntv.core.model.ContentMenu
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageButton
import tv.own.owntv.mobile.ui.stage.StageButtonStyle
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StageSheetHeader

/**
 * Layout › Browsing & lists: for Live TV, Movies and Series, whether a section reopens on the category and
 * the item last used; then whether Live TV lists its built-in Catch-up and All channels categories.
 */
@Composable
fun SettingsBrowsingPage(modifier: Modifier = Modifier, vm: SettingsViewModel = koinViewModel()) {
    val s = vm.settings
    val sections = listOf(
        Triple(R.string.common_nav_live_tv, s.rememberCategoryLive.pref(true) to s.rememberLastLive.pref(false),
            (({ on: Boolean -> vm.edit { setRememberCategoryLive(on) } }) to ({ on: Boolean -> vm.edit { setRememberLastLive(on) } }))),
        Triple(R.string.common_nav_movies, s.rememberCategoryMovies.pref(true) to s.rememberLastMovies.pref(false),
            (({ on: Boolean -> vm.edit { setRememberCategoryMovies(on) } }) to ({ on: Boolean -> vm.edit { setRememberLastMovies(on) } }))),
        Triple(R.string.common_nav_series, s.rememberCategorySeries.pref(true) to s.rememberLastSeries.pref(false),
            (({ on: Boolean -> vm.edit { setRememberCategorySeries(on) } }) to ({ on: Boolean -> vm.edit { setRememberLastSeries(on) } }))),
    )
    val note = stringResource(R.string.settings_browsing_description_full)
    // Live TV's section only: whether the TV Guide shares its remembered category, under that row.
    val guideFollows = switchItem(
        "browse_guide_follows_live", MobileIcons.Guide, stringResource(R.string.settings_guide_follows_live),
        stringResource(R.string.settings_guide_follows_live_description), s.guideFollowsLiveCategory.pref(false),
    ) { on -> vm.edit { setGuideFollowsLiveCategory(on) } }
    val built = sections.map { (titleRes, values, setters) ->
        SettingSection(
            stringResource(titleRes),
            listOfNotNull(
                switchItem("browse_cat_$titleRes", MobileIcons.Folder, stringResource(R.string.settings_browsing_last_category), null, values.first, setters.first),
                guideFollows.takeIf { titleRes == R.string.common_nav_live_tv },
                switchItem("browse_item_$titleRes", MobileIcons.History, stringResource(R.string.settings_browsing_last_item), null, values.second, setters.second),
            ),
        )
    }
    val builtIns = s.liveBuiltIns.pref(tv.own.owntv.core.live.LiveBuiltIns())
    val liveCategories = SettingSection(
        stringResource(R.string.settings_browsing_live_categories),
        listOf(
            switchItem("browse_live_catchup", MobileIcons.Catchup, stringResource(R.string.content_catchup), null, builtIns.showCatchup) { on -> vm.edit { setLiveShowCatchupRail(on) } },
            switchItem("browse_live_all", MobileIcons.LiveTv, stringResource(R.string.content_category_all_channels), null, builtIns.showAll) { on -> vm.edit { setLiveShowAllRail(on) } },
        ),
    )
    val liveCategoriesNote = stringResource(R.string.settings_browsing_live_categories_description)
    val pins = rememberQuickPins(vm)
    SettingsStagePage(modifier) {
        item(key = "note") { PageNote(note) }
        // These rows are not Quick toggles; a hold does nothing.
        settingSections((built + liveCategories).map { sec -> SettingSection(sec.title, sec.items.map { it.unpinnable() }) }, pins) {}
        item(key = "live_categories_note") { PageNote(liveCategoriesNote) }
    }
}

private fun SettingItem.unpinnable() = SettingItem(key, icon, title, line, value, onClick, sheet, pinnable = false)

/** The long-press menus this page offers; movies and series no longer have one. */
private val MENU_PAGES = listOf(ContentMenu.LIVE, ContentMenu.EPISODE)

/** Layout › Long-press menus: Live TV and Episodes — each opens its actions to rearrange or hide. */
@Composable
fun SettingsMenusPage(modifier: Modifier = Modifier, vm: SettingsViewModel = koinViewModel()) {
    var menu by remember { mutableStateOf<ContentMenu?>(null) }
    val note = stringResource(R.string.settings_content_menus_description)
    val rows = MENU_PAGES.map { m ->
        SettingItem("menu_${m.name}", MobileIcons.MoreHoriz, stringResource(m.titleRes()), null, SettingValue.Opens(null), onClick = { menu = m }, pinnable = false)
    }
    SettingsStagePage(modifier) {
        item(key = "note") { PageNote(note) }
        rows.forEach { row -> item(key = row.key) { SettingItemRow(row, {}, null) } }
    }
    menu?.let { MenuOrderSheet(vm, it) { menu = null } }
}

/**
 * One long-press menu's actions, in the order they will appear, with an eye to show or hide each one
 * and ↑ ↓ to rearrange them. A hidden action stays listed, dimmed, so it can be shown again; Reset
 * puts the shipped menu back.
 */
@Composable
private fun MenuOrderSheet(vm: SettingsViewModel, menu: ContentMenu, onDismiss: () -> Unit) {
    val layout = remember(menu) { vm.settings.menuLayout(menu) }.pref(MenuLayout())
    val actions = applyMenuOrder(catalogue(menu), layout.order) { it.key }
    val shipped = catalogue(menu).map { it.key }
    // A list equal to the shipped one is saved as "no order", so a later release's new action keeps its place.
    fun save(order: List<String>, hidden: Set<String>) = vm.edit { setMenuLayout(menu, MenuLayout(order.takeIf { it != shipped }.orEmpty(), hidden)) }
    MobileBottomSheet(onDismissRequest = onDismiss) {
        StageSheetHeader(stringResource(menu.titleRes()), eyebrow = stringResource(R.string.settings_content_menus_title))
        PageNote(stringResource(R.string.settings_content_menus_note))
        androidx.compose.foundation.layout.Column(Modifier.sheetScroll()) {
            actions.forEachIndexed { index, action ->
                val hidden = action.key in layout.hidden
                StageMenuItem(
                    stringResource(action.labelRes),
                    onClick = {},
                    enabled = !hidden,
                    tags = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            MoveKey(
                                if (hidden) MobileIcons.VisibilityOff else MobileIcons.Visibility,
                                if (hidden) R.string.common_show else R.string.common_hide,
                                true,
                            ) { save(actions.map { it.key }, if (hidden) layout.hidden - action.key else layout.hidden + action.key) }
                            MoveKey(MobileIcons.KeyboardArrowUp, R.string.settings_row_menu_move_up, index > 0) { save(actions.movedKeys(index, -1), layout.hidden) }
                            MoveKey(MobileIcons.KeyboardArrowDown, R.string.settings_row_menu_move_down, index < actions.lastIndex) { save(actions.movedKeys(index, 1), layout.hidden) }
                        }
                    },
                )
            }
        }
        if (layout.isCustom(menu)) {
            StageButton(
                onClick = { vm.edit { setMenuLayout(menu, MenuLayout()) } },
                text = stringResource(R.string.common_reset),
                style = StageButtonStyle.PLAIN,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/** A 28 dp ink key with an arrow (the stepper's key shape). */
@Composable
internal fun MoveKey(icon: androidx.compose.ui.graphics.vector.ImageVector, @StringRes labelRes: Int, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalStagePalette.current
    val label = stringResource(labelRes)
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(palette.ink.copy(alpha = 0.08f))
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { tv.own.owntv.mobile.ui.stage.StageIcon(icon, 16.dp, if (enabled) palette.text else palette.dim) }
}

private fun List<tv.own.owntv.core.menu.MenuActionRef>.movedKeys(index: Int, delta: Int): List<String> =
    map { it.key }.toMutableList().apply { add(index + delta, removeAt(index)) }

private fun ContentMenu.titleRes() = when (this) {
    ContentMenu.LIVE -> R.string.common_nav_live_tv
    ContentMenu.MOVIE -> R.string.common_nav_movies
    ContentMenu.SERIES -> R.string.common_nav_series
    ContentMenu.EPISODE -> R.string.content_episodes
}
