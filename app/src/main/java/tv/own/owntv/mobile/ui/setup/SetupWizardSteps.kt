package tv.own.owntv.mobile.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.core.brand.AppIcon
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.mobile.i18n.AppLocales
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.theme.FontCustomization
import tv.own.owntv.core.theme.UiFontScale
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.BrandMark
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.screens.settings.AppIconSheet
import tv.own.owntv.mobile.ui.screens.settings.RemoteSourceSheet
import tv.own.owntv.mobile.ui.screens.settings.SettingsChoice
import tv.own.owntv.mobile.ui.screens.settings.StageChoiceSheet
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageChevron
import tv.own.owntv.mobile.ui.stage.StageRowValue
import tv.own.owntv.mobile.ui.stage.StageSettingRow
import tv.own.owntv.mobile.ui.stage.StageStepper
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * The first-run wizard's own steps, the TV's sequence and words, each in a [WizardFrame]:
 * Welcome → Display → Before you start → Set up OwnTV → Who's watching? → Add a playlist. The TV's chooser
 * for a backup has no step here: a phone has its own file picker, so Restore goes straight to it.
 */

/** The language as a row (a tap opens the list), then Get started. */
@Composable
fun WelcomeStep(onNext: () -> Unit) {
    WizardFrame(
        step = WizardStep.LANGUAGE,
        title = stringResource(R.string.setup_welcome_title),
        sub = stringResource(R.string.setup_welcome_sub),
        next = stringResource(R.string.setup_get_started),
        onNext = onNext,
        onBack = null,
    ) {
        // Here and not only in Settings: every screen after this one is text, and a user who cannot read the
        // disclaimer cannot agree to it.
        FirstRunLanguageRow()
    }
}

/**
 * How big the text is, before the first screen that is mostly words: Font size as a stepper, written
 * straight through — `MobileTheme` scales the whole step under the finger — the app icon, and the
 * sample row to judge them by. The interface zoom is no longer a user setting on this app, so the
 * wizard does not offer it either.
 */
@Composable
fun DisplaySizeStep(onNext: () -> Unit, onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    val fonts by settings.fontCustomization.collectAsStateWithLifecycle(FontCustomization())
    val appIcon by settings.appIcon.collectAsStateWithLifecycle(AppIcon.DEFAULT)
    var pickIcon by remember { mutableStateOf(false) }
    val resetSize: () -> Unit = {
        scope.launch {
            settings.setFontCustomization(fonts.copy(sizePercent = UiFontScale.DEFAULT))
        }
    }
    val setFont: (Int) -> Unit = { raw -> scope.launch { settings.setFontCustomization(fonts.copy(sizePercent = UiFontScale.clamp(raw))) } }
    WizardFrame(
        step = WizardStep.DISPLAY,
        title = stringResource(R.string.setup_display_size_title),
        sub = stringResource(R.string.setup_display_sub_mobile),
        next = stringResource(R.string.setup_continue),
        onNext = onNext,
        onBack = onBack,
        extraLeft = listOf(stringResource(R.string.settings_reset) to resetSize),
    ) {
        StageSettingRow(title = stringResource(R.string.settings_font_size), icon = MobileIcons.Edit, subtitle = stringResource(R.string.setup_line_font), role = null) {
            StageStepper(
                stringResource(R.string.settings_surface_transparency, fonts.sizePercent),
                onMinus = { setFont(fonts.sizePercent - UiFontScale.STEP) },
                onPlus = { setFont(fonts.sizePercent + UiFontScale.STEP) },
                minusEnabled = fonts.sizePercent > UiFontScale.MIN,
                plusEnabled = fonts.sizePercent < UiFontScale.MAX,
            )
        }
        StageSettingRow(
            title = stringResource(R.string.settings_app_icon),
            icon = MobileIcons.Palette,
            subtitle = stringResource(R.string.setup_line_app_icon_mobile),
            onClick = { pickIcon = true },
        ) { AppIconSwatches(appIcon) }
        // The sample: a row's own type at the size it will really be.
        val palette = LocalStagePalette.current
        Row(
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(palette.ink.copy(alpha = 0.045f))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_panel_width_preview).uppercase(), style = stageText(10.5f, 800, letterSpacing = androidx.compose.ui.unit.TextUnit(0.12f, androidx.compose.ui.unit.TextUnitType.Em)), color = palette.dim)
            Text(stringResource(R.string.home_row_now_trending), style = stageText(13f, 700), color = palette.text, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(stringResource(R.string.common_on), style = stageText(12f, 700), color = stageAccent.accent)
        }
    }

    // No restart question: nothing is on the home screen yet, and the pick applies once the app is in the background.
    if (pickIcon) {
        AppIconSheet(chosen = appIcon, onPick = { scope.launch { settings.setAppIcon(it) } }, onDismiss = { pickIcon = false }, askRestart = false)
    }
}

