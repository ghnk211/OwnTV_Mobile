package tv.own.owntv.mobile.tvbox.util

import tv.own.owntv.mobile.tvbox.util.TbLog

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File
import java.net.URLDecoder

/**
 * 本地 / 局域网配置源（对齐 TVBoxOS 的 `clan://` 协议）。
 *
 * ## 生态里到底有哪几种写法
 * TVBoxOS 把"本地导入"做成了**一套地址前缀**，用户在配置地址栏里填什么都能用：
 *
 * | 写法 | 含义 | 参考项目落点 |
 * |------|------|-------------|
 * | `clan://localhost/xx/config.json` | 本机外部存储根下的文件 | `RemoteServer` 的 `/file/` 路由 |
 * | `clan://192.168.1.9/config.json` | **另一台设备**上 TVBox 的内置服务 | `http://192.168.1.9/file/config.json` |
 * | `file:///sdcard/xx/config.json` | 本机绝对路径 | 先规范成 `clan://localhost/` |
 * | `/sdcard/xx/config.json` | 裸绝对路径 | 同上 |
 * | `content://...` | SAF / ContentProvider | 系统文件选择器 |
 *
 * 我们**不照抄 NanoHTTPD**：参考项目把本地文件暴露成 http 是为了让下游的
 * `OkGo.get()` 不用改；我们直接在这里把前缀解析掉，读文件与走网络分两条路，
 * 更省事也更安全（不用在手机上常开一个明文 HTTP 服务）。
 *
 * 真正必须照抄的是**语义**：`clan://localhost/` = 外部存储根，
 * `clan://<非localhost>/` = 另一台设备的 `/file/` 服务。这两条决定了
 * 生态里现成的"本地版配置"能不能直接用。
 */
sealed interface LocalSource {

    /** 用户填的原始地址（落库、报错、相对路径推导都用它） */
    val raw: String

    /**
     * 配置文件所在目录的前缀，用于修正正文里的 `./` 相对路径
     * （对齐 TVBoxOS `ApiConfig.fixContentPath`）。推导不出来时为 null。
     */
    val dirPrefix: String?

    /** 本机文件（file://、裸绝对路径、clan://localhost/） */
    data class LocalFile(val file: File, override val raw: String) : LocalSource {
        override val dirPrefix: String?
            get() = file.parentFile?.let { "file://${it.absolutePath.trimEnd('/')}/" }
    }

    /**
     * 另一台设备的 TVBox 内置服务。
     *
     * 生态用法：家里一台盒子装了 TVBox 并开着 RemoteServer（端口 9978），
     * 手机上填 `clan://192.168.1.9/config.json` 就能**直接用盒子上的那份配置和 jar**，
     * 不用再传文件。参考项目 `clanToAddress()` 的 else 分支就是这个语义。
     */
    data class RemoteClan(val httpUrl: String, override val raw: String) : LocalSource {
        override val dirPrefix: String
            get() = httpUrl.substring(0, httpUrl.lastIndexOf('/') + 1)
    }

    /** SAF / ContentProvider Uri */
    data class Content(val uri: Uri, override val raw: String) : LocalSource {
        // content:// 的"目录"没有稳定的字符串形式，交给调用方按原 Uri 处理
        override val dirPrefix: String? get() = null
    }
}

/** 读本地文件的结果。**必须**区分"不存在"与"没权限" —— 两者的处置完全不同。 */
sealed interface LocalRead {
    data class Ok(val bytes: ByteArray) : LocalRead

    data class Fail(val kind: FailKind, val message: String) : LocalRead
}

/**
 * - [NOT_FOUND]：路径确实不存在 → 让用户检查地址
 * - [NO_PERMISSION]：文件在，但 Android 11+ 分区存储不给读 → **引导授权**，不是重试能解决的
 * - [IO]：别的读写错误
 */
enum class FailKind { NOT_FOUND, NO_PERMISSION, IO }

