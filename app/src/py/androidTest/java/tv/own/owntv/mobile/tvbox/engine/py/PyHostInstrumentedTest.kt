package tv.own.owntv.mobile.tvbox.engine.py

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import tv.own.owntv.mobile.tvbox.net.SiteMark
import java.io.File
import java.util.Base64

/**
 * PyHost 的运行时用例（docs/TVbox-PY源迁移方案.md §6）。
 *
 * 校验 fetch 的返回契约（code/url/content/base64/charset）、自定义 header、
 * [SiteMark] 埋点、非法 header 跳过、失败 code=-1，以及脚本 stdio 转发不崩。
 */
@RunWith(AndroidJUnit4::class)
class PyHostInstrumentedTest {

    private lateinit var server: MockWebServer
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        PyEngine.ensurePythonStarted(context)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun fetch_returnsJsonContract() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=gbk")
                .setBody("北京时间"),
        )

        val raw = PyHost.fetch(
            server.url("/live").toString(),
            """{"X-Test":"1"}""",
            "北京[js]",
            5_000,
        )
        val obj = JSONObject(raw)
        assertEquals(200, obj.getInt("code"))
        assertEquals("gbk", obj.getString("charset"))
        assertEquals("北京时间", String(Base64.getDecoder().decode(obj.getString("content")), Charsets.UTF_8))

        val request = server.takeRequest()
        assertEquals("1", request.getHeader("X-Test"))
        assertEquals("北京[js]", SiteMark.decode(request.getHeader(SiteMark.HEADER).orEmpty()))
    }

    @Test
    fun fetch_skipsIllegalHeaderWithoutFailing() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        // 换行在 HTTP 头里非法；必须跳过它，而不是让整次请求抛异常
        val raw = PyHost.fetch(
            server.url("/x").toString(),
            """{"X-Bad":"a\nb","X-Good":"yes"}""",
            "",
            5_000,
        )
        val obj = JSONObject(raw)
        assertEquals(200, obj.getInt("code"))
        val request = server.takeRequest()
        assertEquals("yes", request.getHeader("X-Good"))
    }

    @Test
    fun fetch_failureReturnsMinusOneNotThrow() {
        val raw = PyHost.fetch("http://127.0.0.1:1/nothing", "", "", 1_000)
        val obj = JSONObject(raw)
        assertEquals(-1, obj.getInt("code"))
        assertNotNull(obj.optString("error"))
    }

    @Test
    fun redirect_stdio_doesNotCrashAndForwardsPrint() {
        val loader = com.chaquo.python.Python.getInstance().getModule("base.loader")
        loader.callAttr("redirect_stdio")
        val script = File(context.filesDir, "pytest/print.py").apply {
            parentFile?.mkdirs()
            writeText("print('hello from python')\n")
        }
        // 执行顶层 print：转发到 TbLog 的路径不能抛异常
        loader.callAttr("load_spider", script.absolutePath, "test_print")
        assertTrue(script.isFile)
    }
}