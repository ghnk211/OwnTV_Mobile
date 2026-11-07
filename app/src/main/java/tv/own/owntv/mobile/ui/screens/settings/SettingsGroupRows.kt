package tv.own.owntv.mobile.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.mobile.i18n.AppLocales
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.metadata.MetadataConfig
import tv.own.owntv.core.metadata.MetadataMode
import tv.own.owntv.core.model.HomeConfig
import tv.own.owntv.core.model.HomeRow
import tv.own.owntv.core.reminder.ReminderSchedule
import tv.own.owntv.core.settings.GuideRetention
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.settings.StartupMode
import tv.own.owntv.core.theme.AppFontFamily
import tv.own.owntv.core.theme.BackgroundConfig
import tv.own.owntv.core.theme.BackgroundStyle
import tv.own.owntv.core.theme.FontCustomization
import tv.own.owntv.core.theme.GlassConfig
import tv.own.owntv.core.theme.PictureLook
import tv.own.owntv.core.theme.ThemeMode
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.nav.MobileDestination
import tv.own.owntv.mobile.ui.profiles.ProfilePinSheet
import tv.own.owntv.mobile.ui.screens.downloads.volumeName
import tv.own.owntv.mobile.ui.stage.StageGroupLabel
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StageSearchField
import tv.own.owntv.mobile.ui.stage.StageSheetHeader

/** Where a row that opens a page goes: navigation on a phone. Provided by the nav host around every settings route. */
val LocalSettingsNav = staticCompositionLocalOf<(String) -> Unit> { {} }

/** The rows of [group], with their values as they are now. Quick is built from the others ([quickSections]). */
@Composable
fun groupSections(group: SettingsGroup, vm: SettingsViewModel): List<SettingSection> = when (group) {
    SettingsGroup.QUICK -> emptyList()
    SettingsGroup.SOURCES -> sourcesSections(vm)
    SettingsGroup.APPEARANCE -> appearanceSections(vm)
    SettingsGroup.LAYOUT -> layoutSections(vm)
    SettingsGroup.CONTENT -> contentSections(vm)
    SettingsGroup.PLAYER -> playerSections(vm)
    SettingsGroup.PICTURE -> pictureSections(vm)
    SettingsGroup.SOUND -> soundSections(vm)
    SettingsGroup.LIVE -> liveSections(vm)
    SettingsGroup.WATCHING -> watchingSections(vm)
    SettingsGroup.APP -> appSections(vm)
}

/** Every group's rows at once: the root's counts, Quick and the search read them all. */
@Composable
fun allGroupSections(vm: SettingsViewModel): Map<SettingsGroup, List<SettingSection>> =
    SettingsGroup.entries.associateWith { androidx.compose.runtime.key(it) { groupSections(it, vm) } }

/** A choice row: its value with ▾, a tap opens [StageChoiceSheet] with the group as the eyebrow. */
@Composable
internal fun <T> choiceItem(
    key: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    group: SettingsGroup,
    title: String,
    line: String?,
    help: String?,
    choices: List<SettingsChoice<T>>,
    selected: T,
    recommended: T?,
    onSelect: (T) -> Unit,
    valueText: String = choices.firstOrNull { it.value == selected }?.label.orEmpty(),
): SettingItem {
    val eyebrow = stringResource(group.titleRes)
    return SettingItem(
        key = key, icon = icon, title = title, line = line,
        value = SettingValue.Choice(valueText),
        sheet = { dismiss ->
            StageChoiceSheet(eyebrow, title, help, choices, selected, onSelect, dismiss, recommended)
        },
    )
}

/** A switch row: a tap flips it. */
internal fun switchItem(
    key: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    line: String?,
    on: Boolean,
    onChange: (Boolean) -> Unit,
) = SettingItem(key, icon, title, line, SettingValue.Switch(on), onClick = { onChange(!on) })

/** A row that opens a full page. */
@Composable
internal fun pageItem(
    key: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    line: String?,
    value: String?,
    route: String,
): SettingItem {
    val nav = LocalSettingsNav.current
    return SettingItem(key, icon, title, line, SettingValue.Opens(value), onClick = { nav(route) })
}

// ---------------------------------------------------------------------------------------------- Sources

