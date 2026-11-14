# TVbox py 源（Python 采集源）功能复刻方案

> 复刻源：`E:/develop/androidPoject/video_on_demand` **main 分支** @ `b634904`（下称 **VOD**）
> 复刻目标：`E:/develop/ideaPoject/git/Own/OwnTV_Mobile` **mobile-mini 分支** @ `e4cecb31`（下称 **Mobile**）
> 范围：**只做 TVbox py 源**（`type == 3` 且 `api` 指向 `.py` 的站点），覆盖「py 脚本导入 → 源解析 → 内容展示 → 播放」完整链路。**不含** CMS / jar / JS 源改造、播放器替换、Core 数据层改造、直播源。
> 本文只出方案，不改代码。日期：2026-11-12
> 关联文档：`docs/TVbox点播迁移方案.md`、`docs/TVbox-JS源迁移方案.md`、`docs/点播内容接入Core方案.md`、`docs/TVbox回归测试报告-模拟器.md`、`docs/Core源码集成方案.md`

---

## 0. 基线与前置结论

### 0.1 分支基线（2026-11-12 实测）

| 仓库 | 日常改动分支 | 当前提交 | 说明 |
|---|---|---|---|
| OwnTV_Mobile | `mobile-mini`（跟踪 `origin/mobile-mini`，ahead 5） | `e4cecb31` | 本次全部改动落在这里；**不新建分支** |
| OwnTV_Core | `mobile` | `e0556f0f` | **本次不改**：本需求不需要 Core 侧任何变更 |
| `vendor/OwnTV_Core` | —（subtree 快照） | — | Core `mobile` 分支快照。**不要手工编辑**，同步流程见 `docs/Core源码集成方案.md` |
| video_on_demand | 分析对象 `main` | `b634904` | 全程只读取证，未切分支、未改动 |

**工作区现状（非本方案所为）**：Mobile 有 5 个未提交改动 —— `OwnTVMobileApp.kt`、`tvbox/di/TvboxModule.kt`、`tvbox/engine/js/JsEngine.kt`、`tvbox/engine/js/JsWarmUp.kt`、`tools/i18n/safe_literals.txt`。实施时必须**叠加**而非覆盖，尤其 `TvboxModule.kt` 正是本方案要改的装配点。

> **取证口径**：VOD 源码注释多处引用 `docs/py采集源接入方案.md`、`docs/py文件直接导入改造方案.md`、`docs/py模块打包开关方案.md`，但 `main`@`b634904` 的 `docs/` 目录**不存在**（`git ls-tree -r --name-only HEAD docs` 无输出）。本文全部结论只以源码、Gradle 配置、proguard 文件和测试代码为准，不引用上述不存在的文档正文。

### 0.2 五条已核实的现状（决定方案形态）

1. **Mobile 已移植 VOD 的绝大部分 TVbox 解析栈**（`tvbox/` 下 `config/parser`、`engine/cms`、`engine/jar`、`engine/js`、`engine/proxy`、`engine/sniff`、`index`、`play`、`repository`、`net`、`util`），**唯独缺 py 运行时**：无 Chaquopy 插件、无 `src/main/python`、无 `PyEngine`/`PySpider`/`PyHost`、无 `pyDir()`、无 `ensurePyFile()`、无 `WITH_PY` 开关。
2. **接线口已预留**：`EngineKind.SPIDER_PY`（`engine/Spider.kt`）、`Engine.supports/obtain/isCooling/invalidateAll`、`SourceRepository.engineFor() = engines.firstOrNull { it.supports(site) }`（`repository/SourceRepository.kt:98-99`）、`canServe()` / `kindOf()`（`:95-101`）、`invalidateAll()` 遍历全部 engines（`:121-125`）。**把 PyEngine 加进 `engines` 列表即可完成路由，调度核心零改动。**
3. **站点可见性已收口成单一谓词**：`TvboxRuntime.kt:48-49` 把 `config.isPlayable` 与 `ConfigParser.isPlayable` **都**指向 `sourceRepository.canServe(site)`；`ConfigParser.kt:235-238` 的导入警告也用它。因此新增 PyEngine 后，源列表 / 首页绑定 / 导入警告 / 聚合搜索的可用性会**自动一致**，无需改 UI。
4. **播放出口不是 VOD 的 PlayerManager**：Mobile 是 `TvboxPlayResolver.resolve()`（`play/TvboxPlayResolver.kt:78-132`）→ `ResolvedPlay` → `VodTuner.playTvbox()`（`ui/screens/library/VodTuner.kt:535`）→ `OwnTVPlayer`；从 Core 表进入时先走 `TvboxPlaybackRouter.prepare()`（`play/TvboxPlaybackRouter.kt:47-113`）反解 `tvbox://` 句柄。py 源只要产出同构 `PlayResult`，这条链**零改动**。
5. **归一化层签名逐字对齐**：`JarResultParser.toResult(raw, baseUrl, requireName, fallbackId)`（`engine/jar/JarResultParser.kt:63-68`）、`PlayNormalize.normalizePlay(raw, flag, id, logTag, sourceLabel)`（`engine/PlayNormalize.kt:20-37`）与 VOD 的 `PySpider` 调用完全一致，`preview()`/`describe()` 也在（`JarResultParser.kt:607/622`）。**py 结果可以零转换复用 jar 的归一化链。**

### 0.3 硬约束（来自 `项目说明.txt`）

- 本地 `OwnTV_Mobile` 只改 `mobile-mini`，本地 `OwnTV_Core` 只改 `mobile`；`vendor/OwnTV_Core` 是 Core `mobile` 的 subtree 快照，**不手工编辑**。
- 提交流程遵循 `docs/Core源码集成方案.md`；正式包 keystore 在 `E:/develop/ideaPoject/git/Own/keyStore`。
- **需求实现后清理测试过程中产生的构建文件，脚本文件除外。**
- `verifyI18nLiterals` 挂 `preBuild`（`app/build.gradle.kts:283-302`），扫描 `src/main/java/**/*.kt`；新增 Kotlin 中文字面量必须进 `res/values/strings_mobile.xml` 或登记 `tools/i18n/safe_literals.txt`。`.py` 文件不在扫描范围。

