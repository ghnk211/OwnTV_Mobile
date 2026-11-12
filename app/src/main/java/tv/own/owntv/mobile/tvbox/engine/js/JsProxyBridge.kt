package tv.own.owntv.mobile.tvbox.engine.js

import tv.own.owntv.mobile.tvbox.engine.proxy.JarProxyHub
import tv.own.owntv.mobile.tvbox.util.TbLog
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * JS 侧 `/proxy` 路由与回环服务之间的桥。
 *
 * jar 源与 JS 源**共用同一条路由**：服务只认 siteKey，由这里的注册表决定回调谁，
 * 不再起第二个回环 HTTP 服务。token 是**会话级、可复用**的 —— HLS 一个播放会话会发
 * 几十上百个分片请求，一次性 token 会让第 2 个分片起全部 403。
 *
 * 重写自 VOD `4c8d289:app/src/main/java/com/vod/player/engine/js/JsProxyRegistry.kt`，
 * 服务生命周期交给既有 [JarProxyHub]。
 */
internal object JsProxyBridge {

    private val spiders = ConcurrentHashMap<String, JsSpider>()
    private val fallbackKeys = ConcurrentHashMap<String, String>()

    @Volatile
    private var currentPlaySiteKey: String? = null

    @Volatile
    private var installed = false

    /** 进程内唯一的会话 token；拼进每个回环 URL，不写日志。 */
    private val token: String = TokenFactory.next()

    /** 挂到 [JarProxyHub]：分发钩子 + do=js 的 token 校验。幂等。 */
    fun install() {
        if (installed) return
        installed = true
        JarProxyHub.jsDispatcher = { params -> dispatchToArray(params) }
        JarProxyHub.jsTokenValidator = { params -> params["token"] == token }
        TbLog.tag(TAG).d("JS 代理桥已挂载到 JarProxyHub")
    }

    fun register(siteKey: String, spider: JsSpider) {
        if (siteKey.isBlank()) return
        spiders[siteKey] = spider
    }

    fun unregister(siteKey: String) {
        spiders.remove(siteKey)
        fallbackKeys.remove(siteKey)
    }

    /** 兜底：某些源在 proxy 回调里把 siteKey 换成了规则名（RKEY），这里记住映射。 */
    fun rememberAlias(alias: String, siteKey: String) {
        if (alias.isNotBlank() && alias != siteKey) fallbackKeys[alias] = siteKey
    }

    fun clear() {
        spiders.clear()
        fallbackKeys.clear()
        currentPlaySiteKey = null
    }

    /** 记录最近一次播放的源；请求缺 siteKey 时兜底。 */
    fun onPlay(siteKey: String) {
        if (siteKey.isNotBlank()) currentPlaySiteKey = siteKey
    }

    /**
     * 回环代理基址（带 token）。未启动时先懒启动；仍不可用返回 null，
     * 调用方保持原地址而不是拼一个必然连不上的 URL。
     */
    fun baseUrl(): String? {
        install()
        JarProxyHub.ensureStarted()
        val port = JarProxyHub.port
        if (port <= 0 || !JarProxyHub.isReady) return null
        return "http://127.0.0.1:$port/proxy?do=js&token=$token"
    }

    /** `js2Proxy` 完整地址：`url`/`siteKey` 等参数必须 URL-encode，避免注入改掉 `do`。 */
    fun js2Proxy(from: String, siteType: String, siteKey: String, header: String, url: String): String? {
        val base = baseUrl() ?: return null
        return buildString {
            append(base)
            appendParam("from", from)
            appendParam("siteType", siteType)
            appendParam("siteKey", siteKey)
            appendParam("header", header)
            appendParam("url", url)
        }
    }

    private fun StringBuilder.appendParam(key: String, value: String) {
        if (value.isEmpty()) return
        append('&').append(key).append('=')
            .append(URLEncoder.encode(value, "UTF-8").replace("+", "%20"))
    }

    /** `/proxy` 回调入口：按 siteKey 找到源并执行 JS `proxy(params)`。 */
    private fun dispatchToArray(params: Map<String, String>): Array<Any?>? {
        val requested = params["siteKey"]?.takeIf { it.isNotBlank() }
        val key = resolveKey(requested)
        val spider = key?.let { spiders[it] }
        if (spider == null) {
            TbLog.tag(TAG).w("proxy 找不到 JS 站点实例：siteKey=%s do=%s", requested.orEmpty(), params["do"])
            return null
        }
        rememberAlias(requested.orEmpty(), spider.siteKey)
        val result = spider.proxyLocal(params) ?: return null
        return arrayOf(result.code, result.contentType, result.bodyBytes(), result.headers)
    }

    /**
     * 口径与参考项目一致：精确 → 别名 → 忽略大小写 → 唯一实例兜底。
     * 最后一条是必要的：drpy 少数规则会在回调里丢掉 siteKey。
     */
    private fun resolveKey(requested: String?): String? {
        if (requested == null) return currentPlaySiteKey ?: spiders.keys.firstOrNull()
        if (spiders.containsKey(requested)) return requested
        fallbackKeys[requested]?.let { if (spiders.containsKey(it)) return it }
        spiders.keys.firstOrNull { it.equals(requested, ignoreCase = true) }?.let { return it }
        return spiders.keys.firstOrNull()
    }

    private const val TAG = "JsProxyBridge"
}

internal object TokenFactory {
    fun next(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}