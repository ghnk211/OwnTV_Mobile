package tv.own.owntv.mobile.tvbox.engine.js

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Minimal jsoup-backed implementation of the DRPY `pdfh`/`pdfa`/`pd` parser helpers.
 *
 * ## 语义对齐参考项目 TVBoxOS `crawler/js/HtmlParser`
 * drpy/海阔的选择器方言是 **jQuery 血统**，与 jsoup 同名伪类的语义并不一致：
 *
 * 1. **`:eq(n)` 是结果集下标，不是兄弟下标**
 *    jsoup 的 `:eq/:gt/:lt` 走的是 `elementSiblingIndex()`（**兄弟**下标），
 *    drpy 走的是结果集下标（jQuery 语义）。故把 `:eq(n)` **从选择器里剥掉**，
 *    改对结果集做下标选取。
 * 2. **`--` 是排除语法，不是子代选择器**
 *    `h2--span` 表示"选 h2，再剔除其中的 span 后取 Text"。
 *
 * `:gt/:lt` **原样透传给 jsoup**（参考项目也这么做）。
 *
 * 移植自 VOD `4c8d289:app/src/main/java/com/vod/player/engine/js/DrpyHtmlParser.kt`。
 */
internal object DrpyHtmlParser {

    fun pdfh(html: String, rule: String): String {
        val parts = rule.split("&&").filter(String::isNotBlank)
        if (parts.isEmpty()) return ""
        val last = parts.last()
        val field = last.takeIf { parts.size > 1 && isField(it) }
        val selectors = if (field == null) parts else parts.dropLast(1)
        val element = select(html, selectors) ?: return ""
        return when (field?.lowercase()) {
            "text" -> element.text()
            "html", "innerhtml" -> element.html()
            "outerhtml" -> element.outerHtml()
            null -> element.outerHtml()
            else -> element.attr(field)
        }
    }

    fun pdfa(html: String, rule: String): List<String> {
        val selectors = rule.split("&&").filter(String::isNotBlank)
        if (selectors.isEmpty()) return emptyList()
        return runCatching {
            resolve(Jsoup.parse(html), selectors).map(Element::outerHtml)
        }.getOrDefault(emptyList())
    }

    fun pd(html: String, rule: String, baseUrl: String): String {
        val value = pdfh(html, rule)
        if (value.isBlank() || baseUrl.isBlank()) return value
        return runCatching { URI(baseUrl).resolve(URI(value)).toString() }.getOrDefault(value)
    }

    private fun select(html: String, selectors: List<String>): Element? = runCatching {
        resolve(Jsoup.parse(html), selectors).firstOrNull()
    }.getOrNull()

    /**
     * 按 drpy 语义选出元素集合：剥离 `:eq(n)` → 结果集取下标 → 应用 `--` 排除。
     * 排除对**克隆体**做，避免污染同一片段的下一次取数。
     */
    private fun resolve(root: Element, selectors: List<String>): List<Element> {
        val parsed = ParsedRule(selectors)
        if (parsed.selector.isBlank()) return emptyList()
        var elements: List<Element> = root.select(parsed.selector)
        for (index in parsed.eq) {
            val picked = pick(elements, index) ?: return emptyList()
            elements = listOf(picked)
        }
        if (parsed.excludes.isEmpty()) return elements
        return elements.map { element ->
            val clone = element.clone()
            clone.select(parsed.excludes.joinToString(",")).remove()
            clone
        }
    }

    /** 结果集取下标，负索引表示倒数第 n 个（jQuery `:eq(-1)` = 最后一个） */
    private fun pick(elements: List<Element>, index: Int): Element? {
        if (elements.isEmpty()) return null
        val i = if (index < 0) elements.size + index else index
        return elements.getOrNull(i)
    }

    /** 把 drpy 选择器串拆成「jsoup 选择器 + :eq 下标序列 + -- 排除列表」 */
    private class ParsedRule(selectors: List<String>) {
        val selector: String
        val eq: List<Int>
        val excludes: List<String>

        init {
            val joined = selectors.joinToString(" ") { it.trim() }
            eq = EQ.findAll(joined).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
            val withoutEq = joined.replace(EQ, "").trim()
            val pieces = withoutEq.split("--")
            selector = pieces.first().trim()
            excludes = pieces.drop(1).map { it.trim() }.filter { it.isNotBlank() }
        }
    }

    private val EQ = Regex(""":eq\(\s*(-?\d+)\s*\)""")

    private fun isField(value: String): Boolean =
        value.equals("Text", ignoreCase = true) ||
            value.equals("Html", ignoreCase = true) ||
            value.equals("InnerHtml", ignoreCase = true) ||
            value.equals("OuterHtml", ignoreCase = true) ||
            value.matches(Regex("[A-Za-z_:][A-Za-z0-9_.:-]*"))
}