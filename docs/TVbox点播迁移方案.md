# TVbox 点播功能迁移方案（迁入 OwnTV_Mobile）

> **存储结论已作废（2026-11-11）**：本方案"内容/进度/历史/收藏独立存 app 私有 JSON、不写 Core 表"的口径
> 已被 `docs/点播内容接入Core方案.md`（P0–P5 已实施）取代——**索引写 Core 表、播放仍走 TVbox**：
> 分类/收藏/进度/历史按档案落 Core，继续观看、搜索、Library、备份全部复用；`TvboxStore` 与旧 JSON 已删除（不迁移）。
> §5.4 及文中各处"不写 Core 表"表述仅作历史记录保留。
>
> 状态：**方案评审稿，未实施**。本次只输出方案，未修改任何代码文件。
> 版本：v1.2（2026-11-08 拍板修订）
> 日期：2026-11-08
>
> **v1.2 拍板口径（用户已确认，全部按此落地）**：
> 1. **TVbox 内容接入 Core 分类锁 / 儿童模式**：复用 Core 的 `lockedCats`、Customize PIN、解锁会话、PIN 限速器与 `AdultCategoryClassifier`，TVbox 侧实现分类判定与 UI（详见 §5.5）。
> 2. type 3 JS/drpy **本期不做**，仅保留 P5 可选位。
> 3. 播放页收藏入口 **隐藏**（`TVBOX_ITEM_ID`），不写 Core 收藏表。
> 4. TVbox 进度/历史 **不进入** Core「继续观看」，只在 VOD 页内提供入口。
> 5. 多集每集 **逐集携带 headers**，不共用首集。
> 6. 配置管理：**单配置启用 + 列表切换**，不做多配置合并。
> 7. 保留 `SourceHealth` 冷却/灰显。
> 8. 首版 **不接字幕/弹幕**；播放页字幕入口对 TVbox 隐藏。
>
> **v1.1 已修正项**（保留记录）：桩文件 12→11；补 `CoroutineUtil.kt`/`TvBoxParity.kt`；修正 `VodTuner.saveProgress()/began()` 写 Core 表的设计冲突；修正 `ConfigParser.kt:172` 的 `ParseConfig.serializer()` 依赖；校准行号与 Mobile Gradle 9.7.1。
>
> 依据文档：`Qwn\项目说明.txt`、`OwnTV_Mobile\docs\Core源码集成方案.md`、`video_on_demand\docs\站源解析全链路流程图.md`。**本方案行号与文件清单已于 2026-11-08 对 `OwnTV_Mobile @62c5058` / `OwnTV_Core @0619549b` / VodPlayer `main @4c8d289` 核对**；VodPlayer 侧行号仍以其 `main` 源码为准。

---

## 0. 结论摘要

| 项 | 结论 |
|---|---|
| 迁移形态 | **只搬"源导入 → 源解析 → 内容展示 → 播放地址解析"的业务层 + 纯 JVM 算法，UI 用 Mobile 的 Stage 组件重写，播放出口换成 `VodTuner` → `OwnTVPlayer`** |
| 分支基准 | Mobile 只能在 `mobile-mini` 改；**VodPlayer 的解析代码必须以本地 `main @4c8d289` 为准，不能用当前检出的 `feature/tv`**（详见 §1.3，这是本次最大的分支陷阱） |
| 播放器 | 复用 `OwnTVPlayer.play()` / `playEpisodes()`（libmpv + player-core 内 Media3）；**不迁移** VodPlayer 的 `PlayerManager` / `PlayerEngine` / `ExoPlayerEngine` / IJK / `PlayerSurfaceHost`，不新增任何播放器依赖 |
| 接入位置 | `MobileNavHost.kt:153` 的 `MobileDestination.VOD -> VodScreen()`；`VodScreen.kt:22` 是占位页，直接替换；`MobileDestination.kt:35` 的 VOD 枚举已在底栏/侧栏且恒显示，**不要改** |
| 数据落点 | 内容/进度/历史/收藏独立存 app 私有 JSON / DataStore；**分类锁与儿童模式复用 Core 的锁定数据（`owntv_customizations` 的 `lockedCats`）、Customize PIN、解锁会话与 `isAdult` 判定**；不改 Core schema、不动 `vendor/OwnTV_Core` 源码 |
| 分类锁 / 儿童模式 | 分类锁 key 用 `tvbox:<siteKey>:<typeId|typeName>` 写入 Core `lockedCats`（MOVIE section），复用同一 PIN/限速器/解锁会话；儿童模式复用 `profile.isKids` + `AdultCategoryClassifier.isAdult`，并补 TVbox 中文成人词表（§5.5） |
| 范围 | 仅 TVbox 源（CMS + jar/spider + WebView 嗅探兜底，JS/drpy 列为可选阶段）；**排除 py 源、直播、投屏、下载、网盘源、TV 双 UI** |
| 备份 | 所有原文件备份统一放 `OwnTV_Mobile\docs\backup`，沿用 `<文件名>.kt.bak` 命名 |

**一句话**：Mobile 底部栏早已预留 VOD 占位页，`OwnTVPlayer.play(url: String, ...)` 天然接受任意 http(s)/m3u8 直链、自定义 headers 与剧集队列；迁移只需在 `app/` 侧补齐 TVbox 解析链，再通过 `VodTuner` 新增的「URL 直起」通路写播放状态并 `navigate(PLAYER_ROUTE)`。

---

## 1. 分支与仓库基准（重点）

### 1.1 项目说明的硬约定

`Qwn\项目说明.txt` 第 1–4、7 条：

1. `OwnTV_Mobile` / `OwnTV_Core` 各自同步 GitHub `ghnk211` fork 的最新代码；
2. **本地 OwnTV_Mobile 只改动 `mobile-mini` 分支，本地 OwnTV_Core 只改动 `mobile` 分支**；Mobile 的 `vendor/OwnTV_Core` 快照 = Core `mobile` 分支最新版；
3. 改动提交流程见 `docs\Core源码集成方案.md`；
4. 一个需求实现后要清理测试产生的构建文件（脚本文件除外）。

`Qwn` 根目录**不是** git 仓库；实际有两个独立仓库 `Qwn\OwnTV_Mobile`、`Qwn\OwnTV_Core`，以及仓外的 `git\video_on_demand`。

### 1.2 实际检出核对（2026-11-08）

| 仓库 | 当前分支 | HEAD | 跟踪 | 结论 |
|---|---|---|---|---|
| `OwnTV_Mobile` | `mobile-mini` | `62c5058` | `fork/mobile-mini` | 与约定一致，**所有改动只能落这里** |
| `OwnTV_Core` | `mobile` | `0619549b` | `origin/mobile` | 与约定一致；`vendor/OwnTV_Core` 快照 = 该提交 |
| `video_on_demand` | `feature/tv` | `1d0563e` | `origin/feature/tv`（领先 3） | **不是** TVbox 解析代码的正确基准 |
| VodPlayer 本地 `main` | `main` | `4c8d289` | 领先 `origin/main` 1 个提交 | **TVbox 解析代码的正确基准** |

Mobile 工作区另有未提交改动，实施前必须保留：`M app/build.gradle.kts`（本机签名路径改为 `keyStroe` + 独立 test keystore）、`M docs/Core源码集成方案.md`、`?? docs/backup/`（3 个 `.bak` + README，**尚未纳入版本控制**）、`?? docs/TVbox点播迁移方案.md`（本方案，未跟踪）。

### 1.3 video_on_demand 的分支陷阱（必须先看）

- `feature/tv @1d0563e` 是「Merge branch 'main' of github.com/ghnk211/VodPlayer into feature/tv」，其 merge base 是 **`origin/main @b634904`**，合并的是**远端** main，**没有**本地 `main @4c8d289`。
- `git log feature/tv..main` 只剩 1 个提交：`4c8d289`「jar 相对地址使用点解析修复：spider/jar 字段裸相对与运行时未改写值按配置来源 URL 兜底；`LocalSourceResolver.resolveAgainst` 接入下载路径；+5 单测（全量 972 绿）」，改动 3 个文件：
  - `repository/ConfigRepository.kt`（+48/−14，两条下载路径透传 `sourceUrl`）
  - `util/LocalSource.kt`（+28，新增 `resolveAgainst(url, baseUrl)`）
  - `util/LocalSourceTest.kt`（+50）
- `git diff --stat main..feature/tv`：70 个文件 / +14390 / −312，**净增量几乎全是 `ui/tv/**` 的 TV 双 UI 皮肤 + 焦点/遥控器键 + TV 单测**，与 TVbox 源解析无关；反向 diff 里 `ConfigRepository.kt` 少了 `sourceUrl` 透传、`LocalSource.kt` 少了 `resolveAgainst`。
- 后果：若按 `feature/tv` 取解析代码，会重现已修的故障——**旧配置 / 本地转存配置 / 裸相对 `spider` 字段被当 URL 交给 OkHttp，表现为「配置导入成功但 csp_* 站全部找不到实现类」**。

另外 `docs/TV版本改造方案.md` 写「依据分支 `feature/tv`（当前提交 `770ee6e`）」、`docs/使用说明文档安卓版.md` 写 v0.1.4 对应 `770ee6e`，均为**旧提交、文档漂移**；`docs/站源解析全链路流程图.md` 的行号也早于当前代码。三者只能当流程参考，**以 `main @4c8d289` 源码为准**。

### 1.4 实施基准裁定

| 用途 | 基准 |
|---|---|
| 源导入 / 配置解析 / 引擎 / 播放地址解析代码 | **VodPlayer 本地 `main @4c8d289`**，用 `git -C <vod> show main:<path>` 或临时 worktree 只读取文件 |
| UI / 导航 / 播放器 / DI / 依赖 | **Mobile `mobile-mini @62c5058`** |
| 不取 | `origin/main @b634904`、`feature/tv @1d0563e`、`origin/feature/tv @359d029`、`backup/p2p-final`、`ui/tv/**` |
| 源侧仓库 | 全程只读，**不改 VodPlayer，不切换其分支**；若实施者坚持在 `feature/tv` 上取码，必须手工回填 `4c8d289` 的 3 个文件差异 |
| 目标侧仓库 | **不新建分支**，直接在 `mobile-mini` 上小步提交（项目说明第 3 条） |

---

## 2. 迁移范围与边界

### 2.1 纳入（TVbox 点播闭环）

TVBox JSON 配置驱动的站点内容：

1. **源导入**：URL / 本地文件（SAF）导入、配置下载、解密（明文 / `;pk;` Base64 / AES）、脏 JSON 注释剥离、相对 `spider`/`jar` 地址改写与按来源 URL 兜底、URL 去重、来源 URL 落库、配置缓存与切换、spider jar 预下载（md5 命名落盘）。
2. **源解析**：`SiteConfig` 支持性判定、`Engine` 路由、CMS（type 0/1/4，JSON/XML）、jar spider（type 3 `csp_*`，`DexClassLoader` + 11 个 `com.github.catvod` 桩 + 本地回环代理）、播放地址解码（密文 / Base64 / `parse=1`）、`ParseRepository` 前缀解析器、WebView 嗅探兜底（二级播放页 → 直链，是闭环必需层）。
3. **内容展示**：站点/分类/筛选、首页列表与分页、搜索、详情与线路分集（`$` / `#` / `$$$`）、海报加载。
4. **播放**：把 `playerContent` 结果解析成「最终 URL + headers/UA + 剧集列表」，交给 Mobile 原播放器起播；进度/历史/收藏独立存储。
5. **安全与家长控制**：分类锁（复用 Core `lockedCats`、Customize PIN、解锁会话与限速器）与儿童模式（复用 `profile.isKids` + `AdultCategoryClassifier.isAdult`，补 TVbox 中文词表）；见 §5.5。
6. **阶段 P5（2026-11-12 已落地）**：type 3 的 JS/drpy 站点（`engine/js/**` + QuickJS AAR）。实施方案见 `docs/TVbox-JS源迁移方案.md`；采用 QuickJS 2.1.0，并在 `JsEngine` 构造时探测 native 可用性，16KB 页设备上 JS 源整体降级为不可用（不崩进程、不影响 CMS/jar）。

