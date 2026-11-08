package tv.own.owntv.mobile.tvbox.config.parser

import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * 用真实世界的脏配置验证容错解析器。
 *
 * 背景：用户给了一份`http://xhztv.top/4k.json`（TVBox 配置，30KB）。
 * 用**标准 JSON 解析器**（Python json / 严格实现）直接失败，报两类错：
 * 1. `Expecting value: line 54 column 5` —— 配置里写了 `// xxx` 行注释（JSON 不允许）
 * 2. `Invalid control character at: line 682` —— 字符串里有未转义的裸控制字符
 *
 * 生态里这类配置很常见（人手写 JSON 大多不规范），所以容错是刚需。
 * 本测试把真实样本存成 fixture，确保容错能力不会退化。
 */
class ConfigParserDirtyJsonTest {

    private fun fixture(): String {
        val f = File("src/test/resources/fixtures/dirty-4k.json")
        assertTrue("找不到脏配置样本：${f.absolutePath}", f.exists())
        return f.readText()
    }

    /**
     * 真实样本必须能解析出站点 —— 这是本次改动的核心目标。
     *
     * 之前 `isLenient = true` 不足以处理 `//` 行注释：
     * kotlinx.serialization 的 lenient 允许未加引号的键名、允许单引号，
     * 但**不忽略注释**，遇到 `/` 直接报 `Expecting value`。
     */
    @Test
    fun `含行注释的真实脏配置能解析出站点`() {
        val r = ConfigParser.parse(fixture())
        assertNotNull("脏配置不应解析失败，实际错误：${r.error}", r.config)
        assertTrue("站点数不应为 0，实际 ${r.config?.sites?.size}", r.config!!.sites.isNotEmpty())
    }

    /** 裸控制字符（未转义的换行/制表符）也必须被容忍 */
    @Test
    fun `字符串内裸控制字符不导致整份配置失败`() {
        val dirty = """
            {"spider":"https://x/a.jar","sites":[
              {"key":"k1","name":"源1","type":3,"api":"csp_Demo"},
              {"key":"k2","name":"源2","type":1,"api":"https://x/cms/?ac=list"}
            ]}
        """.trimIndent()
        // 人为插入裸制表符与换行到字符串里
        val withCtrl = dirty.replace("源1", "源\t1\n名字")
        val r = ConfigParser.parse(withCtrl)
        assertNotNull("裸控制字符不应导致失败：${r.error}", r.config)
        assertTrue(r.config!!.sites.isNotEmpty())
    }

    /** 注释剥离不能误伤 URL 里的 // —— 这是最容易写错的地方 */
    @Test
    fun `注释剥离不误伤 URL 中的双斜杠`() {
        val withUrl = """
            {
              // 这是真注释，应被剥掉
              "spider":"https://cdn.example.com/a.jar",
              "sites":[{"key":"k","name":"源","type":1,"api":"https://x.com/cms/?ac=list"}]
            }
        """.trimIndent()
        val r = ConfigParser.parse(withUrl)
        assertNotNull(r.config)
        assertTrue(
            "URL 里的 // 被误当注释切断了，实际：${r.config?.spider}",
            r.config!!.spider?.contains("https://cdn.example.com/a.jar") == true,
        )
        assertTrue(
            "api 的 // 受损：${r.config?.sites?.firstOrNull()?.api}",
            r.config!!.sites.first().api.contains("https://x.com/cms/") == true,
        )
    }
}