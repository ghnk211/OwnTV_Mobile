package tv.own.owntv.mobile.tvbox.engine.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 相对路径兜底、静态 import 提取与 http/本地分流。 */
class JsSpiderRelativeTest {

    private val runtime = "https://cdn.example.com/lib/drpy2.min.js"

    @Test
    fun `relative rule script resolves against the config url first`() {
        assertEquals(
            "https://cdn.example.com/cfg/js/rule.js",
            JsSpider.resolveRuleUrlFor("./js/rule.js", runtime, "https://cdn.example.com/cfg/a.json"),
        )
    }

    @Test
    fun `relative rule script falls back to the runtime directory`() {
        assertEquals(
            "https://cdn.example.com/lib/rule.js",
            JsSpider.resolveRuleUrlFor("rule.js", runtime, null),
        )
    }

    @Test
    fun `absolute rule script is untouched`() {
        assertEquals(
            "https://other.example.com/rule.js",
            JsSpider.resolveRuleUrlFor("https://other.example.com/rule.js", runtime, "https://cdn.example.com/cfg/a.json"),
        )
        assertNull(JsSpider.resolveRuleUrlFor(null, runtime, null))
        assertNull(JsSpider.resolveRuleUrlFor("  ", runtime, null))
    }

    @Test
    fun `relative runtime falls back to the config url`() {
        assertEquals(
            "https://cdn.example.com/cfg/lib/drpy2.min.js",
            JsSpider.resolveScriptUrlFor("./lib/drpy2.min.js", runtime, "https://cdn.example.com/cfg/a.json"),
        )
    }

    @Test
    fun `static imports cover from and bare forms`() {
        val source = """
            import drpy from './drpy-core-lite.min.js';
            import './extra.js';
            import { a } from "/abs/mod.js";
        """.trimIndent()
        assertEquals(
            listOf(
                "https://cdn.example.com/lib/drpy-core-lite.min.js",
                "https://cdn.example.com/lib/extra.js",
                "https://cdn.example.com/abs/mod.js",
            ),
            JsSpider.extractStaticImportsFrom(source, runtime),
        )
    }

    @Test
    fun `script route splits http from local addresses`() {
        assertEquals(ScriptRoute.HTTP, JsSpider.scriptRouteOf("https://a.com/x.js"))
        assertEquals(ScriptRoute.HTTP, JsSpider.scriptRouteOf("clan://192.168.1.9/x.js"))
        assertEquals(ScriptRoute.LOCAL, JsSpider.scriptRouteOf("clan://localhost/x.js"))
        assertEquals(ScriptRoute.LOCAL, JsSpider.scriptRouteOf("file:///sdcard/x.js"))
        assertEquals(ScriptRoute.LOCAL, JsSpider.scriptRouteOf("/sdcard/x.js"))
        assertNull(JsSpider.scriptRouteOf("./x.js"))
    }
}