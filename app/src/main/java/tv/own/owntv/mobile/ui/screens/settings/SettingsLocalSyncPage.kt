package tv.own.owntv.mobile.ui.screens.settings

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.backup.BackupManager
import tv.own.owntv.core.companion.CompanionServerState
import tv.own.owntv.core.sync.local.SyncDirection
import tv.own.owntv.core.sync.local.SyncFailure
import tv.own.owntv.core.sync.local.shortCodes
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.sheetListHeight
import tv.own.owntv.mobile.ui.setup.WizardFrame
import tv.own.owntv.mobile.ui.setup.WizardStep
import tv.own.owntv.mobile.ui.setup.WizardSweepBar
import tv.own.owntv.mobile.ui.theme.MobileDimens
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageButton
import tv.own.owntv.mobile.ui.stage.StageGroupLabel
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.StageLabel
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StageSearchField
import tv.own.owntv.mobile.ui.stage.StageSettingRow
import tv.own.owntv.mobile.ui.stage.StageSheetHeader
import tv.own.owntv.mobile.ui.stage.StageSwitch
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageClickable
import tv.own.owntv.mobile.ui.stage.stageText
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color

/**
 * Sync this phone with the television over the home Wi-Fi — no account, no cloud, nothing leaving
 * the house.
 *
 * The page is a list of the devices already paired plus the two ways to add one: **let this phone be
 * found** (it shows a PIN and a QR code, and the television connects to it) or **find a device**
 * (discovery, a scanned QR, or a typed address). Either device can be either role, which is the
 * point — a sync you can only start from the television is not much use when you are holding a phone.
 *
 * Every direction is named. There is no bare "Sync" button whose meaning the user has to guess.
 */
@Composable
fun SettingsLocalSyncPage(
    modifier: Modifier = Modifier,
    vm: LocalSyncViewModel = koinViewModel(),
) {
    val paired by vm.paired.collectAsStateWithLifecycle()
    val hosting by vm.hosting.collectAsStateWithLifecycle()
    val palette = LocalStagePalette.current
    // The paired device a tap picked: its row takes the `.fx` look and shows Sync now · Unpair.
    var picked by remember { mutableStateOf<String?>(null) }

    // The listener runs only while this page is on screen. That, and the PIN, is the whole of the
    // security model — so leaving the page has to close it, not merely stop looking at it.
    DisposableEffect(Unit) { onDispose { vm.stopHosting() } }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 24.dp),
    ) {
        val listening = hosting as? CompanionServerState.Listening
        Text(stringResource(R.string.more_sync_description), style = stageText(12f, 500, lineHeight = 1.4f), color = palette.muted, modifier = Modifier.padding(bottom = 12.dp))
        // One state, one switch. Sync mode is what makes this device reachable at all — every other
        // action on this screen, in either direction, needs the FAR device to have it on too, which is
        // why it comes first and why the failure message names exactly where to find it.
        SyncCard(
            icon = MobileIcons.Phone,
            title = stringResource(R.string.local_sync_mode),
            subtitle = stringResource(R.string.more_sync_mode_description),
            onClick = { if (!vm.busy) { if (listening != null) vm.stopHosting() else vm.startHosting() } },
            trailing = { StageSwitch(listening != null) },
        ) {
            listening?.let { HostingCard(it, vm.deviceName) }
        }
        Spacer(Modifier.height(8.dp))
        SyncCard(
            icon = MobileIcons.Search,
            title = stringResource(R.string.local_sync_connect),
            subtitle = stringResource(R.string.local_sync_connect_description),
            onClick = { if (!vm.busy) vm.beginPairing() },
            trailing = { StageIcon(MobileIcons.KeyboardArrowRight, 16.dp, palette.text) },
        )

        if (paired.isNotEmpty()) {
            // Only the devices whose names collide get a code, so a normal household never sees one.
            val codes = shortCodes(paired)
            StageLabel(stringResource(R.string.local_sync_paired_devices), Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp))
            paired.forEach { device ->
                val selected = picked == device.id
                StageSettingRow(
                    title = codes[device.id]?.let { stringResource(R.string.local_sync_device_with_code, device.name, it) } ?: device.name,
                    subtitle = device.address + stringResource(R.string.content_epg_bits_separator) + lastSyncedText(device.lastSyncAt),
                    // A paired device records no kind (television or phone), so one neutral glyph for all.
                    icon = MobileIcons.Sync,
                    current = selected,
                    onClick = { picked = if (selected) null else device.id },
                )
                if (selected) {
                    Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StageButton(onClick = { vm.chooseDevice(device) }, text = stringResource(R.string.settings_sync_now), icon = MobileIcons.Refresh, small = true, enabled = !vm.busy)
                        StageButton(onClick = { picked = null; vm.unpair(device) }, text = stringResource(R.string.more_sync_unpair), icon = MobileIcons.Close, small = true, enabled = !vm.busy)
                    }
                }
            }
        }

        if (vm.busy) {
            Text(stringResource(R.string.local_sync_working), style = stageText(12f, 600), color = palette.text, modifier = Modifier.padding(top = 14.dp))
        }
    }

    when (val step = vm.step) {
        null -> Unit
        is LocalSyncViewModel.Step.FindDevice -> FindDeviceSheet(vm)
        is LocalSyncViewModel.Step.EnterPin -> PinSheet(onSubmit = vm::submitPin, onDismiss = vm::cancel)
        is LocalSyncViewModel.Step.ChooseDirection -> DirectionSheet(
            deviceName = step.device.name,
            onChoose = vm::chooseDirection,
            onUnpair = { vm.unpair(step.device); vm.cancel() },
            onDismiss = vm::cancel,
        )
        is LocalSyncViewModel.Step.ChooseSections -> SectionsSheet(
            direction = step.direction,
            onStart = vm::start,
            onDismiss = vm::cancel,
        )
        is LocalSyncViewModel.Step.Confirm -> ConfirmSheet(
            preview = step.preview,
            direction = step.direction,
            // A sync container is sealed with the key the two devices agreed, and a profile can carry a
            // PIN — which is then what opens this app afterwards.
            sealed = step.password != null,
            renameTo = step.renameTo,
            onConfirm = vm::confirm,
            onDismiss = vm::cancel,
        )
        is LocalSyncViewModel.Step.Result -> ResultSheet(step, vm::cancel)
    }

    vm.error?.let { ErrorSheet(it, vm::dismissError) }
    vm.blocked?.let { BlockedSheet(it, vm::dismissBlocked) }
}

