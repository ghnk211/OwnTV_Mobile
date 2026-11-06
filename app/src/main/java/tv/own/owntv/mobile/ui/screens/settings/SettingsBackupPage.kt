package tv.own.owntv.mobile.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.backup.BackupManager
import tv.own.owntv.core.companion.CompanionFailure
import tv.own.owntv.core.companion.CompanionServerState
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.backup.BackupProfiles
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.StageConfirmItems
import tv.own.owntv.mobile.ui.screens.MoreCountsViewModel
import tv.own.owntv.mobile.ui.setup.copyPickedFile
import tv.own.owntv.mobile.ui.setup.rememberBackupFilePicker
import tv.own.owntv.mobile.ui.shell.ShellViewModel
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageButton
import tv.own.owntv.mobile.ui.stage.StageGroupLabel
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.StageLabel
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StageProgress
import tv.own.owntv.mobile.ui.stage.StageSearchField
import tv.own.owntv.mobile.ui.stage.StageSettingRow
import tv.own.owntv.mobile.ui.stage.StageSheetHeader
import tv.own.owntv.mobile.ui.stage.StageSwitch
import tv.own.owntv.mobile.ui.stage.drawInnerRing
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageClickable
import tv.own.owntv.mobile.ui.stage.stageFx
import tv.own.owntv.mobile.ui.stage.stageText
import tv.own.owntv.mobile.ui.theme.MobileDimens
import tv.own.owntv.mobile.ui.stage.StageButtonStyle

/** What the picker offers to create. A `.own` is a ZIP, and no MIME type of its own exists for it. */
private const val BACKUP_MIME = "application/octet-stream"
private const val BACKUP_FILE_NAME = "owntv-backup.own"

/**
 * What the save picker's document is actually called after the user has had it — the suggestion
 * [BACKUP_FILE_NAME] only covers the case where the provider refuses to answer.
 */
private fun savedFileName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()?.takeIf { it.isNotBlank() } ?: BACKUP_FILE_NAME

/** Where a backup goes once its contents are chosen: a picked file, another device by Remote, or the share sheet. */
private enum class BackupTarget { FILE, REMOTE, SHARE }

/**
 * More › Backup & Restore: "Never backed up" (what lives only on this phone) or when the last backup
 * was; **Back up now** to a file on this phone, by Remote to another device, or through the share sheet;
 * **Restore** from a file or by Remote; and what a backup holds.
 *
 * Files go through the phone's document picker, so the backup lands wherever the user keeps their files
 * without the app ever asking for storage access it does not need. Remote is core's companion server, as on
 * the television: another device on the same Wi-Fi opens the address, enters the PIN, and downloads the
 * backup or sends one. A restore merges: nothing already on the phone is deleted. Every choice on the way
 * is a Stage sheet.
 */