### 2.2 排除（明确不带）

| 类别 | 排除对象 |
|---|---|
| **py 源（用户明确排除）** | `app/src/main/python/**`、`app/src/py/**`、`app/src/nopy/**`、`engine/py/PyEngine/PySpider/PyHost`、`PyEngineHolder`、Chaquopy 插件与 `chaquopy {} / findBuildPython / proguard-chaquopy.pro / WITH_PY / BuildConfig.WITH_PY`、`app/src/main/java` 内 py 散点（`SiteConfig.isPySite`、`isSupported` 的 `BuildConfig.WITH_PY && isPySite` 分支、`unsupportedReason` py 文案、`ConfigRepository` 的 `.py` 分流 / `importPyScript` / `upsertSite` / `ensurePyFile` / `materializeLocalPy` / `downloadPy` / 伴生对象 py 函数、`ConfigViewModel.jarReady` py 分支、`ConfigScreen` py 文案、`ServiceLocator.pyEngine`） |
| 播放器 | `player/PlayerManager.kt`、`player/engine/**`（`PlayerEngine`/`ExoPlayerEngine`/`IjkPlayerEngine`/`SystemPlayerEngine`/`EngineRouter`/`EngineRegistry`）、`ui/components/PlayerSurfaceHost.kt`、`ui/play/PlayScreen.kt`、`ui/tv/play/**`、Media3 / GSY ijk / `androidx.media:media` 依赖 |
| VodPlayer UI | `ui/**` 全部（含 `ui/tv/**` TV 皮肤、VodAppNav、ConfigScreen、HomeScreen、SearchScreen、DetailScreen、HistoryScreen）、`vm/**` 只取解析编排逻辑、不取 UI 状态类 |
| 无关业务 | 直播 `live/**` + `LiveConfig`/`CatchupConfig`/EPG、投屏 `cast/**`、下载 `download/**`、网盘源 `engine/alist/**` 与 `HomeViewModel.probeDirectory`、应用自升级 `update/**`、Web 控制台 `server/**`、反编译防护 `SignatureGuard`/`TamperGuard`、字幕/弹幕旁路与 M3U8 净化代理（首版可不做） |
| 数据/构建 | VodPlayer 的 Room（`AppDatabase`/`Entities`/`Daos`）、DataStore `SettingsStore`、KSP、Chaquopy、`ServiceLocator`、`VodApp`、`MainActivity` |

### 2.3 播放器硬约束（验收红线）

- 唯一播放出口：`vendor/OwnTV_Core/player-core/.../OwnTVPlayer.kt:2811 play(...)` 与 `:2879 playEpisodes(...)`，经 `app/.../ui/screens/library/VodTuner.kt` 调用。
- `git diff` 中**不得出现**新增 `androidx.media3.exoplayer` / `media3-datasource-okhttp` / `gsyvideoplayer` / IJK / VLC / ExoPlayer 依赖，也**不得修改** `vendor/OwnTV_Core/player-core/**`。
- 播放页仍是 `ui/player/PlayerScreen.kt` + `PLAYER_ROUTE`，不新增播放页、不新增播放 ViewModel。

---

## 3. 两个项目模块与调用流程对应关系

### 3.1 全链路对照表

| 环节 | VodPlayer（`main @4c8d289`） | OwnTV_Mobile（`mobile-mini`） | 迁移策略 |
|---|---|---|---|
| 应用入口 / 装配 | `VodApp.kt` + `di/ServiceLocator.kt`（手写单例、`engines = CmsEngine → JarEngine → JsEngine → [PyEngine]`） | `OwnTVMobileApp.kt:157-165` `startKoin { modules(coreModule … settingsModule) }`（12 module） | 重写为新增 `tvboxModule`，用 `single { }` / `viewModelOf(::…)`；不搬 ServiceLocator |
| 导航 | `ui/nav/VodAppNav.kt` 的 `Routes`（CONFIG/HOME/SEARCH/DETAIL/PLAY，参数写 `PlayArgsStore`/`DetailArgsStore`） | `MobileNavHost.kt:62-287` 单 NavHost，`PLAYER_ROUTE = "player"` **无参**，播放状态在 `VodTuner.playing` / `LiveTuner.channel` 单例里 | VOD 页内自建子状态机（配置/站点/分类/详情），**不新增顶层路由**；跨页大数据用内存 Store 或 ViewModel 持有 |
| 点播菜单 | 首页 Tab（配置在设置里） | `MobileDestination.kt:35` `VOD("vod", …, emptySet())` 恒显示，`bottomBar = HOME/LIVE/VOD`、`rail` 同；`VodScreen.kt` 为占位页 | 直接替换占位页，枚举不动 |
| 源导入 UI/VM | `ui/config/ConfigScreen.kt` + `vm/ConfigViewModel.kt` | 无（Core 的 `SourceImporter` 只处理 M3U/XTREAM/Stalker；`core/parser/TvBoxPlaylistParser.kt` 只解析 `lives` 段，产出 `M3uEntry`，不产出点播内容） | 新建 Mobile 版配置页 + `TvboxConfigViewModel`；`TvBoxPlaylistParser` 仅作 org.json/headers 写法参考，**不修改 Core** |
| 配置仓库 | `repository/ConfigRepository.kt`（导入/去重/缓存/jar 下载/首页源绑定，依赖 Room + DataStore + SAF） | 无 | **精简移植**：去掉 Room/DataStore/备份/py，持久化改 app 私有 JSON + 独立 DataStore 文件；保留 `importFromUrl` 全流程与 `sourceUrl` 透传 |
| 配置解析 | `config/parser/ConfigParser.kt`、`FindResult.kt`、`util/Aes.kt`、`util/LocalSource.kt`（含 `resolveAgainst`）、`Md5Util.kt`、`HtmlGuard.kt`、`TextUtil.kt` | 无 | 整包移植（纯 JVM + OkHttp + Android Uri/SAF） |
| 网络层 | `net/NetApi.kt`（GET/POST/字节/超时/DoH/代理）、`OkHttpProvider.kt`、`AddressFetcher.kt` | Core `core/network/HttpClient.kt:32` 只有 `get(url, ua, headers, block: InputStream)`，**无 POST/字节/自定义超时**；app 已在 compile classpath 用 `okhttp3.OkHttpClient`（`OwnTVMobileApp.kt:13`） | 移植 `NetApi` 为 app 侧独立网络层，用 `koinInject<OkHttpClient>()` 派生 `newBuilder()`；**不要改 Core 的 HttpClient** |
| 站点路由 | `repository/SourceRepository.kt` + `engine/Spider.kt` 的 `Engine` 接口、超时/冷却/缓存/聚合搜索 | 无 | 移植，去掉 `SourceStatRepository`/`SourceMarkPolicy`（用空实现）与 `jsWarmUp`/py 分支 |
| CMS 引擎 | `engine/cms/CmsSpider.kt`（含 `CmsEngine`）、`CmsParser.kt` | 无 | 移植，依赖换成 app 侧 `NetApi` |
| jar 引擎 | `engine/jar/JarSpider.kt`、`JarEngine.kt`、`JarResultParser.kt`、`engine/proxy/JarProxyHub.kt` + `JarProxyServer.kt`、`app/src/main/java/com/github/catvod/**` 11 个桩 | 无 | 整套移植；**桩必须保持 `com.github.catvod.*` 原包名**（jar 的 ABI 硬契约）；`DexClassLoader` 用平台 API |
| 播放地址解析 | `PlayNormalize.kt`、`CipherUrlDecoder.kt`、`Base64Compat.kt`、`ParseRepository.kt`、`ParseHealth.kt`、`PlayUrlCodec.kt`、`engine/sniff/SniffEngine.kt` + `SniffMatcher.kt` | 无 | 移植；`SniffEngine` 强依赖 `android.webkit.*`、主线程与 Activity 视图树，需单独适配 |
| 内容模型 | `config/model/TvBoxConfig.kt`（`TvBoxConfig`/`SiteConfig`/`ParseConfig`/`SniffRule`）、`engine/SpiderResult.kt`（`Movie`/`SpiderResult`/`PlayResult`/`ClassInfo`/`FilterDef`）、`PlayUrlCodec.PlayLine/Episode` | Core Room v53：`MovieEntity`/`SeriesEntity`/`EpisodeEntity`/`SourceEntity`（`sourceId/remoteId/categoryId/profileId` 强绑定）；app：`VodItem`/`VodCategory`/`VodPlayback` | **保持 TVbox 模型独立**，不映射进 Core 实体、不改 Room schema |
| 内容展示 | `HomeScreen`/`SearchScreen`/`DetailScreen` + `HomeViewModel`/`SearchViewModel`/`DetailViewModel`（Compose BOM 2024.12.01、Coil 2.7） | Stage 设计系统 `ui/stage/*`、`MobileIcons`、`stringResource`、Coil **3.6.2**、三语言 `strings_mobile.xml`、`collectAsStateWithLifecycle` + `koinViewModel()` | **不搬 UI**：按 Stage 组件重写配置页/首页/详情/搜索 4 个页面，复用业务层 VM 逻辑 |
| 播放编排 | `vm/PlayViewModel.kt`：`resolveAndPlay` → `startPlay` → `PlaybackUrlPolicy.wrap` → `PlayerManager.play(url, header, title, startMs, …)` | `VodTuner.playMovie/playEpisode/playDownload/playRecording` → `OwnTVPlayer.play/playEpisodes` → `navigate(PLAYER_ROUTE)` | **只搬解析编排**：新建 `TvboxPlayResolver`，输出 `ResolvedPlay(url, headers, ua, lines, title)`，调用 `VodTuner.playTvbox(...)`；`PlayerManager` 及其以下整层删除 |
| 历史/收藏/进度 | Room `HistoryEntity`/`FavoriteEntity` + `MediaRepository` | Core `progressDao`/`historyDao`/`favoriteDao`，键 `(profileId, mediaType, itemId)` | **不写 Core 表**：TVbox 独立存 app 私有 JSON/DataStore；`VodPlayback.itemId` 用 `TVBOX_ITEM_ID = -2L` 占位（§5.4） |
| 分类锁 / 儿童模式 | `CustomizationStore` 的锁定机制；TVbox 内容无 Core `categoryId` | Core `CustomizationStore.setCategoryLocked`/`observe`、`CategoryUnlockSession`、`SettingsRepository.customizePin`、`Pin.verifyCustomize`、`PinAttemptLimiter`、`AdultCategoryClassifier.isAdult` | **复用 Core 锁定数据与判定**：锁 key 写 `tvbox:<siteKey>:<typeId|typeName>` 到 MOVIE section 的 `lockedCats`；儿童模式用 `profile.isKids` + `isAdult` + TVbox 中文词表；判定/UI 在 app 侧（§5.5） |

### 3.2 目标端目标调用链

