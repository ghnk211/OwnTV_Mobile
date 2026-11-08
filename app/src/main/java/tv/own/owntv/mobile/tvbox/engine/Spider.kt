package tv.own.owntv.mobile.tvbox.engine

/**
 * 统一五接口抽象（方案 5.1），与 TVBox 协议语义对齐。
 *
 * 实现约定：
 * - 全部为 suspend；Jar 引擎的反射阻塞调用在实现内部用 withContext(Dispatchers.IO) 桥接
 * - 允许返回 null 表示"该源不支持此接口"（不视为错误）
 * - 抛异常表示该源本次失败，由 SpiderPool 统一计入健康统计
 */
interface Spider {

    /** 站点 key */
    val siteKey: String

    /** 站点名（UI 展示） */
    val displayName: String

    /** 初始化；ext 为站点扩展字段（懒加载后传入） */
    suspend fun init(ext: String?)

    /** 首页分类 + 推荐列表 */
    suspend fun homeContent(filter: Boolean): SpiderResult?

    /**
     * 首页推荐/最近更新（兜底）。
     *
     * 很多源把推荐内容放在**独立接口**而不是 `homeContent` 里，
     * 对齐 TVBox 的 `homeVideoContent()`。当 `homeContent` 返回空分类时，
     * 用它填充首页，避免用户看到空白页。
     *
     * 默认返回 null（该源没有这个接口），由调用方决定降级策略。
     */
    suspend fun homeVideoContent(): SpiderResult? = null

    /** 分类列表（含筛选） */
    suspend fun categoryContent(
        tid: String,
        pg: String,
        filter: Boolean,
        extend: String?,
    ): SpiderResult?

    /** 详情 + 播放线路/选集 */
    suspend fun detailContent(ids: String): SpiderResult?

    /** 搜索；quick=快速模式，pg=翻页 */
    suspend fun searchContent(key: String, quick: Boolean, pg: String?): SpiderResult?

    /** 取播放地址 */
    suspend fun playerContent(flag: String, id: String, vipFlags: String?): PlayResult?

    /** 释放实例（同步） */
    fun destroy()

    /** 是否已初始化 */
    val initialized: Boolean
}

/** 引擎标识 */
enum class EngineKind(val label: String) {
    CMS("CMS接口"),
    SPIDER_JAR("Jar引擎"),
    SPIDER_JS("JS引擎"),
    SPIDER_PY("Python引擎"),
    SNIFFER("嗅探"),
    NONE("未实现"),
}

/** 引擎统一门面：屏蔽具体引擎差异，供 SourceRepository 调用 */
interface Engine {
    val kind: EngineKind

    /** 该引擎能否处理这个站点 */
    fun supports(site: tv.own.owntv.mobile.tvbox.config.model.SiteConfig): Boolean

    /** 获取（或创建）该站点的 Spider 实例 */
    suspend fun obtain(site: tv.own.owntv.mobile.tvbox.config.model.SiteConfig): Spider

    /** 站点是否正在冷却 */
    fun isCooling(siteKey: String): Boolean

    /**
     * 站点实例是否已完成 init（优化方案 A4-2：冷源搜索降级判据）。
     *
     * 默认 true = 「不跳过」——CMS/Jar 的 init 很便宜，无需降级；
     * JsEngine 覆写为查 context 就绪状态（未 init 的 JS 源冷启动 9~11s，搜索轮不该硬等）。
     */
    fun isInitialized(site: tv.own.owntv.mobile.tvbox.config.model.SiteConfig): Boolean = true

    /** 配置切换时清空全部实例 */
    fun invalidateAll()
}
