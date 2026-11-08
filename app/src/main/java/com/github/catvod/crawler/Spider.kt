package com.github.catvod.crawler

import android.content.Context
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * 桩：Spider 抽象基类（方案 5.3）。
 *
 * 这是 spider jar 的**编译期依赖**。jar 里的实现类继承本类并 override 部分方法，
 * 运行时由宿主 DexClassLoader 的 parent-first 委派加载到本实现。
 *
 * 关键：不要给本类加 final 方法、不要改成 interface、不要改包名，
 * 否则所有 csp_ jar 会 NoClassDefFoundError。
 *
 * ## ⚠️ 方法签名必须逐字节对齐生态 —— 本类不是"接口设计"，是"ABI 兼容层"
 *
 * JVM/ART 的方法查找用的是**精确描述符**（name + 参数类型 + **返回类型**），
 * 继承链上的协变返回类型**不参与**方法匹配。也就是说：
 * 宿主写 `open fun homeContent(filter: Boolean): Any?`（描述符 `...Ljava/lang/Object;`），
 * jar 按 `homeContent(Z)Ljava/lang/String;` 调用 → **NoSuchMethodError**，且这类是 Error，
 * `catch (Exception)` 兜不住。
 *
 * 真实案例（`vip.json` 的 23 个页签里只有「柠檬」能出数据）：
 * ```
 * NoSuchMethodError: No virtual method homeContent(Z)Ljava/lang/String;
 *   in class Lcom/github/catvod/crawler/Spider; or its super classes
 * ```
 *
 * 本类的签名不是拍脑袋写的，是用 `dexdump -d classes.dex` 从真实 jar 里
 * 提取「对本桩的全部方法引用」逐条对齐的。该 jar（aowu/woshinidie 加固壳）
 * 的引用清单如下 —— **缺任何一条都会让调用它的那个接口整体崩掉**：
 * ```
 * <init>()V
 * init(Context, String)V
 * destroy()V
 * homeContent(Z)String                     ← 布尔参数 + String 返回
 * homeVideoContent()String
 * categoryContent(String, String, Z, HashMap)String   ← 注意第 4 参是 HashMap！
 * detailContent(List)String                            ← List 而非 String
 * playerContent(String, String, List)String            ← 第 3 参是 List
 * searchContent(String, Z)String
 * searchContent(String, Z, String)String
 * action(String)String                        ← 交互动作
 * liveContent(String)String                   ← 直播
 * isVideoFormat(String)Z
 * manualVideoCheck()Z
 * proxyLocal(Map)[Ljava/lang/Object;          ← 返回 Object[]
 * client()OkHttpClient            ← 静态！jar 用来取宿主网络栈
 * safeDns()Dns                                ← 静态！jar 用来取宿主 DNS
 * ```
 *
 * ## 2026-10-19 增补（wexguard 加固壳分析 P1，对齐 TVBoxOS Spider.java）
 * 真实 dex 被加密在 assets 里、静态扫不到，但生态主流 jar 编译时依赖的是
 * TVBoxOS 的完整桩面。以下三条 TVBoxOS 有、本桩此前没有，已按同签名补齐
 * （JVM 描述符逐条对齐，缺了就是 NoSuchMethodError / NoClassDefFoundError）：
 * ```
 * initApi(Lcom/github/catvod/crawler/SpiderApi;)V   ← 宿主注入能力集合
 * cancelByTag()V                                    ← 宿主停止时取消该源请求
 * proxy(Ljava/util/Map;)[Ljava/lang/Object;         ← 默认转发 proxyLocal
 * ```
 * 配套新增 `com.github.catvod.crawler.SpiderApi`（log/multiReq/webParse/
 * getAddress/getPort/getScreenOrientation）与 `com.github.catvod.Proxy`
 * （本地代理端口持有者，P2 播放链路的前置桩）。
 *
 * 两个容易踩的细节：
 * 1. `categoryContent` 第 4 参生态里是 **`java.util.HashMap`**（筛选参数），
 *    不是 FongMi 早期的 `String extend`。写成 String 会 NoSuchMethodError。
 * 2. `client()` / `safeDns()` 是 **static**，jar 内部直接 `invoke-static`
 *    拿宿主的 OkHttpClient 复用连接池 —— 这两个也必须存在（否则 jar 崩），
 *    且返回的必须是宿主真实可用的实例，不能是空壳。
 *
 * （同生态的 FongMi / TVBox / CatVodOpen 桩都是这个形态。）
 */
abstract class Spider {

    protected var context: Context? = null

    // ---------------- 生命周期 ----------------

    /**
     * 注意：`init` 有两个重载且**都不能省**。实测 jar 里的实现类会调
     * `super.init(Context)` 单参版，缺了它运行期直接
     * `NoSuchMethodError: No super method init(Landroid/content/Context;)V` —— 崩在 jar 里，
     * 宿主只看到一个 InvocationTargetException，排查成本很高。
     *
     * 不用 `@JvmOverloads`（这里没有默认参数，注解无效只会产生编译警告）。
     */
    open fun init(context: Context, ext: String?) {
        this.context = context
    }

    /** 单参重载（jar 常见调用形态） */
    open fun init(context: Context) {
        this.context = context
    }

