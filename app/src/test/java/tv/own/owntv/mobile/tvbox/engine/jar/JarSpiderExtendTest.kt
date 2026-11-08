package tv.own.owntv.mobile.tvbox.engine.jar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `categoryContent` 的 extend 形态回归测试。
 *
 * ## 固化的真机 bug：「💖┃至臻┃4K💖」子类列表全空
 *
 * `csp_PanWebShare.categoryContent(String,String,Z,HashMap)` 的第一条指令就是
 * `extend.get("cateId")`（dexdump 实测），**没有判空**。而本工程早前 `extendToMap`
 * 在 extend 为空（用户没选筛选项）时返回 **null** → jar 内 NPE →
 * `tryInvoke` 静默退到 String 重载（桩基类默认返回 null）→ 上层误报"该分类暂无数据"。
 *
 * TVBoxOS 对照：传给 categoryContent 的恒是 `sortData.filterSelect`
 * （`new HashMap<>()`，无筛选时为空 map，**永不为 null**）。
 *
 * 故契约：**extendToMap 恒不返回 null**。
 */
class JarSpiderExtendTest {

    @Test
    fun `空 extend 必须返回空 map 而不是 null（至臻4K NPE 根因）`() {
        listOf(null, "", "   ").forEach { input ->
            val map = JarSpider.extendToMap(input)
            assertNotNull("extendToMap($input) 必须非 null —— jar 首条指令会解引用", map)
            assertTrue("extendToMap($input) 应为空 map", map.isEmpty())
        }
    }

    @Test
    fun `JSON 对象 extend 解析为键值 map`() {
        val map = JarSpider.extendToMap("""{"cateId":"12","area":"大陆"}""")
        assertEquals(2, map.size)
        assertEquals("12", map["cateId"])
        assertEquals("大陆", map["area"])
    }

    @Test
    fun `非法 JSON 退化为单键 map 不丢信息`() {
        val map = JarSpider.extendToMap("not-a-json")
        assertEquals("not-a-json", map["extend"])
    }

    @Test
    fun `JSON 数组 extend 退化为单键 map`() {
        val map = JarSpider.extendToMap("[1,2]")
        assertEquals("[1,2]", map["extend"])
    }
}
