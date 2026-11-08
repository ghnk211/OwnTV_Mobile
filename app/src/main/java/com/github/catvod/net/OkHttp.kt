package com.github.catvod.net

import tv.own.owntv.mobile.tvbox.util.TbLog

import tv.own.owntv.mobile.tvbox.CatvodHost
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 桩：静态网络门面（方案 5.3 / 7.3）。
 *
 * jar 里的实现通过 `OkHttpUtils` / `OkHttp` 调用网络。
 * 本实现把请求转发到宿主共享 OkHttpClient，从而：
 * - 超时 / UA / 重试策略由宿主统一控制
 * - 请求可打上 siteKey 标记，驱动按源统计与冷却
 *
 * 安全边界（M-3 / H-2）：本桩是**不可信第三方代码**的网络入口之一 ——
 * client() 返回的是带私网段拦截的派生实例（CatvodHost.netClient() →
 * OkHttpProvider.jarStubClient），jar 源码经此路访问 192.168.x / 10.x 等
 * 内网地址会被快速失败；Cookie 仍走主链 store（TLS 严格校验受系统 CA 保护）。
 *
 * 注意：不要缓存 OkHttpClient 字段，宿主可能在配置切换时重建。
 */
object OkHttp {

    private const val TAG = "CatvodOkHttp"

    @JvmStatic
    fun client(): OkHttpClient = CatvodHost.netClient()

    @JvmStatic
    fun client(timeoutSeconds: Int): OkHttpClient = CatvodHost.netClient()

    // ---------------- 同步调用（jar 侧多为阻塞实现） ----------------

    @JvmStatic
    @JvmOverloads
    fun get(url: String, header: String? = null): OkResult {
        val req = buildRequest(url, header, null)
        return execute(req)
    }

    @JvmStatic
    @JvmOverloads
    fun get(url: String, header: String?, cookie: String?): OkResult {
        val req = buildRequest(url, header, cookie)
        return execute(req)
    }

    @JvmStatic
    @JvmOverloads
    fun post(url: String, body: String?, header: String? = null): OkResult {
        val req = buildRequest(url, header, null, body)
        return execute(req)
    }

    @JvmStatic
    @JvmOverloads
    fun postForm(url: String, form: String?, header: String? = null): OkResult {
        val req = buildRequest(url, header, null, form)
        return execute(req)
    }

    @JvmStatic
    fun request(req: Request): OkResult = execute(req)

    private fun execute(req: Request): OkResult = try {
        client().newCall(req).execute().use { resp ->
            OkResult(
                code = resp.code,
                body = resp.body?.string().orEmpty(),
                cookie = resp.header("Set-Cookie"),
                headers = resp.headers,
            )
        }
    } catch (e: Exception) {
        OkResult(code = -1, body = "", error = e.message ?: e.javaClass.simpleName)
    }

    // ---------------- 异步调用 ----------------

    @JvmStatic
    @JvmOverloads
    fun asyncGet(url: String, callback: Callback, header: String? = null) {
        client().newCall(buildRequest(url, header, null)).enqueue(callback)
    }

    @JvmStatic
    fun call(req: Request): Call = client().newCall(req)

    // ---------------- TVBoxOS 对齐：string / newCall / noRedirect ----------------
    //
    // 2026-10-19 增补（wexguard 加固壳分析 P1-2）：真实 dex 被加密扫不到，
    // 但生态 jar 编译时依赖的是 TVBoxOS `com.github.catvod.net.OkHttp` 的完整静态面。
    // 以下签名与 TVBoxOS OkHttp.java 逐条对齐（JVM 描述符级别），缺哪个
    // 哪个方法被 jar 调用时就 NoSuchMethodError（Error，宿主 catch 不住）。
    // 返回 "" 的语义与 TVBoxOS 一致：jar 侧把空串当"无响应"处理。

    /** 默认超时（ms）—— 与 TVBoxOS `TIMEOUT` 一致 */
    private const val DEFAULT_TIMEOUT_MS = 30_000L

    /** GET 同步取文本；非 http(s) 地址或任何失败都返回 "" */
    @JvmStatic
    fun string(url: String): String = string(url, emptyMap<String, String>())

    /** GET 同步取文本（自定义超时，ms） */
    @JvmStatic
    fun string(url: String, timeout: Long): String {
        if (!isHttpUrl(url)) return ""
        return runCatching {
            client(timeout).newCall(Request.Builder().url(url).build()).execute()
                .use { resp -> resp.body?.string().orEmpty() }
        }.getOrElse { t ->
            TbLog.tag(TAG).d("OkHttp.string(url, timeout) 失败：%s", t.toString())
            ""
        }
    }

