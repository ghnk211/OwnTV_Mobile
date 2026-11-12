package tv.own.owntv.mobile.tvbox.engine.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsRuntimeTest {
    @Test
    fun `relative module url resolves against importing module`() {
        assertEquals(
            "https://tv.203511.xyz/lib/drpy-core-lite.min.js",
            JsRuntime.normalizeModuleUrl(
                "https://tv.203511.xyz/lib/drpy2.min.js",
                "./drpy-core-lite.min.js",
            ),
        )
    }

    @Test
    fun `parent module url resolves and normalizes`() {
        assertEquals(
            "https://tv.203511.xyz/lib/shared/core.js",
            JsRuntime.normalizeModuleUrl(
                "https://tv.203511.xyz/lib/runtime/drpy2.min.js",
                "../shared/./core.js",
            ),
        )
    }

    @Test
    fun `empty module stub exports common missing names`() {
        val code = JsRuntime.EMPTY_MODULE_CODE
        listOf("inflate", "deflate", "gzip", "ungzip", "encrypt", "decrypt", "JSEncrypt", "NodeRSA", "JSON5", "pako")
            .forEach { name ->
                assertTrue("缺 export: $name", code.contains("export function $name"))
            }
        assertTrue("缺 default export", code.contains("export default"))
    }

    @Test
    fun `invoke bridge returns json string for object results`() {
        val bridge = JsRuntime.INVOKE_BRIDGE
        assertTrue(bridge.contains("__vodInvoke"))
        assertTrue(bridge.contains("JSON.stringify"))
    }
}