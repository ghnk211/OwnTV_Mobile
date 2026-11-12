# TVbox JS 源（TVBox 点播脚本源）迁移方案

> 迁移源：`D:\AndroidStudioProjects\git\video_on_demand` **main 分支** @`4c8d289`（下称 **VOD**）
> 迁移目标：`D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile` **mobile-mini 分支** @`59ddf14`（下称 **Mobile**）
> 范围：**只做 TVbox JS 源**（`api`/`ext` 指向 `.js` 的站点，含 drpy2 运行时与单文件 JS）。**不含 py 源、jar 源改造、直播 JS 源、投屏/下载/网盘、TV 双 UI**。
> 本文只出方案，不改代码。日期：2026-11-12（**v2：逐条对代码核实后修订**）

---

## 0. 基线与前置结论

### 0.1 分支基线

| 仓库 | 日常改动分支 | 当前提交 | 说明 |
|---|---|---|---|
| OwnTV_Mobile | `mobile-mini`（跟踪 `fork/mobile-mini`） | `59ddf14` | 本次全部改动落在这里；**不新建分支** |
| OwnTV_Core | `mobile`（跟踪 `origin/mobile`） | `c6f90356` | **本次不改** |
| `vendor/OwnTV_Core` | —（subtree 快照） | `b1aa2ee` | 这是 **Mobile 侧的 subtree squash 提交**，其 `git-subtree-split` = `c6f90356`，与 Core `mobile` HEAD 一致。**不要手工改**，否则污染下次 `subtree pull` |
| video_on_demand | 分析对象 `main` | `4c8d289` | 该工作区**实际停在 `feature/tv`@`1d0563e`**；本次全部通过 `git show 4c8d289:<path>` 只读取证，**未切换其分支**。`feature/tv` 是 VOD 自己的 TV UI 分支，不作为移植依据 |

### 0.2 三条已核实的现状（决定方案形态）

1. **Mobile 已移植 VOD 的绝大部分 TVbox 解析栈**（`tvbox/` 下约 13.5k 行：`config/parser`、`engine/cms`、`engine/jar`、`engine/proxy`、`engine/sniff`、`index`、`play`、`repository`、`net`、`util`），**唯独缺 `engine/js/**`**。
2. **接线口已预留**：`Engine.isInitialized(site)` 默认实现（`engine/Spider.kt:87-89`，注释明写"JsEngine 覆写为查 context 就绪状态"）、`EngineKind.SPIDER_JS`（`engine/Spider.kt:64`）、`SourceRepository.aggregateSearch` 的"跳过未 init 的 JS 源"分支（`:380-393`）、`CmsEngine.supports` 显式让出 `.js`（`engine/cms/CmsSpider.kt:481-482`）、`NetApi.request` 注释"drpy JS 侧 `req(url, obj)` 的唯一落点"（`net/NetApi.kt:167`）、`SecureKv.PREFS_DRPY`（`data/prefs/SecureKv.kt:15`）。**本方案把这些坑填上。**
3. **站点可见性有三套口径，且互相矛盾**（不是 v1 说的"双口径"）：

   | 位置 | 判据 | JS 站点结果 |
   |---|---|---|
   | `SiteConfig.isSupported`（`config/model/TvBoxConfig.kt:171-176`） | `type==3 → spiderClassName != null \|\| isJsSite` | **true** |
   | `TvboxHomeViewModel`（`ui/screens/vod/TvboxViewModels.kt:230`） | `filter { it.isSupported }` | **可见、可选** |
   | `TvboxConfigRepository.resolveHomeSite()`（`:453-454`）/ `setHomeSite()`（`:471`） | `isSupported` | **可被设为首页源** |
   | `SourceRepository.supportedSites()`（`:100-103`） | `it.isSupported && engineFor(it) != null` | **被剔除**（无 JsEngine） |

   净效果：JS 源**在源列表里可选、可当首页源，但一点开必然失败**。收口方案见 §2.6。

### 0.3 一条硬约束：QuickJS 2.1.0 的 `.so` **不是 16KB 页对齐**

实测 `wrapper-android-2.1.0.aar`（本机 Gradle 缓存）：

| ABI | 文件名 | 字节数 | `PT_LOAD p_align` |
|---|---|---|---|
| arm64-v8a | `libquickjs-android-wrapper.so` | 1,288,336 | `0x1000`（4KB） |
| armeabi-v7a | 同上 | 898,744 | `0x1000` |
| x86 | 同上 | 1,394,368 | `0x1000` |
| x86_64 | 同上 | 1,460,640 | `0x1000` |

- 文件名是 **`libquickjs-android-wrapper.so`**，不是 v1 写的 `libquickjs.so`。
- 四个 ABI 齐全，与现有 `standard`(arm64-v8a + armeabi-v7a) / `x86_64` 两个 flavor（`app/build.gradle.kts:74-84`）对得上，**模拟器可测**。
- 但 **`targetSdk = 36`**（`app/build.gradle.kts:36`）：在 16KB 页设备上，未对齐的 `.so` 会 `dlopen` 失败。**4KB 页设备（含绝大多数现有真机与常规 x86_64 模拟器镜像）不受影响，所以模拟器测试通过 ≠ 合规**。

三个选项（**阶段一必须先定，见 §6**）：

| 选项 | 做法 | 代价 | 结论 |
|---|---|---|---|
| **A（首选）** | 升级 `wang.harlon.quickjs` 到已做 16KB 对齐的版本（Maven Central 有 `3.2.3`，2025-07-01） | 本机缓存**只有 2.1.0**，需联网下载（**须用户确认**）；跨大版本 API 需重新核对（`compileModule` / `ModuleLoader.isBytecodeMode` / `setMemoryLimit` / `setMaxStackSize`），VOD 的 2.1.0 写法可能要改 | 唯一能真正满足 targetSdk 36 的路 |
| B | 保留 2.1.0，用 NDK r27+ 自行重编对齐版 `.so` | 需要 wrapper 源码 + NDK 工具链，成本远超本次迁移 | 不取 |
| C（兜底，**无论选谁都必做**） | 保留 2.1.0 + 运行时捕获 `UnsatisfiedLinkError`，把 JS 源整体判为不可用 | 16KB 设备上 JS 源不可用（CMS/jar 不受影响） | 作为 A 未落地前的安全网；**必须实现，否则 JS 源会崩进程而不是降级** |

> C 的落地点：`JsEngine` 构造时做一次 native 可用性探测并缓存结果；不可用时 `supports(site)` 返回 `false`。这样 §2.6 的三处可见性口径自动一致（不会出现"能选但必崩"）。`UnsatisfiedLinkError` 是 `Error` 不是 `Exception`，探测处必须显式按 `Throwable` 捕获。

### 0.4 核实方式与**尚未核实**的事项

取证方式：Mobile 侧直接读工作区（`mobile-mini`@`59ddf14`）；VOD 侧 `git show 4c8d289:<path>`；`.so` 用自写 ELF 头解析读 `PT_LOAD p_align`（ELF32/ELF64 分别取 `e_phoff`/`e_phentsize`/`e_phnum`，字段宽度按位宽区分）；依赖版本用 Gradle 缓存目录核对。

**以下 5 项本文未核实，实施前必须补，不要当成已知事实：**

1. `quickjs 3.2.3` 与 `2.1.0` 的 API 差异（本机缓存无 3.2.3，未下载、未比对）。
2. drpy2 运行时脚本在本仓 wrapper 上的实际兼容性（VOD 已验证，本仓未跑过真机）。
3. release（R8，`isMinifyEnabled=true`，`app/build.gradle.kts:136-137`）下 QuickJS 的 JNI/反射绑定是否完好；AAR 的 consumer 规则未逐条核对。
4. 目标真机是否 16KB 页（`adb shell getconf PAGE_SIZE`）——未连设备。
5. `TbLog` 在 release 是否落盘、是否已有脱敏（§5.5 的日志要求依赖这一点）。

---

## 1. 两个项目的模块对应关系

### 1.1 迁移映射表

VOD 侧行数已逐个核对（`git show 4c8d289:` 后计数），与下表一致。

| VOD 模块（main @4c8d289） | 行数 | Mobile 落点 | 处理方式 |
|---|---|---|---|
| `engine/js/JsEngine.kt` | 100 | `tvbox/engine/js/JsEngine.kt` | **移植 + 改造**：`supportsSite` 判据保留；新增 native 可用性探测（§0.3 C） |
| `engine/js/JsSpider.kt` | 361 | `tvbox/engine/js/JsSpider.kt` | **改造**：日志改 `TbLog`；`proxy://` 改写走 `JsProxyBridge.baseUrl()`；脚本下载**按 scheme 分流**（§2.7，不是"统一走 AddressFetcher"） |
| `engine/js/JsRuntime.kt` | 311 | `tvbox/engine/js/JsRuntime.kt` | 原样移植（QuickJS context + 单线程 executor + 模块加载器 + 字节码缓存） |
| `engine/js/JsBridge.kt` | 378 | `tvbox/engine/js/JsBridge.kt` | **改造**：`req/fetch` → `NetApi.request`；**SSRF 需自行加固**（本仓 `isPrivateHost` 只拦 IP 字面量，见 §5.4）；日志改 `TbLog` |
| `engine/js/JsProxyRegistry.kt`（含 `JsProxyUrls`/`TokenFactory`） | 165 | `tvbox/engine/js/JsProxyBridge.kt` | **重写**：不自带 HTTP 服务，挂到既有 `JarProxyHub`（§2.5） |
| `engine/js/JsProxyServer.kt` | 254 | — | **不移植**；Range/token 能力并入 `JarProxyServer`；**空闲回收不移植**（Mobile 无此机制，见 §2.5 第 9 条） |
| `engine/js/JsProxyCodec.kt` | 53 | `tvbox/engine/js/JsProxyCodec.kt` | 原样移植（经典 drpy 数组形态 + cat 对象形态） |
| `engine/js/JsModuleCache.kt` | 92 | `tvbox/engine/js/JsModuleCache.kt` | 原样移植；目录改 `FileUtil.jsDir()`（需新增） |
| `engine/js/JsLocalStore.kt` | 42 | `tvbox/engine/js/JsLocalStore.kt` | 原样移植；`SecureKv.PREFS_DRPY` 已存在（**明文 SP**，见 §5.2） |
| `engine/js/JsUrl.kt` | 71 | `tvbox/engine/js/JsUrl.kt` | 原样移植（**必须移植**，QuickJS 无 WHATWG `URL`，见 C1） |
| `engine/js/DrpyHtmlParser.kt` | 124 | `tvbox/engine/js/DrpyHtmlParser.kt` | 原样移植；需新增 `org.jsoup` 依赖 |
| `engine/js/JsWarmUp.kt` | 98 | `tvbox/engine/js/JsWarmUp.kt` | 原样移植；改 Koin 注入；**排序不能依赖 `SourceHealth.rank`（本仓无此 API）** |
| `di/ServiceLocator.kt`（js 段） | — | `tvbox/di/TvboxModule.kt` + `tvbox/di/TvboxRuntime.kt` | **改造**：Koin `single` + 生命周期钩子 |
| `repository/SourceRepository.kt`（js 段） | — | `tvbox/repository/SourceRepository.kt` | **小改**：加 `jsWarmUp` 参数并真正触发插队预热；加 `canServe()` |
| `repository/ConfigRepository.kt`（`onJsWarmUp`） | — | `tvbox/repository/TvboxConfigRepository.kt` | **小改**：新增钩子 + `applyConfig` 触发 + `isPlayable` 谓词 + `activeSourceUrl()` |
| `engine/proxy/*` 对应物 | — | `tvbox/engine/proxy/JarProxyHub.kt` + `JarProxyServer.kt` | **小改**：JS 分发钩子 + 字节/字符串响应 + Range + token |
| `util/TextUtil` / `Md5Util` / `toHeaderMap` / `SourceHealth` | — | 已存在等价实现 | 复用（`SourceHealth` 在 `util/CoroutineUtil.kt:23`，只有 `state/isCooling/recordSuccess/recordFailure/clearCooldown`） |
| `engine/jar/JarResultParser.kt` | 630 | 已移植（本仓同为 630 行） | **复用**：`toResult`(:63) / `toPlayResult`(:108) / `toMovie`(:165) / `describe`(:607)，**不要另写一份** |
| `engine/PlayNormalize.kt` | 82 | 已移植 | 不接入（VOD 的 JsSpider 走 JarResultParser，保持一致） |

