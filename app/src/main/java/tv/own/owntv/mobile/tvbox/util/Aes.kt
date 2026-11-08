package tv.own.owntv.mobile.tvbox.util

import tv.own.owntv.mobile.tvbox.util.TbLog

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES 加解密（对齐 TVBoxOS `com.github.tvbox.osc.util.AES`）。
 *
 * TVBox 生态的配置正文大量是加密的，解密算法固定，因此这里严格复刻官方实现——
 * 任何"改良"（换 padding、改编码）都会导致解出乱码，反而破坏兼容性。
 */
object Aes {

    /** 尾部补齐/截断到 [length] 长度；[replace] 不足时循环填充 */
    fun rightPadding(key: String, replace: String, length: Int): String {
        val trimmed = key.trim()
        return when {
            trimmed.length > length -> trimmed.substring(0, length)
            trimmed.length == length -> trimmed
            else -> trimmed + buildString {
                repeat(length - trimmed.length) { append(replace) }
            }
        }
    }

    /**
     * AES/ECB 解密；[data] 为十六进制字符串。
     *
     * ## padding 为什么两种都试
     * 官方实现写死 `PKCS7Padding`，那是因为它只跑在 Android（Conscrypt 支持）。
     * 但这段代码必须能在 **JVM 单元测试**里跑通，而标准 JVM（SunJCE）
     * 只提供 `PKCS5Padding`。
     * AES 分组长度固定 16 字节，PKCS5 与 PKCS7 在此**完全等价**，
     * 故先试官方写法、失败再退 PKCS5 —— 两端行为一致。
     *
     * ## 安全声明（L-1：审计接受保留，此处显式声明边界）
     * ECB 模式无 IV、相同明文块产生相同密文块，属弱模式 —— 但本方法
     * **只用于解密 TVBox 配置正文**，算法与模式由配置格式单方面规定：
     * - key 随加密配置一起明文下发，不构成本应用持有的秘密；
     * - 只有 `DECRYPT_MODE`，本应用**不产生**任何 ECB 密文；
     * - 实际风险是"配置格式变了导致解不开"，而非"本应用数据被破解"。
     * 因此按生态兼容保留，不做"改良"（换模式/padding 会直接解出乱码）。
     */
    fun ecb(data: String, key: String): String? = runCatching {
        val padded = rightPadding(key, "0", 16)
        val cipher = cipherFor("AES/ECB")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(padded.toByteArray(), "AES"))
        String(cipher.doFinal(hexToBytes(data)))
    }.getOrElse {
        TbLog.d("AES/ECB 解密失败：%s", it.message)
        null
    }

    /** AES/CBC 解密；[data] 为十六进制字符串。padding 兼容策略同 [ecb] */
    fun cbc(data: String, key: String, iv: String): String? = runCatching {
        val cipher = cipherFor("AES/CBC")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key.toByteArray(), "AES"),
            IvParameterSpec(iv.toByteArray()),
        )
        String(cipher.doFinal(hexToBytes(data)))
    }.getOrElse {
        TbLog.d("AES/CBC 解密失败：%s", it.message)
        null
    }

    /** 先试 Android 侧的 PKCS7Padding，不可用则退标准 JVM 的 PKCS5Padding */
    private fun cipherFor(base: String): Cipher =
        runCatching { Cipher.getInstance("$base/PKCS7Padding") }
            .getOrElse { Cipher.getInstance("$base/PKCS5Padding") }

    /** 十六进制字符串 → 字节数组（每 2 字符 1 字节） */
    fun hexToBytes(src: String): ByteArray {
        val len = src.length / 2
        return ByteArray(len) {
            Integer.parseInt(src.substring(it * 2, it * 2 + 2), 16).toByte()
        }
    }

    /** 轻量 JSON 有效性判断（只认对象/数组，与 org.json 的宽松解析等价） */
    fun isJson(content: String): Boolean {
        val t = content.trim()
        if (t.length < 2) return false
        val first = t.first()
        val last = t.last()
        if (!((first == '{' && last == '}') || (first == '[' && last == ']'))) return false
        return runCatching {
            com.google.gson.JsonParser.parseString(t)
            true
        }.getOrDefault(false)
    }

    /**
     * Base64 解码（**自实现，不依赖 android.util.Base64**）。
     *
     * ## 为什么要自己写
     * 1. `android.util.Base64` 在 JVM 单元测试里是空壳（未 returnDefaultValues 时直接抛），
     *    解密逻辑就无法被单测覆盖 —— 而这正是最该被测的部分。
     * 2. 生态配置里的 Base64 常**没有 padding**，且可能混有换行/空白。
     *    `android.util.Base64.DEFAULT` 遇到非法字符直接抛异常，
     *    而这里的正确行为是"跳过杂字符继续解"（官方实现同样容忍）。
     */
    fun decodeBase64(data: String): ByteArray? {
        if (data.isEmpty()) return ByteArray(0)
        val out = java.io.ByteArrayOutputStream(data.length * 3 / 4 + 3)
        val buf = IntArray(4)
        var bufLen = 0
        for (c in data) {
            // 必须显式判 >= 0：DECODE_TABLE 的"无效"标记是 -1（不是 null），
            // 用 `?: continue` 永远走不到，换行/空白会被当 -1 参与位运算污染结果。
            val code = c.code
            if (code >= DECODE_TABLE.size) continue
            val v = DECODE_TABLE[code]
            if (v < 0) continue
            buf[bufLen++] = v
            if (bufLen == 4) {
                out.write((buf[0] shl 2) or (buf[1] ushr 4))
                out.write(((buf[1] and 0xF) shl 4) or (buf[2] ushr 2))
                out.write(((buf[2] and 0x3) shl 6) or buf[3])
                bufLen = 0
            }
        }
        // 尾部残缺分组：按剩余字符数补齐（无 padding 变体的关键）
        when (bufLen) {
            2 -> out.write((buf[0] shl 2) or (buf[1] ushr 4))
            3 -> {
                out.write((buf[0] shl 2) or (buf[1] ushr 4))
                out.write(((buf[1] and 0xF) shl 4) or (buf[2] ushr 2))
            }
        }
        return out.toByteArray()
    }

    /** Base64 字母表 → 6bit 值；非表字符为 null（解码时跳过） */
    private val DECODE_TABLE = IntArray(128) { -1 }.also { t ->
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        alphabet.forEachIndexed { i, c -> t[c.code] = i }
    }
}