```
VodScreen（配置/站点/分类/列表/详情，Stage 组件）
  → TvboxConfigViewModel.importFromUrl(url)
  → TvboxConfigRepository.importFromUrl：去重 → NetApi.getText → FindResult.decode
        → HtmlGuard → LocalSourceResolver.fixContentPath → ConfigParser.parse
        → 写 files/tvbox/configs/{id}.json（含来源 URL）→ applyConfig
        → SpiderSpec.parse + LocalSourceResolver.resolveAgainst(url, sourceUrl) → 后台下载 jar
  → TvboxHomeViewModel：SourceRepository.homeContent / categoryContent / searchContent
        → engineFor(site) → CmsEngine / JarEngine → CmsSpider / JarSpider
        → JarResultParser.toResult → SpiderResult(list: Movie, classes, filters)
  → 站点/分类/列表过滤（每次进入或刷新）
        → TvboxCategoryPolicy.visibleSites/visibleCategories/movieAllowed（儿童模式：profile.isKids + isAdult + 中文词表）
        → TvboxCategoryLock.sealedKeys（分类锁：锁定行显示锁定态，点击弹 PIN）
  → 点击 → TvboxDetailViewModel：detailContent → PlayUrlCodec.parse → List<PlayLine>
        → 进入详情前：TvboxCategoryPolicy.blockedByKids(...) → 是则提示并返回
        → TvboxCategoryLock.seals(siteKey, typeId/typeName) → 是则弹 PIN（PinAttemptLimiter + Pin.verifyCustomize）
  → 选集 → TvboxPlayResolver.resolve(site, flag, episodeId, categoryKey)
        → SourceRepository.playerContent → PlayResult
        → ParseRepository / CipherUrlDecoder / SniffEngine（parse=1 时）
        → ResolvedPlay(finalUrl, headers, userAgent, allLines, title, startIndex, categoryKey)
→ VodTuner.playTvbox(resolved)
        → liveTuner.stop(); cast.release(this)
        → httpHeaders = StreamHeaders.encode(headers Map)
        → 单集：OwnTVPlayer.play(url, title, isLive=false, startPositionMs, userAgent, httpHeaders,
                                  contentKey = "tvbox:$siteKey:$vodId", vodEngineOverride = MPV_FIRST)
        → 多集：OwnTVPlayer.playEpisodes(items: List<PlaylistItem>, startIndex, …)  // 每集各自 headers
        → player.exitAudioOnly(); session.attach(engine); PlaybackService.start(context)
        → _playing.value = VodPlayback(..., TVBOX_ITEM_ID, ...)   // 只驱动 PlayerScreen / mini player
        → TvboxStore.saveProgress(...)                            // 进度/历史写 app 私有存储
        → TvboxSealedPlaybackGuard.attach(siteKey, categoryKey)   // 重锁/切档案时命中即 stop()
   → 返回 true 后 navController.navigate(PLAYER_ROUTE)  // MobileNavHost.kt:341，路由无参
   → PlayerScreen 读 vodTuner.playing / tuner.player 渲染 HUD（收藏/字幕入口按 TVBOX_ITEM_ID 隐藏）
```

**关键顺序**：必须先让 `VodTuner` 起播成功并写好 `playing`，再 `navigate(PLAYER_ROUTE)`；否则 `PlayerScreen` 会显示空态（`MobileShell` 的 `nothingPlaying` 逻辑）。

**关键隔离**：`playTvbox` **不得**调用 `saveProgress()` / `began()` / `recordHistory()`（它们直接写 Core 的 `PlaybackProgressEntity` / `WatchHistoryEntity`，且 `VodPlayback.itemId` 为 `Long`）。TVbox 的进度、历史、收藏一律走 `TvboxStore`，详见 §5.4。

**分类锁与儿童模式的双重校验**：列表层做展示过滤，详情/播放层做强制校验（不能只靠列表隐藏）；播放中由 `TvboxSealedPlaybackGuard` 监听重锁事件，详见 §5.5。

---

## 4. 需复用 / 调整 / 不改动 的类与资源

### 4.1 直接复用（Mobile 侧零改动；例外已在表中标注）

| 能力 | 位置 |
|---|---|
| 播放器门面 | `vendor/OwnTV_Core/player-core/src/main/java/tv/own/owntv/player/OwnTVPlayer.kt:2811 play(...)`、`:2879 playEpisodes(...)`、`:102-114 PlaylistItem/MediaMeta` |
| headers 编解码 | `vendor/OwnTV_Core/core/src/main/java/tv/own/owntv/core/network/StreamHeaders.kt:42 decode / :56 encode / :79 userAgentOf`（`Key: Value` 每行；自动过滤 RESERVED 头） |
| 网络客户端 | `koinInject<OkHttpClient>()`（Core `di/DataModule.kt:47-79` 单例，强制 HTTP/1.1、平台+Let's Encrypt 根证书、默认 UA）；简单 GET 可用 `core/network/HttpClient.kt:32 get(...)` / `:139 getText(...)`（都只支持 GET，无 POST/字节响应/自定义超时） |
| 图片 | `coil-compose` 3.6.2 的 `AsyncImage`（海报 URL 先 `TextUtil.absoluteUrl` 补全） |
| UI 组件 | `ui/stage/*`（`StageRow`/`StagePoster`/`StageMenuItem`/`stageText`/`LocalStagePalette`）、`ui/components/MobileIcon.kt`（`object MobileIcons`）、`stringResource` |
| UI 状态范式 | `koinViewModel()` + `StateFlow` + `collectAsStateWithLifecycle()`；`VodPlayback`/`VodItem` 作为 State 命名参考 |
| 播放策略件 | `DataSaverGate`、`CastController`、`ExternalPlayerLauncher`、`PlaybackService`、`PlaybackSession`、`session.attach(engine)`、`VodTuner` 的 5s 进度定时器（`:652`）；`saveProgress()`（`:531`）需按 §5.4 增加 `playingExternal` 分支，属"小改"而非零改动 |
| 配置解析参考 | `vendor/OwnTV_Core/core/src/main/java/tv/own/owntv/core/parser/TvBoxPlaylistParser.kt`（只解析 `lives`，参考其 org.json + headers 归并写法） |
| 明文流量 | `app/src/main/AndroidManifest.xml:61 usesCleartextTraffic="true"` 已放行 http 源 |
| HLS | player-core 的 `media3-exoplayer-hls` + libmpv/FFmpeg 已支持 m3u8，无需新增 |

### 4.2 需要调整 / 新增的适配层

| 适配点 | 说明 |
|---|---|
| `VodTuner.playTvbox(...)`（新增方法） | 照 `VodTuner.kt:360-391 playDownload` / `:409-428 playRecording` 骨架扩展：补 `userAgent`、`httpHeaders = StreamHeaders.encode(map)`、`contentKey = "tvbox:$siteKey:$vodId"`、`vodEngineOverride = EnginePreference.MPV_FIRST`；多集走 `playEpisodes`。**只照搬 `liveTuner.stop()` / `cast.release(this)` / `player.exitAudioOnly()` / `session.attach(engine)` / `PlaybackService.start(context)`，不要调用 `saveProgress()` / `began()` / `recordHistory()`**——这三者写 Core 表；`_playing` 传 `TVBOX_ITEM_ID`（见 §5.4） |
| `MobileNavHost.kt:153` | `MobileDestination.VOD -> VodScreen(onPlay = { navController.navigate(PLAYER_ROUTE) }, …)`，参考 `:125`/`:151`/`:176` 的回调写法 |
| `VodScreen.kt:22` | 占位页整体替换为 TVbox 点播容器（内部子状态：配置 / 站点 / 分类 / 搜索 / 详情 / 选集） |
| `OwnTVMobileApp.kt:160-164` | `modules(...)` 追加 `tvboxModule` |
| `di/TvboxModule.kt`（新增） | `single { TvboxNetApi(...) }`、`single { TvboxConfigRepository(...) }`、`single { SourceRepository(...) }`、`single { TvboxPlayResolver(...) }`、`single { TvboxCategoryPolicy(...) }`、`single { TvboxCategoryLock(...) }`、`single { TvboxSealedPlaybackGuard(...) }`、`viewModelOf(::TvboxConfigViewModel)` 等 |
| `TvboxCategoryPolicy.kt`（新增） | 儿童模式纯函数：`profileDao.getById(pid)?.isKids` + `AdultCategoryClassifier.isAdult(name)` + TVbox 中文成人词表 + `SiteConfig.ext` 的 `adult/nsfw` 标记；提供 `visibleSites / visibleCategories / movieAllowed / blockedByKids`（§5.5） |
| `TvboxCategoryLock.kt`（新增） | 分类锁：key `tvbox:<siteKey>:<typeId|typeName>`；读写 Core `CustomizationStore.observe/setCategoryLocked`（MOVIE section）、`CategoryUnlockSession`、`SettingsRepository.customizePin`、`Pin.verifyCustomize`、`PinAttemptLimiter`；提供 `seals / visibleCategories / unlock / toggleLock`（§5.5） |
| `TvboxSealedPlaybackGuard.kt`（新增） | 监听 `CategoryUnlockSession.epoch()` 与 `settings.activeProfileId`；TVbox 播放中命中 sealed 即 `vodTuner.stop()`；在 `MobileShell` 或 `MainActivity` 注册（§5.5） |
| `gradle/libs.versions.toml` + `app/build.gradle.kts` | 新增必要依赖（§5.2） |
| `app/proguard-rules.pro` | 补反射 keep：`com.github.catvod.**` 桩、jar 反射加载的 Spider 类；`JsModuleCache` 相关仅在 P5 引入 JS 后再补；否则 release R8 会破坏 ABI |
| `res/values/strings_mobile.xml` + `values-zh-rCN/`、`values-zh-rTW/` 同名文件 | 三语言同步新增文案（原 `mobile_vod_placeholder_*` 可保留或替换），满足 `MobileStringsParityTest` |
| `app/src/main/java/com/github/catvod/**` | 11 个桩文件**保持原包名整包复制**，不可重命名（jar 内 `com.github.catvod.crawler.Spider` 的编译期契约） |

### 4.3 明确不改动

`vendor/OwnTV_Core/**`（含 `OwnTVPlayer.kt`、`HttpClient.kt`、`OwnTVDatabase`）、Core Room schema v53 与 `ALL_MIGRATIONS`、`MobileDestination.kt`、`ui/player/**`（唯一例外：`PlayerScreen.kt` 按 `TVBOX_ITEM_ID` 隐藏收藏与字幕入口，约 10 行）、Core 的 i18n baseline、`settings.gradle.kts` 的 includeBuild/仓库配置。

---

## 5. 数据模型与依赖差异

### 5.1 数据模型差异

| 维度 | VodPlayer | OwnTV_Mobile | 处理 |
|---|---|---|---|
| 配置 | `TvBoxConfig`（spider/sites/parses/flags/rules/ads/proxy/doh/lives）、`SiteConfig`（key/name/type/api/ext/header/jar/UA/categories/playUrl…） | `SourceEntity`（M3U/XTREAM/STALKER，url + UA + referer） | 独立，不复用 `SourceEntity` |
| 内容 | `SpiderResult`/`Movie`/`ClassInfo`/`FilterDef`、`CmsParser.CmsPage/CmsMovie`、`com.github.catvod.bean.Result` 桩 bean | `MovieEntity`/`SeriesEntity`/`EpisodeEntity`（Room，绑定 sourceId/categoryId/profileId） | 独立保留 TVbox 模型；**不做 ORM 映射** |
| 线路/分集 | `PlayUrlCodec.PlayLine/Episode`（`$$$` 线路、`#` 集、`$` 名称与地址） | `PlaylistItem(url, meta, resolveUrl, httpHeaders, drmConfig, manifestType)` | 详情阶段用 `PlayLine`，起播阶段转成 `List<PlaylistItem>`；headers 逐项用 `StreamHeaders.encode` |
| 播放结果 | `PlayResult(parse, playUrl, url, header: JsonObject?, flag, urls, subt, danmaku…)` | `OwnTVPlayer.play(...)` 参数（`httpHeaders: String?`、`userAgent`、`manifestType`…） | 新增 `ResolvedPlay(url, headers, userAgent, allLines, title, startIndex, categoryKey)` 中间模型，`JsonObject` header → `Map<String,String>` → `StreamHeaders.encode` |
| 历史/收藏/进度 | Room `HistoryEntity`/`FavoriteEntity`（siteKey/vodId/episodeIndex/positionMs…） | Core `WatchHistoryEntity`/`PlaybackProgressEntity`（profileId + mediaType + `itemId: Long`） | **独立存 app 私有 JSON/DataStore，不写 Core 表**；`VodPlayback.itemId` 是 `Long`，TVbox 用 `TVBOX_ITEM_ID = -2L` 占位。详见 §5.4 |
| 偏好 | `SettingsStore`（DataStore Preferences） | Core `SettingsRepository`（DataStore） | 新建独立 DataStore 文件（如 `tvbox_prefs`），不往 `owntv_settings` 加 key |
| 分类锁 / 儿童模式 | `CustomizationStore` 只认 `categories.id`；TVbox 内容无 Core 分类行 | Core `SectionCustomizations.lockedCategories`（DataStore JSON）、`CategoryUnlockSession`、`SettingsRepository.customizePin`、`AdultCategoryClassifier` | **不新增表**：锁 key 写 Core `lockedCats`（`tvbox:` 前缀，MOVIE section）；儿童判定复用 `isKids` + `isAdult` 并补中文词表（§5.5） |

