package tv.own.owntv.mobile.tvbox.engine.py

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig

/**
 * Py 引擎路由判据（docs/TVbox-PY源迁移方案.md §6）：
 * - `api=x.py`（type=3）→ Python 引擎，且不再灰显
 * - `.py?token=1` / `.py#frag` 这类带查询串/片段的同样判为 py（与 `isPySite` 逐字一致）
 * - JS / Jar 形态不被 Py 引擎抢走
 */
class PyEngineRoutingTest {

    private fun site(type: Int, api: String) =
        SiteConfig(key = "k", name = "n", type = type, api = api)

    @Test
    fun `py api routes to py engine`() {
        assertTrue(PyEngine.supportsSite(site(3, "https://a.com/ss/北京.py")))
        assertTrue(PyEngine.supportsSite(site(3, "./js/ss/北京.py")))
    }

    @Test
    fun `py api with query or fragment still routes to py engine`() {
        assertTrue(PyEngine.supportsSite(site(3, "https://a.com/x.py?token=1")))
        assertTrue(PyEngine.supportsSite(site(3, "https://a.com/x.py#frag")))
        assertTrue(site(3, "https://a.com/x.py?token=1").isPySite)
    }

    @Test
    fun `py site is supported and has no unsupported reason`() {
        val s = site(3, "https://a.com/py/北京.py")
        assertTrue(s.isPySite)
        assertTrue(s.isSupported)
        assertNull(s.unsupportedReason)
    }

    @Test
    fun `js and jar forms stay out of py engine`() {
        assertFalse(PyEngine.supportsSite(site(3, "https://a.com/lib/drpy2.min.js")))
        assertFalse(PyEngine.supportsSite(site(3, "csp_Jianpian")))
        assertFalse(PyEngine.supportsSite(site(0, "https://a.com/api.php/provide/vod")))
        assertFalse(PyEngine.supportsSite(site(3, "https://a.com/x.pyc")))
    }
}