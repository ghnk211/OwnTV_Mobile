package tv.own.owntv.mobile.tvbox.engine.proxy

import tv.own.owntv.mobile.tvbox.util.TbLog

import com.github.catvod.crawler.Spider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * jar 侧本地代理的总枢纽：服务生命周期 + `siteKey → spider` 实例表 + 请求分发。
 *
 * ## 它解决什么问题（真机实证 2026-10-19）
 * wexguard 加固壳站点（「韩剧┃秒播」`WexAiHanXiaoQuanGuard` 等）的
 * `playerContent` 返回 `http://127.0.0.1:9978/proxy?do=hxq&url=<真实m3u8>`，
 * 需要宿主起一个本地 HTTP 服务回调 jar 的 `proxy`/`proxyLocal` 才能拿到内容。
 * 没有这一层时该源**完全无法播放**（详见 [JarProxyServer] 的类注释）。
 *
 * ## siteKey 从哪来
 * spider 拼 URL 时**不带 siteKey**（日志实证：`?do=hxq&url=...`），
 * 而分发时必须知道该回调哪个 spider 实例。参考项目
 * `ApiConfig.getCurrentProxySource` 的做法是维护一个「当前播放源 key」兜底，
 * 并在缺省时把 key **写回 params**（jar 的 proxy 实现可能读它）。
 * 本类照做：[onPlay] 记最近一次 `playerContent` 的站点，
 * [dispatch] 在缺省时用它补上。
 *
 * ## 为什么不在播放 URL 上直接追加 siteKey
 * 改 URL 会影响播放器对相对地址的解析（m3u8 里的相对分片路径），
 * 收益却只是"少一次兜底"，不值当。
 */
object JarProxyHub {

    private val server = JarProxyServer { params -> dispatch(params) }

    /** siteKey → 已初始化的 spider 实例（[tv.own.owntv.mobile.tvbox.engine.jar.JarEngine] 每次 obtain 覆盖） */
    private val spiders = ConcurrentHashMap<String, Spider>()

    /** 最近一次 playerContent 的站点 key（参考项目 currentPlaySourceKey） */
    private val currentPlaySiteKey = AtomicReference<String?>(null)

    /** 已成功启动的端口；0 表示未启动/启动失败 */
    @Volatile
    private var boundPort: Int = 0

    /**
     * 本地代理是否可用。
     *
     * 给播前判定用：不可用时，`127.0.0.1` 这类地址**不能**外发给第三方解析器，
     * 应当直接失败并提示（否则白等 10~86 秒，见 [isSelfProxyUrl]）。
     */
    val isReady: Boolean get() = boundPort > 0 && server.isRunning()

    /** 实际监听端口（未启动为 0） */
    val port: Int get() = boundPort

    /**
     * 懒启动：JarEngine 首次成功拿到 spider 时调用。
     *
     * 不放在 `Application.onCreate` —— 不碰 jar 源的配置不该白开一个端口。
     * 幂等，重复调用只做一次。
     */
    fun ensureStarted() {
        if (isReady) return
        synchronized(this) {
            if (isReady) return
            val p = server.start()
            if (p > 0) {
                boundPort = p
                // 关键：让走 `Proxy.getUrl()` 的那批 jar 拿到真实端口
                // （wexguard 系自己硬编码 9978，本工程首选端口也是它，两者一致）
                runCatching { com.github.catvod.Proxy.set(p) }
                    .onFailure { TbLog.tag(TAG).w(it, "Proxy.set 失败") }
            }
        }
    }

    /** 注册 spider 实例（JarEngine.obtain 成功后调用） */
    fun register(siteKey: String, spider: Spider) {
        if (siteKey.isBlank()) return
        spiders[siteKey] = spider
    }

    /** 记录当前播放源（JarSpider.playerContent 调用时） */
    fun onPlay(siteKey: String) {
        if (siteKey.isNotBlank()) currentPlaySiteKey.set(siteKey)
    }

    /** 站点释放时摘掉实例，避免持有已 destroy 的 spider */
    fun unregister(siteKey: String) {
        spiders.remove(siteKey)
    }

    fun stop() {
        server.stop()
        boundPort = 0
        spiders.clear()
    }

    // ---------------- 分发 ----------------