**为什么不进 Core Room**：`vendor/OwnTV_Core/core/src/main/java/tv/own/owntv/core/database/OwnTVDatabase.kt:123` 是 `version = 53`，`core/schemas/.../` 下 50 个 schema JSON，`:1447` 是 `ALL_MIGRATIONS`，并与备份/本地同步协议联动；`vendor/OwnTV_Core` 是 git subtree 快照（`docs/Core源码集成方案.md:134`「不做日常编辑」），改它必须走 Core `mobile` → push fork → `git subtree pull` → 重建 i18n baseline 全流程。成本高、收益为零。

### 5.2 工具链与依赖差异

| 项 | VodPlayer（`main`） | Mobile（`mobile-mini`） | 迁移处理 |
|---|---|---|---|
| AGP / Kotlin / Gradle | 8.13.1 / 2.1.0 / 8.13 | **9.4.0 / 2.4.20 / 9.7.1**（AGP 9 内置 Kotlin；Gradle wrapper 见 `gradle/wrapper/gradle-wrapper.properties`） | 只复制 .kt 源码，构建脚本不搬 |
| Compose BOM | 2024.12.01 | **2026.09.00** | UI 全部用 Stage 组件重写，不搬 Composable |
| minSdk / compileSdk / targetSdk | 24 / 35 / 35 | 26 / 37 / 36 | 无风险（升 minSdk）；Java 都是 17 |
| 编译 JDK | 17 | **25**（`gradle-daemon-jvm.properties` toolchainVersion=25，`D:/jdk/jdk-25`） | 本机必须用 JDK 25，否则 toolchain 拒绝 |
| OkHttp | **4.12.0** | **5.5.0**（Core/player-core `implementation`，app 已可通过传递依赖使用） | 需逐项核对 API：`Headers.of(Map)`、`RequestBody.create`、`Dns`/`CookieJar` 等；用 `koinInject<OkHttpClient>().newBuilder()` 派生，避免改 Core 单例 |
| JSON | `kotlinx-serialization-json 1.7.3`（`JsonObject/JsonElement`，`TvBoxConfig.kt`/`SpiderResult.kt` 有 `@Serializable`）+ Gson 2.11.0（`JarResultParser` 反射） | 运行时只有平台 `org.json`；Core 的 Gson/org.json 仅 `testImplementation` | 见 §5.3 |
| HTML/XML | `jsoup 1.18.1` + `javax.xml.parsers`/`org.w3c.dom` | 无 jsoup；XML 用 JVM 标准库即可 | 新增 `org.jsoup:jsoup`，或把 jsoup 用法改写为正则/标准 DOM（CMS XML 用标准 DOM 已足够） |
| 图片 | Coil **2.7.0** `AsyncImage` | Coil **3.6.2** | 用 Coil 3 的 `AsyncImage` 重写，包名 `io.coil-kt.coil3` |
| Room / DataStore | Room 2.6.1 + KSP；DataStore 1.1.1 | Room 2.8.5（Core，v53） | **不迁 Room**；偏好可用 DataStore（Core 已依赖） |
| Media3 | 1.5.1 ExoPlayer/HLS/RTSP/UI + IJK/GSY | player-core 内 1.11.1（ExoPlayer/HLS/DASH/UI），app 仅 `media3-ui` | **整体不迁移、不新增** |
| WebView / DexClassLoader | 平台 API，`engine/sniff/**`、`engine/jar/**` | 全仓无相关代码 | 平台能力，无需依赖；需补 Manifest（如用到外部存储）/ R8 keep |
| QuickJS | `wang.harlon.quickjs:wrapper-android 2.1.0`（JS/drpy 源） | **已引入 2.1.0**（`wrapper-java` 仅测试） | P5 已落地；`.so` 为 4KB 对齐，`JsEngine` 构造时探测 `UnsatisfiedLinkError` 并降级，详见 `docs/TVbox-JS源迁移方案.md` §0.3 |
| DI / 日志 | 手写 `ServiceLocator` + Timber 5.0.1 | Koin 4.2.2 + `android.util.Log` | 装配改 Koin；Timber 换成 `Log` 或写 20 行小包装（避免新增依赖） |
| 构建开关 | `WITH_PY`、Chaquopy、`src/py|nopy` sourceSets、KSP | 无 | 全部去掉 |
| i18n 门禁 | 无 | **`preBuild` 挂 `verifyI18nLiterals`**（`app/build.gradle.kts:283` 注册任务、`:302` 依赖），`hardcoded_baseline.txt` 仅 5 行、绝对 ratchet | 见 §9，最大工程约束 |

### 5.3 序列化与网络层的落地决策（二选一，建议 A）

- **方案 A（推荐，改动最小）**：新增 `org.jetbrains.kotlinx:kotlinx-serialization-json`（选与 Kotlin 2.4.20 兼容的 1.9.x+）作为**纯运行时**依赖，**移除** `TvBoxConfig.kt` / `SpiderResult.kt` 等文件里的 `@Serializable` 注解，并**同步改写 `ConfigParser.kt:172` 的 `json.decodeFromJsonElement(ParseConfig.serializer(), el)` 为手写 `jsonObject` 字段读取**——这是白名单内唯一依赖编译器插件的调用点（`:434` 的 `JsonElement.serializer()` 由运行时提供，不需要类注解）。其余解析本来就是 `Json.parseToJsonElement` 手写；`PlayResult.header: JsonObject?` 改成 `Map<String, String>` 便于直接对接 `StreamHeaders.encode`。这样不引入 serialization compiler plugin，避免与 AGP 9 内置 Kotlin 的插件版本冲突。
- **方案 B**：保留 `@Serializable`，追加 `org.jetbrains.kotlin.plugin.serialization` 2.4.20 与对应运行时。改动更少的代码但多一个编译插件，且需验证 AGP 9 + Kotlin 2.4.20 组合。
- Gson：仅 `JarResultParser.beanToMap` 反射桩 bean 用到；建议保留并新增 `com.google.code.gson:gson`（小体积），**不要**改成手写反射清单，避免破坏 jar 返回值归一化。
- 网络：Core `HttpClient.get` 只支持 GET + UA + 简单 headers + 流式 `InputStream`（另有 `getText`），**不支持 POST、字节响应、自定义超时、多候选重试**；CMS 补丁、jar 下载、解析器请求都需要这些能力，因此必须移植 `NetApi`/`OkHttpProvider`/`AddressFetcher` 为 app 侧独立网络层。

### 5.4 进度 / 历史 / 收藏的落点（v1.1 新增，必读）

> **已作废（2026-11-11）**：`TvboxStore` 与私有 JSON 已随 `点播内容接入Core方案.md` P5 删除。
> 进度/历史/收藏现写 Core 表（按档案隔离；进度带 `extKey` 记录线路/集数身份），收藏入口对已索引条目开放。
> `TVBOX_ITEM_ID = -2L` 仅保留为索引失败时的播放哨兵：可播放、不写进度、收藏入口隐藏。本节保留作历史记录。

Mobile 现状（`VodTuner.kt`）决定了 TVbox 不能直接复用播放收尾逻辑：

- `VodPlayback.itemId` 是 **`Long`**（`:66`）；`saveProgress()`（`:531`）把它写进 Core `PlaybackProgressEntity`；`began()`（`:557`）与 `recordHistory()` 写 Core `WatchHistoryEntity`。
- `playRecording()` 已示范"临时条目"做法：`RECORDING_ITEM_ID = -1L`（`:61`）。
- `PlayerScreen.kt:267` 用 `film.seriesId ?: film.itemId` 作为收藏键——TVbox 若沿用，会把负数/占位 id 写进 Core 收藏表。

因此 `playTvbox` 只复用播放器与 housekeeping，不复用数据写入：

| 数据 | 落点 | 键 | 写入时机 |
|---|---|---|---|
| 进度 | `TvboxStore`（`files/tvbox/progress.json` 或独立 DataStore） | `siteKey + vodId + episodeFlag + episodeId` | `playTvbox` 内 5s 定时 + 暂停/退出时 |
| 历史 | 同上（`history.json`） | 同上 | 起播成功后一次 |
| 收藏 | 同上（`favorites.json`） | 同上 | VOD 页内收藏按钮，**不接 Core favoriteDao** |
| `_playing` | `VodPlayback(mediaType = MOVIE/SERIES, itemId = TVBOX_ITEM_ID)` | 常量 `TVBOX_ITEM_ID = -2L`（参照 `RECORDING_ITEM_ID = -1L`） | 仅用于 PlayerScreen / mini player 渲染 |

实现约束：

1. `VodTuner` 增加 `@Volatile private var playingExternal = false`；`playTvbox` 置 `true`，`playMovie/playEpisode/playDownload/playRecording` 置 `false`。`saveProgress()` 在 `playingExternal` 分支只写 `TvboxStore` 后 `return`，不碰 `progressDao`。
2. TVbox 模式下 `playingProfileId = -1L`，使 `saveProgress()` 现有的 `currentProfileId() != playingProfileId` 守卫直接短路（双保险）。
3. `PlayerScreen` 收藏入口：`itemId == TVBOX_ITEM_ID` 时**隐藏**（拍板口径），不改接 Core `favoriteDao`。
4. `TvboxStore` 首版 schema 只需 `siteKey / vodId / episodeIndex / episodeId / positionMs / durationMs / updatedAt`，不与 VodPlayer 的 Room 表兼容。

### 5.5 分类锁与儿童模式接入（v1.2 拍板，必读）

#### 5.5.1 为什么不能直接调用 `sealsCategory`

Core 的分类锁判定链要求真实 `categories.id`：

- `CustomizationStore.sealsCategory(profileId, categoryId, anySession)` → `CategoryLockView.sealsCategory(...)`；
- `CategoryLockView` 先用 `categoryDao.getById(categoryId)` 反查 `CategoryEntity`，再按 `"<sourceId>:<remoteId ?: name>"` 匹配 `lockedCats`（`CategoryLockView.kt:44-57`）；
- `categories` 表对 `sources.id` 有外键（`ContentEntities.kt:19-24`），`SourceType` 只有 M3U/XTREAM/LOCAL_BACKUP/STALKER，TVbox 站点不在其中；
- 往 `categories` 插虚拟行会被同步剪除（`SyncSupport.pruneCategories`），且没有可用的 `TVBOX` 类型；改这些等于动 Core 源码/schema，违反 §4.3。

所以 TVbox 接入的是 **Core 的「锁数据 + PIN + 解锁会话 + 儿童判定」**，不是 `sealsCategory` 本身。

#### 5.5.2 复用清单（不改 Core）

| Core 能力 | API | TVbox 用途 |
|---|---|---|
| 锁定集合 | `CustomizationStore.observe(pid, MediaType.MOVIE)` → `lockedCategories` | 读锁 |
| 上锁/解锁 | `CustomizationStore.setCategoryLocked(pid, MOVIE, key, locked)` | 写锁（key 前缀 `tvbox:`） |
| 解锁会话 | `CategoryUnlockSession.isUnlocked / unlock / epoch / relockAll` | 本次会话放行 |
| Customize PIN | `SettingsRepository.customizePin(pid)`、`Pin.verifyCustomize` | 校验 PIN |
| PIN 限速 | `PinAttemptLimiter.attempt { }` | 防爆破 |
| 儿童档案 | `ProfileDao.getById(pid)?.isKids` | 儿童模式开关 |
| 成人判定 | `AdultCategoryClassifier.isAdult(name)` | 分类名判定 |
| 移除 PIN | `CustomizationStore.clearCategoryLocks(pid)` | 会同时清掉 TVbox 锁（预期） |

`CategoryUnlockSession` 只暴露同步 `epoch()`，没有公开的 Flow；重锁检测由 §5.5.7 的生命周期钩子完成。

#### 5.5.3 key 规范与写法

```
tvboxKey(siteKey, typeId, typeName) =
    "tvbox:${siteKey}:${typeId?.takeIf { it.isNotBlank() } ?: typeName}"
```

