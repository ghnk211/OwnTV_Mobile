package tv.own.owntv.mobile.tvbox.engine.js

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** `/proxy` 返回值解析：经典 drpy 五元组 + cat 对象形态。 */
class JsProxyCodecTest {

    @Test
    @OptIn(ExperimentalEncodingApi::class)
    fun `classic drpy tuple with base64 body decodes to bytes`() {
        val payload = Base64.Default.encode("0123456789".toByteArray())
        val result = JsProxyCodec.toResult(listOf(200, "video/mp2t", payload, mapOf("X-Test" to "1"), true))
        assertEquals(200, result?.code)
        assertEquals("video/mp2t", result?.contentType)
        assertEquals("1", result?.headers?.get("X-Test"))
        assertArrayEquals("0123456789".toByteArray(), result?.bytes)
    }

    @Test
    fun `classic drpy tuple with plain text body`() {
        val result = JsProxyCodec.toResult(listOf(404, "text/plain", "Not Found"))
        assertEquals(404, result?.code)
        assertEquals("text/plain", result?.contentType)
        assertArrayEquals("Not Found".toByteArray(), result?.bodyBytes())
        assertArrayEquals("Not Found".toByteArray(), result?.bytes)
    }

    @Test
    fun `cat style object map is supported`() {
        val result = JsProxyCodec.toResult(
            mapOf(
                "code" to 200,
                "contentType" to "application/octet-stream",
                "content" to "abc",
            ),
        )
        assertEquals(200, result?.code)
        assertEquals("application/octet-stream", result?.contentType)
        assertArrayEquals("abc".toByteArray(), result?.bodyBytes())
    }

    @Test
    fun `non list non map returns null`() {
        assertNull(JsProxyCodec.toResult("hello"))
        assertNull(JsProxyCodec.toResult(null))
    }
}