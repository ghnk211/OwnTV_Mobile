package tv.own.owntv.mobile.tvbox.engine.js

import tv.own.owntv.mobile.tvbox.util.TextUtil
import java.net.URI

/**
 * drpy 宿主的 `joinUrl(base, path)`（对应 drpy2 的 `urljoin`，语义等同浏览器
 * `new URL(path, base)`）。
 *
 * ## 为什么必须宿主实现，不能用 JS 侧的 `new URL(path, base)`
 * QuickJS（wang.harlon.quickjs wrapper 内置的 QuickJS 内核）**没有 WHATWG `URL` 类** ——
 * `new URL(...)` 抛 ReferenceError 被 catch 吞掉，**静默返回裸 `path`**。
 *
 * 后果是 drpy2 在 `init()` 里做的规则归一化
 * （`rule.homeUrl = urljoin(rule.host, rule.homeUrl)`，同段还有 `rule.url` /
 * `rule.searchUrl` / `rule.detailUrl`）**全部等于没做**，相对路径原样留在规则里；
 * 随后 `getHome(url)` 执行 `url.split("//")[1].split("/")[0]` 对相对路径直接抛
 * `cannot read property 'split' of undefined` → home / homeVod 双空。
 *
 * 移植自 VOD `4c8d289:app/src/main/java/com/vod/player/engine/js/JsUrl.kt`。
 */
object JsUrl {

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")

    /**
     * @param base 基准地址（通常是 `rule.host`）
     * @param path 待拼接地址，可能是绝对地址 / 根相对 `/x` / 协议相对 `//host/x` / 相对 `x`
     */
    fun join(base: String?, path: String?): String {
        val b = base?.trim().orEmpty()
        val p = path?.trim().orEmpty()
        if (p.isEmpty()) return b
        if (b.isEmpty()) return p

        // 1) 自带协议（http/https/data/magnet/…）：原样返回，不拼接
        if (SCHEME.containsMatchIn(p)) return p

        // 2) 协议相对 //host/x：补 base 的协议（base 无协议时按 https 补）
        if (p.startsWith("//")) {
            val scheme = b.substringBefore("://").takeIf { it != b && it.isNotBlank() }
            return if (scheme.isNullOrBlank()) "https:$p" else "$scheme://${p.removePrefix("//")}"
        }

        // 3) 其余（根相对 / 相对 / 带 query）：交给 java.net.URI 的 RFC 2396 解析，
        //    它天然处理"替换末段"与 `.`/`..` 规整
        resolveByUri(b, p)?.let { return it }

        // 4) URI 解析不了（含空格/方括号等非法字符）：退回简化拼接，至少保证是绝对地址
        return TextUtil.absoluteUrl(p, b)
    }

    /**
     * 刻意**不做** `URI(...)` 的转义构造：drpy 规则里 `**` 是搜索关键字占位符、
     * `fyclass/fypage` 是分页占位符，一旦被 percent-encode 成 `%2A%2A`，
     * 后续 `url.replace('**', wd)` 就再也匹配不上，搜索会静默失效。
     * 所以直接按原串解析，解析不了再走 [TextUtil.absoluteUrl] 的简化拼接。
     */
    private fun resolveByUri(base: String, path: String): String? =
        runCatching { URI(base).resolve(URI(path)).toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != path }
}