- 与 Core 的 `"<sourceId>:<remoteId ?: name>"` 同构，`tvbox` 前缀保证不与 Core 数字 sourceId 冲突。
- `CategoryLockView.withSealedIds` 只解析 `substringBefore(':').toLongOrNull()`，`tvbox` → null，**不会进入 Core 的 `sealedCategoryIds`**，不会误伤 Core 分类（`CategoryLockView.kt:78-84`）。
- 备份重映射对非数字前缀原样保留（`BackupManager.kt:1849-1855`），TVbox 锁可随 Core 备份导出/恢复。
- 分类改名或换 `typeId` 会让 key 漂移（与 Core「锁跟随 key」一致），需重新上锁。
- 统一写入 `MediaType.MOVIE` section（TVbox 不区分电影/剧集）；后续若按内容类型分流，只改 section 参数，不改 key 格式。

#### 5.5.4 判定接口

```kotlin
// TvboxCategoryLock.kt（app 侧）
suspend fun seals(profileId: Long, siteKey: String, typeId: String?, typeName: String, anySession: Boolean = false): Boolean
suspend fun sealedKeys(profileId: Long, siteKey: String, categories: List<ClassInfo>): Set<String>  // 锁定 key 集合，UI 据此显示锁定态
suspend fun unlock(profileId: Long, siteKey: String, typeId: String?, typeName: String, pin: String): PinAttempt
suspend fun toggleLock(profileId: Long, siteKey: String, typeId: String?, typeName: String, locked: Boolean)
fun keyOf(siteKey: String, typeId: String?, typeName: String): String
```

`sealedKeys` **不隐藏分类行**：分类列表照常显示，命中锁的行加锁图标；点击弹 PIN（`seals` 为 true 时）。`seals` 用于详情/播放的强制校验，不能只靠 UI。

`seals` 严格照 Core `CategoryLockView` 的顺序：

1. `customizePin(pid).first() == null` → `false`（没有 PIN 不算锁）；
2. `observe(pid, MOVIE).first().lockedCategories` 不含 key → `false`；
3. `anySession == false` 且 `unlocks.isUnlocked(pid, MOVIE, key)` → `false`；
4. 否则 `true`。

#### 5.5.5 儿童模式判定

```kotlin
// TvboxCategoryPolicy.kt（app 侧，纯函数）
fun visibleSites(profile: ProfileEntity?, sites: List<SiteConfig>): List<SiteConfig>
fun visibleCategories(profile: ProfileEntity?, site: SiteConfig, classes: List<ClassInfo>): List<ClassInfo>
fun movieAllowed(profile: ProfileEntity?, site: SiteConfig, movie: Movie): Boolean
fun blockedByKids(profile: ProfileEntity?, site: SiteConfig, typeName: String?): Boolean
```

`blockedByKids` 是详情/播放层复用的单点判定；`visibleSites`/`visibleCategories`/`movieAllowed` 都调用它。

判定顺序：

1. `profile?.isKids != true` → 全放行；
2. 站点 `ext` 含 `"adult":true` / `"nsfw":true` → 整个站点隐藏（聚合搜索也不参与）；
3. 分类名命中 `AdultCategoryClassifier.isAdult(name)` → 隐藏；
4. 分类名命中 TVbox 中文词表（`伦理片/成人/福利/午夜/写真/禁片/里番/无码/情色/三级/激情/偷拍/探花/18禁/限制级` 等，app 内常量）→ 隐藏；
5. 列表行的 `Movie.typeName` 命中上述任一规则 → 该行隐藏（防止「分类安全但条目来自成人分类」）；
6. 未命中 → 放行（与 Core 一致，不 fail-closed）。

可选严格模式：`TvboxPrefs.kidsStrict`（默认 `false`）；开启后只显示用户显式标记为安全的站点/分类，用于词表漏判时兜底。

**与 Core 一致性**：Core 的 `AdultCategoryClassifier` 只按分类名判定，中文仅覆盖 `成人/成年人`；TVbox 中文词表是同一判定的补充。两边都不按标题判定，避免「Sex and the City」类误杀。

#### 5.5.6 校验点（三处，缺一不可）

| 层 | 位置 | 行为 |
|---|---|---|
| 列表/分类 | `TvboxHomeViewModel`、`TvboxDetailViewModel` | 儿童过滤；锁定分类显示锁定态，点击弹 PIN |
| 详情 | `TvboxDetailViewModel.open` | 儿童命中直接拒绝；锁命中先弹 PIN，解锁后打开 |
| 播放 | `TvboxPlayResolver.resolve` / `VodTuner.playTvbox` 调用前 | 再次 `seals + blockedByKids`，拒绝则返回错误提示 |

搜索结果按 `Movie.donorSiteKey` 归到站点，先过 `movieAllowed`；`typeName` 为空的条目按站点级 `adult` 标记处理。

#### 5.5.7 上锁 UI 与 PIN

- 入口：VOD 页分类行的 ⋯ 菜单（`StageMenuItem`）→「上锁 / 解锁」；详情页 ⋯ 菜单同。
- 前提：已有 Customize PIN（`customizePin(pid) != null`）。没有则提示「请先在 设置 → Customize 设置 PIN」，**不在 TVbox 内单独设置 PIN**（避免两处 PIN 状态不一致）。
- PIN 弹窗复用 `PinDoorSheet`（`ui/categorylock/CategoryLockPrompts.kt`）与 `PinAttemptLimiter`；校验用 `Pin.verifyCustomize`。
- 解锁顺序（防绕过）：先取 `since = unlocks.epoch()` → 校验 PIN → `unlocks.unlock(pid, MOVIE, key, since)`；期间若发生 `relockAll`（离开 App/切档案），`unlock` 返回 `false`，不重新放行。
- 解锁只对本次会话有效（`CategoryUnlockSession.unlock`），离开 App/切档案后重新锁（与 Core 完全一致）。
- Core 设置里移除 PIN 会调用 `clearCategoryLocks`，同时清掉 TVbox 锁（预期）。

#### 5.5.8 重锁与播放中停播

`TvboxSealedPlaybackGuard`（app 侧）：

1. 在 `MobileShell` 注册：`LifecycleEventEffect(Lifecycle.Event.ON_STOP)` → `guard.leftApp()`；`settings.activeProfileId` 变化 → `guard.profileChanged(pid)`。
2. `leftApp()` / `profileChanged()` 读取 `VodTuner` 当前 TVbox 播放上下文（`playingExternal == true` 时的 `siteKey/categoryKey`）。
3. 调 `TvboxCategoryLock.seals(pid, ..., anySession = false)`；`true` → `vodTuner.stop()`。
4. 与 Core 的 `CategoryRelock` 并行、互不替代：Core guard 只停 Live/Core VOD，TVbox guard 只停 TVbox 播放。
5. 读锁异常 → fail-closed 停播（与 Core `sealsNow` 一致）。

#### 5.5.9 副作用与边界

1. **Core `anySealed` 会看到 TVbox 锁**：`CategoryLockView.anySealed` 遍历 LIVE/MOVIE/SERIES 的 `lockedCategories`，TVbox key 在 MOVIE section 会让 `anySealed` 为 true。影响：Core 的 `ItemCategory.Unresolved`（下载/录制文件分类已不存在）会按 sealed 拒绝播放。方向更保守，不是安全漏洞；若不可接受，只能走 5.5.10 的 Core 改造。
2. **Core 的 Customize 页不显示 TVbox 分类**（它从 `categories` 表构建），TVbox 锁只在 VOD 页菜单里管理；移除 PIN 可一次清空。
3. **`observeHasLocks` 会因 TVbox 锁返回 true**：Core 离开 App 时不再走 `nothingToSeal` 短路，多一次 DB 读，无功能影响。
4. **不做下载/录制锁**：TVbox 首版不做下载，无需接 `LockedNotificationTitles`。
5. **收藏/进度**：仍按 §5.4 独立存储；分类锁只影响可见性与播放，不改变存储位置。

#### 5.5.10 升级路径（如果后续要求 Core 原生支持 TVbox 分类）

需要 Core v54：`SourceType` 增加 `TVBOX`（或 `categories` 增加 `origin` 列）、`pruneCategories`/`clearSourceContent` 排除外部来源、`CategoryLockView` 支持外部 categoryId、`SealedPlaybackGuard` 的 `PlayingCategories` 支持外部 key。

这不属于本次范围；本方案先以**不改 Core** 的方式接入同一套锁数据与儿童判定。

---

## 6. 点播菜单接入位置

| # | 文件（相对 `OwnTV_Mobile/`） | 行 | 动作 |
|---|---|---|---|
| 1 | `app/src/main/java/tv/own/owntv/mobile/ui/nav/MobileDestination.kt` | 35 / 50 / 57 | `VOD("vod", …, emptySet())` 已在底栏与 rail 且恒显示 → **不要改**（`sections` 空 = 不受 `NavVisibility` 过滤） |
| 2 | `app/src/main/java/tv/own/owntv/mobile/ui/nav/MobileNavHost.kt` | **153** | `MobileDestination.VOD -> VodScreen()` → 传 `onPlay = { navController.navigate(PLAYER_ROUTE) }` 等回调；`when` 是穷举，不要加新 destination |
| 3 | `app/src/main/java/tv/own/owntv/mobile/ui/screens/vod/VodScreen.kt` | **22** | 替换占位 `fun VodScreen(...)`；同包新建 `VodViewModel`（或 `Tvbox*ViewModel` 系列）；分类行 ⋯ 菜单接「上锁/解锁」，列表/搜索接儿童模式过滤（§5.5） |
| 4 | `app/src/main/java/tv/own/owntv/mobile/di/` | — | 新增 `TvboxModule.kt`，`viewModelOf(::…)` 注册；参照 `di/LibraryModule.kt:41-42` |
| 5 | `app/src/main/java/tv/own/owntv/mobile/OwnTVMobileApp.kt` | **160-164** | `modules(...)` 追加 `tvboxModule` |
| 6 | `app/src/main/java/tv/own/owntv/mobile/ui/screens/library/VodTuner.kt` | 360-391 / 409-428 / 213-269 / 272-341 | 新增 `playTvbox(...)`，参考 `playDownload`/`playRecording`/`playMovie`/`playEpisode` 骨架；**不调用 `saveProgress`/`began`/`recordHistory`**（见 §5.4） |
| 7 | `app/src/main/res/values/strings_mobile.xml` 及 `values-zh-rCN/`、`values-zh-rTW/` 同名文件 | 45-46（values）/ 36-37（zh） | 三语言新增 TVbox 文案（导入、站点、分类、错误态等） |
| 8 | 可选：`ui/screens/MoreScreen.kt` / `MoreLeaf.kt` / `SettingsScreen.kt:188-224` | — | 若要在 More 或设置里也放「配置源」入口，需同步 `LEAF_ROWS` 搜索索引；**首版建议只放 VOD 页内**，减少改动面 |
| 9 | `app/src/main/java/tv/own/owntv/mobile/ui/player/PlayerScreen.kt` | 267 附近 | `film.itemId == TVBOX_ITEM_ID` 时隐藏收藏与字幕入口（拍板口径，约 10 行）；不改其他播放逻辑 |
| 10 | `app/src/main/java/tv/own/owntv/mobile/ui/shell/MobileShell.kt` | — | 注册 `TvboxSealedPlaybackGuard`：`ON_STOP` → `leftApp()`；`activeProfileId` 变化 → `profileChanged()`（§5.5.8） |

**不建议**在首页加 TVbox 行：那要改 Core `HomeRow` 枚举 + `HomeScreen.kt:278-375` 的 `when` + `SettingsHomePage.kt:180-220` + `SettingsScreen LEAF_ROWS` + 26→3 语言文案 + i18n baseline，且触发 §4.3 的 subtree 流程。

---

## 7. 分阶段实施步骤

> 每阶段独立编译验证；每阶段结束提交一次（项目说明第 4 条的提交流程）；源码只在 `mobile-mini`。

**P0 — 基线冻结与备份（不做功能）**
1. 复核三仓分支与 HEAD（§1.2），记录 `git status`；保留 Mobile 现有未提交改动，先把 `docs/backup/` 纳入版本控制或另存一份。
2. 用 `git -C <vod> show main:<path>` 从 **`main @4c8d289`** 导出 §12「源侧白名单」到临时目录，逐一核对不含 py / 直播 / 播放器 / TV UI。
3. 按 §8 备份所有将被修改的原文件。

