package tv.own.owntv.mobile.tvbox.engine.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrpyHtmlParserTest {
    private val html = """
        <div class="card">
          <a class="title" href="/detail/1">Example <b>Film</b></a>
          <a class="title" href="/detail/2">Second</a>
        </div>
    """.trimIndent()

    @Test
    fun `pdfh extracts text and attributes`() {
        assertEquals("Example Film", DrpyHtmlParser.pdfh(html, ".card&&a.title&&Text"))
        assertEquals("/detail/1", DrpyHtmlParser.pdfh(html, ".card&&a.title&&href"))
    }

    @Test
    fun `pdfa returns matching element html`() {
        assertEquals(
            listOf("<a class=\"title\" href=\"/detail/1\">Example <b>Film</b></a>", "<a class=\"title\" href=\"/detail/2\">Second</a>"),
            DrpyHtmlParser.pdfa(html, ".card&&a.title"),
        )
    }

    @Test
    fun `pd resolves extracted relative attribute`() {
        assertEquals(
            "https://example.test/detail/1",
            DrpyHtmlParser.pd(html, ".card&&a.title&&href", "https://example.test/list"),
        )
    }

    private val siblings = """
        <div><em>z</em><p class="x">a</p><p class="x">b</p></div>
    """.trimIndent()

    @Test
    fun `eq indexes the result set not the sibling position`() {
        assertEquals("a", DrpyHtmlParser.pdfh(siblings, ".x:eq(0)&&Text"))
        assertEquals("b", DrpyHtmlParser.pdfh(siblings, ".x:eq(1)&&Text"))
    }

    @Test
    fun `eq supports negative index`() {
        assertEquals("b", DrpyHtmlParser.pdfh(siblings, ".x:eq(-1)&&Text"))
        assertEquals("a", DrpyHtmlParser.pdfh(siblings, ".x:eq(-2)&&Text"))
    }

    @Test
    fun `eq out of range yields empty`() {
        assertEquals("", DrpyHtmlParser.pdfh(siblings, ".x:eq(9)&&Text"))
        assertEquals("", DrpyHtmlParser.pdfh(siblings, ".x:eq(-9)&&Text"))
    }

    @Test
    fun `eq inside a chained selector`() {
        val chained = """
            <dl class="pd-class"><dt>分类</dt><dd>言情</dd></dl>
            <dl class="pd-class"><dt>分类</dt><dd>都市</dd></dl>
        """.trimIndent()
        assertEquals("言情", DrpyHtmlParser.pdfh(chained, ".pd-class:eq(0) dd&&Text"))
        assertEquals("都市", DrpyHtmlParser.pdfh(chained, ".pd-class:eq(1) dd&&Text"))
    }

    @Test
    fun `gt still passes through to jsoup`() {
        val table = """
            <table class="list_musiclist">
              <tr><th>表头</th></tr>
              <tr><td>第一首</td></tr>
              <tr><td>第二首</td></tr>
            </table>
        """.trimIndent()
        assertEquals(2, DrpyHtmlParser.pdfa(table, ".list_musiclist tr:gt(0)").size)
    }

    @Test
    fun `double dash excludes descendants instead of descending`() {
        val item = """<h2>斗破苍穹<span>HD</span></h2>"""
        assertEquals("斗破苍穹", DrpyHtmlParser.pdfh(item, "h2--span&&Text"))
        assertEquals("斗破苍穹HD", DrpyHtmlParser.pdfh(item, "h2&&Text"))
    }

    @Test
    fun `exclude keeps the element itself`() {
        val item = """<a class="t" href="/p/1"><span class="tag">新</span>标题</a>"""
        assertEquals("标题", DrpyHtmlParser.pdfh(item, ".t--.tag&&Text"))
        assertEquals("/p/1", DrpyHtmlParser.pdfh(item, ".t--.tag&&href"))
    }

    @Test
    fun `multiple excludes are all applied`() {
        val item = """<div class="box">正文<em>广告</em><span>角标</span></div>"""
        assertEquals("正文", DrpyHtmlParser.pdfh(item, ".box--em--span&&Text"))
    }

    @Test
    fun `exclude does not mutate the source for the next rule`() {
        val item = """<div class="box">正文<em>广告</em></div>"""
        assertEquals("正文", DrpyHtmlParser.pdfh(item, ".box--em&&Text"))
        assertEquals("正文广告", DrpyHtmlParser.pdfh(item, ".box&&Text"))
    }

    @Test
    fun `no option returns outer html`() {
        val box = """<span class="box">正文<em>广告</em></span>"""
        assertEquals("<span class=\"box\">正文<em>广告</em></span>", DrpyHtmlParser.pdfh(box, ".box"))
    }

    @Test
    fun `empty rule yields empty`() {
        assertEquals("", DrpyHtmlParser.pdfh(html, ""))
        assertTrue(DrpyHtmlParser.pdfa(html, "").isEmpty())
    }
}