### 1.2 不迁移清单（范围红线）

- `engine/py/**`、`app/src/python/**`、Chaquopy / `PyEngineHolder`：**py 源不接**。
  > 更正：本仓 **没有 `isPySite` 字段**。`SiteConfig.isSupported`（`TvBoxConfig.kt:171-176`）对 `type==3` 的判据是 `spiderClassName != null || isJsSite`，`.py` 站点因 `spiderClassName == null` 且 `isJsSite == false` 自然落到 `false`。**不要为了"排除 py"去加字段。**
- `live/**`（含 `LiveSpiderBridge` 的 JS 分支）、`cast/**`、`download/**`、`subtitle/**`、`danmu/**`、`alist/**`、`server/**`、`update/**`。
- VOD 的 `ui/**`、`vm/**`、`player/**`：Mobile 用 Core 播放器 + 自己的 Compose 页面。
- `JsProxyServer.kt`：不复制第二个回环 HTTP 服务。
- **`vendor/OwnTV_Core` 与 Core Room schema：零改动。**

---

## 2. 调用流程对应关系

### 2.1 源导入

```
[VOD]
ConfigScreen → ConfigViewModel.importFromUrl
  → ConfigRepository.importFromUrl
      → fetchConfig(address)                        // http / clan / file
      → FindResult.decode(text, configKey)          // 明文 / ;pk; / AES
      → LocalSourceResolver.fixContentPath(decoded, dirPrefix)   // "./" → 配置目录前缀
      → ConfigParser.parse(fixed)                   // sites/parses/rules/proxy/doh/lives
      → saveConfig(...) → applyConfig(config)
            ├─ onConfigApplied  → sniff 规则、ads、proxy
            ├─ onSpiderWarmUp   → jarEngine.warmUp()
            └─ onJsWarmUp       → jsWarmUp.warmAll(sites, priorityKeys, rank)   ★
      → launchSpiderPrefetch(config, sourceUrl)     // 仅 jar

[Mobile 现状]
TvboxConfigViewModel.importFromUrl (ui/screens/vod/TvboxViewModels.kt:88)
  → TvboxConfigRepository.importFromUrl (:295)
      → fetchConfig (:226) → FindResult.decode → fixContentPath (:328) → ConfigParser.parse
      → saveConfig (:405-431，:427 调 applyConfig) → applyConfig (:433-446)
            ├─ onConfigApplied  → TvboxRuntime 挂 sniffEngine.setGlobalRules (:37-39)
            ├─ onSpiderWarmUp   → jarEngine.warmUp() (:40)        ← 有，条件是 config.spider 非空 (:440)
            └─ onJsWarmUp       → ❌ 缺                            ★ 本次新增
      → onConfigsChanged → TvboxSourceProvisioner.syncAll() (:51)  // Core 源行供给，JS 无需改
```

**结论**：导入链路只需加一个 `onJsWarmUp` 钩子（与 `onSpiderWarmUp` 同构），解析/解密/缓存/去重/相对路径全部现成。JS 源导入后**不落任何新表**，配置 JSON 原样存 `files/tvbox/configs/{id}.json`。

**关键差异**：jar 预热的触发条件是 `if (!config.spider.isNullOrBlank())`（`:440`）。**纯 JS 配置没有 `spider` 字段**，所以 JS 预热必须独立按"存在 `isJsSite` 站点"触发，不能复用这个 if。

### 2.2 源解析（站点 → 引擎 → 五接口）

```
[VOD]
SourceRepository.supportedSites() → engineFor(site) = engines.firstOrNull { supports(site) }
    engines = [CmsEngine, JarEngine, JsEngine]
SourceRepository.call(site) {
    engine.obtain(site)                     // JsEngine: LRU(4) 内复用 JsSpider
    spider.init(site.ext)                   // 单独 20s 预算，不计健康
    block(spider)                           // home/category/detail/search/play
}
JsSpider.init:
    downloadScripts()  → runtime(api) ∥ rule(ext) ∥ 静态 import 依赖
    JsRuntime.init(runtimeSource, runtimeUrl, ruleSource, ruleUrl)
        ├─ QuickJSContext.create() + setMemoryLimit(64MB) + setMaxStackSize(512KB)
        ├─ JsBridge.installAll()   // console / req-fetch / local / setTimeout / pdfh-pdfa-pd / getProxy-js2Proxy
        ├─ 模块模式：setModuleLoader + import(runtimeUrl) → globalThis.__drpyModule
        ├─ drpy2: __drpyModule.init(ext 源码)
        └─ __vodInvoke(name, argsJson)  —— 统一调用桥
JsSpider.<api>()  →  call("home"|"category"|...) → JarResultParser.toResult(...) → SpiderResult
```

**Mobile 改造后**同形，唯一差异是 `engines` 由 Koin 装配。当前是 `TvboxModule.kt:107`：

```kotlin
// 现状
engines = listOf<Engine>(get<CmsEngine>(), get<JarEngine>()),
// 改为
engines = listOf<Engine>(get<CmsEngine>(), get<JarEngine>(), get<JsEngine>()),
```

路由边界（已逐条核对，**天然互斥**）：

| 引擎 | 判据 | 位置 |
|---|---|---|
| `CmsEngine` | `type in {0,1,4} && api.isNotBlank() && !site.apiIsJs` | `engine/cms/CmsSpider.kt:481-482` |
| `JarEngine` | `type == 3 && !site.isJsSite && spiderClassName != null` | `engine/jar/JarEngine.kt:81-82` |
| `JsEngine`（新） | `apiIsJs \|\| (type == 3 && isJsSite)`，再叠加 native 可用 | 移植 VOD `supportsSite` |

其中 `isJsSite = apiIsJs || (type == 3 && api.contains("drpy", true))`（`TvBoxConfig.kt:110-111`），`apiIsJs` 见 `:106-107`。`JsEngine` 仍排在 `JarEngine` 之后（与 VOD 一致，防未来判据漂移）。

### 2.3 内容展示

`SpiderResult` → `TvboxHomeViewModel` / `TvboxDetailViewModel` → `TvboxIndexer` 写 Core 目录 → `VodTuner` 出 Core 目录行。

**这条链路对引擎零感知**：JS 源返回同一个 `SpiderResult(list, classes, filters, total)`，由 `JarResultParser.toResult` 归一。**展示层零改动**（唯一例外是 §2.6 的可见性谓词），变化是"JS 源从必然失败变成能出内容"。

### 2.4 播放

```
[VOD]
PlayViewModel.resolveAndPlay:
  冷却 → playerContent → 站点 playUrl 前缀 → proxy:// 改写(6 处散点) → 命名解析器 → 嗅探 → 直连
JsSpider.playerContent:
  call("play", flag, id, flags) → JarResultParser.toPlayResult
    → directStreamGuard（parse=1 但地址是流 → 改 0）
    → rewriteProxy（proxy:// → http://127.0.0.1:PORT/proxy?do=js&token=...&...）

[Mobile 改造后]
TvboxPlayResolver.resolve (play/TvboxPlayResolver.kt:53)
  冷却 → playerContent → applyPlayUrlPrefix(:181-190)
  → JarProxyHub.isSelfProxyUrl 本机代理闸门(:89-93)
  → ParseRepository 命名解析器(:96-97) → SniffEngine(:113-114) → direct(:128-131)
  → ResolvedPlay → playTvbox
JsSpider.playerContent（同 VOD）：归一 + 直链卫 + proxy:// 改写（目标改 JsProxyBridge.baseUrl()）
```

Mobile 已把"本机回环代理"收口在**一个闸门**里（`TvboxPlayResolver.kt:89-93`）：

```kotlin
val localProxy = JarProxyHub.isSelfProxyUrl(prefixed.url)
if (localProxy && !JarProxyHub.isReady) {
    return Outcome.Error("该源需要本机代理服务，服务未就绪，请重试或切换线路")
}
```

**因此 JS 的 `proxy://` 改写必须吐 `JarProxyHub` 认得的地址**（`http://127.0.0.1:<port>/proxy?...do=...`），否则闸门判 `isReady=false` 直接失败。

### 2.5 代理链路（本方案唯一需要改既有服务的地方）

