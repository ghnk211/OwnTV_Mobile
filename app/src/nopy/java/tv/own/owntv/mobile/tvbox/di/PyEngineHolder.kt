package tv.own.owntv.mobile.tvbox.di

import android.content.Context
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.engine.Engine
import java.io.File

/**
 * Python 引擎装配点（精简包侧，WITH_PY=false 时本源集注册进 main）。
 *
 * 返回 null：注册表里没有 Python 引擎，py 源自然路由失败，
 * 由 `SiteConfig.isSupported`（随 BuildConfig.WITH_PY 回退）在 UI 层灰显拦截。
 * 签名必须与 `src/py/java` 侧真实现完全一致。
 */
object PyEngineHolder {
    fun create(context: Context, pyFileProvider: suspend (SiteConfig) -> File?): Engine? = null
}