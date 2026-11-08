package com.github.catvod.util

import android.content.Context
import android.content.res.AssetManager
import tv.own.owntv.mobile.tvbox.CatvodHost
import tv.own.owntv.mobile.tvbox.data.prefs.SecureKv

/**
 * 桩：宿主上下文/资源访问（部分 jar 用它读 assets、拿配置）。
 *
 * 反向依赖宿主（ServiceLocator）而非持有 Context，避免 Context 泄漏。
 *
 * 安全边界（M-2 第 4 项：不可信代码面）：调用方是第三方 spider jar，
 * **按不可信代码对待**。[getPref]/[putPref] 提供的是 `catvod` SP 的任意 KV
 * 读写（生态预期这里存 cookie/token 类数据），与 drpy 的 `local`
 * （JsLocalStore，按 siteKey 隔离）是两块独立存储。新增能力前先评估是否
 * 进一步放大不可信代码可触达的面。
 */
object AppExt {

    @JvmStatic
    fun context(): Context = CatvodHost.appContext()

    @JvmStatic
    fun assets(): AssetManager = CatvodHost.appContext().assets

    @JvmStatic
    fun getPref(key: String): String? = SecureKv.get(
        CatvodHost.appContext().getSharedPreferences(SecureKv.PREFS_CATVOD, Context.MODE_PRIVATE),
        SecureKv.PREFS_CATVOD,
        key,
    )

    @JvmStatic
    fun putPref(key: String, value: String?) {
        SecureKv.put(
            CatvodHost.appContext().getSharedPreferences(SecureKv.PREFS_CATVOD, Context.MODE_PRIVATE),
            SecureKv.PREFS_CATVOD,
            key,
            value,
        )
    }
}

/**
 * 桩：字符串工具。部分 jar 用它做 AES/Base64/MD5，
 * 缺类会 NoClassDefFoundError，故提供最小可用子集。
 */
object StringPro {

    @JvmStatic
    fun base64Encode(data: ByteArray): String =
        android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)

    @JvmStatic
    fun base64Decode(data: String): ByteArray =
        android.util.Base64.decode(data, android.util.Base64.NO_WRAP)

    @JvmStatic
    @JvmOverloads
    fun md5(text: String?, charset: String? = "UTF-8"): String {
        if (text == null) return ""
        val md = java.security.MessageDigest.getInstance("MD5")
        return md.digest(text.toByteArray(java.nio.charset.Charset.forName(charset ?: "UTF-8")))
            .joinToString("") { "%02x".format(it) }
    }

    @JvmStatic
    fun isEmpty(s: String?): Boolean = s.isNullOrEmpty()
}
