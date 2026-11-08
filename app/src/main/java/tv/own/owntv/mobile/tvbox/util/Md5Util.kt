package tv.own.owntv.mobile.tvbox.util

import java.io.File
import java.security.MessageDigest

/**
 * 摘要校验：spider jar 与配置缓存防篡改（方案 12.4）。
 *
 * M-2/L-2：配置里的校验段从 MD5 升级为**兼容 MD5 与 SHA-256**：
 * - 存量配置（`;md5;` 32 位十六进制）继续按 MD5 校验，生态不破；
 * - 新配置可声明 64 位十六进制（SHA-256），抗碰撞。
 * 注意（文档复杂度提示）：校验段本身还可能是远端 HTTP 地址
 * （`url;md5;http://host/md5.txt`，见 [RemoteArtifact.md5IsRemote]），
 * 服务端下发协议需同步升级，并非单侧改动。
 */
object Md5Util {

    fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).toHex()

    fun md5(text: String): String = md5(text.toByteArray(Charsets.UTF_8))

    fun md5(file: File): String? = runCatching {
        file.inputStream().use { md5(it.readBytes()) }
    }.getOrNull()

    /** SHA-256（M-2/L-2：防"恶意构造"用，MD5 只够防"下载损坏"） */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    fun sha256(file: File): String? = runCatching {
        file.inputStream().use { sha256(it.readBytes()) }
    }.getOrNull()

    fun ByteArray.toHex(): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(hex[v ushr 4]).append(hex[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * 校验配置里 "url;md5;hash" / "url;sha256;hash" 形式的 spider 声明（字节级）。
     *
     * 按期望值长度分流：64 位十六进制按 SHA-256 比，其余按 MD5 比
     * （存量配置与落盘文件名仍以 md5 命名，语义不变）。
     */
    fun verifyAgainst(bytes: ByteArray, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return true
        val want = expected.trim().lowercase()
        val actual = when (want.length) {
            64 -> sha256(bytes).lowercase()
            else -> md5(bytes).lowercase()
        }
        return actual == want
    }

    /** 文件版 [verifyAgainst]（读取失败视为校验不通过） */
    fun verifyAgainst(file: File, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return true
        val want = expected.trim().lowercase()
        val actual = when (want.length) {
            64 -> sha256(file)?.lowercase()
            else -> md5(file)?.lowercase()
        } ?: return false
        return actual == want
    }
}

/** 把 "url;md5;hash" / "url1;md5;h1;;url2;md5;h2" 拆成带校验的候选源 */
data class RemoteArtifact(
    val url: String,
    val md5: String?,
) {
    /** md5 段是否是一个远端地址（需下载时动态取回真实校验值） */
    val md5IsRemote: Boolean get() = md5?.startsWith("http") == true

    /** 可直接用于比对的固定 md5；远端形态返回 null */
    val fixedMd5: String? get() = md5?.takeIf { !md5IsRemote }
}

object SpiderSpec {
    /**
     * 解析配置中的 spider 字段。
     * 格式：每段以 ;; 分隔，段内 url;md5;hash；
     * 兼容"只有 url"、"url;md5"、"url;md5;hash"、"url;;hash" 等变体。
     *
     * ## md5 段本身可能是 http 地址
     * 部分配置把校验值做成远端动态下发（`url;md5;http://host/md5.txt`），
     * 用于免硬编码 + 防篡改。原实现用 `!it.startsWith("http")` 把这种段直接丢掉，
     * 导致 `md5 == null` → 跳过校验。这里保留 URL 形态，
     * 交由 [RemoteArtifact.md5IsRemote] 在下载时动态取回。
     *
     * ## M-2/L-2：校验段同时接受 64 位十六进制（SHA-256）
     * 长度分流在 [Md5Util.verifyAgainst]；这里只负责"像不像一个摘要值"。
     */
    fun parse(spiderField: String?): List<RemoteArtifact> {
        if (spiderField.isNullOrBlank()) return emptyList()
        return spiderField.split(";;")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { segment ->
                val parts = segment.split(";").map { it.trim() }
                val url = parts.firstOrNull().orEmpty()
                if (url.isEmpty()) return@mapNotNull null
                // 段内第二位若像摘要（32 位 md5 或 64 位 sha-256）则视为校验值
                val maybeHash = parts.getOrNull(1)?.takeIf { it.matches(HASH_RE) }
                // 段内第三位或第二位（当第二位非摘要时）也可能放校验；http 形态保留待动态取回
                val third = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                val hash = third ?: maybeHash
                RemoteArtifact(url = url, md5 = hash)
            }
            .distinctBy { it.url }
    }

    /** 摘要值形态：32 位（MD5）或 64 位（SHA-256）十六进制 */
    private val HASH_RE = Regex("^[0-9a-fA-F]{32}$|^[0-9a-fA-F]{64}$")
}
