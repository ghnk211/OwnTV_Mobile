package com.github.catvod.crawler

import tv.own.owntv.mobile.tvbox.util.TbLog

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Surface
import android.view.WindowManager
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 桩：宿主向 spider 注入的能力集合（对齐 TVBoxOS `com.github.catvod.crawler.SpiderApi`）。
 *
 * ## 它是什么、谁调它
 * TVBoxOS 的 `JarLoader.getSpider` 在实例化 spider 后、`init(Context, ext)` 之前会调
 * `spider.initApi(new SpiderApi())`。jar 侧的典型用法是 override 基类的空
 * `initApi` 把 api 存进字段，之后在 `homeContent`/`playerContent` 里调：
 * ```
 * this.api.log("xxx")            // 日志转发宿主
 * this.api.multiReq(jsonArray)   // 多路并发请求（宿主网络栈）
 * this.api.webParse(url, flag)   // WebView 解析
 * this.api.getAddress(true)      // 本地代理地址
 * ```
 * **宿主不调用 `initApi` 就等于这些调用全 NPE** —— 本类只是让签名存在，
 * 注入动作在 `JarSpider.init` 里完成（对齐参考顺序）。
 *
 * ## 与 TVBoxOS 实现的差异（为什么不是照抄）
 * TVBoxOS 的 `getAddress/getPort/webParse` 都指向它的本地代理服务（RemoteServer）。
 * 本工程**尚无 jar 侧本地代理服务**（wexguard 分析文档 P2，需真机确认真实 dex
 * 是否走 `proxy://` 链路后再定），故这三个方法按"空转"实现：
 * - `getAddress/getPort` 返回 ""（没有服务就没有地址，让 jar 早失败早暴露）
 * - `webParse` **原样返回入参 url** —— 本工程的解析兜底在播放链路上
 *   （`PlayResult.parse=1` → 嗅探引擎 WebView 解析），jar 拿到 url 塞进
 *   playerContent 结果后，归一化层会判"不是流地址 → parse=1"走嗅探，
 *   语义上正好等价于 TVBoxOS 的 proxy://go=SuperParse，只是解析发生在宿主的另一段。
 *
 * 网络相关的 `log`/`multiReq` 走宿主真实网络栈（与 jar 自己 OkHttp 同一套
 * relaxed-TLS client，见 [SpiderNet]）。
 */
class SpiderApi {

    /** 日志转发宿主 Timber（jar 侧高频调用，必须永不抛） */
    fun log(msg: String?) {
        runCatching { TbLog.tag(TAG).d(msg.orEmpty()) }
    }

    /**
     * 本地代理地址（对齐 TVBoxOS：指向 RemoteServer 的 `/proxy`）。
     *
     * 2026-10-19 起本工程有了真实服务（[tv.own.owntv.mobile.tvbox.engine.proxy.JarProxyHub]），
     * 未启动时仍返回 "" —— 没有服务就不给地址，让 jar 早失败早暴露，
     * 而不是拼出 `127.0.0.1:0/proxy` 这种看似合法的死链。
     */
    fun getAddress(local: Boolean): String =
        if (tv.own.owntv.mobile.tvbox.engine.proxy.JarProxyHub.isReady) {
            "http://127.0.0.1:${tv.own.owntv.mobile.tvbox.engine.proxy.JarProxyHub.port}/proxy"
        } else {
            ""
        }

    /** 本地代理端口。无服务时返回 ""。 */
    fun getPort(): String =
        if (tv.own.owntv.mobile.tvbox.engine.proxy.JarProxyHub.isReady) {
            tv.own.owntv.mobile.tvbox.engine.proxy.JarProxyHub.port.toString()
        } else {
            ""
        }

