package com.github.catvod.crawler

import tv.own.owntv.mobile.tvbox.net.OkHttpProvider
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * 宿主网络栈出口：给 spider jar 提供可复用的 OkHttpClient 与 Dns。
 *
 * ## 为什么要单独一层
 *
 * `com.github.catvod.crawler.Spider` 是**编译期 ABI 契约**，不能直接依赖
 * `tv.own.owntv.mobile.tvbox.net` 这类宿主实现细节 —— 一旦 jar 与宿主互相污染，
 * 排查"类冲突"会非常痛苦。故这里做一层极薄的中转：
 *
 * - 桩 [Spider] 只认 okhttp3 的公开类型（jar 侧 `invoke-static` 要的）
 * - 宿主具体实现（OkHttpProvider 里的连接池 / Cookie / DNS）留在 [OkHttpProvider]
 * - 单测可以在无 Application context 的环境下直接调用本对象，不触发 Android 依赖
 *
 * ## 为什么 jar 一定要复用宿主的 client
 *
 * 壳 jar 内部大量走网络请求。dexdump 显示它对
 * `Lcom/github/catvod/crawler/Spider;.client:()Lokhttp3/OkHttpClient;`
 * 做 invoke-static —— 目的就是**复用宿主的连接池、Cookie 与 DNS 策略**，
 * 而不是自己再建一份 OkHttp。如果这里返回空壳/新建实例，会导致：
 * - Cookie 不共享 → 站点二次验证失效
 * - 连接池翻倍 → 弱网下连接数被拖垮
 * - DNS 策略不一致 → jar 侧请求频繁超时
 */
internal object SpiderNet {

    /**
     * jar 专用网络栈。
     *
     * 不是宿主的严格 TLS 实例 —— 见 `OkHttpProvider.jarClient()` 注释：
     * 生态里csp 源普遍用自签证书，jar 配套逻辑预期"信任所有"，
     * 真机实测直接给严格 client 会导致 SSL 校验失败 → 源解析 null → NPE。
     */
    fun client(): OkHttpClient = OkHttpProvider.jarClient()

    /** 宿主 DNS（IPv4 优先，避免部分源站 IPv6 不可达） */
    fun safeDns(): Dns = OkHttpProvider.safeDns()
}