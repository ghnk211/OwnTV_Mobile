package com.github.catvod.crawler

import tv.own.owntv.mobile.tvbox.util.TbLog


/**
 * 桩：日志工具（转发到宿主 Timber）。
 * jar 里的实现类常调用 SpiderDebug.log / SpiderDebug.error。
 */
object SpiderDebug {

    private const val TAG = "Catvod"

    @JvmStatic
    fun log(message: String?) {
        TbLog.tag(TAG).d( message.orEmpty())
    }

    /**
     * jar 的网络失败回调会调这个签名（OkHttp onFailure → `SpiderDebug.log(t)`）。
     *
     * ## 缺它就是真机闪退（2026-10-14 实锤）
     * 崩溃堆栈：
     * ```
     * NoSuchMethodError: No static method log(Ljava/lang/Throwable;)V
     *   in class Lcom/github/catvod/crawler/SpiderDebug;
     *   at com.github.catvod.spider.merge.rd.onFailure(...)
     *   at java.lang.Thread.run(Thread.java:933)   ← jar 自建线程
     * ```
     * `NoSuchMethodError` 是 Error 而非 Exception，且发生在 **jar 自己的 OkHttp
     * 回调线程**上 —— 宿主任何 try-catch 都够不着，进程直接死。
     *
     * 所以这是 **ABI 契约**问题：桩方法签名必须与 jar 的引用完全一致，
     * 缺一个就崩。解 fan.txt 的 dex method_ids 确认，jar 对 SpiderDebug
     * **只引用这一个方法**，签名正是 `log(Ljava/lang/Throwable;)V`。
     */
    @JvmStatic
    fun log(throwable: Throwable?) {
        TbLog.e(throwable, TAG, "jar error")
    }

    @JvmStatic
    fun log(tag: String?, message: String?) {
        TbLog.tag(TAG).d( "[%s] %s", tag.orEmpty(), message.orEmpty())
    }

    @JvmStatic
    @JvmOverloads
    fun error(message: String?, throwable: Throwable? = null) {
        TbLog.e(throwable, TAG, message.orEmpty())
    }

    @JvmStatic
    @JvmOverloads
    fun error(tag: String?, message: String?, throwable: Throwable? = null) {
        TbLog.e(throwable, TAG, "[%s] %s", tag.orEmpty(), message.orEmpty())
    }

    @JvmStatic
    fun info(message: String?) {
        TbLog.tag(TAG).i( message.orEmpty())
    }

    @JvmStatic
    fun warn(message: String?) {
        TbLog.tag(TAG).w( message.orEmpty())
    }

    /** 清理（对齐 jar 期望的接口） */
    @JvmStatic
    fun clean() {}
}
