package tv.own.owntv.mobile.tvbox.di

import android.content.Context
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import tv.own.owntv.mobile.tvbox.engine.Engine
import tv.own.owntv.mobile.tvbox.engine.py.PyEngine
import java.io.File

/**
 * Python 引擎装配点（完整包侧，WITH_PY=true 时本源集注册进 main）。
 *
 * 与 `src/nopy/java` 下的同名桩签名一致：TvboxModule 只认 Holder，
 * 编译期决定 py 引擎在不在注册表里，零反射。
 */
object PyEngineHolder {
    fun create(context: Context, pyFileProvider: suspend (SiteConfig) -> File?): Engine? =
        PyEngine(context, pyFileProvider)
}