@Composable
fun SettingsBackupPage(
    modifier: Modifier = Modifier,
    vm: BackupViewModel = koinViewModel(),
    counts: MoreCountsViewModel = koinViewModel(),
    shell: ShellViewModel = koinViewModel(),
) {
    val lastBackup by counts.lastBackup.collectAsStateWithLifecycle()
    val favorites by counts.favorites.collectAsStateWithLifecycle()
    val history by counts.history.collectAsStateWithLifecycle()
    val playlists by shell.playlists.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalStagePalette.current
    // A method reference held so a recomposition does not hand the sheets a new one every frame.
    val decisionFor = remember(vm) { vm::decisionFor }

    var exportTarget by remember { mutableStateOf<BackupTarget?>(null) }
    var restoreChooser by remember { mutableStateOf(false) }
    var remoteRestore by remember { mutableStateOf(false) }
    var remoteExport by remember { mutableStateOf(false) }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { uri ->
        val request = vm.pendingFileExport
        vm.pendingFileExport = null
        if (uri != null && request != null) {
            // The picker lets the file be renamed, and that new name is what lands on disk — so the
            // confirmation reads it back instead of repeating the name we suggested.
            vm.export(uri, savedFileName(context, uri), request.sections, request.password)
        }
    }
    val pickBackup = rememberBackupFilePicker { uri ->
        scope.launch {
            val file = copyPickedFile(context, uri, context.cacheDir)
            if (file != null) vm.beginRestore(file) else vm.reportReadFailure()
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val last = lastBackup
        if (last == null) {
            // `.card` warn: what lives only on this phone, counted.
            BackupCard(warn = true, icon = MobileIcons.Warning, title = stringResource(R.string.more_backup_never_title), inline = true) {
                Text(
                    stringResource(
                        R.string.more_backup_never_body_mobile,
                        pluralStringResource(R.plurals.more_playlist_count, playlists.size, playlists.size),
                        pluralStringResource(R.plurals.more_count_favorites, favorites.total, favorites.total),
                        pluralStringResource(R.plurals.more_count_history, history.total, history.total),
                    ),
                    style = stageText(11.5f, 500, lineHeight = 1.4f),
                    color = palette.muted,
                )
            }
        } else {
            BackupCard(icon = MobileIcons.History, title = stringResource(R.string.more_pane_last_backup), inline = true) {
                Text(
                    DateUtils.getRelativeTimeSpanString(last.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                    style = stageText(11.5f, 500),
                    color = palette.muted,
                )
            }
        }
        // A long backup has no popup over it: the card says so and the buttons go quiet.
        BackupCard(fx = true, icon = MobileIcons.Download, title = stringResource(R.string.more_backup_now)) {
            Text(stringResource(R.string.more_backup_now_body_mobile), style = stageText(11.5f, 500), color = palette.muted)
            if (vm.busy) {
                Text(stringResource(R.string.settings_backup_working), style = stageText(11.5f, 600), color = palette.text, modifier = Modifier.padding(top = 10.dp))
                StageProgress(0f, Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StageButton(onClick = { if (!vm.busy) exportTarget = BackupTarget.FILE }, text = stringResource(R.string.more_backup_file_tv_mobile), icon = MobileIcons.Folder, small = true, enabled = !vm.busy)
                StageButton(onClick = { if (!vm.busy) exportTarget = BackupTarget.REMOTE }, text = stringResource(R.string.settings_backup_remote), icon = MobileIcons.Translate, small = true, enabled = !vm.busy)
                StageButton(onClick = { if (!vm.busy) exportTarget = BackupTarget.SHARE }, text = stringResource(R.string.common_share), icon = MobileIcons.OpenInNew, small = true, enabled = !vm.busy)
            }
        }
        BackupCard(icon = MobileIcons.Refresh, title = stringResource(R.string.settings_backup_restore_action), onClick = { if (!vm.busy) restoreChooser = true }) {
            Text(stringResource(R.string.more_restore_body_mobile), style = stageText(11.5f, 500), color = palette.muted)
        }
        StageLabel(stringResource(R.string.more_backup_included), Modifier.padding(start = 4.dp, top = 8.dp))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BackupManager.Section.entries.forEach { IncludedChip(stringResource(it.labelRes())) }
        }
    }

    exportTarget?.let { target ->
        ExportSheet(
            onExport = { request ->
                exportTarget = null
                when (target) {
                    BackupTarget.FILE -> {
                        vm.pendingFileExport = request
                        // Some phones have no app that answers "save a file" (the system Files app
                        // disabled or removed), and launching the picker there throws.
                        try {
                            createDocument.launch(BACKUP_FILE_NAME)
                        } catch (_: ActivityNotFoundException) {
                            vm.reportNoFileApp()
                        }
                    }
                    BackupTarget.REMOTE -> {
                        remoteExport = true
                        vm.exportRemote(request.sections, request.password)
                    }
                    BackupTarget.SHARE -> vm.exportForShare(request.sections, request.password) { file ->
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".share", file)
                        val send = Intent(Intent.ACTION_SEND)
                            .setType(BACKUP_MIME)
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, null))
                    }
                }
            },
            onDismiss = { exportTarget = null },
        )
    }

    if (restoreChooser) {
        MobileBottomSheet(onDismissRequest = { restoreChooser = false }, title = stringResource(R.string.settings_backup_restore_title)) {
            StageSheetHeader(title = stringResource(R.string.settings_backup_restore_title), subtitle = stringResource(R.string.more_restore_body_mobile))
            StageMenuItem(stringResource(R.string.more_backup_file_tv_mobile), onClick = { restoreChooser = false; pickBackup() }, icon = MobileIcons.Folder, chevron = true)
            StageMenuItem(stringResource(R.string.settings_backup_remote), onClick = { restoreChooser = false; remoteRestore = true }, icon = MobileIcons.Translate, chevron = true)
        }
    }

    if (remoteRestore) {
        val state by vm.remoteState.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { vm.startRemoteRestore() }
        // A backup sent from the other device goes into the same restore steps as a picked file.
        LaunchedEffect(Unit) { vm.remoteBackups.collect { file -> remoteRestore = false; vm.beginRestore(file) } }
        DisposableEffect(Unit) { onDispose { vm.stopRemote() } }
        RemoteSheet(
            title = stringResource(R.string.settings_restore_remote_title),
            body = stringResource(R.string.settings_restore_remote_description),
            state = state,
            preparing = false,
            onRestart = vm::startRemoteRestore,
            onDismiss = { remoteRestore = false },
        )
    }

    if (remoteExport) {
        val state by vm.remoteState.collectAsStateWithLifecycle()
        DisposableEffect(Unit) { onDispose { vm.stopRemote() } }
        // A failed preparation is reported by the outcome sheet; the Remote sheet has nothing to show.
        LaunchedEffect(vm.outcome) { if (vm.outcome is BackupViewModel.Outcome.Failed) remoteExport = false }
        RemoteSheet(
            title = stringResource(R.string.settings_download_remote_title),
            body = stringResource(R.string.settings_download_remote_description),
            state = state,
            preparing = vm.preparingRemote,
            onRestart = null,
            onDismiss = { remoteExport = false },
        )
    }

    vm.pending?.let { pending ->
        when {
            // A sealed file tells nobody anything — not even what is in it — until it is opened.
            pending.inspection == null -> SealedPasswordSheet(
                wrong = pending.wrongPassword,
                onSubmit = vm::unseal,
                onDismiss = vm::cancelRestore,
            )
            else -> RestoreSheet(
                inspection = pending.inspection,
                wrongPassword = pending.wrongPassword,
                // A sealed file was already opened with its password; asking for it twice would be
                // asking the same question again.
                askPassword = pending.inspection.encrypted && pending.password == null,
                // A sealed file can carry a profile PIN, which a restore then writes over this app's.
                sealed = pending.sealed,
                decisionFor = decisionFor,
                onRestore = vm::restore,
                onDismiss = vm::cancelRestore,
            )
        }
    }

    vm.outcome?.let { outcome -> OutcomeSheet(outcome, vm::dismissOutcome) }

    // A restore can bring a different icon colour than the launcher shows. Offer the restart once the
    // summary is closed, so the two sheets never stack.
    val settings: tv.own.owntv.core.settings.SettingsRepository = org.koin.compose.koinInject()
    val chosenIcon = settings.appIcon.pref(null)
    val appliedIcon = tv.own.owntv.mobile.ui.components.rememberAppliedIcon()
    var restoredPending by remember { mutableStateOf(false) }
    LaunchedEffect(vm.outcome) { if (vm.outcome is BackupViewModel.Outcome.Restored) restoredPending = true }
    if (restoredPending && vm.outcome == null) {
        if (chosenIcon != null && chosenIcon != appliedIcon) {
            AppIconRestartSheet(chosenIcon) { restoredPending = false }
        } else {
            LaunchedEffect(Unit) { restoredPending = false }
        }
    }
}

