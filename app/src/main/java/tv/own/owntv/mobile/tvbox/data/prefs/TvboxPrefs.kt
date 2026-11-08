package tv.own.owntv.mobile.tvbox.data.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TVbox-side preferences, kept in a dedicated `tvbox_prefs` file.
 *
 * Deliberately separate from core's `owntv_settings` DataStore: the TVbox source list is its own
 * world with its own lifecycle (import/switch/delete), and nothing in it may leak into the core
 * settings schema. Core already owns its DataStore; adding a second file there is not an option,
 * and SharedPreferences in the app's private storage is the same isolation for zero new
 * dependencies.
 */
class TvboxPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("tvbox_prefs", Context.MODE_PRIVATE)

    var activeConfigId: Long
        get() = prefs.getLong(KEY_ACTIVE_ID, -1L)
        set(value) = prefs.edit().putLong(KEY_ACTIVE_ID, value).apply()

    var nextConfigId: Long
        get() = prefs.getLong(KEY_NEXT_ID, 1L)
        set(value) = prefs.edit().putLong(KEY_NEXT_ID, value).apply()

    fun sourceUrlOf(configId: Long): String? =
        prefs.getString(KEY_SOURCE_URL_PREFIX + configId, null)

    fun setSourceUrl(configId: Long, url: String?) {
        prefs.edit().apply {
            if (url.isNullOrBlank()) remove(KEY_SOURCE_URL_PREFIX + configId)
            else putString(KEY_SOURCE_URL_PREFIX + configId, url)
        }.apply()
    }

    fun sourceKindOf(configId: Long): String? =
        prefs.getString(KEY_SOURCE_KIND_PREFIX + configId, null)

    fun setSourceKind(configId: Long, kind: String?) {
        prefs.edit().apply {
            if (kind.isNullOrBlank()) remove(KEY_SOURCE_KIND_PREFIX + configId)
            else putString(KEY_SOURCE_KIND_PREFIX + configId, kind)
        }.apply()
    }

    var homeSiteKey: String?
        get() = prefs.getString(KEY_HOME_SITE, null)
        set(value) = prefs.edit().putString(KEY_HOME_SITE, value).apply()

    var localTreeUri: String?
        get() = prefs.getString(KEY_LOCAL_TREE_URI, null)
        set(value) = prefs.edit().putString(KEY_LOCAL_TREE_URI, value).apply()

    /** Kids-mode strict fallback (plan §5.5.5): show only explicitly safe-marked sites. */
    var kidsStrict: Boolean
        get() = prefs.getBoolean(KEY_KIDS_STRICT, false)
        set(value) = prefs.edit().putBoolean(KEY_KIDS_STRICT, value).apply()

    suspend fun localTreeUriOrNull(): String? = withContext(Dispatchers.IO) { localTreeUri }

    private companion object {
        const val KEY_ACTIVE_ID = "active_config_id"
        const val KEY_NEXT_ID = "next_config_id"
        const val KEY_SOURCE_URL_PREFIX = "source_url_"
        const val KEY_SOURCE_KIND_PREFIX = "source_kind_"
        const val KEY_HOME_SITE = "home_site_key"
        const val KEY_LOCAL_TREE_URI = "local_tree_uri"
        const val KEY_KIDS_STRICT = "kids_strict"
    }
}
