package tv.own.owntv.mobile.tvbox.config.model

import kotlinx.serialization.json.JsonObject
import tv.own.owntv.mobile.BuildConfig

/**
 * Java 二进制类名校验（`com.foo.Bar`、`com.foo.Bar$Inner`）：
 * 每段都是合法 Java 标识符，至少两段。用于识别「全限定实现类名」形态的 jar 源 api。
 */
private val FQCN_REGEX = Regex("""[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+""")

/**
 * TvBox 配置数据模型（方案 4.4）。
 *
 * 兼容要点：
 * - 全部字段可缺省，缺失取默认值
 * - 单条 site 非法只丢弃该条，不失败整份配置（见 ConfigParser）
 * - 额外字段用 ignoreUnknownKeys 忽略，兼容各家魔改配置
 */
data class TvBoxConfig(
    val spider: String? = null,
    val wallpaper: String? = null,
    val sites: List<SiteConfig> = emptyList(),
    val parses: List<ParseConfig> = emptyList(),
    val flags: List<String> = emptyList(),
    val rules: List<SniffRule> = emptyList(),
    /** 广告域名/URL 片段（对齐 TVBoxOS 顶层 `ads`） */
    val ads: List<String> = emptyList(),
    /** 配置级按 host 代理规则（对齐 TVBoxOS `proxy`） */
    val proxy: List<ProxyConfig> = emptyList(),
    /** 配置声明的 DoH 节点（对齐 TVBoxOS 顶层 `doh`） */
    val doh: List<DohConfig> = emptyList(),
    /** 直播源（对齐 TVBoxOS `lives` 字段） */
    val lives: List<LiveConfig> = emptyList(),
) {
    /** 背景图地址（无则空） */
    val wallpaperUrl: String? get() = wallpaper?.takeIf { it.isNotBlank() }

    /**
     * 协议已知、配置格式能识别的 type（方案 4.1 路由表）。
     * 未知 type（如 type 2）保留在 sites 里由 UI 灰显，不阻断导入。
     */
    fun knownSites(): List<SiteConfig> = sites.filter { it.type in KNOWN_TYPES }

    fun unknownSites(): List<SiteConfig> = sites.filter { it.type !in KNOWN_TYPES }

    /** 当前构建真正有引擎可跑的站点（M1 = CMS 型 0/1/4） */
    fun supportedSites(): List<SiteConfig> = sites.filter { it.isSupported }

    fun unsupportedSites(): List<SiteConfig> = sites.filter { !it.isSupported }

    companion object {
        /** TvBox 协议已知 type */
        val KNOWN_TYPES = setOf(0, 1, 3, 4)

        /** 已实现引擎覆盖的 TvBox type；JS 路由还会按 api 后缀识别兼容写错 type 的配置。 */
        val ENGINE_TYPES = setOf(0, 1, 3, 4)
    }
}

