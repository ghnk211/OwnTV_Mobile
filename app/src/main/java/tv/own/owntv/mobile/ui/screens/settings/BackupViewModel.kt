package tv.own.owntv.mobile.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.backup.BackupManager
import tv.own.owntv.core.companion.CompanionController
import tv.own.owntv.core.companion.CompanionLink
import tv.own.owntv.core.companion.CompanionServerState
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.backup.BackupProfiles
import java.io.File

/**
 * Writing a backup out to a file the user picked, and reading one back in.
 *
 * The backup itself is core's, shared with the television — what is this app's own is the document
 * picker at each end. Core reads and writes real files, so a picked document is copied into the
 * cache before it is opened, and an export is written to the cache and then poured into the URI the
 * picker handed back. That is the price of never asking a phone for all-files access.
 *
 * This app keeps one profile, so an export never has a say about profiles — it carries the profile the
 * app opens with, and nothing else. A restore is the other direction, and there the file does get to
 * name the profile: [BackupProfiles] decides before anything is applied whether that name fits this
 * device, whether it means renaming the device's own profile (announced on the sheet, rolled back if
 * the import then fails), or whether the file has to be refused and left untouched.
 */
class BackupViewModel(
    private val backup: BackupManager,
    private val context: Context,
    private val profileDao: ProfileDao,
    private val settings: SettingsRepository,
    private val companion: CompanionController,
) : ViewModel() {

    /** A backup of a large catalog takes a while; nothing else may be started on top of it. */
    var busy by mutableStateOf(false)
        private set

    /** What the finished action has to tell the user, shown until they dismiss it. */
    var outcome by mutableStateOf<Outcome?>(null)
        private set

    /** The file being restored, as far as it has been understood so far. */
    var pending by mutableStateOf<PendingRestore?>(null)
        private set

    sealed interface Outcome {
        data class Exported(val name: String, val passwordsOmitted: Boolean) : Outcome
        data class Restored(val summary: BackupManager.ImportSummary) : Outcome

        /**
         * The file is fine and this app cannot hold what is in it — nothing was written, and the file is
         * left where it was so it can be fixed on the device that made it.
         */
        data class Refused(@param:StringRes val messageRes: Int) : Outcome

        data class Failed(@param:StringRes val messageRes: Int) : Outcome
    }

    /**
     * [inspection] is null while a sealed file is still locked — a sealed container says nothing at
     * all about itself, not even which sections it holds, until the password opens it.
     *
     * [summary], [deviceProfile] and [otherNames] are the two sides of the name question, captured
     * together so the decision the sheet shows and the one the restore re-checks are made of the same
     * facts. The dormant [otherNames] are the rows the older multi-profile builds left behind: this app
     * shows only [deviceProfile], but those names are still taken.
     */
    data class PendingRestore(
        val file: File,
        val inspection: BackupManager.Inspection? = null,
        val password: String? = null,
        val sealed: Boolean = false,
        val wrongPassword: Boolean = false,
        val summary: BackupProfiles.Summary? = null,
        val deviceProfile: ProfileEntity? = null,
        val otherNames: List<String> = emptyList(),
    )

    // ---- Remote: the television's companion server, core's. Another device on the same Wi-Fi
    // opens the address, enters the PIN, and downloads this phone's backup or sends one to restore. ----

    /** Idle / Starting / Listening with the PIN and QR / Failed / Locked, for the Remote sheets. */
    val remoteState: StateFlow<CompanionServerState> get() = companion.state

    /** Backups sent from another device; each one goes into [beginRestore]. */
    val remoteBackups: Flow<File> get() = companion.backups

    /** The backup is being written before the Remote server opens to serve it. */
    var preparingRemote by mutableStateOf(false)
        private set

    fun startRemoteRestore() = companion.startForBackupRestore(CompanionLink.DEFAULT_PORT)

    fun stopRemote() = companion.stop()

    /** Writes the backup into the companion's folder, then serves it for another device to download. */
    fun exportRemote(sections: Set<BackupManager.Section>, password: String?) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            preparingRemote = true
            val profileIds = exportedProfileIds()
            if (profileIds == null) {
                outcome = Outcome.Failed(R.string.settings_backup_export_error)
            } else {
                backup.export(companion.backupExportDir, sections, password, profileIds).fold(
                    onSuccess = { path -> companion.startForBackupDownload(CompanionLink.DEFAULT_PORT, File(path)) },
                    onFailure = { outcome = Outcome.Failed(R.string.settings_backup_export_error) },
                )
            }
            preparingRemote = false
            busy = false
        }
    }

    /** Writes the backup into the cache for the system share sheet; [onReady] gets the file to share. */
    fun exportForShare(sections: Set<BackupManager.Section>, password: String?, onReady: (File) -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            val profileIds = exportedProfileIds()
            if (profileIds == null) {
                outcome = Outcome.Failed(R.string.settings_backup_export_error)
                busy = false
                return@launch
            }
            // One shared backup at a time: the last one is gone by the time the next is written.
            val dir = File(context.cacheDir, SHARE_DIR).apply { deleteRecursively(); mkdirs() }
            backup.export(dir, sections, password, profileIds).fold(
                onSuccess = { path -> onReady(File(path)) },
                onFailure = { outcome = Outcome.Failed(R.string.settings_backup_export_error) },
            )
            busy = false
        }
    }

    /**
     * What to write once the save picker returns: the user has already said what to export, and the
     * picker only decides where it goes. Held here rather than in the page, so an activity recreated
     * while the picker is in front still writes the file. In memory only — it carries the password.
     */
    internal var pendingFileExport: ExportRequest? = null

    /** No app on this phone can answer the save picker. */
    fun reportNoFileApp() {
        pendingFileExport = null
        outcome = Outcome.Failed(R.string.settings_backup_no_file_app)
    }

    /** The picked document could not even be copied out of its provider — nothing to inspect. */
    fun reportReadFailure() {
        outcome = Outcome.Failed(R.string.settings_backup_read_error)
    }

    fun dismissOutcome() {
        outcome = null
    }

    fun cancelRestore() {
        pending?.file?.delete()
        pending = null
    }

    /** Writes the backup into the cache, then into [target]; the cached copy never survives the call. */
    fun export(
        target: Uri,
        name: String,
        sections: Set<BackupManager.Section>,
        password: String?,
    ) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            val profileIds = exportedProfileIds()
            val staged = File(context.cacheDir, EXPORT_DIR)
            val result: Result<Unit> = when (profileIds) {
                null -> Result.failure(IllegalStateException("no active profile"))
                else -> backup.export(staged, sections, password, profileIds)
                    .mapCatching { path -> copyOut(File(path), target) }
            }
            staged.deleteRecursively()
            outcome = result.fold(
                onSuccess = { Outcome.Exported(name, passwordsOmitted = password.isNullOrBlank()) },
                onFailure = { Outcome.Failed(R.string.settings_backup_export_error) },
            )
            busy = false
        }
    }

    /**
     * The one profile a backup of this app may carry, or null when the preference still names none —
     * which cannot happen in a running app, and must never fall back to core's "null means every
     * profile in the database", because those others are exactly what this app stopped managing.
     */
    private suspend fun exportedProfileIds(): Set<Long>? =
        settings.activeProfileId.first().takeIf { it >= 0 }?.let { setOf(it) }

    /** Step one of a restore: what is in this file, and does it need a password before anyone can look. */
    fun beginRestore(file: File) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            // The device side of the name question is read once, here, and travels with the file: the
            // sheet's notice and the restore's re-check must be about the same profile.
            val activeId = settings.activeProfileId.first()
            val stored = profileDao.getAllOnce()
            val device = stored.firstOrNull { it.id == activeId }
            val otherNames = stored.filter { it.id != activeId }.map { it.name }
            if (backup.isSealed(file)) {
                // A sealed container gives up no names yet. The refusal waits for the password.
                pending = PendingRestore(file = file, sealed = true, deviceProfile = device, otherNames = otherNames)
            } else {
                val summary = BackupProfiles.read(file)
                val reason = backupRefusalRes(earlyBlock(summary))
                if (reason != null) {
                    outcome = Outcome.Refused(reason)
                    file.delete()
                } else {
                    backup.sectionsIn(file).fold(
                        onSuccess = {
                            pending = PendingRestore(
                                file = file,
                                inspection = it,
                                summary = summary,
                                deviceProfile = device,
                                otherNames = otherNames,
                            )
                        },
                        onFailure = {
                            outcome = Outcome.Failed(R.string.settings_backup_read_error)
                            file.delete()
                        },
                    )
                }
            }
            busy = false
        }
    }

    /** The password for a sealed file, which is the only thing that can reveal what is inside it. */
    fun unseal(password: String) {
        val current = pending ?: return
        if (busy) return
        viewModelScope.launch {
            busy = true
            backup.sectionsIn(current.file, password).fold(
                onSuccess = { inspection ->
                    val summary = BackupProfiles.read(current.file, password)
                    val reason = backupRefusalRes(earlyBlock(summary))
                    if (reason == null) {
                        pending = current.copy(
                            inspection = inspection,
                            password = password,
                            wrongPassword = false,
                            summary = summary,
                        )
                    } else {
                        // Opened, and it is this app that cannot hold what is inside it: the password was
                        // right, so the file goes back the way it came with an instruction, not an error.
                        outcome = Outcome.Refused(reason)
                        current.file.delete()
                        pending = null
                    }
                },
                onFailure = { pending = current.copy(wrongPassword = true) },
            )
            busy = false
        }
    }

    /**
     * What restoring [sections] would do to this device's profile. The sheet asks again on every tick,
     * because unticking Sources is what turns an announced rename into no rename at all.
     */
    fun decisionFor(sections: Set<BackupManager.Section>): BackupProfiles.Decision {
        val current = pending ?: return BackupProfiles.Decision()
        return BackupProfiles.decide(current.summary, current.deviceProfile, current.otherNames, sections)
    }

    /**
     * Restore [sections]. [password] may be null even for an encrypted file: everything but the
     * saved passwords comes back, which is a fair trade for a forgotten passphrase.
     */
    fun restore(sections: Set<BackupManager.Section>, password: String?, deviceSettings: Boolean = false) {
        val current = pending ?: return
        if (busy) return
        viewModelScope.launch {
            busy = true
            // Worked out again rather than trusted from the sheet: the user may have been looking at a
            // decision made before the last tick, and a rename must never ride on a stale one.
            val decision = decisionFor(sections)
            val reason = backupRefusalRes(decision.block)
            if (reason != null) {
                outcome = Outcome.Refused(reason)
                current.file.delete()
                pending = null
                busy = false
                return@launch
            }
            val incoming = decision.renameTo
            val device = current.deviceProfile
            // Renamed before anything is applied, and the row it was beforehand is kept for the rollback.
            val before = if (incoming != null && device != null) {
                BackupProfiles.renameDeviceProfile(profileDao, device, incoming)
                device
            } else null
            try {
                backup.import(current.file, sections, password ?: current.password, deviceSettings = deviceSettings).fold(
                    onSuccess = {
                        outcome = Outcome.Restored(it)
                        current.file.delete()
                        pending = null
                    },
                    onFailure = { failure ->
                        restoreName(before)
                        if (failure is BackupManager.WrongPasswordException) {
                            pending = current.copy(wrongPassword = true)
                        } else {
                            outcome = Outcome.Failed(R.string.settings_backup_import_error)
                            current.file.delete()
                            pending = null
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                // Leaving the screen mid-restore must not leave the profile answering to a name whose
                // data never arrived.
                restoreName(before)
                throw cancelled
            }
            busy = false
        }
    }

    /**
     * [BackupProfiles.decide] run with nothing but the file, for the two refusals that no choice of
     * sections can undo: a file with more than one profile, and a profile with no name at all.
     */
    private fun earlyBlock(summary: BackupProfiles.Summary?): BackupProfiles.Block? =
        BackupProfiles.decide(summary, null, emptyList(), emptySet()).block

    /** Puts the profile's own name back after a restore that did not happen. Uncancellable by design. */
    private suspend fun restoreName(original: ProfileEntity?) {
        if (original == null) return
        withContext(NonCancellable) { BackupProfiles.restoreProfileName(profileDao, original) }
    }

    private suspend fun copyOut(source: File, target: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(target)?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: error("backup target unavailable")
    }

    private companion object {
        const val EXPORT_DIR = "backup-export"

        /** Under the cache; the only folder `res/xml/share_paths.xml` lets the share sheet read. */
        const val SHARE_DIR = "backup-share"
    }
}

/**
 * Why a file is being refused, in the words that tell the user what to change on the device that made
 * it. One table for the sheet's inline reason and the outcome sheet's, so the two never disagree.
 */
@StringRes
internal fun backupRefusalRes(block: BackupProfiles.Block?): Int? = when (block) {
    BackupProfiles.Block.MULTIPLE -> R.string.mobile_multi_profile_import
    BackupProfiles.Block.UNNAMED -> R.string.mobile_nameless_profile_import
    BackupProfiles.Block.HIDDEN -> R.string.mobile_hidden_profile_import
    null -> null
}
