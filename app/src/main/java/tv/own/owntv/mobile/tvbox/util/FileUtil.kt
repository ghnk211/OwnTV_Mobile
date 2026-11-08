package tv.own.owntv.mobile.tvbox.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.Locale

/** 应用私有目录工具：TVbox 的配置/jar 缓存统一落在 `files/tvbox/` 下，MODE_PRIVATE。 */
object FileUtil {

    /** TVbox 数据根目录（与 Core 的数据文件互不重叠）。 */
    fun tvboxDir(context: Context): File =
        File(context.filesDir, "tvbox").apply { if (!exists()) mkdirs() }

    fun subDir(context: Context, name: String): File =
        File(tvboxDir(context), name).apply { if (!exists()) mkdirs() }

    /** 配置原始文本缓存目录 */
    fun configDir(context: Context) = subDir(context, "configs")

    /** spider jar 缓存目录（按 md5 命名） */
    fun spiderDir(context: Context) = subDir(context, "spiders")

    fun readText(file: File): String? = runCatching {
        if (file.exists()) file.readText(Charsets.UTF_8) else null
    }.getOrNull()

    fun writeText(file: File, text: String): Boolean = runCatching {
        file.parentFile?.mkdirs()
        // 先写临时文件再改名，避免半截文件被当作有效缓存
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (file.exists()) {
            check(file.setWritable(true)) { "无法解除文件只读属性：${file.absolutePath}" }
            check(file.delete()) { "无法删除旧文件：${file.absolutePath}" }
        }
        tmp.renameTo(file)
    }.getOrDefault(false)

    /** 二进制写入（jar 下载缓存），同样走 tmp → rename */
    fun writeBytes(file: File, bytes: ByteArray): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (file.exists()) {
            check(file.setWritable(true)) { "无法解除文件只读属性：${file.absolutePath}" }
            check(file.delete()) { "无法删除旧文件：${file.absolutePath}" }
        }
        tmp.renameTo(file)
    }.getOrDefault(false)

    fun deleteQuietly(file: File?) {
        runCatching { if (file?.exists() == true) file.delete() }
    }

    /** 清理目录但保留目录本身 */
    fun clearDir(dir: File) {
        runCatching { dir.listFiles()?.forEach { deleteRecursively(it) } }
    }

    fun deleteRecursively(file: File) {
        runCatching {
            if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
            file.delete()
        }
    }

    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
        else -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024 / 1024)
    }

    /** 从 SAF 的 content:// Uri 读取文本（配置本地文件导入用） */
    fun readTextFromUri(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
    }.getOrNull()
}