VOD 是**独立**的 `JsProxyServer`（随机端口 + 会话 token + Range + 60s 空闲回收）。Mobile 已有 `JarProxyHub` + `JarProxyServer`（首选 9978、`/proxy` 单路由、jar spider 实例表、播放前闸门）。

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A（取）** | `JsProxyBridge` 挂进 `JarProxyHub`，共用端口与闸门；`JarProxyServer` 增加字节/字符串响应 + Range + token | 单端口单生命周期；`TvboxPlayResolver` 闸门零改动；jar/js 共用 `isSelfProxyUrl` | 需改 `JarProxyHub`/`JarProxyServer` 约 80–100 行，**jar 侧必须回归** |
| B | 照搬 `JsProxyServer`，独立随机端口 | 1:1 移植 | 两个回环服务、两个端口；闸门要分别探活；"哪个服务活着"成为新状态源 |

**取 A。以下 9 条是核实过的既有实现前提，方案必须按它们写，不能按 v1 的想象写：**

1. **`dispatch()` 按 `siteKey` 路由，不按 `do`**（`JarProxyHub.kt:106-153`；`do` 只出现在日志里）。缺 `siteKey` 时回退 `currentPlaySiteKey`，仍为空则直接 `return null`。⇒ **JS 分发必须在 siteKey 解析之前短路**，否则纯 JS 配置（`currentPlaySiteKey` 只由 `JarSpider.playerContent` 写）第一个请求就 null。
2. **实例表类型是 `ConcurrentHashMap<String, com.github.catvod.crawler.Spider>`**（`:35`）。`JsSpider` 不是 catvod `Spider`，**塞不进去**。⇒ 必须独立 `jsDispatcher` 钩子 + `JsProxyBridge` 自己的 `siteKey → JsSpider` 注册表。
3. **`writeProxy` 只认 `InputStream`**（`JarProxyServer.kt:158`：`val body = rs.getOrNull(2) as? InputStream`）。`ByteArray` / `String` 会被静默当成 null body，回 `Content-Length: 0`。⇒ **ByteArray 与 String 两个分支都要加**（VOD 的 JS `proxy()` 返回整段 `byte[]`，m3u8 文本可能是 `String`）。
4. **token 是会话级、可复用，不是一次性**。VOD `JsProxyServer.kt:31` 生成 `TokenFactory.next()`，`:127` 做相等校验，不等则 403。⇒ HLS 每个分片都带同一 token，**做成一次性会让第二个分片起全部 403**。详见 §5.3。
5. **Range 现状**：`Accept-Ranges: bytes` 恒发（`:199`），但**没有 `parseRange`**；小响应缓冲到 `BUFFER_LIMIT = 2MB`（`:304`）后写准确 `Content-Length`，超限退回无长度流式。⇒ 需新增 `parseRange`（`a-b` / `a-` / `-b`）+ 206 + `Content-Range`；`statusText` 已含 206（`:246`），code 是透传的（`:155`）。
6. **端口与地址判定**：`PORTS = listOf(9978) + (9979..9999)`（`:310`）；`isSelfProxyUrl`（`JarProxyHub.kt:183-192`）要求 `http(s)://127.` 或 `localhost` **且** 含 `/proxy` **且** 含 `do=`，**不认 `[::1]`**。⇒ JS 侧 `baseUrl()` 必须用 `127.0.0.1` 且带 `do=js`。
7. **`ensureStarted()` 只由 `JarEngine`（`:246`/`:268`）调用**。⇒ 纯 JS 配置没有 jar 引擎，服务不会自己起来；`JsSpider.init` 成功后必须自己调一次（幂等）。
8. **`pool` 是一次性 `val`**（`JarProxyServer.kt:56`），`stop()` 调 `pool.shutdownNow()`（`:89`）⇒ **停止后无法重启**（`acceptLoop` 的 `pool.submit` 会抛 `RejectedExecutionException`）。`stop()` 目前**零调用方**。本次不修（列入 §7.3 已知残留），但 **JS 侧任何路径都不得调用 `stop()`**。
9. **Mobile 没有空闲回收**。VOD 的 `IDLE_TIMEOUT_MS = 60_000` 不移植。⇒ **验收项里不要写"退出后 60s 端口回收"**（v1 §8.3 有这条，无法验证）。

落地要点：

- `JarProxyHub` 加 `var jsDispatcher: ((Map<String,String>) -> Array<Any?>?)?`；`dispatch()` **开头**判 `params["do"] == "js"` → 交 JS 并 return；jar 分支（含反射静态 `com.github.catvod.spider.Proxy.proxy(Map)` 兜底，`:164-174`）**一行不改**。
- `JsProxyBridge`：`siteKey → JsSpider` 注册表（保留 VOD 的 `rememberAlias` 兜底）、`baseUrl()`、`js2Proxy(...)`、`dispatch(params)`、`onPlay(siteKey)`；返回 `Object[]{code, mime, body, headers}`，与 jar 侧契约一致。
- `JarProxyServer`：ByteArray/String 分支 + `parseRange` + 206 + `do=js` 的 token 校验（403）。
- `JsSpider.playerContent` 的 `proxy://` 改写走 `JsProxyBridge.baseUrl()`，不自建地址。
- 回归口径：jar 侧 `do=hxq` / `do=ck` / `do=m3u8` 三条真机必验（§9.3）。

### 2.6 站点可见性收口（v1 缺失的落地点）

§0.2 第 3 条的三套口径必须收成一套，否则"迁移完成"只是把失败点从引擎挪到 UI。

**做法（避免 DI 环）**：

1. `SourceRepository` 新增 `fun canServe(site: SiteConfig): Boolean = site.isSupported && engineFor(site) != null`；`supportedSites()`（`:100-103`）改为复用它。
2. `TvboxConfigRepository` 新增 `var isPlayable: (SiteConfig) -> Boolean = { it.isSupported }`；`resolveHomeSite()`（`:454`）与 `setHomeSite()`（`:471`）改用它。
3. `TvboxRuntime` 在 init 里注入 `config.isPlayable = { sourceRepository.canServe(it) }`（Runtime 已同时持有两者，**Repository 不反向依赖 Engine**）。
4. `TvboxHomeViewModel:230` 改用 `configRepository` 暴露的同一谓词，不再直接 `filter { it.isSupported }`。
5. native 不可用时 `JsEngine.supports` 返回 `false`（§0.3 C）⇒ `canServe` 自动为 false ⇒ 三处一致地把 JS 源判为不可用，**不会出现"能选但必崩"**。

**顺带更正一条 v1 断言**：`ConfigParser` 的"当前版本无法播放"警告按 `isSupported` 取反，而 JS 站点今天 `isSupported == true`，所以**它们现在并不会进这个警告**（v1 §8.1 的"JS 站点不再出现在警告里"前提不成立）。本次把警告判据也切到同一谓词：native 可用时不警告，不可用时才提示。

### 2.7 脚本下载分流（v1 的"统一走 AddressFetcher"不可行）

核实 `net/AddressFetcher.kt`：

- `fetchText(address): Outcome = fetch(address)`（**`:76`**）——**只是别名，返回 `ByteArray`，不做任何解码**。
- http 分支走 `net.getBytes(httpUrl)`（`:59-63`），**不带 header、不带 siteKey、不带超时**。drpy 站点普遍需要 `Referer`/`User-Agent`，直接用会大面积 403/空页。

**因此必须按 scheme 分流，两条出口都返回 `String`：**

| 地址形态 | 出口 | 说明 |
|---|---|---|
| `http://` / `https://`（含 `clan://<非 localhost>`，`AddressFetcher` 内部会映射成 http） | `NetApi.getText(url, headers)`（`net/NetApi.kt:46-52`） | 带 `site.header`；按响应 charset 解码并回退 UTF-8（C7） |
| `file://` / `clan://localhost/…` / 裸绝对路径 / `content://` | `AddressFetcher.fetch(address)` 取 bytes，**调用方显式解码** | 解码顺序：BOM → `rule.encoding` → UTF-8；`Outcome.Err(needsPermission=true)` 透出到导入警告（§5.2） |

相对路径兜底：`site.api` / `site.ext` 不含 `://` 且非本地路径时，用 `LocalSourceResolver.resolveAgainst(api, configRepository.activeSourceUrl())`（对齐 `4c8d289` 对 jar 的"使用点解析"修法）。`activeSourceUrl()` **本仓尚不存在**，需新增（`TvboxPrefs.sourceUrlOf`（`:30`）已有但**零调用方**，可作实现基础）。

---

## 3. 需复用 / 调整的类与资源

### 3.1 直接复用（零改动）

| 类 | 用途 |
|---|---|
| `engine.Spider` / `Engine` / `EngineKind` / `SpiderResult` / `PlayResult` / `PlayLine` / `ClassInfo`（均在 `engine/Spider.kt`） | 五接口与数据载体，JS 与 CMS/jar 共用 |
| `engine.jar.JarResultParser`（`toResult`/`toPlayResult`/`toMovie`/`describe`） | JS 结果归一化，**不要另写一份** |
| `engine.PlayUrlCodec` | `$$$`/`#`/`$` 分集线路解析 |
| `engine.CipherUrlDecoder` | Base64 密文播放地址 |
| `engine.ParseRepository` / `engine.sniff.SniffEngine` | 前缀解析器、WebView 嗅探兜底 |
| `repository.SourceRepository` | 超时/健康/缓存/聚合搜索（加 `jsWarmUp` 参数 + `canServe()`） |
| `repository.TvboxConfigRepository` | 导入、缓存、站点表、`ensureSiteJar` |
| `util.LocalSourceResolver`（`fixContentPath`/`resolveAgainst`/`isLocalPath`/`httpDirPrefix`） | 相对 `.js` 路径修正与旧配置兜底 |
| `net.NetApi.getText/request` | 脚本下载（http）、drpy `req()` 落地 |
| `net.AddressFetcher.fetch` | 脚本下载（本地/clan/SAF），**注意 §2.7 的解码责任** |
| `data.prefs.SecureKv`（`PREFS_DRPY = "drpy_local"`） | drpy `local` 落点 |
| `util.FileUtil` / `Md5Util` / `TextUtil.absoluteUrl|looksLikeStream` / `JsonExt.toHeaderMap` / `SourceHealth`（`util/CoroutineUtil.kt:23`） | 工具 |
| `index/*`、`play/TvboxPlaybackRouter` | 索引与播放编排，零改动 |

### 3.2 需要调整（锚点已核实）

