package tv.own.owntv.mobile.tvbox.engine.proxy

import tv.own.owntv.mobile.tvbox.util.TbLog

import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * jar 侧本地代理服务：只绑回环、只认 `/proxy` 一个路由。
 *
 * ## 为什么必须有它（真机实证 2026-10-19）
 * wexguard 加固壳的 spider 在 `playerContent` 里返回的**不是直链**，而是
 * `http://127.0.0.1:9978/proxy?do=hxq&url=<真实m3u8>` —— 这是 TVBox 生态的
 * 标准约定：宿主起一个本地 HTTP 服务，jar 把"要经过宿主网络栈/要改包"的地址
 * 挂到这个服务下，由宿主回调 `spider.proxy(params)` 拿真实内容。
 *
 * 本工程此前**没有这一层**（`com.github.catvod.Proxy` 只是端口记账桩），于是：
 * 1. 这条地址被判成"网页"，甩给 4 个第三方解析器 —— 对方当然连不到你手机的
 *    `127.0.0.1`，每个 10 秒超时，实测最长一次挂了 86 秒；
 * 2. 即便不绕解析器，播放器也连不上任何东西，整个源表现为"无法播放"。
 *
 * 参考项目对应实现：`RemoteServer.handleProxy` → `params` 里有 `do` →
 * `ApiConfig.proxyLocal(params)` → `Spider.proxy/proxyLocal`（jar 覆写），
 * 返回值约定为 `Object[]{code, mime, InputStream, headers}`。
 *
 * ## 三条硬边界
 * 1. **只绑 127.0.0.1** —— 局域网访问不到。
 * 2. **只认 `/proxy`** —— 没有文件路由、不列目录、不读任意文件，
 *    不会被当成开放代理。
 * 3. **只认 `do` 参数** —— 没有 `do` 直接 404，不做任意 URL 转发。
 *
 * ## 为什么不用 NanoHTTPD
 * 同 JsProxyServer：单路由 + 需要流式回写，
 * 自己写比引依赖再改它的响应生成更可控，也避免与 js 侧那套端口/生命周期打架。
 *
 * ## 端口为什么必须是 9978
 * `com.github.catvod.Proxy.getUrl()` 在本工程无服务时返回空串，但 wexguard 壳
 * **内部硬编码兜底 9978**（日志实证）。所以首选端口固定 9978；被占用时退到
 * 9979 起，同时由 [JarProxyHub] 把真实端口注入 `Proxy.set()`，
 * 让走 `getUrl()` 的那批 jar 仍能拿到正确地址。
 */
