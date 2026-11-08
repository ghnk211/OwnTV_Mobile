package tv.own.owntv.mobile.tvbox.engine.jar

import com.github.catvod.crawler.Spider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.HashMap

/**
 * **ABI 契约测试**：宿主桩 `com.github.catvod.crawler.Spider` 的方法描述符
 * 必须与生态 jar 编译时依赖的完全一致。
 *
 * ## 为什么这类测试值得单独写
 *
 * JVM/ART 的方法查找用精确描述符（参数类型 **+ 返回类型**），继承链上的
 * 协变返回类型不参与匹配。所以"看起来一样"的桩在运行期会直接
 * `NoSuchMethodError`——而且这是 **Error**，`catch (Exception)` 兜不住，
 * 崩溃点发生在 jar 内部，宿主日志里只能看到一句 InvocationTargetException。
 *
 * 真机实测代价：`vip.json` 的 23 个页签里只有「柠檬」能出数据，
 * 其余全部"无法获取列表"，排查耗时远超写这些断言。
 *
 * ## 描述符来源
 *
 * 不是凭记忆写的，是用 Android SDK 的 dexdump 从真实 jar 提取的：
 * ```
 * build-tools/35.0.0/dexdump.exe -d classes.dex
 *   | grep -oP "Lcom/github/catvod/crawler/Spider;\.\w+:\([^)]*\)[^ ]*"
 * ```
 * 该 jar（aowu / woshinidie 加固壳）引用宿主桩的**完整清单**：
 * ```
 * <init>()V
 * init(Context, String)V
 * destroy()V
 * homeContent(Z)String
 * homeVideoContent()String
 * categoryContent(String, String, Z, HashMap)String
 * detailContent(List)String
 * playerContent(String, String, List)String
 * searchContent(String, Z)String
 * searchContent(String, Z, String)String
 * action(String)String
 * liveContent(String)String
 * isVideoFormat(String)Z
 * manualVideoCheck()Z
 * proxyLocal(Map)[Ljava/lang/Object;
 * client()OkHttpClient            ← static
 * safeDns()Dns                    ← static
 * ```
 * 2026-10-19 增补（wexguard 加固壳分析 P1，对齐 TVBoxOS Spider.java，
 * 真实 dex 加密在 assets 里静态扫不到，按参考项目完整桩面补齐）：
 * ```
 * initApi(Lcom/github/catvod/crawler/SpiderApi;)V
 * cancelByTag()V
 * proxy(Ljava/util/Map;)[Ljava/lang/Object;
 * ```
 * 新增/改动桩方法时，请同步更新本测试并在注释里写明来源。
 */
class SpiderStubAbiTest {

    /** 桩里声明的全部方法名（用于反向检查：jar 引用的一个都不能少） */
    private fun stubMethods() = Spider::class.java.declaredMethods.associateBy { it.name }

    // ---------------- 五接口：返回类型必须是 String ----------------

    @Test
    fun `homeContent 描述符必须是 (Z)String`() {
        val m = Spider::class.java.getDeclaredMethod("homeContent", Boolean::class.javaPrimitiveType)
        assertEquals("homeContent", m.name)
        // 回归固化：曾写成 Any?，描述符变成 Ljava/lang/Object; → NoSuchMethodError
        assertEquals("java.lang.String", m.returnType.name)
        assertTrue("homeContent 应为非 final（jar 需 override）", !java.lang.reflect.Modifier.isFinal(m.modifiers))
    }

    @Test
    fun `homeVideoContent 描述符必须是 ()String`() {
        val m = Spider::class.java.getDeclaredMethod("homeVideoContent")
        assertEquals("java.lang.String", m.returnType.name)
    }

    @Test
    fun `categoryContent HashMap 形态描述符必须是 (String,String,Z,HashMap)String`() {
        val m = Spider::class.java.getDeclaredMethod(
            "categoryContent",
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            HashMap::class.java,
        )
        assertEquals("java.lang.String", m.returnType.name)
        // 第 4 参必须是 HashMap 而不是 String —— 加固壳正是按 HashMap 编译的
        assertEquals("java.util.HashMap", m.parameterTypes[3].name)
    }

    @Test
    fun `categoryContent String 形态也必须保留`() {
        val m = Spider::class.java.getDeclaredMethod(
            "categoryContent",
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
        )
        assertEquals("java.lang.String", m.returnType.name)
    }

    @Test
    fun `detailContent 两个重载都必须存在`() {
        // 旧协议：String
        assertEquals(
            "java.lang.String",
            Spider::class.java.getDeclaredMethod("detailContent", String::class.java).returnType.name,
        )
        // 新协议：List —— 加固壳走这个
        assertEquals(
            "java.lang.String",
            Spider::class.java.getDeclaredMethod("detailContent", List::class.java).returnType.name,
        )
    }

