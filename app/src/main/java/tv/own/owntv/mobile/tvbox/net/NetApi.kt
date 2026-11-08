package tv.own.owntv.mobile.tvbox.net

import tv.own.owntv.mobile.tvbox.util.TbLog

import tv.own.owntv.mobile.tvbox.util.TextUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool

/**
 * 面向业务的网络门面：CMS 引擎、配置导入、jar/js 下载都走这里。
 * 与 [OkHttpProvider] 的区别：本类负责拼接 URL、注入站点标记、容错返回。
 */
class NetApi(
    private val clientProvider: () -> OkHttpClient,
    private val downloadProvider: () -> OkHttpClient,
) {

    data class TextResponse(
        val code: Int,
        val body: String,
        val finalUrl: String,
        val error: String? = null,
    ) {
        val isSuccess: Boolean get() = code in 200..299 && error == null
    }

    data class BytesResponse(
        val code: Int,
        val bytes: ByteArray?,
        val finalUrl: String,
        val contentType: String?,
        val error: String? = null,
    ) {
        val isSuccess: Boolean get() = code in 200..299 && bytes != null && error == null
    }

    /** GET 文本：用于 CMS 接口与配置拉取 */
    suspend fun getText(
        url: String,
        siteKey: String? = null,
        headers: Map<String, String>? = null,
        timeoutMs: Long = 20_000,
        ua: String? = null,
    ): TextResponse = withContext(Dispatchers.IO) {
        val target = TextUtil.absoluteUrl(url, null)
        if (target.isEmpty()) {
            return@withContext TextResponse(-1, "", "", "空 URL")
        }
        var lastError: String? = null
        // 方案 4.3：配置内多 URL 兜底由调用方处理；此处仅做 1 次重试。
        // P2-6：**只有网络异常才重试**。HTTP 4xx/5xx 是服务端给出的明确答复，
        // 重试同址只是把坏源首页的等待翻倍（死源请求 ×2 才进外层超时）。
        repeat(2) { attempt ->
            try {
                val req = Request.Builder()
                    .url(target)
                    .get()
                    .apply {
                        // 显式 UA 优先于拦截器注入的默认 UA（UA 门控兜底重试依赖这一点）
                        ua?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
                        siteKey?.let { header(SiteMark.HEADER, SiteMark.encode(it)) }
                        // 配置里的自定义 header 同样可能含中文（源站要求 Referer 带中文路径等），
                        // 一律走 safeHeader：写不进去就跳过，绝不让整个请求崩掉。
                        headers?.forEach { (k, v) -> safeHeader(k, v) }
                    }
                    .build()
                clientProvider().newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        return@withContext TextResponse(resp.code, body, resp.request.url.toString())
                    }
                    // 非 2xx 一律不重试（含 5xx）：响应已到达，重试无意义
                    return@withContext TextResponse(
                        resp.code, body, resp.request.url.toString(), "HTTP ${resp.code}"
                    )
                }
            } catch (e: java.io.IOException) {
                // 只有 IOException（连接失败/超时/中断）才重试一次
                lastError = e.message ?: e.javaClass.simpleName
                TbLog.tag(TAG).w( "GET %s 第 %d 次失败: %s", target, attempt + 1, lastError)
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                return@withContext TextResponse(-1, "", target, lastError)
            }
        }
        TextResponse(-1, "", target, lastError ?: "请求失败")
    }

    /** GET 二进制：jar / js 下载 */
    suspend fun getBytes(
        url: String,
        timeoutMs: Long = 60_000,
        ua: String? = null,
    ): BytesResponse = withContext(Dispatchers.IO) {
        val target = TextUtil.absoluteUrl(url, null)
        if (target.isEmpty()) return@withContext BytesResponse(-1, null, "", null, "空 URL")
        try {
            val req = Request.Builder().url(target).get().apply {
                // 显式 UA 优先于下载链默认 UA（UA 门控兜底重试依赖这一点）
                ua?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
            }.build()
            downloadProvider().newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes()
                if (resp.isSuccessful && bytes != null) {
                    BytesResponse(resp.code, bytes, resp.request.url.toString(), resp.header("Content-Type"))
                } else {
                    BytesResponse(resp.code, bytes, resp.request.url.toString(), resp.header("Content-Type"), "HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            TbLog.tag(TAG).w( "GET bytes %s 失败: %s", target, e.message)
            BytesResponse(-1, null, target, null, e.message ?: e.javaClass.simpleName)
        }
    }

    /** POST 表单：部分 CMS 源搜索需 POST */
    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        siteKey: String? = null,
        headers: Map<String, String>? = null,
    ): TextResponse = withContext(Dispatchers.IO) {
        val target = TextUtil.absoluteUrl(url, null)
        if (target.isEmpty()) return@withContext TextResponse(-1, "", "", "空 URL")
        try {
            val body = form.entries.joinToString("&") { (k, v) ->
                "${enc(k)}=${enc(v)}"
            }.toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val req = Request.Builder()
                .url(target)
                .post(body)
                .apply {
                    siteKey?.let { header(SiteMark.HEADER, SiteMark.encode(it)) }
                    headers?.forEach { (k, v) -> safeHeader(k, v) }
                }
                .build()
            clientProvider().newCall(req).execute().use { resp ->
                TextResponse(
                    resp.code,
                    resp.body?.string().orEmpty(),
                    resp.request.url.toString(),
                    if (resp.isSuccessful) null else "HTTP ${resp.code}",
                )
            }
        } catch (e: Exception) {
            TextResponse(-1, "", target, e.message ?: e.javaClass.simpleName)
        }
    }

    /** HEAD：域名预热（方案 8.2） */
    suspend fun head(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).head().build()
            clientProvider().newCall(req).execute().use { it.code < 500 }
        }.getOrDefault(false)
    }

    /**
     * 通用请求：drpy JS 侧 `req(url, obj)` 的唯一落点（方案 5.4 / M1）。
     *
     * ## 与 [getText] 的区别
     * `getText` 只覆盖"GET 拿文本"，而 drpy 的 `request()` 会传
     * `method` / `body` / `data`(表单) / `headers` / `redirect` / `timeout`，
     * 并且**要读响应头**（`withHeaders` 时 JS 侧要取 `set-cookie`）。
     * 因此这里一次性把这些能力都提供出去，返回值同时带 content、headers、bytes。
     *
     * @param body 原始请求体（字符串，已由调用方拼好）；与 [form] 二选一
     * @param form 表单字段，走 `application/x-www-form-urlencoded`
     * @param buffer 需要二进制语义时返回字节（drpy 的 `obj.buffer` / `toBase64`）
     */
    suspend fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String>? = null,
        body: String? = null,
        form: Map<String, String>? = null,
        timeoutMs: Long = 20_000,
        followRedirects: Boolean = true,
        siteKey: String? = null,
        ua: String? = null,
        buffer: Boolean = false,
    ): RawResponse = withContext(Dispatchers.IO) {
        val target = TextUtil.absoluteUrl(url, null)
        if (target.isEmpty()) return@withContext RawResponse(-1, "", emptyMap(), null, "", "空 URL")

        val verb = method.trim().uppercase().ifEmpty { "GET" }
        val raw = body?.takeIf { it.isNotEmpty() }
        val requestBody = when {
            raw != null -> raw.toRequestBody(null)
            !form.isNullOrEmpty() -> form.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            verb == "POST" || verb == "PUT" || verb == "PATCH" || verb == "DELETE" ->
                "".toRequestBody(null)
            else -> null
        }

        val builder = Request.Builder().url(target)
        try {
            builder.method(verb, requestBody)
        } catch (e: IllegalArgumentException) {
            // 非法 method（如 "get " 带空格）直接降级为 GET，不让整个请求崩
            TbLog.tag(TAG).w("非法 method=%s，降级为 GET", method)
            builder.method("GET", null)
        }
        builder.apply {
            ua?.takeIf { it.isNotBlank() }?.let { safeHeader("User-Agent", it) }
            siteKey?.let { header(SiteMark.HEADER, SiteMark.encode(it)) }
            headers?.forEach { (k, v) -> safeHeader(k, v) }
        }

        val callClient = clientProvider()
        // 关闭跟随重定向要换一个 client 实例（OkHttp 的该项在 builder 上，不在 request 上）
        val baseClient = if (followRedirects) callClient else callClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        // read/write 收紧到 min(10s, timeoutMs)：空闲后被链路静默掐断的半开连接
        // （adb reverse / 代理 / NAT 都会掐）要等 read timeout 才暴露，
        // 20s 默认值让"死等 → JS 拿空串 → JSON.parse 报错"的链路长到不可用。
        val effTimeout = timeoutMs.coerceIn(3_000, 30_000)
        val client = baseClient.newBuilder()
            .readTimeout(effTimeout.coerceAtMost(10_000), TimeUnit.MILLISECONDS)
            .writeTimeout(effTimeout, TimeUnit.MILLISECONDS)
            .callTimeout(effTimeout * 2L, TimeUnit.MILLISECONDS)
            .build()

        // 幂等方法在 IOException 时重试一次，且**重试必须用全新空连接池**：
        // 复用半开连接时请求已写出、只会一直 read 挂起，抛 SocketTimeoutException 后
        // OkHttp 未必弹出该池中连接（timeout ≠ connection failure），第二次 Call
        // 可能又领到同一条死连接 —— 真机实测两次超时的根因。空池强制新建 TCP+TLS。
        val idempotent = verb == "GET" || verb == "HEAD"
        var lastError: String? = null
        repeat(if (idempotent) 2 else 1) { attempt ->
            val attemptClient = if (attempt == 0) client
            else client.newBuilder().connectionPool(ConnectionPool()).build()
            try {
                attemptClient.newCall(builder.build()).execute().use { resp ->
                    val bytes = if (buffer) resp.body?.bytes() else null
                    // 文本解码：优先用响应声明的 charset，缺省 UTF-8。
                    // drpy 侧对 gbk 源会自己走 `rule.encoding` + base64 再解码，
                    // 这里只保证"不乱码地拿到一份文本"。
                    val text = if (buffer) "" else resp.body?.string().orEmpty()
                    val hs = buildMap<String, String> {
                        for (i in 0 until resp.headers.size) {
                            // 同名 header 合并，JS 侧 Object.keys 取第一个即可
                            val name = resp.headers.name(i)
                            put(name, get(name)?.let { "$it, ${resp.headers.value(i)}" } ?: resp.headers.value(i))
                        }
                    }
                    return@withContext RawResponse(
                        code = resp.code,
                        content = text,
                        headers = hs,
                        bytes = bytes,
                        finalUrl = resp.request.url.toString(),
                        error = if (resp.isSuccessful) null else "HTTP ${resp.code}",
                    )
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                TbLog.tag(TAG).w("%s %s 第%d次失败: %s", verb, target, attempt + 1, lastError)
                // 只有网络层异常值得重试；其余（编码/状态等）直接失败返回
                if (e !is java.io.IOException) {
                    return@withContext RawResponse(-1, "", emptyMap(), null, target, lastError)
                }
            }
        }
        RawResponse(-1, "", emptyMap(), null, target, lastError ?: "请求失败")
    }

    /** [request] 的返回值：content 与 headers 都要给到 JS 侧 */
    data class RawResponse(
        val code: Int,
        val content: String,
        val headers: Map<String, String>,
        val bytes: ByteArray?,
        val finalUrl: String,
        val error: String? = null,
    ) {
        val isSuccess: Boolean get() = code in 200..399 && error == null
    }

    /** 把 JSON header 对象转成 Map（配置 sites[].header） */
    fun headerMap(headers: Headers?): Map<String, String> = buildMap {
        headers?.let { hs ->
            for (i in 0 until hs.size) put(hs.name(i), hs.value(i))
        }
    }

    /**
     * 写 header 的安全版本。
     *
     * OkHttp 的 header() 对非 Latin-1 字符直接抛 IllegalArgumentException，
     * 且这个异常发生在**请求构造阶段**，一个坏 header 会让整个请求失败。
     * 这里改为：非法值跳过并告警，不拖累主请求。
     */
    private fun Request.Builder.safeHeader(name: String, value: String): Request.Builder =
        try {
            header(name, value)
        } catch (e: IllegalArgumentException) {
            TbLog.tag(TAG).w( "跳过非法 header %s=%s（%s）", name, value.take(40), e.message)
            this
        }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun decodeUrl(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private companion object {
        const val TAG = "NetApi"
    }
}