@Composable
private fun sourcesSections(vm: SettingsViewModel): List<SettingSection> {
    val g = SettingsGroup.SOURCES
    val s = vm.settings
    val sources by vm.sources.collectAsStateWithLifecycle()
    val epgVm: EpgSourcesViewModel = koinViewModel()
    val feeds by epgVm.sources.collectAsStateWithLifecycle()

    val offset = s.epgOffsetMinutes.pref(0)
    val reminderMode = s.reminderMode.pref(SettingsRepository.ReminderMode.ASK)
    val lead = s.reminderLeadMinutes.pref(ReminderSchedule.DEFAULT_LEAD_MINUTES)
    val guideDays = s.guideDaysToKeep.pref(GuideRetention.DEFAULT_DAYS)
    val tz = s.catchupTimezone.pref(SettingsRepository.CatchupTimezone.AUTOMATIC)
    val tzOffset = s.catchupOffsetMinutes.pref(0)

    val catchupValue = when (tz) {
        SettingsRepository.CatchupTimezone.AUTOMATIC -> stringResource(R.string.settings_catchup_timezone_automatic)
        SettingsRepository.CatchupTimezone.DEVICE -> stringResource(R.string.settings_catchup_timezone_device)
        SettingsRepository.CatchupTimezone.MANUAL -> utcOffsetLabel(tzOffset)
    }
    return listOfNotNull(
        SettingSection(
            stringResource(R.string.settings_sources_title),
            listOf(
                pageItem(
                    "tab_SOURCES", MobileIcons.Layers, stringResource(R.string.settings_playlists),
                    stringResource(R.string.settings_line_playlists_mobile),
                    pluralStringResource(R.plurals.more_playlist_count, sources.size, sources.size), SettingsLeaf.PLAYLISTS.route,
                ),
                pageItem(
                    "tab_EPG", MobileIcons.Guide, stringResource(R.string.settings_epg_sources),
                    stringResource(R.string.settings_epg_sources_nav_description),
                    pluralStringResource(R.plurals.settings_epg_feed_count, feeds.size, feeds.size), SettingsLeaf.EPG_SOURCES.route,
                ),
            ),
        ),
        SettingSection(
            stringResource(R.string.settings_part_guide_catchup),
            listOfNotNull(
                choiceItem(
                    "epg_offset", MobileIcons.Schedule, g, stringResource(R.string.content_epg_time_offset),
                    stringResource(R.string.settings_line_epg_offset), stringResource(R.string.settings_epg_offset_dialog_description),
                    // Whole hours: the guide shift keeps hours, only catch-up went to quarter hours.
                    (-12..14).map { SettingsChoice(it * 60, offsetOrOff(it * 60)) },
                    offset, 0, { m -> vm.edit { setEpgOffsetMinutes(m) } },
                    valueText = offsetOrOff(offset),
                ),
                choiceItem(
                    "reminders", MobileIcons.Bell, g, stringResource(R.string.settings_reminders),
                    stringResource(R.string.settings_line_reminders), stringResource(R.string.settings_reminders_description),
                    SettingsRepository.ReminderMode.entries.map { SettingsChoice(it, stringResource(reminderModeLabel(it))) },
                    reminderMode, SettingsRepository.ReminderMode.ASK, { m -> vm.edit { setReminderMode(m) } },
                ),
                choiceItem(
                    "reminder_lead", MobileIcons.Schedule, g, stringResource(R.string.settings_reminder_lead),
                    stringResource(R.string.settings_line_reminder_lead), stringResource(R.string.settings_reminder_lead_description),
                    ReminderSchedule.LEAD_CHOICES.map { SettingsChoice(it, tv.own.owntv.mobile.ui.screens.guide.reminderLeadText(it)) },
                    lead, ReminderSchedule.DEFAULT_LEAD_MINUTES, { m -> vm.edit { setReminderLeadMinutes(m) } },
                ),
                choiceItem(
                    "guide_days", MobileIcons.CalendarMonth, g, stringResource(R.string.settings_epg_guide_days),
                    stringResource(R.string.settings_line_guide_days), stringResource(R.string.settings_help_guide_days),
                    GuideRetention.PRESET_DAYS.map { SettingsChoice(it, pluralStringResource(R.plurals.settings_epg_guide_days_value, it, it)) },
                    guideDays, GuideRetention.DEFAULT_DAYS, { d -> vm.edit { setGuideDaysToKeep(d) } },
                ),
                SettingItem(
                    "catchup", MobileIcons.Catchup, stringResource(R.string.settings_catchup),
                    stringResource(R.string.settings_line_catchup), SettingValue.Choice(catchupValue),
                    sheet = { dismiss -> CatchupSheet(vm, dismiss) },
                ),
                if (sources.isNotEmpty()) pageItem(
                    "catchup_sources", MobileIcons.Layers, stringResource(R.string.settings_catchup_timezone_per_playlist),
                    stringResource(R.string.settings_line_catchup_sources),
                    overrideCountLabel(sources.count { it.catchupTimezone != null }), SettingsLeaf.CATCHUP_SOURCES.route,
                ) else null,
            ),
        ),
    )
}