data class SiteConfig(
    val key: String,
    val name: String = key,
    val type: Int = 0,
    val api: String = "",
    val searchable: Int = 1,
    val quickSearch: Int = 0,
    val filterable: Int = 0,
    val ext: String? = null,
    val header: JsonObject? = null,
    /** 部分配置用 jar 字段指定单站点私有 jar */
    val jar: String? = null,
    /** 站点级 UA 覆写 */
    val userAgent: String? = null,
    val playerType: Int? = null,
    val categories: List<String> = emptyList(),
    /** 站点级播放解析前缀（生态 `playUrl`）：非直链地址拼上它再解析/嗅探 */
    val playUrl: String? = null,
    /** 站点级请求超时（秒），缺失时由网络层用全局默认 */
    val timeout: Int? = null,
) {
    val canSearch: Boolean get() = searchable == 1
    val canQuickSearch: Boolean get() = quickSearch == 1
    val canFilter: Boolean get() = filterable == 1

    /**
     * 启发式判定"纯搜索型源"（对齐 TVBoxOS `SourceViewModel.getSort`）。
     *
     * TVBoxOS 的规则是：站点名长度 ≤3 且以"搜"结尾 → 认定该源只提供搜索，
     * 直接跳过 homeContent，不让用户对着空白首页。
     *
     * ## 为什么可以照搬
     * 这条规则不是拍脑袋：生态里纯搜索源的命名高度一致（"搜库""资源搜""影视搜"），
     * 而能浏览的源名字通常明显更长。用它做**预判**能省掉一次必然为空的
     * homeContent 网络往返（这类源往往还会拖满整个超时）。
     *
     * 预判只影响 UI 呈现与是否发请求，**不写回配置**——名字改一下就失效，
     * 所以真正的判定仍以运行时返回为准（见 `HomeViewModel.probeHome`）。
     */
    val looksSearchOnly: Boolean
        get() {
            val n = name.trim()
            return n.length in 1..3 && n.endsWith("搜")
        }

    /** 只按 api 判断脚本后缀，兼容 `.js?token=...` 与 `#fragment`。 */
    val apiIsJs: Boolean
        get() = api.substringBefore('?').substringBefore('#').endsWith(".js", true)

    /** 只按 api 判断 Python 脚本后缀，兼容 `.py?token=...` 与 `#fragment`。 */
    val apiIsPy: Boolean
        get() = type == 3 && api.substringBefore('?').substringBefore('#').endsWith(".py", true)

    /** 是否为 Python 采集源；与 `PyEngine.supportsSite` 必须逐字一致。 */
    val isPySite: Boolean
        get() = apiIsPy

    /** 是否为脚本型源；type=3 的 drpy runtime 也按兼容标记识别。 */
    val isJsSite: Boolean
        get() = apiIsJs || (type == 3 && api.contains("drpy", true))

    /**
     * Jar 型源的实现类名（已剥离 csp_ 前缀）；非 Jar 型返回 null。
     *
     * 兼容三种生态写法（对齐 TVBoxOS `ApiConfig` 的语义）：
     * - `csp_XXX`：标准 TVBox 形式，取 `XXX` 作为类名（引擎侧再按
     *   `com.github.catvod.spider.XXX` 等候选解析）
     * - 全限定实现类名（如 `com.tvbox.spider.impl.ChengGuoSpider`）：直接作为
     *   二进制类名交给 DexClassLoader，jar 取自站点私有 jar 或**顶层 `spider` jar**
     * - 裸类名（如 `py_cctv_full`）：部分魔改配置在**站点私有 jar** 场景下
     *   直接写类名，不带 csp_ 前缀 —— 此时要求站点带 jar 字段且 api 不是
     *   URL/脚本形态，才认作类名（避免把 `./js/x.py` 误当类名）
     */
    val spiderClassName: String?
        get() {
            if (type != 3) return null
            if (api.startsWith("csp_")) return api.removePrefix("csp_")
            if (isJsSite || isPySite || api.contains('/')) return null
            // 全限定实现类名（含点号、各段为合法 Java 标识符）：直接按类名解析。
            // 顶层 spider jar 即默认 jar，不要求站点带 jar 字段
            if (FQCN_REGEX.matches(api)) return api
            // 裸短类名：仅在声明了站点私有 jar 时接受
            if (jar != null && !api.contains('.')) return api
            return null
        }

    /**
     * 是否支持"目录下钻"（网盘类源，当前判定 AList 系）。
     *
     * ## 判据为什么是 ext 里的 `drives`
     * `csp_AList`（小雅/小鸭/七米蓝这类多仓网盘）的约定是：
     * `categoryContent(tid)` 的 **tid 直接当作网盘路径**去列目录
     * （`i()` 里 `path.indexOf("/")` 切出驱动名，余下整串交给 `/api/fs/list`）。
     * 所以任意深度的子目录都能当成一个新"分类"继续浏览 —— 这是把
     * `<驱动>/<路径>` 一层层点进去的唯一入口。
     *
     * 而 TVBox 协议本身只有「分类 → 列表 → 详情」三段，详情页拿到目录只能
     * 返回"目录自身"这一集（播放必然失败）。要看到目录里的文件，只能由
     * App 侧记住路径栈、再用该路径重新发一次 categoryContent。
     *
     * 只对声明了 `drives` 的 jar 源开启：别的源 tid 语义是分类 id，
     * 拿 vod_id 当 tid 去请求会返回空甚至报错。
     */
    val supportsDirBrowse: Boolean
        get() = type == 3 && ext?.contains("\"drives\"") == true

    /** type 是否属于 TvBox 协议已知范围（配置格式层面能识别） */
    val isKnownType: Boolean get() = type in TvBoxConfig.KNOWN_TYPES

    /**
     * 当前构建是否有引擎能真正跑这个站点。
     *
     * **必须与引擎路由逐条对齐**（CmsEngine.supports / JarEngine.supports /
     * JsEngine.supports / PyEngine.supports），
     * 两层判定不一致的后果是叠加的：UI 显示可用 → 点开必失败；
     * 聚合搜索还会把这些假可用源算进并发目标，白打一路。
     * - type 0/1/4：CMS 引擎或 api 后缀识别出的 JS 引擎（要求 api 非空）
     * - type 3：Jar 引擎（实现类形态）、JS 引擎或 Python 引擎（后者还要求 WITH_PY）
     */
    val isSupported: Boolean
        get() = when (type) {
            0, 1, 4 -> api.isNotBlank()
            3 -> spiderClassName != null || isJsSite || (BuildConfig.WITH_PY && isPySite)
            else -> false
        }

    /** 不可用原因，用于 UI 给出可执行的引导文案 */
    val unsupportedReason: String?
        get() = when {
            isSupported -> null
            // WITH_PY=true 时 py 站点必 isSupported（上面的与门已挡在前），走不进本分支，
            // 故这里直接判 isPySite 即等价于 !WITH_PY，无需再 if 分叉
            type == 3 && isPySite -> "本版本不含 Python 引擎，py 采集源不可用"
            type == 3 && api.isBlank() -> "api 为空"
            type == 3 -> "Jar 源 api 应为 csp_XXX 或实现类名，当前为：$api"
            else -> "type=$type 不在已实现的引擎路由内"
        }
}

