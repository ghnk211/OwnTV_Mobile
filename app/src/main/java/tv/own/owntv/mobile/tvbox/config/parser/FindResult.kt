package tv.own.owntv.mobile.tvbox.config.parser

import tv.own.owntv.mobile.tvbox.util.TbLog

import tv.own.owntv.mobile.tvbox.util.Aes

/**
 * 配置正文解密（对齐 TVBoxOS `ApiConfig.FindResult`）。
 *
 * ## 为什么要解密
 * TVBox 生态的配置默认**加密分发**。直接当 JSON 解析会失败，表现为
 * "配置解析失败"，但实际上只是没解密。生态里同一 URL 的正文有 4 种封装：
 *
 * | 形态 | 判据 | 处理 |
 * |------|------|------|
 * | 明文 JSON | [Aes.isJson] | 原样返回 |
 * | `;pk;` 混淆 | 匹配 `[A-Za-z0-9]{8}\*\*` | 跳过分隔符后 Base64 解码 |
 * | `2423` 包裹 | 以 `2423` 开头 | 抽 key/iv 后 AES-CBC 解密 |
 * | 整体 AES | 上述都不命中且 URL 带 `;pk;` | 用 URL 的 key 做 AES-ECB |
 *
 * ## 为什么严格复刻而非"优化"
 * 这些格式是壳作者约定的，padding（PKCS7）、编码（`new String` 平台默认）、
 * 截取下标（`length-26`、`length-13`）任何一处改动都会解出乱码。
 * 故此处逐行对齐官方实现，包括看起来很怪的 `2324`/`$#`/`#$` 定位方式。
 */
object FindResult {

    /** 8 位字母数字 + `**` 分隔符，是 Base64 混淆形态的指纹 */
    private val MARKER = Regex("[A-Za-z0-9]{8}\\*\\*")

    /** 形如 `http://host/config.json;pk;12345678` → 取 `;pk;` 后的字符串作 AES key */
    fun configKeyOf(apiUrl: String?): String? {
        if (apiUrl.isNullOrBlank()) return null
        val parts = apiUrl.split(";pk;")
        return parts.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    /**
     * @param json 原始响应正文
     * @param configKey URL 中 `;pk;` 之后的 key；为空则跳过 AES-ECB 分支
     * @return 解密后的 JSON 文本；解密失败时原样返回（交由上层报解析错误）
     */
    fun decode(json: String?, configKey: String?): String {
        if (json.isNullOrBlank()) return json.orEmpty()
        var content = json

        // ① 已是明文 JSON —— 绝大多数情况
        if (Aes.isJson(content)) return content

        try {
            // ② ;pk; 混淆：Base64 编码的 JSON
            val matcher = MARKER.find(content)
            if (matcher != null) {
                content = content.substring(content.indexOf(matcher.value) + 10)
                val decoded = Aes.decodeBase64(content)
                if (decoded != null) {
                    content = String(decoded)
                } else {
                    TbLog.w("配置 Base64 解码失败，按原文继续")
                    return json
                }
            }

            content = content.trim()

            // ③ 2423 包裹：AES-CBC
            if (content.startsWith("2423")) {
                content = content.replace(Regex("\\s+"), "")
                // 密文区：从 "2324" 之后 4 位起，到末尾前 26 位
                val data = content.substring(content.indexOf("2324") + 4, content.length - 26)
                // 整串当十六进制转字节再转字符串（key/iv 就藏在这个小写十六进制串里）
                val hexText = String(Aes.hexToBytes(content)).lowercase()
                val keyStart = hexText.indexOf("$#") + 2
                val keyEnd = hexText.indexOf("#$")
                val key = Aes.rightPadding(hexText.substring(keyStart, keyEnd), "0", 16)
                val iv = Aes.rightPadding(hexText.substring(hexText.length - 13), "0", 16)
                Aes.cbc(data, key, iv)?.let { return it } ?: run {
                    TbLog.w("配置 2423 形态 AES-CBC 解密失败")
                    return json
                }
            }

            // ④ 整体 AES-ECB：key 来自 URL 的 ;pk; 段
            if (!configKey.isNullOrBlank() && !Aes.isJson(content)) {
                Aes.ecb(content, configKey)?.let { return it }
                TbLog.w("配置 AES-ECB 解密失败（key=%s）", configKey.take(8))
                return json
            }

            return content
        } catch (t: Throwable) {
            // 解密是"尽力而为"，任何异常都不该让整次导入崩掉
            TbLog.w("配置解密异常，回退原文：%s", t.message)
            return json
        }
    }

    /**
     * `img+` 前缀：从图片正文中抽出隐藏的 jar 字节。
     *
     * 生态里部分源把 jar 伪装成图片：URL 以 `img+` 开头，
     * 响应体是 PNG/JPEG 图片，二进制尾部（图片渲染无关区域）塞了
     * `xxxxxxxx**` + Base64 的真实 jar。DexClassLoader 不能直接吃图片，
     * 必须先按官方规则抽出来。
     */
    fun decodeImgJar(body: ByteArray?): ByteArray? {
        if (body == null || body.isEmpty()) return null
        // 二进制里直接找分隔符；官方在文本形态用正则，这里对字节做同样的定位
        val text = String(body, Charsets.ISO_8859_1)
        val idx = MARKER.find(text)?.let { text.indexOf(it.value) } ?: return null
        val payload = text.substring(idx + 10)
        val decoded = Aes.decodeBase64(payload) ?: return null
        return decoded.takeIf { it.size > 0 }
    }

    /** URL 是否为 img+ 图片藏 jar 形态 */
    fun isImgJar(url: String?): Boolean = url?.trim()?.startsWith("img+") == true

    /** 去掉 `img+` 前缀得到真实下载地址 */
    fun stripImgPrefix(url: String): String = url.trim().removePrefix("img+")
}