    @Test
    fun `playerContent 两个重载都必须存在`() {
        assertEquals(
            "java.lang.String",
            Spider::class.java.getDeclaredMethod(
                "playerContent", String::class.java, String::class.java, String::class.java,
            ).returnType.name,
        )
        // vipFlags 为 List 的形态：加固壳走这个
        assertEquals(
            "java.lang.String",
            Spider::class.java.getDeclaredMethod(
                "playerContent", String::class.java, String::class.java, List::class.java,
            ).returnType.name,
        )
    }

    @Test
    fun `searchContent 双签名都必须存在`() {
        assertEquals(
            "java.lang.String",
            Spider::class.java.getDeclaredMethod(
                "searchContent", String::class.java, Boolean::class.javaPrimitiveType,
            ).returnType.name,
        )
        assertEquals(
            "java.lang.String",
            Spider::class.java.getDeclaredMethod(
                "searchContent", String::class.java, Boolean::class.javaPrimitiveType, String::class.java,
            ).returnType.name,
        )
    }

    // ---------------- 生命周期 ----------------

    @Test
    fun `init 两个重载都必须存在`() {
        // 缺单参版时 jar 会报 NoSuchMethodError: No super method init(Landroid/content/Context;)V
        Spider::class.java.getDeclaredMethod("init", android.content.Context::class.java)
        Spider::class.java.getDeclaredMethod("init", android.content.Context::class.java, String::class.java)
    }

    @Test
    fun `destroy 无参`() {
        assertEquals("void", Spider::class.java.getDeclaredMethod("destroy").returnType.name)
    }

    // ---------------- 2026-10-19 增补：initApi / cancelByTag / proxy（wexguard P1） ----------------

    @Test
    fun `initApi 描述符必须是 SpiderApi 单参且返回 void 且非 final`() {
        // TVBoxOS JarLoader.getSpider 在 init(Context, ext) 前调 spider.initApi(new SpiderApi())；
        // jar 侧 override 存字段后调 api.log/multiReq/webParse。缺它就是 NoSuchMethodError。
        val m = Spider::class.java.getDeclaredMethod(
            "initApi",
            com.github.catvod.crawler.SpiderApi::class.java,
        )
        assertEquals("void", m.returnType.name)
        assertTrue("initApi 应为非 final（jar 需 override）", !java.lang.reflect.Modifier.isFinal(m.modifiers))
    }

    @Test
    fun `cancelByTag 描述符必须是 ()V`() {
        val m = Spider::class.java.getDeclaredMethod("cancelByTag")
        assertEquals("void", m.returnType.name)
    }

    @Test
    fun `proxy 描述符必须是 Map 单参返回 Object 数组且默认转发 proxyLocal`() {
        val m = Spider::class.java.getDeclaredMethod("proxy", Map::class.java)
        assertEquals("[Ljava.lang.Object;", m.returnType.name)
        // 行为断言：基类 proxy 默认转 proxyLocal（与 TVBoxOS 一致），空转返回空数组
        val bare = object : Spider() {}
        assertEquals(0, bare.proxy(mapOf("url" to "https://x/y")).size)
    }

    @Test
    fun `SpiderApi 桩类必须存在于 crawler 包`() {
        // 桩缺失时 jar 引用即 NoClassDefFoundError（jar 自建线程上抛，宿主兜不住）
        val cls = Class.forName("com.github.catvod.crawler.SpiderApi")
        listOf("log", "multiReq", "webParse", "getAddress", "getPort", "getScreenOrientation")
            .forEach { name ->
                assertTrue("SpiderApi 缺少方法 $name", cls.methods.any { it.name == name })
            }
    }

    @Test
    fun `Proxy 桩必须有 static set_int`() {
        // TVBoxOS JarLoader.injectProxyPort 反射调 com.github.catvod.Proxy.set(int)
        val cls = Class.forName("com.github.catvod.Proxy")
        val m = cls.getMethod("set", Int::class.javaPrimitiveType)
        assertTrue("Proxy.set 必须 static", java.lang.reflect.Modifier.isStatic(m.modifiers))
    }

    // ---------------- 生态扩展接口（dexdump 实测，缺一个崩一个） ----------------

    @Test
    fun `action 描述符必须是 (String)String`() {
        val m = Spider::class.java.getDeclaredMethod("action", String::class.java)
        assertEquals("java.lang.String", m.returnType.name)
    }

    @Test
    fun `liveContent 描述符必须是 (String)String`() {
        val m = Spider::class.java.getDeclaredMethod("liveContent", String::class.java)
        assertEquals("java.lang.String", m.returnType.name)
    }

    @Test
    fun `isVideoFormat 描述符必须是 (String)boolean`() {
        val m = Spider::class.java.getDeclaredMethod("isVideoFormat", String::class.java)
        assertEquals("boolean", m.returnType.name)
        assertEquals("Z", jvmDescriptor(m.returnType))
    }

    @Test
    fun `manualVideoCheck 描述符必须是 ()boolean`() {
        val m = Spider::class.java.getDeclaredMethod("manualVideoCheck")
        assertEquals("boolean", m.returnType.name)
    }

