package tv.own.owntv.mobile.tvbox.config.parser

import tv.own.owntv.mobile.tvbox.util.TbLog

import tv.own.owntv.mobile.tvbox.config.model.AdRuleHeuristics
import tv.own.owntv.mobile.tvbox.config.model.DohConfig
import tv.own.owntv.mobile.tvbox.config.model.LiveConfig
import tv.own.owntv.mobile.tvbox.config.model.ParseConfig
import tv.own.owntv.mobile.tvbox.config.model.ProxyConfig
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.config.model.SniffRule
import tv.own.owntv.mobile.tvbox.config.model.TvBoxConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 解析结果：配置本体 + 过程中的问题清单（UI 提示用） */
data class ConfigParseResult(
    val config: TvBoxConfig?,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
) {
    val isSuccess: Boolean get() = config != null && error == null
}

/**
 * 非法 JSON 的预处理修复。
 *
 * ## 为什么需要
 * 生态里的 TVBox 配置几乎都是人手写的 JSON，**带注释是常态**。
 * 真实样本 `http://xhztv.top/4k.json`（30KB / 23 站点）：
 * ```
 * //////////////////////////////////////////
 * //https://tv.lan2wan.top/candymuj1.json
 * ```
 * 标准 JSON 解析器直接报 `Unexpected JSON token at offset 1376: Expected end of the array`。
 * kotlinx.serialization 的 `isLenient = true` 也**不覆盖注释** ——
 * 它只放宽未加引号的键名、单引号等，不剥离注释。
 *
 * ## 关键约束：绝不能误伤字符串里的 `//`
 * 配置里全是 URL（`"spider":"https://cdn.x.com/a.jar"`），
 * 粗暴地正则替换 `//.*` 会把URL 从 `https:` 处拦腰截断，
 * 变成 `"https:"` —— 症状是"配置解析成功但所有源都连不上"，比直接报错更难查。
 *
 * 故必须**逐字符扫描并跟踪字符串态**：只在字符串态之外才认注释。
 */
private object DirtyJsonRepair {

