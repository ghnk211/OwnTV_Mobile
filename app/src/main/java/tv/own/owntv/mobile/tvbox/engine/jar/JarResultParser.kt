package tv.own.owntv.mobile.tvbox.engine.jar

import tv.own.owntv.mobile.tvbox.util.TbLog

import tv.own.owntv.mobile.tvbox.engine.ClassInfo
import tv.own.owntv.mobile.tvbox.engine.FilterDef
import tv.own.owntv.mobile.tvbox.engine.FilterValue
import tv.own.owntv.mobile.tvbox.engine.Movie
import tv.own.owntv.mobile.tvbox.engine.PlayResult
import tv.own.owntv.mobile.tvbox.engine.PlayUrlCodec
import tv.own.owntv.mobile.tvbox.engine.SpiderResult
import tv.own.owntv.mobile.tvbox.util.StripHtml
import tv.own.owntv.mobile.tvbox.util.TextUtil
import tv.own.owntv.mobile.tvbox.util.legalHeaders
import tv.own.owntv.mobile.tvbox.util.toJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Jar 源返回值 → 归一化模型（方案 5.2 / 5.3）。
 *
 * ## 为什么需要这一层
 * spider jar 的五接口返回 `Any?`，实测形态至少有四种：
 * 1. `com.github.catvod.bean.Result`（老协议，字段 vod_*）
 * 2. `com.google.gson.JsonObject`（新协议，字段 vod_* 但走 Gson）
 * 3. `Map<String, Any?>`（部分 jar 直接返回 map）
 * 4. JSON 字符串（部分 jar 手拼字符串返回）
 *
 * 若不归一化，UI 层就要为每种形态各写一套解析。归一到这里后，
 * 上层（HomeScreen / DetailScreen）与 CMS 源完全共用一套逻辑。
 *
 * ## 字段兼容策略
 * jar 生态的字段命名不统一，故按优先级多别名读取：
 * - id: `vod_id` / `id` / `vodId`
 * - 封面: `vod_pic` / `pic` / `vod_pic_slide`（slide 为横图，退而求其次）
 * - 线路: `vod_play_from` / `playFrom`
 * 读取时统一按 [asString] 做类型无关转换（Int/Long/String/BigDecimal 都能取到）。
 */
object JarResultParser {

    private const val TAG = "JarParser"

    /**
     * jar 返回的整体结果 → SpiderResult
     *
     * ## 分类字段名：生态用 `class`（单数），不是 `classes`
     * TVBoxOS `AbsSortJson` 明确写着 `@SerializedName(value = "class")`，
     * 即 jar 返回的 JSON 里分类数组的键是 **`class`**。早前这里只读 `classes`（复数），
     * 导致 jar 源的分类**永远解析为空** —— 首页只剩"全部"一个 tab，
     * 且 homeContent 的 list 也空时直接误报"没有首页浏览内容"（真机 4k.json/newwex.json 复现）。
     *
     * 故优先读 `class`，同时回落 `classes`（个别魔改 jar 用复数）。
     *
     * @param requireName 是否把"名称非空"作为条目的硬性判据（默认 true）。
     *   仅**详情路径**应传 false，理由见 [toMovie]。
     */
    fun toResult(
        raw: Any?,
        baseUrl: String? = null,
        requireName: Boolean = true,
        fallbackId: String? = null,
    ): SpiderResult? {
        val map = asMap(raw) ?: return null
        // 生态主键是 "class"（单数），个别 jar 用 "classes"，两个都认
        val classArray = (map["class"] ?: map["classes"]).asList()
        val classes = classArray.mapNotNull { clsItem ->
            val cm = asMap(clsItem) ?: return@mapNotNull null
            val id = cm.str("type_id", "typeId", "id")
            val name = cm.str("type_name", "typeName", "name")
            if (id.isNullOrBlank() || name.isNullOrBlank()) return@mapNotNull null
            ClassInfo(id, name)
        }
        val rawList = map["list"].asList()
        val movies = rawList.mapNotNull { toMovie(it, baseUrl, requireName, fallbackId) }
        // 原始 list 有条目却全被过滤掉时，必须留下根因：这是"详情页未返回详情数据 /
        // 列表页空白"最常见的一类原因（缺 id、缺 name、list 元素形态不认识），
        // 而调用方只看到"空结果"，日志里毫无线索。
        if (rawList.isNotEmpty() && movies.isEmpty()) {
            TbLog.tag(TAG).w(
                "list 有 %d 条但全部被丢弃（requireName=%s）：首条=%s",
                rawList.size, requireName, preview(rawList.firstOrNull(), 300),
            )
        }
        return SpiderResult(
            classes = classes,
            // jar 常常无条件带上 filterDef，这里直接透传，省得 JarSpider 再补一次
            filters = toFilters(raw),
            list = movies,
            page = map.int("page") ?: 1,
            pagecount = map.int("pagecount", "pageCount") ?: 1,
            total = map.int("total") ?: movies.size,
            limit = map.int("limit") ?: 0,
        )
    }