---

## 1. 两个项目相关模块与调用流程的对应关系

| # | 链路环节 | `video_on_demand`（源） | `OwnTV_Mobile`（目标） | 处置 |
|---|---|---|---|---|
| 1 | 本地文件导入入口 | `ConfigRepository.importLocalFile(uri)`（`:424-437`）→ `isPyFileName` / `looksLikePyScript` → `importPyScript` | `TvboxConfigRepository.importLocalFile(uri)`（`:398-403`）直接 `importFromText` | **加分流** |
| 2 | 脚本落盘目录 | `FileUtil.pyDir()` = `files/py`（`util/FileUtil.kt:29`） | `FileUtil` 只有 `configDir` / `spiderDir` / `jsDir`（`util/FileUtil.kt:15-25`） | **加 `pyDir()`**，落 `files/tvbox/py` |
| 3 | 合成站点 | `SiteConfig(key = py_<md5[:12]>, type = 3, api = "file://<abs>.py", searchable = 1)` → `upsertSite()` 并入激活配置（`:457-513`） | 无；`TvBoxConfig.kt:169` 注释明写「py 源已按方案排除，不参与判定」 | **新增** `importPyScript` + `upsertSite` |
| 4 | 站点判据 | `isPySite`（`type==3 && api.endsWith(".py", true)`）、`isSupported` 含 `BuildConfig.WITH_PY`、`unsupportedReason` 精简包文案 | 仅 `apiIsJs` / `isJsSite`（`TvBoxConfig.kt:105-111`）；`isSupported` type3 只认 `spiderClassName \|\| isJsSite`（`:171-176`） | **改 `TvBoxConfig.kt`** |
| 5 | 可用性谓词 | `ConfigParser.isPlayable` | `TvboxRuntime.kt:48-49` 已统一指向 `sourceRepository.canServe()` | **零改动**（只要 PyEngine 进 `engines`） |
| 6 | 脚本就绪 | `ensurePyFile(site)`（`:1340-1360`）：`file://` 直通 / `LocalSourceResolver.parse` → `RemoteClan` 下载、`LocalFile`/`Content` materialize、http `downloadPy`；`isValidPyText` 挡 HTML 落地页 | 无；有同构的 `ensureSiteJar()`（`:812-836`）、`downloadSpiderLocked()`（`:857+`）、`materializeLocalJar` | **新增一组 py 版方法**（复制 jar 版骨架） |
| 7 | 引擎装配 | `ServiceLocator.pyEngine = PyEngineHolder.create(ctx, pyFileProvider)`，加入 `engines` | `TvboxModule.kt:146-152`：`engines = listOf<Engine>(Cms, Jar, Js)` | **加 `PyEngineHolder.create(...)`** |
| 8 | 引擎门面 | `PyEngine`（`src/py/java/.../PyEngine.kt`）：LRU 12、`buildMutex`、`lastErrors`、`ensurePythonStarted`、`redirect_stdio` | 无；`EngineKind.SPIDER_PY` 已保留 | **新增** |
| 9 | 五接口桥 | `PySpider`：`callAttr` + `PY_EXECUTOR(8)` + `Floors` 分级超时 + `JarResultParser` / `PlayNormalize` | `JarSpider` / `JsSpider` 同构 | **新增**（签名已核对一致） |
| 10 | 宿主能力桥 | `PyHost.fetch` / `log` → `ServiceLocator.netClient()` + Timber + Gson | `NetApi` / `OkHttpProvider.get()`（`net/OkHttpProvider.kt`）、`SiteMark`（`net/TvboxInterceptors.kt:20-30`）、`TbLog`、`TextUtil` | **新增适配版** |
| 11 | Python assets | `app/src/main/python/base/{__init__,loader,spider}.py` | 无 | **新增 3 个** |
| 12 | 统一调度 | `SourceRepository.engineFor()` + `Semaphore(4)` + 缓存 + `SourceHealth` | 同构（`SourceRepository.kt:45-125`） | **零改动** |
| 13 | 内容展示 | `HomeViewModel` / `DetailViewModel` / 搜索直接消费 `SpiderResult` | `TvboxHome/Detail VM` → `TvboxIndexer` 写 Core 表；`TvboxOnlineSearch` 聚合搜索 | **零改动**（py 产出同构 `SpiderResult`） |
| 14 | 播放解析与起播 | `PlayViewModel.resolveAndPlay` → `playerContent` → `playerManager.play` | `TvboxPlayResolver.resolve`（playUrl 前缀 → 本机代理 → 命名解析器 → 嗅探 → 直连）→ `VodTuner.playTvbox` → `OwnTVPlayer`；Core 行走 `TvboxPlaybackRouter.prepare` | **零改动，不复制 PlayerManager / Media3 / IJK** |
| 15 | 生命周期 | `onSwitchHook` 清各引擎 | `TvboxRuntime.kt:50-57` 已调 `sourceRepository.invalidateAll()`，其内部遍历全部 engines | **零改动或仅显式补一行**；注意别重复 `destroy()` |
| 16 | 构建接线 | Chaquopy `17.1.0`、`WITH_PY`、`src/py`↔`src/nopy`、Python 3.11、pip 三件套、`proguard-chaquopy.pro`、`PY_X86_64` | 全无 | **新增整套接线** |
| 17 | 日志 / DI | Timber / `ServiceLocator` | `TbLog` / Koin `tvboxModule` | **替换** |

---

## 2. 需复用、调整与新增的类及资源

### 2.1 直接复用（零改动，已逐个核对签名）