    /**
     * 把 `/proxy?do=xxx&...` 路由到对应 spider 的 `proxy(params)`。
     *
     * 返回值约定与参考项目一致：`Object[]{code, mime, InputStream, headers}`。
     * 任何异常都吞掉并返回 null —— 这里是 HTTP 服务线程，抛出去会断连接且无日志。
     */
    private fun dispatch(input: Map<String, String>): Array<Any?>? {
        val params = LinkedHashMap(input)
        val key = params["siteKey"]?.takeIf { it.isNotBlank() }
            ?: currentPlaySiteKey.get()
        if (key.isNullOrBlank()) {
            TbLog.tag(TAG).w("proxy 请求没有 siteKey，且无当前播放源：do=%s", params["do"])
            return null
        }
        // 参考项目同样会把补出来的 key 写回 params
        params.putIfAbsent("siteKey", key)

        val spider = spiders[key]
        if (spider == null) {
            TbLog.tag(TAG).w("proxy 找不到站点实例：siteKey=%s do=%s", key, params["do"])
            return null
        }
        val started = System.currentTimeMillis()
        val result = runCatching { spider.proxy(params) }
            .onFailure { TbLog.tag(TAG).w(it, "spider.proxy 异常：siteKey=%s do=%s", key, params["do"]) }
            .getOrNull()
        if (result != null && result.isNotEmpty()) {
            TbLog.tag(TAG).i(
                "proxy 完成（spider）：siteKey=%s do=%s code=%s（%d ms）",
                key, params["do"], result.getOrNull(0), System.currentTimeMillis() - started,
            )
            return result
        }

        // 第二层：jar 内部的**静态** `com.github.catvod.spider.Proxy.proxy(Map)`。
        //
        // 这不是宿主侧的 [com.github.catvod.Proxy]（那个只管端口记账），而是 jar dex 里
        // 自带的代理出口。参考项目 JarLoader.proxyInvoke 用 jar 自己的 DexClassLoader
        // `loadClass("com.github.catvod.spider.Proxy")` 拿到它再 `getMethod("proxy", Map)`。
        // wexguard 壳的 `do=hxq`（取真实 m3u8）走的正是这里 —— 只调 spider.proxy
        // 会得到空（真机实证：`spider.proxy 返回空：siteKey=WexHanXiaoQuan do=hxq`）。
        invokeJarStaticProxy(spider, params)?.let { r ->
            if (r.isNotEmpty()) {
                TbLog.tag(TAG).i(
                    "proxy 完成（jar 静态 Proxy）：siteKey=%s do=%s code=%s（%d ms）",
                    key, params["do"], r.getOrNull(0), System.currentTimeMillis() - started,
                )
                return r
            }
        }

        TbLog.tag(TAG).w("spider.proxy 返回空：siteKey=%s do=%s（%d ms）", key, params["do"], System.currentTimeMillis() - started)
        return null
    }

    /**
     * 反射调用 jar dex 内的静态代理出口。
     *
     * ## 为什么必须用 jar 的 ClassLoader
     * 该类的包名是 `com.github.catvod.spider.Proxy`，与宿主桩
     * `com.github.catvod.Proxy` **不是同一个类**。用宿主的 ClassLoader 去找，
     * 要么 ClassNotFound，要么（更糟）找到别的同名片状类。
     * spider 实例的 classLoader 正是加载它的 DexClassLoader，直接复用。
     */
    private fun invokeJarStaticProxy(spider: Spider, params: Map<String, String>): Array<Any?>? {
        return runCatching {
            val loader = spider.javaClass.classLoader ?: return null
            val clz = Class.forName(JAR_PROXY_CLASS, false, loader)
            val method = clz.getMethod("proxy", Map::class.java)
            method.invoke(null, params) as? Array<Any?>
        }.onFailure {
            // 绝大多数 jar 没有这个类，属于正常情况，不打 WARN 刷屏
            TbLog.tag(TAG).d("jar 无静态 Proxy 出口：%s", it.javaClass.simpleName)
        }.getOrNull()
    }

    /**
     * 这个地址是不是"指向本机代理"的地址。
     *
     * 用于播前闸门：`127.0.0.1:<port>/proxy?do=` 只有本机的 [JarProxyServer]
     * 能处理。把它 URL-encode 后交给第三方解析 API 是**纯浪费** ——
     * 对方服务器上的 127.0.0.1 是它自己，不是你的手机。
     */
    fun isSelfProxyUrl(url: String?): Boolean {
        val u = url?.trim()?.lowercase().orEmpty()
        if (u.isEmpty()) return false
        val isLoopback = u.startsWith("http://127.") ||
            u.startsWith("https://127.") ||
            u.startsWith("http://localhost") ||
            u.startsWith("https://localhost")
        if (!isLoopback) return false
        return u.contains("/proxy") && u.contains("do=")
    }

    private const val TAG = "JarProxyHub"

    /** jar dex 内的静态代理出口（**不是**宿主桩 `com.github.catvod.Proxy`） */
    private const val JAR_PROXY_CLASS = "com.github.catvod.spider.Proxy"
}
