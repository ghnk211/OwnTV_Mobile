package tv.own.owntv.mobile.tvbox.engine.py

import android.util.Base64
import android.util.Log
import okhttp3.Request
import tv.own.owntv.mobile.tvbox.net.OkHttpProvider
import tv.own.owntv.mobile.tvbox.net.SiteMark
import tv.own.owntv.mobile.tvbox.util.TbLog
import tv.own.owntv.mobile.tvbox.util.TextUtil
import java.util.concurrent.TimeUnit

/**
 * Python 侧的宿主能力桥（docs/TVbox-PY源迁移方案.md §2.4）。
 *
 * 用法（Python 侧）：
 * ```python
 * from tv.own.owntv.mobile.tvbox.engine.py import PyHost
 * raw = PyHost.fetch(url, headers_json, site_key, timeout_ms)   # 返回 JSON 文本
 * ```
 *
 * 设计要点：
 * - **静态方法**（@JvmStatic）：Python 经 Chaquopy 的 Java 代理按类名调用，
 *   R8 混淆改名会直接断链；proguard-chaquopy.pro 已 keep 本包全部类。
 * - **同步阻塞**：调用发生在 Python 线程内（PySpider 的独立执行器之下），
 *   直接走宿主 Python 客户端同步 execute。
 * - **网络隔离**：走 [OkHttpProvider.pyClient]（私网拦截 + 独立 CookieStore），
 *   py 脚本是不可信第三方代码，不能复用主链的局域网/登录态能力。
 */
object PyHost {

    private const val TAG = "PyHost"

    /** fetch 返回 JSON 的 content 字段编码：base64（保真传输二进制，Python 侧解码） */
    private const val B64_FLAGS = Base64.NO_WRAP

    /**
     * 同步 GET：桥到宿主 OkHttp。
     *
     * @param headersJson JSON 对象文本（脚本自定义请求头），空串表示无
     * @param siteKey 站点 key，打上 [SiteMark.HEADER] 埋点
     * @param timeoutMs 本次请求超时（毫秒）
     * @return JSON 文本：`{"code":int,"url":str,"content":base64,"charset":str}`
     *   任何失败都返回 code=-1 的 JSON —— Python 侧按 Response 对象消费，不让异常击穿脚本
     */
    @JvmStatic
    fun fetch(url: String, headersJson: String, siteKey: String, timeoutMs: Long): String {
        val target = runCatching { TextUtil.absoluteUrl(url, null) }.getOrDefault(url)
        if (target.isBlank()) return errorJson(url, "空 URL")
        return try {
            val builder = Request.Builder().url(target).get()
            parseHeaders(headersJson).forEach { (k, v) ->
                // 配置/脚本里的 header 可能含非法值，写不进去就跳过，不让整个请求崩掉
                runCatching { builder.header(k, v) }
                    .onFailure { TbLog.tag(TAG).d("跳过非法请求头 %s: %s", k, it.toString()) }
            }
            if (siteKey.isNotBlank()) {
                builder.header(SiteMark.HEADER, SiteMark.encode(siteKey))
            }
            // 派生 client 只改超时，拦截器链（私网防护/UA/Referer）全继承
            val client = OkHttpProvider.pyClient().newBuilder()
                .callTimeout(timeoutMs.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
                .build()
            client.newCall(builder.build()).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                buildJson(
                    code = resp.code,
                    url = resp.request.url.toString(),
                    content = bytes,
                    contentType = resp.header("Content-Type"),
                )
            }
        } catch (t: Throwable) {
            // catch Throwable：OkHttp 的 SSL/协议错误是 Error 系，Exception 兜不住
            TbLog.tag(TAG).w(t, "fetch 失败：%s", target)
            errorJson(url, t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * 脚本日志转发（base/loader.py 的 stdout/stderr 重定向出口）。
     *
     * @param level android.util.Log 优先级常量（2=DEBUG 3=INFO 4=WARN 5=ERROR）
     */
    @JvmStatic
    fun log(level: Int, tag: String, msg: String) {
        val safeTag = tag.ifBlank { "python" }
        val api = TbLog.tag(safeTag)
        when (level) {
            Log.ASSERT, Log.ERROR -> api.e(msg)
            Log.WARN -> api.w(msg)
            Log.INFO -> api.i(msg)
            else -> api.d(msg)
        }
    }

    /** 响应 JSON：成功/失败统一形态 */
    private fun buildJson(code: Int, url: String, content: ByteArray, contentType: String?): String {
        val obj = com.google.gson.JsonObject().apply {
            addProperty("code", code)
            addProperty("url", url)
            addProperty("content", Base64.encodeToString(content, B64_FLAGS))
            addProperty("charset", charsetOf(contentType))
        }
        return obj.toString()
    }

    /** 失败 JSON（code=-1），Python 侧 Response 按空内容消费 */
    private fun errorJson(url: String, error: String): String {
        val obj = com.google.gson.JsonObject().apply {
            addProperty("code", -1)
            addProperty("url", url)
            addProperty("content", "")
            addProperty("charset", "")
            addProperty("error", error)
        }
        return obj.toString()
    }

    /** 从 Content-Type 提取字符集（无声明时由 Python 侧逐级兜底解码） */
    private fun charsetOf(contentType: String?): String? {
        val m = contentType?.let { CHARSET.find(it) } ?: return null
        return m.groupValues[1].trim().takeIf { it.isNotBlank() }
    }

    /** 解析脚本传入的请求头 JSON；非法 JSON 返回空（宁可少带头，不崩请求） */
    private fun parseHeaders(headersJson: String): Map<String, String> {
        if (headersJson.isBlank()) return emptyMap()
        return runCatching {
            val obj = com.google.gson.JsonParser.parseString(headersJson)
            if (!obj.isJsonObject) return emptyMap()
            obj.asJsonObject.entrySet().mapNotNull { e ->
                val v = e.value
                if (v != null && v.isJsonPrimitive) e.key to v.asString else null
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** Content-Type 里的 charset 提取正则 */
    private val CHARSET = Regex("""charset=([^;\s]+)""", RegexOption.IGNORE_CASE)
}