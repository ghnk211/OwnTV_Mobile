package com.github.catvod.crawler

import tv.own.owntv.mobile.tvbox.CatvodHost

/**
 * 桩：动态加载器（部分 jar 用它在运行时加载其他 jar / dex）。
 *
 * FongMi 生态里 `XLoader` 有两种用法：
 * 1. 传 ClassLoader 进来回传（宿主注入时用）
 * 2. 传 "jar:xxx" 或文件路径，加载另一个 jar
 *
 * 宿主实现只保证第1 种（返回宿主桩 ClassLoader），
 * 第 2 种按需转 DexClassLoader；缺类风险高的场景由 jar 自己兜底。
 */
object XLoader {

    /** 宿主桩 ClassLoader：jar 内对 com.github.catvod.** 的引用都落到这里 */
    @JvmStatic
    fun getClassLoader(): ClassLoader = CatvodHost.appContext().javaClass.classLoader!!

    @JvmStatic
    fun loadClass(name: String): Class<*>? = runCatching { Class.forName(name, true, getClassLoader()) }
        .getOrNull()

    /**
     * 兼容旧签名：有些 jar 调 `XLoader.loadClass(classLoader, name)`。
     */
    @JvmStatic
    fun loadClass(parent: ClassLoader?, name: String): Class<*>? =
        runCatching { Class.forName(name, true, parent ?: getClassLoader()) }.getOrNull()
}