| 文件 | 锚点 | 调整 |
|---|---|---|
| `tvbox/di/TvboxModule.kt` | engines `:107`；SourceRepository 参数 `:105-109`；**`TvboxRuntime` 装配 `:147`（现为 7 个 `get()`）** | 新增 `JsEngine`/`JsWarmUp` 两个 `single`；engines 加 `get<JsEngine>()`；`SourceRepository` 传 `jsWarmUp`；**`:147` 改 9 个 `get()`** |
| `tvbox/di/TvboxRuntime.kt` | 构造 `:18-26`（7 参）、init `:36-52`、`onSwitchHook` `:41-47` | 构造 +`jsEngine`/`jsWarmUp`；`onSwitchHook` 追加 `jsEngine.invalidateAll()`；挂 `config.onJsWarmUp`；注入 `config.isPlayable`（§2.6） |
| `tvbox/repository/TvboxConfigRepository.kt` | 钩子 `:141-154`、`applyConfig` `:433-446`（jar 条件 `:440`）、`resolveHomeSite` `:453-454`、`setHomeSite` `:471` | +`onJsWarmUp` 钩子与独立触发条件；+`isPlayable` 谓词；+`activeSourceUrl()` |
| `tvbox/repository/SourceRepository.kt` | 构造 `:48-52`、`supportedSites` `:100-103`、`aggregateSearch` `:353-421`（warming `:380-393`） | +`jsWarmUp: JsWarmUp?`；+`canServe()`；**warming 分支补 `jsWarmUp?.warmSoon(site)`** |
| `ui/screens/vod/TvboxViewModels.kt` | `:230` | 可见性过滤改用统一谓词 |
| `tvbox/engine/proxy/JarProxyHub.kt` | 字段 `:30-38`、`dispatch` `:106-153` | +`jsDispatcher`；`do=js` **前置**短路；jar 分支不动 |
| `tvbox/engine/proxy/JarProxyServer.kt` | `writeProxy` **`:154-225`**（body 取值 `:158`）、常量 `:301-310` | +ByteArray/String 分支、+`parseRange`/206、+`do=js` token 校验 |
| `tvbox/util/FileUtil.kt` | `subDir` `:15-16` | +`fun jsDir(context) = subDir(context, "js")` |
| `gradle/libs.versions.toml` | `[libraries]` gson 行 `:131` 附近 | +`quickjs`/`jsoup` 版本与三条 library |
| `app/build.gradle.kts` | deps `:356-357` | +3 条 `implementation` |
| `app/proguard-rules.pro` | `:34-35`（catvod / `tvbox.engine.**` keep） | 新包已被 `:35` 覆盖；只补说明注释 + 必要 `-dontwarn`（AAR 自带 keep，**未逐条核对，见 §0.4**） |
| `tools/i18n/safe_literals.txt` | 追加（现 2012 行） | `classify-safe` 归类新增日志/协议字面量（约 200 条） |
| `docs/TVbox点播迁移方案.md` | P5 `:478-480`、§2.1 第 6 项 `:99`、QuickJS 行 `:257`、§12.1 P5 行 `:608` | 更新"P5 已落地"与范围说明（**该文 §9 没有"C1"，v1 的"§12.1 C1"引用无效**） |
| `docs/core-changes.md` | 记录段 | 追加"TVbox JS 源：**未改 Core**；引擎来源 VOD `4c8d289`" |

> **warming 分支的真相**：`SourceRepository.kt:380-382` 的注释声称会触发预热，但 `:387-392` 实际只打日志 + 发"预热中，本轮暂不参与"，**没有任何预热调用**。所以 v1 说的"JS 源永远搜不到"是对的，且这是**既有注释与实现不符**，本次一并修正。

### 3.3 资源

- **文案**：JS 引擎本身不新增用户可见文案（站点列表、错误态、设置项复用现有字符串）。若后续加"JS 源诊断"入口，文案必须进 `app/src/main/res/values*/strings_mobile.xml` 三语（`MobileStringsParityTest` 校验 key 集合一致）。
- **原生库**：见 §0.3（文件名、四 ABI 尺寸、**未 16KB 对齐**）。`keepDebugSymbols += "**/*.so"`（`app/build.gradle.kts:167`）会保留符号，体积按上表计入。
- **缓存资产**：`files/tvbox/js/` 下三类文件（`*.js` 源码 / `*.bin` QuickJS 字节码 / `*.deps` 依赖清单），7 天 TTL，键 = 模块 URL 的 md5。drpy 运行时约 708KB，多源共享同一 runtime URL 时只存一份。

---

## 4. 数据模型与依赖差异

### 4.1 数据模型

| 项 | VOD | Mobile 现状 | 迁移动作 |
|---|---|---|---|
| 站点模型 | `SiteConfig(key,name,type,api,ext,header,jar,playerType,playUrl,timeout,…)` | **字段一致**（已移植） | 无 |
| JS 判定 | `apiIsJs` / `isJsSite` | `TvBoxConfig.kt:106-111`，`isJsSite = apiIsJs \|\| (type==3 && api.contains("drpy", true))` | 无 |
| `ext` 语义 | jar = init 的 JSON；JS = 规则脚本 URL | 同 | 文档写清"同一字段两种语义，由 `isJsSite` 分流"，**不要**给 `ext` 加类型 |
| 播放结果 | `PlayResult(parse, playUrl, url, header, flag, urls, subt, danmaku)` | `header` 是 `JsonObject?` | JS 侧只填 `parse/url/flag`，无差异 |
| 持久化 | JS 无 DB；`local` 落 SharedPreferences | 无 JS 表；`SecureKv.PREFS_DRPY` 已存在，键形如 `"$tag.$key"` | **不新增表、不新增 DataStore、不动 Core schema** |
| 配置持久化 | Room `ConfigEntity` + 文件缓存 | 索引 JSON + `files/tvbox/configs/{id}.json` + 独立 DataStore | 无（JS 站点随配置原样序列化；`TvboxConfigCacheCodec` 已覆盖 `ext/header/timeout`） |

**结论：零迁移脚本、零 schema 变更、零旧数据兼容负担。** 代价是 JS 的 `local` 数据不进备份 —— 这一点在本仓是**强制**的，不是可选：`allowBackup=false`（`AndroidManifest.xml:52`）+ `backup_rules.xml` / `data_extraction_rules.xml` **对 cloud-backup 与 device-transfer 全域排除**（详见 §5.2）。

### 4.2 依赖差异

| 依赖 | VOD | Mobile 现状 | 动作 |
|---|---|---|---|
| `wang.harlon.quickjs:wrapper-android` / `wrapper-java` | `2.1.0` / `2.1.0` | 无 | **新增**；版本取决于 §0.3 的选项决策（缓存只有 2.1.0） |
| `org.jsoup:jsoup` | `1.18.1` | 无 | **新增**（缓存已有 1.13.1 / 1.14.1 / 1.18.1，可离线） |
| `gson` | `2.11.0` | `2.11.0`（`libs.versions.toml:37`） | 复用（`JsRuntime` 的 args/result JSON 编解码） |
| `kotlinx-serialization-json` | `1.7.3` | `1.9.0`（`:30`，**runtime only，`[plugins]` 无 serialization 插件**） | 复用；用 `parseToJsonElement`/`jsonObject` 手写解析，**别加 `@Serializable`** |
| `kotlinx-coroutines` | `1.9.0` | `1.11.0`（`:27`） | 复用 |
| `nanohttpd` | 有（`JsProxyServer` 自写，未实际使用） | 无 | **不加** |
| `timber` | 有 | `TbLog` 等价门面 | `Timber.tag(x)`→`TbLog.tag(x)`；`d/w/i/e` 逐一对齐签名（含 throwable-first 重载） |
| `kotlin.io.encoding.Base64` | `@OptIn(ExperimentalEncodingApi::class)` | Kotlin 2.4.20 可用；本仓另有 `engine/Base64Compat.kt` | 用标准库版本，保持 VOD 原样 |
| AGP / Kotlin | **8.13.1 / 2.1.0**（v1 写"9.x / 2.x"不准） | AGP 9.4.0 / Kotlin 2.4.20 | 本仓更高，无冲突；但 VOD 的写法要按新 Kotlin 校一遍 |
| compileSdk / minSdk / targetSdk | — | **37 / 26 / 36**（`app/build.gradle.kts:20-22,35,36`） | targetSdk 36 ⇒ §0.3 的 16KB 约束成立 |

---

## 5. 权限与安全

> 本章为 v2 新增。drpy 脚本是**任意第三方代码**，安全边界必须在方案阶段定清，不能留到实施时即兴决定。

### 5.1 信任边界

```
不可信：站点配置 JSON、drpy runtime 脚本、规则脚本、脚本运行时产生的一切字符串
   │  在 QuickJS 沙箱内执行（无 JVM 反射、无文件 API、无 Android API）
   ▼
唯一通道：JsBridge 暴露的宿主函数
   ├─ req/fetch/_http  → NetApi.request      → 公网 + 回环（SSRF 面）
   ├─ local get/set    → JsLocalStore        → 明文 SharedPreferences
   ├─ pdfh/pdfa/pd     → DrpyHtmlParser      → 纯内存，无外呼
   ├─ getProxy/js2Proxy→ JsProxyBridge       → 生成回环 URL（带 token）
   └─ console          → TbLog               → 日志（泄露面）
   ▼
可信：宿主 Kotlin 代码、回环 HTTP 服务、Core 数据库
```

**原则**：bridge 是白名单，**不新增任何"给 JS 直接读文件/起 Intent/拿 Context"的出口**。VOD 的 `JsRuntime` 对 `inflate/deflate/gzip/ungzip/encrypt/decrypt/JSEncrypt/NodeRSA/JSON5/pako` 用的是 `EMPTY_MODULE_CODE` 空实现桩（不是真能力），移植时**保持空桩**，不要"顺手补全"成宿主加密/压缩出口。

### 5.2 权限点、角色与数据范围