    /**
     * 剥离 `//` 行注释与 `/* */` 块注释，并转义字符串内的裸控制字符。
     *
     * @return 修复后的文本；修复失败时返回原串
     */
    fun stripComments(text: String): String {
        if (!text.contains("//") && !text.contains("/*")) return text
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        var inString = false
        var escaped = false

        while (i < n) {
            val c = text[i]
            if (inString) {
                if (escaped) escaped = false
                else when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                    // 裸控制字符：JSON 规范禁止未转义的控制字符，
                    // 但生态配置里常见（如 ext 值里直接换行）→ 转义而非丢弃
                    '\n' -> { out.append("\\n"); i++; continue }
                    '\r' -> { out.append("\\r"); i++; continue }
                    '\t' -> { out.append("\\t"); i++; continue }
                }
                out.append(c)
                i++
                continue
            }
            // 字符串态之外才判断注释
            if (c == '"') {
                inString = true
                out.append(c)
                i++
                continue
            }
            if (c == '/' && i + 1 < n) {
                when (text[i + 1]) {
                    '/' -> {
                        // 行注释：跳到换行前（换行本身留给下一轮处理，保持行结构便于排错）
                        i += 2
                        while (i < n && text[i] != '\n' && text[i] != '\r') i++
                        continue
                    }
                    '*' -> {
                        i += 2
                        while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) i++
                        i = (i + 2).coerceAtMost(n)
                        continue
                    }
                    else -> { out.append(c); i++ }
                }
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}

/**
 * TvBox 配置容错解析器（方案 4.4）。
 *
 * 容错策略：
 * - 顶层字段缺失取默认值
 * - 单条 site 非法只丢弃该条并记 warning，不失败整份配置
 * - 未知字段忽略（各家魔改配置字段极多）
 * - 顶层 JSON 非法才返回 error
 */
object ConfigParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        allowTrailingComma = true
    }

    fun parse(text: String): ConfigParseResult {
        if (text.isBlank()) {
            return ConfigParseResult(null, error = "配置内容为空")
        }
        // 部分配置带 BOM 或前后有空白/HTML 包裹
        var cleaned = text.trim().removePrefix("\uFEFF").trim()
        // 剥离注释 + 转义裸控制字符（生态配置的常态，见 DirtyJsonRepair）
        cleaned = runCatching { DirtyJsonRepair.stripComments(cleaned) }
            .getOrElse { e ->
                TbLog.w(e, "注释剥离失败，按原文解析")
                cleaned
            }
        val root = runCatching { json.parseToJsonElement(cleaned) }.getOrElse { e ->
            TbLog.e(e, "配置JSON 解析失败")
            return ConfigParseResult(null, error = "JSON 格式错误：${e.message?.take(120)}")
        }

        val obj = root as? JsonObject
            ?: return ConfigParseResult(null, error = "配置根节点不是 JSON 对象")

        val warnings = mutableListOf<String>()

        val spider = obj.str("spider")
        val wallpaper = obj.str("wallpaper")

        val sites = obj.arr("sites").mapIndexedNotNull { i, el ->
            val o = el as? JsonObject
            if (o == null) {
                warnings += "sites[$i] 不是对象，已跳过"
                return@mapIndexedNotNull null
            }
            parseSite(o) ?: run {
                warnings += "sites[$i] 缺少 key/api，已跳过"
                null
            }
        }

        val parses = obj.arr("parses").mapNotNull { el -> parseParseConfig(el) }

        val rules = obj.arr("rules").mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            parseRule(o)
        }

        val ads = obj.arr("ads").mapNotNull { it.contentOrNullSafe() }

        val proxy = obj.arr("proxy").mapNotNull { el ->
            val o = el as? JsonObject
            if (o == null) {
                warnings += "proxy 条目不是对象，已跳过"
                null
            } else {
                parseProxy(o, warnings)
            }
        }

        val flags = obj.arr("flags").mapNotNull { el ->
            (el as? JsonPrimitive)?.contentOrNullSafe()
        }

        // DoH 节点同样只做结构解析：解析失败/非 http(s) 直接跳过，不阻断配置导入
        val doh = obj.arr("doh").mapNotNull { el ->
            when (el) {
                is JsonObject -> parseDoh(el)
                is JsonPrimitive -> el.contentOrNullSafe()
                    ?.trim()
                    ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                    ?.let { DohConfig(url = it) }
                else -> null
            }
        }

        // 直播源：解析失败/为空都不影响点播导入 —— 一份只含直播的配置很常见，
        // 反过来一份只有点播没有直播源才是常态，两者不该互相阻塞。
        val lives = obj.arr("lives").mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            parseLive(o)
        }

        if (sites.isEmpty()) {
            return ConfigParseResult(
                config = null,
                warnings = warnings,
                error = "配置中没有可用站点（sites 为空或全部非法）",
            )
        }

        val unknown = sites.filter { !it.isKnownType }
        if (unknown.isNotEmpty()) {
            warnings += "${unknown.size} 个站点类型不在已知路由表内（已保留但 UI 灰显）：" +
                unknown.take(5).joinToString("、") { "${it.name}(type=${it.type})" }
        }

// 协议认识但当前构建没有引擎的源仍保留并提示；JS 源已由 QuickJS 引擎接管。
        val noEngine = sites.filter { it.isKnownType && !it.isSupported }
        if (noEngine.isNotEmpty()) {
            val byReason = noEngine.groupBy { it.unsupportedReason ?: "暂不可用" }
            warnings += "${noEngine.size} 个站点当前版本无法播放（已保留，待引擎落地后可用）：" +
                byReason.entries.joinToString("；") { (reason, list) ->
                    "${reason} × ${list.size}"
                }
        }

        val config = TvBoxConfig(
            spider = spider,
            wallpaper = wallpaper,
            sites = sites,
            parses = parses,
            flags = flags,
            rules = rules,
            ads = ads,
            proxy = proxy,
            doh = doh,
            lives = lives,
        )

        TbLog.i(
            "配置解析完成：站点 %d（协议已知 %d，M1 可用 %d），parses %d，rules %d，doh %d，spider=%s",
            config.sites.size, config.knownSites().size, config.supportedSites().size,
            parses.size, rules.size, doh.size, spider?.takeIf { it.isNotBlank() } ?: "无",
        )

        return ConfigParseResult(config, warnings)
    }

    /** 解析单条 site；返回 null 表示该条非法 */
    private fun parseSite(o: JsonObject): SiteConfig? {
        val key = o.str("key")?.takeIf { it.isNotBlank() } ?: return null
        val api = o.str("api")?.takeIf { it.isNotBlank() } ?: return null
        val type = o.int("type") ?: 0
        return SiteConfig(
            key = key,
            name = o.str("name")?.takeIf { it.isNotBlank() } ?: key,
            type = type,
            api = api.trim(),
            searchable = o.int("searchable") ?: 1,
            quickSearch = o.int("quickSearch") ?: 0,
            filterable = o.int("filterable") ?: 0,
            ext = o.ext("ext"),
            header = o.obj("header"),
            jar = o.str("jar"),
            userAgent = o.str("userAgent") ?: o.str("ua"),
            playerType = o.int("playerType"),
            categories = o.arr("categories").mapNotNull { it.contentOrNullSafe() },
            playUrl = o.str("playUrl"),
            timeout = o.int("timeout"),
        )
    }

    /**
     * 解析单条 live；返回 null 表示订阅地址为空。
     *
     * 生态字段双名兼容（归一到模型字段）：
     * - 订阅地址：`api`（TVBoxOS）/ `url`（各家魔改）—— fun.json 用的全是 `url`
     * - UA：`userAgent` / `ua`
     * `ext` 可能是字符串或对象（对象序列化为紧凑 JSON 文本，与字符串形态统一）。
     */
    private fun parseLive(o: JsonObject): LiveConfig? {
        val api = (o.str("api") ?: o.str("url") ?: "").trim()
        if (api.isBlank()) {
            // 内联 channels 的直播源（少数魔改写法）暂不支持，但要留痕方便排查
            if (o["channels"] != null) TbLog.w("lives 含内联 channels 形态，暂不支持，跳过")
            return null
        }
        return LiveConfig(
            name = o.str("name") ?: "",
            type = o.int("type") ?: 0,
            api = api,
            jar = o.str("jar"),
            ext = o.ext("ext"),
            userAgent = o.str("userAgent") ?: o.str("ua"),
            epg = o.str("epg"),
            logo = o.str("logo"),
            playerType = o.int("playerType"),
            timeout = o.int("timeout"),
            group = o.str("group"),
            catchup = parseCatchup(o),
        )
    }

    /**
     * 解析直播源级 catchup。
     *
     * 兼容：
     * - `"catchup": {"type","source","replace","regex"}`
     * - `"catchup": "default"` + 平级 `catchup-source` / `catchup-replace`
     */
    private fun parseCatchup(o: JsonObject): tv.own.owntv.mobile.tvbox.config.model.CatchupConfig? {
        val raw = o["catchup"]
        val obj = raw as? JsonObject
        val type = when (raw) {
            is JsonPrimitive -> raw.contentOrNullSafe().orEmpty()
            else -> obj?.str("type").orEmpty()
        }
        val source = obj?.str("source") ?: o.str("catchup-source").orEmpty()
        val replace = obj?.str("replace") ?: o.str("catchup-replace").orEmpty()
        val regex = obj?.str("regex").orEmpty()
        if (type.isBlank() && source.isBlank() && replace.isBlank()) return null
        return tv.own.owntv.mobile.tvbox.config.model.CatchupConfig(
            type = type,
            source = source,
            replace = replace,
            regex = regex,
        )
    }

    /**
     * 解析单条配置级 DoH 节点。
     *
     * 只接受 http(s) 端点；`ips` 是端点域名的 bootstrap 地址
     * （系统 DNS 被污染时靠它直连 DoH 服务器）。
     */
    private fun parseDoh(o: JsonObject): DohConfig? {
        val url = o.str("url") ?: o.str("endpoint") ?: return null
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
        return DohConfig(
            name = o.str("name") ?: "",
            url = url.trim(),
            ips = o.arr("ips").mapNotNull { it.contentOrNullSafe() }.map { it.trim() }
                .filter { it.isNotEmpty() },
        )
    }

    /**
     * 解析单条嗅探规则；返回 null 表示该条非法。
     *
     * `hosts` 兼容三种形态（fun.json 里三种都出现了）：
     * - 字符串 `"*"` 或逗号分隔
     * - 数组 `["a.com", "b.com"]` → 逗号归一
     * - 缺失 / 单数写法 `host` → 默认全匹配
     */
    private fun parseRule(o: JsonObject): SniffRule? {
        val hosts = when (val el = o["hosts"] ?: o["host"]) {
            null -> "*"
            is JsonPrimitive -> el.contentOrNullSafe() ?: "*"
            is JsonArray -> el.mapNotNull { it.contentOrNullSafe() }
                .joinToString(",").ifBlank { "*" }
            else -> "*"
        }
        val rule = o.arr("rule").mapNotNull { it.contentOrNullSafe() }
        // `ads` 是显式广告规则；`regex` 原样保留在 SniffRule.regex 里：
        // - 播放列表类正则同时并入 ads，供 M3U8 净化（沿用既有口径）；
        // - 非播放列表正则（item_id=、video/tos/cn 等）由嗅探器按 hosts 限定做 URL 正则匹配，
        //   不再像以前那样被整条丢弃。
        val regex = o.arr("regex").mapNotNull { it.contentOrNullSafe() }
        val adRules = LinkedHashSet<String>()
        o.arr("ads").mapNotNullTo(adRules) { it.contentOrNullSafe() }
        regex.filterTo(adRules) { AdRuleHeuristics.isPlaylistRule(it) }
        return SniffRule(
            name = o.str("name") ?: "",
            hosts = hosts,
            rule = rule,
            ads = adRules.toList(),
            regex = regex,
        )
    }

    /**
     * 解析单条配置级代理规则。
     *
     * 只做结构归一，不验证代理可达；`hosts`/`urls` 支持字符串与数组两种写法。
     * 缺 host 或缺 url 的条目丢弃并记 warning，不阻断整份配置。
     */
    private fun parseProxy(o: JsonObject, warnings: MutableList<String>): ProxyConfig? {
        val hosts = stringList(o, "hosts") ?: stringList(o, "host").orEmpty()
        val urls = stringList(o, "urls") ?: stringList(o, "url").orEmpty()
        if (hosts.isEmpty() || urls.isEmpty()) {
            warnings += "proxy 条目缺少 hosts/urls，已跳过"
            return null
        }
        return ProxyConfig(
            name = o.str("name") ?: "",
            hosts = hosts,
            urls = urls,
        )
    }

    /** 取字符串或字符串数组；键缺失返回 null（与空数组区分，用于双名兼容） */
    private fun stringList(o: JsonObject, key: String): List<String>? {
        val el = o[key] ?: return null
        return when (el) {
            is JsonPrimitive -> listOfNotNull(el.contentOrNullSafe())
            is JsonArray -> el.mapNotNull { it.contentOrNullSafe() }
            else -> null
        }
    }

    /**
     * ext 取值：字符串原样，对象/数组序列化为紧凑 JSON 文本。
     *
     * 为什么要兼容对象形态：魔改配置的 ext 常是结构化配置（如
     * `{"userScript":"./x.user.js","spider":{...}}`），
     * 统一成字符串后下游（CmsSpider.parseExt 等）只需处理一种形态。
     */
    /**
     * ParseConfig 无 @Serializable 编译器插件可用（本移植按方案 §5.3-A 只带运行时依赖），
     * 字段读取手写：宽松取 name/type/url/ext/show，形态异常整条丢弃。
     */
    private fun parseParseConfig(el: JsonElement): ParseConfig? {
        val o = el as? JsonObject ?: return null
        return ParseConfig(
            name = o.str("name") ?: "",
            type = o.int("type") ?: 0,
            url = o.str("url") ?: "",
            ext = o["ext"] as? JsonObject,
            show = o.str("show"),
        )
    }

    private fun JsonObject.ext(key: String): String? {
        val el = this[key] ?: return null
        return when (el) {
            is JsonPrimitive -> el.contentOrNullSafe()
            // JsonElement.toString() renders the same JSON text encodeToString would.
            is JsonObject, is JsonArray -> el.toString().takeIf { it.isNotBlank() }
            else -> null
        }
    }

    // ---------- 宽容的 JSON 取值辅助：兼容 "1"/1/true 等写法 ----------

    private fun JsonObject.str(key: String): String? {
        val el = this[key] ?: return null
        if (el is JsonPrimitive) {
            if (el.isString) return el.content.takeIf { it.isNotBlank() }
            return el.content.takeIf { it.isNotBlank() && it != "null" }
        }
        return null
    }

    private fun JsonObject.int(key: String): Int? {
        val el = this[key] ?: return null
        if (el !is JsonPrimitive) return null
        el.content.trim().toIntOrNull()?.let { return it }
        // 兼容 "1"/"0" 之外的真假值
        return when (el.booleanOrNull) {
            true -> 1
            false -> 0
            else -> null
        }
    }

    private fun JsonObject.obj(key: String): JsonObject? =
        runCatching { this[key]?.jsonObject }.getOrNull()

    private fun JsonObject.arr(key: String): List<JsonElement> =
        (this[key] as? JsonArray).orEmpty()

    private fun JsonElement.contentOrNullSafe(): String? = runCatching {
        (this as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
    }.getOrNull()

    /** 供测试用：宽松解析任意 JSON 的 sites 数量（配置体检） */
    fun quickSiteCount(text: String): Int = runCatching {
        val el = json.parseToJsonElement(text.trim().removePrefix("\uFEFF"))
        (el.jsonObject["sites"] as? JsonArray)?.size ?: 0
    }.getOrDefault(0)
}
