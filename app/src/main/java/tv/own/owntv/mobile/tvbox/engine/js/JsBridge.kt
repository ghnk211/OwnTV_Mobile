package tv.own.owntv.mobile.tvbox.engine.js

import com.google.gson.Gson
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tv.own.owntv.mobile.tvbox.net.NetApi
import tv.own.owntv.mobile.tvbox.util.TbLog
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * drpy/cat 运行时与宿主之间的全局对象。
 *
 * ## 分工原则（照抄参考项目）
 * Java 侧只做"必须原生才能做的事"：网络、HTML 解析、定时器、存储、代理地址。
 * `md5` / `atob` / `CryptoJS` / `cheerio` 这些一律留在 JS 侧。
 *
 * ## 安全边界
 * JS 源是第三方代码，按不可信对待。`req`/`fetch` 出网前统一过 [JsSsrfGuard]：
 * scheme 白名单 + host 黑名单 + DNS 解析后复检 + 每跳重定向复检。
 *
 * 移植自 VOD `4c8d289:app/src/main/java/com/vod/player/engine/js/JsBridge.kt`。
 */
internal class JsBridge(
    private val ctx: QuickJSContext,
    private val net: NetApi,
    private val siteKey: String,
    private val siteName: String,
    private val timeoutMs: Long,
    private val store: JsLocalStore,
) {
    private val timers = ConcurrentHashMap<Int, TimerEntry>()
    private val timerIds = AtomicInteger(1)

    private data class TimerEntry(
        val fn: JSFunction,
        val intervalMs: Long,
        var nextAt: Long,
    )

    fun installAll() {
        installConsole()
        installRequest()
        installLocal()
        installTimers()
        installHtmlParser()
        installProxyHooks()
    }

    private fun installConsole() {
        val console = ctx.createNewJSObject()
        val make = { level: String ->
            JSCallFunction { args ->
                val msg = "[$siteKey] ${args.joinToString(" ")}"
                when (level) {
                    "error" -> TbLog.tag("JS").e(msg)
                    "warn" -> TbLog.tag("JS").w(msg)
                    "info" -> TbLog.tag("JS").i(msg)
                    else -> TbLog.tag("JS").d(msg)
                }
                null
            }
        }
        console.setProperty("log", make("log"))
        console.setProperty("info", make("info"))
        console.setProperty("warn", make("warn"))
        console.setProperty("error", make("error"))
        console.setProperty("debug", make("debug"))
        ctx.setProperty(ctx.globalObject, "console", console)
    }

    private fun installRequest() {
        val bridge = ctx.createNewJSObject()
        bridge.setProperty("req", JSCallFunction { args ->
            val url = args.getOrNull(0)?.toString().orEmpty()
            if (url.isBlank()) return@JSCallFunction emptyResponse()
            val options = args.getOrNull(1)?.toString().orEmpty()
            val result = runBlocking {
                runCatching {
                    withTimeout(timeoutMs) { performRequest(url, options) }
                }.getOrElse { errorResponse(it.message ?: it.javaClass.simpleName) }
            }
            ctx.parseJSON(result)
        })
        ctx.setProperty(ctx.globalObject, "__androidReq", bridge)
        ctx.evaluate(
            """
            globalThis.req = function(url, obj) {
              var json = (obj === undefined || obj === null) ? '{}' : JSON.stringify(obj);
              return __androidReq.req(String(url), json);
            };
            globalThis._http = function(url, obj) { return globalThis.req(url, obj); };
            globalThis.fetch = function(url, obj) {
              var r = globalThis.req(url, obj);
              return (r && typeof r.content === 'string') ? r.content : '';
            };
            """.trimIndent(),
            "js-request-bridge.js",
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun performRequest(url: String, optionsJson: String): String {
        val opt = runCatching {
            Gson().fromJson(optionsJson, Map::class.java) as? Map<*, *>
        }.getOrNull() ?: emptyMap<Any?, Any?>()

        val headers = linkedMapOf<String, String>()
        (opt["headers"] as? Map<*, *>)?.forEach { (k, v) ->
            if (k != null && v != null) headers[k.toString()] = v.toString()
        }
        var method = (opt["method"] as? String) ?: (opt["method"] as? Number)?.toString() ?: "GET"
        var body = opt["body"] as? String
        var form = (opt["data"] as? Map<*, *>)?.mapNotNull { (k, v) ->
            if (k == null) null else k.toString() to v?.toString().orEmpty()
        }?.toMap()
        val withHeaders = opt["withHeaders"] == true || opt["withHeaders"] == "true"
        val buffer = when (val b = opt["buffer"]) {
            true, "true" -> true
            is Number -> b.toInt() > 0
            is String -> b.toIntOrNull()?.let { it > 0 } ?: false
            else -> false
        }
        val redirect = opt["redirect"]
        val followRedirects = when {
            redirect == false || redirect == "false" -> false
            redirect is Number -> redirect.toInt() != 0
            else -> true
        }
        val timeout = (opt["timeout"] as? Number)?.toLong()
            ?: (opt["timeout"] as? String)?.toLongOrNull()
            ?: timeoutMs

        var current = url
        var hop = 0
        while (true) {
            JsSsrfGuard.check(current)?.let { reason ->
                TbLog.tag("JS").w("[%s] 请求被拒绝：%s", siteKey, reason)
                return errorResponse(reason)
            }
            val resp = net.request(
                url = current,
                method = method,
                headers = headers,
                body = body,
                form = form,
                timeoutMs = timeout.coerceAtMost(MAX_REQUEST_MS),
                followRedirects = false,
                siteKey = siteKey,
                buffer = buffer || withHeaders,
            )
            val location = resp.headers.entries
                .firstOrNull { it.key.equals("Location", ignoreCase = true) }
                ?.value
            if (followRedirects && resp.code in 300..399 && !location.isNullOrBlank()) {
                if (hop >= MAX_REDIRECTS) return errorResponse("重定向次数过多")
                hop++
                current = JsUrl.join(current, location)
                // 301/302/303 按浏览器语义改成 GET；307/308 保留方法与请求体
                if (resp.code != 307 && resp.code != 308) {
                    method = "GET"
                    body = null
                    form = null
                }
                continue
            }
            val payload = linkedMapOf<String, Any?>(
                "code" to resp.code,
                "content" to if (buffer && resp.bytes != null) {
                    Base64.Default.encode(resp.bytes)
                } else {
                    resp.content
                },
                "headers" to resp.headers,
                "finalUrl" to resp.finalUrl,
                "error" to resp.error,
            )
            return Gson().toJson(payload)
        }
    }

    private fun emptyResponse(): String = Gson().toJson(
        mapOf("code" to -1, "content" to "", "headers" to emptyMap<String, String>(), "error" to "空 URL"),
    )

    private fun errorResponse(message: String): String = Gson().toJson(
        mapOf("code" to -1, "content" to "", "headers" to emptyMap<String, String>(), "error" to message),
    )

    private fun installLocal() {
        val local = ctx.createNewJSObject()
        local.setProperty("get", JSCallFunction { args ->
            val ns = args.getOrNull(0)?.toString().orEmpty()
            val k = args.getOrNull(1)?.toString().orEmpty()
            store.get(siteKey, ns, k)
        })
        local.setProperty("set", JSCallFunction { args ->
            val ns = args.getOrNull(0)?.toString().orEmpty()
            val k = args.getOrNull(1)?.toString().orEmpty()
            val v = args.getOrNull(2)?.toString().orEmpty()
            store.set(siteKey, ns, k, v)
            null
        })
        local.setProperty("delete", JSCallFunction { args ->
            val ns = args.getOrNull(0)?.toString().orEmpty()
            val k = args.getOrNull(1)?.toString().orEmpty()
            store.delete(siteKey, ns, k)
            null
        })
        ctx.setProperty(ctx.globalObject, "local", local)
    }

    private fun installTimers() {
        val timersObj = ctx.createNewJSObject()
        timersObj.setProperty("__set", JSCallFunction { args ->
            val fn = args.getOrNull(0) as? JSFunction ?: return@JSCallFunction 0
            val delay = (args.getOrNull(1) as? Number)?.toLong() ?: 0L
            val interval = (args.getOrNull(2) as? Number)?.toLong() ?: 0L
            val id = timerIds.getAndIncrement()
            timers[id] = TimerEntry(fn, interval, System.currentTimeMillis() + delay.coerceAtLeast(0))
            id
        })
        timersObj.setProperty("__clear", JSCallFunction { args ->
            val id = (args.getOrNull(0) as? Number)?.toInt() ?: return@JSCallFunction null
            timers.remove(id)
            null
        })
        ctx.setProperty(ctx.globalObject, "__androidTimers", timersObj)
        ctx.evaluate(
            """
            globalThis.setTimeout = function(fn, delay) {
              return __androidTimers.__set(fn, delay || 0, 0);
            };
            globalThis.setInterval = function(fn, delay) {
              return __androidTimers.__set(fn, delay || 0, delay || 0);
            };
            globalThis.clearTimeout = function(id) { __androidTimers.__clear(id); };
            globalThis.clearInterval = function(id) { __androidTimers.__clear(id); };
            """.trimIndent(),
            "js-timer-bridge.js",
        )
    }

    /** 触发已到期的定时器回调；必须在该 context 所属的线程调用。 */
    fun drainTimers(now: Long = System.currentTimeMillis()) {
        if (timers.isEmpty()) return
        timers.entries.sortedBy { it.key }.forEach { (id, entry) ->
            if (entry.nextAt > now) return@forEach
            if (entry.intervalMs > 0) entry.nextAt = now + entry.intervalMs else timers.remove(id)
            runCatching { entry.fn.call() }
                .onFailure { TbLog.tag("JS").w("[$siteKey] 定时器回调失败: ${it.message}") }
        }
    }

    fun clearTimers() = timers.clear()

    private fun installHtmlParser() {
        val bridge = ctx.createNewJSObject()
        bridge.setProperty("pdfh", JSCallFunction { args ->
            DrpyHtmlParser.pdfh(args.getOrNull(0)?.toString().orEmpty(), args.getOrNull(1)?.toString().orEmpty())
        })
        bridge.setProperty("pdfa", JSCallFunction { args ->
            Gson().toJson(
                DrpyHtmlParser.pdfa(args.getOrNull(0)?.toString().orEmpty(), args.getOrNull(1)?.toString().orEmpty()),
            )
        })
        bridge.setProperty("pd", JSCallFunction { args ->
            DrpyHtmlParser.pd(
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
                args.getOrNull(2)?.toString().orEmpty(),
            )
        })
        ctx.setProperty(ctx.globalObject, "__androidHtmlParser", bridge)

        val url = ctx.createNewJSObject()
        url.setProperty("join", JSCallFunction { args ->
            JsUrl.join(args.getOrNull(0)?.toString(), args.getOrNull(1)?.toString())
        })
        ctx.setProperty(ctx.globalObject, "__androidUrl", url)

        ctx.evaluate(
            """
            globalThis.pdfh = function(html, rule) {
              return __androidHtmlParser.pdfh(String(html == null ? '' : html), String(rule == null ? '' : rule));
            };
            globalThis.pdfa = function(html, rule) {
              return JSON.parse(__androidHtmlParser.pdfa(String(html == null ? '' : html), String(rule == null ? '' : rule)));
            };
            globalThis.pd = function(html, rule, base) {
              return __androidHtmlParser.pd(String(html == null ? '' : html), String(rule == null ? '' : rule), String(base == null ? '' : base));
            };
            globalThis.joinUrl = function(base, path) {
              var b = String(base == null ? '' : base);
              var p = String(path == null ? '' : path);
              if (typeof __androidUrl !== 'undefined' && __androidUrl && typeof __androidUrl.join === 'function') {
                var r = __androidUrl.join(b, p);
                if (r !== null && r !== undefined) return String(r);
              }
              if (!p) return b;
              if (!b) return p;
              if (/^[a-zA-Z][a-zA-Z0-9+.\-]*:/.test(p)) return p;
              if (p.indexOf('//') === 0) return b.indexOf('://') > 0 ? b.substring(0, b.indexOf('://') + 1) + p : 'https:' + p;
              if (p.charAt(0) === '/') {
                var m = b.match(/^([a-zA-Z][a-zA-Z0-9+.\-]*:\/\/)?[^\/]*/);
                return (m ? m[0] : '') + p;
              }
              var head = b.split('#')[0].split('?')[0];
              head = head.replace(/\/[^\/]*$/, '/');
              return head + p;
            };
            """.trimIndent(),
            "js-html-bridge.js",
        )
    }

    private fun installProxyHooks() {
        val bridge = ctx.createNewJSObject()
        bridge.setProperty("getProxy", JSCallFunction { _ ->
            JsProxyBridge.baseUrl().orEmpty()
        })
        bridge.setProperty("js2Proxy", JSCallFunction { args ->
            val from = args.getOrNull(0)?.toString().orEmpty()
            val siteType = args.getOrNull(1)?.toString().orEmpty()
            val key = args.getOrNull(2)?.toString().orEmpty()
            val header = args.getOrNull(3)?.toString().orEmpty()
            val url = args.getOrNull(4)?.toString().orEmpty()
            JsProxyBridge.js2Proxy(from, siteType, key.ifEmpty { siteKey }, header, url).orEmpty()
        })
        ctx.setProperty(ctx.globalObject, "__androidProxy", bridge)
        ctx.evaluate(
            """
            globalThis.getProxy = function(isJs) { return __androidProxy.getProxy(isJs === undefined ? true : isJs); };
            globalThis.js2Proxy = function(from, siteType, siteKey, header, url) {
              return __androidProxy.js2Proxy(from, siteType, siteKey, header, url);
            };
            """.trimIndent(),
            "js-proxy-bridge.js",
        )
    }

    /** 站点身份，供日志与 proxy 回调定位 */
    fun describe(): String = "$siteName($siteKey)"

    private companion object {
        private const val MAX_REQUEST_MS = 60_000L

        /**
         * 重定向跳数上限。每一跳都重新过 [JsSsrfGuard] —— 否则 302 到
         * `http://127.0.0.1:9978/…` 就绕过了首跳的校验。
         */
        private const val MAX_REDIRECTS = 5
    }
}

/**
 * JS 出网入口的 SSRF 加固层。
 *
 * 本仓既有 `isPrivateHost` 只拦私网 **IP 字面量**：`localhost` 放行、域名放行、
 * DNS 指向内网也放行。对不可信脚本不够，这里补齐四层：
 * 1. scheme 白名单；2. host 黑名单；3. 解析后逐个 IP 复检；4. 重定向每跳复检。
 */
internal object JsSsrfGuard {

    private val ALLOWED_SCHEMES = setOf("http", "https")

    private val BLOCKED_HOSTS = setOf(
        "localhost",
        "0.0.0.0",
        "169.254.169.254",
        "::1",
    )

    private val BLOCKED_SUFFIXES = setOf(".localhost", ".local")

    /** @return null 表示放行；否则返回可回显给 JS 的拒绝原因（只含 host）。 */
    fun check(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return "非法 URL"
        val scheme = uri.scheme?.lowercase() ?: return "缺少协议"
        if (scheme !in ALLOWED_SCHEMES) return "不允许的协议：$scheme"
        val host = uri.host?.trim()?.lowercase() ?: return "缺少主机"
        if (isBlockedHost(host)) return "内网地址已拦截（SSRF 防护）：$host"
        if (isIpLiteral(host)) {
            return if (isPrivateAddressLiteral(host)) "内网地址已拦截（SSRF 防护）：$host" else null
        }
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull()
            ?: return "域名解析失败：$host"
        if (addresses.isEmpty()) return "域名解析失败：$host"
        val bad = addresses.firstOrNull { isPrivateAddress(it) }
        if (bad != null) return "域名指向内网地址，已拦截：$host"
        return null
    }

    fun isBlockedHost(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (h in BLOCKED_HOSTS) return true
        return BLOCKED_SUFFIXES.any { h.endsWith(it) } || h.endsWith(".local")
    }

    fun isPrivateAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress ||
            addr.isAnyLocalAddress || addr.isMulticastAddress
        ) {
            return true
        }
        val b = addr.address ?: return false
        if (b.size == 4) return isPrivateV4(b[0], b[1], b[2], b[3])
        if (b.size == 16) {
            if ((b[0].toInt() and 0xFE) == 0xFC) return true
            if (b.slice(0..9).all { it.toInt() == 0 } &&
                b[10].toInt() == 0xFF && b[11].toInt() == 0xFF
            ) {
                return isPrivateV4(b[12], b[13], b[14], b[15])
            }
        }
        return false
    }

    private fun isPrivateAddressLiteral(host: String): Boolean {
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return true
        return isPrivateAddress(addr)
    }

    private fun isIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true
        val parts = host.split('.')
        return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
    }

    private fun isPrivateV4(a: Byte, b: Byte, c: Byte, d: Byte): Boolean {
        val i = { x: Byte -> x.toInt() and 0xFF }
        val ia = i(a)
        val ib = i(b)
        return ia == 0 ||
            ia == 10 ||
            (ia == 172 && ib in 16..31) ||
            (ia == 192 && ib == 168) ||
            (ia == 169 && ib == 254) ||
            (ia == 100 && ib in 64..127)
    }
}