/**
 * `.card` (radius 18, padding 14, ink 4.5 %): an icon (18) over a 15 / 800 title and its [content]. [inline]
 * puts the icon beside the title block (the warning); [warn] is the warn tint and rim; [fx] the selected look.
 */
@Composable
private fun BackupCard(
    icon: ImageVector,
    title: String,
    warn: Boolean = false,
    fx: Boolean = false,
    inline: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val palette = LocalStagePalette.current
    val shape = RoundedCornerShape(18.dp)
    val interaction = remember { MutableInteractionSource() }
    val warnColor = palette.warn
    val base = Modifier
        .fillMaxWidth()
        .clip(shape)
        .then(
            when {
                warn -> Modifier
                    .background(warnColor.copy(alpha = 0.10f))
                    .drawBehind { drawInnerRing(warnColor.copy(alpha = 0.3f), 1.dp.toPx(), 18.dp.toPx()) }
                fx -> Modifier.stageFx(18.dp)
                else -> Modifier.background(palette.ink.copy(alpha = 0.045f))
            },
        )
        .then(if (onClick != null) Modifier.stageClickable(interaction, onClick, null) else Modifier)
        .padding(14.dp)
    val tint = if (warn) warnColor else palette.text
    if (inline) {
        Row(base, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StageIcon(icon, 20.dp, tint)
            Column(Modifier.weight(1f)) {
                Text(title, style = stageText(14f, 800), color = palette.text)
                Column(Modifier.padding(top = 2.dp)) { content() }
            }
        }
    } else {
        Column(base) {
            StageIcon(icon, 18.dp, tint)
            Text(title, style = stageText(15f, 800), color = palette.text, modifier = Modifier.padding(top = 6.dp))
            content()
        }
    }
}

