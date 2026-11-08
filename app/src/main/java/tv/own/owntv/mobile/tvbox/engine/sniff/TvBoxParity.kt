package tv.own.owntv.mobile.tvbox.engine.sniff

import tv.own.owntv.mobile.tvbox.config.model.ParseConfig
import kotlinx.serialization.json.jsonPrimitive

/**
 * TVBoxOS 解析流程**字节级复刻**（实验分支 `exp/tvbox-parse-parity`）。
 *
 * 目的：把参考项目（C:\Users\wangnk\Downloads\TVBoxOS-main）的网页型解析链
 * 原样搬过来跑真机基准，量化"我们 vs 参考项目"的差距归因，再回头精修主链路。
 * 逐条对应（源码行号为参考项目实测）：
 *
 * - [checkVideoFormat] ← PlayFragment.java:2724-2739（`url=http`/`.html` 前置排除 + 规则）
 * - [isVideoFormat] ← DefaultConfig.java:121-145（snifferMatch 正则全集，**不含 .ts**）
 * - [pickWebJx] ← SuperParse.java:62-116（flag 匹配到就**只用**匹配的 type=0；否则全部）
 * - [loadHtml] ← SuperParse.java:155-205（JS 动态拼 iframe、src 无引号、sandbox 三件套）
 *
 * 与主链路的**刻意差异**（复刻语义，不带我们的增强）：
 * - **无 verifier**（TVBoxOS 命中即播，不验证流真伪）
 * - **无 .ts 分片降级**（其 snifferMatch 本就不含 .ts，天然不命中）
 * - **无候选健康排序 / ParseHealth 熔断**（TVBoxOS 无此机制）
 * - UA 用 `Utils.UaWinChrome`（SuperParse.java:122 —— 参考项目也是 Windows UA！）
 */
object TvBoxParity {

    /** SuperParse.java:122 `Utils.UaWinChrome`（复刻值） */
    const val UA_WIN_CHROME: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 嗅探超时：PlayFragment.java:2339 `MSG_PARSE_TIMEOUT = 20 * 1000` */
    const val PARSE_TIMEOUT_MS = 20_000L

    /**
     * DefaultConfig.java:121-135 `snifferMatch` 逐分支照抄（正则语义不变）。
     * 注意：后缀集不含 `.ts` —— 这正是参考项目"分片从来不会被当正片"的原因。
     */
    private val SNIFFER_MATCH = Regex(
        "http((?!http).){12,}?\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|m4a|mp3|aac|mpd)\\?.*|" +
            "http((?!http).){12,}\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|m4a|mp3|aac|mpd)|" +
            "http((?!http).)*?video/tos*|" +
            "http((?!http).){20,}?/m3u8\\?pt=m3u8.*|" +
            "http((?!http).)*?default\\.ixigua\\.com/.*|" +
            "http((?!http).)*?dycdn-tos\\.pstatp[^\\?]*|" +
            "http.*?/player/m3u8play\\.php\\?url=.*|" +
            "http.*?/player/.*?[pP]lay\\.php\\?url=.*|" +
            "http.*?/playlist/m3u8/\\?vid=.*|" +
            "http.*?\\.php\\?type=m3u8&.*|" +
            "http.*?/download.aspx\\?.*|" +
            "http.*?/api/up_api.php\\?.*|" +
            "https.*?\\.66yk\\.cn.*|" +
            "http((?!http).)*?netease\\.com/file/.*",
    )

    /** DefaultConfig.java:137-145 `isVideoFormat` */
    fun isVideoFormat(url: String): Boolean {
        if (android.net.Uri.parse(url).path.isNullOrEmpty()) return false
        return SNIFFER_MATCH.containsMatchIn(url)
    }

    /**
     * PlayFragment.java:2724-2739 `checkVideoFormat`（去掉 csp spider 的 manualVideoCheck 分支，
     * 那是 jar 源专属）。
     */
    fun checkVideoFormat(webUrl: String?, url: String): Boolean {
        if (url.contains("url=http") || url.contains(".html")) return false
        return checkIsVideoForParse(webUrl, url)
    }

    /** VideoParseRuler.java:70-86 `checkIsVideoForParse`（配置 rules 部分映射到 SniffRule） */
    private fun checkIsVideoForParse(webUrl: String?, url: String): Boolean {
        if (isVideoFormat(url)) return true
        // 配置 rules（hosts+regex）：与主链路的 customHit 同语义，复刻为"命中 host 后任一 rule 命中"
        return customRuleHit(url)
    }

    private var rules: List<tv.own.owntv.mobile.tvbox.config.model.SniffRule> = emptyList()

    /** 注入配置 rules（每次解析前调用） */
    fun setRules(r: List<tv.own.owntv.mobile.tvbox.config.model.SniffRule>) {
        rules = r
    }

    private fun customRuleHit(url: String): Boolean {
        val lower = url.lowercase()
        for (r in rules) {
            if (r.rule.isEmpty()) continue
            val hs = r.hostList()
            val hostOk = hs.isEmpty() || hs.any { it == "*" || lower.contains(it.lowercase()) }
            if (!hostOk) continue
            if (r.rule.any { it.isNotBlank() && lower.contains(it.trim().lowercase()) }) return true
        }
        return false
    }

    /**
     * SuperParse.java:62-116 候选选择（与主链路 candidatesForWebPage 的**关键差异**）：
     * flag（线路名）命中某个解析器 `ext.flag` → **只用命中的**；一个都没命中 → 全部。
     * 只取 type=0 进 webJx（type=1 的 jsonJx 由调用方另行处理）。
     */
    fun pickWebJx(flag: String, parses: List<ParseConfig>): List<String> {
        val matched = parses.filter { pc ->
            runCatching {
                val arr = pc.ext?.get("flag") as? kotlinx.serialization.json.JsonArray
                arr?.any {
                    runCatching { it.jsonPrimitive.content }.getOrNull()
                        ?.equals(flag, ignoreCase = true) == true
                } == true
            }.getOrDefault(false)
        }
        val pool = matched.ifEmpty { parses }
        return pool.filter { it.type == 0 && it.url.isNotBlank() }.map { it.url }
    }

    /** SuperParse.java:155-205 `loadHtml` 的 HTML 模板（JS 动态拼 iframe，逐字符复刻） */
    fun loadHtml(webJx: List<String>, url: String): String {
        val jxs = webJx.joinToString(",") { "\"$it\"" }
        return (
            "\n<!doctype html>\n<html>\n<head>\n<title>解析</title>\n" +
                "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\" />\n" +
                "<meta http-equiv=\"X-UA-Compatible\" content=\"IE=EmulateIE10\" />\n" +
                "<meta name=\"renderer\" content=\"webkit|ie-comp|ie-stand\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width\">\n" +
                "</head>\n<body>\n<script>\n" +
                "var apiArray=[$jxs];\n" +
                "var urlPs=\"$url\";\n" +
                "var iframeHtml=\"\";\n" +
                "for(var i=0;i<apiArray.length;i++){\n" +
                "var URL=apiArray[i]+urlPs;\n" +
                "iframeHtml=iframeHtml+\"<iframe sandbox='allow-scripts allow-same-origin allow-forms' " +
                "frameborder='0' allowfullscreen='true' webkitallowfullscreen='true' " +
                "mozallowfullscreen='true' src=\"+URL+\"></iframe>\";\n" +
                "}\n" +
                "document.write(iframeHtml);\n" +
                "</script>\n</body>\n</html>"
            )
    }
}