    /**
     * jar 返回的播放信息 → PlayResult。
     *
     * parse 语义沿用 TVBox：0 = 直链，1 = 需嗅探/解析。
     * 这里不做解析（归 M3），只把 flag 带出去供上层判断。
     */
    fun toPlayResult(raw: Any?): PlayResult? {
        val map = asMap(raw) ?: return null
        val url = map.str("url") ?: return null
        val parse = map.int("parse") ?: 0
        // header 可能是 Map 或 "k:v\nk:v" 字符串，两种都收
        val header = map["header"]?.let { h -> toHeaderJson(h) }
        val flag = map.str("flag")
        val urls = map["urls"].asList()
            .mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
        return PlayResult(
            parse = parse,
            playUrl = map.str("playUrl"),
            url = url,
            header = header,
            flag = flag,
            urls = urls,
            subt = map.str("subt", "subtitle", "sub"),
            danmaku = map.str("danmaku", "danmu", "danmakuUrl"),
        )
    }

    /**
     * 单条影片 → Movie；baseUrl 用于把相对封面补成绝对地址。
     *
     * @param requireName 名称是否作为**硬性判据**（默认 true：无名条目整条丢弃）。
     *
     * ## 详情路径必须传 false（DJ音乐[js] 实证，见 docs/DJ音乐js源解析播放分析.md B1）
     * drpy 的 `js:` 内联二级（`$js.toString(() => {...})`）在 drpy2 里走 `vod = VOD` 分支，
     * **不做任何字段回填** —— 源作者只写 `VOD.vod_play_from` / `VOD.vod_play_url` 时
     * （drpy 社区常见写法），产物里就没有 `vod_name`。
     * 早前这里要求 name 非空，于是条目被整条 return null，详情页直接报
     * 「未返回详情数据」，连 DetailFallback 的兜底都轮不到执行。
     *
     * 名称降级为可选后，由 [tv.own.owntv.mobile.tvbox.repository.DetailFallback.merge] 用列表快照补齐，
     * 与 TVBoxOS 的行为对齐（它的详情页标题用的是列表点进来时带的 VodInfo）。
     *
     * **只对详情路径放宽**：列表页若放宽，理论上会出现无名空卡片
     * （生态内实测未出现），保留 requireName 默认值 true 把影响面锁死在详情。
     *
     * @param fallbackId 条目自身没有 id 时的兜底 id（**详情路径必传**）。
     *
     * ## 详情缺 id 是常态，不是脏数据（2026-10-19 真机实证）
     * 直播类站源（虎牙/斗鱼/哔哩，`csp_LiveAi*Guard`）的 `detailContent` 返回：
     * ```json
     * {"list":[{"vod_name":"海南团播…","vod_pic":"…","vod_remarks":"huya",
     *           "vod_content":"https://www.huya.com/991108",
     *           "vod_play_from":"播放地址_阿里_flv$$$播放地址_腾讯_flv",
     *           "vod_play_url":"蓝光4M$<密文>"}]}
     * ```
     * **没有 `vod_id`** —— 它们把 id 放在列表侧（进入详情时由宿主传入），详情只负责给线路。
     * 早前把 id 当硬性判据，于是「list 有 1 条但 listSize=0」→ 详情页直接报
     * 「未返回详情数据」。TVBoxOS 不做条目级校验：详情展示/播放用的是列表页带的
     * `currentVodId`，详情返回体只贡献字段。
     *
     * 故这里在 id 缺失时用调用方传入的请求 id 兜底 —— 语义与 TVBoxOS 一致，
     * 且保证 `Movie.id` 永不为空（历史/收藏/播放全靠它做 key）。
     */
    fun toMovie(
        raw: Any?,
        baseUrl: String? = null,
        requireName: Boolean = true,
        fallbackId: String? = null,
    ): Movie? {
        val map = asMap(raw) ?: return null
        val id = map.str("vod_id", "id", "vodId")?.takeIf { it.isNotBlank() }
            ?: fallbackId?.takeIf { it.isNotBlank() }
            ?: return null
        val rawName = map.str("vod_name", "name", "vodName")
        if (requireName && rawName.isNullOrBlank()) return null
        val name = rawName.orEmpty()
        return Movie(
            id = id,
            name = name,
            // slide 是横图，作为封面兜底；都没有则留空由 UI 显占位
            pic = map.str("vod_pic", "pic", "vod_pic_slide")
                ?.let { TextUtil.absoluteUrl(it, baseUrl) }
                ?.takeIf { it.isNotBlank() },
            remark = map.str("vod_remarks", "remarks", "vod_remark"),
            year = map.str("vod_year", "year"),
            area = map.str("vod_area", "area"),
            typeName = map.str("type_name", "vod_class", "typeName"),
            content = StripHtml.strip(map.str("vod_content", "content", "vod_descr")),
            actor = map.str("vod_actor", "actor"),
            director = map.str("vod_director", "director"),
            playFrom = map.str("vod_play_from", "playFrom"),
            playUrl = PlayUrlCodec.sanitize(map.str("vod_play_url", "playUrl")),
        )
    }

