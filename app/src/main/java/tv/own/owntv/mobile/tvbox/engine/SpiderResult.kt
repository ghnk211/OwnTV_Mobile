package tv.own.owntv.mobile.tvbox.engine

import kotlinx.serialization.json.JsonObject

/**
 * 分类筛选的一个维度（对齐 TVBoxOS `MovieSort.SortFilter`）。
 *
 * CMS 的 `filters` 与 jar 的 `filterDef` 都是这个形状：
 * ```json
 * { "key": "class", "name": "剧情", "value": [ {"n":"全部","v":""}, {"n":"喜剧","v":"喜剧"} ] }
 * ```
 * `key` 是提交给源的参数名，`v` 是参数值，`n` 只用于展示。
 */
data class FilterDef(
    val key: String,
    val name: String,
    val values: List<FilterValue> = emptyList(),
) {
    /** 用户可选项为空时不值得渲染成一行筛选 */
    val usable: Boolean get() = key.isNotBlank() && values.isNotEmpty()
}

/** 筛选候选项：`n` 展示名，`v` 提交值 */
data class FilterValue(
    val n: String,
    val v: String,
)

/**
 * 归一化数据模型（方案 5.2）。
 * 所有引擎（Jar / JS / CMS / 嗅探）的返回最终收敛到这几个类型。
 */
data class SpiderResult(
    /** 分类：type_id / type_name */
    val classes: List<ClassInfo> = emptyList(),
    /**
     * 分类筛选条件树：**key = type_id**，value = 该分类下的筛选维度列表。
     *
     * 仅 `homeContent(filter=true)` 时有值（对齐 TVBoxOS `AbsSortJson.filters`）。
     * 键是分类 id 而不是站点 key —— 同一个源里不同分类的筛选项往往完全不同
     * （电影筛"年代"，剧集筛"集数状态"）。
     */
    val filters: Map<String, List<FilterDef>> = emptyMap(),
    /** 影片列表 */
    val list: List<Movie> = emptyList(),
    val page: Int = 1,
    val pagecount: Int = 1,
    val total: Int = 0,
    /** 部分源（CMS）首页直接带推荐列表，classes 为空时用它 */
    val limit: Int = 0,
) {
    val hasMore: Boolean get() = page < pagecount
    val isEmpty: Boolean get() = list.isEmpty()
}

data class ClassInfo(
    val typeId: String,
    val typeName: String,
)

data class Movie(
    /** vod_id 字符串化，兼容数字 id */
    val id: String,
    val name: String,
    val pic: String? = null,
    /** 角标，如"更新到第12集" */
    val remark: String? = null,
    val year: String? = null,
    val area: String? = null,
    val typeName: String? = null,
    val content: String? = null,
    /** 线路名，多线路以 $$$ 分隔 */
    val playFrom: String? = null,
    /** "第1集$url1#第2集$url2$$$线路2..." */
    val playUrl: String? = null,
    val actor: String? = null,
    val director: String? = null,
    /** 源站附加的 vod_* 扩展字段原样保留，供详情页兜底展示 */
    val extra: Map<String, String> = emptyMap(),
    /**
     * 内容实际来自哪个站点的 key。
     *
     * 首页兜底时（`SourceRepository.homeFallback`）内容可能是从别的源借来的，
     * 此时本条目的 id/详情只在**借主站点**有效。点进详情必须用它，
     * 否则会用当前（空首页的）站点去查详情，必然 404。
     */
    val donorSiteKey: String? = null,
) {
    val hasPlayLines: Boolean get() = !playUrl.isNullOrBlank()
}

data class PlayResult(
    /** 1 = 需要 webview 嗅探 / parse 解析 */
    val parse: Int = 0,
    /** 播放前缀（parse 接口或直链前缀） */
    val playUrl: String? = null,
    /** 真正的页面或直链地址 */
    val url: String = "",
    /** 播放请求头，透传给播放器 */
    val header: JsonObject? = null,
    val flag: String? = null,
    val urls: List<String> = emptyList(),
    /**
     * 字幕地址（源返回的 `subt` / `subtitle` 字段）。
     * 可能是 http(s) 地址，也可能是字幕文件内容本身（少数源直接塞文本）。
     */
    val subt: String? = null,
    /** 弹幕地址（源返回的 `danmaku` / `danmu` 字段） */
    val danmaku: String? = null,
) {
    val needSniff: Boolean get() = parse == 1
    val isDirect: Boolean get() = parse == 0
}
