package tv.own.owntv.mobile.tvbox.util

/**
 * HTML 落地页识别 —— TVBox 生态「UA 门控」站点的兜底前置判断。
 *
 * ## 背景（真机实测，2026-10-15）
 * 影视仓（`http://影视仓.com` → `47.120.41.246:8025`）按 User-Agent 分流：
 * - okhttp 系 UA → 302 到真实配置 `tv.json` / 真 jar（lubin.php）/ 真 m3u（zb.php）
 * - 浏览器系 UA → 302 到官网 HTML 落地页
 *
 * 本项目默认注入移动 Chrome UA（[UaUtil.DEFAULT_UA]），请求配置地址
 * 跟两级重定向后拿到的是 HTML，`ConfigParser` 报「JSON 格式错误」，
 * 用户看到"无法解析"。修复策略见 [UaUtil.GATE_UA] 与各调用点的重试逻辑。
 *
 * ## 判定规则
 * 配置（JSON / 加密 base64）、spider jar（PK / dex 魔数）、
 * 直播订阅（#EXTM3U / txt / JSON / XML）都**不可能**以 `<` 开头
 * （XML 订阅以 `<?xml` 开头，显式排除）。因此：
 * 剥掉 BOM 与空白后**以 `<` 开头**且头部 256 字符内含任一 HTML 特征标记
 * （doctype / html / head / body / 注释 / meta / script / div …）即认定为落地页。
 *
 * 实测样本（都不能只匹配 `<!doctype` 前缀，否则漏判）：
 * - 影视仓落地页：BOM + `<!DOCTYPE html>` ✓
 * - zb.php 门控跳转落点：`    <!-- ===== 顶部悬浮 ===== -->`（前导空白 + 注释开头）
 *
 * 误判代价为零：真资源被误判时，GATE_UA 重试会原样返回同样内容，
 * 调用点校验（isJson / looksLikeJar / #EXTM3U）不认，只会多一次请求。
 */
object HtmlGuard {

    /** HTML 文档头部特征标记（命中其一即认定为网页） */
    private val HTML_MARKERS = listOf(
        "<!doctype", "<html", "<head", "<body", "<!--",
        "<meta", "<script", "<div", "<title", "<span",
        "<style", "<link", "<img", "<form", "<table", "<svg", "<nav", "<header",
    )

    /** 文本是否是 HTML 文档（落地页 / 错误页） */
    fun looksLikeHtml(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        return isHtmlHead(text)
    }

    /**
     * 字节是否是 HTML 文档。
     * 剥掉 UTF-8 BOM 后取前 512 字节按 Latin-1 解码做标记匹配 ——
     * 纯 ASCII 比较，不会破坏二进制内容的后续判断（jar 魔数校验另走 looksLikeJar）。
     */
    fun looksLikeHtml(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.isEmpty()) return false
        var off = 0
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) off = 3
        val end = minOf(bytes.size, off + 512)
        return isHtmlHead(String(bytes, off, end - off, Charsets.ISO_8859_1))
    }

    private fun isHtmlHead(s: String): Boolean {
        val trimmed = s.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        // 真资源都不是 `<` 开头；XML 订阅以 `<?xml` 开头，显式排除
        if (!trimmed.startsWith("<")) return false
        if (trimmed.startsWith("<?xml", ignoreCase = true)) return false
        val head = trimmed.take(256).lowercase()
        return HTML_MARKERS.any { head.contains(it) }
    }
}
