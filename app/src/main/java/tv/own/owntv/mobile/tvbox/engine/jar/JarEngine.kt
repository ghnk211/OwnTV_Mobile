package tv.own.owntv.mobile.tvbox.engine.jar

import tv.own.owntv.mobile.tvbox.util.TbLog

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.SystemClock
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.config.model.TvBoxConfig
import tv.own.owntv.mobile.tvbox.engine.Engine
import tv.own.owntv.mobile.tvbox.engine.EngineKind
import tv.own.owntv.mobile.tvbox.engine.Spider
import tv.own.owntv.mobile.tvbox.util.FileUtil
import tv.own.owntv.mobile.tvbox.util.SourceHealth
import tv.own.owntv.mobile.tvbox.util.SpiderSpec
import tv.own.owntv.mobile.tvbox.util.onIo
import dalvik.system.DexClassLoader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Jar 引擎门面 + SpiderPool（方案 5.3 / 10.1）。
 *
 * 职责：
 * 1. 确保 spider jar 就绪（优先读预下载缓存，缺失/首次导入则即时下载）
 * 2. 建 DexClassLoader（parent-first，parent = 宿主 ClassLoader）
 * 3. 按站点反射实例化 csp 类，按 siteKey 缓存实例
 * 4. 配置切换时 `invalidateAll()` 释放全部实例与 loader
 *
 * ## 为什么 loader 也缓存
 * DexClassLoader 每次新建都会重新走 dexopt，一个 20MB 的 jar 反复建 loader 会耗数秒。
 * 同一 jar 文件路径对应同一份 dex，故按 jar 绝对路径缓存 loader；配置切换时整体丢弃。
 *
 * @param spiderJarDirProvider 返回公共 spider jar 所在目录（配置 spider 字段下载落点）
 * @param spiderMd5Provider 返回配置 `spider` 字段里声明的**固定 md5 集合**（小写）。
 *   用于在多个 jar 共处同一目录时精确挑出"配置说的那个公共 jar"，见 [pickJar]。
 * @param siteJarProvider 站点私有 jar（`site.jar` 字段）→ 本地文件；少数配置用它替换主jar
 */