**本次不新增任何 Android 权限。** 现有相关声明：`INTERNET`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`（`:34`）、`CAMERA`（`:47`）、`ACCESS_COARSE_LOCATION`（`:41`）、`REQUEST_INSTALL_PACKAGES`（`:19`）；**无存储权限**（`AndroidManifest.xml:10` 明确"用 SAF 代替"），`usesCleartextTraffic=true`（`:61`）且**无 `networkSecurityConfig`**。

| 角色 | 能做什么 | 不能做什么 | 数据范围 |
|---|---|---|---|
| 普通用户 | 导入配置、选源、播放、切配置 | — | 自己的配置与 `drpy_local` |
| drpy 脚本（不可信） | 经 bridge 发 http(s) 请求、读写**本源**的 `local`、生成回环代理 URL | 读任意文件、访问其它源的 `local`、直连内网/回环、拿 Context/权限 | 仅 `siteKey` 命名空间内的 KV |
| 同设备其它 App | 可连 `127.0.0.1:9978`（回环对本机所有进程开放） | **无 token 不得取 JS 代理内容**（403） | 无 |
| 宿主 | 全部 | 不得把 token/带鉴权 URL 写进日志或上报 | — |

数据落点与出境：

| 数据 | 位置 | 加密 | 备份/迁移 |
|---|---|---|---|
| 脚本源码 / 字节码 / deps | `files/tvbox/js/`（新增 `FileUtil.jsDir()`） | 否 | **排除**（`backup_rules.xml` / `data_extraction_rules.xml` 全域排除） |
| drpy `local`（可能含 cookie/token/播放进度） | SharedPreferences `drpy_local`，键 `"$tag.$key"`，tag 含 `siteKey` | **否（明文）** —— `SecureKv.kt:8-11` 自述这是"有记录的简化" | **排除**，不上云、不随设备迁移 |
| 站点配置 | `files/tvbox/configs/{id}.json` | 否 | 排除 |
| Core 目录/播放记录 | Core Room | 沿用现状 | **本次零改动** |

**本地脚本读取的权限口径**：走 `AddressFetcher` → `LocalSourceResolver.read(...)` → SAF 目录树授权（`TvboxPrefs.localTreeUri`）。`LocalSource.hasAllFilesAccess()`（`:376-380`）与 `allFilesAccessIntent()`（`:390-394`）**存在但零调用方**；本次**不新增"所有文件访问"引导**，无授权时按 `Outcome.Err(needsPermission=true)` 透出到导入警告，让用户去已有的 SAF 授权入口。

> 明文 KV 是**已知残留风险**，不在本次范围内整改（改加密会牵动既有 `SecureKv` 全部使用方）。方案里必须写明：JS 的 `local` 可能存站点 cookie，**明文、不进备份、按源隔离**；若未来要加密，另开需求。

### 5.3 内部 API 鉴权（回环代理）

- **token 语义：会话级、可复用、进程内有效**。`TokenFactory`（VOD `JsProxyRegistry.kt:159-165`）一起移植；`JsProxyBridge` 启动时生成一次，`baseUrl()`/`js2Proxy()` 拼出的每个 URL 都带它。
  **绝不是一次性**：HLS 一个播放会话会发几十上百个分片请求，一次性 token 会让第 2 个分片起全部 403（v1 §2.5/§7 C13 写"一次性"，是**会导致播放直接不可用的错误**）。
- **校验点**：`JarProxyServer` 在 `do=js` 分支比对 `params["token"]`，不等 → **403**（与 VOD `JsProxyServer.kt:127` 一致），响应体不回显期望值。
- **作用域**：只加在 `do=js`。**jar 侧沿用现状（无 token）**，不扩大改动面；这是**已知残留**，记入 §7.3。
- **传递**：token 只出现在 URL query；`js2Proxy` 拼 URL 时对 `url`/`siteKey` 做 URL-encode，避免参数注入把 `do` 改掉。
- **不得**：token 进日志、进崩溃上报、进 `SpiderResult`/`PlayResult` 的任何用户可见字段。
- **服务边界不变**：只绑 `127.0.0.1`（`JarProxyServer.kt:69`）、只认 `/proxy`（`:123`）、只认 GET/HEAD（`:118`）、无 `do` 直接 404（`:128`）。**不加文件路由、不做任意 URL 转发。**

### 5.4 SSRF：本仓 `isPrivateHost` 不够，JS 侧必须自行加固

核实 `net/TvboxInterceptors.kt:102-108`：`localhost` → **false**（放行）、非 IP 字面量 → **false**（放行）、`isPrivateAddress`（`:118-122`）对 loopback → **false**。⇒ **它只拦"私网 IP 字面量"，不拦域名、不拦 localhost、不拦 DNS 指向内网。**

v1 §3.1 把它当成"JS 不可信代码的 SSRF 拦截"直接复用，是**高估**。JS 侧必须在 `JsBridge.req/fetch` 入口自行加一层：

1. **scheme 白名单**：只允许 `http`/`https`；`file:`/`content:`/`clan:`/`jar:`/`ftp:` 一律拒（脚本不该有本地读能力，本地脚本读取是**宿主**的事，见 §2.7）。
2. **host 黑名单**：`localhost`、`*.localhost`、`*.local`、`[::1]`、`0.0.0.0`、`169.254.169.254`（云 metadata）、以及 `127.0.0.0/8` 全段。
3. **解析后复检**：对域名解析出的每个 IP 再过 `isPrivateHost`/私网判定，防"域名指向内网"与 DNS rebinding；解析失败按拒绝处理。
4. **重定向**：限制次数，且**每一跳都重跑 1–3**（否则 302 到 `http://127.0.0.1:9978/…` 就绕过了）。
5. **拒绝时**：返回 drpy 侧可识别的空响应 + `TbLog.w`（记 host，不记完整 URL），**不抛异常打断整个 context**。

单测 `JsBridgeSsrfTest` 覆盖上述 1–4（§9.1）。

### 5.5 审计与日志

- **保留**现有审计口径：`JarProxyHub` 已记 `siteKey + do + code + 耗时`（`:135-152`），JS 分支沿用同格式，便于真机排障。
- **必须脱敏**：`JarProxyServer.kt:140` 现在打**完整 url**（`proxy 返回空：do=%s url=%s`）。`do=js` 的 URL 带 token，**该分支要改成只记 host + path，去掉 query**（或整体截断）。这是本次唯一必须动的既有日志行。
- JS 侧 `console.log` → `TbLog.d`；**实施前先核实 `TbLog` 在 release 的行为**（§0.4 第 5 项），若会落盘则需降级/关闭。
- 不新增用户可见文案（§3.3）；错误提示复用现有字符串。
- 审计不新增存储：日志只走 logcat，不写文件、不上报。

### 5.6 安全验收用例（S1–S9，全部必做）

| # | 场景 | 期望 |
|---|---|---|
| S1 | `GET /proxy?do=js&url=…`（无 token） | **403**，不回显期望 token |
| S2 | 同上但 token 错误 | **403** |
| S3 | 同一 token 连续请求 3 个分片 | **全 200/206**（证明会话级而非一次性） |
| S4 | `do=hxq`（jar，无 token） | 与改动前**行为一致**（jar 侧不加鉴权） |
| S5 | JS 内 `req("http://127.0.0.1:9978/proxy?do=js…")` | 被拒（回环黑名单），不得自代理成环 |
| S6 | JS 内 `req("http://localhost/…")` / `req("http://169.254.169.254/")` / `req("http://[::1]/")` | 全部被拒 |
| S7 | JS 内 `req("file:///data/data/…")` / `req("content://…")` | 全部被拒（scheme 白名单） |
| S8 | 本地 `.js` 无 SAF 授权 | `Err(needsPermission=true)` 透出到导入警告；**不崩溃、不静默成功** |
| S9 | 抓 logcat 与播放结果 | **不出现 token、不出现带鉴权的完整 URL** |

---

## 6. 分阶段实施步骤

> 每阶段结束都必须"能编译 + 能跑现有测试"，不允许长期半接线状态。阶段一~三是纯增量（不注册引擎），阶段四起才改变运行时行为。

### 阶段零：备份与基线（0.3h）

```bash
# 0.1 Mobile 打备份标签（推 fork 属远端写操作，须用户确认后再执行）
cd D:/AndroidStudioProjects/git/Qwn/OwnTV_Mobile
git status --short                      # 必须为空
git tag -a pre-tvbox-js-20261112 -m "before TVbox JS engine port" mobile-mini
git bundle create ../backup-tvbox-js-20261112.bundle mobile-mini
# git push fork pre-tvbox-js-20261112   ← 需用户确认

# 0.2 Core 侧只核对，不改
git -C ../OwnTV_Core status --short
git -C ../OwnTV_Core rev-parse --short HEAD     # 期望 c6f90356，工作区干净

# 0.3 基线三连（记录耗时与结果，作为回归对照）
./gradlew :app:testStandardDebugUnitTest
./gradlew :app:assembleStandardDebug :app:assembleX86_64Debug
python tools/i18n/check_hardcoded_strings.py verify
```

产出：`backup-tvbox-js-20261112.bundle`（仓库外）、基线测试/构建结果记录。

> **门禁注意**：`VerifyI18nLiterals`（`app/build.gradle.kts:253-280`，挂在 `preBuild` `:302`）在**找不到 python 时只 warn 并盖章 "skipped" 后 return**（`:256-264`）。所以"编译过了"**不等于** i18n 过了。基线阶段必须确认 python 可用（`python --version`），并单独跑 `verify`（首次可用 `verify --bootstrap`，`:269`）。

### 阶段一：依赖与骨架（0.5h + **版本决策**）

1. **先定 QuickJS 版本（§0.3）**：查缓存/离线可得性 → 若需下载 3.2.3，**先向用户确认**（联网 + 依赖升级属需确认操作）→ 拿到后核对 API 差异与 `.so` 的 `p_align`。无论选哪个版本，**C 兜底（native 探测 + 降级）都要实现**。
2. `gradle/libs.versions.toml`：加 `quickjs`、`jsoup = "1.18.1"` 与 `quickjs-android`/`quickjs-java`/`jsoup` 三条 library。
3. `app/build.gradle.kts:356-357` 后加三条 `implementation`。
4. `util/FileUtil.kt` 加 `jsDir(context)`。
5. 建包 `tvbox/engine/js/`，先只放 `JsUrl.kt`（无依赖，可独立编译）。

验证：`./gradlew :app:assembleX86_64Debug`；`unzip -l` 检查 APK 内 `.so` 只保留 flavor 对应 ABI；**再跑一次 `p_align` 检查**（§0.3 的方法），确认交付的 `.so` 是 16KB 对齐或已明确记录为已知限制。

### 阶段二：纯逻辑件 + 单测（0.5d）

按依赖从底到顶，**每个文件先能过编译，再补单测**：