data class ParseConfig(
    val name: String = "",
    val type: Int = 0,
    val url: String = "",
    val ext: JsonObject? = null,
    /** 解析类型：0=关闭 1=json 2=json扩展 3=json聚合（三者均 HTTP+JSON，差异在服务端）。
     *  真正需要 WebView 跑 JS 的是解析结果里的 parse:1 标记，而非本 type 字段。 */
    val show: String? = null,
)

/**
 * 配置级代理规则（对齐 TVBoxOS `proxy` 数组元素）。
 *
 * 生态写法：`hosts` 可以是字符串或数组，`urls` 可以是单个 URL 或数组；
 * URL 形如 `http://user:pass@host:port`、`socks5://host:port`。
 * 解析阶段只做结构归一，不校验可用性；真正的代理对象由
 * the runtime proxy layer (dropped in this port) 在运行时构造。
 */
data class ProxyConfig(
    val name: String = "",
    val hosts: List<String> = emptyList(),
    val urls: List<String> = emptyList(),
)

/**
 * 配置声明的 DoH 节点（对齐 TVBoxOS 顶层 `doh`）。
 *
 * 生态形态：`{"name":"Google","url":"https://dns.google/dns-query","ips":["8.8.8.8"]}`。
 * `ips` 是 DoH 端点域名的 bootstrap 地址 —— 系统 DNS 被污染时，
 * 端点域名本身也解析不出来，必须能按配置里的 IP 直连。
 */
data class DohConfig(
    val name: String = "",
    val url: String = "",
    val ips: List<String> = emptyList(),
) {
    val displayName: String get() = name.takeIf { it.isNotBlank() } ?: url
}

/**
 * 回看配置（源级或频道级）。
 *
 * 生态两种写法：
 * - `"catchup": "default"` + `"catchup-source": "http://...{utc}{utcend}"`
 * - `"catchup": { "type": "append", "source": "...", "replace": "old,new" }`
 *
 * 模板 token（对齐 TVBoxOS LivePlayActivity）：
 * - `{utc}` / `{utcend}`：节目开始/结束秒级时间戳
 * - `{(byyyyMMddHHmmss)}` / `{(eyyyyMMddHHmmss)}`：开始/结束按格式串
 * - `{(btimestamp)}` / `{(etimestamp)}`：开始/结束秒级时间戳
 */
data class CatchupConfig(
    val type: String = "",
    val source: String = "",
    val replace: String = "",
    val regex: String = "",
) {
    val isUsable: Boolean get() = source.isNotBlank()
}

/**
 * 直播源配置（对齐 TVBoxOS `lives` 数组元素）。
 *
 * 字段名沿用生态约定：`api` 是订阅地址，`ext` 是扩展参数，
 * `type` 0=接口型（CMS JSON/XML）3=jar/脚本型。
 *
 * 生态里订阅地址字段名并不统一：`api`（TVBoxOS）与 `url`（各家魔改）都常见，
 * 甚至同一份配置里混用 —— 由 ConfigParser 解析时归一到 `api`（双名兼容）。
 */