/**
 * The same feature offered during first-run setup, as the third way of furnishing a new phone: copy
 * the device you already have over the Wi-Fi, instead of typing a playlist in or finding a backup.
 *
 * It lives beside [SettingsLocalSyncPage] so it can reuse that page's sheets unchanged — they are the
 * substance of it, and a second copy would be a second thing to keep right.
 *
 * Two things differ from the settings page, and only two:
 *  - **it never hosts.** A phone still being set up has nothing worth serving, and announcing an
 *    empty container on the network would only be something for the other device to find by mistake.
 *  - **the direction is not a question.** A device at this point in its life can only receive, so
 *    [LocalSyncViewModel.Step.ChooseDirection] is answered rather than shown. Which *sections* to
 *    take is still asked: someone moving to a new phone may want the playlists without the old
 *    device's settings.
 *
 * The television's counterpart is `SetupLocalSyncScreen`, and the sequence is deliberately identical.
 */
@Composable
fun SetupLocalSyncStep(
    onRestored: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: LocalSyncViewModel = koinViewModel(),
) {
    // Straight into discovery: arriving here IS the decision to look for the other device, so asking
    // the user to press "Find a device" as well would be asking twice.
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        vm.beginPairing()
        started = true
    }

    val step = vm.step
    LaunchedEffect(step, started) {
        when {
            !started -> Unit
            // Dismissing any sheet clears the step, and here that means leaving — there is no device
            // list underneath to fall back to.
            step == null -> onBack()
            step is LocalSyncViewModel.Step.ChooseDirection -> vm.chooseDirection(SyncDirection.RECEIVE)
            else -> Unit
        }
    }

    // Once the data has landed there is nothing left to cancel, so Back means the same as Done.
    BackHandler { if (step is LocalSyncViewModel.Step.Result) onRestored() else vm.cancel() }

    Box(modifier) {
        // Title only. Every sheet below already says what to do, and the find sheet in particular
        // carries this step's instruction verbatim — printing it here too showed it twice at once.
        WizardFrame(
            step = WizardStep.CONTENT,
            title = stringResource(R.string.setup_sync_device),
            sub = null,
            next = null,
            onNext = {},
            onBack = null,
        ) { if (vm.busy) WizardSweepBar() }

        when (step) {
            null -> Unit
            is LocalSyncViewModel.Step.FindDevice -> FindDeviceSheet(vm)
            is LocalSyncViewModel.Step.EnterPin -> PinSheet(onSubmit = vm::submitPin, onDismiss = vm::cancel)
            // Answered above; a sheet for it would flash a choice the user never made.
            is LocalSyncViewModel.Step.ChooseDirection -> Unit
            is LocalSyncViewModel.Step.ChooseSections -> SectionsSheet(
                direction = step.direction,
                onStart = vm::start,
                onDismiss = vm::cancel,
            )
            is LocalSyncViewModel.Step.Confirm -> ConfirmSheet(
                preview = step.preview,
                direction = step.direction,
                sealed = step.password != null,
                renameTo = step.renameTo,
                onConfirm = vm::confirm,
                onDismiss = vm::cancel,
            )
            // Not [ResultSheet]: its Close returns to the device list, and here the only thing left
            // to do is finish onboarding.
            is LocalSyncViewModel.Step.Result -> SetupResultSheet(step, onDone = onRestored)
        }

        vm.error?.let { ErrorSheet(it, vm::dismissError) }
        // The same refusal as on the settings page: the profile that arrived is the other device's
        // problem to fix, and nothing of it was applied here.
        vm.blocked?.let { BlockedSheet(it, vm::dismissBlocked) }
    }
}

