package tv.own.owntv.mobile.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.backup.BackupManager
import tv.own.owntv.core.companion.CompanionServerState
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sync.local.DiscoveredDevice
import tv.own.owntv.core.sync.local.LocalSyncManager
import tv.own.owntv.core.sync.local.PairedDevice
import tv.own.owntv.core.sync.local.SyncDirection
import tv.own.owntv.core.sync.local.SyncFailure
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.backup.BackupProfiles

/**
 * Local sync, from the phone's side.
 *
 * Two roles, and the user picks which one this device is playing. **Hosting** puts a PIN and a QR
 * code on screen and waits — that is what the television scans or types. **Connecting** goes the
 * other way: the phone finds the television, is paired once, and from then on can send to it,
 * receive from it, or do both.
 *
 * Nothing is ever applied without being shown first. A received file sits in the cache until the
 * user has seen what it would change and said yes, because the one outcome worth engineering
 * against is somebody tapping the wrong direction and losing a household's watch history.
 *
 * This app keeps one profile, so every container it writes — hosted, sent, or sent back after a merge
 * — carries that profile and nothing else. What arrives is checked against it before anybody is
 * offered a confirm sheet: more than one profile, or a nameless one, is refused outright, and a
 * different name is announced as a rename of this device's profile, undone if the merge fails.
 */
