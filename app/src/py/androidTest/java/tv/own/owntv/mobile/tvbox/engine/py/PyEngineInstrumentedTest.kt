package tv.own.owntv.mobile.tvbox.engine.py

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.own.owntv.mobile.tvbox.config.model.SiteConfig
import java.io.File

/**
 * PyEngine 的运行时用例（docs/TVbox-PY源迁移方案.md §6：模块加载成功 / 无 Spider 子类 / 调用超时）。
 *
 * ## 为什么放 androidTest 而不是 JVM 单测
 * CPython 解释器只存在于 Android 运行时（Chaquopy 的 libpython.so 是 Android ABI），
 * JVM 单测起不了它；这几条用例必须在真机/模拟器上跑（`connectedStandardDebugAndroidTest`）。
 * 归一化契约与路由判据在 JVM 侧，见 [PyResultContractTest] / [PyEngineRoutingTest]。
 */
@RunWith(AndroidJUnit4::class)
class PyEngineInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        PyEngine.ensurePythonStarted(context)
    }

    /** 写一份临时用户脚本到应用私有目录（模拟下载/本地落盘产物） */
    private fun writeScript(name: String, source: String): File =
        File(context.filesDir, "pytest/$name").apply {
            parentFile?.mkdirs()
            writeText(source)
        }

    @Test
    fun loadSpider_success() {
        val script = writeScript(
            "ok.py",
            """
            from base.spider import Spider

            class Spider(Spider):
                def homeContent(self, filter):
                    return {"class": [{"type_id": "1", "type_name": "测试"}], "list": []}
            """.trimIndent(),
        )
        val loader = Python.getInstance().getModule("base.loader")
        val inst = loader.callAttr("load_spider", script.absolutePath, "test_ok")
        assertNotNull(inst)

        // 返回值桥：dict → JSON 文本，字段名走生态契约（class/list/vod_*）
        val json = loader.callAttr("dumps", inst.callAttr("homeContent", false)).toString()
        assertTrue("返回值应含分类名，实际：$json", json.contains("测试"))
    }

    @Test
    fun loadSpider_rejectsScriptWithoutSubclass() {
        // 只 import 基类、没有定义 Spider 子类 —— 生态里属于坏脚本，必须报明确错误
        val script = writeScript("bad.py", "from base.spider import Spider\n")
        val loader = Python.getInstance().getModule("base.loader")
        val e = runCatching {
            loader.callAttr("load_spider", script.absolutePath, "test_bad")
        }.exceptionOrNull()
        assertNotNull("未定义 Spider 子类应抛异常", e)
        assertTrue(
            "异常应说明原因，实际：${e?.message}",
            e!!.message.orEmpty().contains("未定义 Spider 子类"),
        )
    }

    /**
     * 裸类鸭子类型形态（fun.json 生态写法）必须能加载并调用。
     *
     * clun.top/fun.json 的 28 个 py 源全是 `class Spider:` 不继承任何宿主基类
     * （fish2018/lib 系，自称"五壳通用独立 Spider"）。硬挑继承时会全部报
     * 「未继承 base.spider.Spider」；动态混入 BaseSpider 兜底后脚本自身方法优先、
     * 未实现的接口落基类空实现。这条守门员防回归。
     */
    @Test
    fun loadSpider_acceptsBareDuckTypedClass() {
        val script = writeScript(
            "bare.py",
            """
            class Spider:
                def homeContent(self, filter):
                    return {"class": [{"type_id": "1", "type_name": "裸类源"}], "list": []}

                def playerContent(self, flag, id, vipFlags=None):
                    return {"parse": 0, "url": "http://example.com/v.mp4"}
            """.trimIndent(),
        )
        val loader = Python.getInstance().getModule("base.loader")
        val inst = loader.callAttr("load_spider", script.absolutePath, "test_bare")
        assertNotNull("裸 class Spider: 应能加载", inst)

        // 脚本自身方法优先（混入不覆盖脚本实现）
        val json = loader.callAttr("dumps", inst.callAttr("homeContent", false)).toString()
        assertTrue("裸类 homeContent 应生效，实际：$json", json.contains("裸类源"))

        // 未实现的方法落到基类空实现（返回 None），不是 AttributeError
        val miss = inst.callAttr("searchContent", "x", false, "1")
        assertNull("未实现的 searchContent 应回落基类空实现返回 None", miss)
    }

    @Test
    fun loadSpider_rejectsClassWithoutAnyInterface() {
        // 类名叫 Spider 但一个五接口都没实现 —— 随机类撞名，必须拒
        val script = writeScript("empty.py", "class Spider:\n    x = 1\n")
        val loader = Python.getInstance().getModule("base.loader")
        val e = runCatching {
            loader.callAttr("load_spider", script.absolutePath, "test_empty")
        }.exceptionOrNull()
        assertNotNull("无五接口的类应抛异常", e)
        assertTrue(
            "异常应说明原因，实际：${e?.message}",
            e!!.message.orEmpty().contains("未实现任何五接口方法"),
        )
    }

    @Test
    fun call_timeoutStopsWaitingButDoesNotCrash() {
        val script = writeScript(
            "slow.py",
            """
            import time
            from base.spider import Spider

            class Spider(Spider):
                def homeContent(self, filter):
                    time.sleep(30)
                    return {"list": []}
            """.trimIndent(),
        )
        val loader = Python.getInstance().getModule("base.loader")
        val inst = loader.callAttr("load_spider", script.absolutePath, "test_slow")
        val site = SiteConfig(key = "slow", name = "慢源", type = 3, api = "slow.py")
        // 注入小超时下限：max(500ms, 无 timeout 声明 + 5s 余量) = 5s 内必须返回
        val spider = PySpider(site, inst, loader, PySpider.Floors(homeMs = 500))

        val start = System.currentTimeMillis()
        val result = runBlocking { spider.homeContent(false) }
        val elapsed = System.currentTimeMillis() - start

        assertNull("超时应按'无数据'返回 null", result)
        assertTrue("超时后应立即返回，实际耗时 ${elapsed}ms", elapsed < 15_000)
    }

    /**
     * searchContent 运行时链路。
     *
     * UI 侧 `adb shell input text` 不支持中文；这里用与北京.py 同构的 searchContent
     * （cid 英文匹配）在设备上直接验证：参数传递（key/quick/pg）→ 返回值桥 →
     * 归一化 → 命中条目。中文关键词的匹配逻辑是纯 Python 侧行为，与链路无关。
     */
    @Test
    fun searchContent_returnsNormalizableResult() {
        val script = writeScript(
            "search.py",
            """
            from base.spider import Spider

            CHANNELS = {'bjws': ['x', '北京卫视'], 'kaku': ['y', '卡酷少儿']}

            class Spider(Spider):
                def searchContent(self, key, quick=False, pg='1'):
                    keyword = str(key or '').strip().lower()
                    if not keyword:
                        return {'list': []}
                    videos = []
                    for cid, ch in CHANNELS.items():
                        if keyword in cid.lower() or keyword in ch[1].lower():
                            videos.append({
                                'vod_id': cid, 'vod_name': ch[1],
                                'vod_pic': '', 'vod_remarks': '直播',
                            })
                    return {'list': videos}
            """.trimIndent(),
        )
        val loader = Python.getInstance().getModule("base.loader")
        val inst = loader.callAttr("load_spider", script.absolutePath, "test_search")
        val site = SiteConfig(key = "search", name = "搜索源", type = 3, api = "search.py")
        val spider = PySpider(site, inst, loader)

        val hit = runBlocking { spider.searchContent("bjws", quick = false, pg = "1") }
        assertNotNull("英文频道 id 应能命中", hit)
        assertEquals("bjws", hit!!.list[0].id)
        assertEquals("北京卫视", hit.list[0].name)

        val miss = runBlocking { spider.searchContent("zzzz", quick = false, pg = "1") }
        assertNotNull("未命中也应返回可归一化的空结果", miss)
        assertTrue(miss!!.list.isEmpty())
    }

    /**
     * 协程模式守门员（PySpider.call 的骨架复刻）：同步阻塞调用 + 超时脱身。
     *
     * 这个模式是实测三次弯路后定下来的（详见 PySpider.call 的注释）：
     * `async(IO){阻塞}.await()` 会因 async 是子 Job 被结构化并发拖满全程，
     * 必须用 suspendCancellableCoroutine + 独立线程：超时到点协程立即返回 null，
     * 线程里跑完的调用无人等待。若有人把 PySpider.call 改回 async 模式，这条会先红。
     */
    @Test
    fun timeoutPattern_suspendCancellableCoroutineEscapesBlockingThread() {
        val start = System.currentTimeMillis()
        val outcome = runBlocking {
            withTimeoutOrNull(3_000) {
                suspendCancellableCoroutine<String?> { cont ->
                    Thread {
                        Thread.sleep(10_000)
                        cont.resumeWith(runCatching { "done" })
                    }.start()
                }
            }
        }
        val elapsed = System.currentTimeMillis() - start
        assertNull("超时应返回 null", outcome)
        assertTrue("协程应在 3s 立即脱身，实际耗时 ${elapsed}ms", elapsed < 6_000)
    }
}