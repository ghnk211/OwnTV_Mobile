package tv.own.owntv.mobile.tvbox.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 内存 CookieStore（方案 7.1）：按 host 分桶。
 * 切换配置时清空（[clearAll]），避免跨配置串味。
 */
class MemoryCookieStore : CookieJar {

    private data class Key(
        val name: String,
        val domain: String,
        val path: String,
    )

    private val store = LinkedHashMap<Key, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { c ->
            val key = Key(c.name, c.domain, c.path)
            // session cookie 不需要长期保留，但统一放内存即可，容量有上限
            store[key] = c
        }
        trimIfNeeded()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val expired = store.filterValues { it.expiresAt < now }
        expired.keys.forEach { store.remove(it) }

        return store.values.filter { c ->
            c.expiresAt >= now && c.matches(url)
        }
    }

    /** 手动塞入 Cookie（登录态、源站需要固定 token 的场景） */
    @Synchronized
    fun put(url: HttpUrl, cookie: Cookie) {
        store[Key(cookie.name, cookie.domain, cookie.path)] = cookie
    }

    @Synchronized
    fun clearAll() = store.clear()

    @Synchronized
    fun clearHost(host: String) {
        store.keys.filter { it.domain.contains(host, ignoreCase = true) }.forEach { store.remove(it) }
    }

    @Synchronized
    fun size(): Int = store.size

    @Suppress("ControlFlowWithEmptyBody")
    private fun trimIfNeeded() {
        // 简单上限：超过 300 条时丢弃最早插入的 100 条
        if (store.size > MAX) {
            val drop = store.keys.take(100)
            drop.forEach { store.remove(it) }
        }
    }

    private companion object {
        const val MAX = 300
    }
}