internal class JarProxyServer(
    private val dispatcher: (Map<String, String>) -> Array<Any?>?,
) {

    @Volatile
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "jar-proxy-worker").apply { isDaemon = true }
    }

    /**
     * 启动服务，返回实际监听端口；全部候选端口被占用时返回 0。
     *
     * 幂等：已启动则直接返回当前端口。
     */
    fun start(): Int {
        if (running.get()) return port()
        for (candidate in PORTS) {
            val socket = runCatching {
                ServerSocket(candidate, 64, InetAddress.getByName("127.0.0.1"))
            }.getOrNull() ?: continue
            serverSocket = socket
            running.set(true)
            Thread({ acceptLoop(socket) }, "jar-proxy-accept").apply { isDaemon = true }.start()
            TbLog.tag(TAG).i("jar 本地代理已启动：http://127.0.0.1:$candidate/proxy")
            return candidate
        }
        TbLog.tag(TAG).w("jar 本地代理启动失败：候选端口全部被占用")
        return 0
    }

    fun port(): Int = serverSocket?.localPort ?: 0

    fun isRunning(): Boolean = running.get()

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        pool.shutdownNow()
        TbLog.tag(TAG).i("jar 本地代理已停止，端口释放")
    }

    // ---------------- 接受循环 ----------------

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (t: Throwable) {
                if (running.get()) TbLog.tag(TAG).d("accept 退出：%s", t.message)
                return
            }
            pool.submit { runCatching { handle(client) } }
        }
    }

    // ---------------- 单次请求 ----------------

    private fun handle(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 30_000
            val input = sock.getInputStream()
            val requestLine = input.readLineLimited() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0).orEmpty().uppercase()
            val target = parts.getOrNull(1).orEmpty()
            val headers = readHeaders(input)
            if (method != "GET" && method != "HEAD") {
                writeSimple(sock.getOutputStream(), 405, "Method Not Allowed")
                return
            }
            val path = target.substringBefore('?')
            if (path != PROXY_PATH) {
                writeSimple(sock.getOutputStream(), 404, "Not Found")
                return
            }
            val query = parseQuery(target.substringAfter('?', ""))
            if (!query.containsKey("do")) {
                writeSimple(sock.getOutputStream(), 404, "Not Found")
                return
            }
            // 对齐参考项目 `params.putAll(session.getHeaders())`：
            // 少数 spider 的 proxy 实现会读 Range / Referer 等请求头。
            val params = LinkedHashMap<String, String>(query)
            headers.forEach { (k, v) -> params.putIfAbsent(k, v) }
            val result = runCatching { dispatcher(params) }.onFailure {
                TbLog.tag(TAG).w(it, "proxy 分发异常 do=%s", params["do"])
            }.getOrNull()
            if (result == null || result.size < 3) {
                TbLog.tag(TAG).w("proxy 返回空：do=%s url=%s", params["do"], params["url"])
                writeSimple(sock.getOutputStream(), 502, "proxy returned empty")
                return
            }
            writeProxy(sock.getOutputStream(), result, method == "HEAD")
        }
    }

    /**
     * 回写 jar 给的 `Object[]`：`[0]=code, [1]=mime, [2]=InputStream, [3]=headers(可选)`。
     *
     * 用 `Connection: close` + 不写 Content-Length 来界定响应体（流式，
     * 内存里不堆整段媒体）。3xx 且 body 为空时靠 headers 里的 Location 跳转。
     */
    private fun writeProxy(out: OutputStream, rs: Array<Any?>, headOnly: Boolean) {
        val code = (rs[0] as? Number)?.toInt() ?: 200
        val mime = (rs.getOrNull(1) as? String)?.takeIf { it.isNotBlank() }
            ?: "application/octet-stream"
        val body = rs.getOrNull(2) as? InputStream
        @Suppress("UNCHECKED_CAST")
        val extra = (rs.getOrNull(3) as? Map<*, *>)
            ?.entries
            ?.mapNotNull { e ->
                val k = e.key as? String ?: return@mapNotNull null
                val v = e.value as? String ?: return@mapNotNull null
                k to v
            }
            .orEmpty()

        BufferedOutputStream(out).use { bos ->
            bos.write(("HTTP/1.1 $code ${statusText(code)}\r\n").toByteArray())
            bos.write(("Content-Type: $mime\r\n".toByteArray()))
            bos.write("Accept-Ranges: bytes\r\n".toByteArray())
            extra.forEach { (k, v) ->
                // 长度/分块头由本服务自己决定，jar 给的一律不转发（长度未知，走 close 界定）
                if (k.equals("Content-Length", true) ||
                    k.equals("Transfer-Encoding", true) ||
                    k.equals("Connection", true)
                ) return@forEach
                runCatching { bos.write("$k: $v\r\n".toByteArray()) }
            }
            bos.write("Connection: close\r\n\r\n".toByteArray())
            if (!headOnly && body != null) {
                // 8KB 缓冲逐段拷贝：m3u8 是几 KB，ts/mp4 分片可能几 MB，不能整段读进内存
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = runCatching { body.read(buf) }.getOrDefault(-1)
                    if (n <= 0) break
                    bos.write(buf, 0, n)
                }
            }
            bos.flush()
        }
        runCatching { body?.close() }
    }

    private fun writeSimple(out: OutputStream, code: Int, text: String) {
        BufferedOutputStream(out).use { bos ->
            val body = text.toByteArray()
            bos.write(("HTTP/1.1 $code $text\r\n").toByteArray())
            bos.write("Content-Type: text/plain; charset=utf-8\r\n".toByteArray())
            bos.write(("Content-Length: ${body.size}\r\n").toByteArray())
            bos.write("Connection: close\r\n\r\n".toByteArray())
            bos.write(body)
            bos.flush()
        }
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        307 -> "Temporary Redirect"
        403 -> "Forbidden"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        else -> "OK"
    }

    /**
     * 解析 query。值按 UTF-8 解码一次（与 NanoHTTPD `getParms` 一致）——
     * spider 拼的 `url=http%3A%2F%2F...` 到这里正好还原成 `http://...`。
     */
    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return buildMap {
            raw.split('&').forEach { pair ->
                val i = pair.indexOf('=')
                if (i <= 0) return@forEach
                val k = pair.substring(0, i)
                val v = pair.substring(i + 1)
                put(k, runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v))
            }
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        while (true) {
            val line = input.readLineLimited() ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i <= 0) continue
            result[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        return result
    }

    /** 只读到 `\n`，避免 BufferedReader 的编码开销与缓冲区吞掉后续二进制的风险 */
    private fun InputStream.readLineLimited(max: Int = 8192): String? {
        val sb = StringBuilder()
        while (true) {
            val c = read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (c != '\r'.code && sb.length < max) sb.append(c.toChar())
        }
    }

    private companion object {
        const val TAG = "JarProxy"
        const val PROXY_PATH = "/proxy"

        /**
         * 端口候选：9978 是 TVBox 生态默认值（wexguard 壳硬编码兜底的就是它），
         * 必须首选；被占用时顺延，实际端口由 [JarProxyHub] 注入 `Proxy.set()`。
         */
        val PORTS: List<Int> = listOf(9978) + (9979..9999).toList()
    }
}