/** [ResultSheet] with the one difference setup needs: the button leaves the wizard, not the sheet. */
@Composable
private fun SetupResultSheet(result: LocalSyncViewModel.Step.Result, onDone: () -> Unit) {
    MobileBottomSheet(onDismissRequest = onDone, title = stringResource(R.string.local_sync_done)) {
        StageSheetHeader(title = stringResource(R.string.local_sync_done))
        Column(
            modifier = Modifier,
            verticalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
        ) {
            result.received?.let { SyncNote(stringResource(R.string.local_sync_received_items, it.items)) }
        }
        SyncButtons(
            confirm = stringResource(R.string.common_done),
            confirmEnabled = true,
            onConfirm = onDone,
            onDismiss = onDone,
        )
    }
}

/** "Last synced: 2 hours ago", or the plain "Not synced yet" the EPG rows already use. */
@Composable
private fun lastSyncedText(at: Long): String = if (at <= 0) {
    stringResource(R.string.settings_epg_sources_not_synced)
} else {
    stringResource(
        R.string.local_sync_last_synced,
        DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
    )
}

/**
 * What the other device has to be told while Sync mode is on: the PIN (accent, spaced), the QR on white
 * and the address. The QR carries the address only — never the PIN, so a photographed code on its own
 * opens nothing.
 */
@Composable
private fun HostingCard(state: CompanionServerState.Listening, deviceName: String) {
    val palette = LocalStagePalette.current
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        state.qr?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = stringResource(R.string.local_sync_qr_description),
                modifier = Modifier.size(QR_SIZE).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(deviceName, style = stageText(13f, 700), color = palette.text)
            Text(stringResource(R.string.local_sync_pin_is, state.pin), style = stageText(18f, 800), color = stageAccent.accent)
            state.urls.firstOrNull()?.let { Text(it, style = stageText(12f, 600), color = palette.muted) }
        }
    }
    Text(stringResource(R.string.local_sync_host_hint), style = stageText(11.5f, 500, lineHeight = 1.4f), color = palette.muted, modifier = Modifier.padding(top = 8.dp))
}

/** `.card` with a row: an 18 dp icon, the 14 / 800 title and its 11 dp line, then [trailing]; [below] under it. */
@Composable
private fun SyncCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
    below: @Composable () -> Unit = {},
) {
    val palette = LocalStagePalette.current
    val shape = RoundedCornerShape(18.dp)
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth().clip(shape).background(palette.ink.copy(alpha = 0.045f)).stageClickable(interaction, onClick, null).padding(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            StageIcon(icon, 18.dp, palette.text)
            Column(Modifier.weight(1f)) {
                Text(title, style = stageText(14f, 800), color = palette.text)
                Text(subtitle, style = stageText(11f, 500, lineHeight = 1.35f), color = palette.muted)
            }
            trailing()
        }
        below()
    }
}

