package tv.own.owntv.mobile.tvbox.play

/**
 * 一个 TVbox 站点+分类的锁/儿童判定身份。
 *
 * TVbox 内容没有 Core `categories` 行，分类锁走 key 字符串
 * （`tvbox:<siteKey>:<typeId|typeName>`），儿童模式走名字面判定 ——
 * 这两个字段在列表→详情→播放三层间必须原样传递，任何一层丢掉
 * 都会让强制校验失去上下文。
 */
data class TvboxCategoryKey(
    val siteKey: String,
    val siteName: String,
    val typeId: String?,
    val typeName: String,
)

/**
 * 解析完成、可交给播放器的最终产物（方案 §3.2）。
 *
 * 只做数据搬运：[url] 是最终直链（可能来自 playerContent 直连、命名解析器或
 * WebView 嗅探），[headers] 是要随播放下发的请求头，[lines] 是详情页的完整
 * 线路/选集（多集时转 PlaylistItem 逐集起播）。
 */
data class ResolvedPlay(
    val url: String,
    val headers: Map<String, String>,
    val userAgent: String?,
    /** 详情页完整线路列表（换线 UI 用；单集源可能为空列表） */
    val lines: List<tv.own.owntv.mobile.tvbox.engine.PlayLine>,
    /** 起播的线路下标与集下标（对齐 [lines]） */
    val lineIndex: Int,
    val episodeIndex: Int,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
    val categoryKey: TvboxCategoryKey,
    val siteKey: String,
    val vodId: String,
)
