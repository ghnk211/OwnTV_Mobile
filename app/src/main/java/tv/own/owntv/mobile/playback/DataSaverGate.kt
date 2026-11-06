package tv.own.owntv.mobile.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.i18n.AppLocale
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.mobile.R

/**
 * Data saver: refuse to open a stream over the mobile network.
 *
 * Asked once per stream rather than watched, because the answer only matters at the moment something
 * starts — a connection that becomes metered mid-film is not worth cutting off, and a user who walks
 * out of Wi-Fi range would otherwise lose the picture without touching anything.
 *
 * The refusal has to say so itself. Nothing is on screen at that point but the channel the user just
 * tapped, and the player's own failure messages are about a stream that started and went wrong —
 * "no internet" here would be a lie, because the phone has a perfectly good connection.
 */
class DataSaverGate(
    private val context: Context,
    private val settings: SettingsRepository,
    private val localeStore: LocaleStore,
) {

    /** True when a stream may start now. False means the user has already been told why not. */
    suspend fun allowsStreaming(): Boolean {
        if (!settings.dataSaverNow()) return true
        if (!isMetered()) return true
        withContext(Dispatchers.Main) {
            val ctx = AppLocale.wrap(context, tv.own.owntv.mobile.i18n.AppLocales.normalize(localeStore.currentTag.value))
            Toast.makeText(ctx, R.string.player_error_data_saver, Toast.LENGTH_LONG).show()
        }
        return false
    }

    /** Mobile data, a metered hotspot, or a Wi-Fi the user has marked as metered — all the same thing. */
    fun isMetered(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
