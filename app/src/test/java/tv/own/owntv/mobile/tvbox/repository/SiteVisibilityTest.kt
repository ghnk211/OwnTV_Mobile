package tv.own.owntv.mobile.tvbox.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.engine.js.JsEngine

/**
 * 可见性口径回归：`SiteConfig.isSupported` 只是配置形态，
 * 真正可播放还必须"有引擎"；JS 源在 QuickJS native 不可用时三处（列表 / 首页绑定 /
 * 引擎路由）必须同时剔除，不能"能选但必失败"。
 */
class SiteVisibilityTest {

    private val jsSite = SiteConfig(
        key = "js",
        name = "JS源",
        type = 3,
        api = "https://a.com/lib/drpy2.min.js",
    )

    @Test
    fun `config-shape predicate alone reports js sites as supported`() {
        assertTrue(jsSite.isSupported)
    }

    @Test
    fun `engine predicate gates visibility on native availability`() {
        assertTrue(JsEngine.supportsWith(nativeAvailable = true, jsSite))
        assertFalse(JsEngine.supportsWith(nativeAvailable = false, jsSite))
    }

    @Test
    fun `non js sites are unaffected by the js engine`() {
        val jar = SiteConfig(key = "jar", name = "Jar源", type = 3, api = "csp_Jianpian")
        assertTrue(jar.isSupported)
        assertFalse(JsEngine.supportsWith(nativeAvailable = true, jar))
    }
}