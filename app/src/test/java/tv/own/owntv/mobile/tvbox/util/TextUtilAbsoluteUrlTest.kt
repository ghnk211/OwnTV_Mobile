package tv.own.owntv.mobile.tvbox.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** 海报/封面相对地址补全（回归改进项 8：网格与详情同一口径）。 */
class TextUtilAbsoluteUrlTest {

    private val base = "http://10.0.2.2:8899/api.php/provide/vod"

    @Test
    fun `绝对地址原样返回`() {
        assertEquals("http://a/pic.jpg", TextUtil.absoluteUrl("http://a/pic.jpg", base))
        assertEquals("https://a/pic.jpg", TextUtil.absoluteUrl("https://a/pic.jpg", base))
    }

    @Test
    fun `相对路径按配置 base 补全`() {
        // "api.php/provide/vod" → 目录前缀 "http://10.0.2.2:8899/"
        assertEquals("http://10.0.2.2:8899/pic/x.jpg", TextUtil.absoluteUrl("pic/x.jpg", base))
    }

    @Test
    fun `根相对路径按 host 补全`() {
        assertEquals("http://10.0.2.2:8899/pic/x.jpg", TextUtil.absoluteUrl("/pic/x.jpg", base))
    }

    @Test
    fun `空地址与空 base 不崩`() {
        assertEquals("", TextUtil.absoluteUrl(null, base))
        assertEquals("", TextUtil.absoluteUrl("", base))
    }
}