    /** GET 同步取文本（带请求头，形如 Map） */
    @JvmStatic
    fun string(url: String, headers: Map<String, String>?): String {
        if (!isHttpUrl(url)) return ""
        return runCatching {
            newCall(url, headers).execute().use { resp -> resp.body?.string().orEmpty() }
        }.getOrElse { t ->
            TbLog.tag(TAG).d("OkHttp.string(url, headers) 失败：%s", t.toString())
            ""
        }
    }

    @JvmStatic
    fun newCall(url: String): Call = newCall(client(), url)

    @JvmStatic
    fun newCall(url: String, tag: String): Call =
        client().newCall(Request.Builder().url(url).tag(tag).build())

    @JvmStatic
    fun newCall(client: OkHttpClient, url: String): Call =
        client.newCall(Request.Builder().url(url).build())

    @JvmStatic
    fun newCall(client: OkHttpClient, url: String, tag: String): Call =
        client.newCall(Request.Builder().url(url).tag(tag).build())

    @JvmStatic
    fun newCall(url: String, headers: Map<String, String>?): Call =
        client().newCall(Request.Builder().url(url).headers(toHeaders(headers)).build())

    @JvmStatic
    fun newCall(url: String, headers: Map<String, String>?, body: RequestBody): Call =
        client().newCall(Request.Builder().url(url).headers(toHeaders(headers)).post(body).build())

    @JvmStatic
    fun newCall(url: String, body: RequestBody, tag: String): Call =
        client().newCall(Request.Builder().url(url).post(body).tag(tag).build())

    @JvmStatic
    fun newCall(client: OkHttpClient, url: String, body: RequestBody): Call =
        client.newCall(Request.Builder().url(url).post(body).build())

    /** 不跟随重定向的 client（jar 取 302 Location 自己处理时用） */
    @JvmStatic
    fun noRedirect(): OkHttpClient = noRedirect(DEFAULT_TIMEOUT_MS)

    @JvmStatic
    fun noRedirect(timeout: Long): OkHttpClient =
        client().newBuilder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    /** 带超时的派生 client（共享连接池/Cookie，仅超时不同） */
    @JvmStatic
    fun client(timeout: Long): OkHttpClient =
        client().newBuilder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .build()

    /** redirect=false 时等价 [noRedirect]（TVBoxOS 同名重载） */
    @JvmStatic
    fun client(redirect: Boolean, timeout: Long): OkHttpClient =
        if (redirect) client(timeout) else noRedirect(timeout)

    private fun isHttpUrl(url: String?): Boolean =
        url != null && (url.startsWith("http://") || url.startsWith("https://"))

    private fun toHeaders(headers: Map<String, String>?): Headers {
        // 不用 Headers.of(Map)：OkHttp 4.12 起它是 deprecated(error)，替换扩展
        // toHeaders() 又依赖具体版本，手搓 Builder 最稳。
        if (headers.isNullOrEmpty()) return Headers.Builder().build()
        val builder = Headers.Builder()
        headers.forEach { (k, v) -> builder.add(k, v) }
        return builder.build()
    }

    // ---------------- 内部 ----------------

    /**
     * 构造请求。
     * header 形如 "a:1\nb:2"（TVBox 生态惯例的换行分隔键值对）。
     */
    private fun buildRequest(
        url: String,
        header: String?,
        cookie: String?,
        body: String? = null,
    ): Request {
        val builder = Request.Builder().url(url)
        parseHeader(header).forEach { (k, v) -> builder.header(k, v) }
        cookie?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        if (body != null) {
            builder.post(
                okhttp3.RequestBody.create(
                    "application/json; charset=utf-8".toMediaTypeOrNull(),
                    body,
                )
            )
        } else {
            builder.get()
        }
        return builder.build()
    }

    private fun parseHeader(header: String?): Map<String, String> {
        if (header.isNullOrBlank()) return emptyMap()
        return header.split("\n", "&#10;").mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null
            else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }.toMap()
    }
}

/** 桩：网络返回包装 */
class OkResult(
    @JvmField val code: Int = 0,
    @JvmField val body: String = "",
    @JvmField val cookie: String? = null,
    @JvmField val headers: Headers? = null,
    @JvmField val error: String? = null,
) {
    val isSuccessful: Boolean get() = code in 200..299
    val content: String get() = body

    /**
     * 调试用描述。
     * 注意不能命名为 toString(@JvmOverloads)：会与 Any.toString() 的 JVM 签名冲突
     * （"toString()Ljava/lang/String;"），导致 accidental override 编译错误。
     */
    @JvmOverloads
    fun describe(includeHeaders: Boolean = false): String = buildString {
        append("code=").append(code)
        if (error != null) append(", error=").append(error)
        if (includeHeaders && headers != null) append(", headers=").append(headers)
    }

    override fun toString(): String = describe()
}
