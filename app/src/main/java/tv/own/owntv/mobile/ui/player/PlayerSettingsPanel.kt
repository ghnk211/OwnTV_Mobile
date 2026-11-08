package tv.own.owntv.mobile.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.categorylock.SettingsPinGate
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.screens.settings.LocalSettingsNav
import tv.own.owntv.mobile.ui.screens.settings.OpenSheetHost
import tv.own.owntv.mobile.ui.screens.settings.QuickPinSheet
import tv.own.owntv.mobile.ui.screens.settings.SettingSection
import tv.own.owntv.mobile.ui.screens.settings.SettingsGroup
import tv.own.owntv.mobile.ui.screens.settings.SettingsViewModel
import tv.own.owntv.mobile.ui.screens.settings.allGroupSections
import tv.own.owntv.mobile.ui.screens.settings.quickRows
import tv.own.owntv.mobile.ui.screens.settings.rememberOpenSheet
import tv.own.owntv.mobile.ui.screens.settings.rememberQuickPins
import tv.own.owntv.mobile.ui.screens.settings.settingSections
import tv.own.owntv.mobile.ui.stage.LocalScrollLongTitles
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StagePalette
import tv.own.owntv.mobile.ui.stage.StageSheetHeader

/**
 * The player's right overlay: the settings, held on top of the picture rather than left for a trip
 * to the Settings tab.
 *
 * Two levels, exactly as the tab has them: the eight groups, then the rows of the one picked. The
 * rows are **the same objects the tab draws** — [allGroupSections] is the same builder
 * `SettingsScreen` and `SettingsGroupScreen` read — so a row can never exist here and not there, in
 * a different order, or with a different value. A row that opens a page navigates the real route,
 * which leaves the player underneath on the back stack.
 *
 * The panel asks for the Settings PIN itself ([SettingsPinGate]): the player is not one of the
 * guarded routes, so each long press opens a visit of its own.
 */
@Composable
internal fun PlayerSettingsPanel(
    onOpenRoute: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = koinViewModel(),
) {
    SettingsPinGate(onDismiss = onDismiss) {
        // The panel is drawn over a film: the player's own colours, whatever the page theme is — and
        // a third of a screen cuts most setting names, so their titles scroll rather than end in "…".
        val openRoute: (String) -> Unit = { route -> onDismiss(); onOpenRoute(route) }
        CompositionLocalProvider(
            LocalStagePalette provides StagePalette.Dark,
            LocalScrollLongTitles provides true,
            LocalSettingsNav provides openRoute,
        ) {
            PlayerSettingsSheet(vm = vm, onDismiss = onDismiss, modifier = modifier)
        }
    }
}

/**
 * The groups the panel offers: everything about what is playing, and nothing about the app itself.
 * Appearance (theme, fonts, glass) and App (language, app lock, downloads, network) are page-wide
 * matters — changing them over a film answers a question nobody asked in that moment.
 */
private val PANEL_GROUPS: List<SettingsGroup> =
    SettingsGroup.entries.filterNot { it == SettingsGroup.APPEARANCE || it == SettingsGroup.APP }

@Composable
private fun PlayerSettingsSheet(vm: SettingsViewModel, onDismiss: () -> Unit, modifier: Modifier) {
    var group by remember { mutableStateOf<SettingsGroup?>(null) }
    val pins = rememberQuickPins(vm)
    val all = allGroupSections(vm)
    val open = rememberOpenSheet()

    // Quick is not one of the builders: it is whichever rows the profile pinned, in its own order.
    val current = group
    val quick = if (current == SettingsGroup.QUICK) quickRows(all, pins) else emptyList()
    val sections: List<SettingSection> = when (current) {
        null -> emptyList()
        SettingsGroup.QUICK -> if (quick.isEmpty()) emptyList() else listOf(SettingSection(null, quick))
        else -> all[current].orEmpty()
    }

    BackHandler(onBack = { if (current != null) group = null else onDismiss() })
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // The panel comes from the end edge, so the picture stays whole on the other side of it.
    val wash = if (rtl) {
        arrayOf(0f to Color(0xBF020506), 0.7f to Color.Transparent)
    } else {
        arrayOf(0.3f to Color.Transparent, 1f to Color(0xBF020506))
    }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(*wash))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        // A third of the screen (G7) held sideways; two thirds of it held upright, where a third
        // would be 120 dp and every row would be a scroll of one word. Measured from the box, so
        // the width is right after a rotation rather than the one the window had at first.
        val landscape = maxWidth > maxHeight
        val panelWidth = if (landscape) maxWidth / 3 else maxWidth * 0.62f
        Row(
            Modifier.fillMaxSize().padding(if (landscape) 14.dp else 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Column(
                Modifier
                    .width(panelWidth)
                    .fillMaxHeight()
                    .playerPane(RoundedCornerShape(24.dp))
                    // The panel's own taps stay in it.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(horizontal = 6.dp, vertical = 10.dp),
            ) {
                if (current == null) {
                    // The badge is every row the group holds, Quick included — counted here, in the
                    // composable scope, because a LazyListScope body cannot read a string resource.
                    val quickCount = quickRows(all, pins).size
                    val counts = PANEL_GROUPS.associateWith { g ->
                        if (g == SettingsGroup.QUICK) quickCount else all[g].orEmpty().sumOf { it.items.size }
                    }
                    StageSheetHeader(stringResource(R.string.common_nav_settings))
                    LazyColumn(Modifier.fillMaxHeight()) {
                        PANEL_GROUPS.forEach { g ->
                            item(key = g.name) {
                                StageMenuItem(
                                    label = stringResource(g.titleRes),
                                    onClick = { group = g },
                                    icon = g.icon,
                                    count = counts.getValue(g).toString(),
                                    chevron = true,
                                    height = 52.dp,
                                )
                            }
                        }
                    }
                } else {
                    val count = sections.sumOf { it.items.size }
                    StageMenuItem(
                        label = stringResource(current.titleRes),
                        onClick = { group = null },
                        icon = MobileIcons.ArrowBack,
                        count = pluralStringResource(R.plurals.settings_setting_count, count, count),
                        height = 48.dp,
                    )
                    LazyColumn(
                        Modifier.fillMaxHeight(),
                        contentPadding = PaddingValues(bottom = 8.dp),
                    ) {
                        settingSections(sections, pins) { open.value = it }
                    }
                }
            }
        }
    }
    // A row's own sheet (a choice list, a confirm) draws over the panel, on the activity's sheet
    // host — the player has no `SettingsStagePage` to hang it from.
    OpenSheetHost(open, sections)
    QuickPinSheet(pins)
}