/** The App icon row's value: five icons from the chosen one's set, the chosen one ringed. */
@Composable
private fun AppIconSwatches(chosen: AppIcon) {
    val accent = stageAccent.accent
    val shown = (AppIcon.entries.take(5) + chosen).distinct().takeLast(5)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        shown.forEach { icon ->
            Box(
                Modifier
                    .size(18.dp)
                    .then(if (icon == chosen) Modifier.border(1.5.dp, accent, RoundedCornerShape(6.dp)) else Modifier),
                contentAlignment = Alignment.Center,
            ) { BrandMark(icon, 16.dp, Modifier.clip(RoundedCornerShape(5.dp)), followAccent = false) }
        }
    }
}

/** What the app is not — it ships no channels, the user brings their own sources. */
@Composable
fun DisclaimerStep(onAgree: () -> Unit, onBack: () -> Unit) {
    WizardFrame(
        step = WizardStep.DISPLAY,
        title = stringResource(R.string.setup_before_you_start),
        sub = stringResource(R.string.setup_disclaimer),
        next = stringResource(R.string.setup_i_understand),
        onNext = onAgree,
        onBack = onBack,
    )
}

/**
 * Start fresh, or bring everything back. The three ways content arrives on an app that keeps one
 * profile: a playlist typed or sent in, a backup file, another phone. The profile is not asked for —
 * whichever of these lands first is the one it is made for.
 */
@Composable
fun SetupChoiceStep(onStartFresh: () -> Unit, onRestore: () -> Unit, onSyncDevice: () -> Unit, onBack: () -> Unit) {
    WizardFrame(
        step = WizardStep.CONTENT,
        title = stringResource(R.string.setup_set_up_owntv),
        sub = stringResource(R.string.setup_setup_choice_description),
        next = null,
        onNext = {},
        onBack = onBack,
    ) {
        WizardCard(MobileIcons.Person, stringResource(R.string.mobile_start_fresh), stringResource(R.string.mobile_start_fresh_description), onStartFresh)
        // The phone restores from a file it picks itself; Remote restore lives on the Backup page.
        WizardCard(MobileIcons.Download, stringResource(R.string.setup_restore_a_backup), stringResource(R.string.mobile_restore_backup_description), onRestore)
        WizardCard(MobileIcons.Phone, stringResource(R.string.setup_sync_device), stringResource(R.string.setup_sync_device_description), onSyncDevice)
    }
}

/**
 * Add a playlist: Remote first (a phone keyboard is slow for addresses too), Type it here, Import,
 * then Skip for now.
 */
@Composable
fun AddContentStep(
    vm: SetupViewModel,
    onRemoteReceived: () -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onSkip: () -> Unit,
    onBack: (() -> Unit)?,
) {
    var remote by remember { mutableStateOf(false) }
    WizardFrame(
        step = WizardStep.PLAYLIST,
        title = stringResource(R.string.setup_add_playlist),
        sub = stringResource(R.string.setup_add_playlist_description),
        next = null,
        onNext = {},
        onBack = onBack,
    ) {
        WizardCard(MobileIcons.Translate, stringResource(R.string.setup_from_phone), stringResource(R.string.setup_phone_card_line)) {
            vm.consumeRemotePayload()
            vm.startRemote()
            remote = true
        }
        WizardCard(MobileIcons.Edit, stringResource(R.string.settings_add_type_here), stringResource(R.string.settings_line_add_type_here_mobile)) {
            vm.consumeRemotePayload()
            onNew()
        }
        WizardCard(MobileIcons.Folder, stringResource(R.string.setup_import), stringResource(R.string.setup_restore_backup_file), onImport)
        WizardLink(stringResource(R.string.setup_skip_for_now), onSkip)
    }
    if (remote) RemoteSourceSheet(vm, onReceived = { remote = false; onRemoteReceived() }, onDismiss = { remote = false })
}

/** The language row: how many languages ship, under it; the chosen one on the right; a tap opens the list. */
@Composable
private fun FirstRunLanguageRow(localeStore: LocaleStore = koinInject()) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val stored by localeStore.currentTag.collectAsStateWithLifecycle()
    // Normalised, so the row names the language the wizard is actually drawn in.
    val current = AppLocales.normalize(stored)
    val systemLabel = stringResource(R.string.settings_language_system_default)
    val rows = remember { AppLocales.rows.sortedBy { it.englishName } }
    val label = rows.firstOrNull { it.languageTag == current }?.endonym ?: systemLabel
    StageSettingRow(
        title = stringResource(R.string.settings_language),
        icon = MobileIcons.Translate,
        subtitle = pluralStringResource(R.plurals.setup_languages_all, rows.size, rows.size),
        onClick = { open = true },
    ) { StageRowValue(label, StageChevron.DOWN) }
    if (open) {
        val choices = listOf(SettingsChoice("", systemLabel)) + rows.map { SettingsChoice(it.languageTag, it.endonym, it.englishName) }
        StageChoiceSheet(
            eyebrow = null,
            title = stringResource(R.string.settings_language),
            text = null,
            choices = choices,
            selected = current.takeIf { tag -> rows.any { it.languageTag == tag } }.orEmpty(),
            onSelect = { tag -> scope.launch { runCatching { localeStore.set(tag) } } },
            onDismiss = { open = false },
        )
    }
}