**P1 — 依赖与空壳骨架**
4. 在 `gradle/libs.versions.toml` + `app/build.gradle.kts` 加 §5.2/5.3 决定的依赖；`app/proguard-rules.pro` 补 keep。
5. 新建包 `tv.own.owntv.mobile.tvbox.**` 与 `com.github.catvod.**` 桩目录；`di/TvboxModule.kt` 挂进 `OwnTVMobileApp`。
6. 只把 `VodScreen.kt` 换成空壳（导航能进、无业务逻辑、无新增字面量），跑 `assembleStandardDebug` 让 `verifyI18nLiterals` 先过一遍，确认门禁基线没被破坏。

**P2 — 源导入 + 配置解析闭环**
7. 移植 `config/model`、`config/parser`、`util`（含 `LocalSource.resolveAgainst`）、`net`、`Spider.kt`/`SpiderResult.kt`/`PlayUrlCodec.kt`。
8. 精简 `ConfigRepository`：去 Room/DataStore 备份/py/首页源绑定，改 app 私有 JSON（`files/tvbox/configs/{id}.json`，写入的是已改写的 spider 绝对地址）+ 独立 DataStore；**保留** `importFromUrl` 的「去重 → 抓取 → 解密 → HTML 门控 → fixContentPath → ConfigParser.parse → 保存来源 URL → 后台 jar 预下载」全顺序，以及 `resolveAgainst` 的两处调用点（`ConfigRepository.kt` 在 main 的 `:1317`、`:1495`）。
9. 实现配置页（URL 导入 + SAF 本地文件 + 站点列表 + 可用性/不支持原因），沿用 Stage 组件与三语言 stringResource。
10. 移植 TVbox 相关单测（ConfigParser / FindResult / LocalSource.resolveAgainst / PlayUrlCodec / JarResultParser / CipherUrlDecoder / SniffMatcher），排除 py 测试。

**P3 — 源解析引擎 + 内容展示**
11. 移植 `SourceRepository`、`CmsSpider/CmsParser`、`JarSpider/JarEngine/JarResultParser`、`JarProxyHub/JarProxyServer`、11 个 catvod 桩；`engines` 只注册 `CmsEngine → JarEngine`（P5 再加 JsEngine）。
12. 实现首页/分类/筛选/搜索/详情 4 个页面与 VM（业务层可复用 VodPlayer 的编排思路，UI 用 Stage + Coil 3 重写）；跨页大数据用 ViewModel 持有的内存 Store（`PlayLine` 不可 Parcelable）。
13. 新建 `TvboxCategoryPolicy`（§5.5.5）：站点 `ext` 的 `adult/nsfw` 标记、分类名 `isAdult` + 中文词表、`Movie.typeName` 行过滤；接入站点列表/分类列表/聚合搜索/详情；补齐三语言文案与单测。
14. 用本地夹具 HTTP 服务（参考 VodPlayer `device_ui/` 或 `tools/` 里的 stub）验证：CMS type 0 JSON / type 1 XML / type 4、相对 spider 地址、私有 jar、jar 加固壳（arm-only 只能在 arm64 真机）。

**P4 — 播放地址解析 + 播放器对接（核心验收阶段）**
15. 移植 `PlayNormalize`/`CipherUrlDecoder`/`Base64Compat`/`ParseRepository`/`ParseHealth`/`SniffEngine`/`SniffMatcher`；`SniffEngine` 的 Activity attach / 主线程约束按 Mobile 的 Activity 生命周期适配。
16. 新建 `TvboxPlayResolver`：只做「`playerContent` → `ResolvedPlay`」，**不引 `PlayerManager`、不引 Media3**；解析前调 `TvboxCategoryLock.seals` + `TvboxCategoryPolicy.blockedByKids`，拒绝时给换线/换源/解锁提示。
17. `VodTuner.playTvbox(...)`：单集 `OwnTVPlayer.play(...)`，多集 `playEpisodes(List<PlaylistItem>, startIndex)`（**逐集 headers**）；`httpHeaders = StreamHeaders.encode(map)`；`contentKey = "tvbox:$siteKey:$vodId"`；`vodEngineOverride = MPV_FIRST`；`_playing` 用 `TVBOX_ITEM_ID`。**不调用 `began()`/`saveProgress()`/`recordHistory()`**，进度/历史改由 `TvboxStore` 承担（§5.4）；给 `VodTuner` 增加 `playingExternal` 与当前 `siteKey/categoryKey` 字段。
18. 新建 `TvboxCategoryLock` + VOD 页「上锁/解锁」菜单 + `PinDoorSheet`/`PinAttemptLimiter` 接入；锁 key 写 Core `lockedCats`（§5.5.3）。
19. 新建 `TvboxSealedPlaybackGuard`，在 `MobileShell` 注册；验证离开 App/切档案时 TVbox 播放正确停止（§5.5.8）。
20. `MobileNavHost.kt:153` 接 `onPlay`；`VodScreen` 选集 → resolver → tuner → `navigate(PLAYER_ROUTE)`；`PlayerScreen` 按 `TVBOX_ITEM_ID` 隐藏收藏与字幕入口。
21. 回归原点播（Movies/Series）、直播、mini player、PlayerScreen HUD。

**P5 — JS/drpy 源（2026-11-12 已落地）**
22. 引入 QuickJS 2.1.0 AAR + `tvbox/engine/js/**`（12 文件）；`JsEngine` 排在 CMS/Jar 之后，native 不可用时 `supports=false`；JS 与 jar 共用 `JarProxyHub`（字节/字符串响应、Range、会话 token）。
23. 全量验收（§10）、release 包 R8 验证、按项目说明第 7 条清理测试构建产物（保留脚本与 `docs/backup`）、更新 `docs/core-changes.md`（本次 core 零改动）。

---

## 8. 备份安排

**统一目录**：`D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\docs\backup`（用户指定，已存在，当前为 untracked）。

**命名与做法**：沿用现有习惯，逐字节复制为 `<原文件名>.bak`；同名冲突时加阶段后缀，如 `VodScreen.kt.before-tvbox.bak`。`docs/backup/README.md` 追加本次章节，记录日期、缘由、对应源码路径、恢复方法。注意：**该 README 必须用编辑器整篇重写，不要用 `cat >>` 追加中文**（本机实测会把新章节插到文件头并吞掉原小节）。

**实施前需备份的原文件**（改动前各复制一份）：

| 原文件 | 备份名 |
|---|---|
| `ui/screens/vod/VodScreen.kt` | `VodScreen.kt.bak` |
| `ui/nav/MobileNavHost.kt` | `MobileNavHost.kt.bak` |
| `ui/screens/library/VodTuner.kt` | `VodTuner.kt.bak` |
| `OwnTVMobileApp.kt` | `OwnTVMobileApp.kt.bak` |
| `app/build.gradle.kts` | `app.build.gradle.kts.bak`（**务必备份工作区当前版本**，它含未提交的本机签名/test keystore 改动） |
| `gradle/libs.versions.toml` | `libs.versions.toml.bak` |
| `app/proguard-rules.pro` | `proguard-rules.pro.bak` |
| `app/src/main/AndroidManifest.xml` | `AndroidManifest.xml.bak`（若改动） |
| `res/values/strings_mobile.xml` + `values-zh-rCN/` + `values-zh-rTW/` 同名文件 | `strings_mobile.xml.bak` / `strings_mobile.xml.zh-rCN.bak` / `strings_mobile.xml.zh-rTW.bak` |
| `ui/screens/settings/SettingsScreen.kt`（若加设置入口） | `SettingsScreen.kt.bak` |

另建议：先把当前 `docs/Core源码集成方案.md` 的未提交 diff 导出为 `docs/backup/Core源码集成方案.md.local.patch`，避免后续操作覆盖。`.bak` 不是 Kotlin 源集后缀，不参与编译，也不会被 `app/src/main/java/**/*.kt` 的 i18n 扫描读到。

---

## 9. 兼容性处理

| 风险面 | 处理 |
|---|---|
| **py 耦合摘除** | `SiteConfig.isSupported` 改为 `0,1,4 -> api.isNotBlank()`、`3 -> spiderClassName != null || isJsSite`；`spiderClassName`（`TvBoxConfig.kt:145`）与 `toSpiderSite("probe")`（`:328-329`）去掉 `isPySite` 条件；删 `isPySite`/`BuildConfig.WITH_PY`/`unsupportedReason` py 文案；`ConfigRepository` 删 `.py` 分流与 `importPyScript`/`ensurePyFile`/`materializeLocalPy`/`downloadPy`；`ConfigViewModel.jarReady` 只按 jar 缓存判定；`ServiceLocator` 对应注册删除 |
| **i18n 硬门禁（最高优先级）** | `app/build.gradle.kts:302` 让 `preBuild` 依赖 `verifyI18nLiterals`；`tools/i18n/check_hardcoded_strings.py:34-36` 只扫 `app/src/main/java`，`hardcoded_baseline.txt` 近乎空 → **任何新增用户可见字面量都会让编译红**。所有文案进三语言 `strings_mobile.xml`（key 集合必须一致，`MobileStringsParityTest`）；日志/JSON key/URL/正则等技术字面量走 `tools/i18n/classify-safe` 归类；数字格式化显式 locale（`check_number_locale.py`）；Compose `Text` 必须有 overflow 策略（`check_text_overflow.py`） |
| **OkHttp 4.12 → 5.5** | `NetApi` 已使用 5.x 兼容写法（`toRequestBody`/`toMediaType` 扩展）；重点核对 `CookieJar`/`Dns`/`SpiderNet` 的自定义实现与 `Headers` 构造；catvod 桩 `crawler/Spider.kt:221 client(): OkHttpClient` 与 `net/OkHttp.kt` 按 5.5 改写；用 `newBuilder()` 派生，避免改动 Core 全局单例（它强制 HTTP/1.1 + 自定义 trust/UA） |
| **Kotlin/AGP/Compose 跨代** | 只复制 `.kt` 业务逻辑；Compose UI 全部重写；`kotlinx-serialization` 按 §5.3 A 方案处理；验证 `@JvmField`/`@JvmStatic`/反引号标识符在 Kotlin 2.4.20 下的行为 |
| **WebView 嗅探** | Mobile 全仓无 WebView 代码；`SniffEngine` 必须在主线程创建 WebView、attach 到当前 Activity 视图树（1×1），并处理 `CookieManager`/`stopLoading`/延迟 destroy；release 需补 R8 keep；`usesCleartextTraffic=true` 已具备 |
| **jar / DexClassLoader** | minSdk 26 支持；加固壳（`assets/wexguard_*.so`）只有 ARM v7/v8，**x86_64 模拟器无法加载**，arm64 真机才能验；`com.github.catvod` 桩是硬 ABI，不能改名/裁剪；本地回环代理 `127.0.0.1:9978/proxy?do=` 必须在 `JarEngine.warmUp` 早于任何 obtain 启动，且回环地址绝不能外发给第三方解析器 |
| **播放状态与策略** | `PLAYER_ROUTE` 无参数 → 先起播再 navigate；`VodTuner`/`LiveTuner` 互斥（`liveTuner.stop()`）；不可绕过 `DataSaverGate`、外部播放器、Cast、`PlaybackService.start`、`session.attach`；**TVbox 走独立通道**：`_playing` 用 `TVBOX_ITEM_ID = -2L`，进度/历史/收藏写 `TvboxStore`，不碰 Core 的 `progressDao/historyDao/favoriteDao`；`PlayerScreen` 收藏/字幕入口按该常量隐藏（§5.4） |
| **分类锁 / 儿童模式** | 锁 key 统一 `tvbox:<siteKey>:<typeId\|typeName>` 写入 Core `lockedCats`（MOVIE section），复用同一 Customize PIN、`CategoryUnlockSession`、`PinAttemptLimiter`；儿童模式复用 `profile.isKids` + `AdultCategoryClassifier.isAdult` 并补 TVbox 中文词表。**不改 Core schema/源码**；副作用见 §5.5.9（Core 的 `ItemCategory.Unresolved` 会更保守） |
| **headers** | `StreamHeaders.encode` 会规范化名称并丢弃 `host/content-length/connection/transfer-encoding/range`；TVbox 返回的 `JsonObject` header 先转 `Map<String,String>`；UA 单独传 `userAgent`（`StreamHeaders.userAgentOf` 优先级见 `OwnTVPlayer.kt:2856`） |
| **包名/资源** | 业务代码统一 `tv.own.owntv.mobile.tvbox.**`；`com.github.catvod.**` 保持原包名；VodPlayer 的 `R.string.*` / `BuildConfig.*` / Timber 全部替换；`android.nonTransitiveRClass=false` 让 app 可直接用 Core 字符串 |
| **构建与提交** | 本机 JDK 25；变体 `assembleStandardDebug` / `assembleX86_64Debug` / `assembleStandardRelease`；不改 `vendor/OwnTV_Core`（subtree 流程见 `docs/Core源码集成方案.md:300-312`）；不在 Mobile 新建分支；需求完成后清理构建产物 |

