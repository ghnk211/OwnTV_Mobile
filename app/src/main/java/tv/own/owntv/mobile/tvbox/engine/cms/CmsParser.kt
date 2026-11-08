package tv.own.owntv.mobile.tvbox.engine.cms

import tv.own.owntv.mobile.tvbox.util.TbLog

import tv.own.owntv.mobile.tvbox.engine.FilterDef
import tv.own.owntv.mobile.tvbox.engine.FilterValue
import tv.own.owntv.mobile.tvbox.util.TextUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 苹果 CMS（macCMS）接口响应解析。
 *
 * 标准形态：
 * ```json
 * {"code":1,"msg":"数据列表","page":1,"pagecount":10,"limit":20,"total":200,
 *  "list":[{"vod_id":123,"vod_name":"xx","vod_pic":"...","vod_play_from":"线路",
 *           "vod_play_url":"第1集$http://a.m3u8#第2集$http://b.m3u8"}],
 *  "class":[{"type_id":1,"type_name":"电影"}]}
 * ```
 * 字段全部按字符串取（各家 CMS 有的返回数字有的返回字符串）。
 */
object CmsParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** 是否是有效的 CMS JSON 响应 */
    fun looksLikeCms(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return false
        return trimmed.contains("\"list\"") || trimmed.contains("\"code\"") ||
            trimmed.contains("\"pagecount\"")
    }

    data class CmsPage(
        val movies: List<CmsMovie> = emptyList(),
        val classes: List<CmsClass> = emptyList(),
        /** 筛选树：type_id → 筛选维度列表（CMS 与首页/分类同一份响应里带） */
        val filters: Map<String, List<FilterDef>> = emptyMap(),
        val page: Int = 1,
        val pageCount: Int = 1,
        val total: Int = 0,
        val limit: Int = 0,
        val ok: Boolean = false,
        val message: String? = null,
    )

    data class CmsClass(val typeId: String, val typeName: String)

    data class CmsMovie(
        val id: String,
        val name: String,
        val pic: String? = null,
        val remark: String? = null,
        val year: String? = null,
        val area: String? = null,
        val typeName: String? = null,
        val content: String? = null,
        val actor: String? = null,
        val director: String? = null,
        val playFrom: String? = null,
        val playUrl: String? = null,
        val extra: Map<String, String> = emptyMap(),
    )

    /** 保留的 vod_* 扩展字段（详情页兜底展示用） */
    private val EXTRA_KEYS = listOf(
        "vod_year", "vod_area", "vod_remarks", "vod_actor", "vod_director",
        "vod_lang", "vod_class", "vod_score", "vod_time", "vod_status",
    )

    fun parsePage(body: String?): CmsPage {
        if (body.isNullOrBlank()) return CmsPage(message = "空响应")
        val trimmed = body.trim()
        // 苹果CMS 双形态：JSON（新版 api.php/provide/vod/）与 XML（老版 api/xml.php）。
        // 入口按首字节嗅探分流，产出与 JSON 分支同构，下游 CmsSpider 零改动。
        if (trimmed.startsWith("<") && !trimmed.startsWith("<!")) return parseXmlPage(trimmed)
        val root = runCatching { json.parseToJsonElement(trimmed) }.getOrElse {
            return CmsPage(message = "JSON 解析失败：${it.message?.take(80)}")
        }
        val obj = root as? JsonObject ?: return CmsPage(message = "响应不是 JSON 对象")

        val code = obj.int("code")
        // code: 1=成功 0=失败；部分站不返回 code，只要 list 非空即视为成功
        val list = (obj["list"] as? JsonArray).orEmpty()
        val classes = (obj["class"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("type_id") ?: o.str("typeid") ?: return@mapNotNull null
            CmsClass(
                typeId = id,
                typeName = o.str("type_name") ?: o.str("typename") ?: id,
            )
        }

        val msg = obj.str("msg")
        val ok = when (code) {
            1 -> true
            0 -> list.isNotEmpty()
            null -> list.isNotEmpty() || classes.isNotEmpty()
            else -> list.isNotEmpty()
        }

        return CmsPage(
            movies = list.mapNotNull { parseMovie(it) },
            classes = classes,
            filters = parseFilters(obj["filters"]),
            page = obj.int("page") ?: 1,
            pageCount = obj.int("pagecount") ?: 1,
            total = obj.int("total") ?: 0,
            limit = obj.int("limit") ?: 0,
            ok = ok,
            message = if (ok) null else (msg ?: "CMS 返回 code=$code"),
        )
    }

    /**
     * 解析筛选树（对齐 TVBoxOS `AbsSortJson.filters`）。
     *
     * 标准形态 —— 顶层是 **type_id → 该分类的筛选维度数组**：
     * ```json
     * "filters": {
     *   "1": [ { "key":"class","name":"剧情",
     *            "value":[{"n":"全部","v":""},{"n":"喜剧","v":"喜剧"}] } ]
     * }
     * ```
     *
     * 另一个常见变体是"维度对象"而非数组：
     * ```json
     * "filters": { "1": { "class": {"name":"剧情","value":[...]} } }
     * ```
     * 两种都要吃下，否则相当一部分 CMS 源的筛选会整片消失。
     *
     * 解析不出来返回空 map —— 那是"该源没给筛选"，不是错误，
     * 上层按"无筛选"渲染即可，不该因此报错或中断。
     */
    fun parseFilters(el: JsonElement?): Map<String, List<FilterDef>> {
        val root = el as? JsonObject ?: return emptyMap()
        if (root.isEmpty()) return emptyMap()

        return root.mapNotNull { (typeId, defsEl) ->
            val defs = when (defsEl) {
                is JsonArray -> defsEl.mapNotNull { parseFilterDef(it) }
                is JsonObject -> defsEl.mapNotNull { (key, vEl) ->
                    // 对象形态：key 即参数名，vEl 是 {name,value}
                    val vo = vEl as? JsonObject ?: return@mapNotNull null
                    val name = vo.str("name") ?: key
                    val values = parseFilterValues(vo["value"] ?: vo["values"])
                    if (values.isEmpty()) null else FilterDef(key, name, values)
                }
                else -> emptyList()
            }
            if (defs.isEmpty()) null else typeId to defs
        }.toMap()
    }

    private fun parseFilterDef(el: JsonElement): FilterDef? {
        val o = el as? JsonObject ?: return null
        val key = o.str("key").orEmpty().ifBlank { o.str("name").orEmpty() }
        if (key.isBlank()) return null
        val name = o.str("name") ?: key
        val values = parseFilterValues(o["value"] ?: o["values"])
        if (values.isEmpty()) return null
        return FilterDef(key, name, values)
    }

    /** 候选项：`[{n,v}]`；也容忍 `["喜剧","动作"]` 裸数组与 `{"n":"v"}` 扁平对象 */
    private fun parseFilterValues(el: JsonElement?): List<FilterValue> = when (el) {
        is JsonArray -> el.mapNotNull { item ->
            when (item) {
                is JsonObject -> {
                    val n = item.str("n") ?: item.str("name") ?: item.str("title")
                        ?: return@mapNotNull null
                    val v = item.str("v") ?: item.str("value") ?: n
                    FilterValue(n, v)
                }
                is JsonPrimitive -> item.content.takeIf { it.isNotBlank() }
                    ?.let { FilterValue(it, it) }
                else -> null
            }
        }
        is JsonObject -> el.mapNotNull { (k, v) ->
            val n = (v as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: k
            FilterValue(n, k)
        }
        else -> emptyList()
    }

    /** 解析单条影片；缺少 id 或 name 返回 null */
    fun parseMovie(el: JsonElement): CmsMovie? {
        val o = el as? JsonObject ?: return null
        val id = o.str("vod_id")?.takeIf { it.isNotBlank() } ?: return null
        val name = o.str("vod_name")?.takeIf { it.isNotBlank() } ?: return null

        val extra = buildMap {
            EXTRA_KEYS.forEach { k ->
                o.str(k)?.takeIf { it.isNotBlank() }?.let { put(k, it) }
            }
        }

        return CmsMovie(
            id = id,
            name = TextUtil.normalizeName(name),
            pic = o.str("vod_pic"),
            remark = o.str("vod_remarks"),
            year = o.str("vod_year"),
            area = o.str("vod_area"),
            typeName = o.str("type_name"),
            content = o.str("vod_content"),
            actor = o.str("vod_actor"),
            director = o.str("vod_director"),
            playFrom = o.str("vod_play_from"),
            playUrl = o.str("vod_play_url"),
            extra = extra,
        )
    }

    /**
     * 详情接口可能返回：
     * - 单对象 { "vod_id":..., "vod_play_url":... }
     * - 数组 [ {...} ]
     * - 包装对象 { "list": [ {...} ] }
     * 逐个尝试，返回第一个能解析出播放地址的。
     */
    fun parseDetail(body: String?): CmsMovie? {
        if (body.isNullOrBlank()) return null
        val trimmed = body.trim()
        // XML 形态详情（老版 api/xml.php）：与 JSON 分支同构产出
        if (trimmed.startsWith("<") && !trimmed.startsWith("<!")) return parseXmlDetail(trimmed)
        val root = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
            ?: return null
        return when (root) {
            is JsonArray -> root.firstNotNullOfOrNull { parseMovie(it) }
            is JsonObject -> {
                parseMovie(root)
                    ?: (root["list"] as? JsonArray)?.firstNotNullOfOrNull { parseMovie(it) }
                    ?: (root["data"] as? JsonObject)?.let { parseMovie(it) }
                    ?: (root["data"] as? JsonArray)?.firstNotNullOfOrNull { parseMovie(it) }
            }

            else -> null
        }
    }

    // ---------- 宽容取值：CMS 字段类型混乱（数字/字符串/布尔都见过） ----------

    /** 这些文本字段返回 "0" 视为无值 */
    private val ZERO_MEANING_EMPTY = setOf(
        "vod_year", "vod_area", "vod_actor", "vod_director", "vod_lang", "vod_class", "vod_time",
    )

    private fun JsonObject.str(key: String): String? {
        val el = this[key] ?: return null
        if (el !is JsonPrimitive) return null
        if (el is kotlinx.serialization.json.JsonNull) return null
        val s = el.content
        if (s.isBlank() || s == "null") return null
        if (s == "0" && key in ZERO_MEANING_EMPTY) return null
        return s
    }

    private fun JsonObject.int(key: String): Int? {
        val el = this[key] ?: return null
        if (el !is JsonPrimitive) return null
        el.content.trim().toIntOrNull()?.let { return it }
        return when (el.booleanOrNull) {
            true -> 1
            false -> 0
            else -> null
        }
    }

    /** 提取 searchSuggest / 关键词联想（部分源支持） */
    fun parseSuggestions(body: String?): List<String> {
        if (body.isNullOrBlank()) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(body.trim())
            val list = when (root) {
                is JsonArray -> root
                is JsonObject -> (root["list"] as? JsonArray) ?: (root["data"] as? JsonArray)
                else -> null
            }
            list.orEmpty().mapNotNull { el ->
                when (el) {
                    is JsonPrimitive -> el.content.takeIf { it.isNotBlank() }
                    is JsonObject -> (el["vod_name"] as? JsonPrimitive)?.content
                        ?: (el["name"] as? JsonPrimitive)?.content
                        ?: (el["title"] as? JsonPrimitive)?.content

                    else -> null
                }
            }.map { TextUtil.normalizeName(it) }.filter { it.isNotBlank() }.distinct()
        }.getOrDefault(emptyList())
    }

    // ---------- 苹果CMS XML 形态（老版 api/xml.php） ----------

    /**
     * 解析 XML 列表/分类响应（`<rss><list ...>…<video>…</video></list></rss>`）。
     *
     * 字段映射与 JSON 分支同构（对齐 JSON 分支的 vod_* 字段）：
     * `<video><id/><name/><pic/><dt/><note/><dd flag="线路">第1集$url#第2集$url</dd>`
     * → `CmsMovie`（`dd` 的 `$` 分集、`#` 分隔直接复用 [tv.own.owntv.mobile.tvbox.engine.PlayUrlCodec]）。
     *
     * @return 与 JSON 分支同构的 [CmsPage]；XML 非法时返回带 message 的空页
     */
    private fun parseXmlPage(body: String): CmsPage {
        val doc = parseXmlSafe(body) ?: return CmsPage(message = "XML 解析失败")
        val listEl = doc.getElementsByTagName("list").item(0) as? Element
        val videoEls = doc.getElementsByTagName("video")
        val movies = mutableListOf<CmsMovie>()
        for (i in 0 until videoEls.length) {
            (videoEls.item(i) as? Element)?.let { el -> xmlMovie(el)?.let { movies.add(it) } }
        }
        val classes = doc.getElementsByTagName("ty").let { nodes ->
            (0 until nodes.length).mapNotNull { i ->
                val el = nodes.item(i) as? Element ?: return@mapNotNull null
                val id = el.getAttribute("id").ifBlank { el.text() } ?: return@mapNotNull null
                if (el.text().isNullOrBlank()) return@mapNotNull null
                CmsClass(typeId = id, typeName = el.text() ?: id)
            }
        }
        val ok = movies.isNotEmpty() || classes.isNotEmpty()
        return CmsPage(
            movies = movies,
            classes = classes,
            page = listEl?.intAttr("page") ?: 1,
            pageCount = listEl?.intAttr("pagecount") ?: 1,
            total = listEl?.intAttr("recordcount") ?: 0,
            limit = listEl?.intAttr("pagesize") ?: 0,
            ok = ok,
            message = if (ok) null else "XML 响应无 video 节点",
        )
    }

    /**
     * 解析 XML 详情响应（与 JSON 详情同构：取第一个 `<video>`）。
     * XML 非法或无 video 节点返回 null（由上层回退 ac=videolist）。
     */
    private fun parseXmlDetail(body: String): CmsMovie? {
        val doc = parseXmlSafe(body) ?: return null
        val nodes = doc.getElementsByTagName("video")
        return (0 until nodes.length).firstNotNullOfOrNull { i ->
            (nodes.item(i) as? Element)?.let { xmlMovie(it) }
        }
    }

    /** 单个 `<video>` → [CmsMovie]；缺 id/name 返回 null（与 JSON 分支一致） */
    private fun xmlMovie(el: Element): CmsMovie? {
        val id = el.childText("id")?.takeIf { it.isNotBlank() } ?: return null
        val name = el.childText("name")?.takeIf { it.isNotBlank() } ?: return null

        // 播放线路：一个 video 下多个 <dd flag="线路名">第1集$url#…</dd>，
        // 按 flag 顺序拼成 JSON 分支同构的 vod_play_from / vod_play_url
        val ddNodes = el.getElementsByTagName("dd")
        val froms = mutableListOf<String>()
        val urls = mutableListOf<String>()
        for (i in 0 until ddNodes.length) {
            val dd = ddNodes.item(i) as? Element ?: continue
            val body = dd.text()?.takeIf { it.isNotBlank() } ?: continue
            froms += dd.getAttribute("flag").ifBlank { "线路${i + 1}" }
            urls += body
        }

        val extra = buildMap {
            listOf(
                "lang" to "vod_lang", "area" to "vod_area", "year" to "vod_year",
                "actor" to "vod_actor", "director" to "vod_director", "des" to "vod_content",
            ).forEach { (xmlKey, jsonKey) ->
                el.childText(xmlKey)?.takeIf { it.isNotBlank() && it != "0" }?.let { put(jsonKey, it) }
            }
        }

        return CmsMovie(
            id = id,
            name = TextUtil.normalizeName(name),
            pic = el.childText("pic"),
            remark = el.childText("note"),
            year = el.childText("year")?.takeIf { it != "0" },
            area = el.childText("area")?.takeIf { it != "0" },
            typeName = el.childText("type"),
            content = el.childText("des"),
            actor = el.childText("actor")?.takeIf { it != "0" },
            director = el.childText("director")?.takeIf { it != "0" },
            playFrom = froms.takeIf { it.isNotEmpty() }?.joinToString("$$$"),
            playUrl = urls.takeIf { it.isNotEmpty() }?.joinToString("$$$"),
            extra = extra,
        )
    }

    /**
     * 安全解析 XML 文本为 DOM。
     *
     * ## 为什么不能用 setFeature 做 XXE 加固
     * Android 的 `org.apache.harmony.xml.parsers.DocumentBuilderFactoryImpl`
     * **不支持 `setFeature(...)`，调用即抛 ParserConfigurationException**。
     * 真机实测：那三行加固代码让 `parseXmlSafe` 对**每一个** XML(type=0) 采集源
     * 返回 null，量子/非凡首页恒"源返回空"，而 JVM 单测里 JDK 实现支持这些
     * feature，测试全绿 —— 典型的"只在真机上坏"。
     * 故外部实体一律改由 [EntityResolver] 挡成空输入，这条路径跨实现都有效。
     *
     * 解析失败（含非 XML 文本）返回 null，由调用方降级。
     */
    private fun parseXmlSafe(body: String): Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setValidating(false)
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        builder.parse(InputSource(StringReader(body)))
    }.onFailure {
        TbLog.tag("CmsParser").w(it, "XML DOM 解析失败（%d 字符）：%s", body.length, it.toString())
    }.getOrNull()

    // ---------- XML DOM 取值小工具 ----------

    /** 直接子元素 `<tag>` 的文本（CDATA 自动展开）；缺失返回 null */
    private fun Element.childText(tag: String): String? {
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.tagName == tag) {
                return n.textContent?.trim()?.takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    /** 元素自身文本内容 */
    private fun Element.text(): String? =
        textContent?.trim()?.takeIf { it.isNotBlank() }

    /** 整数属性；缺失/非法返回 null */
    private fun Element.intAttr(name: String): Int? =
        getAttribute(name).takeIf { it.isNotBlank() }?.toIntOrNull()

    /** 供 XML 模式复用的 JSON 转义输出（部分 CMS 返回 HTML 包裹的 JSON） */
    fun stripHtmlWrapper(body: String?): String? {
        if (body.isNullOrBlank()) return null
        var s = body.trim()
        // ## XML 响应必须原样放行
        // 苹果CMS 的 api/xml.php 返回的就是 XML 文档，且 name/des 必带
        // `<![CDATA[...]]>` —— 若走到下面的 CDATA 正则，会被替换成内层文本，
        // 整个 XML 文档就毁了（真机实测：`<rss>...<![CDATA[美少女]]></rss>`
        // → `美少女`）。XML 由 parseXmlPage 专门处理，这里只管 HTML 包裹的 JSON。
        if (looksLikeXml(s)) return s
        // <script>...</script> 或 <![CDATA[...]]> 包裹（JSON 被裹在里面的形态）
        val cd = CDATA.find(s)?.groupValues?.get(1)
        if (!cd.isNullOrBlank()) return cd.trim()
        val script = SCRIPT.find(s)?.groupValues?.get(1)
        if (!script.isNullOrBlank()) return script.trim()
        // 直接是 HTML 页面（注意 <!DOCTYPE html> 也以 "<" 开头，不可用 startsWith("<!") 排除）
        if (s.startsWith("<") && s.contains("<html", ignoreCase = true)) return null
        return s
    }

    /**
     * 是否是苹果CMS XML 响应（`<?xml` 头 / `<rss` 根）。
     *
     * 与 [parsePage] / [parseDetail] 的分流判定保持同一口径 ——
     * 三处对"这是不是 XML"的判断必须一致，否则会出现
     * "stripHtmlWrapper 放行、parsePage 却按 JSON 解析"这类错位。
     */
    fun looksLikeXml(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("<?xml", ignoreCase = true) ||
            t.startsWith("<rss", ignoreCase = true)
    }

    private val CDATA = Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)
    private val SCRIPT = Regex("<script[^>]*>(.*?)</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /** 调试用：提取 JSON 中的某个原始字段 */
    fun rawField(body: String?, key: String): String? = runCatching {
        val root = json.parseToJsonElement(body?.trim().orEmpty()).jsonObject
        (root[key] as? JsonPrimitive)?.content
    }.getOrNull()

    /** 兼容部分源把整个 JSON 当字符串塞进 data 字段 */
    fun parseEmbedded(body: String?): CmsMovie? {
        val embedded = rawField(body, "data") ?: return null
        return parseDetail(embedded)
    }
}
