package tv.own.owntv.mobile.tvbox.engine.js

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.engine.PlayResult
import tv.own.owntv.mobile.tvbox.engine.Spider
import tv.own.owntv.mobile.tvbox.engine.SpiderResult
import tv.own.owntv.mobile.tvbox.engine.jar.JarResultParser
import tv.own.owntv.mobile.tvbox.engine.proxy.JarProxyHub
import tv.own.owntv.mobile.tvbox.net.AddressFetcher
import tv.own.owntv.mobile.tvbox.net.NetApi
import tv.own.owntv.mobile.tvbox.util.LocalRead
import tv.own.owntv.mobile.tvbox.util.LocalSource
import tv.own.owntv.mobile.tvbox.util.LocalSourceResolver
import tv.own.owntv.mobile.tvbox.util.TbLog
import tv.own.owntv.mobile.tvbox.util.TextUtil
import tv.own.owntv.mobile.tvbox.util.toHeaderMap
import java.net.URI

/**
 * DRPY 脚本源适配器。
 *
 * 配置形态：`api` 指向**运行时**（`./lib/drpy2.min.js`），`ext` 指向**站点规则**（`./js/xxx.js`）。
 *
 * ## 函数签名必须与 drpy2 对表
 * | 宿主 | JS | 实测签名 |
 * |------|----|---------|
 * | home | `home(filter)` | `(filter, home_html, class_parse)` |
 * | homeVod | `homeVod()` | `(params)` |
 * | category | `category(tid, pg, filter, extend)` | 同名 |
 * | detail | `detail(ids)` | `(vod_url)` —— 单个字符串 |
 * | search | `search(wd, quick, pg)` | 顺序是 `wd, quick, pg` |
 * | play | `play(flag, id, flags)` | 同名 |
 *
 * 移植并改造自 VOD `4c8d289:app/src/main/java/com/vod/player/engine/js/JsSpider.kt`：
 * 脚本下载按 scheme 分流（http 带站点 header；本地走 AddressFetcher + 显式解码）。
 */
