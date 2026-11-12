package tv.own.owntv.mobile.tvbox.engine.js

import android.content.Context
import tv.own.owntv.mobile.tvbox.data.prefs.SecureKv

/**
 * drpy 的 `local`（localStorage）落点。
 *
 * 按 siteKey 隔离：参考项目的键前缀没有按源隔离，两个源用同一个 ns 时会互相覆盖；
 * 这里把 siteKey 编进键里，避免 A 源把 B 源的 cookie/token 冲掉。
 *
 * 用 SharedPreferences 而不是 DataStore：JS 侧的 `local.get/set` 是同步调用
 * （发生在 QuickJS 线程上），DataStore 只有 suspend/flow 接口。
 *
 * 移植自 VOD `4c8d289:app/src/main/java/com/vod/player/engine/js/JsLocalStore.kt`。
 */
class JsLocalStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(siteKey: String, ns: String, k: String): String =
        "jsRuntime_${siteKey}_${ns}_$k"

    fun get(siteKey: String, ns: String, k: String): String {
        if (k.isBlank()) return ""
        return SecureKv.get(prefs, PREFS_NAME, key(siteKey, ns, k), k) ?: ""
    }

    fun set(siteKey: String, ns: String, k: String, v: String) {
        if (k.isBlank()) return
        SecureKv.put(prefs, PREFS_NAME, key(siteKey, ns, k), v)
    }

    fun delete(siteKey: String, ns: String, k: String) {
        if (k.isBlank()) return
        SecureKv.remove(prefs, PREFS_NAME, key(siteKey, ns, k))
    }

    private companion object {
        const val PREFS_NAME = SecureKv.PREFS_DRPY
    }
}