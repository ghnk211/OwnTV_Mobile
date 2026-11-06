package tv.own.owntv.mobile.ui.setup

import android.icu.text.ListFormatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.backup.BackupManager
import tv.own.owntv.core.setup.SourceImporter
import tv.own.owntv.core.setup.displayText
import tv.own.owntv.core.sync.ImportStage
import tv.own.owntv.core.sync.detailText
import tv.own.owntv.core.sync.importProgressDisplay
import tv.own.owntv.core.sync.primaryText
import tv.own.owntv.core.sync.progressCounts
import tv.own.owntv.core.sync.remainderText
import tv.own.owntv.core.sync.summaryText
import tv.own.owntv.core.sync.warningText
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.screens.home.formatCount
import tv.own.owntv.mobile.ui.screens.settings.SetupLocalSyncStep
import tv.own.owntv.mobile.ui.screens.settings.descriptionRes
import tv.own.owntv.mobile.ui.screens.settings.labelRes
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageChevron
import tv.own.owntv.mobile.ui.stage.StageGroupLabel
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StageRowValue
import tv.own.owntv.mobile.ui.stage.StageSearchField
import tv.own.owntv.mobile.ui.stage.StageSettingRow
import tv.own.owntv.mobile.ui.stage.StageSheetHeader
import tv.own.owntv.mobile.ui.stage.StageSwitch
import tv.own.owntv.mobile.ui.stage.stageBackground
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * Where an "Add a source" from Settings enters the flow (Add a source picks the way there itself): the form ("Type it
 * here", pre-filled when Remote sent one) or the backup picker.
 */
enum class SetupEntry { ADD_CONTENT, FORM, IMPORT }

/** What the finished wizard leaves for the shell (the TV's `PendingShellRequest`): the last step's Add a TV guide. */
object SetupHandoff {
    var addEpg by mutableStateOf(false)
        private set

    fun requestAddEpg() { addEpg = true }

    /** Read once by EPG sources, which then opens its Add. */
    fun takeAddEpg(): Boolean = addEpg.also { addEpg = false }
}

private enum class Step {
    WELCOME, DISPLAY_SIZE, DISCLAIMER, CHOICE, SYNC_DEVICE, ADD_CONTENT, FORM, IMPORTING, RESTORE
}

/**
 * Getting content onto the phone: type a playlist in, or bring everything back from a backup.
 *
 * **The first run is the television's wizard**, in its order and with its words — Welcome, Display,
 * the disclaimer, start-fresh-or-restore, then where the content comes from, Importing and All set.
 * Opening the same flow later from Settings' Add a source starts at the way that page chose instead.
 *
 * There is no "who is watching" step any more. One profile per app means the wizard creates it
 * itself — the importer's, or a default when the user skips — and the profile step, its avatar strip
 * and its optional PIN left with it.
 */
@Composable
fun SetupFlow(
    onDone: (profileId: Long?) -> Unit,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    entry: SetupEntry = SetupEntry.ADD_CONTENT,
) {
    val vm: SetupViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    // A backup this app will not restore, and why — shown rather than swallowed (§5.8.3).
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    // No way to cancel means there is nothing behind this flow, which is exactly what a first run is.
    val firstRun = onCancel == null
    val entryStep = when {
        firstRun -> Step.WELCOME
        entry == SetupEntry.FORM -> Step.FORM
        entry == SetupEntry.IMPORT -> Step.RESTORE
        else -> Step.ADD_CONTENT
    }
    var step by rememberSaveable(firstRun) { mutableStateOf(entryStep) }
    // A submission from Remote, read once by the form it pre-fills.
    val remotePayload by vm.remotePayload.collectAsStateWithLifecycle()
    val remoteInitial = remember(remotePayload) { remotePayload?.toFormValues() }
    // Where "Try again" and "Cancel" return to: the form for a new playlist, the list for a shared one.
    var importOrigin by rememberSaveable { mutableStateOf(Step.FORM) }
    // The playlist being imported, for "Importing <name>"; null when linking existing playlists.
    var importName by rememberSaveable { mutableStateOf<String?>(null) }
    // Where Back from the backup picker returns to — the first-run choice, or Add content.
    var backupOrigin by rememberSaveable { mutableStateOf(Step.ADD_CONTENT) }
    // Entered straight on Import: the picker opens at once, as the Import card promised.
    var openedPicker by rememberSaveable { mutableStateOf(false) }

    // The picked file, and what the user chose to take out of it. Both `null` until each is
    // answered, which is what drives the sheet below: a file with no choice yet is the question.
    var restoreFile by remember { mutableStateOf<java.io.File?>(null) }
    var restoreSections by remember { mutableStateOf<Set<BackupManager.Section>?>(null) }
    // "Hardware settings from the other device" — answered with the sections, carried like them.
    var restoreDeviceSettings by remember { mutableStateOf(false) }

    // The name field is optional, and the television has always filled a blank one in rather than
    // storing an empty string. Without this the playlist has no name anywhere it is shown.
    val defaultIptvName = stringResource(R.string.setup_default_iptv)
    val defaultPlaylistName = stringResource(R.string.setup_name_default_playlist)
    val defaultPortalName = stringResource(R.string.setup_default_portal)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Picking the file does not start the restore — it asks what to take out of it first.
    val pickBackup = rememberBackupFilePicker { uri ->
        scope.launch {
            copyPickedFile(context, uri, context.cacheDir)?.let {
                restoreSections = null
                restoreFile = it
            }
        }
    }

    // Back walks the wizard backwards rather than out of the app. From the very first step there is
    // nowhere to go unless the caller gave us somewhere, and an install with no playlist has nothing
    // behind it at all.
    val atStart = step == entryStep
    // Back from the step this flow was entered on leaves it, back to Settings' Add a source.
    val leave: () -> Unit = { vm.reset(); onCancel?.invoke() }
    val backFromForm: () -> Unit = { if (entryStep == Step.FORM) leave() else { vm.reset(); step = Step.ADD_CONTENT } }
    val backFromRestore: () -> Unit = {
        vm.reset(); restoreFile = null; restoreSections = null
        if (entryStep == Step.RESTORE) leave() else step = backupOrigin
    }
    BackHandler(enabled = !atStart || onCancel != null) {
        when (step) {
            Step.WELCOME -> onCancel?.invoke()
            Step.DISPLAY_SIZE -> step = Step.WELCOME
            Step.DISCLAIMER -> step = Step.DISPLAY_SIZE
            Step.CHOICE -> step = Step.DISCLAIMER
            // The step owns its own Back: it has sheets to dismiss first, and once the data has
            // landed there is nothing to go back to.
            Step.SYNC_DEVICE -> Unit
            // Not `onCancel?.invoke()`: `firstRun` IS `onCancel == null`, so this branch is only
            // reached when there is one, and the compiler knows it.
            Step.ADD_CONTENT -> if (firstRun) step = Step.CHOICE else onCancel()
            Step.FORM -> backFromForm()
            Step.RESTORE -> backFromRestore()
            Step.IMPORTING -> Unit // the buttons on that screen decide; a stray swipe must not abandon a sync
        }
    }

    LaunchedEffect(Unit) {
        if (entryStep == Step.RESTORE && !openedPicker) { openedPicker = true; pickBackup() }
    }
    // Read once: a later "Type it here" must not be filled from an old submission.
    LaunchedEffect(step) { if (step != Step.FORM && remotePayload != null) vm.consumeRemotePayload() }

    Box(modifier = modifier.fillMaxSize()) {
        when (step) {
            Step.WELCOME -> WelcomeStep(onNext = { step = Step.DISPLAY_SIZE })
            // Before the disclaimer, which is the first screen that is mostly words: if the text is
            // too small to read, that is the screen it first hurts on.
            Step.DISPLAY_SIZE -> DisplaySizeStep(onNext = { step = Step.DISCLAIMER }, onBack = { step = Step.WELCOME })
            Step.DISCLAIMER -> DisclaimerStep(onAgree = { step = Step.CHOICE }, onBack = { step = Step.DISPLAY_SIZE })
            Step.CHOICE -> SetupChoiceStep(
                onStartFresh = { step = Step.ADD_CONTENT },
                onRestore = { backupOrigin = Step.CHOICE; step = Step.RESTORE; pickBackup() },
                onSyncDevice = { step = Step.SYNC_DEVICE },
                onBack = { step = Step.DISCLAIMER },
            )
            // A sync brings profiles with it, which this app no longer keeps: the step says so and
            // refuses rather than inventing a second ending (§5.10).
            Step.SYNC_DEVICE -> SetupLocalSyncStep(onRestored = { vm.finish(onDone) }, onBack = { step = Step.CHOICE })
            Step.ADD_CONTENT -> AddContentStep(
                vm = vm,
                onRemoteReceived = { importOrigin = Step.FORM; step = Step.FORM },
                onNew = { importOrigin = Step.FORM; step = Step.FORM },
                onImport = { backupOrigin = Step.ADD_CONTENT; step = Step.RESTORE; pickBackup() },
                onSkip = { vm.finish(onDone) },
                onBack = if (firstRun) ({ step = Step.CHOICE }) else onCancel,
            )
            Step.FORM -> Column(Modifier.fillMaxSize().then(if (firstRun) Modifier.stageBackground() else Modifier).statusBarsPadding()) {
                // "Type it here", from the wizard or Settings' Add a source: the page's own title and the way back.
                FormHeader(firstRun = firstRun, onBack = backFromForm)
                AddSourceForm(
                    // Remote's submission fills the form; the user still presses Start import.
                    initial = remoteInitial,
                    onStartXtream = { name, server, user, pass, ua, referer, refresh, live, movies, series, hls ->
                        importName = name.ifBlank { defaultIptvName }
                        vm.startXtream(importName!!, server, user, pass, ua, referer, refresh, live, movies, series, hls)
                        step = Step.IMPORTING
                    },
                    onStartM3u = { name, url, ua, referer, refresh ->
                        importName = name.ifBlank { defaultPlaylistName }
                        vm.startM3u(importName!!, url, ua, referer, refresh)
                        step = Step.IMPORTING
                    },
                    onStartStalker = { name, portal, mac, serial, dev1, dev2, sig, ua, referer, refresh, live, movies, series, login, password ->
                        importName = name.ifBlank { defaultPortalName }
                        vm.startStalker(importName!!, portal, mac, serial, dev1, dev2, sig, ua, referer, refresh, live, movies, series, login, password)
                        step = Step.IMPORTING
                    },
                )
            }
            Step.IMPORTING -> ImportProgress(
                state = state,
                progress = progress,
                name = importName,
                onContinue = { vm.finish(onDone) },
                onAddGuide = { SetupHandoff.requestAddEpg(); vm.finish(onDone) },
                onRunInBackground = { vm.continueInBackground(onDone) },
                onRetry = { vm.reset(); step = importOrigin },
                onCancel = { vm.cancelImport(); step = importOrigin },
            )
            Step.RESTORE -> {
                RestoreBackup(
                    state = state,
                    wizardStep = if (backupOrigin == Step.CHOICE) WizardStep.CONTENT else WizardStep.PLAYLIST,
                    // The same choice the sheet took, carried across the password question: a sealed
                    // file is chosen from before it can be opened, so the answer has to outlive it.
                    onPassword = { file, password ->
                        vm.restoreWithPassword(file, password, restoreSections ?: allSections, restoreDeviceSettings)
                    },
                    onContinue = { vm.finish(onDone) },
                    onPickAgain = { vm.reset(); restoreFile = null; restoreSections = null; pickBackup() },
                    onBack = backFromRestore,
                )
                // A file is picked and nothing has been asked of it yet — so ask, over the top.
                val picked = restoreFile
                if (picked != null && restoreSections == null) {
                    RestoreSectionsSheet(
                        onConfirm = { sections, deviceSettings ->
                            restoreSections = sections
                            restoreDeviceSettings = deviceSettings
                            vm.importBackup(picked, sections, deviceSettings)
                        },
                        onDismiss = backFromRestore,
                    )
                }
            }
        }
    }

    // Setup cannot show what a backup holds before restoring it, nor undo a rename half-way through,
    // so a backup it cannot take safely is refused here instead — in one sentence that says why, with
    // the file left picked so it can be tried again from Settings, where the questions can be asked.
    blocked?.let { reason ->
        MobileBottomSheet(onDismissRequest = vm::dismissBlocked, title = stringResource(R.string.settings_backup_restore_title)) {
            StageSheetHeader(stringResource(R.string.settings_backup_restore_title))
            Text(
                text = stringResource(reason),
                style = stageText(13f, 500),
                color = LocalStagePalette.current.muted,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            StageMenuItem(
                stringResource(R.string.common_ok),
                onClick = vm::dismissBlocked,
                icon = MobileIcons.Check,
                filled = true,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/**
 * "Importing <name>" with the sweeping bar and a row for each part of the catalogue as it arrives, Cancel
 * and Run in background; then "All set!" with what arrived and Add a TV guide; or the failure with Back and Try
 * again. The rows say what is known — counts so far, and Done once a part has finished — never a total.
 */
@Composable
private fun ImportProgress(
    state: SourceImporter.ImportState,
    progress: ImportStage?,
    name: String?,
    onContinue: () -> Unit,
    onAddGuide: () -> Unit,
    onRunInBackground: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val resources = LocalContext.current.resources
    when (state) {
        is SourceImporter.ImportState.Success -> WizardFrame(
            step = WizardStep.READY,
            title = stringResource(R.string.setup_all_set),
            sub = listOfNotNull(
                state.counts?.summaryText(resources, includeEpg = true),
                state.warnings.warningText(resources),
                state.remainder.remainderText(resources),
            ).joinToString("\n"),
            next = stringResource(R.string.setup_continue),
            onNext = onContinue,
            onBack = null,
        ) {
            StageSettingRow(
                title = stringResource(R.string.setup_add_guide),
                icon = MobileIcons.Guide,
                subtitle = stringResource(R.string.setup_add_guide_line),
                onClick = onAddGuide,
            ) { StageRowValue("", StageChevron.FORWARD) }
        }
        is SourceImporter.ImportState.Failed -> WizardFrame(
            step = WizardStep.PLAYLIST,
            title = stringResource(R.string.setup_import_failed),
            sub = state.failure.displayText(resources),
            next = stringResource(R.string.setup_try_again_caps),
            onNext = onRetry,
            onBack = onCancel,
        )
        else -> WizardFrame(
            step = WizardStep.PLAYLIST,
            title = name?.let { stringResource(R.string.setup_importing_title, it) } ?: stringResource(R.string.setup_importing_catalog),
            sub = stringResource(R.string.setup_watching_during_import),
            // A full catalogue takes minutes, and nobody should have to watch it: leaving is the first-class
            // action. Cancel throws the import away, so it says so rather than "Back".
            next = stringResource(R.string.setup_run_in_background),
            onNext = onRunInBackground,
            onBack = onCancel,
            backLabel = stringResource(R.string.common_cancel),
        ) {
            WizardSweepBar()
            ImportRows(progress)
        }
    }
}

/** One row per part being fetched: its count so far, and Done once it has finished; or what is happening first. */
@Composable
private fun ImportRows(progress: ImportStage?) {
    val resources = LocalContext.current.resources
    val counts = progress?.progressCounts()
    if (counts == null || !counts.hasItems) {
        val display = progress?.importProgressDisplay()
        val title = display?.primaryText(resources) ?: stringResource(R.string.setup_preparing_catalog)
        StageSettingRow(
            title = title,
            icon = MobileIcons.Download,
            // While connecting core's two lines are the same sentence; say it once.
            subtitle = display?.detailText(resources)?.takeIf { it != title },
            role = null,
        )
        return
    }
    val parts = listOf(
        Triple(R.string.setup_live_tv, MobileIcons.LiveTv, Triple(counts.live, counts.liveActive, R.plurals.sync_count_channels)),
        Triple(R.string.setup_movies, MobileIcons.Movie, Triple(counts.movies, counts.moviesActive, R.plurals.sync_count_movies)),
        Triple(R.string.setup_series, MobileIcons.Series, Triple(counts.series, counts.seriesActive, R.plurals.sync_count_series)),
    )
    parts.forEach { (title, icon, part) ->
        val (count, active, plural) = part
        if (count == 0 && !active) return@forEach
        StageSettingRow(
            title = stringResource(title),
            icon = icon,
            subtitle = pluralStringResource(plural, count, formatCount(count)),
            current = active,
            role = null,
        ) {
            if (!active) StageRowValue(stringResource(R.string.common_done))
        }
    }
}

private val allSections: Set<BackupManager.Section> get() = BackupManager.Section.entries.toSet()

/**
 * What to take out of the backup file, before any of it is applied — every section, not only the ones the file
 * holds: a sealed file says nothing about its contents until the password arrives, and asking for the password
 * before the user has said what they want would be the wrong order. Ticking a section the file does not carry
 * simply restores nothing for it.
 */
@Composable
private fun RestoreSectionsSheet(
    onConfirm: (Set<BackupManager.Section>, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var sections by remember { mutableStateOf(allSections) }
    // Unticked: a backup of this very phone gets its hardware settings back regardless; this is about another device's.
    var deviceSettings by remember { mutableStateOf(false) }
    MobileBottomSheet(onDismissRequest = onDismiss) {
        StageSheetHeader(stringResource(R.string.settings_backup_restore_title))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            StageGroupLabel(stringResource(R.string.settings_backup_what_restore))
            BackupManager.Section.entries.forEach { section ->
                val on = section in sections
                StageSettingRow(
                    title = stringResource(section.labelRes()),
                    subtitle = stringResource(section.descriptionRes()),
                    onClick = { sections = if (on) sections - section else sections + section },
                    role = Role.Switch,
                    minHeight = 48.dp,
                ) { StageSwitch(on) }
            }
            if (BackupManager.Section.SETTINGS in sections) {
                StageSettingRow(
                    title = stringResource(R.string.settings_backup_device_settings),
                    subtitle = stringResource(R.string.settings_backup_device_settings_desc),
                    onClick = { deviceSettings = !deviceSettings },
                    role = Role.Switch,
                    minHeight = 48.dp,
                ) { StageSwitch(deviceSettings) }
            }
        }
        // A first run with no playlists restored has nothing to play (the TV's same warning).
        if (sections.isNotEmpty() && BackupManager.Section.SOURCES !in sections) {
            Text(
                stringResource(R.string.settings_backup_no_sources_warning),
                style = stageText(12.5f, 600),
                color = LocalStagePalette.current.warn,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        // Confirm does not dismiss: answering is what closes the sheet, and dismissing means leaving.
        val enabled = sections.isNotEmpty()
        StageMenuItem(
            stringResource(R.string.settings_backup_restore_action),
            onClick = { onConfirm(sections, deviceSettings) },
            icon = MobileIcons.Check,
            filled = enabled,
            enabled = enabled,
            modifier = Modifier.padding(top = 6.dp),
        )
        StageMenuItem(stringResource(R.string.common_cancel), onClick = onDismiss, icon = MobileIcons.Close)
    }
}

/** The restore, once a file has been picked: its password if it needs one, then the result. */
@Composable
private fun RestoreBackup(
    state: SourceImporter.ImportState,
    wizardStep: WizardStep,
    onPassword: (java.io.File, String?) -> Unit,
    onContinue: () -> Unit,
    onPickAgain: () -> Unit,
    onBack: () -> Unit,
) {
    val resources = LocalContext.current.resources
    when (state) {
        is SourceImporter.ImportState.NeedPassword -> {
            var password by remember(state.file, state.retry) { mutableStateOf("") }
            WizardFrame(
                step = wizardStep,
                title = stringResource(if (state.retry) R.string.setup_wrong_backup_password else R.string.setup_enter_backup_password),
                sub = stringResource(
                    when {
                        state.retry && state.sealed -> R.string.setup_password_mismatch_sealed
                        state.retry -> R.string.setup_password_mismatch
                        state.sealed -> R.string.setup_backup_encrypted_prompt
                        else -> R.string.setup_backup_passwords_encrypted_prompt
                    },
                ),
                next = stringResource(R.string.setup_restore),
                onNext = { onPassword(state.file, password) },
                nextEnabled = password.isNotBlank(),
                onBack = onBack,
                // A sealed container is nothing but ciphertext: skipping the password would restore an empty
                // backup, so that way out is not offered.
                extraLeft = if (state.sealed) emptyList() else listOf(stringResource(R.string.setup_skip_no_passwords) to { onPassword(state.file, null) }),
            ) {
                StageSearchField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = stringResource(R.string.setup_backup_password),
                    icon = null,
                    password = true,
                    imeAction = ImeAction.Done,
                    onSearch = { if (password.isNotBlank()) onPassword(state.file, password) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        is SourceImporter.ImportState.Success -> WizardFrame(
            step = WizardStep.READY,
            title = stringResource(R.string.setup_all_set),
            sub = listOfNotNull(
                state.restoredItems?.let { pluralStringResource(R.plurals.setup_restored_items, it, it) },
                if (state.passwordsOmitted) stringResource(R.string.setup_passwords_omitted) else null,
                state.needPassword.takeIf { it.isNotEmpty() }?.let {
                    stringResource(R.string.setup_need_password, ListFormatter.getInstance(LocalConfiguration.current.locales[0]).format(it))
                },
                state.skippedSources.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.setup_skipped_sources, it, it) },
                if (state.invalidLocale) stringResource(R.string.setup_invalid_locale) else null,
            ).joinToString("\n"),
            next = stringResource(R.string.setup_continue),
            onNext = onContinue,
            onBack = null,
        )
        is SourceImporter.ImportState.Failed -> WizardFrame(
            step = wizardStep,
            title = stringResource(R.string.setup_restore_failed),
            sub = state.failure.displayText(resources),
            next = stringResource(R.string.setup_try_again_caps),
            onNext = onPickAgain,
            onBack = onBack,
        )
        // The picker is a separate activity: cancelling it leaves this screen with nothing happening, so
        // there is always a way back and a way to pick another file.
        else -> WizardFrame(
            step = wizardStep,
            title = stringResource(R.string.setup_restoring),
            sub = null,
            next = stringResource(R.string.setup_pick_backup_file),
            onNext = onPickAgain,
            onBack = onBack,
        )
    }
}

/** A Remote submission as the form's own values (the TV fills its Add Source screen the same way). */
private fun tv.own.owntv.core.companion.CompanionPayload.toFormValues(): SourceFormValues = SourceFormValues(
    kind = when (type) {
        tv.own.owntv.core.model.SourceType.M3U -> SourceKind.M3U
        tv.own.owntv.core.model.SourceType.STALKER -> SourceKind.STALKER
        else -> SourceKind.XTREAM
    },
    name = name,
    urlOrServer = if (type == tv.own.owntv.core.model.SourceType.STALKER) portalUrl else server,
    username = user,
    password = pass,
    mac = mac,
    serialNumber = serialNumber,
    deviceId = deviceId,
    deviceId2 = deviceId2,
    signature = signature,
    userAgent = userAgent,
    autoRefresh = tv.own.owntv.core.settings.PlaylistRefresh.parse(autoRefresh),
    live = syncLive,
    movies = syncMovies,
    series = syncSeries,
)

/**
 * The add form's header: its title (28 / 800), then the muted way back — "‹ Playlists · Add a source" from
 * Settings, "‹ Add a playlist" in the wizard.
 */
@Composable
private fun FormHeader(firstRun: Boolean, onBack: () -> Unit) {
    val palette = LocalStagePalette.current
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
        Text(
            stringResource(R.string.settings_add_type_here),
            style = stageText(28f, 800),
            color = palette.text,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Row(
            Modifier.padding(top = 5.dp).clickable(interaction, indication = null, onClick = onBack),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StageIcon(MobileIcons.ArrowBack, 13.dp, palette.muted)
            Text(
                if (firstRun) {
                    stringResource(R.string.setup_add_playlist)
                } else {
                    stringResource(R.string.settings_playlists) + stringResource(R.string.content_epg_bits_separator) + stringResource(R.string.content_add_source)
                },
                style = stageText(13f, 500),
                color = palette.muted,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}