---

## 10. 测试验收要点

**编译门禁**
- `./gradlew :app:assembleStandardDebug`（`preBuild` 自动跑 `verifyI18nLiterals`）、`:app:testStandardDebugUnitTest`、`:app:lintStandardDebug`；release 另跑 `:app:assembleStandardRelease` 验 R8 keep 与签名。
- `git diff` 复核：无播放器依赖新增、无 `vendor/OwnTV_Core` 改动、无 py 文件、无 Room schema 变更。

**单元测试（从 VodPlayer 移植 TVbox 子集，排除 py 相关）**
- `ConfigParser`：注释剥离 / 脏 JSON / sites/parses/rules/ads/proxy/doh；`FindResult.decode` 四种封装；`Aes`。
- `LocalSourceResolver.resolveAgainst`：绝对 URL、本地路径、`url;md5` 多段、`./x` 与裸 `x`、旧配置无来源 URL 兜底（对应 `4c8d289` 的 5 个单测）。
- `PlayUrlCodec`：`$`/`#`/`$$$` 分集与线路；`JarResultParser`：Bean/JsonObject/Map/JSON 字符串四形态；`CipherUrlDecoder`；`SniffMatcher` 强弱命中。

**端到端（本地夹具优先，避免依赖公网）**
1. URL 导入配置 → 站点列表 → 支持/不支持原因正确 → 冷启动从缓存恢复并按来源 URL 回源刷新。
2. 相对 `spider` 地址（含「网源转存本地文件」形态）→ jar 下载落盘 → csp_* 站能加载实现类。
3. CMS type 0 JSON / type 1 XML / type 4：分类 → 分页 → 搜索 → 详情 → 线路分集。
4. jar 源：`init/ext`、五接口、`proxy` 回环代理、加固壳（arm64 真机）。
5. `playerContent` 返回直链 / 密文 / `parse=1` 二级页（走嗅探）三条路径都能得到最终 URL + headers。
6. 起播：单集 `play`、多集 `playEpisodes` 的 next/prev、UA/Referer/Cookie、http 明文、HLS m3u8、后台返回。
7. 续播：从 `TvboxStore` 读回上次位置；暂停、杀进程、再次进入三条路径一致。
8. **分类锁**：VOD 分类行 ⋯ → 上锁 → 退出 App 再进入 → 分类隐藏/锁定 → 点击弹 PIN → 正确 PIN 解锁并进入；错误 PIN 5 次后触发限速；离开 App 后重新锁。
9. **儿童模式**：切到 `isKids` 档案 → 成人分类/成人站点/`adult` 标记站点隐藏；聚合搜索不返回这些源；详情/播放二次校验拒绝；普通档案不受影响。
10. **重锁停播**：TVbox 播放已上锁分类的内容 → 按 Home（`ON_STOP`）→ 回前台，TVbox 播放已停止，Core Live/VOD 行为不变。
11. **备份恢复**：带 TVbox 锁的备份恢复到另一台设备 → `lockedCats` 中的 `tvbox:` key 原样保留，锁仍生效（`BackupManager.remapCustomizationValue` 不重映射非数字前缀）。

**回归与红线**
- 原有 Movies/Series 点播、直播、mini player、PlayerScreen HUD 不受影响；TVbox 起播时直播正确停止、Cast 正确释放。
- **数据隔离验收**：完整播放一次 TVbox 内容并退出后，Core 的 `playback_progress`、`watch_history`、`favorites` 三表**无新增行**（可用 `adb shell` + Room 调试或单测断言）；`PlayerScreen` 不出现 TVbox 收藏项。
- **分类锁数据验收**：TVbox 上锁后 Core `owntv_customizations` 的 `cust_<pid>_MOVIE` 中 `lockedCats` 出现 `tvbox:<site>:<cat>`；Core 设置移除 PIN 后该 key 被清空；Core 的 Customize 页不出现 TVbox 分类行（预期）。
- **儿童模式一致性**：同一 `isKids` 档案下，Core 内容与 TVbox 内容使用同一 `profile.isKids` 与 `AdultCategoryClassifier.isAdult`；中文词表差异只允许出现在 TVbox 侧，且必须在 §5.5.5 记录。
- 播放器验收：全链路只出现 `OwnTVPlayer.play/playEpisodes` 调用，`PlayerScreen` 仍是原文件，未新增 Media3/ExoPlayer/IJK/播放器依赖。
- 范围验收：仓库中不存在 `src/py`、`src/nopy`、`main/python`、`PyEngine`、`WITH_PY`、Chaquopy；未带入 TV 双 UI / 直播 / 投屏 / 下载 / 网盘源文件。
- 依赖闭包验收：`util/CoroutineUtil.kt`、`engine/sniff/TvBoxParity.kt` 已随白名单带入，`assembleStandardDebug` 无 unresolved reference。
- 收尾：清理测试构建产物（保留脚本与 `docs/backup`），按项目说明第 4/7 条走提交流程。

---

## 11. 主要风险与缓解

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | **分支误用**：按 `feature/tv @1d0563e` 取解析代码 | 缺 `4c8d289`，旧配置/裸相对 spider 解析失败，csp_* 站全灰 | 统一以 VodPlayer **本地 `main @4c8d289`** 取码（§1.3/§1.4），P0 用 `git show main:<path>` 导出并核对 |
| R2 | **播放器被误引入**：整包拷 `PlayViewModel`/`PlayerManager` | 带进 Media3/ExoPlayer/IJK，违反硬约束，APK 体积与双内核冲突 | P4 只搬解析编排到 `TvboxPlayResolver`；白名单禁止 `player/**`；验收用 `git diff` 卡红线 |
| R3 | **i18n 硬门禁** | 新增中文日志/文案直接让 `preBuild` 失败，无法编译 | P1 先用空壳过门禁；所有文案三语言同步；技术字面量 `classify-safe` |
| R4 | **数据污染 Core** | 复用 `began()`/`saveProgress()` 会把 TVbox 条目写进 Core 进度/历史表；改 Room v53 还会触发 subtree + 50 schema 迁移 | TVbox 数据独立 app 私有存储；`playTvbox` 不调用三处写入；`itemId` 用 `TVBOX_ITEM_ID = -2L`；不改 `vendor/OwnTV_Core` |
| R5 | **jar/桩 ABI 与 R8** | 反射加载失败、加固壳在 x86_64 不可用、release keep 缺失 | 桩保持原包名；补 keep；arm64 真机验收；代理在 warmUp 启动 |
| R6 | **WebView 嗅探** | 主线程/Activity attach 不当导致崩溃或解析超时 | 单列 P4 适配；用夹具复现 `parse=1`；超时与三腿竞速逻辑按源码保留 |
| R7 | **依赖跨大版本** | Kotlin 2.1→2.4.20、OkHttp 4.12→5.5、Coil 2→3、Compose BOM 跨代编译失败 | P1 先只加依赖编空壳；按 §5.2/§5.3 逐项适配，不搬构建脚本 |
| R8 | **工作区未提交改动被覆盖** | `app/build.gradle.kts` 的本机签名/test keystore 改动丢失；`docs/backup` 未跟踪 | P0 先导出 diff 与备份；新增依赖时用 Edit 追加而非整文件覆盖 |
| R9 | **范围蔓延** | py 源、直播、投屏、下载、网盘源、TV UI 误带入 | §12 白名单 + P0 逐文件核对；每阶段 `git status` 检查新增文件 |
| R10 | **播放状态顺序** | 先 navigate 后起播 → `PlayerScreen` 空态；两 tuner 未互斥 | 严格按 §3.2 顺序，照 `playRecording` 骨架做 housekeeping，但不调用 `began()` |
| R11 | **进度/收藏串数据** | `VodPlayback.itemId: Long` 与 `PlayerScreen.kt:267` 的收藏键让 TVbox 条目可能落进 Core 表 | `TVBOX_ITEM_ID = -2L`；`playingExternal` 短路 `saveProgress`；播放页隐藏收藏；验收断言三表无新增行（§10） |
| R12 | **分类锁副作用** | TVbox 锁写入 Core `lockedCats` 后，Core 的 `CategoryLockView.anySealed` 对 `ItemCategory.Unresolved` 更保守，可能拒绝播放分类已删除的下载/录制文件 | 接受该保守行为并写入 §5.5.9；若业务不可接受，走 §5.5.10 的 Core v54 外部分类命名空间，不在本期处理 |
| R13 | **儿童模式漏判** | 中文成人分类词表不全会让儿童档案看到成人内容 | 复用 `AdultCategoryClassifier` + TVbox 中文词表 + 站点 `ext` 的 `adult/nsfw` 标记；提供 `kidsStrict` 严格模式；用户可手动上锁；验收用夹具覆盖常见中文分类名 |
| R14 | **分类锁 key 漂移** | 站点改分类 `typeId` 或改名后，已上锁的 key 失配 | 与 Core「锁跟随 key」一致，需重新上锁；VOD 页在分类变更时提示；key 用 `typeId` 优先、`typeName` 兜底 |

---

## 12. 预计新增 / 修改文件清单

