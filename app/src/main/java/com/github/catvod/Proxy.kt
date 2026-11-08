package com.github.catvod

/**
 * 桩：本地代理端口持有者（对齐 TVBoxOS `com.github.catvod.Proxy`）。
 *
 * ## 它解决什么问题
 * jar 侧构造 `proxy://` 播放地址时需要知道**宿主本地代理服务的端口**，
 * 参考项目在 `JarLoader` 建好 spider 后调 `Proxy.set(port)` 把端口注入进来
 * （TVBoxOS 里是 `injectProxyPort` / `ControlManager` 两处），jar 随后用
 * `getPort()` / `getUrl(local)` 拼 `http://127.0.0.1:<port>/proxy?...`。
 *
 * **没有这个类，jar 一引用就 NoClassDefFoundError**（jar 自建线程上抛，
 * 宿主 catch 不住，进程直接死）。
 *
 * ## 当前状态（P2 之前）
 * 本工程还没有 jar 侧本地代理服务（wexguard 分析文档 P2：需真机确认真实 dex
 * 是否走 `proxy://` 链路后再决定接不接 RemoteServer 形态的服务），所以：
 * - `set(int)` 先老实记账（P2 落地时由宿主在建 spider 后注入真实端口）
 * - `getPort()` 返回 0 / `getUrl()` 返回 "" —— **没有服务就不给地址**，
 *   让 jar 早失败，而不是拼出 `http://127.0.0.1:0/proxy` 这种看似合法的死链
 *
 * 与 jar 自带的 `com.github.catvod.spider.Proxy`（静态 `proxy(Map)` 出口）是
 * 两个东西：那个在 jar dex 里，由宿主反射调用；这个在宿主侧，jar 主动读。
 */
class Proxy {

    companion object {

        private var port: Int = 0

        /** 宿主注入本地代理端口（TVBoxOS：JarLoader.injectProxyPort 同款时机） */
        @JvmStatic
        fun set(port: Int) {
            Proxy.port = port
        }

        /** 当前代理端口；无服务时为 0 */
        @JvmStatic
        fun getPort(): Int = port

        /**
         * 代理基址。无服务时返回 ""。
         *
         * 只回环：本工程未来的本地代理服务也只会绑 127.0.0.1（与 JS 引擎的
         * JsProxyServer 同一套边界），故 local 参数
         * 不改变目标地址，保留只是为了签名兼容。
         */
        @JvmStatic
        fun getUrl(local: Boolean): String = if (port > 0) "http://127.0.0.1:$port/proxy" else ""
    }
}
