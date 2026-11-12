package tv.own.owntv.mobile.tvbox.engine.js

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JS 出网 SSRF 加固：scheme 白名单、回环/元数据/私网拒绝、重定向目标复检。
 * 纯字面量用例，不依赖 DNS 与外网。
 */
class JsBridgeSsrfTest {

    @Test
    fun `scheme whitelist rejects local schemes`() {
        assertNotNull(JsSsrfGuard.check("file:///data/data/tv.own.owntv.mobile/x.js"))
        assertNotNull(JsSsrfGuard.check("content://com.android.providers/x"))
        assertNotNull(JsSsrfGuard.check("ftp://example.com/x"))
        assertNotNull(JsSsrfGuard.check("jar://x"))
    }

    @Test
    fun `loopback and localhost are rejected`() {
        assertNotNull(JsSsrfGuard.check("http://127.0.0.1:9978/proxy?do=js"))
        assertNotNull(JsSsrfGuard.check("http://127.8.9.10/x"))
        assertNotNull(JsSsrfGuard.check("http://localhost/x"))
        assertNotNull(JsSsrfGuard.check("http://foo.localhost/x"))
        assertNotNull(JsSsrfGuard.check("http://[::1]/x"))
    }

    @Test
    fun `cloud metadata and private ranges are rejected`() {
        assertNotNull(JsSsrfGuard.check("http://169.254.169.254/latest/meta-data/"))
        assertNotNull(JsSsrfGuard.check("http://0.0.0.0/"))
        assertNotNull(JsSsrfGuard.check("http://10.0.0.1/"))
        assertNotNull(JsSsrfGuard.check("http://192.168.1.1/"))
        assertNotNull(JsSsrfGuard.check("http://172.16.0.1/"))
        assertNotNull(JsSsrfGuard.check("http://100.64.0.1/"))
    }

    @Test
    fun `redirect target is rechecked before the next hop`() {
        // 首跳公网 + 302 到回环：宿主每跳重建绝对地址后再校验，必须在第二跳拒绝。
        val next = JsUrl.join("https://public.example.com/a", "http://127.0.0.1:9978/proxy?do=js")
        assertNotNull(JsSsrfGuard.check(next))
    }

    @Test
    fun `public literal is allowed`() {
        assertNull(JsSsrfGuard.check("https://1.1.1.1/x"))
    }
}