    /**
     * 宿主注入能力集合（对齐 TVBoxOS `Spider.initApi`）。
     *
     * TVBoxOS 的 JarLoader 在 `init(Context, ext)` **之前**调它；jar 侧典型写法是
     * override 后把 api 存字段，之后在业务接口里调 `api.log/multiReq/webParse`。
     * 本工程由 `JarSpider.init` 在引导 init 前反射注入（见 [JarSpider]）。
     *
     * 基类空转：jar 不 override 时什么也不发生。
     */
    open fun initApi(api: SpiderApi) {}

    // ---------------- TVBox 五接口（返回 JSON 文本） ----------------

    /** 首页分类 + 推荐；返回 JSON 文本 */
    open fun homeContent(filter: Boolean): String? = null

    /** 首页推荐（部分源单独实现此接口，而非塞进 homeContent） */
    open fun homeVideoContent(): String? = null

    /**
     * 分类列表；返回 JSON 文本。
     *
     * 两个重载都要有：`extend` 在生态里有 String 和 HashMap 两种形态，
     * 不同 jar 编译时选中的不同，宿主都得提供，否则该源直接崩。
     */
    open fun categoryContent(
        tid: String,
        pg: String,
        filter: Boolean,
        extend: HashMap<String, Any?>?,
    ): String? = null

    open fun categoryContent(
        tid: String,
        pg: String,
        filter: Boolean,
        extend: String?,
    ): String? = null

    /** 详情 + 播放线路；返回 JSON 文本 */
    open fun detailContent(ids: String): String? = null

    /**
     * 详情（新协议重载，传 List）。
     * 与单参版共存：生态里两种都有，只能二选一尝试，不能互相覆盖。
     */
    open fun detailContent(ids: MutableList<String>?): String? = null

    /** 搜索；TVBox 双签名（带 pg 的三参版见下） */
    open fun searchContent(key: String, quick: Boolean): String? = null

    /** 搜索（带翻页）；返回 JSON 文本 */
    open fun searchContent(key: String, quick: Boolean, pg: String): String? = null

    /** 播放地址；返回 JSON 文本 */
    open fun playerContent(flag: String, id: String, vipFlags: String?): String? = null

    /**
     * 播放地址（vipFlags 为 List 的形态，**壳 jar 走的就是这个**）。
     * 与三参 String 版共存。
     */
    open fun playerContent(flag: String, id: String, vipFlags: MutableList<String>?): String? = null

    // ---------------- 生态扩展接口（dexdump 实测存在，缺了会崩） ----------------

    /** 交互动作（扫码/授权等）；返回 JSON 文本 */
    open fun action(action: String): String? = null

    /** 直播源内容；返回 JSON 文本 */
    open fun liveContent(key: String): String? = null

    /** 判断地址是否为视频直链 */
    open fun isVideoFormat(url: String): Boolean = false

    /** 是否需要手动校验（部分源的开关） */
    open fun manualVideoCheck(): Boolean = false

    /** 走宿主本地代理时回传结果；注意返回类型是 `Object[]` 而非 String */
    open fun proxyLocal(params: Map<String, String>?): Array<Any?> = emptyArray()

    /**
     * 代理出口（对齐 TVBoxOS `Spider.proxy`：默认转发 [proxyLocal]）。
     *
     * 用途：本地代理服务收到请求后回调 jar（TVBoxOS 是 RemoteServer → jar 的
     * `proxy(Map)`）。当前无本地代理服务，默认转发保持行为一致；
     * P2 落地后由宿主按 [com.github.catvod.Proxy] 的端口路由到这里。
     *
     * 泛型用 `Map<String, String>` 与 TVBoxOS 一致（JVM 描述符同为
     * `(Ljava/util/Map;)[Ljava/lang/Object;`，泛型不参与 ABI 匹配）。
     */
    open fun proxy(params: Map<String, String>?): Array<Any?> = proxyLocal(params)

    /** 是否支持自动播放 */
    open fun autoPlay(): Boolean = false

    /** 手动播放（部分源需要） */
    open fun manualPlay(): Boolean = false

    /** 代理设置（部分源用于绕过本地直连限制） */
    open fun setProxy(proxy: Boolean) {}

    /**
     * 取消该源发起的全部网络请求（宿主侧停播/切源时调用，对齐 TVBoxOS）。
     *
     * 默认空转 —— jar 若 override 通常会转发到自己 OkHttp client 的
     * `cancelByTag`；宿主不强制要求实现，故不放在反射必调链上。
     */
    open fun cancelByTag() {}

    /** 释放资源 */
    open fun destroy() {}

    // ---------------- 宿主网络栈出口（jar 以 invoke-static 调用） ----------------

    companion object {
        /**
         * 宿主 OkHttpClient。
         *
         * jar 用它复用宿主的连接池 / Cookie / 缓存，而不是自己再建一份。
         * 这里是**惰性单例**而非硬引用：桩类由宿主 dex 加载，这里只持有一个弱引用，
         * 避免单元测试（无 Application context）时初始化就崩。
         */
        @JvmStatic
        fun client(): OkHttpClient = SpiderNet.client()

        /** 宿主 DNS 解析器（jar 用于绕过运营商劫持） */
        @JvmStatic
        fun safeDns(): Dns = SpiderNet.safeDns()
    }
}