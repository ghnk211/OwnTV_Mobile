package tv.own.owntv.mobile.tvbox.net

import tv.own.owntv.mobile.tvbox.util.TbLog

import android.content.Context
import android.net.Uri
import tv.own.owntv.mobile.tvbox.data.prefs.TvboxPrefs
import tv.own.owntv.mobile.tvbox.util.FailKind
import tv.own.owntv.mobile.tvbox.util.LocalRead
import tv.own.owntv.mobile.tvbox.util.LocalSource
import tv.own.owntv.mobile.tvbox.util.LocalSourceResolver

/**
 * 统一的"按地址取数据"：网络 / 本地文件 / 局域网 TVBox 服务三合一。
 *
 * ## 为什么抽出来
 * 兼容 TVBox 生态之后，**任何地址字段都可能是本地形态**：
 * 配置的 `spider`、`sites[].jar`、直播的 `lives[].api`、相对路径 `./xxx`
 * 都可能写成 `clan://localhost/…`。若每个调用点各写一遍分流，
 * 漏一处就是"那一个功能用不了本地文件"，而且症状各不相同、很难联想到同一根因。
 *
 * 这里只做一件事：**给一个地址，把字节取回来**，本地与网络的差异对外不可见。
 *
 * ## 无权限时的降级
 * 直读失败会尝试用 SAF 目录树授权（[SettingsStore.localTreeUri]）读取，
 * 这样用户不必授予 `MANAGE_EXTERNAL_STORAGE` 也能用 `clan://localhost/`。
 */
class AddressFetcher(
    private val context: Context,
    private val net: NetApi,
    private val settings: TvboxPrefs? = null,
) {

    private companion object {
        const val TAG = "AddressFetcher"
    }

    sealed interface Outcome {
        data class Ok(val bytes: ByteArray) : Outcome

        data class Err(
            val message: String,
            /** 失败原因是缺权限 / 授权失效 —— UI 应引导授权而不是让用户重试 */
            val needsPermission: Boolean = false,
        ) : Outcome
    }

    /** 取字节。地址可以是 http(s) / clan:// / file:// / 裸绝对路径 / content:// */
    suspend fun fetch(address: String): Outcome {
        val src = LocalSourceResolver.parse(address)

        // clan://<非 localhost>/ = 另一台设备上的 TVBox 服务，本质是 http
        val httpUrl = when (src) {
            is LocalSource.RemoteClan -> src.httpUrl
            null -> address
            else -> null
        }

        if (httpUrl != null) {
            val resp = net.getBytes(httpUrl)
            return resp.bytes?.takeIf { it.isNotEmpty() }?.let { Outcome.Ok(it) }
                ?: Outcome.Err("下载失败：${resp.error ?: "HTTP ${resp.code}"}")
        }

        val local = src ?: return Outcome.Err("不支持的地址：$address")
        return when (val r = LocalSourceResolver.read(context, local, treeUri())) {
            is LocalRead.Ok -> Outcome.Ok(r.bytes)
            is LocalRead.Fail -> {
                TbLog.tag(TAG).w( "本地读取失败（%s）：%s", r.kind, r.message)
                Outcome.Err(r.message, needsPermission = r.kind == FailKind.NO_PERMISSION)
            }
        }
    }

    /** 取文本（UTF-8）。配置 JSON、直播订阅 m3u/txt 都走这里 */
    suspend fun fetchText(address: String): Outcome = fetch(address)

    private suspend fun treeUri(): Uri? = settings?.localTreeUri
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
}
