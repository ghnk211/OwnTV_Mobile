package tv.own.owntv.mobile.backup

import tv.own.owntv.core.backup.BackupContainer
import tv.own.owntv.core.backup.BackupManager
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.ProfileEntity
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject

/**
 * Whose profile a backup or a sync container carries, and what this app may do about it.
 *
 * This app keeps one profile: the one it opens with. Core's backup format has always carried a
 * `profiles` array and matches rows by name, so a file is still written and read exactly as the
 * television writes and reads it — nothing here changes the format or the protocol. What changes is
 * what the phone accepts: a file holding three profiles cannot be poured into an app with one, and a
 * file holding one profile under a different name would silently create a *second* profile on this
 * device and leave it unreachable, because there is no profile switcher left to reach it with.
 *
 * So the name is settled before anything is applied, one of three ways:
 *
 * - **It matches** (case- and space-insensitively, as core's own import matches it) → restore.
 * - **It differs** → this device's profile is renamed to the incoming name first, the file is applied,
 *   and the rename is rolled back if the apply fails. Renaming the one profile the user can see beats
 *   refusing the file outright, which is what the television would do and what a phone with no
 *   profile editor cannot undo. It is also why the rename is *announced* on the sheet before the user
 *   confirms, and why Setup — which cannot show that prompt or roll anything back — refuses instead.
 * - **It collides with a dormant profile** (a row this app still holds but never shows) → refused:
 *   renaming would silently relabel a profile whose favorites and history are still on this device,
 *   and the user has no way to tell the two apart afterwards.
 *
 * [read] is deliberately separate from [decide], and [decide] takes plain values rather than flows:
 * the policy is the part worth testing, and it stays testable by staying free of Android.
 */
object BackupProfiles {

    /** How many profiles a container carries, and the name of the first one when it carries any. */
    data class Summary(val count: Int, val name: String?)

    /** Why a container cannot be applied as it stands. */
    enum class Block {

        /** More than one profile — there is nothing on this device to hold them. */
        MULTIPLE,

        /** The incoming name belongs to a dormant profile still on this device. */
        HIDDEN,

        /** The profile has no name, so it cannot be matched to this device's. */
        UNNAMED,
    }

    /** A refusal, a rename the user must be told about, or neither. Never both. */
    data class Decision(val block: Block? = null, val renameTo: String? = null)

    /**
     * The `profiles` array of [file], without applying or even fully parsing anything else.
     *
     * Null means "could not be read at all" — a wrong password, a truncated file, a format this build
     * does not know. That answers as *allow*, because this is a guard against a shape the app cannot
     * hold, not a validator of core's files: [BackupManager] reads the same bytes with more context and
     * reports a genuinely broken file better than a name count ever could.
     */
    fun read(file: File, password: String? = null): Summary? = runCatching {
        val json = when (BackupContainer.probe(file)) {
            BackupContainer.Kind.ENCRYPTED_CONTAINER -> BackupContainer.open(file, password).json
            // A plain container: the JSON entry alone, streamed out of the ZIP — the wallpaper and the
            // avatar bytes have nothing to say about who the file belongs to.
            BackupContainer.Kind.CONTAINER -> ZipFile(file).use { zip ->
                zip.getEntry(BackupContainer.JSON_ENTRY)?.let { entry ->
                    zip.getInputStream(entry).bufferedReader().readText()
                }
            }
            BackupContainer.Kind.LEGACY_JSON -> file.readText()
            BackupContainer.Kind.UNKNOWN -> null
        } ?: return@runCatching null

        // No array at all is a pre-v11 or settings-only file: zero profiles, which is restorable.
        val array = JSONObject(json).optJSONArray("profiles") ?: return@runCatching Summary(0, null)
        Summary(
            count = array.length(),
            name = (array.optJSONObject(0)?.opt("name") as? String)?.takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    /**
     * What to do about [summary] on a device whose profile is [deviceProfile].
     *
     * [otherNames] are the names of the rows this device still holds but does not show — the profiles
     * left behind by the older multi-profile builds. [sections] matters because only a container that
     * restores [BackupManager.Section.SOURCES] can bring its profile row along: core applies profiles
     * with the sources, so unticking Sources means nothing is renamed and nothing is created.
     */
    fun decide(
        summary: Summary?,
        deviceProfile: ProfileEntity?,
        otherNames: List<String>,
        sections: Set<BackupManager.Section>,
    ): Decision {
        if (summary == null) return Decision()
        if (summary.count > 1) return Decision(block = Block.MULTIPLE)
        if (summary.count != 1) return Decision()
        val incoming = summary.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Decision(block = Block.UNNAMED)
        // A device with no profile has nothing to rename and nothing to collide with.
        if (deviceProfile == null) return Decision()
        if (BackupManager.Section.SOURCES !in sections) return Decision()

        val key = incoming.lowercase()
        val matchesDormant = otherNames.any { it.trim().lowercase() == key }
        return when {
            matchesDormant -> Decision(block = Block.HIDDEN)
            key == deviceProfile.name.trim().lowercase() -> Decision()
            else -> Decision(renameTo = incoming)
        }
    }

    /**
     * Renames [profile] to [name] and returns the row as it now stands, for the rollback to put back.
     *
     * Re-read rather than renamed from the copy the caller captured: a whole-row [ProfileDao.update]
     * off a stale entity would overwrite a PIN set while the sheet sat open, and the PIN is the one
     * field nobody may lose quietly.
     */
    suspend fun renameDeviceProfile(profileDao: ProfileDao, profile: ProfileEntity, name: String): ProfileEntity {
        val current = profileDao.getById(profile.id) ?: profile
        val renamed = current.copy(name = name.trim())
        profileDao.update(renamed)
        return renamed
    }

    /** Puts [original]'s name back on this device's profile. The only thing a failed rename leaves behind. */
    suspend fun restoreProfileName(profileDao: ProfileDao, original: ProfileEntity) {
        val current = profileDao.getById(original.id) ?: return
        if (current.name != original.name) profileDao.update(current.copy(name = original.name))
    }
}