object LocalSourceResolver {

    private const val TAG = "LocalSource"

    /** `clan://` 协议头长度 */
    private const val CLAN = "clan://"

    /** clan://localhost/ 的落点：外部存储根目录（与参考项目 `RemoteServer` 一致） */
    fun externalRoot(): File = Environment.getExternalStorageDirectory()

    /**
     * 解析用户填的地址。
     *
     * @param root 外部存储根，仅用于 `clan://localhost/`。抽成参数是为了单测
     *   能注入假路径 —— 真机上 [externalRoot] 依赖 Android 环境。
     * @return 本地/局域网形态；普通 http(s) 返回 null，交由网络层处理
     */
    fun parse(input: String, root: File = externalRoot()): LocalSource? {
        val s = input.trim()
        if (s.isBlank()) return null

        when {
            s.startsWith("content://", ignoreCase = true) -> {
                val uri = runCatching { Uri.parse(s) }.getOrNull() ?: return null
                return LocalSource.Content(uri, s)
            }

            s.startsWith(CLAN, ignoreCase = true) -> return parseClan(s, root)

            s.startsWith("file://", ignoreCase = true) -> {
                val path = s.removePrefix("file://")
                    .substringBefore('?')
                    .substringBefore('#')
                val decoded = runCatching { URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
                if (decoded.isBlank()) return null
                return LocalSource.LocalFile(File(decoded), s)
            }

            // 裸绝对路径（/sdcard/… 、/storage/emulated/0/…）
            s.startsWith("/") -> return LocalSource.LocalFile(File(s), s)

            // http/https 及其他（如 proxy://）交给网络层
            else -> return null
        }
    }

    /**
     * `clan://` 的两种语义，必须分开。
     *
     * - `clan://localhost/<path>`（也接受 `clan:///<path>`，host 为空）→ 本机外部存储根
     * - `clan://<host>/<path>` → 那台设备上 TVBox 的 `/file/` 服务
     *
     * 第二种是真实存在的用法（一台盒子共享配置给全家设备），
     * 只实现 localhost 会让它退化成"地址填错了"，排查起来很费劲。
     */
    private fun parseClan(s: String, root: File): LocalSource? {
        val rest = s.substring(CLAN.length)
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        val host = rest.substring(0, slash)
        val path = rest.substring(slash + 1)
        if (path.isBlank()) return null

        if (host.isBlank() || host.equals("localhost", ignoreCase = true) ||
            host == "127.0.0.1"
        ) {
            return LocalSource.LocalFile(File(root, path), s)
        }
        return LocalSource.RemoteClan("http://$host/file/$path", s)
    }

    /** 是否本地/局域网形态（供 spider 字段、站点 jar 字段判断走哪条路） */
    fun isLocalPath(input: String?): Boolean {
        val s = input?.trim().orEmpty()
        if (s.isBlank()) return false
        return s.startsWith("content://", true) ||
            s.startsWith(CLAN, true) ||
            s.startsWith("file://", true) ||
            s.startsWith("/")
    }

    // ---------------- 读取 ----------------

    /**
     * 读本地源的全部字节。
     *
     * 判定"没权限"靠 [File.canRead] 与父目录可读性：Android 11+ 分区存储下，
     * `/sdcard/xx.json` 的 `exists()` 可能因为父目录不可列举而返回 false，
     * 此时报 NOT_FOUND 会误导用户去改地址 —— 其实是需要授权。
     *
     * @param treeUri 用户通过 `ACTION_OPEN_DOCUMENT_TREE` 授权的目录。
     *   直读失败时用它降级（无需 MANAGE_EXTERNAL_STORAGE），见 [readViaTree]。
     */
    fun read(context: Context, src: LocalSource, treeUri: Uri? = null): LocalRead = when (src) {
        is LocalSource.Content -> readUri(context, src.uri)
        is LocalSource.LocalFile -> readFileWithFallback(context, src.file, treeUri)
        // RemoteClan 是 http，不应走到这里
        is LocalSource.RemoteClan -> LocalRead.Fail(FailKind.IO, "RemoteClan 应走网络层：${src.httpUrl}")
    }

    private fun readFileWithFallback(context: Context, file: File, treeUri: Uri?): LocalRead {
        val direct = readFile(file)
        if (direct is LocalRead.Ok) return direct
        if (treeUri == null) return direct

        val viaTree = readViaTree(context, file, externalRoot(), treeUri)
        if (viaTree is LocalRead.Ok) return viaTree
        // 两条路都没成：返回直读那条，它的信息（"没权限"/"不存在"）更可行动
        return direct
    }

    /**
     * 用 SAF 目录树授权读取外部存储根下的文件。
     *
     * ## 为什么要有这条路
     * `clan://localhost/` 语义是外部存储根，而 Android 11+ 分区存储下
     * 只有 `MANAGE_EXTERNAL_STORAGE` 能让 File API 直读那里。
     * 但那是个高危权限，很多用户不愿意给、部分 ROM 也拿不到。
     * `ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission` 是
     * **不需要任何权限声明**的等价能力：用户把文件夹授权给我们，
     * 之后就能一直读。授权一次，永久生效（跨进程重启也保留）。
     *
     * ## 为什么要逐级尝试剥离前缀
     * 用户授权的可能是**深层目录**（比如直接选中 `jihe666` 而不是 `/sdcard`）。
     * 此时 `clan://localhost/jihe666/jihe.json` 换算出的相对路径是
     * `jihe666/jihe.json`，但从授权根目录找它要找的是 `jihe.json`。
     * 让用户在"授权哪一级"上做选择题是不合理的 —— 逐级剥离自动适配。
     */
    fun readViaTree(context: Context, file: File, root: File, treeUri: Uri): LocalRead {
        val full = file.absolutePath.removePrefix(root.absolutePath).trim('/')
        if (full.isBlank()) {
            return LocalRead.Fail(FailKind.NOT_FOUND, "路径不在外部存储根下：${file.absolutePath}")
        }

        val segments = full.split('/').filter { it.isNotBlank() }
        var lastReason: LocalRead.Fail? = null
        for (drop in segments.indices) {
            val rel = segments.drop(drop)
            when (val r = findInTree(context, treeUri, rel)) {
                is LocalRead.Ok -> return r
                is LocalRead.Fail -> lastReason = r
            }
        }
        return lastReason
            ?: LocalRead.Fail(FailKind.NOT_FOUND, "授权目录下找不到：$full")
    }

    private fun findInTree(context: Context, treeUri: Uri, rel: List<String>): LocalRead {
        var current = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
            ?: return LocalRead.Fail(FailKind.NOT_FOUND, "授权目录不可访问")
        for (seg in rel) {
            current = current.findFile(seg)
                ?: return LocalRead.Fail(FailKind.NOT_FOUND, "授权目录下找不到：${rel.joinToString("/")}")
        }
        if (!current.isFile) {
            return LocalRead.Fail(FailKind.NOT_FOUND, "不是一个文件：${rel.joinToString("/")}")
        }
        return try {
            val bytes = context.contentResolver.openInputStream(current.uri)?.use { it.readBytes() }
            if (bytes == null) {
                LocalRead.Fail(FailKind.NOT_FOUND, "无法打开：${rel.joinToString("/")}")
            } else {
                LocalRead.Ok(bytes)
            }
        } catch (e: SecurityException) {
            LocalRead.Fail(FailKind.NO_PERMISSION, "目录授权已失效，请重新授权文件夹")
        } catch (e: Exception) {
            LocalRead.Fail(FailKind.IO, e.message ?: "读取失败")
        }
    }

    private fun readUri(context: Context, uri: Uri): LocalRead = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null) {
            LocalRead.Fail(FailKind.NOT_FOUND, "无法打开：${short(uri.toString())}")
        } else {
            LocalRead.Ok(bytes)
        }
    } catch (e: SecurityException) {
        // SAF 授权被回收（用户清了数据 / 授权过期）是常见情况
        TbLog.tag(TAG).w( "Uri 授权失效：%s", e.message)
        LocalRead.Fail(FailKind.NO_PERMISSION, "文件授权已失效，请重新选择文件")
    } catch (e: Exception) {
        LocalRead.Fail(FailKind.IO, e.message ?: "读取失败")
    }

    private fun readFile(file: File): LocalRead {
        if (!file.exists()) {
            val parentUsable = runCatching { file.parentFile?.canRead() == true }.getOrDefault(false)
            return if (parentUsable) {
                LocalRead.Fail(FailKind.NOT_FOUND, "文件不存在：${file.absolutePath}")
            } else {
                // 父目录都读不了 —— 典型分区存储限制，报"没权限"才有用
                LocalRead.Fail(FailKind.NO_PERMISSION, "无法访问该路径（需要先授予文件访问权限）：${file.absolutePath}")
            }
        }
        if (!file.canRead()) {
            return LocalRead.Fail(FailKind.NO_PERMISSION, "无读取权限：${file.absolutePath}")
        }
        return try {
            LocalRead.Ok(file.readBytes())
        } catch (e: SecurityException) {
            LocalRead.Fail(FailKind.NO_PERMISSION, "系统拒绝读取：${e.message}")
        } catch (e: Exception) {
            LocalRead.Fail(FailKind.IO, e.message ?: "读取失败")
        }
    }

    // ---------------- 正文修正（对齐 TVBoxOS fixContentPath） ----------------

    /**
     * 把配置正文里的 `./` 相对路径补成绝对地址。
     *
     * ## 为什么必须做
     * 生态里的本地配置普遍用相对路径引用同目录的 spider.jar：
     * ```json
     * { "spider": "./spider.jar", "sites": [...] }
     * ```
     * 不修正的话 jar 地址就是 `./spider.jar`，**没有任何一层能解析它**，
     * 表现为"配置导入成功，但所有 csp_ 源都报找不到 jar"。
     *
     * 对齐参考项目：只有正文里出现 `"./` 才动手（避免误伤 URL 里的 `./`），
     * 且替换成**配置文件所在目录**的前缀。
     */
    fun fixContentPath(content: String, dirPrefix: String?): String {
        if (dirPrefix.isNullOrBlank()) return content
        if (!content.contains("\"./")) return content
        // 只替换「引号后紧跟 ./」的形态（值以相对路径开头），
        // 不能无差别 replace("./") —— 值中间出现的 ./（如 https://a.com/./b）会被误伤
        return content.replace(Regex("\"\\./"), "\"$dirPrefix")
    }

    /**
     * http(s) 配置地址 → 目录前缀（对齐本地形态的 [LocalSource.dirPrefix]）。
     *
     * ## 为什么需要
     * 生态里的远程配置同样大量使用 `./` 引用同目录资源（spider jar、脚本、直播源）。
     * 本地形态有 [LocalSource.dirPrefix]，而 http 形态此前没有对应概念，
     * `fixContentPath` 收到 null 直接原样返回 —— 表现为"导入成功但所有相对资源都 404"。
     *
     * `https://host/path/fun.json` → `https://host/path/`（保留末尾斜杠供拼接）。
     * 非 http(s) 或无法定位 `/` 时返回 null（本地形态仍走 [LocalSource.dirPrefix]）。
     */
    fun httpDirPrefix(url: String): String? {
        val s = url.trim()
        if (!s.startsWith("http", ignoreCase = true)) return null
        val base = s.substringBefore('?').substringBefore('#')
        val i = base.lastIndexOf('/')
        // 没有路径分隔符（如 https://host）时无目录可推导
        if (i <= base.indexOf("://") + 2) return null
        return base.substring(0, i + 1)
    }

    /**
     * 把 spider/jar 字段里的相对地址按**配置来源 URL**解析为绝对地址（使用点兜底）。
     *
     * ## 为什么不能只靠导入期的 [fixContentPath]
     * - 修复逻辑上线**前**入库的旧配置，正文里的 `./` 从未被改写，DB 里存的是相对值；
     * - "网源转存为本地文件"的配置（先浏览器下载再本地导入）没有来源 URL，导入期改写不了；
     * - 裸相对形态（`"spider": "jar/a.jar"`，不带 `./`）正则一直不覆盖。
     * 三种情况都会让 [ensureSiteJar]/downloadSpider 把相对串当 URL 交给 OkHttp，
     * 表现为"配置导入成功但 csp 站全部找不到实现类"。
     *
     * 规则（目录相对语义，对齐 TVBoxOS parseJar）：
     * - 已含 `://`（http/https/clan/file/content/proxy://）、本地绝对路径（`/`开头，
     *   `/sdcard` 等 —— 无法与根相对 `/x` 区分，按既有 [isLocalPath] 语义保持原样）、
     *   含 `;`（调用方未拆段的 `url;md5;hash` 形态）→ 原样返回；
     * - base 非 http(s) 或为空 → 原样返回（无来源可依，宁缺勿错拼）；
     * - `./x` 与裸 `x` → 配置文件所在目录 + x（[httpDirPrefix] 已剥 query/fragment）。
     */
    fun resolveAgainst(url: String?, baseUrl: String?): String {
        val u = url?.trim().orEmpty()
        if (u.isEmpty() || u.contains(';')) return u
        if (u.contains("://")) return u
        if (isLocalPath(u)) return u
        val b = baseUrl?.trim().orEmpty()
        if (!b.startsWith("http://", true) && !b.startsWith("https://", true)) return u
        val dir = httpDirPrefix(b) ?: return u
        return if (u.startsWith("./")) dir + u.substring(2) else dir + u
    }

    // ---------------- 权限 ----------------

    /**
     * 是否已具备"读外部存储任意文件"的能力。
     *
     * Android 10 及以下靠 `READ_EXTERNAL_STORAGE`；11 起只有
     * `MANAGE_EXTERNAL_STORAGE`（所有文件访问权限）能让 File API 直读 `/sdcard/`。
     * 没有它时，`clan://localhost/` 只能读 App 专属目录（无需任何权限）。
     */
    fun hasAllFilesAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true // Android 10-：READ_EXTERNAL_STORAGE 授予后 File API 可用，由系统权限弹窗决定
    }

    /**
     * 跳系统设置页授予"所有文件访问权限"（Android 11+）。
     *
     * `newTask=false` 用于 `ActivityResultContracts.StartActivityForResult`：
     * 带 `FLAG_ACTIVITY_NEW_TASK` 启动会命中系统的
     * "launching as a new task, so cancelling activity result"，结果立即被取消，
     * 表现为授权页刚打开就被后续动作盖住。
     */
    fun allFilesAccessIntent(context: Context, newTask: Boolean = true): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).apply { if (newTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    /**
     * 该路径是否**不需要**额外权限即可读。
     *
     * App 专属外部目录（`Android/data/<pkg>/files`、`Android/media/<pkg>`）
     * 在分区存储下始终可读写。用户把配置放这里是最省事的无权限方案，
     * 值得在 UI 里明确告诉他。
     */
    fun isInAppExternalDir(context: Context, file: File): Boolean {
        val owned = listOfNotNull(
            context.getExternalFilesDir(null),
            *runCatching { context.externalMediaDirs }.getOrDefault(emptyArray()),
            context.getExternalCacheDir(),
        )
        val abs = file.absolutePath
        return owned.any { abs.startsWith(it.absolutePath) }
    }

    private fun short(s: String): String = if (s.length <= 80) s else s.take(80) + "…"
}