- `engine/Spider.kt`：`Spider` / `Engine` 接口（`:11-92`）、`EngineKind.SPIDER_PY`
- `engine/SpiderResult.kt`：`SpiderResult` / `ClassInfo` / `Movie` / `FilterDef` / `FilterValue` / `PlayResult`
- `engine/jar/JarResultParser.kt`：`toResult` / `preview` / `describe`（含 `class`/`classes`、`filters`、`list`、`vod_*`、密文解码兼容）
- `engine/PlayNormalize.kt`：`internal object`（同 module 内 `PySpider` 可访问）
- `repository/SourceRepository.kt`、`play/TvboxPlayResolver.kt`、`play/TvboxPlaybackRouter.kt`、`ui/screens/library/VodTuner.kt`
- `index/**`、`search/TvboxOnlineSearch.kt`、Core 的 `ProfileDao` / `ProgressDao` / `MovieEntity` / `CategoryDao`
- `util/LocalSource.kt`（`parse` / `read` / `resolveAgainst` / `fixContentPath` / `httpDirPrefix`）、`net/AddressFetcher.kt`
- `net/NetApi.getText()`（`NetApi.kt:46-52`）、`net/TvboxInterceptors.SiteMark`、`util/HtmlGuard.looksLikeHtml()`（`:39/49`）、`Md5Util`、`TextUtil`、`SourceHealth`、`onIo` / `AppScope`、`TbLog`、`FileUtil.writeText`
- **R8 已有宽泛 keep**：`app/proguard-rules.pro:35` 的 `-keep class tv.own.owntv.mobile.tvbox.engine.** { *; }` 已覆盖 `engine.py.PyHost`，只需补 `-dontwarn com.chaquo.python.**`

### 2.2 必须调整

| 文件 | 改动 |
|---|---|
| `config/model/TvBoxConfig.kt` | `SiteConfig` 的 `apiIsPy` / `isPySite` / `isSupported` / `unsupportedReason` |
| `repository/TvboxConfigRepository.kt` | 导入分流、站点并入、脚本就绪（本方案最大改动点） |
| `di/TvboxModule.kt` | 注册 `PyEngineHolder`，`engines` 加一路 |
| `di/TvboxRuntime.kt` | 可选：`onSwitchHook` 显式清 py 引擎 |
| `util/FileUtil.kt` | `pyDir()` |
| `net/OkHttpProvider.kt` | `pyClient()`（私网拦截 + 独立 CookieStore） |
| `app/build.gradle.kts` / 根 `build.gradle.kts` / `settings.gradle.kts` / `gradle/libs.versions.toml` / `gradle.properties` / `app/proguard-rules.pro` | Chaquopy 整套接线 |
| `tools/i18n/safe_literals.txt` 或 `res/values/strings_mobile.xml` | 新增日志/错误文案登记 |

### 2.3 新增

`app/src/main/python/base/{__init__.py, loader.py, spider.py}`；`app/src/py/java/.../tvbox/engine/py/{PyHost.kt, PyEngine.kt, PySpider.kt}`；`app/src/py/java/.../tvbox/di/PyEngineHolder.kt`；`app/src/nopy/java/.../tvbox/di/PyEngineHolder.kt`；对应 `src/py/test` / `src/py/androidTest` / `src/test` 测试。完整清单见 §5。

### 2.4 关键适配点（照抄源码会踩坑的地方）

1. **`PyHost` 的 FQCN 就是 Python import 路径**：统一用 `tv.own.owntv.mobile.tvbox.engine.py.PyHost`；`loader.py` / `spider.py` 里的 `from com.vod.player.engine.py import PyHost` 必须一并改掉。两个方法保留 `@JvmStatic`，否则 Chaquopy 的 Java 代理按类名调用会 `NoSuchMethod`，R8 改名同样断链。
2. **`PyHost.fetch` 是 Python 线程内的同步阻塞调用**：用 `OkHttpProvider.get().newBuilder().callTimeout(timeoutMs).build()`（或新增 `pyClient()`），`TextUtil.absoluteUrl` 规范化，手工写 `SiteMark.HEADER` + `SiteMark.encode(siteKey)`，返回 Gson `{"code","url","content"(Base64.NO_WRAP),"charset"}`；任何失败（含 `Throwable`，OkHttp 的 SSL/协议错误是 `Error` 系）都返回 `code=-1` 的 JSON，不让异常击穿脚本。`log(level, tag, msg)` 四档改 `TbLog.tag(safeTag).d/i/w/e`。
3. **安全加强（VOD 没有、Mobile 应加）**：py 脚本是不可信第三方代码，`exec_module` 会执行顶层任意代码。VOD 的 `PyHost` 走主 client，而 Mobile 主链**故意不拦私网**（`OkHttpProvider.kt:217-219`：要保留 `clan://<host>` 局域网配置共享）；只有 jar 链挂了 `LoopbackFastFailInterceptor(blockPrivateNet = true)` + `PrivateNetGuardDns`（`:248-251`）。目标应新增 `OkHttpProvider.pyClient()`，形态对齐 `jarStubClient()`（`get().newBuilder().addInterceptor(...)`），堵住 py 脚本的 SSRF 面并隔离 Cookie。
4. **`PySpider.call()` 的并发模型必须原样保留**：`withTimeoutOrNull + suspendCancellableCoroutine + 独立 PY_EXECUTOR(8)`。VOD 实测排除过三条错路（`withContext(IO)` 无挂起点取消不进去、`async().await()` 结构化并发仍等子 Job、`coroutineScope` 同理），同步 `callAttr` 不可中断，超时只是「协程不再等」。`outcome` 必须用 `Result<PyObject?>?` 三层语义区分真超时 / Python `None` / 脚本异常，否则 `init` 返回 `None` 会被误判超时。`Floors`（init/detail 35s、home/category/play 20s、search 13s）与 `timeoutFor() = max(floor, site.timeout + 5s)` 照搬。
5. **`upsertSite()` 不能直接抄**：VOD 用 Room `configDao.getActive()`；Mobile 用 `TvboxPrefs.activeConfigId` + `ConfigMeta` 索引 + `FileUtil.configDir(context)/"$id.json"`（`encodeConfig` 带 `_cacheVersion`）。保持产品语义「同 key 覆盖、并入当前激活配置、无激活配置才新建、`applyConfig(merged)` + `onConfigsChanged()`」，**不要新建独立配置再切换**（VOD `:486` 的决策：那会让用户原有源看起来丢了）。
6. **`ensurePyFile()` 相对路径兜底**：`./js/ss/x.py` 这类配置内相对 api 要用 `LocalSourceResolver.resolveAgainst(api, activeSourceUrl())`；Mobile 在导入期已有 `fixContentPath`（`TvboxConfigRepository.kt:340/578/612`），但使用点兜底仍需加（对齐 `ensureSiteJar():817-820`）。五种形态：`file://` 私有文件直通、`clan://localhost` 走已授予的 `localTreeUri()`、`clan://<host>` 转成 `http://host/file/...` 走下载、`content://` 走 `LocalSourceResolver.read`、http(s) 走 `NetApi.getText(..., timeoutMs = 30_000)`。全部用 `HtmlGuard.looksLikeHtml(text)` 挡 HTML 落地页。
7. **JSON 容器类型不同**：`SiteConfig.header` 与 `PlayResult.header` 在 Mobile 是 **kotlinx `JsonObject`**（VOD 是 Gson `JsonObject`）。下载脚本时用 `site.header?.toHeaderMap()`；`PlayNormalize` 已内部适配，`PySpider` 只传字符串即可。`PySpider` 自己构造 `pyList` / `pyExtend` 仍可用 Gson（`libs.gson` 已在依赖里）。
8. **`isPySite` 建议比 VOD 更稳**：`api.substringBefore('?').substringBefore('#').endsWith(".py", true)`（对齐 Mobile 已有 `apiIsJs` 对 query/fragment 的处理）。但 `PyEngine.supportsSite(site)` 必须与之**逐字一致**，否则出现「UI 显示可用 → 点开必失败」的双层判定错位。
9. **CPython 不要在 `Application.onCreate` 启动**：`Python.start(AndroidPlatform(...))` 约 100~300ms，会进冷启关键路径。沿用 `PyEngine.obtain()` 惰性启动 + `startLock` 双检；配置生效时可选加 `onPyWarmUp` 后台预热 hook（`applyConfig` 的 `:460-464` 已有 JS 版可参照）。

