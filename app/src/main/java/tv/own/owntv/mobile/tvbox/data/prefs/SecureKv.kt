package tv.own.owntv.mobile.tvbox.data.prefs

import android.content.SharedPreferences

/**
 * KV store for the `com.github.catvod.util.AppExt` stub (`catvod.getPref/putPref`).
 *
 * VodPlayer encrypted sensitive keys (cookie/token-shaped) with an Android Keystore AES-GCM
 * cipher; this first version keeps the same call surface but writes plaintext, which is the
 * documented simplification of the TVbox port. Keys are namespaced by the prefs file tag so
 * values never collide across the stub's two files.
 */
object SecureKv {

    const val PREFS_DRPY = "drpy_local"
    const val PREFS_CATVOD = "catvod"

    fun get(prefs: SharedPreferences, tag: String, key: String, def: String? = null): String? =
        prefs.getString("${tag}.${'$'}${key}", def) ?: def

    fun put(prefs: SharedPreferences, tag: String, key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove("${tag}.${'$'}${key}") else putString("${tag}.${'$'}${key}", value)
        }.apply()
    }
}
