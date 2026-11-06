package tv.own.owntv.mobile.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.profile.ProfileManager
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Who is watching, and whether they may in.
 *
 * Creating, editing, switching and deleting profiles went with multi-profile management; what is left
 * is the three reads the app lock and the More card need. The editing that a single profile still
 * needs — the lock's PIN, Kids mode — is done through Settings, on `SettingsViewModel`.
 */
class ProfilesViewModel(
    profileDao: ProfileDao,
    settings: SettingsRepository,
    private val manager: ProfileManager,
) : ViewModel() {

    /**
     * Null until Room has answered, never an empty list standing in for "not loaded yet". The shell
     * waits on this, and an empty answer arriving early would let the app walk straight past a PIN.
     */
    val profiles: StateFlow<List<ProfileEntity>?> = profileDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The active profile, or null while the preference is still being read.
     *
     * Nullable on purpose, exactly as the television's is: `-1` already means "no profile chosen",
     * so a `-1` placeholder while loading makes those two states indistinguishable — and the shell
     * has to tell them apart, or the frame before the answer arrives looks like a fresh install and
     * flashes the lock on the way past.
     */
    val activeProfileId: StateFlow<Long?> = settings.activeProfileId
        .map<Long, Long?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun verifyPin(profile: ProfileEntity, pin: String): Boolean = manager.verifyPin(profile, pin)
}