---

## 3. 数据模型与依赖差异

### 3.1 数据模型

`SpiderResult` / `ClassInfo` / `Movie` / `FilterDef` / `FilterValue` / `PlayResult` 两项目同构，**无需新增模型**。差异只在容器类型与持久化：

| 项 | VOD | Mobile | 处置 |
|---|---|---|---|
| `SiteConfig.header` | Gson `JsonObject?` | kotlinx `JsonObject?` | `toHeaderMap()` 扩展 |
| `PlayResult.header` | Gson `JsonObject?` | kotlinx `JsonObject?` | `PlayNormalize` 已适配 |
| 配置持久化 | Room `ConfigEntity` / `ConfigDao` | `TvboxPrefs` + `ConfigMeta` 索引 + `configs/{id}.json` | `upsertSite` 按目标重写 |
| 激活配置 | `configDao.getActive()` | `prefs.activeConfigId` + `_activeConfig.value` | 同上 |
| 内容落地 | 私有 JSON / 自有 UI 状态 | Core Room 表 + `TvboxSourceProvisioner` / `TvboxIndexer` | 复用，不改 |
| `PlayResult` 消费 | `playerManager.play` | `TvboxPlayResolver.direct()` → `ResolvedPlay` → `VodTuner.playTvbox` | 复用，不改 |

合成站点只复用既有 `key` / `name` / `type` / `api` / `searchable` 字段，**`encodeConfig` 的 `_cacheVersion` 无需升级**，但必须补一条含 py 站点的 round-trip 单测。

### 3.2 构建与依赖

| 项 | VOD | Mobile | 动作 |
|---|---|---|---|
| AGP / Kotlin | `8.13.1` / `2.1.0` | **`9.4.0` / `2.4.20`（AGP 9 内置 Kotlin）** | ⚠ 兼容性首要风险 R1 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 24 | `release(37)` / 36 / 26 | — |
| Chaquopy | `17.1.0`、Python `3.11`、pip `requests` / `lxml` / `pycryptodome` | 无 | 新增 |
| ABI | `defaultConfig.ndk` = arm64+v7a；`debug` 追加 x86_64；`-PPY_X86_64=true` 让 Chaquopy 也生成 x86_64 运行时 | **productFlavors**：`standard`(arm64,v7a) / `x86_64`（`app/build.gradle.kts:74-84`） | ⚠ Chaquopy 只读 `defaultConfig` 的 abiFilters，必须按 flavor 配置（R2） |
| 打包 | `useLegacyPackaging = true`（`extractNativeLibs=true`） | `packaging.jniLibs.keepDebugSymbols += "**/*.so"`，未设 legacy | 需实测 `libcrypto_chaquopy.so` 能否 `dlopen`（R3） |
| Gradle 属性 | `WITH_PY=true` | `android.nonTransitiveRClass=false`、`configuration-cache=true`、`caching=true` | ⚠ Chaquopy 与配置缓存兼容性未知 |
| 插件仓库 | 显式 `repo1.maven.org` + 阿里云镜像（VOD `settings.gradle.kts` 注释记载 `repo.maven.apache.org` 被拦） | `pluginManagement` 为 `google()` / `mavenCentral()` / `gradlePluginPortal()`（`settings.gradle.kts:1-13`） | 解析失败时补镜像 |
| 日志 / DI | Timber / `ServiceLocator` | `TbLog` / Koin `tvboxModule` | 替换 |
| 播放器 | Media3 + IJK + System | Core `player-core` + `libmpv` + `OwnTVPlayer` | **不引入** |
| 权限 | `MANAGE_EXTERNAL_STORAGE` + `@xml/network_security_config` | 明确注释**不用**外部存储权限、SAF-only、`usesCleartextTraffic="true"` | 保持 SAF-only |
| i18n | 无门禁 | `verifyI18nLiterals` 挂 `preBuild` | 新 Kotlin 字面量登记；`.py` 不扫描 |