class JarEngine(
    private val context: Context,
    private val spiderJarDirProvider: suspend () -> File?,
    private val spiderMd5Provider: suspend () -> Set<String> = { emptySet() },
    private val siteJarProvider: suspend (SiteConfig) -> File? = { null },
) : Engine {

    override val kind: EngineKind = EngineKind.SPIDER_JAR

    /** siteKey → Spider 实例 */
    private val spiders = ConcurrentHashMap<String, JarSpider>()

    /** jar 绝对路径 → DexClassLoader（parent-first） */
    private val loaders = ConcurrentHashMap<String, DexClassLoader>()

    /**
     * jar 绝对路径 → **加固壳引导结果**（见 [BootstrapResult]）。
     * 加固壳有全局 Init，只能跑一次，故按 jar 缓存判定结果。
     */
    private val bootstrapResults = ConcurrentHashMap<String, BootstrapResult>()

    /** 建实例的并发闸：避免同一站点被并发建出多个 Spider */
    private val buildMutex = Mutex()

    override fun supports(site: SiteConfig): Boolean =
        site.type == 3 && !site.isJsSite && site.spiderClassName != null

    /**
     * 站点 key → 最近一次加载失败原因。
     *
     * ## 为什么不用一个全局队列
     * 原来用 `ConcurrentLinkedQueue<String> lastError` 存所有站点的失败原因，
     * 供 `obtain` 抛异常时取用。但首页会**并发预热多个站点**
     * （A 的失败原因入队 → B 的失败原因入队 → A 抛异常时取到的是 B 的），
     * 于是 UI 上出现
     * ```
     * 「公众号：聚玩盒」加载失败：home: 「公众号：聚玩盒」加载失败：找不到实现类 com.github...DouDou
     * ```
     * 这种**同一句话嵌套两遍**的错位信息，且真正原因被前置噪音淹没。
     *
     * 改成按 siteKey 存就干净了：谁失败谁写自己的名字，互不干扰。
     */
    private val lastErrors = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun obtain(site: SiteConfig): Spider {
        spiders[site.key]?.let { return it }
        return buildMutex.withLock {
            // 双检：等锁期间可能已被别的协程建好
            spiders[site.key]?.let { return@withLock it }
            val created = create(site) ?: throw JarLoadException(
                    lastErrors[site.key] ?: "「${site.name}」spider jar 不可用"
                )
            spiders.putIfAbsent(site.key, created) ?: created.also {
                // putIfAbsent 返回非 null 说明已有别人抢先，销毁自己这份
                created.destroy()
            }
        }
    }

    /** 加载 + 实例化；任何一环失败返回 null（由 [obtain] 转成异常计入源健康） */
    private suspend fun create(site: SiteConfig): JarSpider? = onIo {
        val className = site.spiderClassName
        if (className.isNullOrBlank()) {
            fail(site, "api 不是 csp_* 形式：${site.api}")
            return@onIo null
        }

        val jarFile: File
        val jarKind: String
        // 私有 jar 的额外 dex 路径：**必须带上公共 jar**（见 loaderFor 注释）
        var extraDexPath: String? = null
        var isSiteJar = false
        val siteJar = siteJarProvider(site)
        if (siteJar != null) {
            jarFile = siteJar
            isSiteJar = true
            jarKind = "站点私有 jar（${siteJar.name}）"
            // ## 私有 jar 常依赖公共 jar 的 merge 工具类
            // 真机实测（fun.json 的 csp_GM 源）：gm.jar 实例化时抛
            // NoClassDefFoundError: com.github.catvod.spider.merge.y1.c
            // —— 这些工具类在 pg.jar（公共 jar）里，而只给私有 jar 建 ClassLoader
            // 的话它们不在类路径上。故把公共 jar 一并挂进 dexPath。
            val commonDir = spiderJarDirProvider()
            if (commonDir != null) {
                extraDexPath = pickJar(commonDir, spiderMd5Provider())?.absolutePath
                if (extraDexPath == null) {
                    TbLog.tag(TAG).w( "站点私有 jar 依赖公共 jar，但公共 jar 不可用：%s", site.name)
                }
            }
        } else {
            val jarDir = spiderJarDirProvider() ?: run {
                fail(site, "无可用 spider jar（配置 spider 字段为空或下载失败）")
                return@onIo null
            }
            // 配置 spider 字段声明的 md5 才是"公共 jar"的身份证明：
            // 同一目录可能躺着多个 jar（历史配置 / 站点私有 jar 落错位置），
            // 不按 md5 精确挑就只能按 mtime 猜，会选到不含本站实现类的那个。
            val preferMd5 = spiderMd5Provider()
            jarFile = pickJar(jarDir, preferMd5) ?: run {
                fail(site, "spider 目录无 jar：${jarDir.absolutePath}")
                return@onIo null
            }
            jarKind = describeCommonJar(jarFile, preferMd5)
        }

        val loader = loaderFor(jarFile, extraDexPath)
        if (loader == null) {
            fail(site, "DexClassLoader 创建失败：${jarFile.name}")
            return@onIo null
        }

        // 加固壳（aowu / wexguard 系）：真实实现在加密的 assets 里，必须先跑一次 jar 内的引导类。
        // 引导结果决定后续报错文案：是不是壳、引没引起来、是不是 ABI 不匹配。
        val boot = bootstrap(loader, jarFile)

        // P0-3 ABI 预检拦截：jar 里打包了 assets/wexguard_*.so（只有 ARM v7/v8 两份），
        // 而设备无 ARM —— 此时 System.load 必失败（UnsatisfiedLinkError）。
        // bootstrap 已经尝试过（带 ARM 转译的 x86_64 镜像仍可能成功），
        // 这里只在**实际没引起来**时用明确文案拦下，不再让用户对着
        // "实例化失败：InvocationTargetException" 猜。
        if (boot.shellDetected && !boot.isPacked) {
            fail(
                site,
                boot.abiReason
                    ?: boot.error?.message?.takeIf { it.isNotBlank() }?.let { "加固壳初始化失败：$it" }
                    ?: "加固壳初始化失败（Init 未就绪）",
            )
            return@onIo null
        }

        val spiderClass = resolveClass(loader, className)
        if (spiderClass == null) {
            val fallback = if (isSiteJar) {
                "（该站配置了私有 jar，若其下载失败已回退到公共 jar，实现类可能本就不在其中）"
            } else ""
            fail(
                site,
                "找不到实现类 ${candidateNames(className).take(2).joinToString(" / ")}" +
                    "等 ${candidateNames(className).size} 个候选" +
                    "（当前 jar=$jarKind）$fallback" +
                    noAllFilesAccessHint(boot)
            )
            return@onIo null
        }

        val instance = try {
            spiderClass.getDeclaredConstructor().newInstance()
        } catch (e: Exception) {
            // 常见于实现类构造函数需要 Context：此时用带 Context 的重载再试
            try {
                spiderClass.getDeclaredConstructor(Context::class.java).newInstance(context)
            } catch (e2: Throwable) {
                // P0-2：壳检测到但没引起来时，把"加固壳未引导"直接写进用户可见的报错，
                // 不再让用户对着"实例化失败"猜（根因在日志里：bootstrap 的 WARN 全栈）。
                val hint = when {
                    boot.abiRisk -> "；${boot.abiReason}"
                    boot.shellDetected && !boot.isPacked -> "；加固壳未引导，根因见日志"
                    else -> ""
                }
                fail(
                    site,
                    "实例化 ${spiderClass.name} 失败：" +
                        "${e2.javaClass.simpleName}${e2.message?.let { "：$it" } ?: ""}$hint"
                )
                return@onIo null
            }
        }

        // 必须 cast 到宿主桩的类型，否则五接口反射找不到（父类来自宿主 dex）
        val target = instance as? com.github.catvod.crawler.Spider
        if (target == null) {
            fail(
                site,
                "${spiderClass.name} 未继承宿主桩 Spider（类型被 jar 私有副本污染，parent-first 失效）：" +
                    "${instance.javaClass.classLoader}"
            )
            return@onIo null
        }

        lastErrors.remove(site.key)
        // 本地代理：jar 侧 `127.0.0.1:<port>/proxy?do=` 链路的前置条件。
        // 首次拿到 spider 时才起端口 —— 不碰 jar 源的配置不该白开一个监听。
        // 不注册实例的话，proxy 请求到了也无从回调（详见 JarProxyHub）。
        runCatching {
            tv.own.owntv.mobile.tvbox.engine.proxy.JarProxyHub.ensureStarted()
            tv.own.owntv.mobile.tvbox.engine.proxy.JarProxyHub.register(site.key, target)
        }.onFailure { TbLog.tag(TAG).w(it, "本地代理注册失败：%s", site.key) }
        // jar 来源用调用方给的 jarKind（私有/公共），不靠文件名猜 ——
        // 私有 jar 的落盘名也是纯 md5，按名字猜会把它误标成"公共 spider jar"
        TbLog.tag(TAG).i( "%s → %s（jar=%s）", site.name, spiderClass.name, jarKind)
        JarSpider(site, context, target)
    }

    /**
     * 后台预热公共 jar 的 ClassLoader（P0-3，对齐 TVBoxOS `loadJar` 的启动时机）。
     *
     * 配置解析成功后立即调用，把 dexopt + 加固壳解密从首个 homeContent 的超时预算里
     * 挪出来，与 UI 初始化重叠。**只建 loader 与壳引导，不实例化任何站点** ——
     * 后台触发 jar 内部网络请求既没必要，也会污染源健康统计。
     *
     * 失败静默：预热只是提前量，首个 obtain 的 `create` 路径会照常重试并给出准确报错。
     */
    suspend fun warmUp() {
        // 本地代理必须先于任何 spider 实例化起来：jar 的 init() 里可能读
        // Proxy.getPort() / SpiderApi.getPort() 并**缓存**结果，
        // 等 obtain 之后才注入端口就晚了（那批 jar 会一直用空地址）。
        runCatching { tv.own.owntv.mobile.tvbox.engine.proxy.JarProxyHub.ensureStarted() }
        val jarDir = runCatching { spiderJarDirProvider() }.getOrNull() ?: return
        val jar = pickJar(jarDir, runCatching { spiderMd5Provider() }.getOrDefault(emptySet())) ?: return
        val loader = loaderFor(jar, null) ?: return
        // 加固壳的 Init.init 解包（解密 assets 里的大 dex）也是首屏大头，一并提前
        bootstrap(loader, jar)
    }

    /**
     * 找不到实现类时的补充说明（**仅加固壳**）。
     *
     * ## 为什么要限定"加固壳"
     * 早前这里只要 App 没拿到 `MANAGE_EXTERNAL_STORAGE` 就附带权限提示，
     * 于是**普通 jar 缺实现类**（jar 不含该类、类名不一致）也会被告知"去授权"——
     * 真机实测（4k.json 的 csp_Mp4Mov）：公共 jar 里根本没有 Mp4Mov 类，
     * 报错却让用户去「配置管理」授全盘权限，授完照样失败，纯属误导。
     *
     * 真正需要这个权限的只有一种情况：**加固壳**（aowu/wexguard 系）的
     * `Init.init()` 解包会访问外部存储（真机实测 2026-10-09，本地 spider.jar +
     * 仅 SAF 目录授权，补上全盘权限后同一个 jar 立刻正常）。
     * 普通 jar 解包不碰外部存储，缺权限与"找不到实现类"无关。
     *
     * @param boot 该 jar 的引导结果。[BootstrapResult.shellDetected] 为 true 即"是壳"——
     *   含**检测到壳但没引起来**的情形（引导失败时报权限提示才有意义）。
     */
    private fun noAllFilesAccessHint(boot: BootstrapResult): String {
        val isShell = boot.isPacked || boot.shellDetected
        if (!isShell || tv.own.owntv.mobile.tvbox.util.LocalSourceResolver.hasAllFilesAccess()) {
            return ""
        }
        return "。该 jar 是加固壳，解包需访问外部存储，请在「配置管理」页授予「所有文件访问权限」后重试"
    }

    /**
     * 解析 csp_ 实现类的候选全名。
     *
     * 生态里 `api: "csp_XXX"` 对应的实现类**不在** `crawler` 包下，而是在：
     * - `com.github.catvod.spider.XXX`（FongMi/Aowu 系主流，实测绝大多数）
     * - `com.github.catvod.crawler.XXX`（少数老 jar）
     * - `api` 本身写完整类名（自定义包）
     *
     * 架构文档只写了 crawler 那一种，实测真机 jar 是 spider 包 —— 必须都试。
     *
     * ## 别名映射（为什么需要）
     * 真实案例：vip.json 配的是 `api: "csp_Woshinidie"`，但壳里真实类名是
     * `AowuShinidie` —— 配置侧的 api 名与实现类名**不一定一致**（壳作者刻意改名）。
     * 查 jar 全部条目确认只有一个 `AowuShinidie`、零个 `Woshinidie`。
     * 没有别名映射就只能报"找不到实现类"，用户完全无从下手。
     */
    private fun candidateNames(className: String): List<String> {
        val out = LinkedHashSet<String>()
        if (className.contains('.')) {
            out += className
        } else {
            out += "com.github.catvod.spider.$className"
            out += "com.github.catvod.crawler.$className"
            out += className
            // 壳作者改名的情况：csp_Woshinidie → AowuShinidie
            ALIASES[className]?.let { alias ->
                out += "com.github.catvod.spider.$alias"
                out += "com.github.catvod.crawler.$alias"
                out += alias
            }
        }
        return out.toList()
    }

    private fun resolveClass(loader: ClassLoader, className: String): Class<*>? {
        val candidates = candidateNames(className)
        // 先按名字找，再确认可赋值给宿主桩 Spider（有些同名壳类不继承 Spider）
        for (n in candidates) {
            val c = runCatching { loader.loadClass(n) }.getOrNull() ?: continue
            if (com.github.catvod.crawler.Spider::class.java.isAssignableFrom(c)) return c
            // 名字对上了但不继承桩：记一笔说明原因，继续试下一个候选
            TbLog.tag(TAG).d( "%s 存在但不继承宿主桩 Spider（父类=%s），尝试下一个候选",
                n, c.superclass?.name)
        }
        return null
    }

    /**
     * 记录失败原因（按站点存，见[lastErrors] 的说明）。
     *
     * reason 只写"发生了什么"，**不要重复站点名** ——
     * 站点名由上层 [obtain] / UI 统一拼，重复会出现
     * 「X」加载失败：「X」加载失败：…这种嵌套噪音。
     */
    private fun fail(site: SiteConfig, reason: String): Nothing? {
        TbLog.tag(TAG).e( "%s 加载失败：%s", site.name, reason)
        lastErrors[site.key] = reason.take(160)
        return null
    }


/**
 * 执行 jar 内的引导逻辑（加固壳必需，普通 jar 无此方法时静默跳过）。
 *
 * ## 为什么需要这一步
 * 现在生态里主流的 csp jar（aowu / woshinidie / wexguard 系）是**加固壳**：
 * ```
 * assets/wexshinidie.guard  ← 加密的真实 dex
 * com.github.catvod.spider.Init       ← 引导类
 * com.github.catvod.spider.DexNative  ← 解密/加载 + getSpider
 * ```
 * jar 里的 `XxxGuard` 全是**代理类**，反汇编（dexdump -d）可以看到它的构造函数：
 * ```
 * <init>:
 *   invoke-super {v1}, Lcom/github/catvod/crawler/Spider;.<init>
 *   invoke-static {自身类名}, Lcom/github/catvod/spider/Init;.getSpider:(...)Lcom/github/catvod/crawler/Spider;
 *   iput-object      v0 -> this.l1lIl1l1IlIlIl1l1I   ← 真实实现存进字段
 * ```
 * 而每个业务方法（如 `homeContent`）都是
 * `iget-object 真实实现 → invoke-virtual Spider.homeContent(Z)String` 的转发。
 *
 * 所以：**引导必须发生在实例化代理类之前**。否则构造函数里 `getSpider()`
 * 拿不到解密后的 ClassLoader，直接返回 null，真实实现字段为 null，
 * 表现为后续所有接口 NPE —— 而栈里完全看不到 "未引导" 这个真正原因。
 *
 * ## 为什么必须传 Application
 * 壳的 `Init.init(Context)` 反汇编里第一件事就是
 * `check-cast v1, Landroid/app/Application;` —— 传普通 Context 会直接
 * ClassCastException（同样是 Error，catch(Exception) 兜不住）。
 *
 * 引导失败不阻断加载：非加固 jar 走到这里什么也不做。
 *
 * @return [BootstrapResult]：是不是壳、引没引起来、是不是 ABI 不匹配。
 *   "找不到实现类"要不要提示权限、实例化失败要不要提示"加固壳未引导"，都看它。
 */
private fun bootstrap(loader: ClassLoader, jar: File): BootstrapResult {
    val key = jar.absolutePath
    // 同一 jar 只引导一次，返回上次的判定结果（多站点复用同一 jar）
    bootstrapResults[key]?.let { return it }

    // ---- P0-3 ABI 预检（诊断式，不硬拦）----
    // wexguard 壳的 native 解密器只有 assets/wexguard_v7.so（ARM32）/ v8.so（ARM64）
    // 两份，x86 设备上没有转译时 System.load 必失败。这里先识别出来，
    // 若随后引导确实失败，就把"ARM 加固壳 + 设备 ABI"作为明确报错给出去。
    var abiRisk = false
    var abiReason: String? = null
    if (hasArmOnlyNativeLib(jar) && !isArmDevice()) {
        abiRisk = true
        abiReason = armShellBlockedReason(Build.CPU_ABI)
        TbLog.tag(TAG).w( "%s：%s", jar.name, abiReason)
    }

    // 优先用宿主 Application：壳会 check-cast 成 Application，传普通 Context 必崩
    val appCtx: Context? = runCatching {
        // appContext 是 lateinit，未初始化时直接访问会抛 UninitializedPropertyAccessException
        val ctx = tv.own.owntv.mobile.tvbox.CatvodHost.appContext()
        if (ctx !is Application) {
            TbLog.tag(TAG).w( "宿主 Application 不可用（实际 %s），加固壳引导可能失败", ctx.javaClass.name)
        }
        ctx
    }.getOrNull()

    var shellDetected = false
    var lastError: Throwable? = null
    for (candidate in BOOTSTRAP_CLASSES) {
        val cls = runCatching { loader.loadClass(candidate) }.getOrNull() ?: continue
        // 找到引导类 = 这是加固壳（无论后续引导成败）
        shellDetected = true
        // 引导类里缺桩方法/字段会抛 Error（NoSuchMethodError 等），runCatching 只捕 Exception，兜不住
        val ok = runCatchingCatching { invokeBootstrap(cls, appCtx) }
        if (ok.getOrDefault(false) == true) {
            // 新 wexguard 壳（2026-10 批次）的 Init.init 会先启动远端配置/代理 so 的
            // 异步加载，native getLoader 在未就绪时返回 null；此时若立刻实例化
            // XxxGuard，Init.getSpider 会把 null loader 交给 JNI，触发
            // `JNI DETECTED ERROR: obj == null` 并 SIGABRT 整个进程（真机 release 实测）。
            // 等壳把 loader 交出来再实例化。
            val loaderReady = awaitShellLoader(cls, appCtx)
            if (!loaderReady) {
                TbLog.tag(TAG).w( "壳 loader 超时未就绪：%s（%s）", jar.name, candidate)
                return BootstrapResult(
                    shellDetected = true,
                    error = IllegalStateException("加固壳初始化超时（loader 未就绪）"),
                ).also { bootstrapResults[key] = it }
            }
            TbLog.tag(TAG).i( "jar引导已执行：%s（%s）", jar.name, candidate)
            return BootstrapResult(shellDetected = true, isPacked = true)
                .also { bootstrapResults[key] = it }
        }
        lastError = ok.exceptionOrNull() ?: lastError
        // P0-1：DEBUG → WARN 且**打全栈**。以前用 exceptionOrNull()?.toString()，
        // 只留一句 "java.lang.UnsatisfiedLinkError: ..." 不带栈，
        // 而"是不是 ABI 问题/哪个 so 挂的"全在栈里，丢栈等于没诊断。
        TbLog.tag(TAG).w( ok.exceptionOrNull(), "jar 引导 %s 失败", candidate)
    }
    if (shellDetected) {
        // 有引导类但全部失败：这是"加固壳没引起来"，不是"普通 jar"。
        // 这句 WARN 就是下游"实例化失败"的真正根因（P0-2 的文案由它兜底）。
        TbLog.tag(TAG).w( lastError, "jar %s 是加固壳但引导失败", jar.name)
        return BootstrapResult(
            shellDetected = true,
            error = lastError,
            abiRisk = abiRisk,
            abiReason = abiReason,
        ).also { bootstrapResults[key] = it }
    }
    TbLog.tag(TAG).d( "jar 无引导类（普通 jar），跳过：%s", jar.name)
    return BootstrapResult(abiRisk = abiRisk, abiReason = abiReason)
        .also { bootstrapResults[key] = it }
}


/**
 * 等加固壳交出真实 dex 的 ClassLoader（`Init.loader()` 非 null）。
 *
 * ## 为什么需要等
 * 2026-10 批次 wexguard 壳的 `Init.init(Context)` 会先启动"远端配置 + Go 代理 so"
 * 的异步加载，native `getLoader` 未就绪时返回 null。宿主若紧接着 `new` 代理类，
 * `BaseSpiderGuard.<init>` 会把 null loader 传给 JNI：
 * `JNI DETECTED ERROR: obj == null in call to CallObjectMethodA` → SIGABRT，
 * 整个进程被壳带走（release 实测）。
 *
 * 引导类没有 `loader()` 的壳（aowu 等）直接视为就绪，行为不变。
 * 每次探测间隔 200ms，期间每秒重调一次 `init`，让壳有机会把 loader 补上。
 *
 * @return true = 就绪或该壳没有 loader 概念；false = 超时仍未就绪（调用方必须
 *   放弃实例化，否则 native 端必抛 obj == null 并 SIGABRT）。
 */
private fun awaitShellLoader(cls: Class<*>, appCtx: Context?, timeoutMs: Long = 10_000L): Boolean {
    val getter = runCatching { cls.getMethod("loader") }.getOrNull() ?: return true
    if (!java.lang.reflect.Modifier.isStatic(getter.modifiers)) return true

    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    var nextReinit = SystemClock.elapsedRealtime() + 1_000L
    while (SystemClock.elapsedRealtime() < deadline) {
        if (runCatching { getter.invoke(null) }.getOrNull() != null) return true
        if (SystemClock.elapsedRealtime() >= nextReinit) {
            runCatching { invokeBootstrap(cls, appCtx) }
            nextReinit = SystemClock.elapsedRealtime() + 1_000L
        }
        try {
            Thread.sleep(200L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
    }
    return false
}

/**
 * 反射执行引导，按生态实际形态逐级降级。
 *
 * ## 与 TVBoxOS `JarLoader.invokeInit` 的对应关系
 * 官方实现只有一句核心动作：**`Init.init(Context)`**，且**注入完就返回，不再做别的**。
 * 本函数的降级链只是为了兼容"没有 init(Context) 的老壳"，主路径与官方一致。
 *
 * ## 关键教训（曾在这里埋了回归）
 * 我曾额外加了一步"宿主反射注入 DexClassLoader"，结果把本来能用的源全弄坏。
 * dexdump 反汇编证明两类壳的 `init(Context)` **都已自给自足**：
 * ```
 * // aowu / woshinidie 系
 * Init.get().app = (Application) ctx
 * init.loader = (DexClassLoader) DexNative.getLoader(ctx, assets 里的加密 dex 流)
 *
 * // wexguard 系
 * Init.get().app = (Application) ctx
 * init.loader = (DexClassLoader) DexNative.getLoader(ctx)      // 单参
 * ```
 * 官方 `ProtectedInitJar` 只在"扫到 killProcess 且无白名单"时才注入，
 * 而 aowu/wexguard 两个壳**都没有 killProcess** → 官方**从不注入**。
 * 宿主多此一举反而会用错误的 loader 覆盖壳已设好的值。
 *
 * @param appCtx 必须传宿主 **Application**：两类壳的 init 第一件事都是
 *   `check-cast ctx, Landroid/app/Application;`，传普通 Context 直接 ClassCastException
 *   （是 Error，`catch(Exception)` 兜不住）。
 */
private fun invokeBootstrap(cls: Class<*>, appCtx: Context?): Boolean {
    // 形态 1'：static init(Context) —— **主路径，aowu / wexguard 都在这里**
    // 必须先试它：部分壳同时存在 init() 与 init(Context)，先试无参会走错分支
    val staticInitCtx = runCatching {
        cls.getMethod("init", Context::class.java)
            .takeIf { java.lang.reflect.Modifier.isStatic(it.modifiers) }
    }.getOrNull()
    if (staticInitCtx != null) {
        staticInitCtx.isAccessible = true
        staticInitCtx.invoke(null, appCtx ?: context)
        return true
    }

    // 形态 1/3：无参静态 init() —— 早期壳
    val staticInit = runCatching { cls.getMethod("init") }.getOrNull()
        ?: cls.declaredMethods.firstOrNull { it.name == "init" && it.parameterCount == 0 }
    if (staticInit != null && java.lang.reflect.Modifier.isStatic(staticInit.modifiers)) {
        staticInit.isAccessible = true
        staticInit.invoke(null)
        return true
    }

    // 形态 2：get() 单例 + 实例 init(Context)
    val get = runCatching { cls.getMethod("get") }.getOrNull()
    val inst = if (get != null && java.lang.reflect.Modifier.isStatic(get.modifiers)) {
        get.isAccessible = true
        get.invoke(null)
    } else {
        runCatching { cls.getDeclaredConstructor().newInstance() }.getOrNull()
    } ?: return false
    val im = runCatching { cls.getMethod("init", Context::class.java) }.getOrNull()
        ?: return false
    im.isAccessible = true
    im.invoke(inst, appCtx ?: context)
    return true
}
    /** 目录里挑一个 jar：优先带 md5 命名的最新文件 */
    /** 目录里挑一个 jar：优先带 md5 命名的最新文件 */
private fun pickJar(dir: File, preferMd5: Set<String>): File? {
        val jars = dir.listFiles { f -> f.isFile && f.name.endsWith(".jar", true) && f.length() > 0 }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

        // 按魔数过滤：早期版本会把 HTTP 200 的 HTML 落地页当 jar 存进来，
        // 只按后缀+大小挑，会选到 HTML，DexClassLoader 随即抛"不是合法 dex"。
        val valid = jars.filter(::looksLikeJarFile)

        // ## 为什么必须先按 md5 精确命中（真实 bug，别删这段注释）
        // 目录里同时躺着多个 jar 时，"最新那个"未必是配置说的公共 jar。
        // 实测（wangziduoqing 配置）：
        // ```
        //   files/spiders/e89ad80c….jar  (21:49) ← 配置 spider 字段指定的公共 jar
        //   files/spiders/fc8f993c….jar  (22:00) ← 另一个配置的 jar，mtime 更新
        // ```
        // 而该配置 24 个 type=3 站里只有 2 个带站点私有 jar，其余（含荐片/热播）
        // 全部依赖公共 jar。按 mtime 挑会选到 `fc8f993c`，而 `JPianAmns`/`RbysAmns`
        // 这两个实现类**只在 `e89ad80c` 里**（解 dex 字符串表确认，fc8f993c 零命中），
        // 于是必然报"找不到实现类 com.github.catvod.spider.RbysAmns"。
        //
        // 下载落盘统一用 md5 命名（见 [RemoteArtifact.fixedMd5]），文件名即身份，
        // 所以按配置声明的 md5 精确匹配是唯一可靠做法。
        return selectSpiderJar(valid, preferMd5).also { picked ->
            if (picked != null && preferMd5.isNotEmpty() && !preferMd5.matchesMd5Of(picked)) {
                TbLog.w(
                    TAG,
                    "spider 目录未命中配置声明的 md5（%s），退回按修改时间挑选：%s",
                    preferMd5.joinToString(), picked.name
                )
            }
        }
    }

    /**
     * DexClassLoader：parent 传宿主 ClassLoader 实现 parent-first 委派。
     * optimizedDirectory 必须给一个可写且**独立**的目录（同一目录并发写 dex 会失败）。
     *
     * @param extraDexPath 额外 dex 路径（分号分隔）。**站点私有 jar 必须带上公共 jar**：
     *   私有 jar 的实现类依赖公共 jar 的 `spider.merge.*` 工具类，
     *   不带上会报 NoClassDefFoundError（真机实测 gm.jar → merge.y1.c）。
     */
    private fun loaderFor(jar: File, extraDexPath: String? = null): DexClassLoader? {
        val key = jar.absolutePath + "|" + extraDexPath.orEmpty()
        loaders[key]?.let { return it }
        return runCatching {
            // API 26+ 的 ART 拒绝加载可写 dex/jar；重新下载前由 FileUtil 解除只读。
            check(jar.setReadOnly()) { "无法将 dex/jar 设为只读：${jar.absolutePath}" }
            val odexDir = File(context.codeCacheDir, "jar_odex").apply { mkdirs() }
            val parent = JarEngine::class.java.classLoader
            val dexPath = listOfNotNull(jar.absolutePath, extraDexPath).joinToString(File.pathSeparator)
            DexClassLoader(dexPath, odexDir.absolutePath, null, parent).also {
                loaders[key] = it
            }
        }.onFailure {
            TbLog.tag(TAG).e( "DexClassLoader 失败：%s（%s）", jar.name, it.message)
        }.getOrNull()
    }

    override fun isCooling(siteKey: String): Boolean = SourceHealth.isCooling(siteKey)

    override fun invalidateAll() {
        spiders.values.forEach { runCatching { it.destroy() } }
        spiders.clear()
        // DexClassLoader 无法真正 close（Android 上无 close/unload），只能丢弃引用让 GC 回收
        loaders.clear()
        bootstrapResults.clear()
        TbLog.tag(TAG).d( "引擎池已清空")
    }

    companion object {
        private const val TAG = "JarEngine"

        /** jar 加载失败时抛出，由 SourceRepository 统一计入源健康 */
        class JarLoadException(msg: String) : Exception(msg)

        /** 加固壳引导类候选（按尝试顺序） */
        private val BOOTSTRAP_CLASSES = listOf(
            "com.github.catvod.spider.Init",
            "com.github.catvod.crawler.Init",
            "com.github.catvod.spider.Init\$Loader",
        )

        /**
         * 配置侧 api 名 → jar 内真实实现类名。
         *
         * 起因：vip.json 写 `csp_Woshinidie`，但 aowu 加固壳里唯一的对应类是
         * `AowuShinidie`（壳作者为规避扫描刻意改名）。查 jar 全部 dex 条目确认：
         * `Woshinidie` 零命中、`AowuShinidie` 一个 —— 不是"没下载下来"，是名字不同。
         * 不加映射就只能报"找不到实现类 com.github.catvod.spider.Woshinidie"。
         */
        private val ALIASES = mapOf(
            "Woshinidie" to "AowuShinidie",
        )

        /**
         * 公共 jar 的身份文案：**带真实文件名**，md5 未命中时明确说是回退结果。
         *
         * 旧实现一律写"当前 jar=公共 spider jar"，配置声明的 md5 没命中、按 mtime
         * 退回别的 jar 时，报错文案完全看不出实际加载的是谁，真机排障时误导性极强。
         */
        internal fun describeCommonJar(file: File, preferMd5: Set<String>): String {
            val wanted = preferMd5.map { it.lowercase() }.filter { it.isNotBlank() }.toSet()
            val hit = wanted.isEmpty() || wanted.matchesMd5Of(file)
            return if (hit) {
                "公共 spider jar（${file.name}）"
            } else {
                "公共 spider jar（${file.name}，md5 未命中，已回退最新候选）"
            }
        }

        /**
         * 从候选 jar 里挑出"公共 spider jar"：先按配置声明的 md5 精确命中，
         * 命中不到再按传入顺序（调用方已按 mtime 倒序）取第一个。
         *
         * 放 companion 且不碰 Context/网络，是为了能直接单测（见 [JarEngineTest]）。
         */
        internal fun selectSpiderJar(candidates: List<File>, preferMd5: Set<String>): File? {
            val wanted = preferMd5.map { it.lowercase() }.filter { it.isNotBlank() }.toSet()
            if (wanted.isEmpty()) return candidates.firstOrNull()
            return candidates.firstOrNull { f -> wanted.matchesMd5Of(f) }
                ?: candidates.firstOrNull()
        }

        /**
         * 文件名是否属于任一 md5。
         * 下载落盘名为 `<md5>.jar`；同时容忍 `<md5>_<原名>.jar` / `<md5>.xxx.jar` 这类带后缀变体。
         */
        private fun Set<String>.matchesMd5Of(file: File): Boolean {
            val stem = file.name.removeSuffix(".jar").lowercase()
            return any { m -> stem == m || stem.startsWith("$m.") || stem.startsWith("${m}_") }
        }

        /** jar 魔数是否合法：PK\x03\x04（zip/jar）或 dex\n（裸 dex） */
        private fun looksLikeJarFile(f: File): Boolean {
            val head = runCatching {
                f.inputStream().use { input ->
                    val buf = ByteArray(4)
                    val n = input.read(buf, 0, 4)
                    if (n == 4) buf else null
                }
            }.getOrNull() ?: return false
            return (head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                head[2] == 0x03.toByte() && head[3] == 0x04.toByte()) ||
                (head[0] == 0x64.toByte() && head[1] == 0x65.toByte() &&
                    head[2] == 0x78.toByte() && head[3] == 0x0A.toByte())
        }

        /** runCatching 只捕 Exception；jar 缺桩会抛 Error，这里要一并兜住 */
        private inline fun <T> runCatchingCatching(block: () -> T): Result<T> =
            try {
                Result.success(block())
            } catch (t: Throwable) {
                Result.failure(t)
            }

        /** 配置里的 spider 声明（诊断用） */
        fun spiderArtifacts(config: TvBoxConfig?): List<tv.own.owntv.mobile.tvbox.util.RemoteArtifact> =
            SpiderSpec.parse(config?.spider)

        /** spider 缓存目录（与 ConfigRepository 下载落点一致） */
        fun spiderDir(context: Context): File = FileUtil.spiderDir(context)
    }
}

/**
 * 加固壳引导结果（按 jar 路径缓存，见 [JarEngine] 的 bootstrap 流程）。
 *
 * ## 为什么要四个字段而不是一个 Boolean
 * 旧实现只存"引导是否成功"一个 Boolean，把三种完全不同的情况压成 false：
 * 1. 普通 jar（没有引导类）—— 正常，不该提示任何壳相关信息
 * 2. 加固壳但引导失败（缺桩/ABI/权限/解密失败）—— **用户最需要被明确告知的情况**
 * 3. 加固壳且引导成功 —— 正常
 * 报错文案（权限提示 / "加固壳未引导" / ABI 不匹配）全靠区分这三者，
 * 压成一个 Boolean 就只能让用户对着"实例化失败"猜。
 */
data class BootstrapResult(
    /** jar 含壳引导类（Init 等）—— 是加固壳，无论引导成败 */
    val shellDetected: Boolean = false,
    /** 壳引导成功执行（Init.init 已跑，解密 dex 已就绪） */
    val isPacked: Boolean = false,
    /** 引导过程的异常（shellDetected=true 且 isPacked=false 时通常非 null） */
    val error: Throwable? = null,
    /** ABI 预检命中：jar 打包 assets/wexguard_*.so 且设备无 ARM（P0-3） */
    val abiRisk: Boolean = false,
    /** ABI 预检的明确报错文案（abiRisk=true 时非 null） */
    val abiReason: String? = null,
)

/**
 * jar 内是否打包 **ARM-only native 解密器**（wexguard 系加固壳的识别特征）。
 *
 * wexguard 壳的壳 dex 不带解密能力，真实 dex 的密钥/解密在 native 里：
 * `assets/wexguard_v7.so`（ARM32）/ `assets/wexguard_v8.so`（ARM64）—— **只有 ARM 两份**。
 * 设备没有 ARM ABI（且无转译）时 `System.load` 必失败（UnsatisfiedLinkError），
 * 引导必然挂，提前识别出来就能把根因写进报错（P0-3）。
 *
 * 只按条目名匹配，不解压内容；jar 不是合法 zip 时返回 false（交给后续流程报错）。
 */
internal fun hasArmOnlyNativeLib(jar: File): Boolean {
    val zip = runCatching { ZipFile(jar) }.getOrNull() ?: return false
    return zip.use { z ->
        val entries = z.entries()
        while (entries.hasMoreElements()) {
            val name = entries.nextElement().name
            if (name.startsWith("assets/wexguard_", ignoreCase = true) &&
                name.endsWith(".so", ignoreCase = true)
            ) {
                return@use true
            }
        }
        false
    }
}

/** 设备是否有 ARM ABI（wexguard 壳能否 System.load 的前提） */
private fun isArmDevice(): Boolean {
    val abis = Build.SUPPORTED_ABIS?.filter { it.isNotBlank() }.orEmpty()
        .ifEmpty { listOf(Build.CPU_ABI) }
        .filter { it.isNotBlank() }
    return abis.any { it.contains("arm", ignoreCase = true) }
}

/**
 * ABI 不匹配时的明确报错文案（P0-3）。
 *
 * ## 为什么只报错、不直接禁止加载
 * 部分 x86_64 镜像（google_apis_playstore 系）带 ARM 转译层，同样能 System.load
 * ARM so（wexguard 分析文档第五节的推荐处置正是"换 Google Play x86_64 镜像"）。
 * 所以这里只做**诊断**：识别出来 + 打 WARN；等引导真的失败了，
 * 才用这句话替换掉含义不明的 "实例化失败：InvocationTargetException"。
 */
internal fun armShellBlockedReason(abi: String): String =
    "该站源为 ARM 加固壳，当前设备（$abi）无法运行"