    /**
     * 屏幕方向（部分源按横竖屏给不同播放策略）。
     * 拿不到 Activity/WindowManager 时退 `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`
     * （与 TVBoxOS 的兜底一致）。
     */
    fun getScreenOrientation(): Int = runCatching {
        val context: Context = tv.own.owntv.mobile.tvbox.CatvodHost.appContext()
        val activity = context as? Activity
        val cfg = (activity ?: context).resources.configuration
        val rotation = ((activity ?: context).getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        when (cfg.orientation) {
            Configuration.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Configuration.ORIENTATION_LANDSCAPE ->
                if (rotation == Surface.ROTATION_90) ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }.getOrDefault(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)

    /**
     * 多路并发请求（对齐 TVBoxOS `SpiderApi.multiReq`）。
     *
     * 入参 JsonArray，每项形如：
     * ```json
     * {"url":"...","method":"GET|POST","headers":{...},"data":"...|{}","postType":"form|json"}
     * ```
     * 返回同序的 JsonArray：响应体是 JSON 就解析后放入，否则包成字符串元素。
     * 任何单项失败都只让该项为 ""，不拖垮整批（与 TVBoxOS 一致）。
     */
    fun multiReq(array: JsonArray?): String {
        if (array == null || array.size() == 0) return ""
        return runCatching {
            val objects = array.filter { it.isJsonObject }.map { it.asJsonObject }
            if (objects.isEmpty()) return ""
            val pool = Executors.newFixedThreadPool(minOf(objects.size, MAX_MULTIREQ_THREADS))
            try {
                // 显式 Callable：`submit { request(obj) }` 会被重载解析吃掉返回值
                // （Runnable 的 lambda 允许 Unit 强转），拿到 Future<*> 而非 Future<String>
                val futures: List<Future<String>> = objects.map { obj ->
                    pool.submit(java.util.concurrent.Callable { request(obj) })
                }
                JsonArray().apply { futures.forEach { add(toResult(it.get())) } }.toString()
            } finally {
                pool.shutdown()
            }
        }.getOrElse { t ->
            TbLog.tag(TAG).w(t, "multiReq 失败")
            ""
        }
    }

    /**
     * WebView 解析。无本地代理服务时原样返回 url（见类注释）——
     * 宿主播放链路的嗅探引擎会接着解析它。
     */
    fun webParse(url: String?, flag: String?): String = url.orEmpty()

    // ---------------- 内部 ----------------

    private fun request(obj: JsonObject): String = runCatching {
        val url = string(obj, "url")
        if (url.isEmpty()) return ""
        val method = string(obj, "method")
        val headers = headers(obj.get("headers"))
        val builder = Request.Builder().url(url).headers(headers)
        if ("POST".equals(method, ignoreCase = true)) builder.post(body(obj))
        // 与 jar 自身网络同一套 relaxed-TLS client（自签证书站点必需）
        SpiderNet.client().newCall(builder.build()).execute().use { resp: Response ->
            resp.body?.string().orEmpty()
        }
    }.getOrElse { t ->
        TbLog.tag(TAG).d("multiReq 单项失败：%s", t.toString())
        ""
    }

    private fun body(obj: JsonObject): RequestBody {
        val data = obj.get("data")
        if (data == null || data.isJsonNull) {
            return RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), "")
        }
        if ("form".equals(string(obj, "postType"), ignoreCase = true) && data.isJsonObject) {
            val form = FormBody.Builder()
            data.asJsonObject.entrySet().forEach { e -> form.add(e.key, e.value.asString) }
            return form.build()
        }
        val text = if (data.isJsonPrimitive) data.asString else data.toString()
        return RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), text)
    }

    private fun headers(element: JsonElement?): Headers {
        if (element == null || element.isJsonNull || !element.isJsonObject) {
            return Headers.Builder().build()
        }
        // 不用 Headers.of(Map)：OkHttp 4.12 起它是 deprecated(error)，
        // 替换扩展 toHeaders() 又依赖具体版本，手搓 Builder 最稳。
        val builder = Headers.Builder()
        element.asJsonObject.entrySet().forEach { e ->
            val v = e.value
            builder.add(e.key, if (v.isJsonPrimitive) v.asString else v.toString())
        }
        return builder.build()
    }

    private fun toResult(text: String): JsonElement {
        if (text.isEmpty()) return JsonPrimitive("")
        val trim = text.trim()
        if (trim.startsWith("{") || trim.startsWith("[")) {
            runCatching { return JsonParser.parseString(trim) }
        }
        return JsonPrimitive(text)
    }

    private fun string(obj: JsonObject, key: String): String {
        val element = obj.get(key)
        return if (element == null || element.isJsonNull) "" else element.asString
    }

    private companion object {
        const val TAG = "SpiderApi"

        /** 并发上限：与 TVBoxOS 一致（min(项数, 6)），避免大配置把线程打满 */
        const val MAX_MULTIREQ_THREADS = 6
    }
}