---

## 4. 分阶段实施步骤

### P0 — 兼容性预研（先做，失败即停止后续）

在 `mobile-mini` 上只做构建接线：根 `build.gradle.kts` 加 `alias(libs.plugins.chaquopy.python) apply false`、版本目录加 `chaquopy = "17.1.0"`、`app/build.gradle.kts` 加 `id("com.chaquo.python") apply false` + `chaquopy { defaultConfig { version = "3.11"; pip { ... } } }`；一个最小 `androidTest` 只调 `Python.start(AndroidPlatform(ctx))` 并取 `sys` 模块。

> Kotlin DSL 细节（VOD `app/build.gradle.kts:346-349`）：`pluginManager.apply()` 不生成类型安全访问器 `chaquopy { }`，必须 `extensions.configure<com.chaquo.python.ChaquopyExtension>("chaquopy") { ... }`；`defaultConfig` / `productFlavors` 闭包类型是 `PythonExtension`。

**验收**：`:app:assembleStandardDebug`、`:app:assembleX86_64Debug -PPY_X86_64=true`、`:app:assembleStandardRelease`（R8 开）全绿；两种 ABI 都能 `dlopen` `libcrypto_chaquopy.so`；`configuration-cache=true` 下不报错。**不通过则先解 R1/R2/R3，不得进入 P1。**

### P1 — Python assets + 宿主桥

落 3 个 `.py`（改 import 路径）、`PyHost.kt`、`OkHttpProvider.pyClient()`、`FileUtil.pyDir()`。保留 `loader.py` 的 `load_spider` 双形态（契约继承 + 裸类 duck typing 动态混入 `BaseSpider`，sanity check 必须在混入**之前**做）、`redirect_stdio`、`loads` / `dumps`（`ensure_ascii=False`）；`spider.py` 的 `Response` 四级解码、五接口空实现、`fetch` / `html` / `regStr`。

**验收**：`PyHostInstrumentedTest` 用本地 MockWebServer 校验 `fetch` 的 `code` / `url` / `content`(base64) / `charset`、自定义 header、`SiteMark` 埋点、非法 header 跳过、失败 `code=-1`；`redirect_stdio` 能把脚本 `print` 送进 `TbLog`。

### P2 — 引擎与结果归一化

落 `PyEngine.kt`（LRU `MAX_LIVE_INSTANCES = 12`、`LinkedHashMap(accessOrder = true)`、`buildMutex`、`lastErrors`、`PyLoadException`、惰性 `ensurePythonStarted`）、`PySpider.kt`（五接口 + `PY_EXECUTOR(8)` + `Floors` + `runGuarded` 兜 `Throwable` + `warnMissingDependencies`）、`PyEngineHolder` 双实现；复用 `JarResultParser.toResult` / `PlayNormalize.normalizePlay`。

**验收**：`PyEngineInstrumentedTest` 覆盖加载成功、坏脚本 `ValueError`、只 import 基类、裸类 duck typing、分级超时协程即时脱身、`getDependence` 未预装告警、`destroy()`、`invalidateAll()`；JVM 侧 `PyEngineRoutingTest` / `PyResultContractTest`。

### P3 — 配置导入与脚本就绪

改 `TvBoxConfig.SiteConfig` 与 `TvboxConfigRepository`：`importLocalFile` 分流 → `importPyScript` → `upsertSite`；`ensurePyFile` / `downloadPy` / `materializeLocalPy` / `isValidPyText` / `isValidPyFile`；companion 里的 `isPyFileName` / `looksLikePyScript` / `pySiteKey` / `pyDisplayName` / `PY_SPIDER_CLASS` 抽成纯函数供 JVM 单测断言。

> 本地导入必须查 `OpenableColumns.DISPLAY_NAME`（VOD `:429-431`）：SAF 的 `lastPathSegment` 常是不透明 id（`document/msf:123`），否则合成站点名退化成「Python 源 <md5>」。合成 `api` 必须是 `file://` 绝对路径且以 `.py` 结尾 —— `content://` 形态不以 `.py` 结尾，会让站点路由回 jar/JS。

**验收**：SAF 导入 `.py` 生成 `py_<md5[:12]>` 站点；重复导入天然去重；无扩展名文件靠内容嗅探识别；JSON 配置不被误判（不以 `{` 开头 + 模块级 `class Spider`）；坏内容/HTML 落地页被拒；五种 api 形态各跑一次；缓存命中不重复下载；导入期**不做脚本预加载**（`exec_module` 会执行顶层代码）。

### P4 — 装配、可见性、播放闭环

`TvboxModule` 加 `single<PyEngine>` 经 `PyEngineHolder.create(androidContext()) { site -> get<TvboxConfigRepository>().ensurePyFile(site) }`（**不要**注册成 `single<OkHttpClient>`，见 `TvboxModule.kt:100-104` 的注释），追加到 `engines`；`TvboxRuntime.onSwitchHook` 按需显式清理；补 `proguard-rules.pro` 与 i18n 清单。

**验收**（真机/模拟器，按 `docs/TVbox回归测试报告-模拟器.md` 全表口径）：配置导入（含 py 站点）→ 冷启缓存恢复 → 首页内容 → 详情与线路/分集 → 单集起播（直连 + `parse:1` 嗅探两条）→ 聚合搜索 → 收藏 / 继续观看 / Library 经 `TvboxPlaybackRouter` 反解起播 → 分类锁 / 儿童模式 / 重锁停播。

### P5 — 发布加固与文档

release 包 R8 + 正式 keystore 验证；体积与 ABI 分叉核对；`-PWITH_PY=false` 精简包验证 py 源灰显且 `unsupportedReason` 文案正确；CI 矩阵补构建变体；更新本文状态；**清理测试构建产物（脚本除外）**。

