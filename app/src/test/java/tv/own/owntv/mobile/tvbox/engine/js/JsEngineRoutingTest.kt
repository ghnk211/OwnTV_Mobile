package tv.own.owntv.mobile.tvbox.engine.js

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig

/**
 * 引擎路由判据：
 * - `api=x.js?token=1`（type=3）→ JS
 * - `type=0` 但 `api=x.js` → JS（不能被 CMS 抢走）
 * - api 含 `drpy` 的普通 URL → **不**误判为 JS
 * - native 不可用 → 整体不支持（降级，不崩）
 */
class JsEngineRoutingTest {

    private fun site(type: Int, api: String) = SiteConfig(key = "k", name = "n", type = type, api = api)

    @Test
    fun `js api with query string routes to js engine`() {
        assertTrue(JsEngine.supportsSite(site(3, "https://a.com/lib/drpy2.min.js?token=1")))
    }

    @Test
    fun `js api with wrong type still routes to js engine`() {
        assertTrue(JsEngine.supportsSite(site(0, "https://a.com/lib/drpy2.min.js")))
        assertTrue(JsEngine.supportsSite(site(1, "https://a.com/lib/drpy2.min.js")))
    }

    @Test
    fun `url merely containing drpy is not a js site`() {
        assertFalse(JsEngine.supportsSite(site(0, "https://drpy.example.com/api.php")))
        assertFalse(JsEngine.supportsSite(site(1, "https://api.example.com/drpy/list")))
    }

    @Test
    fun `csp class name stays with jar engine`() {
        assertFalse(JsEngine.supportsSite(site(3, "csp_Jianpian")))
    }

    @Test
    fun `apiIsJs strips query and fragment only`() {
        assertTrue(site(3, "https://a.com/x.js?token=1#frag").apiIsJs)
        assertTrue(site(3, "HTTPS://A.COM/X.JS").apiIsJs)
        assertFalse(site(3, "https://a.com/x.jsa").apiIsJs)
        assertFalse(site(3, "https://a.com/x.py").apiIsJs)
    }

    @Test
    fun `native unavailable degrades every js site`() {
        val js = site(3, "https://a.com/lib/drpy2.min.js")
        assertTrue(JsEngine.supportsWith(nativeAvailable = true, js))
        assertFalse(JsEngine.supportsWith(nativeAvailable = false, js))
        // 非 JS 源不受 native 状态影响
        assertFalse(JsEngine.supportsWith(nativeAvailable = true, site(3, "csp_Jianpian")))
    }
}