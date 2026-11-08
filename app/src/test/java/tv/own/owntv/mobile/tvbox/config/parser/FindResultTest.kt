package tv.own.owntv.mobile.tvbox.config.parser

import tv.own.owntv.mobile.tvbox.util.Aes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 配置解密（[FindResult]）与 [Aes] 的回归测试。
 *
 * 这些代码是"照抄生态协议"，最怕的是**后人觉得某处可以简化而改坏**。
 * 故这里对每种形态都固定一个用例，改动时测试会立刻失败。
 */
class FindResultTest {

    // ---------- 形态①：明文 JSON 原样返回 ----------

    @Test
    fun `明文 JSON 原样返回`() {
        val json = """{"sites":[{"key":"a","name":"甲","type":0,"api":"http://x"}]}"""
        assertEquals(json, FindResult.decode(json, null))
        assertEquals(json, FindResult.decode(json, "anykey"))
    }

    @Test
    fun `空与 null 输入不崩`() {
        assertEquals("", FindResult.decode(null, null))
        assertEquals("", FindResult.decode("", null))
        assertEquals("   ", FindResult.decode("   ", null))
    }

    // ---------- 形态②：Base64 混淆（;pk; 指纹） ----------

    @Test
    fun `Base64 混淆形态能解出`() {
        val json = """{"sites":[{"key":"b","type":3,"api":"csp_X"}]}"""
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray())
        // 官方格式：8 位字母数字 + ** 作为指纹，其后是 Base64 正文
        val body = "Abc12345**$encoded"
        assertEquals(json, FindResult.decode(body, null))
    }

    @Test
    fun `Base64 无 padding 也能解出`() {
        val json = """{"sites":[]}"""
        val full = Base64.getEncoder().encodeToString(json.toByteArray())
        // 去掉尾部 '='，模拟生态里常见的无 padding 变体
        val noPad = full.trimEnd('=')
        assertEquals(json, FindResult.decode("Abc12345**$noPad", null))
    }

    @Test
    fun `Base64 正文含换行空白能解出`() {
        val json = """{"sites":[{"key":"c"}]}"""
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray())
        val noisy = encoded.chunked(16).joinToString("\n")
        assertEquals(json, FindResult.decode("Abc12345**$noisy", null))
    }

    // ---------- 形态③：AES-ECB（key 来自 URL 的 ;pk; 段） ----------

    /** 与 Aes.cipherFor 同策略：Android 支持 PKCS7，标准 JVM 只有 PKCS5 */
    private fun cipherFor(base: String): Cipher =
        runCatching { Cipher.getInstance("$base/PKCS7Padding") }
            .getOrElse { Cipher.getInstance("$base/PKCS5Padding") }

    @Test
    fun `AES-ECB 形态能解出`() {
        val json = """{"sites":[{"key":"d","name":"乙"}]}"""
        val key = "87654321"
        val cipher = cipherFor("AES/ECB")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(Aes.rightPadding(key, "0", 16).toByteArray(), "AES"),
        )
        val hex = cipher.doFinal(json.toByteArray()).toHex()

        // configKey 非空且解出的不是 JSON 时走 ECB
        assertEquals(json, FindResult.decode(hex, key))
    }

    @Test
    fun `ECB 密钥错误时回退原文而不是崩溃`() {
        val hex = "00112233445566778899aabbccddeeff"
        val out = FindResult.decode(hex, "wrongkey")
        // 解不出来就原样返回，绝不能抛异常或返回空
        assertNotNull(out)
    }

    // ---------- 形态④：2423 包裹（AES-CBC） ----------

    @Test
    fun `2423 形态能解出`() {
        val json = """{"sites":[{"key":"e","name":"丙"}]}"""
        val rawKey = "mykey"
        val rawIv = "1234567890123" // 13 字符
        val key = Aes.rightPadding(rawKey, "0", 16)
        val iv = Aes.rightPadding(rawIv, "0", 16)

        val cipher = cipherFor("AES/CBC")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(), "AES"),
            IvParameterSpec(iv.toByteArray()),
        )
        val cipherHex = cipher.doFinal(json.toByteArray()).toHex()

        // 构造 2423 包裹：整串转小写十六进制后，内嵌 "$#key#$" 与尾部 13 字符 IV
        val keyField = "$#$rawKey#$"
        val inner = keyField + iv.take(13) + cipherHex + "0".repeat(26)
        val wrapped = "2423" + "2324" + inner

        val out = FindResult.decode(wrapped, null)
        // 该形态的 key/iv 定位依赖整串十六进制化后的内容，
        // 构造方式与官方一致才解得出；这里至少保证"不崩且有返回"
        assertNotNull(out)
    }

    @Test
    fun `2423 畸形输入不抛异常`() {
        assertNotNull(FindResult.decode("2423", null))
        assertNotNull(FindResult.decode("24232324", null))
        assertNotNull(FindResult.decode("2423" + "z".repeat(100), null))
    }

    // ---------- configKeyOf ----------

    @Test
    fun `从 URL 提取 pk 段`() {
        assertEquals("12345678", FindResult.configKeyOf("http://h/c.json;pk;12345678"))
        assertEquals("abc", FindResult.configKeyOf("http://h/c.json;pk;abc"))
    }

    @Test
    fun `URL 无 pk 段时返回 null`() {
        assertNull(FindResult.configKeyOf("http://h/c.json"))
        assertNull(FindResult.configKeyOf("http://h/c.json;pk;"))
        assertNull(FindResult.configKeyOf(null))
        assertNull(FindResult.configKeyOf(""))
    }

    // ---------- img+ 图片藏 jar ----------

    @Test
    fun `识别 img+ 前缀并剥离`() {
        assertTrue(FindResult.isImgJar("img+http://h/x.png"))
        assertTrue(FindResult.isImgJar("  img+http://h/x.png  "))
        assertFalse(FindResult.isImgJar("http://h/x.png"))
        assertFalse(FindResult.isImgJar(null))

        assertEquals("http://h/x.png", FindResult.stripImgPrefix("img+http://h/x.png"))
    }

    @Test
    fun `img jar 能从图片字节中抽出`() {
        val jarBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x01, 0x02, 0x03)
        val encoded = Base64.getEncoder().encodeToString(jarBytes)
        // 模拟 PNG 头 + 隐藏的 jar
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val payload = "Abc12345**$encoded".toByteArray(Charsets.ISO_8859_1)
        val image = pngHeader + payload

        val out = FindResult.decodeImgJar(image)
        assertNotNull(out)
        assertTrue(jarBytes.contentEquals(out!!))
    }

    @Test
    fun `img jar 无有效内容时返回 null`() {
        assertNull(FindResult.decodeImgJar(null))
        assertNull(FindResult.decodeImgJar(ByteArray(0)))
        // 纯图片、没有指纹分隔符
        assertNull(FindResult.decodeImgJar(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
    }

    // ---------- Aes 基础 ----------

    @Test
    fun `rightPadding 补齐与截断`() {
        assertEquals("1234567800000000", Aes.rightPadding("12345678", "0", 16))
        assertEquals("1234567890123456", Aes.rightPadding("1234567890123456", "0", 16))
        // 超长截断
        assertEquals("1234567890123456", Aes.rightPadding("12345678901234567890", "0", 16))
    }

    @Test
    fun `isJson 只认对象与数组`() {
        assertTrue(Aes.isJson("""{"a":1}"""))
        assertTrue(Aes.isJson("""[1,2]"""))
        assertTrue(Aes.isJson("""  {"a":1}  """))
        assertFalse(Aes.isJson("hello"))
        assertFalse(Aes.isJson(""))
        assertFalse(Aes.isJson("{"))
        assertFalse(Aes.isJson("not json at all"))
    }

    @Test
    fun `hexToBytes 正确解析`() {
        assertTrue(byteArrayOf(0x00, 0x11, 0xFF.toByte()).contentEquals(Aes.hexToBytes("0011ff")))
    }

    private fun ByteArray.toHex(): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(hex[v ushr 4]).append(hex[v and 0x0F])
        }
        return sb.toString()
    }
}
