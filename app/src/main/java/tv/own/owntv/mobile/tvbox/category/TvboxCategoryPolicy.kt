package tv.own.owntv.mobile.tvbox.category

import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.data.prefs.TvboxPrefs
import tv.own.owntv.mobile.tvbox.engine.ClassInfo
import tv.own.owntv.mobile.tvbox.engine.Movie

/**
 * 儿童模式判定（方案 §5.5.5，纯函数 + 单点复用 [blockedByKids]）。
 *
 * TVbox 内容没有 Core 的 `categoryId`，判定只落在**站点与分类名字面**：
 * 1. 档案非 `isKids` → 全放行；
 * 2. 站点 `ext` 含 `"adult":true` / `"nsfw":true` → 整站隐藏（聚合搜索也不参与）；
 * 3. 分类名命中 Core [AdultCategoryClassifier.isAdult] → 隐藏；
 * 4. 分类名命中 TVbox 中文词表 → 隐藏（Core 词表中文只覆盖"成人/成年人"，这是补充）;
 * 5. 列表行的 `Movie.typeName` 命中上述任一 → 该行隐藏（防"分类安全但条目来自成人分类"）；
 * 6. 未命中 → 放行（与 Core 一致，不 fail-closed）。
 *
 * 两边都不按标题判定，避免「Sex and the City」类误杀。
 */
class TvboxCategoryPolicy(
    private val profileDao: ProfileDao,
    private val prefs: TvboxPrefs,
) {

    /** TVbox 中文成人词表（方案 §5.5.5 拍板清单，子串命中即隐藏）。 */
    private val zhAdultWords = listOf(
        "伦理片", "成人", "福利", "午夜", "写真", "禁片", "里番", "无码",
        "情色", "三级", "激情", "偷拍", "探花", "18禁", "限制级",
    )

    private fun zhAdultHit(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.trim()
        return zhAdultWords.any { n.contains(it, ignoreCase = true) }
    }

    /** 站点 ext 的结构化 adult/nsfw 标记（`{"adult":true}` / `{"nsfw":1}` 等写法）。 */
    private fun extMarkedAdult(site: SiteConfig): Boolean {
        val ext = site.ext ?: return false
        val e = ext.lowercase()
        return Regex("\"(adult|nsfw)\"\\s*:\\s*(true|1|\"1\"|\"true\")").containsMatchIn(e)
    }

    /** 分类名是否命中任何隐藏规则（含站点级 ext 标记）。 */
    private fun hiddenName(site: SiteConfig, typeName: String?): Boolean =
        extMarkedAdult(site) ||
            AdultCategoryClassifier.isAdult(typeName) ||
            zhAdultHit(typeName)

    /**
     * 详情/播放层的单点判定：这个站点+分类名在当前档案下是否整体不可见。
     * 列表层三个函数都归到这里，保证口径唯一。
     */
    suspend fun blockedByKids(profile: ProfileEntity?, site: SiteConfig, typeName: String?): Boolean {
        if (profile?.isKids != true) return false
        if (prefs.kidsStrict) return !siteWhitelisted(site)
        return hiddenName(site, typeName)
    }

    /** 儿童档案下可见的站点（聚合搜索与站点列表共用）。 */
    suspend fun visibleSites(profile: ProfileEntity?, sites: List<SiteConfig>): List<SiteConfig> {
        if (profile?.isKids != true) return sites
        return sites.filter { !blockedByKids(profile, it, null) }
    }

    /** 儿童档案下可见的分类行。 */
    suspend fun visibleCategories(
        profile: ProfileEntity?,
        site: SiteConfig,
        classes: List<ClassInfo>,
    ): List<ClassInfo> {
        if (profile?.isKids != true) return classes
        return classes.filter { !blockedByKids(profile, site, it.typeName) }
    }

    /** 列表行过滤：`Movie.typeName` 命中即隐藏（`typeName` 为空按站点级标记处理）。 */
    suspend fun movieAllowed(profile: ProfileEntity?, site: SiteConfig, movie: Movie): Boolean =
        !blockedByKids(profile, site, movie.typeName)

    /** 严格模式（`kidsStrict`）下唯一放行的形态：无结构化 adult 标记的站点。 */
    private fun siteWhitelisted(site: SiteConfig): Boolean = !extMarkedAdult(site)

    /** 供 VM 组装一次档案后反复使用的便捷重载。 */
    suspend fun currentProfile(profileId: Long): ProfileEntity? = profileDao.getById(profileId)
}