    @Test
    fun `proxyLocal 返回类型必须是 Object 数组`() {
        // 注意不是 String：写错会 ClassCastException/NoSuchMethodError
        val m = Spider::class.java.getDeclaredMethod("proxyLocal", Map::class.java)
        assertEquals("[Ljava.lang.Object;", m.returnType.name)
    }

    // ---------------- 宿主网络栈出口（static，jar 用 invoke-static 调） ----------------

    @Test
    fun `client 必须是 static 且返回 OkHttpClient`() {
        val m = Spider::class.java.getDeclaredMethod("client")
        assertTrue("client 必须 static（jar 用 invoke-static 调）",
            java.lang.reflect.Modifier.isStatic(m.modifiers))
        assertEquals("okhttp3.OkHttpClient", m.returnType.name)
    }

    @Test
    fun `safeDns 必须是 static 且返回 okhttp3 Dns`() {
        val m = Spider::class.java.getDeclaredMethod("safeDns")
        assertTrue("safeDns 必须 static", java.lang.reflect.Modifier.isStatic(m.modifiers))
        assertEquals("okhttp3.Dns", m.returnType.name)
    }

    // ---------------- 兜底：jar 引用的方法一个都不能少 ----------------

    @Test
    fun `jar 引用的全部方法都在桩中存在`() {
        val declared = stubMethods().keys
        // dexdump 提取的完整清单 + 2026-10-19 TVBoxOS 对齐增补（initApi/cancelByTag/proxy）
        val required = listOf(
            "init", "destroy",
            "homeContent", "homeVideoContent", "categoryContent",
            "detailContent", "playerContent", "searchContent",
            "action", "liveContent", "isVideoFormat", "manualVideoCheck", "proxyLocal",
            "initApi", "cancelByTag", "proxy",
            "client", "safeDns",
        )
        val missing = required.filterNot { it in declared }
        assertTrue("桩缺少 jar 引用的方法：$missing", missing.isEmpty())
    }

    // ---------------- SpiderDebug：jar 唯一引用的桩，签名错/缺就崩 ----------------

    @Test
    fun `SpiderDebug 必须有 log(Throwable) 静态方法`() {
        // 真机闪退回归（2026-10-14）：jar 的 OkHttp onFailure 回调调
        // `SpiderDebug.log(t)`，签名 log(Ljava/lang/Throwable;)V。
        // 桩里只有 log(String) 时抛 NoSuchMethodError（Error，jar 自建线程上
        // 无人捕获 → 进程死）。解 fan.txt 的 dex method_ids 确认 jar 对
        // SpiderDebug **只引用这一个方法**，故这条测试就是那次崩溃的守门员。
        val m = com.github.catvod.crawler.SpiderDebug::class.java
            .getDeclaredMethod("log", Throwable::class.java)
        assertTrue(
            "log(Throwable) 必须是 static（jar 用 invoke-static 调）",
            java.lang.reflect.Modifier.isStatic(m.modifiers),
        )
        assertEquals("log(Throwable) 返回 void", "void", m.returnType.name)
    }

    @Test
    fun `SpiderDebug 的 log 重载每个签名都有 static 变体`() {
        // 注意不能断言"全部 log 方法都是 static"：Kotlin object 的 @JvmStatic
        // 可能同时生成静态桥与实例方法，那种断言会误判。
        // 真正的 ABI 要求是 —— **jar 用 invoke-static 调，所以每个签名至少有一个 static 变体**。
        val logs = com.github.catvod.crawler.SpiderDebug::class.java
            .declaredMethods.filter { it.name == "log" }
        assertTrue("SpiderDebug 至少要有 log(Throwable) 与 log(String)", logs.size >= 2)
        val signatures = logs.groupBy { it.parameterTypes.map { p -> p.name } }
        signatures.forEach { (params, overloads) ->
            assertTrue(
                "log(${params.joinToString()}) 必须有 static 变体（jar 用 invoke-static 调）",
                overloads.any { java.lang.reflect.Modifier.isStatic(it.modifiers) },
            )
        }
    }

    @Test
    fun `五接口返回类型全为 String 而非 Object`() {
        // 一次性守住最容易回归的点：谁把某个接口改回 Any? 都会在这里炸
        val mustBeString = listOf(
            "homeContent", "homeVideoContent", "categoryContent",
            "detailContent", "playerContent", "searchContent", "action", "liveContent",
        )
        stubMethods().filterKeys { it in mustBeString }.forEach { (name, m) ->
            assertEquals("$name 返回类型必须是 String", "java.lang.String", m.returnType.name)
        }
    }

    /** 简化的 JVM 描述符（只看基元类型，用于验证 Z/V 这类单字母） */
    private fun jvmDescriptor(t: Class<*>): String = when (t.name) {
        "boolean" -> "Z"
        "byte" -> "B"
        "char" -> "C"
        "short" -> "S"
        "int" -> "I"
        "long" -> "J"
        "float" -> "F"
        "double" -> "D"
        "void" -> "V"
        else -> "L${t.name};"
    }
}