class LocalSyncViewModel(
    private val sync: LocalSyncManager,
    private val settings: SettingsRepository,
    private val profileDao: ProfileDao,
) : ViewModel() {

    val paired: StateFlow<List<PairedDevice>> = sync.pairedDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val hosting: StateFlow<CompanionServerState> = sync.hostState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CompanionServerState.Idle)

    val deviceName: String get() = sync.deviceName

    /** Devices answering on the network right now, while the pairing sheet is open. */
    var found by mutableStateOf<List<DiscoveredDevice>>(emptyList())
        private set

    /** The step the screen is on. Everything the user is deciding lives here, not in the composable. */
    var step by mutableStateOf<Step?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf<SyncFailure?>(null)
        private set

    /**
     * Why an incoming container was refused before anyone was asked to confirm it — a `R.string` of the
     * "change it on the other device" family. Nothing was applied and the cached copy is gone.
     */
    var blocked by mutableStateOf<Int?>(null)
        private set

    sealed interface Step {
        /** Choosing which device to pair with — discovery, a scanned QR, or a typed address. */
        data object FindDevice : Step

        /** The address is known; the PIN on the other device's screen is what is missing. */
        data class EnterPin(val address: String, val port: Int) : Step

        /** Paired. Which way should the data go? */
        data class ChooseDirection(val device: PairedDevice) : Step

        /** Which parts to merge. No password is asked for — the two devices agree a key themselves. */
        data class ChooseSections(val device: PairedDevice, val direction: SyncDirection) : Step

        /**
         * The dry run: what this device would gain and lose. Nothing has been written yet — [file] is
         * sitting in the cache and is only applied when the user confirms.
         *
         * [renameTo] is the one thing a merge does to this device's own profile: the incoming one is
         * called that, and since this app shows a single profile, matching names is the only way the
         * data can land on it. [originalProfile] is this device's row as it stands, kept so a merge
         * that fails can put the name back.
         */
        data class Confirm(
            val device: PairedDevice?,
            val direction: SyncDirection,
            val file: File,
            val preview: BackupManager.Preview,
            val sections: Set<BackupManager.Section>,
            val password: String?,
            val renameTo: String? = null,
            val originalProfile: ProfileEntity? = null,
        ) : Step

        /** What happened, once it has. */
        data class Result(val received: BackupManager.ImportSummary?, val sent: Boolean) : Step
    }

    private var discovery: Job? = null
    private var incomingWatcher: Job? = null

    // --- hosting ---------------------------------------------------------------------------------

    /**
     * Starts listening so the television can reach this phone, and watches for a container arriving.
     * Nothing that lands is applied — it becomes a dry run the user confirms, exactly as a pull does.
     */
    fun startHosting() {
        busy = true
        viewModelScope.launch {
            // Only this device's own profile goes out on the wire, and a phone with no profile has
            // nothing worth serving — hosting an empty container would only be something for the other
            // device to find and merge by mistake.
            val ids = activeProfileIds()
            if (ids == null) error = SyncFailure.Unknown
            else sync.startHosting(profileIds = ids).onFailure { error = SyncFailure.Unknown }
            busy = false
        }
        // Guarded: hosting can be switched off and on again, and a second collector would preview the
        // same arriving container twice.
        if (incomingWatcher?.isActive != true) incomingWatcher = viewModelScope.launch {
            sync.incoming.collect { file ->
                val payload = sync.previewIncoming(file).getOrNull() ?: return@collect
                val device = activeProfile()
                // Everything was offered, so everything is what the name is matched against; unticking
                // Sources on the sheet is what would take the rename back out of it.
                val decision = incomingDecision(payload.file, payload.password, device, BackupManager.Section.entries.toSet())
                    ?: return@collect
                step = Step.Confirm(
                    device = null,
                    direction = SyncDirection.RECEIVE,
                    file = payload.file,
                    preview = payload.preview,
                    sections = BackupManager.Section.entries.toSet(),
                    password = payload.password,
                    renameTo = decision.renameTo,
                    originalProfile = device,
                )
            }
        }
    }

    fun stopHosting() = sync.stopHosting()

    // --- pairing ---------------------------------------------------------------------------------

    fun beginPairing() {
        step = Step.FindDevice
        found = emptyList()
        discovery?.cancel()
        discovery = viewModelScope.launch {
            sync.discover().collect { device ->
                if (found.none { it.address == device.address }) found = found + device
            }
        }
    }

    /** From the discovery list, a scanned QR code, or a typed address — all three land here. */
    fun chooseAddress(address: String, port: Int) {
        discovery?.cancel()
        step = Step.EnterPin(address, port)
    }

    /**
     * The pairing this discovered device already has here, or null.
     *
     * Its announced id first, because that is the thing that does not change; the address only as a
     * fallback for a device too old to announce one, where a router handing out a new lease would
     * make a known device look new.
     */
    fun pairedMatch(device: DiscoveredDevice): PairedDevice? {
        val known = paired.value
        return device.deviceId.takeIf { it.isNotBlank() }?.let { id -> known.firstOrNull { it.id == id } }
            ?: known.firstOrNull { it.address == device.address }
    }

    /**
     * Picking a device out of the found list. One that is already paired goes straight to its actions:
     * asking for a PIN again would be asking the user to prove something this device already knows.
     */
    fun choose(device: DiscoveredDevice) {
        val existing = pairedMatch(device)
        if (existing == null) {
            chooseAddress(device.address, device.port)
        } else {
            discovery?.cancel()
            step = Step.ChooseDirection(existing)
        }
    }

    fun submitPin(pin: String) {
        val current = step as? Step.EnterPin ?: return
        busy = true
        viewModelScope.launch {
            sync.pair(current.address, current.port, pin)
                .onSuccess { step = Step.ChooseDirection(it) }
                .onFailure { error = SyncFailure.NotAuthorized }
            busy = false
        }
    }

    fun unpair(device: PairedDevice) {
        viewModelScope.launch { sync.unpair(device.id) }
    }

    // --- syncing ---------------------------------------------------------------------------------

    fun chooseDevice(device: PairedDevice) {
        step = Step.ChooseDirection(device)
    }

    fun chooseDirection(direction: SyncDirection) {
        val device = (step as? Step.ChooseDirection)?.device ?: return
        step = Step.ChooseSections(device, direction)
    }

    /**
     * Does the half of the job that changes nothing: a send goes straight out (the other device is
     * the one merging, and a merge cannot destroy anything), while anything that would change THIS
     * device is fetched and previewed first.
     */
    fun start(sections: Set<BackupManager.Section>) {
        val current = step as? Step.ChooseSections ?: return
        busy = true
        viewModelScope.launch {
            when (current.direction) {
                SyncDirection.SEND -> {
                    val ids = activeProfileIds()
                    if (ids == null) error = SyncFailure.Unknown
                    else sync.send(current.device, sections, ids)
                        .onSuccess { step = Step.Result(received = null, sent = true) }
                        .onFailure { error = failure(it) }
                }
                SyncDirection.RECEIVE, SyncDirection.MERGE -> {
                    val device = activeProfile()
                    sync.fetch(current.device, sections)
                        .onSuccess { payload ->
                            val decision = incomingDecision(payload.file, payload.password, device, sections)
                            if (decision != null) {
                                step = Step.Confirm(
                                    current.device, current.direction, payload.file, payload.preview, sections, payload.password,
                                    renameTo = decision.renameTo, originalProfile = device,
                                )
                            }
                        }
                        .onFailure { error = failure(it) }
                }
            }
            busy = false
        }
    }

    /** The user has seen what will change and said yes. */
    fun confirm(deviceSettings: Boolean = false) {
        val current = step as? Step.Confirm ?: return
        busy = true
        viewModelScope.launch {
            // [deviceSettings]: the other device's hardware settings were ticked on the confirm sheet.
            // The name is settled first, because matching it is the only way a merge can land the
            // incoming profile on this device's one row instead of creating a second it cannot show.
            // What this device's row was beforehand is kept for the rollback — and only when a rename
            // really happened, so a merge that changed the name itself is never undone here.
            val original = current.originalProfile
            val renameTo = current.renameTo
            val previous = if (original != null && renameTo != null) {
                BackupProfiles.renameDeviceProfile(profileDao, original, renameTo)
                original
            } else null
            val applied = try {
                sync.apply(current.file, current.sections, current.password, deviceSettings)
            } catch (cancelled: CancellationException) {
                // Walking away mid-merge must not leave the profile answering to a name whose data
                // never landed.
                restoreName(previous)
                throw cancelled
            }
            if (applied.isFailure) restoreName(previous)
            applied
                .onSuccess { summary ->
                    // A merge sends this device's own data back once the incoming half has landed,
                    // so both ends finish holding the same thing rather than one being a round behind.
                    val ids = activeProfileIds()
                    val sent = current.device != null && current.direction == SyncDirection.MERGE && ids != null &&
                        sync.send(current.device, current.sections, ids).isSuccess
                    step = Step.Result(received = summary, sent = sent)
                }
                .onFailure { error = failure(it) }
            busy = false
        }
    }

    fun cancel() {
        (step as? Step.Confirm)?.file?.delete()
        discovery?.cancel()
        step = null
        sync.clearProgress()
    }

    fun dismissError() {
        error = null
    }

    fun dismissBlocked() {
        blocked = null
    }

    // --- the one profile ----------------------------------------------------------------------------

    /** The profile this app opens with, or null while the preference still names none. */
    private suspend fun activeProfile(): ProfileEntity? = profileDao.getById(settings.activeProfileId.first())

    /** What this device may put on the wire: its own profile, never the dormant rows behind it. */
    private suspend fun activeProfileIds(): Set<Long>? = activeProfile()?.id?.let { setOf(it) }

    /**
     * Whether a container that just arrived can be applied to this device's single profile.
     *
     * Null is a refusal: the reason is on screen as [blocked] and the cached copy has been deleted, so
     * nothing downstream can offer it by mistake. A refused transfer destroys nothing — the data stays
     * on the device it came from, where its profile can still be renamed or its crowd of profiles
     * thinned.
     */
    private suspend fun incomingDecision(
        file: File,
        password: String?,
        device: ProfileEntity?,
        sections: Set<BackupManager.Section>,
    ): BackupProfiles.Decision? {
        val dormant = device?.id?.let { id -> profileDao.getAllOnce().filter { it.id != id }.map { it.name } }.orEmpty()
        val decision = BackupProfiles.decide(BackupProfiles.read(file, password), device, dormant, sections)
        val reason = syncRefusalRes(decision.block)
        if (reason != null) {
            blocked = reason
            file.delete()
            return null
        }
        return decision
    }

    /** Puts this device's own profile name back after a merge that did not happen. Uncancellable. */
    private suspend fun restoreName(original: ProfileEntity?) {
        if (original == null) return
        withContext(NonCancellable) { BackupProfiles.restoreProfileName(profileDao, original) }
    }

    override fun onCleared() {
        discovery?.cancel()
        sync.stopHosting()
        super.onCleared()
    }

    /**
     * Always logs. The reason used to be logged only when the manager had *not* already classified
     * the failure — but the manager classifies every one of them before returning, so the elvis was
     * never reached and a failed sync left no trail at all. A user reporting "it just says something
     * went wrong" could be answered with nothing.
     */
    private fun failure(t: Throwable): SyncFailure {
        val reason = (sync.progress.value as? tv.own.owntv.core.sync.local.SyncProgress.Failed)?.reason
            ?: SyncFailure.Unknown
        android.util.Log.w("LocalSyncViewModel", "Local sync failed: $reason", t)
        return reason
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * Why a sync was refused, phrased for a transfer rather than a file: the fix is on the other device, in
 * the profile that was sent. Kept beside the view model that raises it and read by the sheet that shows
 * it, so the two cannot disagree about what was refused.
 */
@StringRes
internal fun syncRefusalRes(block: BackupProfiles.Block?): Int? = when (block) {
    BackupProfiles.Block.MULTIPLE -> R.string.mobile_multi_profile_sync
    BackupProfiles.Block.UNNAMED -> R.string.mobile_nameless_profile_sync
    BackupProfiles.Block.HIDDEN -> R.string.mobile_hidden_profile_sync
    null -> null
}
