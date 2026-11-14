package tv.own.owntv.mobile.tvbox.net

import tv.own.owntv.mobile.tvbox.util.TbLog

import tv.own.owntv.mobile.tvbox.util.UaUtil
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 全局 OkHttp 单例（方案 7.1）。
 *
 * 关键点：
 * - Cache 目录仅用于"jar/js 下载去重"这类我们自己控制完整性的场景，
 *   源站响应不启用 HTTP 缓存（源站响应不可靠，业务缓存另做）。
 * - 拦截器链顺序：UA 注入(桩代理) → debug 日志 → 重试(1)
 */
object OkHttpProvider {

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var downloadClient: OkHttpClient? = null

    val cookieStore: MemoryCookieStore by lazy { MemoryCookieStore() }

    /**
     * jar 链独立 CookieStore（安全整改 H-2 / §3.1）。
     *
     * jar 客户端 TLS 校验放宽（信任所有证书 + 主机名全放行），同 Wi-Fi 攻击者可对其
     * 做中间人。若与主链共享 [cookieStore]，jar 链的每次请求都会自动携带主链在
     * 同域拿到的登录态 Cookie —— MITM jar 链即可截获本该属于主链的凭证。
     * 独立 store 后这条传递链被切断；jar 自身"首页拿 cookie 再出数据"仍在
     * 自己的 store 内闭环，不受影响。
     */
    private val jarCookieStore: MemoryCookieStore by lazy { MemoryCookieStore() }

    /**
     * 下载链独立 CookieStore（M-2 第 2 项）：jar/js/配置下载与登录态隔离。
     * 下载对象是代码工件，不需要携带任何站点的登录态。
     */
    private val downloadCookieStore: MemoryCookieStore by lazy { MemoryCookieStore() }

    /**
     * Python 采集源独立 CookieStore（R8 / §2.4 第 3 点）。
     *
     * py 脚本是不可信第三方代码，且自带 `requests.Session` 生态 ——
     * 与主链共享 Cookie 会把用户在别的源上的登录态暴露给它。
     */
    private val pyCookieStore: MemoryCookieStore by lazy { MemoryCookieStore() }

    @Volatile
    var debug: Boolean = false

    fun get(cacheRoot: File? = null): OkHttpClient =
        client ?: synchronized(this) {
            client ?: build(cacheRoot).also { client = it }
        }

    /** 下载专用（jar/js/配置），带磁盘缓存与更长超时，桩标记不适用 */
    fun download(cacheRoot: File? = null): OkHttpClient =
        downloadClient ?: synchronized(this) {
            downloadClient ?: buildDownload(cacheRoot).also { downloadClient = it }
        }

    private fun build(cacheRoot: File?): OkHttpClient {
        val b = OkHttpClient.Builder()
            .dispatcher(
                okhttp3.Dispatcher().apply {
                    maxRequests = 32
                    maxRequestsPerHost = 8
                }
            )
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .cookieJar(cookieStore)
            // keepAlive 从默认 5min 收紧到 20s：模拟器 SLIRP / adb reverse / 家用路由
            // 的 NAT 表项对空闲 TCP 静默回收通常在 15~30s，池里留一条"看起来活着"
            // 的半开连接，下一个请求写进去只会干等 read timeout（真机踩坑：
            // 分类页成功后 20s 再进详情，同一域名的请求两次超时）。
            // 20s 内的连续请求仍能正常复用，跨页面的新请求则强制重新握手。
            .connectionPool(ConnectionPool(8, 20, TimeUnit.SECONDS))
            .addInterceptor(LoopbackFastFailInterceptor())
            .addInterceptor(RefererInterceptor())
            .dns(currentDns())
        cacheRoot?.let { b.cache(Cache(File(it, "http"), 8L * 1024 * 1024)) }
        return b.build()
    }