---

## 5. 预计新增 / 修改文件清单

路径相对 `OwnTV_Mobile/`。`...` = `app/src/.../java/tv/own/owntv/mobile/tvbox/`。

### 5.1 新增（NEW）

| 文件 | 内容 |
|---|---|
| `app/src/main/python/base/__init__.py` | 包标记（VOD 有，必须一并带） |
| `app/src/main/python/base/loader.py` | `load_spider` / `loads` / `dumps` / `redirect_stdio`、`_HostLogWriter`、裸类混入逻辑 |
| `app/src/main/python/base/spider.py` | `Response`、`Spider` 基类、五接口空实现 + 协议桩、`fetch` / `html` / `regStr` |
| `app/src/py/java/.../engine/py/PyHost.kt` | `@JvmStatic fetch` / `log` |
| `app/src/py/java/.../engine/py/PyEngine.kt` | LRU 12、`buildMutex`、`ensurePythonStarted`、`PyLoadException`、`supportsSite` |
| `app/src/py/java/.../engine/py/PySpider.kt` | 五接口 + `PY_EXECUTOR` + `Floors` + 归一化 |
| `app/src/py/java/.../di/PyEngineHolder.kt` | `create(...): Engine? = PyEngine(context, pyFileProvider)` |
| `app/src/nopy/java/.../di/PyEngineHolder.kt` | `create(...): Engine? = null`（精简包灰显 py 源） |
| `app/src/py/test/.../PyEngineRoutingTest.kt` | JVM：`.py` 命中、`.js` / `drpy2.min.js` / `csp_X` / `type!=3` 不命中 |
| `app/src/py/test/.../PyResultContractTest.kt` | JVM：`loader.dumps` JSON → `JarResultParser.toResult` / `PlayNormalize` 契约 |
| `app/src/py/androidTest/.../PyEngineInstrumentedTest.kt` | CPython 运行时（只能在设备/模拟器） |
| `app/src/py/androidTest/.../PyHostInstrumentedTest.kt` | `PyHost.fetch` / stdio 转发 |
| `app/src/test/java/.../tvbox/repository/PyImportTest.kt` | 纯函数，不依赖 Chaquopy，放常规 test 源集 |

### 5.2 修改（MOD）

| 文件 | 改动 |
|---|---|
| `build.gradle.kts`（根） | `alias(libs.plugins.chaquopy.python) apply false` |
| `gradle/libs.versions.toml` | `chaquopy = "17.1.0"` + `[plugins] chaquopy-python = { id = "com.chaquo.python", version.ref = "chaquopy" }` |
| `settings.gradle.kts` | 仅当插件解析失败时，在 `pluginManagement.repositories` 补 `repo1.maven.org` / 阿里云镜像 |
| `gradle.properties` | `WITH_PY=true`、`PY_X86_64=false` |
| `app/build.gradle.kts` | `withPy` / `withPyX86` 开关；`buildConfigField("boolean", "WITH_PY", ...)`；`sourceSets` 按开关并入 `src/py`（main+test+androidTest）/ `src/nopy`（main）；`chaquopy {}` 扩展（3.11 + `buildPython` + pip 三件套）；**按 productFlavor 配 Chaquopy ABI**；release `proguardFile("proguard-chaquopy.pro")` 或合并进 `proguard-rules.pro`；必要时 `packaging.jniLibs.useLegacyPackaging` |
| `app/src/main/java/.../config/model/TvBoxConfig.kt` | `apiIsPy` / `isPySite`、`isSupported`、`unsupportedReason` |
| `app/src/main/java/.../repository/TvboxConfigRepository.kt` | P3 全部方法 + companion 纯函数 |
| `app/src/main/java/.../util/FileUtil.kt` | `pyDir(context) = subDir(context, "py")` |
| `app/src/main/java/.../di/TvboxModule.kt` | 注册 `PyEngineHolder`，`engines` 加一路 |
| `app/src/main/java/.../di/TvboxRuntime.kt` | 可选：`onSwitchHook` 显式 `pyEngine.invalidateAll()`（注意 `sourceRepository.invalidateAll()` 已遍历，避免重复 destroy） |
| `app/src/main/java/.../net/OkHttpProvider.kt` | `pyClient()`：私网字面量拦截 + DNS 防护 + 独立 CookieStore |
| `app/proguard-rules.pro` | `-keep class tv.own.owntv.mobile.tvbox.engine.py.** { *; }`（显式双保险）+ `-dontwarn com.chaquo.python.**` |
| `tools/i18n/safe_literals.txt` 或 `res/values/strings_mobile.xml` | 新增日志 / 错误文案登记 |
| `docs/TVbox-PY源迁移方案.md`（本文）、`.github/workflows/android.yml` | 方案留档 / CI 变体（可选） |

### 5.3 明确不需要改动

`OwnTV_Core/**`、`vendor/OwnTV_Core/**`、`SourceRepository.kt`、`Spider.kt`、`SpiderResult.kt`、`JarResultParser.kt`、`PlayNormalize.kt`、`TvboxPlayResolver.kt`、`TvboxPlaybackRouter.kt`、`VodTuner.kt`、`ConfigParser.kt`、`AndroidManifest.xml`。

---

## 6. 测试验收要点

**JVM 单测**（`testStandardDebugUnitTest`）
- `isPyFileName` / `looksLikePyScript` / `pySiteKey` / `pyDisplayName`（含 SAF 不透明 id 与纯数字 id 的兜底）
- `PyEngine.supportsSite`：`.py` = true；`.js` / `drpy2.min.js` / `csp_X` / `type != 3` = false（防 JS/Jar 被 PyEngine 抢走）
- `loader.dumps` 输出 → `JarResultParser.toResult` 的 `class` / `filters` / `list` / `vod_*`；→ `PlayNormalize.normalizePlay` 的 `parse` / `url` / `header` / `flag`
- 含 py 站点的 `encodeConfig` ↔ decode round-trip
- `BuildConfig.WITH_PY = false` 时 `isSupported` 与 `unsupportedReason` 翻转；`ConfigParser` 导入警告文案

