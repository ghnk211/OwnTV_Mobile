package tv.own.owntv.mobile.tvbox.engine.js

import tv.own.owntv.mobile.tvbox.util.Md5Util
import java.io.File

/**
 * JS 模块与字节码的磁盘缓存。
 *
 * 三件事缺一不可：
 * 1. **7 天 TTL** —— 远程脚本会更新，永久缓存等于把源钉死在旧版本。
 * 2. **字节码缓存** —— drpy 运行时约 708KB，每次冷启动现编译要数秒。
 * 3. 缓存键用模块 URL 的 md5 —— URL 里带中文和 query，直接做文件名不合法。
 *
 * 移植自 VOD `4c8d289:app/src/main/java/com/vod/player/engine/js/JsModuleCache.kt`。
 */
class JsModuleCache(private val dir: File) {

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    private fun srcFile(name: String) = File(dir, "${Md5Util.md5(name)}$SRC_EXT")
    private fun binFile(name: String) = File(dir, "${Md5Util.md5(name)}$BIN_EXT")
    private fun depsFile(name: String) = File(dir, "${Md5Util.md5(name)}$DEPS_EXT")

    /** 未过期返回缓存的源码，否则 null */
    fun readSource(name: String): String? {
        val f = srcFile(name)
        if (!isFresh(f)) return null
        return runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
    }

    fun writeSource(name: String, source: String) {
        runCatching { srcFile(name).writeText(source, Charsets.UTF_8) }
    }

    /**
     * 读取字节码。仅当源码缓存也未过期时才认 —— 否则会出现
     * "源已经更新、却还在用旧字节码"的幽灵 bug。
     */
    fun readBytecode(name: String): ByteArray? {
        val bin = binFile(name)
        val src = srcFile(name)
        if (!bin.exists() || bin.length() == 0L) return null
        if (!isFresh(src)) return null
        return runCatching { bin.readBytes() }.getOrNull()
    }

    fun writeBytecode(name: String, bytes: ByteArray) {
        runCatching { binFile(name).writeBytes(bytes) }
    }

    /**
     * runtime 的静态 import 依赖清单：落盘后第二次起可以「runtime 与依赖同时开跑」，
     * 不再付「先下 runtime 再下依赖」的串行代价。与源码同 7 天 TTL。
     */
    fun readDeps(name: String): List<String>? {
        val f = depsFile(name)
        if (!isFresh(f)) return null
        return runCatching { f.readLines(Charsets.UTF_8) }
            .getOrNull()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
    }

    /** 仅在非空时落盘：空清单不写（isFresh 判空文件为过期，写了也没用） */
    fun writeDeps(name: String, deps: List<String>) {
        if (deps.isEmpty()) return
        runCatching { depsFile(name).writeText(deps.joinToString("\n"), Charsets.UTF_8) }
    }

    fun invalidate(name: String) {
        runCatching { srcFile(name).delete() }
        runCatching { binFile(name).delete() }
        runCatching { depsFile(name).delete() }
    }

    private fun isFresh(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return System.currentTimeMillis() - file.lastModified() < TTL_MS
    }

    companion object {
        private const val SRC_EXT = ".js"
        private const val BIN_EXT = ".bin"
        private const val DEPS_EXT = ".deps"
        private const val TTL_MS = 7 * 24 * 60 * 60 * 1000L
    }
}