    // ---------------- 类型无关的取值工具 ----------------

    /**
     * 把任意对象转成 Map。
     * 覆盖四种形态：Java Bean（Result/Movie/Play）、Gson JsonObject、Map、JSON 字符串。
     *
     * ## Bean 分支是这里最关键的一段
     * parent-first 委派下，jar 里 `new Result()` 创建的就是宿主桩类（字段全是 `Any?`）。
     * 桩字段是 `@JvmField` 修饰的**公有字段**，无 getter，所以只能按字段反射；
     * 同时兜一层 getter（部分 jar 用的是自己私有的 bean，字段非公有）。
     */
    fun asMap(raw: Any?): Map<*, *>? = when (raw) {
        null -> null
        is Map<*, *> -> raw
        is String -> if (raw.isBlank()) null else parseJsonToMap(raw)
        is com.google.gson.JsonObject -> gsonElementToMap(raw)
        is com.google.gson.JsonArray -> gsonElementToMap(raw)
        else -> beanToMap(raw)
    }

    /** Java Bean → Map：公有字段 + 零参 getter 双通道 */
    private fun beanToMap(bean: Any): Map<*, *>? {
        val cls: Class<*> = bean.javaClass
        // 明显不是 bean 的类型直接放弃，避免对 String/Number 走反射
        if (cls.isPrimitive || Number::class.java.isAssignableFrom(cls) ||
            cls == Any::class.java || cls.name.startsWith("java.")
        ) return null

        val out = LinkedHashMap<String, Any?>()

        // 1) 公有字段（含父类），覆盖 @JvmField 的桩 bean
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (f in runCatching { c!!.declaredFields }.getOrDefault(emptyArray())) {
                if (!java.lang.reflect.Modifier.isPublic(f.modifiers)) continue
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                val v = runCatching {
                    f.isAccessible = true
                    f.get(bean)
                }.getOrNull()
                if (v != null) out.putIfAbsent(f.name, v)
            }
            c = c.superclass
        }

        // 2) 零参 getter 兜底（jar 私有 bean 常见字段私有 + getter 公有）
        for (m in runCatching { cls.methods }.getOrDefault(emptyArray())) {
            val name = m.name
            if (!name.startsWith("get") || name.length <= 3) continue
            // getClass 是 Object 的元信息，不是 bean 属性（JavaBean 规范也明确排除它）。
            // 不排除会产出假的 "class" 键（值是 java.lang.Class），
            // 与生态分类数组的 "class" 键撞名，分类解析会被它带偏。
            if (name == "getClass") continue
            if (m.parameterCount != 0 || m.returnType == Void.TYPE) continue
            val key = decapitalize(name.substring(3))
            if (out.containsKey(key)) continue
            val v = runCatching {
                m.isAccessible = true
                m.invoke(bean)
            }.getOrNull()
            if (v != null) out[key] = v
        }