1. `JsUrl.kt` → `JsUrlTest`（协议相对 `//`、根相对 `/`、`**` 占位符不被 percent-encode、带空格降级）。
2. `JsProxyCodec.kt` → `JsProxyCodecTest`（经典数组 5 元组 + cat 对象 + base64Flag）。
3. `JsModuleCache.kt` → `JsModuleCacheTest`（TTL、字节码依赖源码新鲜度、`invalidate`）。
4. `DrpyHtmlParser.kt` → `DrpyHtmlParserTest`（`:eq(n)` 结果集下标 vs jsoup 兄弟下标；`h2--span` 排除；`Text/Html/OuterHtml/attr`）。
5. `JsLocalStore.kt`：**本仓无 Robolectric**，SharedPreferences 在 JVM 单测不可用（`returnDefaultValues=true`，`app/build.gradle.kts:121`，只会返回默认值）⇒ **不写 JVM 单测**，放到模拟器阶段验（§9.2）。

验证：`./gradlew :app:testStandardDebugUnitTest` 全绿（现有测试不受影响）。

### 阶段三：运行时与引擎（1–1.5d）

1. `JsBridge.kt`：`req/fetch/_http` → `NetApi.request` **+ §5.4 的 SSRF 加固层**；`local` → `JsLocalStore`；`setTimeout/setInterval` 排队到调用间隙；`pdfh/pdfa/pd` → `DrpyHtmlParser`；`joinUrl` → `JsUrl`；`getProxy/js2Proxy` → `JsProxyBridge`（阶段四前先给占位实现，避免编译环）。
2. `JsRuntime.kt`：单线程 executor + `withTimeoutOrNull` 判 `dead` + 模块加载器 + 字节码缓存 + `__vodInvoke` 桥；`EMPTY_MODULE_CODE` 空桩**保持空**（§5.1）。
3. `JsSpider.kt`：`downloadScripts`（runtime ∥ rule ∥ 已知 deps）、`resolveRuleUrl`、五接口、`directStreamGuard`、`proxyLocal`。
   - **改造点 1**：脚本下载按 §2.7 分流（http → `NetApi.getText` 带 header；本地 → `AddressFetcher.fetch` + 显式解码）。
   - **改造点 2**：相对 `api`/`ext` 用 `LocalSourceResolver.resolveAgainst(..., activeSourceUrl())` 兜底。
4. `JsEngine.kt`：LRU(4) + `isInitialized` 覆写 + `prefetchScripts` + **native 可用性探测（§0.3 C）**。
5. `TvboxModule.kt` 先**只注册 bean，不加入 engines 列表**，保证行为零变化。

验证：编译 + 单测 + `dependencyInsight` 确认 `wrapper-android` 进 `standardDebugRuntimeClasspath`。

### 阶段四：代理链路（0.5–1d）

1. 移植 `JsProxyRegistry` → `JsProxyBridge`（改名以免与"服务"混淆），保留 `rememberAlias` 兜底；**token 会话级**（§5.3）。
2. `JarProxyHub`：加 `jsDispatcher`，`dispatch()` **开头**判 `do=js` 短路（在 siteKey 解析之前，§2.5 第 1 条）；jar 分支与反射兜底**一行不改**。
3. `JarProxyServer`：ByteArray + String 分支、`parseRange` + 206 + `Content-Range`、`do=js` token 校验（403）、`:140` 日志脱敏。
4. `JsSpider.playerContent`：`proxy://` → `JsProxyBridge.baseUrl()`；`JsSpider.init` 成功后 `JarProxyHub.ensureStarted()`。
5. **不得调用 `JarProxyHub.stop()` / `JarProxyServer.stop()`**（§2.5 第 8 条）。

验证：`JarProxyRangeTest`（JVM 可测 `parseRange` 与字节切片）+ S1–S4（§5.6）+ 真机 m3u8 拖动 + **jar 侧三条回归**。

### 阶段五：接线与预热（0.5d）

1. `TvboxModule`：engines 加 `JsEngine`；新增 `JsWarmUp(jsEngine, CoroutineScope(SupervisorJob()+Dispatchers.IO))`；`SourceRepository(..., jsWarmUp = get())`；**`:147` 的 `TvboxRuntime` 装配改 9 个 `get()`**。
2. `TvboxRuntime`：构造 +2 参；`onSwitchHook` 加 `jsEngine.invalidateAll()`（否则旧配置的规则脚本还在 context 里）；挂 `config.onJsWarmUp`；注入 `config.isPlayable`。
3. `TvboxConfigRepository`：+`onJsWarmUp`；`applyConfig` 按"存在 `isJsSite` 站点"独立触发（**不能挂在 `config.spider` 判断里**）；+`activeSourceUrl()`。
4. `SourceRepository.aggregateSearch`：warming 分支补 `jsWarmUp?.warmSoon(it)`，并修正 `:380-382` 那条与实现不符的注释。
5. `JsWarmUp.warmAll`：优先 `prefs.homeSiteKey`，其余按**站点原顺序**（`SourceHealth` **没有 rank API**，VOD 的 `rank` 参数在本仓传空/恒等序）；并发 `Semaphore(2)`、源间隔 100ms、失败静默、不占 `globalSemaphore`（`SourceRepository.kt:55`，`Semaphore(4)`）。

验证：导入含 JS 源的配置 → 日志出现"JS 预热启动：N 个源" → 首页/搜索能出 JS 源内容。

### 阶段六：兼容性与安全收口（0.5–1d）

逐条对照 §8（C1–C19）与 §5.6（S1–S9）处理：单文件 JS、模块模式、`csp_Drpy` 变体、`type` 写错的 `.js`、本地配置、GBK 站点、`js:` 内联二级、SSRF 加固、日志脱敏。

### 阶段七：验收与收尾（1d）

跑 §9 全矩阵；`classify-safe` 归位；更新两份 docs；清理构建产物（`app/build`、`build`、`.gradle`、`.kotlin`，**脚本保留**）；按 §11.3 拆 commit 提交（**提交前须用户确认**）。

**总工作量重估：4.5–6 人天（开发 + 单测）+ 1.5–2 人天（真机/模拟器回归）。**
> v1 估 3–4 + 1–1.5 人天，未计入：16KB 版本决策与 API 核对、SSRF 加固层、可见性三处收口、ByteArray/String 双分支、token 与日志脱敏、9 条安全用例。

---

## 7. 备份与回滚

### 7.1 备份

| 对象 | 方式 | 位置 |
|---|---|---|
| Mobile `mobile-mini` | 标签 `pre-tvbox-js-20261112`（+ 可选 push `fork`，需确认） | 本地 + GitHub fork |
| Mobile 全仓快照 | `git bundle create backup-tvbox-js-20261112.bundle mobile-mini` | `Qwn/backup-tvbox-js-20261112.bundle`（仓库外） |
| Core `mobile` | 只读核对 `c6f90356`，不改 | — |
| `vendor/OwnTV_Core` | 不动（subtree 快照） | — |
| 签名材料 | 不动 `keyStroe/` 与仓库外 properties | — |
| 用户侧配置 | `files/tvbox/configs/*.json`（+ 可选 `spiders/`） | 真机 `adb pull` 留档 |

### 7.2 回滚（**revert 优先，非破坏式**）

本次全部改动落 `mobile-mini`，且按 §11.3 拆成独立 commit，**首选逐条 revert**：

```bash
cd D:/AndroidStudioProjects/git/Qwn/OwnTV_Mobile
git status --short                       # 先确认没有未提交的在制品
git log --oneline pre-tvbox-js-20261112..HEAD   # 列出本次 commit
git revert --no-edit <本次 commit 由新到旧>      # 保留历史，可再次前滚
./gradlew :app:assembleStandardDebug
```

只有在"工作区已乱、且确认无任何未提交成果"时，才考虑破坏式回退；**执行前必须 `git status` 并把在制品 stash（带唯一标记）或提交，且须用户明确确认**：

```bash
# 破坏式，需用户确认；会丢弃未提交改动
git reset --hard pre-tvbox-js-20261112
```

> 不新建分支、不 `git clean -fd` 扫目录（v1 的 `git clean -fd app/.../engine/js` 会连带删掉未跟踪的在制品）。新增的 `engine/js/` 文件在 revert 后自然消失；若残留未跟踪文件，**逐个确认后手动删**。

App 侧残留：`files/tvbox/js/`（脚本缓存）与 SharedPreferences `drpy_local` 可整目录删除，不影响 CMS/jar 源与 Core 数据。**不需要数据库降级**（本方案不碰 schema）。

### 7.3 已知残留（本次**不修**，明文记录以免下次误判）

| 残留 | 位置 | 影响 | 为何不修 |
|---|---|---|---|
| `pool` 一次性，`stop()` 后无法重启 | `JarProxyServer.kt:56,89,103` | 目前 `stop()` 零调用方，不触发 | 修它要改服务生命周期，超出本次范围；JS 侧禁止调 `stop()` |
| jar 侧代理无 token | `JarProxyHub.dispatch` jar 分支 | 同设备其它 App 可拉 jar 代理内容 | 不扩大改动面；只给 `do=js` 加鉴权 |
| `drpy_local` 明文 | `SecureKv.kt:8-11` | 站点 cookie 明文存本机 | 改加密牵动全部既有使用方 |
| `TvboxPrefs.sourceUrlOf` 零调用方 | `data/prefs/TvboxPrefs.kt:30` | 死代码 | `activeSourceUrl()` 可基于它实现，届时自然激活 |
| `LocalSource.allFilesAccessIntent()` 零调用方 | `util/LocalSource.kt:390-394` | 死代码 | 本次不新增"所有文件访问"引导 |
| QuickJS `.so` 未 16KB 对齐（若选 §0.3 C） | AAR 内 | 16KB 页设备 JS 源不可用（已降级，不崩） | 取决于版本决策 |

---

## 8. 兼容性处理（C1–C19）

