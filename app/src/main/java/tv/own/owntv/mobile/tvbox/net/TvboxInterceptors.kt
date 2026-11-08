package tv.own.owntv.mobile.tvbox.net

import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Response
import tv.own.owntv.mobile.tvbox.util.TbLog
import java.net.InetAddress

/**
 * The two interceptors the TVbox network chains actually need, ported from VodPlayer's
 * `net/Interceptors.kt` (the proxy/DoH/stub-marking machinery was deliberately not carried over —
 * plan §12.1). Kept verbatim where it matters: both fix real, reproduced failures.
 */


/**
 * 站点标记：请求统一打上 siteKey 头（NetApi 写入；本移植无桩代理消费方，保留写入面）。
 * HTTP 头只允许 Latin-1，而 TVbox 配置的 key 大量是中文 —— URL-encode 后再放进头里。
 */
object SiteMark {
    const val HEADER = "X-Source-Site-Key"

    fun encode(siteKey: String): String =
        if (siteKey.all { it.code in 32..126 }) siteKey
        else java.net.URLEncoder.encode(siteKey, "UTF-8")

    fun decode(encoded: String): String =
        if (encoded.isEmpty()) encoded
        else runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
}

/**
 * Referer 防盗链兜底拦截器：部分 CDN 对缺 Referer 的请求直接 403。
 * 只在请求未自带 Referer 时补 `scheme://host`，不覆盖调用方显式指定的值。
 */
class RefererInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.header("Referer").isNullOrBlank()) return chain.proceed(request)
        val url = request.url
        return chain.proceed(
            request.newBuilder()
                .header("Referer", "${url.scheme}://${url.host}")
                .build(),
        )
    }
}

/**
 * 本地回环快速失败 + 私网段拦截（SSRF 防护）。
 *
 * 加固壳 jar 的 init 会连宿主本地代理端口；没有监听时 OkHttp 的连接重试要拖满整个
 * connect timeout，这里用一次回环 socket 预探测快速失败。[blockPrivateNet] = true 时
 * 私网字面量 IP 直接拒绝（只挂 jar 链；主链保留 clan://<host> 局域网配置共享）。
 */
class LoopbackFastFailInterceptor(
    private val blockPrivateNet: Boolean = false,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        if (blockPrivateNet && isPrivateHost(host)) {
            throw java.net.ConnectException(
                "内网地址已拦截（SSRF 防护）：$host",
            )
        }
        if (!isLoopbackHost(host)) return chain.proceed(request)

        val port = request.url.port
        if (!probeOpen(host, port)) {
            throw java.net.ConnectException(
                "本地代理 $host:$port 未监听（宿主暂无本地代理服务），已快速失败",
            )
        }
        return chain.proceed(request)
    }

    private fun probeOpen(host: String, port: Int): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

    private companion object {
        const val PROBE_TIMEOUT_MS = 500
    }
}

/** 是否本地回环地址（127.x / localhost / ::1） */
internal fun isLoopbackHost(host: String): Boolean {
    val h = host.trim().lowercase()
    return h == "localhost" || h == "::1" || h == "[::1]" ||
        h.startsWith("127.")
}

/**
 * 是否私网字面量 IP（不做 DNS 解析，热路径零开销）。
 * loopback 不算私网 —— 它走探测语义，宿主自己的本地代理要用。
 */
internal fun isPrivateHost(host: String): Boolean {
    val h = host.trim().removePrefix("[").removeSuffix("]")
    if (h.equals("localhost", ignoreCase = true)) return false
    if (!isIpLiteral(h)) return false
    val addr = runCatching { java.net.InetAddress.getByName(h) }.getOrNull() ?: return false
    return isPrivateAddress(addr)
}

private fun isIpLiteral(h: String): Boolean {
    if (h.isEmpty()) return false
    if (h.contains(':')) return true
    val parts = h.split('.')
    return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
}

/** 地址是否落在私网/链路本地段；loopback 返回 false（探测语义放行）。 */
internal fun isPrivateAddress(addr: java.net.InetAddress): Boolean {
    if (addr.isLoopbackAddress) return false
    if (addr.isSiteLocalAddress || addr.isLinkLocalAddress) return true
    val b = addr.address ?: return false
    if (b.size == 4) return isPrivateV4(b[0], b[1], b[2], b[3])
    if (b.size == 16) {
        if ((b[0].toInt() and 0xFE) == 0xFC) return true
        if (b.slice(0..9).all { it.toInt() == 0 } &&
            b[10].toInt() == 0xFF && b[11].toInt() == 0xFF
        ) {
            return isPrivateV4(b[12], b[13], b[14], b[15])
        }
    }
    return false
}

private fun isPrivateV4(a: Byte, b: Byte, c: Byte, d: Byte): Boolean {
    val i = { x: Byte -> x.toInt() and 0xFF }
    val ia = i(a); val ib = i(b)
    return ia == 0 ||
        ia == 10 ||
        (ia == 172 && ib in 16..31) ||
        (ia == 192 && ib == 168) ||
        (ia == 169 && ib == 254)
}

/**
 * DNS 层私网防护：解析结果落在私网段即拒绝。只挂 jar 链，与字面量检查互补。
 */
internal class PrivateNetGuardDns(private val delegate: Dns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val result = delegate.lookup(hostname)
        val bad = result.firstOrNull { isPrivateAddress(it) }
        if (bad != null) {
            TbLog.tag(TAG).w("DNS 解析结果落在私网段，拒绝（jar 链 SSRF 防护）：%s -> %s", hostname, bad.hostAddress)
            throw java.net.UnknownHostException("私网地址已拦截：$hostname")
        }
        return result
    }

    private companion object {
        const val TAG = "PrivateNetGuardDns"
    }
}

/**
 * Dns 池：部分源站域名 IPv6 不可达，优先取 IPv4；失败时回落到系统解析结果。
 */
internal object InetAddressDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val sys = runCatching { Dns.SYSTEM.lookup(hostname) }
            .onFailure { TbLog.tag("Dns").w(it, "系统 DNS 解析 %s 失败", hostname) }
            .getOrDefault(emptyList())
        if (sys.isEmpty()) return sys
        val v4 = sys.filter { it is java.net.Inet4Address }
        return if (v4.isNotEmpty()) v4 + sys.filter { it !is java.net.Inet4Address } else sys
    }
}
