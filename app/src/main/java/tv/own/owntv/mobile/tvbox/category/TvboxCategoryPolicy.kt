package tv.own.owntv.mobile.tvbox.category

import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.engine.Movie

/**
 * 儿童模式判定（方案 §5.5.5，纯函数 + 单点复用 [blockedByKids]）。
 *
 * TVbox 内容没有 Core 的 `categoryId`，判定只落在**站点与分类名字面**：
 * 1. 档案非 `isKids` → 全放行；
 * 2. 站点 `ext` 含 `"adult":true` / `"nsfw":true` → 整站隐藏（聚合搜索也不参与）；
 * 3. 分类名命中 Core [AdultCategoryClassifier.isAdult] → 隐藏（中文补充词表已并入 Core，
 *    方案 §4.5.2，两边口径统一）；
 * 4. 列表行的 `Movie.typeName` 命中上述任一 → 该行隐藏（防"分类安全但条目来自成人分类"）；
 * 5. 未命中 → 放行（与 Core 一致，不 fail-closed）。
 *
 * 两边都不按标题判定，避免「Sex and the City」类误杀。
 *
 * 依赖只有 `kidsStrict` 一个布尔读数（构造传入读数函数），档案由调用方查好传入 ——
 * 整个类无 Android 依赖，判定矩阵可直接 JVM 单测。
 */
class TvboxCategoryPolicy(private val kidsStrict: () -> Boolean) {

    /** 站点 ext 的结构化 adult/nsfw 标记（`{"adult":true}` / `{"nsfw":1}` 等写法）。 */
    private fun extMarkedAdult(site: SiteConfig): Boolean = siteMarkedAdult(site)

    /** 分类名是否命中任何隐藏规则（含站点级 ext 标记）。 */
    private fun hiddenName(site: SiteConfig, typeName: String?): Boolean =
        extMarkedAdult(site) || AdultCategoryClassifier.isAdult(typeName)

    /**
     * 详情/播放层的单点判定：这个站点+分类名在当前档案下是否整体不可见。
     * 列表层三个函数都归到这里，保证口径唯一。
     *
     * 严格模式（`kidsStrict`，方案 §5.5.5"词表漏判兜底"）在普通判定之上收窄两处 fail-open：
     * - 站点名本身也过 Core 词表（"午夜资源"这类站点名，普通模式不查）；
     * - 分类名缺失（typeName 空）视为不可判定 → 隐藏，而不是放行。
     */
    fun blockedByKids(profile: ProfileEntity?, site: SiteConfig, typeName: String?): Boolean {
        if (profile?.isKids != true) return false
        if (kidsStrict()) {
            val siteHit = extMarkedAdult(site) || AdultCategoryClassifier.isAdult(site.name)
            return siteHit ||
                AdultCategoryClassifier.isAdult(typeName) ||
                typeName.isNullOrBlank()
        }
        return hiddenName(site, typeName)
    }

    /** 儿童档案下可见的站点（聚合搜索与站点列表共用）。 */
    fun visibleSites(profile: ProfileEntity?, sites: List<SiteConfig>): List<SiteConfig> {
        if (profile?.isKids != true) return sites
        return sites.filter { !blockedByKids(profile, it, null) }
    }

    /** 列表行过滤：`Movie.typeName` 命中即隐藏（`typeName` 为空按站点级标记处理）。 */
    fun movieAllowed(profile: ProfileEntity?, site: SiteConfig, movie: Movie): Boolean =
        !blockedByKids(profile, site, movie.typeName)
}

/**
 * 站点 ext 的结构化 adult/nsfw 标记，供索引映射写入 `extJson.adultSite` 与列表/播放判定共用。
 *
 * 站点级标记没有分类名可查，条目级检查只能把这个标记落进索引（方案 §4.5.2）。
 */
internal fun siteMarkedAdult(site: SiteConfig): Boolean {
    val ext = site.ext ?: return false
    val e = ext.lowercase()
    return Regex("\"(adult|nsfw)\"\\s*:\\s*(true|1|\"1\"|\"true\")").containsMatchIn(e)
}