| # | 风险点 | 处理 |
|---|---|---|
| C1 | QuickJS **没有 WHATWG `URL` 类**，`new URL(path, base)` 静默返回裸 path，drpy2 `init` 的 `urljoin` 归一化全失效（首页/推荐双空） | 必须移植 `JsUrl`，`JsBridge.joinUrl` 指向宿主；JS 侧 fallback 保留简化拼接 |
| C2 | QuickJS 的 `-`/`--`/`:eq` 选择器方言与 jsoup 语义不同 | `DrpyHtmlParser` 按 VOD 版本移植（`:eq` 用结果集下标、`--` 是排除、`:gt/:lt` 透传），**不能**直接把规则丢给 jsoup |
| C3 | 单文件 JS（无 drpy runtime，脚本自带 `home/category/…`） | `JsRuntime` 的"模块模式判定 + `__drpyModule` 回退 `globalThis[name]`"已覆盖；`ext` 为空时跳过 `init(ext)` |
| C4 | `type` 写错（`type=0/1` 但 `api` 是 `.js`） | `CmsEngine.supports` 已排除 `apiIsJs`；`JsEngine.supportsSite` 以 `apiIsJs` 优先，不看 `type` |
| C5 | 相对 `./js/x.js` | 导入期 `fixContentPath` 已改写；旧配置/裸相对用 `resolveAgainst` + `activeSourceUrl()` 兜底 |
| C6 | **本地配置（file://、SAF、`clan://`）引用的本地 `.js`** | 按 §2.7 分流：本地走 `AddressFetcher.fetch` + **调用方显式解码**；`fetchText` 不解码，不能直接用 |
| C7 | GBK 站点 | `NetApi.request` 已按响应 charset 解码并回退 UTF-8；本地脚本按 BOM/`rule.encoding`/UTF-8 顺序解码 |
| C8 | 死循环 / 超长脚本 | `setMemoryLimit(64MB)` + `setMaxStackSize(512KB)` + `withTimeoutOrNull` 判 `dead` + 重建 context。**更正**：QuickJS 原生有 `JS_SetInterruptHandler`，只是 **wrapper 2.1.0 未暴露**（若升级到 3.x 需重新核对是否可用）；`dead` 线程不再接新任务，不扩散到其它源 |
| C9 | 4 个 context 上限，超出 LRU 淘汰 | `MAX_LIVE_CONTEXTS=4` 保留；预热对名额外的源只下脚本不建 context |
| C10 | `local` 跨源串号 | 键含 `siteKey`，不退回无源隔离写法 |
| C11 | JS 的 `proxy()` 不实现 Range | `JarProxyServer` 字节分支按 `range` 切片返 206；返回整段 `byte[]` 时切片是纯内存操作 |
| C12 | 与 jar 共用端口的 siteKey 冲突 | `do=js` **前置**短路到 JS 注册表；JS 侧 `onPlay` 记最近播放源作缺省兜底；jar 分支保持"先查实例表" |
| C13 | 回环端口被同设备其它 App 访问 | `do=js` 加**会话级可复用** token（§5.3）；jar 侧沿用现状（§7.3） |
| C14 | R8 混淆破坏反射/原生绑定 | `proguard-rules.pro:35` 的 `-keep class tv.own.owntv.mobile.tvbox.engine.** { *; }` 已覆盖新包；QuickJS AAR 自带 consumer keep（**未逐条核对**）；**release 必须实机验一次** |
| C15 | i18n 硬门禁：新增中文字面量直接编译红 | 阶段七集中 `classify-safe`（`log`/`protocol`/`url`/`mime`/`technical`）；日志不翻译成资源。**注意 python 缺失时门禁静默跳过**（§6 阶段零） |
| C16 | 新 `.so` 触发 `keepDebugSymbols` 与体积增长 | 按 §0.3 实测尺寸计入（arm64 ≈1.23MiB）；不改 `packaging`，在版本说明里注明 |
| **C17** | **`.so` 未 16KB 对齐 + targetSdk 36** | §0.3：优先升级版本；**无论选哪个都要有 `UnsatisfiedLinkError` 探测 + `JsEngine.supports=false` 降级**，保证不崩进程、可见性口径一致 |
| **C18** | **SSRF：`isPrivateHost` 只拦 IP 字面量** | §5.4：JS 侧自建 scheme 白名单 + host 黑名单 + 解析后复检 + 每跳重定向复检 |
| **C19** | **`JarProxyServer` 只认 `InputStream`** | §2.5 第 3 条：ByteArray **与 String** 两个分支都要加，否则静默 `Content-Length: 0` |

---

## 9. 测试与验收

### 9.1 单元测试（JVM）——**先明确边界**

**JVM 单测测不了什么（已核实）：**

- **不能创建 `QuickJSContext`**：`wrapper-java` 的 jar 只有 25 个 class、**不含 native**；本仓**无 Robolectric**，`returnDefaultValues=true`（`app/build.gradle.kts:121`）只会让 Android API 返回默认值。⇒ 凡是触碰 context / SharedPreferences / `Uri` / `Context` 的路径，**一律放模拟器阶段**。
- VOD 的 `JsRuntimeTest` 之所以能在 JVM 跑，是因为它**只测纯字符串处理**（模块判定/参数编码），不建 context。移植时保持这个边界。
- VOD 共 7 个 JVM 测试文件（合计 477 行），可移植 6 个；`JsProxyServerRangeTest` 因服务不移植，**改造成测 `JarProxyServer.parseRange` 与字节切片**。

| # | 用例 | 来源 | 断言要点 |
|---|---|---|---|
| 1 | `JsUrlTest` | 移植 | 协议相对/根相对/相对/`**` 占位符/非法字符降级 |
| 2 | `JsProxyCodecTest` | 移植 | 数组 5 元组、cat 对象、`buffer` + base64 |
| 3 | `JsModuleCacheTest` | 移植 | 7 天 TTL、源码过期连带字节码作废、deps 空不落盘（用临时目录，不碰 Android API） |
| 4 | `DrpyHtmlParserTest` | 移植 | `:eq(0)` 是结果集第一个（非兄弟）、`h2--span&&Text`、`--` 只剔后代 |
| 5 | `JsEngineRoutingTest` | 移植 | `apiIsJs` 覆盖 type 0/1/3/4；`csp_X`、`.py`、纯 jar 类名均不被 `supportsSite` 命中；**native 不可用时 supports=false** |
| 6 | `JsRuntimeTest` | 移植（纯字符串） | 模块模式判定、`__vodInvoke` 参数/结果 JSON 编解码 |
| 7 | `JarProxyRangeTest` | **改造** | `parseRange` 三形态（`a-b`/`a-`/`-b`）、越界钳制、206 头与 `Content-Range` 计算、ByteArray/String 两分支长度正确 |
| 8 | `JsSpiderRelativeTest` | **新增** | `resolveRuleUrl` 相对 `./` 解析、`extractStaticImports` 两种 import 形态、http/本地分流选择正确 |
| 9 | `JsBridgeSsrfTest` | **新增** | §5.4 的 1–4：scheme 白名单、localhost/`[::1]`/metadata 拒绝、域名解析到私网拒绝、重定向每跳复检 |
| 10 | `SiteVisibilityTest` | **新增** | `canServe` / `isPlayable` / ViewModel 三处口径一致；JS 源在 native 可用时可见、不可用时三处同时剔除 |

### 9.2 运行时验证（模拟器 x86_64）

`D:/Android/sdk/emulator/emulator.exe`（后台启动，见 `D:\AndroidStudioProjects\安卓模拟器使用流程.md`）；`adb shell uiautomator dump` 做 UI 断言（`input text` 不支持中文，检索走分类路径）。

> **x86_64 模拟器的两个已知限制**：① 加固壳 jar 源的 `.so` 只有 ARM，**模拟器上加载不了**，jar 代理回归必须上真机；② 常规镜像是 4KB 页，**验不出 16KB 问题**（§0.3）。

1. 导入含 JS 源的配置（URL + 本地文件各一次）→ 站点数、警告数正确；本地无授权时出权限提示（S8）。
2. JS 源首页：分类 + 推荐（`home` + `homeVod` 兜底）出内容。
3. 分类翻页 + 筛选（`extend` JSON 传入 JS）。
4. 详情 → 线路/选集解析（`$$$`/`#`）。
5. 播放：直链、`parse=1` → 命名解析器、`proxy://` → 本机代理，三道分支各一条。
6. 搜索：单源 + 聚合（冷源"预热中"后**第二轮能搜到** —— 这是 warming 分支修复的直接验收点）。
7. 切换配置 → JS context 全清、旧站不串内容。
8. 杀进程冷启动 → 字节码缓存命中，首屏不二次下载。
9. `local` 读写：同一源跨会话保留、不同源互不可见（JVM 测不了，必须在这里验）。
10. S1–S9 安全用例（§5.6）。

### 9.3 真机（arm64，必做）

- ≥4 个 JS 源触发 LRU 淘汰：第 5 个源可用，第 1 个源重建后可用。
- 死循环源（本地构造 `while(true){}`）：超时 → 标 dead → 下次调用自动重建，其它源不受影响。
- `do=js` 代理播放 m3u8：拖动进度条（Range 206）、暂停/恢复、连续分片全通（S3）。**不验"60s 端口回收"**（Mobile 无此机制）。
- **加固壳 jar 源回归**（代理服务被改过）：`do=hxq` / `do=ck` / `do=m3u8` 三条不退化。
- release（`isMinifyEnabled=true`）安装 + JS 源播放通（C14）。
- `adb shell getconf PAGE_SIZE`：若为 16384，验证 §0.3 的降级路径（JS 源不可用但不崩、CMS/jar 正常）。

### 9.4 门禁命令（**已修正**）

```bash
./gradlew :app:testStandardDebugUnitTest
./gradlew :app:lintStandardDebug
./gradlew :app:assembleStandardDebug :app:assembleX86_64Debug :app:assembleStandardRelease
python --version                                                   # 必须先确认，否则门禁静默跳过
python tools/i18n/check_hardcoded_strings.py verify
python tools/i18n/check_text_overflow.py                           # 独立脚本，不是子命令
python tools/i18n/check_number_locale.py                           # CI i18n.yml 同款
```

> v1 的 `check_hardcoded_strings.py check_text_overflow … || true` 有两处错：该脚本的子命令只有 `generate/verify/prune-safe/verify-ci/classify-safe`（`:648-660`），**没有 `check_text_overflow`**；`|| true` 会把真失败吞掉。溢出与数字本地化是**另外两个脚本**（CI `.github/workflows/i18n.yml:96-98`）。

**验收口径**：JS 源"导入 → 首页 → 分类 → 详情 → 播放"闭环可用；CMS/jar 两条既有链路行为**零变化**（py 本就不支持）；S1–S9 全通；**Core 仓库与 `vendor/OwnTV_Core` diff 为空**。

---

