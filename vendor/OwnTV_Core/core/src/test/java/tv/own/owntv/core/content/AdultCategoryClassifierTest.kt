package tv.own.owntv.core.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.model.MediaType

class AdultCategoryClassifierTest {
    @Test
    fun `recognizes common provider adult category names`() {
        listOf(
            "ADULT",
            "UK | Adults 18+",
            "XXX Movies",
            "Erotik",
            "Für Erwachsene",
            "Para Adultos",
            "Dla dorosłych",
            "Для взрослых",
            "للكبار",
            "成人频道",
            "성인",
        ).forEach { name ->
            assertTrue("Expected adult category: $name", AdultCategoryClassifier.isAdult(name))
        }
    }

    @Test
    fun `does not classify adult swim or ordinary categories as adult`() {
        listOf(
            "Adult Swim",
            "US | Adult-Swim HD",
            "Kids",
            "Family Movies",
            "Channel 18",
            "Sports +1",
            "",
        ).forEach { name ->
            assertFalse("Expected safe category: $name", AdultCategoryClassifier.isAdult(name))
        }
    }

    @Test
    fun `classifies the category names the backfill made visible`() {
        // Categories that only reach a user because the sync now asks for the ones a provider's bulk
        // list omitted. These three are verbatim from live panels: the Stalker portal's censored genre,
        // and two Xtream categories that merely look adult — Adult Swim is a cartoon block.
        assertTrue(AdultCategoryClassifier.isAdult("18| FOR ADULTS"))
        assertFalse(AdultCategoryClassifier.isAdult("NETFLIX ADULT-SWIM"))
        assertFalse(AdultCategoryClassifier.isAdult("HBO MAX (ADULT SWIM)"))
    }

    @Test
    fun `adult swim exception does not mask another adult marker`() {
        assertTrue(AdultCategoryClassifier.isAdult("Adult Swim | XXX"))
    }

    @Test
    fun `normal profile does not system-hide adult categories`() {
        val adult = category(1, "Adults 18+")
        assertTrue(AdultCategoryClassifier.hiddenCategoryIds(listOf(adult), emptySet(), false).isEmpty())
    }

    @Test
    fun `kids profile system-hides only classified adult categories`() {
        val adult = category(1, "Adults 18+")
        val safe = category(2, "Family Movies")
        assertTrue(AdultCategoryClassifier.hiddenCategoryIds(listOf(adult, safe), emptySet(), true) == setOf(1L))
    }

    // --- 中文补充词表并入 Core（方案 §4.5.2）：IPTV 分类级 kids 过滤同步收紧 ---

    @Test
    fun `the TVbox Chinese vocabulary is classified at category level too`() {
        listOf(
            "伦理片", "成人", "福利", "午夜", "写真", "禁片", "里番", "无码",
            "情色", "三级", "激情", "偷拍", "探花", "18禁", "限制级",
        ).forEach { word ->
            assertTrue("Expected adult category: $word", AdultCategoryClassifier.isAdult(word))
            assertTrue("Expected adult category: ${word}专区", AdultCategoryClassifier.isAdult("${word}专区"))
        }
    }

    @Test
    fun `ordinary Chinese categories stay visible`() {
        listOf("电影", "电视剧", "综艺", "动漫", "纪录片", "少儿").forEach { name ->
            assertFalse("Expected safe category: $name", AdultCategoryClassifier.isAdult(name))
        }
    }

    @Test
    fun `kids profile system-hides the Chinese supplementary categories`() {
        val adult = category(1, "伦理片")
        val safe = category(2, "电影")
        assertTrue(AdultCategoryClassifier.hiddenCategoryIds(listOf(adult, safe), emptySet(), true) == setOf(1L))
    }

    // --- 条目级判定 isAdultItem（方案 §4.5.2，四处读取路径共用） ---

    @Test
    fun `isAdultItem hits any indexed category name including the Chinese list`() {
        assertTrue(AdultCategoryClassifier.isAdultItem("""{"typeNames":["伦理片"]}"""))
        assertTrue(AdultCategoryClassifier.isAdultItem("""{"typeNames":["电影","福利"]}"""))
        assertTrue(AdultCategoryClassifier.isAdultItem("""{"typeNames":["XXX Movies"]}"""))
    }

    @Test
    fun `isAdultItem honours the site-level adult flag`() {
        assertTrue(AdultCategoryClassifier.isAdultItem("""{"adultSite":true}"""))
        assertTrue(AdultCategoryClassifier.isAdultItem("""{"adultSite":true,"typeNames":["电影"]}"""))
        assertFalse(AdultCategoryClassifier.isAdultItem("""{"adultSite":false,"typeNames":["电影"]}"""))
    }

    @Test
    fun `isAdultItem is false for empty missing or invalid data`() {
        assertFalse(AdultCategoryClassifier.isAdultItem(null))
        assertFalse(AdultCategoryClassifier.isAdultItem(""))
        assertFalse(AdultCategoryClassifier.isAdultItem("   "))
        assertFalse(AdultCategoryClassifier.isAdultItem("{not json"))
        assertFalse(AdultCategoryClassifier.isAdultItem("""{"typeNames":[]}"""))
        assertFalse(AdultCategoryClassifier.isAdultItem("""{"typeNames":["电影","电视剧"]}"""))
        assertFalse(AdultCategoryClassifier.isAdultItem("""{"remark":"成人"}"""))
    }

    @Test
    fun `isAdultItem never judges the title`() {
        assertFalse(AdultCategoryClassifier.isAdultItem("""{"name":"Sex and the City","typeNames":["电影"]}"""))
    }

    private fun category(id: Long, name: String) = CategoryEntity(
        id = id,
        sourceId = 10,
        mediaType = MediaType.MOVIE,
        name = name,
    )
}