    private fun buildDownload(cacheRoot: File?): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // H-2：下载链用独立 CookieStore，不携带主链登录态（下载的是代码工件）
            .cookieJar(downloadCookieStore)
            // ## 必须补 Referer：部分 CDN 对"无 Referer"的下载直接 403
            // 4k.json 的公共 spider jar 走这条链，缺 Referer 拿到的是 548 字节 HTML 错误页，
            // 被 looksLikeJar 拒绝后 csp_* 源全部报"找不到实现类"。
            // 早前这里只注 UA 不补 Referer，是"配置能导入但首页全挂"的直接原因。
            // loopback 快速失败同样覆盖下载链（jar 内部可能用任一 client 做代理探测）
            .addInterceptor(LoopbackFastFailInterceptor())
            .addInterceptor(RefererInterceptor())
            .addInterceptor { chain ->
                val req = chain.request()
                // 只在请求**未自带** UA 时补默认 UA。
                // 早年这里无条件覆盖，调用方显式指定的 UA（如 UA 门控兜底的
                // okhttp 系 UA，见 UaUtil.GATE_UA）会被打回浏览器 UA，
                // 兜底重试永远拿不到真资源 —— 真机实测影视仓 jar/直播均如此。
                val out = if (req.header("User-Agent").isNullOrBlank()) {
                    req.newBuilder().header("User-Agent", UaUtil.DEFAULT_UA).build()
                } else {
                    req
                }
                chain.proceed(out)
            }
            .dns(currentDns())
        // 下载 jar/js 时用磁盘缓存，命中缓存不发网络（方案 4.3）
        cacheRoot?.let { b.cache(Cache(File(it, "download"), 32L * 1024 * 1024)) }
        return b.build()
    }

    /**
     * 宿主 DNS（供 `com.github.catvod.crawler.Spider.safeDns()` 静态方法转调）。
     *
     * jar 用 `invoke-static` 拿这个方法来绕过运营商 DNS 劫持，
     * 所以必须暴露成宿主真实实现，而不是每次新建对象。
     *
     * jar 内部自建 OkHttp 时
     * 用它解析源站域名，Cloudflare 类域名在本地 DNS 解析不出来时才有活路。
     *
     * 动态代理到"当前" DNS：safeDns 是懒建单例，若在配置生效前就被 jar 取走，
     * 固定实例会一直用旧的兜底节点。
     */
    private val safeDnsInstance: Dns by lazy {
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                currentDns().lookup(hostname)
        }
    }

    fun safeDns(): Dns = safeDnsInstance

    /** 当前生效的 DNS：IPv4 优先（部分源站 IPv6 不可达）。DoH/按 host 代理按方案不搬。 */
    private fun currentDns(): Dns = InetAddressDns

    /**
     * 网络设置变更后重建 client：清空单例并 evict 连接池，下一个 `get()` 重新建。
     * 已发出的请求持有旧实例，能正常收尾。
     */
    fun rebuild() {
        runCatching { client?.dispatcher?.cancelAll() }
        runCatching { client?.connectionPool?.evictAll() }
        runCatching { downloadClient?.dispatcher?.cancelAll() }
        runCatching { downloadClient?.connectionPool?.evictAll() }
        runCatching { jarClientInstance?.dispatcher?.cancelAll() }
        runCatching { jarClientInstance?.connectionPool?.evictAll() }
        runCatching { jarStubClientInstance?.dispatcher?.cancelAll() }
        runCatching { jarStubClientInstance?.connectionPool?.evictAll() }
        runCatching { pyClientInstance?.dispatcher?.cancelAll() }
        runCatching { pyClientInstance?.connectionPool?.evictAll() }
        client = null
        downloadClient = null
        jarClientInstance = null
        jarStubClientInstance = null
        pyClientInstance = null
        TbLog.tag(TAG).i("OkHttp client 已重建")
    }

    private const val TAG = "OkHttpProvider"

    /**
     * spider jar 专用网络栈（供 `Spider.client()` 静态方法转调）。
     *
     * ## 为什么不能直接复用 get()
     *
     * 真机实测：vip.json 的「伊外」等源在 `homeContent` 里抛
     * ```
     * NullPointerException: com.google.gson.JsonElement.getAsString() on a null object reference
     *   at ...BasicChecker.check / PKIXMasterCertPathValidator.validate
     * ```
     * 堆栈底是 **SSL 证书链校验失败**。jar 解析接口拿到 null 字段后再调 getAsString()，
     * 于是 NPE 掩盖了真正的根因（连不上）。
     *
     * 生态里绝大多数 csp 源用的是**自签 / 证书链不全**的 CDN 域名，
     * 配套 jar 内部通常自带"信任所有证书"的 OkHttp。但我们通过 `Spider.client()`
     * 把**宿主的严格 OkHttpClient 交给了 jar**，jar 就被宿主的校验策略卡死 ——
     * 这正是"宿主复用网络栈"这个设计的副作用。
     *
     * 故 jar 走独立实例：连接池共享（避免 jar 与宿主各建一堆连接拖垮弱网），
     * 但 **Cookie 独立**（H-2：TLS 放宽链不再携带主链登录态），
     * TLS 校验放宽，与生态预期一致。
     *
     * 安全边界：只放宽**证书链与主机名**校验，不动明文流量、
     * 不加抓包中间人，也不影响宿主自身的请求（`get()` 仍是严格校验）。
     */
    fun jarClient(): OkHttpClient =
        jarClientInstance ?: synchronized(this) {
            jarClientInstance ?: buildJarClient().also { jarClientInstance = it }
        }

    @Volatile
    private var jarClientInstance: OkHttpClient? = null

    /**
     * 桩体系（`com.github.catvod.net.OkHttp`）专用客户端：主 client + 私网字面量拦截。
     *
     * ## 为什么单独给一个
     * 生态 jar 编译时依赖 TVBoxOS 的 `OkHttpUtils` / `OkHttp` 静态面，这些调用经
     * 宿主桩转到**主 client**（`ServiceLocator.netClient()`）—— 这是一条绕过
     * [jarClient] 的不可信代码网络路径（M-3）。jarClient 已挂私网拦截与 DNS 防护，
     * 这条路径也要补齐，否则 SSRF 面收不上。
     *
     * 只加拦截器，**不替换主 client**：主链自身要保留 `clan://<host>`
     * 局域网配置共享（`LocalSource.parseClan`，一台盒子给全家设备共享配置）。
     * 派生实例共享主 client 的连接池 / Cookie / 超时策略，行为与原来一致。
     */
    fun jarStubClient(cacheRoot: File? = null): OkHttpClient =
        jarStubClientInstance ?: synchronized(this) {
            jarStubClientInstance ?: buildJarStubClient(cacheRoot).also { jarStubClientInstance = it }
        }

    @Volatile
    private var jarStubClientInstance: OkHttpClient? = null

    private fun buildJarStubClient(cacheRoot: File?): OkHttpClient =
        get(cacheRoot).newBuilder()
            .addInterceptor(LoopbackFastFailInterceptor(blockPrivateNet = true))
            .build()

    /**
     * Python 采集脚本专用客户端（方案 §2.4 第 3 点，相对 VOD 唯一有意加强的部分）。
     *
     * 与 [jarStubClient] 同形：派生自主 client，保留 UA/Referer/连接池，
     * 但补上私网字面量拦截与 DNS 私网防护，并换成独立 CookieStore。
     * py 脚本的 `exec_module` 会执行顶层任意代码，这条出口不能保留
     * `clan://<host>` 的局域网访问面（主链刻意不拦，见 [build] 注释）。
     */
    fun pyClient(cacheRoot: File? = null): OkHttpClient =
        pyClientInstance ?: synchronized(this) {
            pyClientInstance ?: buildPyClient(cacheRoot).also { pyClientInstance = it }
        }

    @Volatile
    private var pyClientInstance: OkHttpClient? = null

    private fun buildPyClient(cacheRoot: File?): OkHttpClient =
        get(cacheRoot).newBuilder()
            .cookieJar(pyCookieStore)
            .addInterceptor(LoopbackFastFailInterceptor(blockPrivateNet = true))
            .dns(PrivateNetGuardDns(currentDns()))
            .build()

    private fun buildJarClient(): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // H-2：jar 链独立 CookieStore（原来是共享 store，jar 链被 MITM
            // 即可截获主链同域登录态，见 jarCookieStore 注释）
            .cookieJar(jarCookieStore)
            // 连接池也共享，避免 jar 请求与宿主请求各建一堆连接拖垮弱网
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            // M-3：jar 是不可信第三方代码 —— loopback 快速失败 + 私网段硬拦截
            // （主链不拦，要保留 clan://<host> 局域网配置共享）
            .addInterceptor(LoopbackFastFailInterceptor(blockPrivateNet = true))
            .addInterceptor(RefererInterceptor())
            // M-3：DNS 层兜底 —— 域名解析到私网段同样拒绝（拦字面量之外的形态）
            .dns(PrivateNetGuardDns(currentDns()))
        runCatching {
            // Android 的 SSLContext 没有 trustManager 属性（那是 JVM 扩展），
            // 所以必须把同一个 TM 实例同时用于 init 与 sslSocketFactory 的校验参数
            val tm = trustAllManager()
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), java.security.SecureRandom())
            b.sslSocketFactory(ctx.socketFactory, tm)
            b.hostnameVerifier { _, _ -> true }
        }.onFailure { TbLog.w("jar 客户端 TLS 放宽失败，回退默认校验：%s", it.message) }
        return b.build()
    }

    /** 信任所有证书的 X509TrustManager（仅供 jar 源使用） */
    private fun trustAllManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val delegates = tmf.trustManagers.filterIsInstance<X509TrustManager>()
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> =
                delegates.firstOrNull()?.acceptedIssuers ?: emptyArray()
        }
    }

    /** 配置切换时调用：清 cookie + 断连接池 */
    fun onConfigSwitched() {
        runCatching { cookieStore.clearAll() }
        runCatching { jarCookieStore.clearAll() }
        runCatching { downloadCookieStore.clearAll() }
        runCatching { get().dispatcher.cancelAll() }
        runCatching { jarClient().dispatcher.cancelAll() }
        runCatching { connectionPoolOf(client)?.evictAll() }
    }

    private fun connectionPoolOf(c: OkHttpClient?): ConnectionPool? = runCatching {
        c?.connectionPool
    }.getOrNull()

    fun shutdownForTest() {
        runCatching { client?.dispatcher?.executorService?.shutdown() }
        runCatching { downloadClient?.dispatcher?.executorService?.shutdown() }
        runCatching { jarClientInstance?.dispatcher?.executorService?.shutdown() }
        runCatching { client?.cache?.close() }
        runCatching { downloadClient?.cache?.close() }
        client = null
        downloadClient = null
        jarClientInstance = null
        jarStubClientInstance = null
    }
}

