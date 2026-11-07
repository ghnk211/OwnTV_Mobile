package tv.own.owntv.mobile.ui.screens.settings

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.mobile.R

/**
 * A switch that can be lifted to the top of the settings root.
 *
 * The pinned list is core's, shared with the TV app, and core keeps keys it does not recognise — so a
 * phone pinning "Background playback" does not disturb a television that has no such row, and the six
 * the TV app pins by default arrive here with the two remote-control ones simply not drawn.
 */
class QuickToggle(
    val key: String,
    @param:StringRes val titleRes: Int,
    val flow: (SettingsRepository) -> Flow<Boolean>,
    val set: suspend SettingsRepository.(Boolean) -> Unit,
    /** What the switch shows until the stored value arrives — core's own default for the setting. */
    val default: Boolean = false,
)

/** Every switch this app lets the user pin, by the key it is stored under. */
val QUICK_TOGGLES: List<QuickToggle> = listOf(
    QuickToggle(
        "quick_hdr",
        R.string.settings_quick_prefer_sdr,
        { it.preferSdr },
        { setPreferSdr(it) },
        default = true,
    ),
    // The video player's own switches, under the keys the television pins them by, so a pinned list
    // restored from a television lands on the same rows here.
    QuickToggle(
        "vp_hw",
        R.string.settings_hardware_decoding,
        { it.hwDecoding },
        { setHwDecoding(it) },
        default = true,
    ),
    QuickToggle(
        "vp_hdr",
        R.string.settings_quick_prefer_sdr,
        { it.preferSdr },
        { setPreferSdr(it) },
        default = true,
    ),
    QuickToggle(
        "downloads_wifi_only",
        R.string.settings_downloads_wifi_only,
        { it.downloadsWifiOnly },
        { setDownloadsWifiOnly(it) },
    ),
)

/** The pinnable switch stored under [key]. Every key passed here is one of [QUICK_TOGGLES]. */
fun quickToggle(key: String): QuickToggle =
    QUICK_TOGGLES.first { it.key == key }