> 根 = `D:\AndroidStudioProjects\git\Qwn\OwnTV_Mobile\`。源侧文件一律从 `video_on_demand` 的 **`main @4c8d289`** 取，复制到下表目标位置后按 §9 适配。

### 12.1 新增（Kotlin 源码，包 `app/src/main/java/tv/own/owntv/mobile/tvbox/`）

| 子路径 | 来源（VodPlayer `main`） | 备注 |
|---|---|---|
| `config/model/TvBoxConfig.kt`、`AdRules.kt` | `config/model/*` | 去 py 分支与 `BuildConfig` |
| `config/parser/ConfigParser.kt`、`FindResult.kt` | `config/parser/*` | 脏 JSON / 解密 |
| `util/Aes.kt`、`LocalSource.kt`、`Md5Util.kt`、`FileUtil.kt`、`HtmlGuard.kt`、`TextUtil.kt`、`JsonExt.kt`、`CoroutineUtil.kt` | `util/*` | **`LocalSource.kt` 必须含 `resolveAgainst`/`LocalSourceResolver`/`SpiderSpec`**；`JsonExt.kt` 含 `StripHtml`/`toHeaderMap`/`toJsonObject`；`CoroutineUtil.kt` 含 `SourceHealth`/`AppScope`/`onIo` |
| `net/NetApi.kt`、`OkHttpProvider.kt`、`AddressFetcher.kt`（按需 `MemoryCookieStore.kt`） | `net/*` | `OkHttpProvider` 重写为 `koinInject<OkHttpClient>().newBuilder()` 派生；代理/DoH 交给 Core 单例，`ConfigProxySelector`/`DohDns`/`Interceptors`/`LanAddress` 不搬；保留 jar cookie 共享时带上 `MemoryCookieStore.kt` |
| `engine/Spider.kt`、`SpiderResult.kt`、`PlayUrlCodec.kt`、`CipherUrlDecoder.kt`、`Base64Compat.kt`、`PlayNormalize.kt`、`ParseHealth.kt`、`ParseRepository.kt` | `engine/*` | 纯 JVM 核心 |
| `engine/cms/CmsSpider.kt`（含 `CmsEngine`）、`CmsParser.kt` | `engine/cms/*` | CMS type 0/1/4 |
| `engine/jar/JarSpider.kt`、`JarEngine.kt`、`JarResultParser.kt` | `engine/jar/*` | type 3 csp_* |
| `engine/proxy/JarProxyHub.kt`、`JarProxyServer.kt` | `engine/proxy/*` | 回环代理 |
| `engine/sniff/SniffEngine.kt`、`SniffMatcher.kt`、`TvBoxParity.kt` | `engine/sniff/*` | `parse=1` 兜底；**`TvBoxParity.kt` 必带**（`SniffEngine.kt:876` 直接调用 `TvBoxParity.checkVideoFormat`） |
| `repository/ConfigRepository.kt`（精简）、`SourceRepository.kt`、`TvboxStore.kt`（配置缓存/历史/收藏/进度） | `repository/ConfigRepository.kt`、`SourceRepository.kt` | 去 Room/DataStore 备份/py/SourceStat |
| `play/TvboxPlayResolver.kt`、`play/ResolvedPlay.kt` | 由 `vm/PlayViewModel.kt` 的 `resolveAndPlay` 逻辑提炼 | **不含** `PlayerManager` / Media3 |
| `di/TvboxModule.kt` | 无（新写） | Koin 装配 |
| `category/TvboxCategoryPolicy.kt`、`category/TvboxCategoryLock.kt`、`category/TvboxSealedPlaybackGuard.kt` | 无（新写） | 分类锁/儿童模式；见 §5.5 |
| `ui/screens/vod/VodViewModel.kt`、`TvboxConfigViewModel.kt`、`TvboxHomeViewModel.kt`、`TvboxDetailViewModel.kt`、`TvboxConfigScreen.kt`、`TvboxHomeScreen.kt`、`TvboxDetailScreen.kt`、`TvboxComponents.kt` | 无（按 Stage 组件新写） | 替换占位 UI |
| `app/src/main/java/com/github/catvod/**`（11 个桩文件） | `app/src/main/java/com/github/catvod/**` | **保持原包名**，jar ABI 契约 |
| `app/src/test/java/.../tvbox/**` | VodPlayer TVbox 相关单测 | 排除 py 测试 |
| `tvbox/engine/js/**`（12 文件，P5 已落地） | `engine/js/*` | QuickJS 2.1.0；native 不可用时降级；实施细节见 `TVbox-JS源迁移方案.md` |

### 12.2 新增（非源码）

| 文件 | 说明 |
|---|---|
| `docs/TVbox点播迁移方案.md` | 本方案 |
| `docs/backup/*.bak`（§8 清单）+ `docs/backup/README.md` 更新 | 备份与恢复说明 |
| 本地夹具脚本（放 `tools/` 或 `device_ui/`，可选） | 自造 CMS/jar/二级页 HTTP 夹具，用于端到端验收 |

### 12.3 修改（预计）

| 文件 | 修改内容 |
|---|---|
| `app/build.gradle.kts` | 新增依赖（serialization-json / gson / jsoup，P5 另加 QuickJS）；**保留现有本机签名/test keystore 改动** |
| `gradle/libs.versions.toml` | 新增版本与坐标条目 |
| `app/proguard-rules.pro` | 补 catvod 桩 / jar 反射类 / WebView 相关 keep |
| `app/src/main/AndroidManifest.xml` | 视 WebView/SAF 需要微调（预计可不改；INTERNET 与 cleartext 由 Core/现有 Manifest 提供） |
| `app/src/main/res/values/strings_mobile.xml`、`values-zh-rCN/strings_mobile.xml`、`values-zh-rTW/strings_mobile.xml` | 三语言 TVbox 文案（key 集合一致） |
| `ui/nav/MobileNavHost.kt`（:153） | `VodScreen()` → 带 `onPlay` 等回调 |
| `ui/screens/vod/VodScreen.kt` | 占位页整体替换 |
| `ui/screens/library/VodTuner.kt` | 新增 `playTvbox(...)` 与 `playingExternal` 标志；不改既有 `playMovie/playEpisode` 行为 |
| `ui/player/PlayerScreen.kt`（约 10 行） | TVbox 播放时（`film.itemId == TVBOX_ITEM_ID`）隐藏收藏与字幕入口，避免负数 id 写进 Core 收藏表、避免无效字幕搜索；这是 §4.3「不改 ui/player」的唯一例外 |
| `ui/shell/MobileShell.kt` | 注册 `TvboxSealedPlaybackGuard`（`ON_STOP` + `activeProfileId` 变化），TVbox 播放中命中锁即停播（§5.5.8） |
| `OwnTVMobileApp.kt`（:160-164） | `modules(...)` 追加 `tvboxModule` |
| `tools/i18n/safe_literals.txt` / `hardcoded_baseline.txt` | 仅当使用 `classify-safe` 归类技术字面量时更新 |
| `docs/core-changes.md` | 记录「本次 core 零改动」或实际改动 |

### 12.4 明确不新增 / 不修改

`ui/nav/MobileDestination.kt`、`ui/player/**`（`PlayerScreen.kt` 的收藏/字幕入口除外）、`vendor/OwnTV_Core/**`（含 `OwnTVPlayer.kt`、`HttpClient.kt`、`OwnTVDatabase.kt`、Core Room schema）、`settings.gradle.kts`、任何播放器依赖、`app/src/main/AndroidManifest.xml` 的 Activity 声明、`src/py|nopy`、`app/src/main/python/**`。

---

## 附录 A：源侧最小闭环文件白名单（导入 → 解析 → 列表 → 详情 → 播放）

```
config/model/{TvBoxConfig,AdRules}.kt
config/parser/{ConfigParser,FindResult}.kt
util/{Aes,LocalSource,Md5Util,FileUtil,HtmlGuard,TextUtil,JsonExt,CoroutineUtil}.kt
net/{NetApi,OkHttpProvider,AddressFetcher}.kt（保留 jar cookie 时加 MemoryCookieStore.kt）
engine/{Spider,SpiderResult,PlayUrlCodec,CipherUrlDecoder,Base64Compat,PlayNormalize,ParseHealth,ParseRepository}.kt
engine/cms/{CmsSpider,CmsParser}.kt
engine/jar/{JarSpider,JarEngine,JarResultParser}.kt
engine/proxy/{JarProxyHub,JarProxyServer}.kt
engine/sniff/{SniffEngine,SniffMatcher,TvBoxParity}.kt
repository/{ConfigRepository,SourceRepository,DetailFallback}.kt
app/src/main/java/com/github/catvod/**   （11 个桩，原包名）
```

**依赖闭包校验**（v1.1 实测）：`CmsSpider` → `SourceHealth`/`StripHtml`/`toHeaderMap`/`toJsonObject`；`OkHttpProvider` → `UaUtil`/`MemoryCookieStore`；`AddressFetcher` → `SettingsStore`（需替换）/`LocalRead`/`FailKind`/`LocalSourceResolver`；`SniffEngine` → `TvBoxParity`；`ConfigRepository` → `AppScope`/`RemoteArtifact`/`SpiderSpec`。以上除 `SettingsStore` 外均已包含在清单中；`SettingsStore` 用 `TvboxStore` 替换。

裁剪项：`ui/**`（含 `ui/tv/**`）、`vm/**`（只提炼解析逻辑）、`player/**`、`data/**`（Room/DataStore）、`di/ServiceLocator.kt`、`VodApp.kt`、`MainActivity.kt`、`engine/js/**`（P5）、`engine/alist/**`、`live/**`、`cast/**`、`download/**`、`update/**`、`server/**`、`subtitle/**`、`danmu/**`、`util/SignatureGuard.kt`、`util/TamperGuard.kt`。

## 附录 B：py 源排除清单（一律不复制、不引用）

```
app/src/main/python/base/{__init__,loader,spider}.py
app/src/py/**（PyEngine/PySpider/PyHost/PyEngineHolder 及其测试）
app/src/nopy/**（空桩）
app/proguard-chaquopy.pro
app/build.gradle.kts 中 com.chaquo.python / WITH_PY / sourceSets / chaquopy{} / findBuildPython
gradle.properties 的 WITH_PY
根 build.gradle.kts 的 Chaquopy 插件
SiteConfig.isPySite、isSupported 的 WITH_PY 分支、unsupportedReason py 文案
ConfigRepository 的 .py 分流与 importPyScript/upsertSite/ensurePyFile/materializeLocalPy/downloadPy
ServiceLocator.pyEngine / PyEngineHolder / engines 尾部 pyEngine
所有 py 相关 docs 与单测
```

---

## 附录 C：已拍板决策（v1.2，实施按此执行）

| # | 决策 | 结论 | 落点 |
|---|---|---|---|
| C1 | type 3 的 JS/drpy 是否本期纳入 | **本期不做**；P5 保留可选位，单独评审 | §7 P5、§12.1 |
| C2 | TVbox 是否接 Core 分类锁 / 儿童模式 | **接入**：复用 Core `lockedCats`、Customize PIN、`CategoryUnlockSession`、`PinAttemptLimiter`、`AdultCategoryClassifier`；不改 Core schema/源码 | §5.5 |
| C3 | 播放页收藏入口 | **隐藏**（`TVBOX_ITEM_ID`），不写 Core 收藏表 | §5.4、§6 第 9 行、§12.3 |
| C4 | 进度/历史是否进入 Core「继续观看」 | **不进入**；TVbox 页内独立入口 | §5.4 |
| C5 | 多集每集 headers | **逐集携带** `httpHeaders`，不共用首集 | §3.2、§7 第 17 步 |
| C6 | 配置管理形态 | **单配置启用 + 列表切换**，不做多配置合并 | §7 P2 |
| C7 | 源健康/冷却 | **保留 `SourceHealth`**，耗尽即灰显 | §12.1 |
| C8 | 字幕/弹幕 | **首版不接**；播放页字幕入口对 TVbox 隐藏 | §6 第 9 行、§12.3 |

## 附录 D：P0 基线核对命令（Windows git-bash）

```bash
# 1. 三仓状态（期望：mobile-mini / mobile / feature-tv）
git -C OwnTV_Mobile status --short
git -C OwnTV_Mobile rev-parse --abbrev-ref HEAD && git -C OwnTV_Mobile log -1 --oneline
git -C OwnTV_Core rev-parse --abbrev-ref HEAD && git -C OwnTV_Core log -1 --oneline
git -C ../video_on_demand rev-parse --abbrev-ref HEAD && git -C ../video_on_demand log -1 --oneline

# 2. 确认分支陷阱：期望只剩 4c8d289；期望 70 files changed
git -C ../video_on_demand log --oneline feature/tv..main
git -C ../video_on_demand diff --stat main..feature/tv | tail -1

# 3. 只读导出白名单（用 main，不切分支）
git -C ../video_on_demand archive main app/src/main/java/com/vod/player/config \
  app/src/main/java/com/vod/player/util app/src/main/java/com/vod/player/net \
  app/src/main/java/com/vod/player/engine app/src/main/java/com/vod/player/repository \
  app/src/main/java/com/github/catvod | tar -x -C /tmp/tvbox-src

# 4. 分类锁/儿童模式 API 核对（实施前确认签名未漂移）
grep -n "fun setCategoryLocked\|fun observe(\|lockedCategories" \
  vendor/OwnTV_Core/core/src/main/java/tv/own/owntv/core/customize/CustomizationStore.kt
grep -n "fun isUnlocked\|fun unlock\|fun epoch\|fun relockAll" \
  vendor/OwnTV_Core/core/src/main/java/tv/own/owntv/core/customize/CategoryUnlockSession.kt
grep -n "fun isAdult\|fun allows" \
  vendor/OwnTV_Core/core/src/main/java/tv/own/owntv/core/content/AdultCategoryClassifier.kt
grep -n "fun customizePin\|fun verifyCustomize" \
  vendor/OwnTV_Core/core/src/main/java/tv/own/owntv/core/settings/CustomizePinSettings.kt \
  vendor/OwnTV_Core/core/src/main/java/tv/own/owntv/core/util/Pin.kt

# 5. 基线编译（Windows 用 gradlew.bat）
cd OwnTV_Mobile && ./gradlew :app:assembleStandardDebug
```

---

**方案结束。v1.2 仅修订本文档，未修改任何代码文件。**