/** The guide shift: Off at zero, the UTC offset otherwise. */
@Composable
private fun offsetOrOff(minutes: Int): String = if (minutes == 0) stringResource(R.string.common_off) else utcOffsetLabel(minutes)

/**
 * Catch-up: the zone the archives run on (the one the provider reports, the phone's own, or a fixed offset) and
 * which player opens a programme from the past — two questions of one setting, in one sheet.
 */
@Composable
private fun CatchupSheet(vm: SettingsViewModel, onDismiss: () -> Unit) {
    val s = vm.settings
    val tz = s.catchupTimezone.pref(SettingsRepository.CatchupTimezone.AUTOMATIC)
    val offset = s.catchupOffsetMinutes.pref(0)
    val player = s.catchupPlayer.pref(SettingsRepository.CatchupPlayer.INTERNAL)
    val manual = tz == SettingsRepository.CatchupTimezone.MANUAL
    MobileBottomSheet(onDismissRequest = onDismiss) {
        StageSheetHeader(stringResource(R.string.settings_catchup), eyebrow = stringResource(SettingsGroup.SOURCES.titleRes))
        SheetText(stringResource(R.string.settings_catchup_timezone_description))
        Column(Modifier.sheetScroll()) {
            StageGroupLabel(stringResource(R.string.settings_catchup_timezone_label))
            ChoiceRow(
                stringResource(R.string.settings_catchup_timezone_automatic),
                tz == SettingsRepository.CatchupTimezone.AUTOMATIC,
                recommended = true,
            ) {
                vm.edit { setCatchupTimezone(SettingsRepository.CatchupTimezone.AUTOMATIC) }
            }
            ChoiceRow(stringResource(R.string.settings_catchup_timezone_device), tz == SettingsRepository.CatchupTimezone.DEVICE) {
                vm.edit { setCatchupTimezone(SettingsRepository.CatchupTimezone.DEVICE) }
            }
            s.catchupOffsetChoicesMinutes.forEach { m ->
                ChoiceRow(utcOffsetLabel(m), manual && offset == m) {
                    vm.edit { setCatchupTimezone(SettingsRepository.CatchupTimezone.MANUAL); setCatchupOffsetMinutes(m) }
                }
            }
            StageGroupLabel(stringResource(R.string.settings_catchup_player))
            listOf(
                SettingsRepository.CatchupPlayer.ASK to R.string.settings_catchup_player_ask,
                SettingsRepository.CatchupPlayer.INTERNAL to R.string.settings_catchup_player_internal,
                SettingsRepository.CatchupPlayer.EXTERNAL to R.string.settings_catchup_player_external,
            ).forEach { (mode, label) ->
                ChoiceRow(stringResource(label), player == mode, recommended = mode == SettingsRepository.CatchupPlayer.INTERNAL) {
                    vm.edit { setCatchupPlayer(mode) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------- Appearance

@Composable
private fun appearanceSections(vm: SettingsViewModel): List<SettingSection> {
    val s = vm.settings
    val themeMode = s.themeMode.pref(ThemeMode.DARK)
    val glass = s.glassConfig.pref(GlassConfig())
    val background = s.backgroundConfig.pref(BackgroundConfig())
    val fonts = s.fontCustomization.pref(FontCustomization())
    val sep = stringResource(R.string.content_epg_bits_separator)

    val themes = ThemeMode.entries
    return listOf(
        SettingSection(
            null,
            listOf(
                SettingItem(
                    "theme", MobileIcons.WbSunny, stringResource(R.string.settings_theme), stringResource(R.string.settings_theme_description),
                    SettingValue.Segmented(themes.map { stringResource(it.themeLabel()) }, themes.indexOf(themeMode)) { i -> vm.edit { setThemeMode(themes[i]) } },
                ),
                pageItem(
                    "tab_GLASS_EFFECT", MobileIcons.Layers, stringResource(R.string.settings_glass_bg_title),
                    stringResource(R.string.settings_line_glass_bg_mobile), glassSummary(background, glass), SettingsLeaf.GLASS_EFFECT.route,
                ),
                pageItem(
                    "fonts", MobileIcons.Edit, stringResource(R.string.settings_title_fonts), stringResource(R.string.settings_line_fonts),
                    stringResource(fonts.mainFamily.labelRes()) + sep + stringResource(R.string.common_percent, fonts.sizePercent),
                    SettingsLeaf.FONTS.route,
                ),
            ),
        ),
    )
}

/** "Stage · 50%", "Soft · 56%", "Plain · Off": the background and the glass, as the TV's row says it. */
@Composable
private fun glassSummary(background: BackgroundConfig, glass: GlassConfig): String {
    val bg = when {
        background.showsPicture -> stringResource(
            when (background.look) {
                PictureLook.SHARP -> R.string.settings_bg_sharp
                PictureLook.SOFT -> R.string.settings_bg_soft
                PictureLook.DARK -> R.string.settings_bg_dark
            },
        )
        background.style == BackgroundStyle.PLAIN -> stringResource(R.string.settings_bg_plain)
        else -> stringResource(R.string.settings_bg_stage)
    }
    val glassText = if (glass.enabled) stringResource(R.string.common_percent, kotlin.math.round(glass.alpha * 100).toInt()) else stringResource(R.string.common_off)
    return bg + stringResource(R.string.content_epg_bits_separator) + glassText
}

// ----------------------------------------------------------------------------------------------- Layout

@Composable
private fun layoutSections(vm: SettingsViewModel): List<SettingSection> {
    val s = vm.settings
    val labels = s.phoneNavLabels.pref(true)
    val dynamic = s.navMenuMode.pref(SettingsRepository.NavMenuMode.DYNAMIC) == SettingsRepository.NavMenuMode.DYNAMIC
    val liveView = s.liveView.pref(SettingsRepository.LiveView.LIST)
    val vodView = s.vodViewMode.pref(SettingsRepository.VodViewMode.GRID)
    val density = s.guideDensityPct.pref(100)
    val profileId = s.activeProfileId.pref(-1L)
    val home = remember(profileId) { if (profileId < 0) flowOf(HomeConfig()) else s.homeConfig(profileId) }.pref(HomeConfig())
    // Exactly the rows the Home screen page lists: Trending is not one of the toggleable rows, and the
    // sports row this app dropped still sits in an older config's order. Hidden rows still count —
    // they are on that page, switched off — so the badge and the page can never disagree.
    val shownRows = home.settingsRows.count { it != HomeRow.LIVE_SPORTS }
    val remember = listOf(
        s.rememberCategoryLive.pref(true), s.rememberCategoryMovies.pref(true), s.rememberCategorySeries.pref(true),
        s.rememberLastLive.pref(false), s.rememberLastMovies.pref(false), s.rememberLastSeries.pref(false),
    )
    val sep = stringResource(R.string.content_epg_bits_separator)
    val liveViews = SettingsRepository.LiveView.entries
    val vodViews = SettingsRepository.VodViewMode.entries
    return listOf(
        SettingSection(
            null,
            listOf(
                SettingItem(
                    "navigation", MobileIcons.MoreHoriz, stringResource(R.string.settings_navigation), stringResource(R.string.settings_line_navigation_mobile),
                    SettingValue.Opens(
                        stringResource(if (labels) R.string.settings_nav_labels else R.string.settings_nav_icons_only) + sep +
                            stringResource(if (dynamic) R.string.settings_dynamic else R.string.settings_static),
                    ),
                    sheet = { dismiss -> NavigationSheet(vm = vm, onDismiss = dismiss) },
                ),
                SettingItem(
                    "live_opens_in", MobileIcons.LiveTv, stringResource(R.string.settings_live_opens_in), stringResource(R.string.settings_line_live_opens_in),
                    SettingValue.Segmented(
                        liveViews.map { stringResource(if (it == SettingsRepository.LiveView.LIST) R.string.settings_view_list else R.string.settings_seg_guide) },
                        liveViews.indexOf(liveView),
                    ) { i -> vm.edit { setLiveView(liveViews[i]) } },
                ),
                SettingItem(
                    "vod_view", MobileIcons.Movie, stringResource(R.string.settings_vod_view_mobile), stringResource(R.string.settings_line_vod_view_mobile),
                    SettingValue.Segmented(
                        vodViews.map { stringResource(if (it == SettingsRepository.VodViewMode.LIST) R.string.settings_view_list else R.string.settings_view_grid) },
                        vodViews.indexOf(vodView),
                    ) { i -> vm.edit { setVodViewMode(vodViews[i]) } },
                ),
                pageItem(
                    "tab_HOME", MobileIcons.Home, stringResource(R.string.settings_home_screen), stringResource(R.string.settings_line_home),
                    pluralStringResource(R.plurals.settings_home_rows_count, shownRows, shownRows), SettingsLeaf.HOME.route,
                ),
                pageItem(
                    "browsing_lists", MobileIcons.History, stringResource(R.string.settings_browsing_lists), stringResource(R.string.settings_line_browsing),
                    when {
                        remember.all { it } -> stringResource(R.string.settings_browsing_value_both)
                        remember.none { it } -> stringResource(R.string.common_off)
                        else -> stringResource(R.string.settings_live_latency_custom)
                    },
                    SettingsLeaf.BROWSING.route,
                ),
                pageItem(
                    "tab_CONTENT_MENUS", MobileIcons.MoreHoriz, stringResource(R.string.settings_content_menus_title),
                    stringResource(R.string.settings_line_content_menus), null, SettingsLeaf.CONTENT_MENUS.route,
                ),
                SettingItem(
                    "guide_size", MobileIcons.Guide, stringResource(R.string.settings_guide_size_mobile), stringResource(R.string.settings_line_guide_size_mobile),
                    SettingValue.Stepper(
                        stringResource(R.string.common_percent, density),
                        { vm.edit { setGuideDensityPct((density - 10).coerceAtLeast(GUIDE_DENSITY_MIN)) } },
                        { vm.edit { setGuideDensityPct((density + 10).coerceAtMost(GUIDE_DENSITY_MAX)) } },
                        density > GUIDE_DENSITY_MIN, density < GUIDE_DENSITY_MAX,
                    ),
                ),
            ),
        ),
    )
}

private const val GUIDE_DENSITY_MIN = 70
private const val GUIDE_DENSITY_MAX = 130

// ---------------------------------------------------------------------------------------------- Content

@Composable
private fun contentSections(vm: SettingsViewModel): List<SettingSection> {
    val mode = vm.settings.metadataMode.pref(MetadataMode.PROVIDER)
    val tier by vm.metadataTier.collectAsStateWithLifecycle()
    val osVm: OpenSubtitlesViewModel = koinViewModel()
    val os by osVm.state.collectAsStateWithLifecycle()
    var hidden = 0
    for (type in listOf(MediaType.LIVE, MediaType.MOVIE, MediaType.SERIES)) {
        val c = androidx.compose.runtime.key(type) { remember(type) { vm.customizations(type) }.pref(null) }
        hidden += (c?.hiddenCategories?.size ?: 0) + (c?.hiddenItems?.size ?: 0)
    }
    // Kids mode is a flag on the one profile this app keeps. Opening it costs nothing; closing it in
    // front of an app lock asks for that lock first, so the switch a child reaches for is not the way
    // around it.
    val active by vm.activeProfile.collectAsStateWithLifecycle()
    var kidsOff by remember { mutableStateOf(false) }
    val who = active
    if (kidsOff && who != null) {
        ProfilePinSheet(
            profileName = who.name,
            onSubmit = { pin ->
                vm.verifyPin(who, pin).also { if (it) { vm.setKidsMode(false); kidsOff = false } }
            },
            onDismiss = { kidsOff = false },
        )
    }
    return listOf(
        SettingSection(
            null,
            listOf(
                switchItem(
                    "kids_mode", MobileIcons.Person, stringResource(R.string.settings_profile_kids),
                    stringResource(R.string.mobile_kids_mode_description), active?.isKids == true,
                ) { on -> if (!on && active?.pinHash != null) kidsOff = true else vm.setKidsMode(on) },
                pageItem(
                    "tab_CUSTOMIZE", MobileIcons.Edit, stringResource(R.string.settings_customize), stringResource(R.string.settings_line_customize),
                    if (hidden > 0) pluralStringResource(R.plurals.settings_customize_hidden_count, hidden, hidden) else null,
                    SettingsLeaf.CUSTOMIZE.route,
                ),
                pageItem(
                    "tab_METADATA", MobileIcons.Info, stringResource(R.string.settings_metadata), stringResource(R.string.settings_line_metadata),
                    if (mode == MetadataMode.PROVIDER) stringResource(R.string.common_off) else stringResource(
                        when (tier) {
                            MetadataConfig.Tier.DEFAULT_WORKER -> R.string.settings_shared
                            MetadataConfig.Tier.OWN_KEY -> R.string.settings_tier_key
                            MetadataConfig.Tier.SELF_HOST -> R.string.settings_tier_self_host
                        },
                    ),
                    SettingsLeaf.METADATA.route,
                ),
                pageItem(
                    "tab_OPEN_SUBTITLES", MobileIcons.Subtitles, stringResource(R.string.settings_open_subtitles),
                    stringResource(R.string.settings_line_open_subtitles),
                    stringResource(if (os is OpenSubtitlesViewModel.UiState.SignedIn) R.string.settings_open_subtitles_connected else R.string.settings_signed_out),
                    SettingsLeaf.OPEN_SUBTITLES.route,
                ),
            ),
        ),
    )
}

// -------------------------------------------------------------------------------------------------- App

@Composable
private fun appSections(vm: SettingsViewModel): List<SettingSection> {
    val g = SettingsGroup.APP
    val s = vm.settings
    val localeStore: LocaleStore = koinInject()
    val tag = localeStore.currentTag.pref("")
    val appIcon = s.appIcon.pref(tv.own.owntv.core.brand.AppIcon.DEFAULT)
    val brandAccent = s.brandAccentTriangle.pref(true)
    val mode = vm.startupMode.pref(StartupMode.HOME)
    val channel = vm.startupChannel.pref(null)
    val updateOnStart = s.updateCheckOnStart.pref(true)
    val proxy = s.proxyConfig.pref(tv.own.owntv.core.network.ProxyConfig())
    val dns = s.dnsConfig.pref(tv.own.owntv.core.network.DnsConfig())
    val root = s.downloadRoot.pref("")
    val wifiOnly = s.downloadsWifiOnly.pref(false)
    val downloadsAllowed = s.activeDownloadsAllowed.pref(true)
    // The row says what the lock does: a PIN on this profile is asked for when the app opens.
    val appLockOn = vm.activeProfile.pref(null)?.pinHash != null
    val context = LocalContext.current
    val nav = LocalSettingsNav.current
    val on = stringResource(R.string.common_on)
    val off = stringResource(R.string.common_off)
    val dnsHost = dns.dohUrl.ifBlank { dns.host }.removePrefix("https://").substringBefore('/')
    val volume = remember(root) { volumeName(context, root.ifBlank { null }) }

    var channelSheet by remember { mutableStateOf(false) }
    if (channelSheet) {
        StartupChannelSheet(vm, onPick = { picked -> vm.setStartupChannel(picked); channelSheet = false }, onDismiss = { channelSheet = false })
    }
    return listOf(
        SettingSection(
            stringResource(R.string.settings_app_group),
            listOf(
                pageItem(
                    "tab_LANGUAGE", MobileIcons.Translate, stringResource(R.string.settings_language), stringResource(R.string.settings_language_description),
                    localeLabel(tag), SettingsLeaf.LANGUAGE.route,
                ),
                pageItem(
                    "tab_APP_LOCK", MobileIcons.Lock, stringResource(R.string.mobile_app_lock),
                    stringResource(R.string.mobile_app_lock_description),
                    stringResource(if (appLockOn) R.string.settings_profile_pin_lock else R.string.settings_profile_no_lock),
                    SettingsLeaf.APP_LOCK.route,
                ),
                SettingItem(
                    "app_icon", MobileIcons.Palette, stringResource(R.string.settings_app_icon), stringResource(R.string.settings_line_app_icon),
                    SettingValue.Opens(stringResource(appIcon.label)),
                    sheet = { dismiss -> AppIconSheet(chosen = appIcon, onPick = { vm.edit { setAppIcon(it) } }, onDismiss = dismiss) },
                ),
                switchItem(
                    "brand_accent", MobileIcons.Palette, stringResource(R.string.settings_brand_accent),
                    stringResource(R.string.settings_line_brand_accent), brandAccent,
                ) { vm.edit { setBrandAccentTriangle(it) } },
                choiceItem(
                    "app_startup", MobileIcons.Home, g, stringResource(R.string.settings_app_startup),
                    stringResource(R.string.settings_app_startup_description), stringResource(R.string.settings_help_startup),
                    StartupMode.entries.map { SettingsChoice(it, stringResource(it.startupLabel())) },
                    mode, StartupMode.HOME,
                    // "Specific channel" is only half an answer: the channel itself is the setting.
                    { picked -> if (picked == StartupMode.SPECIFIC_CHANNEL) channelSheet = true else vm.setStartupMode(picked) },
                    valueText = if (mode == StartupMode.SPECIFIC_CHANNEL && channel != null) channel.name else stringResource(mode.startupLabel()),
                ),
                SettingItem(
                    "check_updates", MobileIcons.Refresh, stringResource(R.string.settings_check_updates), stringResource(R.string.settings_line_check_updates),
                    SettingValue.Action(stringResource(R.string.settings_check_now)),
                    sheet = { dismiss -> UpdateSheet(onDismiss = dismiss, checkOnOpen = true) },
                ),
                switchItem(
                    "update_startup", MobileIcons.Refresh, stringResource(R.string.settings_update_startup),
                    stringResource(R.string.settings_line_when_app_opens), updateOnStart,
                ) { vm.edit { setUpdateCheckOnStart(it) } },
            ),
        ),
        SettingSection(
            stringResource(R.string.settings_group_network),
            listOf(
                pageItem(
                    "tab_NETWORK", MobileIcons.Translate, stringResource(R.string.common_proxy), stringResource(R.string.settings_line_proxy),
                    if (proxy.usable) "${proxy.host}:${proxy.port}" else off, SettingsLeaf.PROXY.route,
                ),
                pageItem(
                    "tab_DNS", MobileIcons.Translate, stringResource(R.string.settings_dns), stringResource(R.string.settings_line_dns),
                    if (dns.enabled && dnsHost.isNotBlank()) dnsHost else if (dns.enabled) on else off, SettingsLeaf.DNS.route,
                ),
            ),
        ),
        SettingSection(
            stringResource(R.string.common_nav_downloads),
            listOf(
                // Moved here when the profile editor went: the permission is still stored per profile,
                // the switch is just no longer sitting next to the profile it belongs to.
                switchItem(
                    "downloads_allowed", MobileIcons.Download, stringResource(R.string.profiles_allow_downloads),
                    stringResource(R.string.profiles_allow_downloads_description), downloadsAllowed,
                ) { vm.setDownloadsAllowed(it) },
                // The folder is chosen on Downloads (its folder tool), where it is shown; this row goes there.
                SettingItem(
                    "download_folder", MobileIcons.Folder, stringResource(R.string.settings_download_folder),
                    stringResource(R.string.settings_line_download_folder_mobile), SettingValue.Opens(volume),
                    onClick = { nav(MobileDestination.DOWNLOADS.route) },
                ),
                switchItem(
                    "downloads_wifi_only", MobileIcons.Download, stringResource(R.string.settings_downloads_wifi_only),
                    stringResource(R.string.settings_downloads_wifi_only_description), wifiOnly,
                ) { vm.edit { setDownloadsWifiOnly(it) } },
            ),
        ),
    )
}

/** A switch from the pinnable list ([QUICK_TOGGLES]), under its own key. */
@Composable
internal fun quickSwitch(vm: SettingsViewModel, key: String, icon: androidx.compose.ui.graphics.vector.ImageVector, titleRes: Int, lineRes: Int?): SettingItem {
    val toggle = quickToggle(key)
    val on = toggle.flow(vm.settings).pref(toggle.default)
    return switchItem(key, icon, stringResource(titleRes), lineRes?.let { stringResource(it) }, on) { next -> vm.edit { toggle.set(this, next) } }
}

/** A search box over the profile's live channels — a playlist is far too long to scroll blind. */
@Composable
private fun StartupChannelSheet(vm: SettingsViewModel, onPick: (ChannelEntity) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    // Debounced like the guide's own search; collected in an effect, not in composition.
    var debounced by remember { mutableStateOf(query) }
    LaunchedEffect(Unit) { snapshotFlow { query }.debounce(300L).collect { debounced = it } }
    LaunchedEffect(debounced) { results = vm.searchChannels(debounced) }
    MobileBottomSheet(onDismissRequest = onDismiss) {
        StageSheetHeader(stringResource(R.string.settings_startup_specific_channel), eyebrow = stringResource(R.string.settings_app_startup))
        StageSearchField(query, { query = it }, stringResource(R.string.common_search), Modifier.padding(horizontal = 6.dp))
        results.forEach { channel -> StageMenuItem(channel.name, onClick = { onPick(channel) }, icon = MobileIcons.LiveTv) }
    }
}

// ---------------------------------------------------------------------------------------------- helpers

/** A radio row in a sheet that answers more than one question (Catch-up): the choice sheet's own row (#4). */
@Composable
internal fun ChoiceRow(label: String, on: Boolean, recommended: Boolean = false, onClick: () -> Unit) =
    ChoiceItem(label = label, description = null, on = on, recommended = recommended, onClick = onClick)

/** A sheet's list, capped and scrolling (a long list otherwise ran past the screen, #6). */
@Composable
internal fun Modifier.sheetScroll(): Modifier =
    heightIn(max = tv.own.owntv.mobile.ui.components.sheetListHeight()).verticalScroll(rememberScrollState())

/** "UTC+02:00" — a clock offset, not a translated phrase. */
internal fun utcOffsetLabel(minutes: Int): String {
    if (minutes == 0) return "UTC"
    val sign = if (minutes < 0) "-" else "+"
    val abs = kotlin.math.abs(minutes)
    return "UTC$sign%02d:%02d".format(java.util.Locale.ROOT, abs / 60, abs % 60)
}

internal fun reminderModeLabel(mode: SettingsRepository.ReminderMode): Int = when (mode) {
    SettingsRepository.ReminderMode.ASK -> R.string.settings_reminder_ask
    SettingsRepository.ReminderMode.SWITCH -> R.string.settings_reminder_switch
    SettingsRepository.ReminderMode.NOTIFY -> R.string.settings_reminder_notify
}

private fun ThemeMode.themeLabel(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.DARK -> R.string.settings_theme_dark
    ThemeMode.LIGHT -> R.string.settings_theme_light
}

internal fun AppFontFamily.labelRes(): Int = when (this) {
    AppFontFamily.LORA -> R.string.settings_font_lora
    AppFontFamily.SYSTEM_SANS -> R.string.settings_font_system_sans
    AppFontFamily.MONOSPACE -> R.string.settings_font_monospace
    AppFontFamily.PLAYFAIR_DISPLAY -> R.string.settings_font_playfair_display
    AppFontFamily.DANCING_SCRIPT -> R.string.settings_font_dancing_script
    AppFontFamily.POPPINS -> R.string.settings_font_poppins
    AppFontFamily.PLUS_JAKARTA_SANS -> R.string.settings_font_plus_jakarta_sans
}

@Composable
private fun localeLabel(tag: String): String =
    if (tag.isBlank()) stringResource(R.string.settings_language_device)
    else AppLocales.rows.firstOrNull { it.languageTag == tag }?.endonym ?: tag

private fun StartupMode.startupLabel() = when (this) {
    StartupMode.HOME -> R.string.settings_startup_home
    StartupMode.LAST_CHANNEL -> R.string.settings_startup_last_channel
    StartupMode.LAST_CHANNEL_GUIDE -> R.string.settings_startup_last_channel_guide
    StartupMode.LAST_CHANNEL_LIVE -> R.string.settings_startup_last_channel_live
    StartupMode.FAVORITES -> R.string.settings_startup_favorites
    StartupMode.SPECIFIC_CHANNEL -> R.string.settings_startup_specific_channel
}

/** "3 playlists" for the per-playlist rows, or Off when every playlist follows the global. */
@Composable
internal fun overrideCountLabel(count: Int): String =
    if (count == 0) stringResource(R.string.common_off) else pluralStringResource(R.plurals.settings_live_preroll_overrides, count, count)