/** Discovery, a scanned code, or an address typed by hand — all three, because mDNS is not reliable. */
@Composable
private fun FindDeviceSheet(vm: LocalSyncViewModel) {
    var manual by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }

    if (scanning) {
        QrScanSheet(
            onScanned = { address, port ->
                scanning = false
                vm.chooseAddress(address, port)
            },
            onDismiss = { scanning = false },
        )
        return
    }

    MobileBottomSheet(onDismissRequest = vm::cancel, title = stringResource(R.string.local_sync_add_device)) {
        StageSheetHeader(title = stringResource(R.string.local_sync_add_device))
        Column(
            modifier = Modifier
                .heightIn(max = sheetListHeight())
                .verticalScroll(rememberScrollState())
                ,
            verticalArrangement = Arrangement.spacedBy(MobileDimens.GapTiny),
        ) {
            SyncNote(stringResource(R.string.local_sync_find_hint))
            if (vm.found.isEmpty()) {
                StageGroupLabel(stringResource(R.string.local_sync_searching))
            } else {
                StageGroupLabel(stringResource(R.string.local_sync_found))
                vm.found.forEach { device ->
                    // A device already paired says so instead of showing an address the user has no
                    // use for, and opens its actions rather than asking for a PIN it does not need.
                    val known = vm.pairedMatch(device)
                    StageSettingRow(
                        title = device.name,
                        subtitle = if (known != null) {
                            stringResource(R.string.local_sync_already_paired)
                        } else {
                            device.address
                        },
                        onClick = { vm.choose(device) },
                    )
                }
            }
            StageGroupLabel(stringResource(R.string.local_sync_scan_qr))
            StageSettingRow(
                title = stringResource(R.string.local_sync_scan_qr),
                subtitle = stringResource(R.string.local_sync_scan_qr_description),
                onClick = { scanning = true },
            )
            StageGroupLabel(stringResource(R.string.local_sync_manual_address))
            StageSearchField(
                value = manual,
                onValueChange = { manual = it },
                placeholder = stringResource(R.string.local_sync_manual_address_label),
                icon = null,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                imeAction = ImeAction.Done,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        SyncButtons(
            confirm = stringResource(R.string.settings_backup_continue),
            confirmEnabled = manual.isNotBlank(),
            onConfirm = { vm.chooseAddress(manual.trim(), portOf(manual)) },
            onDismiss = vm::cancel,
        )
    }
}

@Composable
private fun PinSheet(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    MobileBottomSheet(onDismissRequest = onDismiss, title = stringResource(R.string.local_sync_enter_pin)) {
        StageSheetHeader(title = stringResource(R.string.local_sync_enter_pin))
        Column(
            modifier = Modifier
                .heightIn(max = sheetListHeight())
                .verticalScroll(rememberScrollState())
                .imePadding()
                ,
            verticalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
        ) {
            SyncNote(stringResource(R.string.local_sync_enter_pin_description))
            StageSearchField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(PIN_LENGTH) },
                placeholder = stringResource(R.string.local_sync_pin_label),
                icon = null,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
                onSearch = { if (pin.length == PIN_LENGTH) onSubmit(pin) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        SyncButtons(
            confirm = stringResource(R.string.local_sync_pair),
            confirmEnabled = pin.length == PIN_LENGTH,
            onConfirm = { onSubmit(pin) },
            onDismiss = onDismiss,
        )
    }
}

/** Three named directions. Nothing here is called "sync" on its own. */
@Composable
private fun DirectionSheet(
    deviceName: String,
    onChoose: (SyncDirection) -> Unit,
    onUnpair: () -> Unit,
    onDismiss: () -> Unit,
) {
    MobileBottomSheet(onDismissRequest = onDismiss, title = deviceName) {
        StageSheetHeader(title = deviceName)
        Column(Modifier) {
            StageSettingRow(
                title = stringResource(R.string.local_sync_send_to, deviceName),
                subtitle = stringResource(R.string.local_sync_send_description),
                onClick = { onChoose(SyncDirection.SEND) },
            )
            StageSettingRow(
                title = stringResource(R.string.local_sync_receive_from, deviceName),
                subtitle = stringResource(R.string.local_sync_receive_description),
                onClick = { onChoose(SyncDirection.RECEIVE) },
            )
            StageSettingRow(
                title = stringResource(R.string.local_sync_merge_with, deviceName),
                subtitle = stringResource(R.string.local_sync_merge_description),
                onClick = { onChoose(SyncDirection.MERGE) },
            )
            StageSettingRow(
                title = stringResource(R.string.local_sync_unpair),
                subtitle = stringResource(R.string.local_sync_unpair_description),
                onClick = onUnpair,
            )
        }
    }
}

/** The same tick-list Backup & Restore uses, because it is the same set of things. */
@Composable
private fun SectionsSheet(
    direction: SyncDirection,
    onStart: (Set<BackupManager.Section>) -> Unit,
    onDismiss: () -> Unit,
) {
    var sections by remember { mutableStateOf(BackupManager.Section.entries.toSet()) }

    val title = stringResource(
        when (direction) {
            SyncDirection.SEND -> R.string.local_sync_what_to_send
            SyncDirection.RECEIVE -> R.string.local_sync_what_to_receive
            SyncDirection.MERGE -> R.string.local_sync_what_to_merge
        },
    )
    MobileBottomSheet(onDismissRequest = onDismiss, title = title) {
        StageSheetHeader(title = title)
        Column(
            modifier = Modifier
                .heightIn(max = sheetListHeight())
                .verticalScroll(rememberScrollState())
                ,
            verticalArrangement = Arrangement.spacedBy(MobileDimens.GapTiny),
        ) {
            SyncNote(stringResource(R.string.local_sync_sections_hint))
            SyncCheckRow(
                label = stringResource(R.string.local_sync_everything),
                description = null,
                checked = sections.size == BackupManager.Section.entries.size,
                onToggle = { on -> sections = if (on) BackupManager.Section.entries.toSet() else emptySet() },
            )
            BackupManager.Section.entries.forEach { section ->
                SyncCheckRow(
                    label = stringResource(section.labelRes()),
                    description = stringResource(section.descriptionRes()),
                    checked = section in sections,
                    onToggle = { on -> sections = if (on) sections + section else sections - section },
                )
            }
            // No password field, deliberately. The two devices already share a secret from pairing
            // and core seals the payload with it, so the playlist logins travel without anyone being
            // asked to invent a passphrase in the middle of a sync.
        }
        SyncButtons(
            confirm = stringResource(R.string.settings_backup_continue),
            confirmEnabled = sections.isNotEmpty(),
            onConfirm = { onStart(sections) },
            onDismiss = onDismiss,
        )
    }
}

/**
 * The dry run. Nothing has been written when this is on screen.
 *
 * [renameTo] is the one thing the merge does to this device rather than to its data: this app keeps one
 * profile, so the incoming one only lands on it by taking its name — which the user is told about here,
 * before anything happens, and which is undone if the merge then fails.
 */
@Composable
private fun ConfirmSheet(
    preview: BackupManager.Preview,
    direction: SyncDirection,
    sealed: Boolean,
    renameTo: String?,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // The other device's hardware settings and engine pins: offered only when it sent some, unticked.
    var deviceSettings by remember(preview) { mutableStateOf(false) }
    MobileBottomSheet(onDismissRequest = onDismiss, title = stringResource(R.string.local_sync_confirm_title)) {
        StageSheetHeader(title = stringResource(R.string.local_sync_confirm_title))
        Column(
            modifier = Modifier
                .heightIn(max = sheetListHeight())
                .verticalScroll(rememberScrollState())
                ,
            verticalArrangement = Arrangement.spacedBy(MobileDimens.GapTiny),
        ) {
            if (preview.isEmpty) {
                SyncNote(stringResource(R.string.local_sync_nothing_to_change))
            } else {
                SyncNote(stringResource(R.string.local_sync_confirm_hint))
                PreviewLine(R.string.local_sync_change_profiles, preview.newProfiles)
                PreviewLine(R.string.local_sync_change_sources, preview.newSources)
                PreviewLine(R.string.local_sync_change_favorites, preview.newFavorites)
                PreviewLine(R.string.local_sync_change_history, preview.newHistory)
                PreviewLine(R.string.local_sync_change_resume, preview.newResume)
                PreviewLine(R.string.local_sync_change_reorder, preview.newReorder)
                PreviewLine(R.string.local_sync_change_settings, preview.changedSettings)
                PreviewLine(R.string.local_sync_change_deletions, preview.deletions)
                if (preview.hasCustomizations) SyncNote(stringResource(R.string.local_sync_change_customize))
            }
            if (preview.hasDeviceSettings) {
                SyncCheckRow(
                    label = stringResource(R.string.settings_backup_device_settings),
                    description = stringResource(R.string.settings_backup_device_settings_desc),
                    checked = deviceSettings,
                    onToggle = { deviceSettings = it },
                )
            }
            renameTo?.let { name ->
                Text(
                    stringResource(R.string.mobile_profile_rename_notice_sync, name),
                    style = stageText(11.5f, 500, lineHeight = 1.4f),
                    color = LocalStagePalette.current.warn,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (sealed) SyncNote(stringResource(R.string.mobile_sync_pin_notice))
            if (direction == SyncDirection.MERGE) SyncNote(stringResource(R.string.local_sync_merge_note))
        }
        SyncButtons(
            confirm = stringResource(R.string.local_sync_apply),
            confirmEnabled = !preview.isEmpty,
            onConfirm = { onConfirm(deviceSettings) },
            onDismiss = onDismiss,
        )
    }
}

/**
 * A transfer this device cannot take, and what to change on the device that sent it. Nothing arrived
 * anywhere: the container was thrown out of the cache the moment it was recognised.
 */
@Composable
private fun BlockedSheet(@StringRes messageRes: Int, onDismiss: () -> Unit) {
    MobileBottomSheet(onDismissRequest = onDismiss, title = stringResource(R.string.local_sync_failed)) {
        StageSheetHeader(title = stringResource(R.string.local_sync_failed))
        Column(Modifier) {
            SyncNote(stringResource(messageRes))
        }
        SyncButtons(
            confirm = stringResource(R.string.common_ok),
            confirmEnabled = true,
            onConfirm = onDismiss,
            onDismiss = onDismiss,
        )
    }
}

/** One counted change. Zeroes are left out — a list of noughts says nothing worth reading. */
@Composable
private fun PreviewLine(labelRes: Int, count: Int) {
    if (count <= 0) return
    Text(
        text = stringResource(labelRes, count),
        style = stageText(13f, 600),
        color = LocalStagePalette.current.text,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

@Composable
private fun ResultSheet(result: LocalSyncViewModel.Step.Result, onDismiss: () -> Unit) {
    MobileBottomSheet(onDismissRequest = onDismiss, title = stringResource(R.string.local_sync_done)) {
        StageSheetHeader(title = stringResource(R.string.local_sync_done))
        Column(
            modifier = Modifier,
            verticalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
        ) {
            result.received?.let { SyncNote(stringResource(R.string.local_sync_received_items, it.items)) }
            if (result.sent) SyncNote(stringResource(R.string.local_sync_sent))
        }
        SyncButtons(
            confirm = stringResource(R.string.settings_close),
            confirmEnabled = true,
            onConfirm = onDismiss,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun ErrorSheet(failure: SyncFailure, onDismiss: () -> Unit) {
    MobileBottomSheet(onDismissRequest = onDismiss, title = stringResource(R.string.local_sync_failed)) {
        StageSheetHeader(title = stringResource(R.string.local_sync_failed))
        Column(Modifier) {
            SyncNote(
                stringResource(
                    when (failure) {
                        SyncFailure.Unreachable -> R.string.local_sync_error_unreachable
                        SyncFailure.NotAuthorized -> R.string.local_sync_error_unauthorized
                        SyncFailure.BadPayload -> R.string.local_sync_error_bad_payload
                        SyncFailure.Unknown -> R.string.local_sync_error_unknown
                    },
                ),
            )
        }
        SyncButtons(
            confirm = stringResource(R.string.settings_close),
            confirmEnabled = true,
            onConfirm = onDismiss,
            onDismiss = onDismiss,
        )
    }
}

/** `192.168.1.5:8089` or a whole URL both carry a port; a bare address means the usual one. */
private fun portOf(address: String): Int =
    address.removePrefix("http://").substringAfter(':', "").substringBefore('/')
        .toIntOrNull() ?: tv.own.owntv.core.companion.CompanionLink.DEFAULT_PORT

private const val PIN_LENGTH = 6
private val QR_SIZE = 110.dp

/** A sheet's muted line (11.5 / 500), on the sheet's 12 dp side. */
@Composable
private fun SyncNote(text: String) {
    Text(text, style = stageText(11.5f, 500, lineHeight = 1.4f), color = LocalStagePalette.current.muted, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
}

/** A sheet's switch row (48 dp): label, what it covers, the switch; the whole row toggles. */
@Composable
private fun SyncCheckRow(label: String, description: String?, checked: Boolean, onToggle: (Boolean) -> Unit) {
    StageSettingRow(title = label, subtitle = description, minHeight = 48.dp, onClick = { onToggle(!checked) }) { StageSwitch(checked) }
}

/** How these sheets end: the confirm (filled when it can be pressed), then Cancel. */
@Composable
private fun SyncButtons(confirm: String, confirmEnabled: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    StageMenuItem(confirm, onClick = onConfirm, icon = MobileIcons.Check, filled = confirmEnabled, enabled = confirmEnabled, modifier = Modifier.padding(top = 6.dp))
    StageMenuItem(stringResource(R.string.common_cancel), onClick = onDismiss, icon = MobileIcons.Close)
}