**androidTest**（CPython 依赖 Android ABI，**JVM 单测起不来**，必须走设备/模拟器）
- `Python.start` 幂等 + 并发双检；`load_spider` 成功 / 无 `Spider` 类 / 只 import 基类 / 裸类 duck typing
- 分级超时：协程能即时脱身，线程里 Python 继续跑完不崩溃
- `searchContent` / `detailContent` 归一化；`destroy()` / `invalidateAll()`
- `PyHost.fetch` 与 `redirect_stdio` → `TbLog`

**真机 / 模拟器全链路回归**：沿用 `docs/TVbox回归测试报告-模拟器.md` 全表，额外加 py 专属项 —— 27 个 py 源配置的 LRU 上限与内存、坏脚本不连坐其它源、切换配置后实例池清空、远端脚本缓存命中、本地 `clan://localhost` py 脚本、`WITH_PY=false` 灰显、release R8 包起播。

**构建门禁**：`:app:testStandardDebugUnitTest`、`verifyI18nLiterals`、`lintStandardDebug`、`:app:assembleStandardRelease`（R8 开）、`connectedStandardDebugAndroidTest`；模拟器包用 `-PPY_X86_64=true :app:assembleX86_64Debug`。

---

## 7. 主要风险与对策

| 风险 | 说明 | 对策 |
|---|---|---|
| **R1 Chaquopy 17.1.0 × AGP 9.4.0 / Kotlin 2.4.20 / compileSdk 37 / configuration-cache** | VOD 是 AGP 8.13.1；插件依赖 AGP 内部 API，且 Mobile 开了 `org.gradle.configuration-cache=true` | P0 预研先行；失败依次尝试：升级 Chaquopy 到支持 AGP 9 的版本 → 临时关配置缓存 → 把 py 源集隔离成独立 feature 变体。**不要降 AGP**（Core / TV / Mobile 三仓锁版本） |
| **R2 ABI flavor 与 Chaquopy 生成规则** | VOD 用 `defaultConfig.ndk.abiFilters`（`app/build.gradle.kts:178-181`）；Mobile 的 abiFilters 在 `productFlavors`，Chaquopy 按 `defaultConfig` 生成运行时 → 可能缺 `libcrypto_chaquopy.so`（VOD 文件头 `:22-27` 记载过这个坑） | 在 `chaquopy { productFlavors { ... } }` 逐 flavor 配置，或补 `defaultConfig.ndk.abiFilters`；`-PPY_X86_64=true` 仅联调用 |
| **R3 16KB 页设备 / .so 对齐 / extractNativeLibs** | `libs.versions.toml:41-44` 已记载 QuickJS `.so` 4KB 对齐在 16KB 页设备 `dlopen` 失败；Chaquopy 的 `libpython` / `libcrypto` 同类风险；Mobile 未设 `useLegacyPackaging` | P0 在 16KB 页设备/模拟器实测 `dlopen`；必要时 `packaging.jniLibs.useLegacyPackaging = true` |
| **R4 体积与 32 位** | CPython + lxml / pycryptodome 显著增大 APK；Python 3.12+ 只支持 64 位，而 `standard` flavor 保留 `armeabi-v7a` | 锁 **Python 3.11**（VOD `:354-356` 实测结论）；ABI split + `WITH_PY=false` 精简包；pip 只装 3 个必需包 |
| **R5 超时不可中断 + GIL** | `callAttr` 同步阻塞，超时只是协程脱身；`PY_EXECUTOR(8)` 叠加 `SourceRepository` 的 `Semaphore(4)`，且 CPython GIL 会把并发 Python 调用串行化，聚合搜索可能变慢 | 保留 LRU 12、`Floors`、`SourceHealth` 冷却；py 源单独降并发或在聚合搜索里限制 py 路数 |
| **R6 R8 断链** | Python 按 FQCN 字符串 `from ... import PyHost`；R8 改名即 `NoSuchMethod` | 现有宽 keep 已覆盖 `tvbox.engine.**`，仍显式加 `engine.py.**` + `-dontwarn com.chaquo.python.**`；**release 真机必测一次起播** |
| **R7 权限 / 路径语义** | Mobile 是 SAF-only；`content://` 形态 api 不以 `.py` 结尾会让站点路由回 Jar/JS | 本地导入**一律落私有目录并合成 `file://<abs>.py`**；`clan://localhost` 走已授予的 `localTreeUri()`；**不加** `MANAGE_EXTERNAL_STORAGE` |
| **R8 任意代码 / SSRF** | py 脚本 `exec_module` 执行顶层代码，可访问宿主网络与私有目录 | 新增 `pyClient()` 私网拦截 + 独立 Cookie；脚本只落 `files/tvbox/py`；明确「用户自行导入即信任」的产品边界。注意脚本自建 `requests.Session` 会绕开宿主统一出口（VOD `PyHost.kt:25-26` 已接受的生态取舍） |
| **R9 i18n 门禁 / 工作区改动** | `verifyI18nLiterals` 挂 `preBuild`，新 Kotlin 中文字面量会红构建；`TvboxModule.kt` 等已有未提交改动 | 新文案登记 `safe_literals.txt`；改动基于当前工作区叠加，勿 `checkout` / 覆盖 |
| **R10 双包开关复杂度** | `src/py` ↔ `src/nopy` + `BuildConfig.WITH_PY` 会让 `main` 源码只能依赖 `PyEngineHolder` | 照搬 VOD 成熟模式；`main` 源码**不要**直接引用 `PyEngine` 或 `com.chaquo.python.*`，否则精简包编译失败 |
| **R11 配置缓存版本 / 数据迁移** | 合成站点写进 `_cacheVersion` 缓存 JSON | 不新增字段故无需升版本，但 round-trip 单测必加；`upsertSite` 须同时更新 `ConfigMeta` 的 siteCount |