        return out.takeIf { it.isNotEmpty() }
    }

    private fun decapitalize(s: String): String =
        if (s.isNotEmpty() && s[0].isUpperCase()) s.replaceFirstChar { it.lowercaseChar() } else s

    /** 取列表：兼容 ArrayList / JSONArray / 单对象 */
    private fun Any?.asList(): List<Any?> = when (this) {
        null -> emptyList()
        is List<*> -> this
        is Array<*> -> toList()
        else -> {
            // Gson JsonArray 实现了 Iterable
            if (this is Iterable<*>) this.toList() else listOf(this)
        }
    }

    private fun Map<*, *>.str(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k] ?: continue
            val s = v.asStringOrNull() ?: continue
            if (s.isNotBlank()) return s
        }
        return null
    }

    private fun Map<*, *>.int(vararg keys: String): Int? {
        for (k in keys) {
            val v = this[k] ?: continue
            when (v) {
                is Number -> return v.toInt()
                is String -> v.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    /** 任意类型 → 字符串（数值/布尔/Gson 元素都能取） */
    private fun Any?.asStringOrNull(): String? = when (this) {
        null -> null
        is String -> this
        // 整数值的 Double 要去掉 ".0"：JSON 里的 123.0 被 Gson 读成 Double 123.0，
        // 直接 toString 会得到 "123.0"，导致 vod_id 与详情页 id 对不上
        is Double -> if (this == Math.floor(this) && !isInfinite()) toLong().toString() else toString()
        is Float -> if (this.toDouble() == Math.floor(this.toDouble()) && !isInfinite()) {
                toLong().toString()
            } else {
                toString()
            }
        is Number -> this.toString()
        is Boolean -> this.toString()
        is Char -> this.toString()
        // Gson JsonPrimitive：优先取其字符串值
        is com.google.gson.JsonPrimitive -> runCatching { asString }.getOrNull()
        // Gson JsonNull / JsonObject / JsonArray 交由 map 分支处理，这里不取字符串
        else -> null
    }

    private fun parseJsonToMap(text: String): Map<*, *>? = runCatching {
        val obj = com.google.gson.JsonParser.parseString(text) ?: return null
        gsonElementToMap(obj)
    }.getOrNull()

    /**
     * Gson JsonObject → Map<String, Any?>，值统一转 String/Number/Boolean/null
     *
     * ## 数组分支为什么不能无脑递归 gsonElementToMap
     * 影视源返回里大量出现字符串数组：`"vod_tag":["动作","悬疑"]`、
     * `filterDef` 里的筛选项数组等。若对数组元素一律按 JsonObject 解析，
     * 遇到字符串元素会走 `!isJsonObject → emptyMap()` 分支 —— 元素语义被抹成空 Map，
     * 下游 `str()` 取不到值，整块数据静默丢失。
     *
     * 故数组元素按其真实形态分派：对象→Map、数组→递归、基本类型→取标量、
     * null→null。**这一点对 jar 源尤其关键**：把元素统一塞成字符串数组里混 Map，
     *   jar 侧拿到与预期不符的结构，可能在序列化回去时对 JsonNull 调 getAsString() 而 NPE
     *   （真机实测：`Attempt to invoke virtual method 'java.lang.String
     *    com.google.gson.JsonElement.getAsString()' on a null object reference`）。
     */
    private fun gsonElementToMap(element: com.google.gson.JsonElement): Map<String, Any?> {
        if (!element.isJsonObject) return emptyMap()
        val obj = element.asJsonObject
        val out = LinkedHashMap<String, Any?>(obj.size())
        for (key in obj.keySet()) {
            val v = obj.get(key)
            out[key] = when {
                v == null || v is com.google.gson.JsonNull -> null
                v.isJsonPrimitive -> {
                    val p = v.asJsonPrimitive
                    when {
                        p.isBoolean -> p.asBoolean
                        p.isNumber -> p.asNumber.let {
                            // 整数优先给 Long/Int，避免下游拿到 "1.0"
                            val d = it.toDouble()
                            if (d == Math.floor(d) && !d.isInfinite()) it.toLong() else it
                        }
                        else -> p.asString
                    }
                }
                v.isJsonArray -> v.asJsonArray.map { arrayElement(it) }
                v.isJsonObject -> gsonElementToMap(v.asJsonObject)
                else -> v.toString()
            }
        }
        return out
    }

    /** 数组元素按真实形态归一：对象→Map、数组→嵌套 list、标量→其值、null→null */
    private fun arrayElement(v: com.google.gson.JsonElement): Any? = when {
        v == null || v is com.google.gson.JsonNull -> null
        v.isJsonObject -> gsonElementToMap(v.asJsonObject)
        v.isJsonArray -> v.asJsonArray.map { arrayElement(it) }
        v.isJsonPrimitive -> {
            val p = v.asJsonPrimitive
            when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> p.asNumber.let {
                    val d = it.toDouble()
                    if (d == Math.floor(d) && !d.isInfinite()) it.toLong() else it
                }
                else -> p.asString
            }
        }
        // 嵌套 Map / List **不能** toString —— 那会把 `{"User-Agent":{...}}`
        // 整段变成一个字符串，再被当成 header 的 key 发给服务器（实测 HTTP 400）。
        // 请求头的值必须是标量，复合结构一律丢弃。
        else -> null
    }

    /** header 既可能是 Map，也可能是 "k:v\nk:v" 换行分隔串 */
    /**
     * 仅供单测：直接调用 header 解析。
     *
     * header 解析要单测是因为它处理的是"源返回的任意形态"，
     * 而真实 bug（嵌套 header 被 toString 成 header 名 → HTTP 400）
     * 只有在纯函数层面才能稳定复现。
     */
    internal fun parseHeaderForTest(raw: Any): JsonObject? = toHeaderJson(raw)

    private fun toHeaderJson(raw: Any): JsonObject? = when (raw) {
        is Map<*, *> -> raw.entries
            .mapNotNull { (k, v) ->
                val ks = k.asStringOrNull()
                val vs = v.asStringOrNull()
                when {
                    // 正常形态：k/v 都是标量
                    ks != null && vs != null ->
                        ks.takeIf { it.isNotBlank() }?.let { it to vs }
                    // **实测形态**：整个 header 被套了一层，如
                    // `{"User-Agent": {"User-Agent": "Mozilla/5.0 (iPhone...)"}}`。
                    // 外层 key 是对象，直接 toString 会把整段 JSON 变成 header 名，
                    // 服务器收到这种畸形头直接回 HTTP 400。
                    // 这里**向下一层取标量**：外层名字通常就是内层的 key 名。
                    ks != null && v is Map<*, *> -> {
                        // 向下一层找第一个标量值
                        var innerName: String? = null
                        var innerValue: String? = null
                        for ((ik, iv) in v) {
                            val iks = ik.asStringOrNull()
                            val ivs = iv.asStringOrNull()
                            if (iks != null && ivs != null) {
                                innerName = iks
                                innerValue = ivs
                                break
                            }
                        }
                        // 外层 key 名优先（它就是 header 名），拿不到才用内层 key 名
                        val name = ks.takeIf { it.isNotBlank() } ?: innerName
                        val value = innerValue
                        if (name != null && value != null) name to value else null
                    }
                    // 再下一层仍是对象：放弃，不产出假 header
                    else -> null
                }
            }
            .toMap()
            .legalHeaders()
            .takeIf { it.isNotEmpty() }
            ?.toJsonObject()

        is String -> toHeaderJsonFromString(raw)

        else -> null
    }

    /**
     * 字符串形态的 header。
     *
     * ## ⚠️ 为什么必须先试 JSON，不能一律按 `:` 切
     * 真机实测（源「热播┃快速」，站 key `Rbys`，type=3 jar）：jar 把 header 以
     * **JSON 字符串**形式返回 —— `"header":"{\"User-Agent\":\"Mozilla/5.0 (iPhone...)\"}"`。
     *
     * 旧实现一律走 `k:v` 切行，于是整段 JSON 被从**第一个冒号**处劈开：
     * ```
     * 输入  {"User-Agent":"Mozilla/5.0 (iPhone; ... Safari/604.1"}
     * key   = {"User-Agent"                 ← 畸形 header 名
     * value = "Mozilla/5.0 (...)Safari/604.1"}   ← 连尾括号一起
     * ```
     * 这个畸形名会被 OkHttp **原样发出**（都是可打印 ASCII，过得了 OkHttp 的
     * 0x21~0x7E 校验），服务端（Tomcat）按 RFC 7230 tchar 拒绝 → **HTTP 400**。
     * curl 对照：同一地址带这个头 400、去掉它 200。
     *
     * 解析不出来时**宁可丢弃**（返回 null）也不退回按冒号切 —— 按冒号切正是畸形头的来源，
     * 少一个请求头最多是防盗链失败，发畸形头是必然 400。
     */
    private fun toHeaderJsonFromString(raw: String): JsonObject? {
        val s = raw.trim()
        if (s.startsWith("{")) {
            val element = runCatching { com.google.gson.JsonParser.parseString(s) }.getOrNull()
                ?.takeIf { it.isJsonObject }
                ?: return null
            return toHeaderJson(gsonElementToMap(element))
        }
        return s.lines()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) return@mapNotNull null
                val k = line.substring(0, idx).trim()
                val v = line.substring(idx + 1).trim()
                if (k.isBlank() || v.isBlank()) null else k to v
            }
            .toMap()
            .legalHeaders()
            .takeIf { it.isNotEmpty() }
            ?.toJsonObject()
    }

    /**
     * 分类筛选条件树：jar 用 `filterDef`（部分用 `filters`）承载，仅 filter=true 时有值。
     *
     * 返回 **type_id → 筛选维度列表**。整棵树解析不出任何可用维度时返回空 map
     * （调用方按"该分类没有筛选"处理，而不是当作错误）。
     *
     * 之所以容错这么重：各家 jar 的筛选树形状差异极大 ——
     * 有的是 `{"1":[{key,name,value:[{n,v}]}]}`，
     * 有的是 `{"1":{"class":{"name":"剧情","value":[...]}}}`（对象而非数组），
     * 还有的直接把值塞成 `{"n":"v"}` 这种扁平 map。三种都得吃下。
     */
    fun toFilters(raw: Any?): Map<String, List<FilterDef>> {
        val map = asMap(raw) ?: return emptyMap()
        val filters = map["filterDef"] ?: map["filters"] ?: return emptyMap()
        val fmap = asMap(filters) ?: return emptyMap()
        if (fmap.isEmpty()) return emptyMap()

        return fmap.mapNotNull { (typeId, defsAny) ->
            val tid = typeId?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val defs = parseFilterDefs(defsAny)
            if (defs.isEmpty()) null else tid to defs
        }.toMap()
    }

    /** 单个分类下的筛选维度列表：数组 / 对象(Map) 两种形状 */
    private fun parseFilterDefs(raw: Any?): List<FilterDef> = when (raw) {
        is Iterable<*> -> raw.mapNotNull { parseFilterDef(it) }
        is Array<*> -> raw.mapNotNull { parseFilterDef(it) }
        is Map<*, *> -> raw.entries.mapNotNull { (k, v) ->
            // 对象形态：key 即筛选参数名，v 是 {name,value} 或 {n:v} 扁平表
            val key = k?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val vm = asMap(v) ?: return@mapNotNull null
            val name = vm.str("name") ?: key
            val values = parseFilterValues(vm["value"] ?: vm["values"] ?: v)
            if (values.isEmpty()) null else FilterDef(key, name, values)
        }
        else -> emptyList()
    }

    private fun parseFilterDef(raw: Any?): FilterDef? {
        val m = asMap(raw) ?: return null
        val key = m.str("key", "k", "name") ?: return null
        val name = m.str("name", "title", "n") ?: key
        val values = parseFilterValues(m["value"] ?: m["values"] ?: m["list"])
        if (values.isEmpty()) return null
        return FilterDef(key, name, values)
    }

    /**
     * 候选项：支持三种形态
     * - `[{n,v}, ...]` 标准对象数组
     * - `["喜剧","动作"]` 裸字符串数组（n 与 v 同名）
     * - `{"喜剧":"comedy"}` 扁平 map（n 是值，v 是键）
     *
     * ## 两个必须小心的坑
     * 1. **不能用 `asMap(item)` 判断"是不是对象"**：`asMap` 对字符串会尝试
     *    按 JSON 解析，失败时返回**空 map 而不是 null**。于是裸字符串
     *    `"喜剧"` 会被当成"一个空对象"，走到取 `n` 的分支取不到值被丢弃，
     *    整个筛选维度因此变空、UI 上整行筛选消失。
     *    这里改成先按实际类型判定，`String` 一律不当对象处理。
     * 2. **`v` 不能用会跳过空串的 `str()`**：`{"n":"全部","v":""}` 是"不限"项，
     *    值本来就是空串，`str` 会跳过它并 fallback 成 `n`，
     *    提交给源时变成 `class=全部` 而不是"不带该参数"，源端直接返回空列表。
     */
    private fun parseFilterValues(raw: Any?): List<FilterValue> {
        when (raw) {
            is Map<*, *> -> {
                return raw.entries.mapNotNull { (k, v) ->
                    val key = k?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val n = v?.toString()?.takeIf { it.isNotBlank() } ?: key
                    FilterValue(n, key)
                }
            }
            is Iterable<*> -> return raw.mapNotNull { toFilterValue(it) }
            is Array<*> -> return raw.mapNotNull { toFilterValue(it) }
            else -> return emptyList()
        }
    }

    private fun toFilterValue(item: Any?): FilterValue? {
        val m: Map<*, *>? = when (item) {
            is Map<*, *> -> item
            is com.google.gson.JsonObject -> asMap(item)
            is String -> null
            else -> asMap(item)?.takeIf { it.isNotEmpty() }
        }
        if (m == null) {
            return item?.toString()?.takeIf { it.isNotBlank() }?.let { FilterValue(it, it) }
        }
        val n = m.str("n", "name", "title") ?: return null
        val v = rawStr(m, "v", "value", "val", "id") ?: n
        return FilterValue(n, v)
    }

    /** 与 str() 相同，但**不跳过空串**（"全部"这类重置项的 v 就是空串） */
    private fun rawStr(m: Map<*, *>, vararg keys: String): String? {
        for (k in keys) {
            val v = m[k] ?: continue
            return v.asStringOrNull()
        }
        return null
    }

    /** 任意值 → JsonElement（筛选树里会出现字符串/数字/数组三种形态） */
    private fun toJsonElement(raw: Any?): JsonElement = when (raw) {
        null -> JsonNull
        is String -> JsonPrimitive(raw)
        is Number -> JsonPrimitive(raw)
        is Boolean -> JsonPrimitive(raw)
        is Map<*, *> -> buildJsonObject {
            raw.forEach { (k, v) ->
                val key = k?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
                put(key, toJsonElement(v))
            }
        }
        is Iterable<*> -> JsonArray(raw.map { toJsonElement(it) })
        is Array<*> -> JsonArray(raw.map { toJsonElement(it) })
        else -> JsonPrimitive(raw.toString())
    }

    /** 便于调用方排查"为什么这个源没数据" */
    fun describe(raw: Any?): String = when (val m = asMap(raw)) {
        null -> "null/不可解析(${raw?.javaClass?.simpleName})"
        else -> {
            val size = (m["list"] as? List<*>)?.size ?: 0
            "keys=${m.keys.take(8).joinToString(",")}, listSize=$size"
        }
    }

    /**
     * 原始返回值的简短预览（诊断用）。
     *
     * 日志里既不能只写 `keys=...`（看不到字段值，猜不出为什么被丢），
     * 也不能整段打印（jar 的 JSON 常带几 KB 的 play_url / 加密串，会把日志刷爆）。
     * 故截断到 [max] 字符，并带上运行时类型 —— "返回 String 还是 Bean" 本身就是关键线索。
     */
    fun preview(raw: Any?, max: Int = 400): String = when (raw) {
        null -> "null"
        is String -> "String(${raw.length}): ${raw.take(max)}"
        else -> {
            val text = runCatching { raw.toString() }.getOrDefault("")
            "${raw.javaClass.name}(${text.length}): ${text.take(max)}"
        }
    }
}