## 10. 主要风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| **`.so` 未 16KB 对齐 + targetSdk 36** | 16KB 页设备上 JS 源不可用；若未做降级则**崩进程** | §0.3：优先升级版本；**强制实现 native 探测 + `supports=false` 降级**；真机 `getconf PAGE_SIZE` 验一次 |
| **改 `JarProxyHub`/`JarProxyServer`** | jar 代理是已上线能力，改坏 = 加固壳源全灭 | 阶段四单独 commit；jar 分支只加前置判断、不改原逻辑；真机回归 `do=hxq`/`do=ck`/`do=m3u8` |
| **token 做成一次性** | HLS 第 2 个分片起全 403，播放直接不可用 | §5.3 明写会话级；S3 用例专门验连续分片 |
| **SSRF 防护不足** | 不可信脚本可探内网/回环/云 metadata | §5.4 自建白名单 + 黑名单 + 解析后复检 + 每跳重定向复检；`JsBridgeSsrfTest` |
| wrapper 未暴露 interrupt handler，死循环只能靠内存/栈上限 + 判死重建 | 极端源占住一条 JS 线程直至进程结束 | 每源独立线程 + `dead` 拒新任务 + 上限 4 context；接受"线程泄漏但不扩散" |
| 引入 native 运行时 | 每 ABI +0.9~1.4MB；armeabi-v7a 性能明显更差 | 只对 `standard`/`x86_64` 交付；低端机靠 LRU(4) 与"只下脚本不建 context"控内存 |
| **可见性三处口径不一致** | "能选但必失败"从引擎挪到 UI，等于没修 | §2.6 统一谓词 + `SiteVisibilityTest`；native 不可用时三处同时剔除 |
| i18n 门禁 | 200+ 中文字面量让 `preBuild` 失败；**python 缺失时又静默跳过** | 阶段七集中 `classify-safe`；基线先确认 python；日志不翻译 |
| 生态碎片（单文件 JS、cat 形态、`js:` 内联二级） | 部分源"显示可用但解析失败" | 归一化统一走 `JarResultParser`；详情放宽 `requireName=false`；直链反向卫（`parse=0` 不是流 → 转嗅探） |
| 预热挤占前台 | 首页首屏变慢 | 并发 2 + 100ms 间隔 + 不占 `globalSemaphore`；失败静默 |
| 本地脚本读取依赖 SAF 授权 | 本地配置的 JS 源在无授权时全失败 | `needsPermission` 透出到导入警告；引导到**已有** SAF 入口，不新增"所有文件访问" |
| 跨大版本升级 QuickJS（若选 §0.3 A） | API 不兼容导致移植件返工 | 阶段一先做 API 差异核对再动代码；保留 2.1.0 分支写法作对照 |
| 未来吸收 VOD 上游改动 | JS 引擎二次分叉 | 保留 VOD 原始注释与出处；`docs/core-changes.md` 记"来源 4c8d289"，后续按文件 diff 而非重写 |
| 误改 Core / vendor | subtree 冲突与 i18n baseline 重建 | 明文规定 Core 零改动；提交前 `git -C ../OwnTV_Core status --short` 必须为空 |

---

## 11. 预计新增 / 修改文件清单

### 11.1 新增（22 个 = 引擎 11 + 测试 10 + 文档 1）

**引擎（11）**

```
app/src/main/java/tv/own/owntv/mobile/tvbox/engine/js/JsEngine.kt          （≈110 行，移植+native 探测）
app/src/main/java/tv/own/owntv/mobile/tvbox/engine/js/JsSpider.kt          （≈380 行，移植+改造）
app/src/main/java/tv/own/owntv/mobile/tvbox/engine/js/JsRuntime.kt         （≈310 行，移植）
app/src/main/java/tv/own/owntv/mobile/tvbox/engine/js/JsBridge.kt          （≈430 行，移植+SSRF 加固）
app/src/main/java/tv/own/owntv/mobile/tvbox/engine/js/JsProxyBridge.kt     （≈200 行，重写：注册表+URL+token+分发）
app/src/main/java/tv/own/owntv/mobile/tvbox/engine/js/JsProxyCodec.kt      （≈55 行，移植）
app/src/main/java/tv/own/owntv/mobile/tvbox/engine/js/JsModuleCache.kt     （≈90 行，移植）
app/src/main/java/tv/own/owntv/mobile/tvbox/engine/js/JsLocalStore.kt      （≈45 行，移植）
app/src/main/java/tv/own/owntv/mobile/tvbox/engine/js/JsUrl.kt             （≈70 行，移植）
app/src/main/java/tv/own/owntv/mobile/tvbox/engine/js/DrpyHtmlParser.kt    （≈125 行，移植）
app/src/main/java/tv/own/owntv/mobile/tvbox/engine/js/JsWarmUp.kt          （≈100 行，移植）
```

**测试（10 = 移植 6 + 改造 1 + 新增 3，随阶段二~六落地）**

```
app/src/test/java/tv/own/owntv/mobile/tvbox/engine/js/JsUrlTest.kt              （移植）
app/src/test/java/tv/own/owntv/mobile/tvbox/engine/js/JsProxyCodecTest.kt       （移植）
app/src/test/java/tv/own/owntv/mobile/tvbox/engine/js/JsModuleCacheTest.kt      （移植）
app/src/test/java/tv/own/owntv/mobile/tvbox/engine/js/DrpyHtmlParserTest.kt     （移植）
app/src/test/java/tv/own/owntv/mobile/tvbox/engine/js/JsEngineRoutingTest.kt    （移植+native 降级断言）
app/src/test/java/tv/own/owntv/mobile/tvbox/engine/js/JsRuntimeTest.kt          （移植，纯字符串）
app/src/test/java/tv/own/owntv/mobile/tvbox/engine/proxy/JarProxyRangeTest.kt   （改造自 VOD JsProxyServerRangeTest）
app/src/test/java/tv/own/owntv/mobile/tvbox/engine/js/JsSpiderRelativeTest.kt   （新增）
app/src/test/java/tv/own/owntv/mobile/tvbox/engine/js/JsBridgeSsrfTest.kt       （新增）
app/src/test/java/tv/own/owntv/mobile/tvbox/repository/SiteVisibilityTest.kt    （新增）
```

**文档（1）**：`docs/TVbox-JS源迁移方案.md`（本文）

### 11.2 修改（14 个）

| 文件 | 位置 | 改动 |
|---|---|---|
| `gradle/libs.versions.toml` | `:131` 附近 + `[libraries]` | +`quickjs`/`jsoup` 版本与三条 library |
| `app/build.gradle.kts` | `:356-357` | +3 条 `implementation` |
| `app/proguard-rules.pro` | `:34-35` 之后 | 说明注释 + 必要 `-dontwarn`（新包已被 keep 覆盖） |
| `tvbox/util/FileUtil.kt` | `:15-16` | +`jsDir(context)` |
| `tvbox/di/TvboxModule.kt` | `:105-109`、`:147` | +`JsEngine`/`JsWarmUp` single；engines 加 JS；`SourceRepository` 传 `jsWarmUp`；Runtime 装配 9 个 `get()` |
| `tvbox/di/TvboxRuntime.kt` | `:18-52` | 构造 +2 参；`onSwitchHook` 加 `invalidateAll()`；+`onJsWarmUp`；注入 `isPlayable` |
| `tvbox/repository/TvboxConfigRepository.kt` | `:141-154`、`:433-446`、`:454`、`:471` | +`onJsWarmUp` 与独立触发；+`isPlayable`；+`activeSourceUrl()` |
| `tvbox/repository/SourceRepository.kt` | `:48-52`、`:100-103`、`:380-393` | +`jsWarmUp`；+`canServe()`；warming 真正预热 + 修正注释 |
| `ui/screens/vod/TvboxViewModels.kt` | `:230` | 可见性过滤改用统一谓词 |
| `tvbox/engine/proxy/JarProxyHub.kt` | `:30-38`、`:106-153` | +`jsDispatcher`、`do=js` 前置路由 |
| `tvbox/engine/proxy/JarProxyServer.kt` | `:154-225`（尤其 `:158`）、`:140`、`:301-310` | +ByteArray/String 分支、+`parseRange`/206、+`do=js` token、日志脱敏 |
| `tools/i18n/safe_literals.txt` | 追加 | `classify-safe` 归类（约 200 条） |
| `docs/TVbox点播迁移方案.md` | `:99`、`:257`、`:478-480`、`:608` | P5 状态与范围说明 |
| `docs/core-changes.md` | 记录段 | "未改 Core；JS 引擎来源 VOD `4c8d289`" |

### 11.3 提交切分（8 个 commit，全部落 `mobile-mini`；**提交前须用户确认**）

| # | commit message | 覆盖阶段 |
|---|---|---|
| 1 | `build: add QuickJS and jsoup for the JS spider engine` | 一 |
| 2 | `feat(tvbox): port JsUrl/JsProxyCodec/JsModuleCache/DrpyHtmlParser with unit tests` | 二 |
| 3 | `feat(tvbox): port QuickJS runtime, bridge and JS spider` | 三 |
| 4 | `feat(tvbox): route JS sources to a dedicated engine (LRU 4, dead-context rebuild)` | 三 |
| 5 | `feat(tvbox): share the loopback proxy with JS sources (byte/string bodies, Range, token)` | 四 |
| 6 | `feat(tvbox): warm up JS sources after config apply and in aggregate search` | 五 |
| 7 | `fix(tvbox): unify JS-site visibility across home list, home site and engine routing` | 五/六 |
| 8 | `docs: record the JS-source port and its VOD origin` | 七 |

> 第 5 条**必须单独成 commit**：它是唯一改动已上线 jar 代理能力的提交，出问题时可单独 revert（§7.2）。

---

## 12. 一句话结论

Mobile 的 TVbox 栈已把 JS 引擎之外的所有环节（导入、路由、归一化、展示、播放、代理、索引）建好，接口/常量都按 P5 预留 —— **本次是"补一个引擎 + 接一根代理线 + 补两个钩子 + 收一套可见性口径"**，不动 Core、不动数据库、不新增表。

真正的成本与风险不在移植本身，而在四件 v1 低估的事：**① QuickJS `.so` 未 16KB 对齐而 targetSdk=36（须先定版本 + 强制降级）；② 既有代理只认 `InputStream`、按 `siteKey` 路由、token 必须会话级（照 v1 写会直接播放不可用）；③ `isPrivateHost` 只拦 IP 字面量，SSRF 得自建一层；④ 站点可见性有三套口径，不收口就等于没修。** 加上 200 条 i18n 归类与"动了就要回归 jar 源"的谨慎验证，重估 **4.5–6 人天开发 + 1.5–2 人天回归**。