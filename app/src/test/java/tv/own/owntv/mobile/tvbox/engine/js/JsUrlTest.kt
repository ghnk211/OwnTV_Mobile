package tv.own.owntv.mobile.tvbox.engine.js

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 宿主 `joinUrl`（drpy2 的 `urljoin`）回归测试。
 *
 * 移植自 VOD `4c8d289:app/src/test/java/com/vod/player/engine/js/JsUrlTest.kt`。
 */
class JsUrlTest {

    @Test
    fun `root relative path joins host`() {
        assertEquals(
            "https://www.djuu.com/exclusive/115_1.html",
            JsUrl.join("https://www.djuu.com", "/exclusive/115_1.html"),
        )
        assertEquals(
            "https://www.djuu.com/djlist/fyclass_fypage.html",
            JsUrl.join("https://www.djuu.com", "/djlist/fyclass_fypage.html"),
        )
    }

    @Test
    fun `relative path replaces last segment`() {
        assertEquals(
            "https://www.djuu.com/djlist/115_1.html",
            JsUrl.join("https://www.djuu.com/djlist/1_1.html", "115_1.html"),
        )
    }

    @Test
    fun `dot segments are normalized`() {
        assertEquals(
            "https://www.djuu.com/a/c.html",
            JsUrl.join("https://www.djuu.com/a/b/../x.html", "./c.html"),
        )
    }

    @Test
    fun `base query is dropped when joining`() {
        assertEquals(
            "https://api.web.360kan.com/v1/detail",
            JsUrl.join("https://api.web.360kan.com/v1/rank?cat=2&size=9", "/v1/detail"),
        )
    }

    @Test
    fun `absolute path is returned as is`() {
        assertEquals(
            "https://other.example.com/x.html",
            JsUrl.join("https://www.djuu.com", "https://other.example.com/x.html"),
        )
    }

    @Test
    fun `protocol relative path inherits scheme`() {
        assertEquals(
            "https://cdn.example.com/x.js",
            JsUrl.join("https://www.djuu.com", "//cdn.example.com/x.js"),
        )
        assertEquals(
            "http://cdn.example.com/x.js",
            JsUrl.join("http://www.djuu.com", "//cdn.example.com/x.js"),
        )
    }

    @Test
    fun `non http scheme is preserved`() {
        assertEquals("magnet:?xt=urn:btih:abc", JsUrl.join("https://a.com", "magnet:?xt=urn:btih:abc"))
        assertEquals("data:text/plain;base64,AAA", JsUrl.join("https://a.com", "data:text/plain;base64,AAA"))
    }

    @Test
    fun `drpy placeholders are not encoded`() {
        assertEquals(
            "https://www.djuu.com/search?musicname=**",
            JsUrl.join("https://www.djuu.com", "/search?musicname=**"),
        )
        assertEquals(
            "https://www.djuu.com/djlist/fyclass_fypage.html",
            JsUrl.join("https://www.djuu.com", "/djlist/fyclass_fypage.html"),
        )
    }

    @Test
    fun `empty inputs degrade gracefully`() {
        assertEquals("https://www.djuu.com", JsUrl.join("https://www.djuu.com", ""))
        assertEquals("https://www.djuu.com", JsUrl.join("https://www.djuu.com", null))
        assertEquals("/x.html", JsUrl.join("", "/x.html"))
        assertEquals("", JsUrl.join(null, null))
    }

    @Test
    fun `illegal characters still yield absolute url`() {
        val joined = JsUrl.join("https://www.djuu.com", "/a b/c.html")
        assertEquals("https://www.djuu.com/a b/c.html", joined)
    }
}