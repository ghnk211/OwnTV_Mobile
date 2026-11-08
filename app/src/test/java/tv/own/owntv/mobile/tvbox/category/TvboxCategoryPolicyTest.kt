package tv.own.owntv.mobile.tvbox.category

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.engine.ClassInfo
import tv.own.owntv.mobile.tvbox.engine.Movie

/**
 * 儿童模式判定矩阵（方案 §5.5.5）：档案口径、站点 ext 标记、Core 分类器、中文词表、
 * 行级过滤、严格模式。纯 JVM —— 判定层回归（D2/D3 教训）的一部分。
 */
class TvboxCategoryPolicyTest {

    private val policy = TvboxCategoryPolicy(kidsStrict = { false })
    private val strict = TvboxCategoryPolicy(kidsStrict = { true })

    private val normal = ProfileEntity(id = 1, name = "Tester", avatarColor = 0, isKids = false)
    private val kids = ProfileEntity(id = 2, name = "Kid", avatarColor = 0, isKids = true)

    private fun site(ext: String? = null, key: String = "s1", name: String = "站一") =
        SiteConfig(key = key, name = name, type = 0, api = "http://x/api", ext = ext)

    private fun movie(name: String, typeName: String?) =
        Movie(id = "1", name = name, typeName = typeName)

    // ---- 非儿童档案：全放行 ----

    @Test
    fun `非儿童档案全放行`() {
        val adultSite = site(ext = "{\"adult\":true}")
        assertTrue(policy.visibleSites(normal, listOf(adultSite)).size == 1)
        assertTrue(policy.blockedByKids(normal, adultSite, "伦理片").not())
        assertTrue(policy.movieAllowed(normal, adultSite, movie("x", "伦理片")))
    }

    // ---- 站点级 ext 标记 ----

    @Test
    fun `儿童档案下 ext 声明 adult 的站点整站隐藏`() {
        val s = site(ext = "{\"adult\":true}")
        assertTrue(policy.visibleSites(kids, listOf(s, site(key = "s2"))).map { it.key } == listOf("s2"))
        assertTrue(policy.blockedByKids(kids, s, "电影"))
    }

    @Test
    fun `儿童档案下 ext 声明 nsfw 的站点整站隐藏`() {
        val s = site(ext = "{\"nsfw\":1}")
        assertTrue(policy.blockedByKids(kids, s, "电影"))
    }

    // ---- 分类名判定 ----

    @Test
    fun `儿童档案下 Core 分类器命中成人隐藏`() {
        assertTrue(policy.blockedByKids(kids, site(), "成人"))
    }

    @Test
    fun `儿童档案下中文词表命中隐藏`() {
        for (name in listOf("伦理片", "三级", "写真", "里番", "18禁", "限制级")) {
            assertTrue("词表应命中:$name", policy.blockedByKids(kids, site(), name))
        }
    }

    @Test
    fun `儿童档案下普通分类放行 - 不 fail-closed`() {
        assertFalse(policy.blockedByKids(kids, site(), "电影"))
        assertFalse(policy.blockedByKids(kids, site(), null))
    }

    // ---- 列表行过滤 ----

    @Test
    fun `儿童档案下 typeName 命中的行隐藏`() {
        val classes = listOf(ClassInfo("1", "电影"), ClassInfo("6", "伦理片"))
        assertEquals(listOf("电影"), policy.visibleCategories(kids, site(), classes).map { it.typeName })
    }

    @Test
    fun `儿童档案下 movie typeName 命中即隐藏`() {
        assertFalse(policy.movieAllowed(kids, site(), movie("伦理片测试01", "伦理片")))
        assertTrue(policy.movieAllowed(kids, site(), movie("电影测试01", "电影")))
    }

    // ---- 严格模式（词表漏判兜底：站点名过词表 + 未知分类 fail-closed） ----

    @Test
    fun `严格模式下分类名缺失视为不可判定而隐藏`() {
        assertTrue(strict.blockedByKids(kids, site(), null))
        assertTrue(strict.blockedByKids(kids, site(), ""))
    }

    @Test
    fun `严格模式下站点名也过词表`() {
        assertTrue(strict.blockedByKids(kids, site(key = "s9", name = "午夜资源"), "电影"))
        assertFalse(strict.blockedByKids(kids, site(), "电影"))
    }

    @Test
    fun `严格模式下词表与 ext 判定照常生效`() {
        assertTrue(strict.blockedByKids(kids, site(), "伦理片"))
        val s = site(ext = "{\"nsfw\":true}")
        assertTrue(strict.blockedByKids(kids, s, "电影"))
        // 非儿童档案不受严格模式影响
        assertFalse(strict.blockedByKids(normal, site(), "伦理片"))
    }
}