---

## 8. 待拍板决策（实施前需确认）

| # | 决策 | 建议 |
|---|---|---|
| 1 | 是否引入 `WITH_PY` 双包开关？ | **引入**：完整包默认 + 精简包可出，沿用 `src/py` / `src/nopy` 互斥源集，成本可控且缓解 R4 |
| 2 | 是否保留 `armeabi-v7a`？ | **保留** → 必须锁 Python 3.11（3.12+ 只有 64 位运行时） |
| 3 | `PyHost.fetch` 走主 client 还是新增受限 `pyClient()`？ | **新增 `pyClient()`**（R8），这是相对 VOD 唯一有意加强的部分 |
| 4 | 本地 py 路径是否严格 SAF-only？ | **是**，不引入 `MANAGE_EXTERNAL_STORAGE`，符合 `AndroidManifest.xml` 现有注释 |

---

> 本文为静态代码追踪结论（2026-11-12），未运行 Gradle 构建、未修改业务代码、未提交、未切换分支。P0 的兼容性结论以实机构建结果为准，若与本文 R1/R2/R3 判断不符，须回来修订本文后再进入 P1。

---

## 9. 实施状态（2026-11-13）

### 9.1 分支与版本偏离

- 实施分支 `mobile-mini`（基于方案基线 `e4cecb31` 之后的 `3d2c2acb`），未新建分支；`OwnTV_Core` / `vendor/OwnTV_Core` 未改动。
- **Chaquopy 取 `17.2.0`（方案原定 17.1.0）**：17.2.0 是首个声明支持 AGP 9 的版本，实测在 AGP `9.4.0` / Kotlin `2.4.20` / compileSdk 37 / `configuration-cache=true` 下构建通过，R1 风险未触发，无需降 AGP。
- 四项待拍板决策全部按建议落地：引入 `WITH_PY` 双包开关、保留 `armeabi-v7a` 并锁 Python 3.11、新增 `pyClient()`、本地路径严格 SAF-only。

### 9.2 P0–P4 完成情况

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | Chaquopy 接线（版本目录、根/模块插件、`WITH_PY` / `PY_X86_64`、按 flavor 的 ABI、`src/py`↔`src/nopy` 源集、`proguard-chaquopy.pro`） | 完成 |
| P1 | `base/{__init__,loader,spider}.py`（import 改为 `tv.own.owntv.mobile.tvbox.engine.py.PyHost`）、`PyHost`、`OkHttpProvider.pyClient()`、`FileUtil.pyDir()` | 完成 |
| P2 | `PyEngine`（LRU 12、惰性 CPython 启动）、`PySpider`（五接口 + `PY_EXECUTOR(8)` + `Floors` + `runGuarded`）、`PyEngineHolder` 双实现、JVM/androidTest 用例 | 完成 |
| P3 | `SiteConfig.apiIsPy/isPySite/isSupported/unsupportedReason`、`TvboxConfigRepository` 导入分流 / `upsertSite` / `ensurePyFile` / 下载与本地落盘 / 纯函数 | 完成 |
| P4 | `TvboxModule` 注册 `PyEngineHolder` 并追加 `engines`、R8 keep、i18n 登记 | 完成 |
| P5 | 发布加固、真机全链路、精简包灰显实测 | 部分（构建门禁已过，真机项未执行） |

### 9.3 与方案不一致的两处实现细节（已核实为 AGP 9 必需）

1. **源集挂载必须同时写 `java.srcDirs` 与 `kotlin.srcDirs`**：AGP 9 内置 Kotlin 走 `KotlinSourceSet`，只追加 `java.srcDirs` 时 `src/py` 不会进入 kotlinc，表现为 `Unresolved reference 'PyEngineHolder'`。
2. **`saveConfig` 拆出 `saveConfigLocked`**：`upsertSite` 需要"锁内再落库"，Mutex 不可重入，直接调 `saveConfig` 会死锁。

### 9.4 已完成的门禁与验证

- `:app:assembleStandardDebug`：成功（APK 约 122 MB，含 arm64-v8a / armeabi-v7a 的 CPython 与 pip 三件套）。
- `:app:assembleStandardRelease`：成功（R8 + `proguard-chaquopy.pro`；产物 unsigned，因本机 release keystore 未配置）。
- `:app:assembleX86_64Debug -PPY_X86_64=true`：成功，APK 内含 `lib/x86_64/libpython3.11.so`（模拟器联调用）。
- `:app:compileStandardDebugKotlin -PWITH_PY=false`：成功，精简包只编译 `src/nopy` 的桩，`engine/py` 无产物（R10 成立）。
- `:app:testStandardDebugUnitTest`：441 用例 0 失败；`PyImportTest` / `PyEngineRoutingTest` / `PyResultContractTest` 全过。
- `:app:compileStandardDebugAndroidTestKotlin`：成功（`PyEngineInstrumentedTest` / `PyHostInstrumentedTest` 可编译）。
- `:app:lintStandardDebug`：`0 errors`。
- `verifyI18nLiterals`：通过（新增技术/日志字面量已按既有 `log` / `technical` / `uri` / `file-path` / `regex` 口径登记）。

### 9.5 尚未执行的验收项

- 真机 / 模拟器 `connectedStandardDebugAndroidTest`（CPython 运行时用例、`PyHost.fetch` 契约）未执行。
- P4 的全链路真机回归（导入含 py 站点的配置 → 首页 / 详情 / 选集 / 播放 / 聚合搜索 / 收藏与继续观看）未执行。
- release 真机起播、`WITH_PY=false` 精简包 py 源灰显文案、16 KB 页设备 `dlopen` 均未实测（本机设备 `PAGE_SIZE=4096`，走不到 16 KB 分支，与 JS 迁移结论一致）。
- 导入期 py 源预热未做（沿用惰性启动，首个 py 源 obtain 时启动 CPython，避免进冷启关键路径）。