class JsSpider(
    private val site: SiteConfig,
    private val net: NetApi,
    private val runtimeUrl: String,
    private val ruleUrl: String?,
    private val store: JsLocalStore,
    private val cache: JsModuleCache,
    private val addressFetcher: AddressFetcher,
    private val activeSourceUrl: () -> String?,
) : Spider {
    override val siteKey: String get() = site.key
    override val displayName: String get() = site.name

    @Volatile
    private var runtime: JsRuntime? = null

    @Volatile
    private var inited = false

    private val initMutex = Mutex()

    override val initialized: Boolean get() = inited

    override suspend fun init(ext: String?) {
        if (inited) return
        initMutex.withLock {
            if (inited) return
            val rule = resolveRuleUrl(ext ?: site.ext)
            val scripts = downloadScripts(ext)
            val created = JsRuntime(
                net = net,
                siteKey = site.key,
                siteName = site.name,
                store = store,
                cache = cache,
                timeoutMs = timeoutFor(DEFAULT_RUNTIME_TIMEOUT_MS),
            )
            try {
                created.init(scripts.runtimeSource, runtimeUrl, scripts.ruleSource, rule)
                runtime = created
                inited = true
                JsProxyBridge.install()
                JsProxyBridge.register(site.key, this)
                JarProxyHub.ensureStarted()
                TbLog.tag(TAG).i("%s DRPY 初始化完成 runtime=%s rule=%s", site.name, runtimeUrl, rule)
            } catch (t: Throwable) {
                created.close()
                throw t
            }
        }
    }

    /** 只把脚本下载进 JsModuleCache、不建 context。 */
    suspend fun warmScripts() {
        initMutex.withLock {
            if (inited) return
            downloadScripts(site.ext)
        }
    }

    private data class Scripts(val runtimeSource: String, val ruleSource: String?)

    private suspend fun downloadScripts(ext: String?): Scripts = coroutineScope {
        val rule = resolveRuleUrl(ext ?: site.ext)
        val knownDeps = cache.readDeps(runtimeUrl)
        val knownDepJobs = knownDeps.orEmpty().map { dep ->
            async { runCatching { fetchScript(dep) } }
        }
        val runtimeJob = async { fetchScript(runtimeUrl) }
        val ruleJob = rule?.let { async { runCatching { fetchScript(it) }.getOrNull() } }
        val runtimeSource = runtimeJob.await()
        val ruleSource = ruleJob?.await()
        if (knownDeps == null) {
            val deps = extractStaticImports(runtimeSource)
            if (deps.isNotEmpty()) {
                cache.writeDeps(runtimeUrl, deps)
                deps.map { dep -> async { runCatching { fetchScript(dep) } } }.joinAll()
            }
        } else {
            knownDepJobs.joinAll()
        }
        Scripts(runtimeSource, ruleSource)
    }

    /**
     * 提取 runtime 的静态 import 依赖（`import x from '...'` 与副作用 `import '...'`），
     * 相对路径按 runtime URL resolve 成绝对地址。
     */
    private fun extractStaticImports(source: String): List<String> =
        extractStaticImportsFrom(source, runtimeUrl)

    override suspend fun homeContent(filter: Boolean): SpiderResult? = guarded("home") {
        normalize(call("home", filter))
    }

    override suspend fun homeVideoContent(): SpiderResult? = guarded("homeVod") {
        normalize(call("homeVod"))
    }

    override suspend fun categoryContent(
        tid: String,
        pg: String,
        filter: Boolean,
        extend: String?,
    ): SpiderResult? = guarded("category") {
        normalize(call("category", tid, pg.ifBlank { "1" }, filter, parseExtend(extend)))
    }

    override suspend fun detailContent(ids: String): SpiderResult? = guarded("detail") {
        if (ids.isBlank()) null else normalize(call("detail", ids), requireName = false)
    }

    override suspend fun searchContent(key: String, quick: Boolean, pg: String?): SpiderResult? =
        guarded("search") {
            if (key.isBlank()) null else normalize(call("search", key, quick, pg ?: "1"))
        }

    override suspend fun playerContent(flag: String, id: String, vipFlags: String?): PlayResult? =
        guarded("play") {
            if (id.isBlank()) null else {
                val flags = vipFlags?.split(",")?.filter { it.isNotBlank() }.orEmpty()
                val raw = call("play", flag, id, flags)
                val result = JarResultParser.toPlayResult(raw) ?: (raw as? String)?.let { text ->
                    if (text.startsWith("{") || text.startsWith("[")) {
                        JarResultParser.toPlayResult(text)
                    } else {
                        PlayResult(
                            parse = if (TextUtil.looksLikeStream(text)) 0 else 1,
                            url = text,
                            flag = flag,
                        )
                    }
                }
                result?.let {
                    JsProxyBridge.onPlay(site.key)
                    rewriteProxy(directStreamGuard(it))
                }
            }
        }

    /** 「地址本身就是流」→ 按直链起播。 */
    private fun directStreamGuard(result: PlayResult): PlayResult =
        if (result.parse == 1 && result.url.isNotBlank() && TextUtil.looksLikeStream(result.url)) {
            TbLog.tag(TAG).i("%s 直链被标 parse=1，按直连起播：%s", site.name, result.url.take(120))
            result.copy(parse = 0)
        } else {
            result
        }

    /** `/proxy` 回调：执行 JS 侧的 `proxy(params)`。 */
    fun proxyLocal(params: Map<String, String>): JsProxyResult? {
        val rt = runtime ?: return null
        val raw = runCatching {
            runBlocking { withTimeout(PROXY_TIMEOUT_MS) { rt.callProxy(params) } }
        }.getOrElse {
            TbLog.tag(TAG).w(it, "%s proxy 调用失败", site.name)
            null
        } ?: return null
        return toProxyResult(raw)
    }

    override fun destroy() {
        JsProxyBridge.unregister(site.key)
        runtime?.close()
        runtime = null
        inited = false
    }

    // ---------------- 内部 ----------------

    private suspend fun call(name: String, vararg args: Any?): Any? {
        val current = runtime ?: error("JS runtime 未初始化")
        if (current.isDead) {
            TbLog.tag(TAG).w("%s 的 JS context 已停用，重建后重试 %s", site.name, name)
            rebuild()
        }
        return (runtime ?: error("JS runtime 未初始化")).callFunction(name, *args)
    }

    private suspend fun rebuild() {
        val old = runtime
        runtime = null
        inited = false
        old?.close()
        init(site.ext)
    }

    private fun normalize(raw: Any?, requireName: Boolean = true): SpiderResult? =
        JarResultParser.toResult(raw, runtimeUrl, requireName)

    /**
     * 脚本下载按 scheme 分流（方案 §2.7）：
     * - http(s) / clan://<非 localhost> → [NetApi.getText]，带 site.header；
     * - file:// / clan://localhost / 裸绝对路径 / content:// → [AddressFetcher]，显式解码。
     */
    private suspend fun fetchScript(url: String): String = withContext(Dispatchers.IO) {
        val target = resolveScriptUrl(url)
        cache.readSource(target)?.let { return@withContext it }
        val text = when (scriptRouteOf(target)) {
            ScriptRoute.HTTP -> fetchHttpScript(
                (LocalSourceResolver.parse(target) as? LocalSource.RemoteClan)?.httpUrl ?: target,
            )

            ScriptRoute.LOCAL -> fetchLocalScript(target)
            null -> throw IllegalStateException("脚本地址无法解析：$target")
        }
        check(!text.trimStart().startsWith("<")) { "脚本地址返回 HTML: $target" }
        cache.writeSource(target, text)
        text
    }

    private suspend fun fetchHttpScript(url: String): String {
        val response = net.getText(
            url,
            site.key,
            headers = site.header?.toHeaderMap(),
            timeoutMs = timeoutFor(20_000),
        )
        check(response.isSuccess) { "脚本下载失败 ${response.code}: ${response.error}; $url" }
        return response.body
    }

    private suspend fun fetchLocalScript(address: String): String =
        when (val r = addressFetcher.fetch(address)) {
            is AddressFetcher.Outcome.Ok -> decodeScript(r.bytes)
            is AddressFetcher.Outcome.Err -> throw IllegalStateException(r.message)
        }

    /**
     * 本地脚本解码：BOM → UTF-8。drpy 的 `rule.encoding` 在 JS 规则源码里，
     * 宿主在下载阶段读不到，故不做声明式编码；BOM 覆盖生态里的 GBK 存量转存文件。
     */
    private fun decodeScript(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return String(bytes, Charsets.UTF_8)
    }

    /** 相对地址兜底：先按活跃配置来源解析，再按 runtime 目录解析。 */
    private fun resolveScriptUrl(url: String): String =
        resolveScriptUrlFor(url, runtimeUrl, activeSourceUrl())

    private fun resolveRuleUrl(ext: String?): String? =
        resolveRuleUrlFor(ext, runtimeUrl, activeSourceUrl())

    private fun parseExtend(extend: String?): Map<String, Any?> {
        if (extend.isNullOrBlank()) return emptyMap()
        return runCatching {
            val element = Json.parseToJsonElement(extend)
            element.jsonObject.mapValues { (_, value) ->
                when (value) {
                    is JsonPrimitive -> value.content
                    else -> value.toString()
                }
            }
        }.getOrDefault(mapOf("value" to extend))
    }

    /** `proxy://` → 回环地址，只在这一个出口做替换。 */
    private fun rewriteProxy(result: PlayResult): PlayResult {
        val url = result.url
        if (url.isNullOrBlank()) return result
        if (!url.startsWith("proxy://", true)) return result
        val base = JsProxyBridge.baseUrl()
        if (base == null) {
            TbLog.tag(TAG).w("%s 代理服务未就绪，无法重写 proxy:// 地址", site.name)
            return result
        }
        val rewritten = base + "&" + url.removePrefix("proxy://")
        TbLog.tag(TAG).i("%s 代理地址重写：%s", site.name, rewritten.take(120))
        return result.copy(url = rewritten)
    }

    internal fun toProxyResult(raw: Any?): JsProxyResult? = JsProxyCodec.toResult(raw)

    private fun timeoutFor(floorMs: Long): Long =
        maxOf(floorMs, (site.timeout?.takeIf { it > 0 } ?: 0) * 1_000L + 5_000L)

    private suspend fun <T> guarded(name: String, block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: JsTimeoutException) {
        TbLog.tag(TAG).w(e, "%s JS %s 超时", site.name, name)
        throw e
    } catch (t: Throwable) {
        TbLog.tag(TAG).w(t, "%s JS %s 调用失败", site.name, name)
        null
    }

    companion object {
        private const val TAG = "JsSpider"
        private const val DEFAULT_RUNTIME_TIMEOUT_MS = 15_000L
        private const val PROXY_TIMEOUT_MS = 20_000L

        private val IMPORT_FROM = Regex("""import[^;'"]*?from\s*['"]([^'"]+)['"]""")
        private val IMPORT_BARE = Regex("""import\s*['"]([^'"]+)['"]""")

        /**
         * 脚本地址分流（方案 §2.7）：
         * http(s) / clan://<非 localhost> 走 [NetApi]（带站点 header）；
         * file:// / clan://localhost / 裸绝对路径 / content:// 走 [AddressFetcher]。
         * null = 相对地址且无法兜底。
         */
        internal fun scriptRouteOf(address: String): ScriptRoute? {
            val a = address.trim()
            if (a.isEmpty()) return null
            if (a.startsWith("http://", true) || a.startsWith("https://", true)) return ScriptRoute.HTTP
            if (a.startsWith("clan://", true)) {
                val rest = a.removePrefix("clan://").removePrefix("CLAN://").removePrefix("Clan://")
                val host = rest.substringBefore('/').lowercase()
                return if (host.isBlank() || host == "localhost" || host == "127.0.0.1") {
                    ScriptRoute.LOCAL
                } else {
                    ScriptRoute.HTTP
                }
            }
            if (a.startsWith("file://", true) || a.startsWith("content://", true) || a.startsWith("/")) {
                return ScriptRoute.LOCAL
            }
            return null
        }

        internal fun resolveScriptUrlFor(rawUrl: String, runtimeUrl: String, sourceUrl: String?): String {
            val raw = rawUrl.trim()
            if (raw.isEmpty()) return raw
            if (raw.contains("://") || LocalSourceResolver.isLocalPath(raw)) return raw
            val byConfig = LocalSourceResolver.resolveAgainst(raw, sourceUrl)
            if (byConfig != raw) return byConfig
            return JsUrl.join(runtimeUrl, raw)
        }

        internal fun resolveRuleUrlFor(rawExt: String?, runtimeUrl: String, sourceUrl: String?): String? {
            val value = rawExt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (value.contains("://") || LocalSourceResolver.isLocalPath(value)) return value
            val byConfig = LocalSourceResolver.resolveAgainst(value, sourceUrl)
            if (byConfig != value) return byConfig
            val base = runtimeUrl.substringBeforeLast('/') + "/"
            return runCatching { URI(base).resolve(value).toString() }
                .getOrDefault(TextUtil.absoluteUrl(value, base))
        }

        internal fun extractStaticImportsFrom(source: String, runtimeUrl: String): List<String> {
            val found = (IMPORT_FROM.findAll(source).map { it.range.first to it.groupValues[1] } +
                IMPORT_BARE.findAll(source).map { it.range.first to it.groupValues[1] })
                .sortedBy { it.first }
                .map { it.second }
            return found
                .filter { it.isNotBlank() && !it.contains(' ') }
                .map { JsRuntime.normalizeModuleUrl(runtimeUrl, it) }
                .filter { it != runtimeUrl }
                .distinct()
                .toList()
        }
    }
}

internal enum class ScriptRoute { HTTP, LOCAL }