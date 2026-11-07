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
    QuickToggle(
        "quick_autoplay",
        R.string.settings_quick_autoplay,
        { it.autoPlayNext },
        { setAutoPlayNext(it) },
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
        "vp_autoplay",
        R.string.settings_autoplay_next,
        { it.autoPlayNext },
        { setAutoPlayNext(it) },
        default = true,
    ),
    QuickToggle(
        "background_playback",
        R.string.settings_background_playback,
        { it.backgroundPlayback },
        { setBackgroundPlayback(it) },
    ),
    QuickToggle("pip_enabled", R.string.settings_pip, { it.pipEnabled }, { setPipEnabled(it) }),
    QuickToggle(
        "downloads_wifi_only",
        R.string.settings_downloads_wifi_only,
        { it.downloadsWifiOnly },
        { setDownloadsWifiOnly(it) },
    ),
    // The mobility layer. Same shape as the three above: touch-host only, and a television that
    // restores a pinned list containing them simply has no row to draw.
    QuickToggle("pip_snap", R.string.settings_pip_snap, { it.pipSnap }, { setPipSnap(it) }, default = true),
    QuickToggle(
        "audio_on_screen_off",
        R.string.settings_audio_on_screen_off,
        { it.audioOnScreenOff },
        { setAudioOnScreenOff(it) },
        default = true,
    ),
    QuickToggle(
        "audio_on_mobile_data",
        R.string.settings_audio_on_mobile_data,
        { it.audioOnMobileData },
        { setAudioOnMobileData(it) },
    ),
    QuickToggle(
        "audio_per_channel",
        R.string.settings_audio_per_channel,
        { it.audioPerChannel },
        { setAudioPerChannel(it) },
        default = true,
    ),
)

/** The pinnable switch stored under [key]. Every key passed here is one of [QUICK_TOGGLES]. */
fun quickToggle(key: String): QuickToggle =
    QUICK_TOGGLES.first { it.key == key }