/** What's included: a 28 dp `.tool` with a ✓. */
@Composable
private fun IncludedChip(text: String) {
    val palette = LocalStagePalette.current
    Row(
        Modifier.height(28.dp).clip(RoundedCornerShape(12.dp)).background(palette.controlFill).padding(horizontal = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StageIcon(MobileIcons.Check, 14.dp, palette.muted)
        Text(text, style = stageText(12.5f, 700), color = palette.muted)
    }
}

/**
 * Remote: core's companion server, open while this sheet is. The PIN (accent, spaced), the QR of the address
 * on white (a QR on glass may not scan) and the address itself; Starting / Failed / Locked say so.
 * [onRestart] mints a fresh PIN after a failure or a lock (restore only).
 */
@Composable
internal fun RemoteSheet(
    title: String,
    body: String,
    state: CompanionServerState,
    preparing: Boolean,
    onRestart: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val palette = LocalStagePalette.current
    MobileBottomSheet(onDismissRequest = onDismiss, title = title) {
        StageSheetHeader(title = title)
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            val muted = stageText(12.5f, 500)
            // The whole instruction, never cut: it is what the other device's user has to follow.
            Text(body, style = stageText(12f, 500, lineHeight = 1.4f), color = palette.muted, modifier = Modifier.padding(bottom = 12.dp))
            when {
                preparing -> Text(stringResource(R.string.settings_preparing_backup), style = muted, color = palette.muted)
                state is CompanionServerState.Listening -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    state.qr?.let { qr ->
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = stringResource(R.string.common_qr_code_companion_url),
                            modifier = Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.setup_enter_pin_browser), style = muted, color = palette.muted)
                        Text(state.pin, style = stageText(30f, 800, 0.18.em), color = stageAccent.accent)
                        Text(stringResource(R.string.setup_open_url), style = muted, color = palette.muted, modifier = Modifier.padding(top = 8.dp))
                        state.urls.forEach { Text(it, style = stageText(13f, 700), color = palette.text) }
                    }
                }
                state is CompanionServerState.Failed -> Text(state.failure.text(), style = muted, color = palette.danger)
                state == CompanionServerState.Locked -> Text(stringResource(R.string.setup_companion_locked), style = muted, color = palette.danger)
                else -> Text(stringResource(R.string.setup_opening_server), style = muted, color = palette.muted)
            }
        }
        if (onRestart != null && (state is CompanionServerState.Failed || state == CompanionServerState.Locked)) {
            StageMenuItem(
                stringResource(if (state is CompanionServerState.Failed) R.string.settings_try_again else R.string.settings_new_pin),
                onClick = onRestart,
                icon = MobileIcons.Refresh,
            )
        }
        StageMenuItem(stringResource(R.string.common_done), onClick = onDismiss, icon = MobileIcons.Check)
    }
}

@Composable
private fun CompanionFailure.text(): String = when (this) {
    CompanionFailure.InvalidPort -> stringResource(R.string.setup_companion_invalid_port)
    is CompanionFailure.PortInUse -> stringResource(R.string.setup_companion_port_in_use, port)
    CompanionFailure.Unavailable -> stringResource(R.string.setup_companion_unavailable)
}

