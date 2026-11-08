package tv.own.owntv.mobile.tvbox.engine

/**
 * 纯 JVM 的 Base64 解码。
 *
 * ## 为什么不直接用 android.util.Base64
 * 那是 Android 框架类，在本地 JVM 单测里是桩实现，调用会抛
 * `RuntimeException: Stub!`。而「这段字符串是不是密文」这个判定逻辑
 * 恰恰是最需要单测覆盖的（判定错��会把能播的源弄坏），
 * 所以解码必须能在 JVM 里跑。
 *
 * 同时支持标准表（`+/`）与 URL_SAFE 表（`-_`）——
 * 实测不同源用的表不一样，只认一种会漏掉另一半。
 */
internal object Base64Compat {

    private const val STD =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val URLSAFE =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** 两张表的值 -> 6 bit 值；同一字符在两张表里值相同，分开建表省一次分支 */
    private val lookup = HashMap<Char, Int>(128).apply {
        for (i in 0 until STD.length) {
            put(STD[i], i)
            put(URLSAFE[i], i)
        }
    }

    /**
     * 解码。
     *
     * @return 字节数组；输入含非法字符 / 长度非法时返回 null（不抛异常）
     */
    fun decode(input: String): ByteArray? {
        if (input.isEmpty()) return ByteArray(0)

        // 去掉 padding（末尾连续的 =），padding 数量本身不参与解码
        var end = input.length
        while (end > 0 && input[end - 1] == '=') end--
        val body = input.substring(0, end)

        val outSize = body.length * 6 / 8
        val out = ByteArray(outSize)

        var buffer = 0
        var bits = 0
        var outIndex = 0

        for (c in body) {
            val v = lookup[c] ?: return null // 非法字符
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                if (outIndex >= outSize) return null // 长度不合法
                out[outIndex++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }

        // 余下的比特必须全为 0（合法的 Base64 不会留无效位）
        if (bits >= 6) return null
        if (bits > 0 && (buffer and ((1 shl bits) - 1)) != 0) return null

        return out
    }

    /**
     * 编码（测试用：验证 decode 的正确性需要能造出密文）。
     *
     * @param noPadding true 时输出 URL_SAFE 且不带 `=` —— 部分源就是这么发的
     */
    fun encode(data: ByteArray, noPadding: Boolean = false): String {
        if (data.isEmpty()) return ""
        val table = if (noPadding) URLSAFE else STD
        val sb = StringBuilder((data.size + 2) / 3 * 4)

        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            sb.append(table[(n shr 18) and 63])
            sb.append(table[(n shr 12) and 63])
            sb.append(table[(n shr 6) and 63])
            sb.append(table[n and 63])
            i += 3
        }

        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                sb.append(table[(n shr 18) and 63])
                sb.append(table[(n shr 12) and 63])
                if (!noPadding) sb.append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or
                    ((data[i + 1].toInt() and 0xFF) shl 8)
                sb.append(table[(n shr 18) and 63])
                sb.append(table[(n shr 12) and 63])
                sb.append(table[(n shr 6) and 63])
                if (!noPadding) sb.append('=')
            }
        }

        return sb.toString()
    }
}
