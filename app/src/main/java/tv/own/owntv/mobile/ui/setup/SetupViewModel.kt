package tv.own.owntv.mobile.ui.setup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.backup.BackupManager
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.settings.PlaylistRefresh
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.setup.SourceImporter
import tv.own.owntv.core.sync.SyncScopeChoice
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.backup.BackupProfiles
import java.io.File

/**
 * Adding a playlist, or restoring a backup, on the phone.
 *
 * The sequence itself — add, sync, finalize, and undo it all when it fails — is core's
 * [SourceImporter], shared with the television. What is this app's own is the job the import runs in
 * and the profile it attaches to: on a phone that is already set up there is an active profile to
 * add to, and only a first run creates one.
 *
 * The import runs in [appScope], not in this ViewModel's own scope. "Run in background" leaves the
 * wizard, and leaving it here means popping a navigation entry — which clears the ViewModel that
 * entry owns and would cancel the very sync the button promised to keep running. The job is still
 * cancelled explicitly by Cancel; it is only the screen going away that no longer stops it.
 */
class SetupViewModel(
    private val importer: SourceImporter,
    private val profileDao: ProfileDao,
    private val settings: SettingsRepository,
    private val appScope: CoroutineScope,
    private val context: Context,
    private val companion: tv.own.owntv.core.companion.CompanionController,
    private val profiles: tv.own.owntv.core.profile.ProfileManager,
) : ViewModel() {

    // ---- Remote: another device fills the form in a browser; the phone pre-fills its own form. ----
    val remoteState get() = companion.state
    /** The last submission, kept until the form has read it. */
    val remotePayload get() = companion.lastPayload
    fun startRemote() = companion.start(tv.own.owntv.core.companion.CompanionLink.DEFAULT_PORT)
    fun stopRemote() = companion.stop()
    fun consumeRemotePayload() = companion.consumePayload()

    val state = importer.state
    val progress = importer.progress

    private var importJob: Job? = null

    fun startXtream(
        name: String,
        server: String,
        username: String,
        password: String,
        userAgent: String,
        httpReferer: String,
        autoRefresh: PlaylistRefresh,
        live: SyncScopeChoice,
        movies: SyncScopeChoice,
        series: SyncScopeChoice,
        preferHls: Boolean,
    ) = runImport {
        importer.xtream(
            name = name, server = server, username = username, password = password,
            userAgent = userAgent, httpReferer = httpReferer, autoRefresh = autoRefresh,
            live = live, movies = movies, series = series, preferHls = preferHls,
        )
    }

    fun startM3u(name: String, url: String, userAgent: String, httpReferer: String, autoRefresh: PlaylistRefresh) =
        runImport {
            importer.m3u(name = name, url = url, userAgent = userAgent, httpReferer = httpReferer, autoRefresh = autoRefresh)
        }

    fun startStalker(
        name: String,
        portalUrl: String,
        mac: String,
        serialNumber: String,
        deviceId: String,
        deviceId2: String,
        signature: String,
        userAgent: String,
        httpReferer: String,
        autoRefresh: PlaylistRefresh,
        live: SyncScopeChoice,
        movies: SyncScopeChoice,
        series: SyncScopeChoice,
        login: String = "",
        password: String = "",
    ) = runImport {
        importer.stalker(
            name = name, portalUrl = portalUrl, mac = mac, serialNumber = serialNumber,
            deviceId = deviceId, deviceId2 = deviceId2, signature = signature, userAgent = userAgent,
            httpReferer = httpReferer, autoRefresh = autoRefresh, live = live, movies = movies, series = series,
            login = login, password = password,
        )
    }

    /**
     * Restore [sections] from a backup file; an encrypted one asks for its password first.
     *
     * [sections] defaults to all of it, which is what this did unconditionally before the wizard
     * gained a tick-list of its own.
     *
     * A file this app cannot restore safely never reaches core: the wizard has no section-by-section
     * preview to explain a rename with, and no way to undo one part-way through, so it refuses and
     * says why in [blocked] instead.
     */
    fun importBackup(
        file: File,
        sections: Set<BackupManager.Section> = BackupManager.Section.entries.toSet(),
        /** Take another device's hardware settings too — see [BackupManager.import]. */
        deviceSettings: Boolean = false,
    ) {
        importJob?.cancel()
        importJob = appScope.launch {
            if (!refuseInSetup(file, null, sections)) importer.importBackup(file, sections, deviceSettings)
        }
    }

    fun restoreWithPassword(
        file: File,
        password: String?,
        sections: Set<BackupManager.Section> = BackupManager.Section.entries.toSet(),
        deviceSettings: Boolean = false,
    ) {
        importJob?.cancel()
        importJob = appScope.launch {
            // The sealed file could not be read before the password arrived, so this is the first
            // real look at what is inside it.
            if (!refuseInSetup(file, password, sections)) importer.restoreWithPassword(file, password, sections, deviceSettings)
        }
    }

    private val _blocked = MutableStateFlow<Int?>(null)

    /** Why the last backup was refused, as a message; null when nothing is being refused. */
    val blocked: StateFlow<Int?> = _blocked.asStateFlow()

    fun dismissBlocked() { _blocked.value = null }

    // ---- The "New profile" page's answers, kept until the flow knows it will end ----

    /** The answers the page collected; null when the user did not take that path. */
    var profileDraft: ProfileDraft? = null
        private set
    /** The row [profileDraft] was written as, once something needed it (an import, or the end). */
    private var draftProfileId: Long? = null

    /**
     * Kept as a draft rather than written here: making the row and switching to it would give the
     * shell an active profile, and the shell replaces the wizard the moment one exists — before any
     * content could be added.
     */
    fun setProfileDraft(draft: ProfileDraft) {
        profileDraft = draft
    }

    /** The drafted profile's id, creating it on first use; null when there is no draft. */
    private suspend fun materialiseDraft(): Long? {
        val draft = profileDraft ?: return null
        draftProfileId?.let { return it }
        // avatarId -1 leaves the drawn face empty, so the shell shows the name's initial.
        val id = profiles.create(
            name = draft.name,
            avatarId = -1,
            isKids = false,
            pin = draft.pin,
            fallbackName = draft.name,
        )
        draftProfileId = id
        saveDraftAvatar(id, draft.picture)
        return id
    }

    /** Copies the page's chosen photo into app-private storage, if one was chosen and still reads. */
    private suspend fun saveDraftAvatar(profileId: Long, picture: String?) {
        val uri = Uri.parse(picture ?: return)
        val profile = profileDao.getById(profileId) ?: return
        val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull() ?: return
        stream.use { profiles.setCustomAvatar(profile, it) }
    }

    /**
     * @return true when the backup was refused — core is then not called, and the picked file stays
     * where it is so the same one can be restored from Settings, where the rename can be confirmed.
     */
    private suspend fun refuseInSetup(file: File, password: String?, sections: Set<BackupManager.Section>): Boolean {
        val onDevice = profileDao.getAllOnce()
        val active = settings.activeProfileIdNow()
        val decision = BackupProfiles.decide(
            summary = BackupProfiles.read(file, password),
            deviceProfile = onDevice.firstOrNull { it.id == active },
            otherNames = onDevice.filterNot { it.id == active }.map { it.name },
            sections = sections,
        )
        val reason = when (decision.block) {
            BackupProfiles.Block.MULTIPLE -> R.string.mobile_multi_profile_import
            BackupProfiles.Block.UNNAMED -> R.string.mobile_nameless_profile_import
            BackupProfiles.Block.HIDDEN -> R.string.mobile_hidden_profile_import
            // A different name means the restore would rename this device's profile. Settings can ask
            // first; the wizard cannot, so it sends the user there.
            null -> if (decision.renameTo != null) R.string.mobile_restore_from_settings else null
        } ?: return false
        _blocked.value = reason
        return true
    }

    /**
     * "Run in background": enter the app now and let the import finish on its own. It deliberately
     * does not cancel — cancelling would undo the playlist the user just added — and the sync pill
     * on the shell is what tells them it is still going.
     */
    fun continueInBackground(onDone: (Long?) -> Unit) {
        importer.backgroundHandoff = true
        finish(onDone)
    }

    /**
     * Ends the flow: the profile the content landed on becomes the active one, and its id is handed
     * back so the shell knows who to show.
     *
     * The profile is the one a restore brought in, the one [attachToProfile] made before the import,
     * or the draft the "New profile" page collected — which is written now, at the end, when
     * switching to it can no longer cut the rest of setup short. A blank run still makes the
     * default profile, because the app needs one to scope favorites and history against.
     *
     * The id is not an unlock. If the profile that landed carries a PIN, the shell's app lock asks for
     * it; passing a session token from here would be the wizard letting itself in.
     */
    fun finish(onDone: (Long?) -> Unit) {
        appScope.launch {
            val active = settings.activeProfileIdNow()
            val existing = if (active >= 0) {
                active
            } else {
                materialiseDraft() ?: profileDao.getAllOnce().firstOrNull()?.id
            }
            if (existing != null) {
                if (existing != active) profiles.switchTo(existing)
                onMain { onDone(existing) }
                return@launch
            }
            // Skipped everything, so nothing ran and no profile was made: the app still needs one to
            // scope favorites and history against.
            val id = profiles.create(
                name = context.getString(R.string.setup_default_profile),
                avatarId = 0,
                isKids = false,
                pin = null,
                fallbackName = context.getString(R.string.setup_default_profile),
            )
            profiles.switchTo(id)
            onMain { onDone(id) }
        }
    }

    /**
     * Hands a wizard callback back to the main thread.
     *
     * [appScope] is a background scope — it has to be, so "Run in background" survives the screen
     * going away — but every one of these callbacks ends up moving the user somewhere, and
     * navigation must be touched on the main thread or `setCurrentState` throws and takes the whole
     * process with it. That is what closed the app on "OK" after an import.
     */
    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    fun reset() = importer.reset()

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        importer.reset()
    }

    private fun runImport(block: suspend () -> Unit) {
        importJob?.cancel()
        val job = appScope.launch {
            attachToProfile()
            block()
        }
        importJob = job
        job.invokeOnCompletion { if (importJob == job) importJob = null }
    }

    /**
     * The profile the new playlist belongs to. An app that has been used before already has one,
     * and adding a second playlist must not quietly create a second profile to hang it on; the
     * "New profile" draft comes next, and only a genuinely empty install makes the default one.
     */
    private suspend fun attachToProfile() {
        val existing = settings.activeProfileIdNow().takeIf { it >= 0 }
            ?: materialiseDraft()
            ?: profileDao.getAllOnce().firstOrNull()?.id
        if (existing != null) {
            importer.useProfile(existing)
        } else {
            importer.createProfile(
                name = context.getString(R.string.setup_default_profile),
                avatarId = 0,
                isKids = false,
                pin = null,
            )
        }
    }
}

/**
 * What the Wizard's "New profile" page collects: a name, an optional PIN, and an optional picture as
 * a `content://` address (kept as a string so [androidx.compose.runtime.saveable.rememberSaveable]
 * can hold it across configuration changes).
 */
class ProfileDraft(val name: String, val pin: String?, val picture: String?)