/** Everything the export needs, decided before the picker opens. It always carries this profile. */
internal data class ExportRequest(
    val sections: Set<BackupManager.Section>,
    val password: String?,
)

/** A sheet's switch row (48 dp): the label, what it covers, and the switch; the whole row toggles. */
@Composable
private fun SwitchRow(label: String, description: String?, checked: Boolean, onToggle: (Boolean) -> Unit) {
    StageSettingRow(title = label, subtitle = description, minHeight = 48.dp, onClick = { onToggle(!checked) }) {
        StageSwitch(checked)
    }
}

/**
 * Which parts of this profile's data, and whether the file is encrypted — in one sheet, because they
 * are one decision and a phone should not walk anybody through three screens for it.
 *
 * There is no "whose data" question any more: this app keeps one profile, and that is the one the
 * backup carries. The locked-profile PIN the old sheet asked before ticking somebody else's name went
 * with the names it ticked.
 */
@Composable
private fun ExportSheet(
    onExport: (ExportRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var sections by remember { mutableStateOf(BackupManager.Section.entries.toSet()) }
    var password by remember { mutableStateOf("") }
    val palette = LocalStagePalette.current

    MobileBottomSheet(onDismissRequest = onDismiss, title = stringResource(R.string.settings_backup_export_title)) {
        // Everything above the buttons scrolls as one block. The header used to sit outside the
        // scrolling part, so at a large font the section rows ate the whole height, the weighted
        // column was left with nothing, and the password field it carries could not be reached at all.
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            StageSheetHeader(
                title = stringResource(R.string.settings_backup_export_title),
                subtitle = stringResource(R.string.mobile_backup_single_profile_note),
            )
            StageGroupLabel(stringResource(R.string.settings_backup_what_backup))
            BackupManager.Section.entries.forEach { section ->
                SwitchRow(
                    label = stringResource(section.labelRes()),
                    description = stringResource(section.descriptionRes()),
                    checked = section in sections,
                    onToggle = { on -> sections = if (on) sections + section else sections - section },
                )
            }
            StageGroupLabel(stringResource(R.string.settings_backup_encrypt_title))
            Text(stringResource(R.string.settings_backup_encrypt_message), style = stageText(11.5f, 500, lineHeight = 1.4f), color = palette.muted, modifier = Modifier.padding(horizontal = 12.dp))
            StageSearchField(
                value = password,
                onValueChange = { password = it },
                placeholder = stringResource(R.string.settings_backup_password),
                icon = null,
                imeAction = ImeAction.Done,
                password = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
        StageConfirmItems(
            confirm = stringResource(if (password.isBlank()) R.string.settings_backup_export_unencrypted else R.string.settings_backup_encrypt_export),
            confirmEnabled = sections.isNotEmpty(),
            onConfirm = { onExport(ExportRequest(sections, password.takeIf { it.isNotBlank() })) },
            onDismiss = onDismiss,
        )
    }
}

/** The password of a sealed file, which cannot be skipped: without it there is nothing to read. */
@Composable
private fun SealedPasswordSheet(
    wrong: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    val palette = LocalStagePalette.current
    MobileBottomSheet(onDismissRequest = onDismiss, title = stringResource(R.string.settings_backup_enter_password)) {
        StageSheetHeader(title = stringResource(R.string.settings_backup_enter_password), subtitle = stringResource(R.string.settings_backup_encrypted_description))
        StageSearchField(
            value = password,
            onValueChange = { password = it },
            placeholder = stringResource(R.string.settings_backup_password),
            icon = null,
            imeAction = ImeAction.Done,
            onSearch = { if (password.isNotBlank()) onSubmit(password) },
            password = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        if (wrong) {
            Text(stringResource(R.string.settings_backup_password_encrypted_mismatch), style = stageText(11.5f, 600), color = palette.danger, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
        // Kept open on purpose: a wrong password comes back to this same sheet with its message.
        ConfirmThenCancel(stringResource(R.string.settings_backup_unlock), password.isNotBlank(), { onSubmit(password) }, onDismiss)
    }
}

/**
 * What to bring back, out of what the file actually holds.
 *
 * [askPassword] is an unsealed but encrypted file: the passwords inside it are locked, everything
 * else is not, so restoring without the password is offered rather than demanded.
 *
 * [decisionFor] is asked again on every tick, because this app keeps one profile and the file keeps a
 * name: what that means — a rename of this device's profile, or a file that cannot be restored at all
 * — is part of what the user is confirming, not something to find out afterwards.
 */
@Composable
private fun RestoreSheet(
    inspection: BackupManager.Inspection,
    wrongPassword: Boolean,
    askPassword: Boolean,
    sealed: Boolean,
    decisionFor: (Set<BackupManager.Section>) -> BackupProfiles.Decision,
    onRestore: (Set<BackupManager.Section>, String?, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var sections by remember(inspection) { mutableStateOf(inspection.sections) }
    // The other device's hardware settings (engine, decoder, frame rate, HDR, surround): offered only
    // for a file written elsewhere, and unticked — a phone's values are rarely a television's.
    var deviceSettings by remember(inspection) { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    val palette = LocalStagePalette.current
    val decision = decisionFor(sections)
    val refusal = backupRefusalRes(decision.block)
    MobileBottomSheet(onDismissRequest = onDismiss, title = stringResource(R.string.settings_backup_restore_title)) {
        StageSheetHeader(title = stringResource(R.string.settings_backup_restore_title))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            StageGroupLabel(stringResource(R.string.settings_backup_what_restore))
            inspection.sections.forEach { section ->
                SwitchRow(
                    label = stringResource(section.labelRes()),
                    description = stringResource(section.descriptionRes()),
                    checked = section in sections,
                    onToggle = { on -> sections = if (on) sections + section else sections - section },
                )
            }
            if (inspection.fromOtherDevice && BackupManager.Section.SETTINGS in sections) {
                SwitchRow(
                    label = stringResource(R.string.settings_backup_device_settings),
                    description = stringResource(R.string.settings_backup_device_settings_desc),
                    checked = deviceSettings,
                    onToggle = { deviceSettings = it },
                )
            }
            val note = stageText(11.5f, 500, lineHeight = 1.4f)
            if (refusal != null) {
                // No restore at all: the reason is the whole point of the sheet now, so the confirm is
                // left disabled rather than hidden — the user has to be able to read it.
                Text(
                    stringResource(refusal),
                    style = note,
                    color = palette.danger,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            } else {
                decision.renameTo?.let { name ->
                    Text(
                        stringResource(R.string.mobile_profile_rename_notice, name),
                        style = note,
                        color = palette.warn,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            if (sealed) {
                Text(
                    stringResource(R.string.mobile_restore_pin_notice),
                    style = note,
                    color = palette.muted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            if (askPassword) {
                Text(stringResource(R.string.settings_backup_saved_passwords_description), style = note, color = palette.muted, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                StageSearchField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = stringResource(R.string.settings_backup_password),
                    icon = null,
                    imeAction = ImeAction.Done,
                    password = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
                if (wrongPassword) {
                    Text(stringResource(R.string.settings_backup_password_mismatch), style = stageText(11.5f, 600), color = palette.danger, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
        // The view model closes the sheet when the restore is done, or keeps it for a wrong password.
        ConfirmThenCancel(
            stringResource(if (askPassword && password.isBlank()) R.string.settings_backup_skip_passwords else R.string.settings_backup_restore_action),
            sections.isNotEmpty() && refusal == null,
            { onRestore(sections, password.takeIf { it.isNotBlank() }, deviceSettings) },
            onDismiss,
        )
    }
}

/** A sheet's ending whose confirm does not close it (the view model decides): ✓ confirm, then ✕ Cancel. */
@Composable
private fun ConfirmThenCancel(confirm: String, enabled: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    StageMenuItem(confirm, onClick = onConfirm, icon = MobileIcons.Check, filled = enabled, enabled = enabled, modifier = Modifier.padding(top = 6.dp))
    StageMenuItem(stringResource(R.string.common_cancel), onClick = onCancel, icon = MobileIcons.Close)
}

/** What happened, in the same words the television uses for it. */
@Composable
private fun OutcomeSheet(outcome: BackupViewModel.Outcome, onDismiss: () -> Unit) {
    val palette = LocalStagePalette.current
    MobileBottomSheet(onDismissRequest = onDismiss, title = stringResource(R.string.settings_backup_title)) {
        StageSheetHeader(title = stringResource(R.string.settings_backup_title))
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val body = stageText(13f, 500, lineHeight = 1.45f)
            when (outcome) {
                is BackupViewModel.Outcome.Exported -> Text(
                    stringResource(
                        if (outcome.passwordsOmitted) R.string.settings_backup_saved_to_without_passwords else R.string.settings_backup_saved_to,
                        outcome.name,
                    ),
                    style = body, color = palette.text,
                )
                is BackupViewModel.Outcome.Restored -> {
                    Text(pluralStringResource(R.plurals.settings_backup_restored, outcome.summary.items, outcome.summary.items), style = body, color = palette.text)
                    if (outcome.summary.skippedSources > 0) {
                        Text(pluralStringResource(R.plurals.settings_backup_skipped_sources, outcome.summary.skippedSources, outcome.summary.skippedSources), style = body, color = palette.muted)
                    }
                    if (outcome.summary.invalidLocale) Text(stringResource(R.string.settings_backup_invalid_locale), style = body, color = palette.muted)
                    Text(stringResource(R.string.settings_backup_restore_resync), style = body, color = palette.muted)
                    Text(stringResource(R.string.settings_backup_restore_password_note), style = body, color = palette.muted)
                }
                is BackupViewModel.Outcome.Failed -> Text(stringResource(outcome.messageRes), style = body, color = palette.danger)
                // Nothing was written and the file is where it was — this is an instruction, not a fault.
                is BackupViewModel.Outcome.Refused -> Text(stringResource(outcome.messageRes), style = body, color = palette.warn)
            }
        }
        StageMenuItem(stringResource(R.string.common_ok), onClick = onDismiss, icon = MobileIcons.Check, filled = true)
    }
}

/** Cancel and confirm, right-aligned under a sheet's body — the shape these sheets end in. */
@Composable
internal fun SheetButtons(
    confirm: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MobileDimens.ScreenPaddingH),
        horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StageButton(
            text = stringResource(R.string.common_cancel),
            onClick = onDismiss,
            small = true,
        )
        StageButton(text = confirm, onClick = onConfirm, enabled = confirmEnabled, style = StageButtonStyle.PRIMARY)
    }
}

@Composable
internal fun Note(text: String) {
    Text(
        text = text,
        style = stageText(12f, 400),
        color = LocalStagePalette.current.muted,
    )
}

internal fun BackupManager.Section.labelRes() = when (this) {
    BackupManager.Section.SOURCES -> R.string.settings_backup_section_sources
    BackupManager.Section.CUSTOMIZE -> R.string.settings_backup_section_customize
    BackupManager.Section.FAVORITES -> R.string.settings_backup_section_favorites
    BackupManager.Section.HISTORY -> R.string.settings_backup_section_history
    BackupManager.Section.RESUME -> R.string.settings_backup_section_resume
    BackupManager.Section.MANUAL_REORDER -> R.string.settings_backup_section_reorder
    BackupManager.Section.SETTINGS -> R.string.settings_backup_section_settings
}

internal fun BackupManager.Section.descriptionRes() = when (this) {
    BackupManager.Section.SOURCES -> R.string.settings_backup_section_sources_desc
    BackupManager.Section.CUSTOMIZE -> R.string.settings_backup_section_customize_desc
    BackupManager.Section.FAVORITES -> R.string.settings_backup_section_favorites_desc
    BackupManager.Section.HISTORY -> R.string.settings_backup_section_history_desc
    BackupManager.Section.RESUME -> R.string.settings_backup_section_resume_desc
    BackupManager.Section.MANUAL_REORDER -> R.string.settings_backup_section_reorder_desc
    BackupManager.Section.SETTINGS -> R.string.settings_backup_section_settings_desc
}