data class LiveConfig(
    val name: String = "",
    val type: Int = 0,
    val api: String = "",
    /** jar/脚本型直播源的类名或地址（type=3） */
    val jar: String? = null,
    val ext: String? = null,
    /** 直播源专用 UA（生态常见简写 `ua`，解析时归一） */
    val userAgent: String? = null,
    /** EPG 节目单地址（留空则走配置里第一个带 epg 的源） */
    val epg: String? = null,
    /** 频道台标地址（模板形式如 `https://x/{name}.png`，播放层自行替换） */
    val logo: String? = null,
    /** 播放器类型：-1 跟随全局 / 1 ijk / 2 exo */
    val playerType: Int? = null,
    /** 请求超时秒数 */
    val timeout: Int? = null,
    val group: String? = null,
    /** 源级回看配置；频道级 catchup 优先 */
    val catchup: CatchupConfig? = null,
) {
    val displayName: String get() = name.takeIf { it.isNotBlank() } ?: api

    /** 是不是"订阅地址直接给 m3u/txt 文本"的那种直播源 */
    val isTextSubscribe: Boolean
        get() = api.endsWith(".m3u", true) || api.endsWith(".txt", true)

    /** type=3 的 JS 脚本型直播源（api 以 .js 结尾或为 drpy 运行时） */
    val isJsSite: Boolean
        get() = type == 3 && (api.endsWith(".js", true) || api.contains("drpy"))

    /**
     * 引擎型直播源：type=3 且当前构建有引擎可跑（jar 形态类名可解析，或 JS 脚本形态）。
     *
     * 判定委托给合成的 [toSpiderSite] —— 站点侧的 csp_/全限定类名/裸类名+私有 jar
     * 规则、JS 后缀识别全在那里，这里再抄一份必然漂移（两端口径不一致的后果
     * 与站点侧相同：UI 显示可用却点开必失败）。
     */
    val isEngineLive: Boolean
        get() = type == 3 && toSpiderSite("probe").let {
            it.spiderClassName != null || it.isJsSite
        }

    /**
     * 把直播源合成为 type=3 站点配置，交给既有引擎路由（Jar/JS）跑五接口。
     *
     * key 由 api 派生（稳定、同一地址同 key）：SourceRepository 的缓存、
     * JarEngine 的实例表、JarProxyHub 的代理注册都以 siteKey 为键。
     */
    fun toSpiderSite(key: String = engineSiteKey()): SiteConfig = SiteConfig(
        key = key,
        name = name.takeIf { it.isNotBlank() } ?: api,
        type = 3,
        api = api,
        ext = ext,
        jar = jar,
        timeout = timeout,
    )

    fun engineSiteKey(): String = "live_" + java.lang.Integer.toHexString(api.hashCode())
}

data class SniffRule(
    val name: String = "",
    val hosts: String = "*",
    val rule: List<String> = emptyList(),
    /** 该规则里的广告/去广告正则（对齐 TVBoxOS `rules[].ads` 与 `regex` 中的 m3u8 规则） */
    val ads: List<String> = emptyList(),
    /**
     * 嗅探正则（对齐 TVBoxOS `rules[].regex`，原始形态保留）。
     *
     * 播放列表类正则（`#EXT-X-*` / 纯数字）由 AdRuleHeuristics
     * 判定后同时并入 [ads] 供 M3U8 净化；其余（如 `item_id=`、`video/tos/cn`）
     * 是 WebView 嗅探用的 URL 正则，由 SniffMatcher 按 hosts 限定匹配。
     */
    val regex: List<String> = emptyList(),
) {
    /** hosts 逗号分隔，"*" 表示全匹配 */
    fun hostList(): List<String> =
        hosts.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun matches(host: String): Boolean {
        val hs = hostList()
        if (hs.isEmpty()) return true
        if (hs.any { it == "*" }) return true
        return hs.any { host.contains(it, ignoreCase = true) }
    }

    /** rule 命中：后缀或包含关键字 */
    fun matchesUrl(url: String): Boolean {
        if (rule.isEmpty()) return false
        val u = url.lowercase()
        return rule.any { r ->
            val k = r.trim().lowercase()
            k.isNotEmpty() && u.contains(k)
        }
    }
}

/** Site.type → 引擎路由（方案 4.1） */
enum class SiteType(val code: Int, val label: String) {
    CMS_JSON(0, "CMS接口"),
    CMS_XML(1, "CMS XML"),
    SPIDER(3, "Spider"),
    STATIC_JSON(4, "静态JSON"),
    UNKNOWN(-1, "不支持");

    companion object {
        fun of(code: Int): SiteType = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
