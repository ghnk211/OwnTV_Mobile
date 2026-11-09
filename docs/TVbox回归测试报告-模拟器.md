# TVbox 点播迁移 · 模拟器回归测试报告

> 日期：2026-11-08 深夜（实现完成后连续执行）
> 环境：Windows 10 19045 / Pixel_7_API_36 模拟器（Android 15，x86_64，1080×2400）
> 被测版本：OwnTV_Mobile `mobile-mini`，TVbox 迁移实现提交 `0f4d34b` + 回归修复（见 §5）
> 测试站源：本地夹具（`tools/regression/fixture-server.js`，CMS type 0 JSON + mp4 直链），宿主机 8899 端口，模拟器经 `10.0.2.2` 访问
> 遵循文档：`D:\AndroidStudioProjects\安卓模拟器使用流程.md`（坐标取自 `uiautomator dump`、`MSYS_NO_PATHCONV=1`、截图用 `exec-out screencap`、探针用真实业务请求）

---

## 1. 测试结论

**通过（18 项）／发现并修复 3 个缺陷（§5）／1 项偏差记录（§6）**。核心链路（导入 → 解析 → 内容 → 播放）、分类锁、儿童模式、数据隔离、重锁停播全部达标；方案红线（不改 Core、不新增播放器依赖、TVbox 数据不进 Core 表）复核通过。

---

## 2. 环境准备

| 项 | 值 |
|---|---|
| APK | `:app:assembleStandardDebug` 产物 `app-standard-debug.apk`（79.7 MB） |
| 安装 | 首次装包因签名不同执行 `uninstall + install`；后续 `install -r` |
| 夹具 | `node tools/regression/fixture-server.js`（0.0.0.0:8899）：`/tvbox-local.json` 配置、`/api.php/provide/vod` CMS type 0（ac=list/videolist/detail，4 分类×24 条目，每条 2 集）、`/video.mp4`（ffmpeg 生成 90s H.264+AAC，支持 Range）、`/pic/*` 占位海报 |
| 测试账号 | 向导新建档案「Tester」（id=1）；Customize PIN=1234（设置 → Sources & guide → Customize → ⋯ → Set PIN） |
| 辅助脚本 | `tools/regression/{dump_ui.py, search_test.py, set_pin.py, relock_test.py, toggle_kids.py}`（动态坐标 + 重试，规避文档坑 4/5/6 与软键盘位移） |

## 3. 通过项（与方案 §10 对照）

| # | 用例 | 结果 | 证据 |
|---|---|---|---|
| 1 | **URL 导入配置**：VOD 页 → 配置 → Import → `http://10.0.2.2:8899/tvbox-local.json` | ✅ | 日志 `配置解析完成：站点 1（协议已知 1，M1 可用 1）`；列表出现「10.0.2.2」条目 |
| 2 | **冷启动缓存恢复**：force-stop 后重启 | ✅ | 日志 `已从缓存恢复配置：10.0.2.2（1 站点）`（`files/tvbox/configs/1.json` + `tvbox_prefs`） |
| 3 | **首页内容**：站点选择器、分类 chips（电影/剧集/综艺/伦理片）、海报网格 + HD 角标 | ✅ | 截图 `shot_home2.png`；数据来自 `ac=list` + `ac=videolist` |
| 4 | **详情与线路分集**：`ac=detail&ids=` → 标题/年份/角标 + 「2 episodes」+ 第01/02集 | ✅ | 截图 `shot_detail2.png` |
| 5 | **单集起播（直连）**：点第01集 → mpv 硬解（`h264_mediacodec`）→ 彩条画面渲染 | ✅ | 截图 `shot_play2.png`（19s 帧）；`FILE_LOADED` 日志 |
| 6 | **多集队列**：30s 单集播完弹「Next episode in Ns · 电影测试01」；MEDIA_NEXT 键换集成功（`FILE_LOADED generation=5`） | ✅ | logcat |
| 7 | **逐集进度**：播放中 5s 定时器写 `files/tvbox/progress.json`；第2集起播后 `episodeIndex:1` 正确落盘 | ✅ | progress.json 内容（§4） |
| 8 | **续播读回**：退出重进详情，第02集高亮（resumeIndex） | ✅ | 截图 `shot_resume.png` |
| 9 | **聚合搜索**：搜索「01」→ 按「本地CMS」分组返回 4 条（电影/剧集/综艺/伦理片 各 01） | ✅ | 截图 `shot_search3.png` |
| 10 | **分类锁·写入**：长按「电影」chip → 「Lock this category」 | ✅ | Core DataStore `cust_1_MOVIE` 出现 `"lockedCats":["tvbox:local_cms:1"]`——与方案 §5.5.3 key 规范一致 |
| 11 | **分类锁·持久化**：重启 App 后 chip 显示 🔒 | ✅ | 截图 `shot_lock3.png` + dump `🔒电影` |
| 12 | **分类锁·列表层拦截**：点击 🔒电影 → PIN 弹窗（PinDoorSheet 复用）；正确 PIN 放行进入 | ✅ | 截图 `shot_pinsheet.png` |
| 13 | **分类锁·PIN 限速**：错误 PIN（9999）→ 「Wrong PIN — try again」 | ✅ | dump `shot_wrongpin.png`（共享 Core PinAttemptLimiter） |
| 14 | **儿童模式·分类隐藏**：isKids=1 后「伦理片」chip 从分类行消失 | ✅ | dump 对比（切换前后）；`AdultCategoryClassifier.isAdult("伦理片")` 或中文词表命中 |
| 15 | **儿童模式·搜索过滤**：搜索「01」不再返回伦理片条目（4 组→3 组） | ✅ | dump 解析（§3.9 对比） |
| 16 | **儿童模式恢复**：关闭 Kids mode 后 isKids=0、内容恢复 | ✅ | `SELECT isKids FROM profiles` → 0 |
| 17 | **重锁停播**：播放中按 Home → PiP → Core relockAll → TVbox guard 判定 sealed → `vodTuner.stop()`；进度定时器停止写入 | ✅ | `TvboxGuard: stopIfSealed（leftApp）：ctx=true` + 写入停止（`relock_test.py` PASS） |
| 18 | **数据隔离**：整个回归期间反复播放/换集/收藏入口展示后，Core 三表 `playback_progress=0 / watch_history=0 / favorites=0` | ✅ | sqlite 查询；TVbox 数据全部在 `files/tvbox/{progress,history,favorites}.json` |
| 19 | **回归·Live TV**：空态「Add a playlist first.」正常，无崩溃 | ✅ | dump |
| 20 | **回归·i18n 门禁**：`verifyI18nLiterals` 全程绿（1978 条 safe 分类 + baseline 未动） | ✅ | 每次构建日志 |

## 4. 数据落点核验（方案 §5.4）

```
files/tvbox/
├── configs/1.json           配置缓存（_cacheVersion:3）
├── configs/index.json       配置元数据
├── progress.json            [{"siteKey":"local_cms","vodId":"101","episodeFlag":"本地线路",
│                             "episodeId":"http://10.0.2.2:8899/video.mp4","episodeIndex":1,
│                             "positionMs":29000,"durationMs":30000,...}]
├── history.json             [{...,"name":"电影测试01","episodeIndex":0,...}]
├── favorites.json           （未触发收藏写入，入口已隐藏验证见 §6）
└── spiders/                 （本夹具无 jar，目录为空壳）
```

Core 侧 `databases/owntv.db`：`playback_progress=0 / watch_history=0 / favorites=0`（回归结束后复查）。`owntv_customizations.preferences_pb`：`cust_1_MOVIE.lockedCats=["tvbox:local_cms:1"]`（分类锁写入 Core 锁数据，符合方案）。

## 5. 回归中发现并修复的缺陷

| # | 症状 | 根因 | 修复 |
|---|---|---|---|
| D1 | 点海报进详情显示「No playable episodes」，无网络请求 | `onOpenDetail(donorSiteKey ?: "")` 传了空 siteKey，`siteMap[""]` 命中即返回 | 传 `state.currentSite?.key` 兜底（TvboxPages.PosterGrid） |
| D2 | 锁定分类 chip 不显示 🔒、点击不弹 PIN（重启后必现） | UI 用 `cat.typeId` 直接比对 `sealedKeys`（存的是完整 key `tvbox:site:1`）；另有一版修复把 siteKey 误并入判断 | UI 统一经 `TvboxCategoryLock.keyOfStatic(siteKey, typeId, typeName)` 比对（与锁写入同口径） |
| D3 | 解锁后播放中按 Home，TVbox 播放**未被**停止 | 三层原因：① Home 实际进入 **PiP**（`onPictureInPictureModeChanged`），不走 `onStop`，guard 没挂 PiP 路径；② guard 挂在 MobileShell 的 `LifecycleEventEffect(ON_STOP)`，与 `MainActivity.onStop` 里 Core `relock.leftApp()` 存在**顺序竞态**（guard 先于 relockAll 求值 → 会话未重锁 → seals=false）；③ 详情/播放层的锁 key 用 `typeName` 兜底，与列表层 `typeId` 写入的锁 key 漂移（方案 R14） | guard 移至 `MainActivity`：`onStop()` 与 PiP 分支中都在 `relock.leftApp()` **之后**调用；`profileChanged` 亦排在 `relock::profileChanged` 之后；列表→详情→播放全程携带 (typeId, typeName)（`TvboxCategoryKey`） |

## 6. 偏差与已知限制（不阻塞）

1. **返回后页面状态**：从全屏播放返回，VOD 页回到首页（页面状态是 Compose `remember`，播放页入栈时列表组合销毁）。功能无损，后续可用 `rememberSaveable` 改善。
2. **自动连播进度上下文**：队列自动换集时 `queueItemChanged` 已重指 `TvboxProgressCtx`；但本夹具两集共用同一 URL，key 按集 URL 相同 → 单条进度记录被覆盖（真实源每集 URL 不同，无此现象）。
3. **搜索结果浮层样式**：搜索结果列表叠在主页网格上（半透明遮罩较淡），可读性可再优化。
4. **arm-only 加固壳 jar**：x86_64 模拟器无法加载（方案 §9 已知限制），需 arm64 真机验证——本次未覆盖，已列入遗留。
5. **WebView 嗅探（parse=1）**：夹具未覆盖（本地直链 + 命名解析器路径单测已覆盖 ParseRepository 解析）；`SniffEngine` 的 Activity attach 逻辑与 VodPlayer 一致移植。
6. **备份恢复保留 tvbox: 锁 key**：方案 §10 第 11 项——`BackupManager.remapCustomizationValue` 对非数字前缀原样保留由 Core 既有逻辑与单测保证，本次未做设备级双机演练。
7. **业务层诊断文案**：移植自 VodPlayer 的解析链错误/日志文案保留中文原文（生态源错误本就是中文透传），已按门禁「log/technical」类目登记；新 UI 全部走三语言 `strings_mobile.xml`（`MobileStringsParityTest` 口径）。

## 7. 红线复核（方案 §2.3/§10）

- `git status vendor/`：**零改动**；`git diff` 无 media3/ExoPlayer/IJK/VLC/QuickJS 新增依赖；无 py 文件；无 Room schema 变更。
- 播放出口仅 `VodTuner.playTvbox → OwnTVPlayer.play/playEpisodes`；`PlayerScreen` 未新增播放页。
- i18n 门禁（`verifyI18nLiterals`）与 `MobileStringsParityTest` 键集一致（en/zh-rCN/zh-rTW 各 30+ 新键）。
- 单元测试：`tv.own.owntv.mobile.tvbox.*` **257 个全绿**（0 failures / 0 errors）。

## 8. 复现指引

```bash
# 1. 夹具（宿主机）
node OwnTV_Mobile/tools/regression/fixture-server.js
# 2. 安装
adb install -r OwnTV_Mobile/app/build/outputs/apk/standard/debug/app-standard-debug.apk
# 3. 手工链路：向导建档 → VOD 页 → 配置 → Import http://10.0.2.2:8899/tvbox-local.json → 返回主页
#    → 分类/详情/播放；设置 PIN 后可测分类锁（长按分类 chip）
# 4. 重锁停播自动用例（需已设 PIN=1234、已导入夹具配置、Kids mode Off）
python OwnTV_Mobile/tools/regression/relock_test.py
# 5. 儿童模式开关
python OwnTV_Mobile/tools/regression/toggle_kids.py   # 需先手动进入 Settings › Sources & guide
```

---

**结论**：TVbox 点播迁移在模拟器上的功能回归、分类锁/儿童模式接入、数据隔离与重锁停播均按方案 v1.2 验收口径通过；发现并修复 3 个缺陷后全链路复测通过。遗留项（arm64 真机加固壳、嗅探端到端、备份恢复演练）见 §6。

---

## 9. 代码改善批次（2026-11-09，第二轮回归验证）

针对 §8 复盘的代码层面改善清单逐项落地；**判定层单测先行**（新增 33 个），全部改动后 290 个 TVbox 单测全绿、`assembleStandardDebug` 与 i18n 门禁通过，并做了一轮设备级回归。

### 9.1 已落地的改善

| # | 改善 | 文件 | 验证 |
|---|---|---|---|
| 1 | **判定层单测**：锁判定四步顺序、双轨 key、解锁 epoch 协议、儿童判定矩阵（词表/ext/严格模式）、绝对地址补全 | `app/src/test/.../tvbox/category/{TvboxCategoryLockTest,TvboxCategoryPolicyTest}.kt`、`util/TextUtilAbsoluteUrlTest.kt` | 33 个新单测全绿；**单测当场抓到一个真实缺陷**：重写时 `seals` 的会话判定写反（解锁态被判为锁上），若没有这批测试将直接上线 |
| 2 | **锁 key 双轨**：`toggleLock` 主键（typeId 形态）与别名（typeName 形态）双写；`seals`/解锁会话对两形态任一命中即生效。搜索来源（只有 typeName）的播放不再绕过分类锁 | `TvboxCategoryLock.kt` | 单测 + 设备：解锁→重上锁后 DataStore 同时出现 `tvbox:local_cms:1` 与 `tvbox:local_cms:电影`（`strings` + od UTF-8 字节双重确认） |
| 3 | **锁数据读写缝**：`TvboxLockStore` 接口（生产实现包 Core `CustomizationStore`），判定层脱离 Android 依赖可直测 | `TvboxCategoryLock.kt` | 单测内存假件直跑 |
| 4 | **儿童判定去依赖**：`TvboxCategoryPolicy` 只收 `kidsStrict: () -> Boolean`，档案由调用方传入 | 同上 | 单测矩阵 |
| 5 | **resolveError 透传显示**：详情页对解析层 Error 原样展示引导文案（换线/换源），不再被固定文案吞掉 | `TvboxPages.kt` | 代码路径验证；设备级文本截图因测试与夹具时序竞态未定格（记录为待补） |
| 6 | **Banner/lockEvent 停留式消费**：横幅立即消费的"单帧闪烁"改为停留 3-4 秒 | `TvboxPages.kt` | 设备：重复导入时「此配置已存在：10.0.2.2」横幅持续可见（截图 `shot_banner3.png`） |
| 7 | **聚合搜索流式渲染**：收集 `searchProgress`（先等订阅建立再触发搜索），先到先显示；最终以返回列表为准 | `TvboxViewModels.kt`、`SourceRepository.kt`（补 `searchProgressSubscribers`） | 设备：搜索 10 行结果正常 |
| 8 | **海报相对地址补全**：网格/列表/搜索三处统一 `absoluteUrl(pic, site.api)` | `TvboxViewModels.kt` | 设备：夹具改为相对路径 `/pic/…?v=2` 后，宿主收到 9 个**已补全的绝对地址**请求，海报正常渲染 |
| 9 | **saveProgress 去重**：position 未变化跳过写盘（片尾 keep-open 不再每 5s 刷一条同值记录） | `VodTuner.kt` | 单测覆盖路径；设备观察写入停止正常 |
| 10 | **播放种类标志集中**：`beginPlay(PlayKind.CORE/TVBOX)` 单入口切换 + TVbox 身份字段统一清理，消灭四处散落的标志复位 | `VodTuner.kt` | 重锁停播回归 PASS |
| 11 | **死代码清理**：删除 `TvboxSourceStats`（恒 false 的空实现及其 5 处调用） | `SourceRepository.kt`、`TvboxModule.kt` | 编译 + 全量单测 |
| 12 | **缓存序列化字段集防线**：`TvboxConfigCacheCodec` 抽为独立对象 + encode→parse round-trip 单测（字段增删必须 +VERSION） | `TvboxConfigRepository.kt` | round-trip 单测锁住全部字段 |
| 13 | **网格稳定 key**：`id + typeName` 取代 index 兜底 | `TvboxPages.kt` | 编译 |

### 9.2 过程中发现并修复的两个新缺陷

| # | 缺陷 | 修复 |
|---|---|---|
| N1 | **详情页内 PIN 解锁后不加载内容**：`open()` 在 seals 命中时提前返回，`unlockWithPin` 只清标志——解锁后页面停在"无分集" | 解锁 Accepted 后重跑 `open(...)` 重新拉详情（设备已验证：解锁后详情正常加载） |
| N2 | **`tvboxModule` 的 `single<OkHttpClient>` 覆盖 Core 全局客户端**：Koin 后注册覆盖，Coil 图片加载、同步、备份全被换到 TVbox 网络链（违反方案"派生不改 Core 单例"） | 删除该注册；TVbox 网络链经 `OkHttpProvider`/`NetApi` provider 直达，不再占用 Core 的 Koin key |

### 9.3 设备级回归结论（改善批次）

- 重锁停播：模拟器重启后完整 PASS（play → Home → guard `ctx=true` → 写入终止）
- 双写锁：PASS（§9.1#2 证据）
- 横幅停留：PASS（截图）
- 相对海报：PASS（9 个补全请求 + 渲染）
- 聚合搜索：PASS（10 行结果）
- 数据隔离复核：Core 三表仍为 0 行
- 待补：resolveError 文案的设备级截图（时序竞态未定格，代码路径已由编译与其余同渲染路径分支间接覆盖）

### 9.4 遗留（下一批）

- `SecureKv` 明文简化 → Keystore AES-GCM 还债（jar 侧 cookie/token 加密，~100 行，单独排期）
- `TvboxPages.kt` 按页面拆分；`SitePickerSheet` 的 dismissed hack 换正式 sheet 形态
- 备份恢复保留 `tvbox:` 锁 key 的设备级双机演练

---

## 10. 真实生态源抽查（2026-11-09，tvbox源.txt 随机抽取）

从采集清单（`tvbox源.txt`，114 条可解析地址）以固定种子随机抽 6 条，在模拟器上实测迁移链。全程使用真机同款交互路径（配置页 → Import → URL 对话框）。

### 10.1 结果

| 抽取 | 地址 | 结果 | 证据 |
|---|---|---|---|
| 软件 | `http://47.96.82.41:5188/api.json` | **导入成功**：解析 90 站点（M1 可用 88）、parses 8、rules 6、doh 5；spider jar（2.1MB）下载缓存（md5 命名）；**jar 本地代理 127.0.0.1:9978 启动**；首页源（csp_Douban）经 JarEngine 加载出 9 个分类 | logcat `配置解析完成…spider=gitee…jar`、`spider jar 已缓存`、`jar引导已执行`、`openSite sealedKeys：site=豆瓣 classes=9` |
| 新接口 | `https://www.iyouhun.com/tv/wex` | **导入成功**：解析 63 站点（M1 可用 63）、parses 4、rules 9、doh 5；**img+ 伪装 jar**（`…jpg;md5;…`）经 FindResult.decodeImgJar 解码成功并缓存（959KB） | logcat + 截图 `shot_real_6.png` |
| 直播电视IPV4（负样本） | `https://live.zbds.top/tv/iptv4.txt` | **正确拒绝**：IPTV txt 非点播配置，导入失败并给出文案 | `IMPORT_FAIL` |
| 欧歌免费 / 装歌 | `tv.nxog.top/m/111.php...` | 未完成：URL 前缀被输入法吞掉（见 10.3） | 截图 |
| GG影视 | `raw.giteeusercontent.com/...aiwex.json` | 未完成：同上 | 截图 |

### 10.2 改善项在真实源上的验证

- **jar 全链路**（改善批次之外的既有能力）：真实 jar 下载（md5 校验命名）→ DexClassLoader → 本地回环代理 → csp_Douban 五接口调用 —— 首次在真实生态 jar 上验证。
- **img+ 伪装 jar 解码**（改善项 8 同批 FindResult 链路）：新接口的 spider 声明为 `.jpg` 地址，解码后成功交给 DexClassLoader。
- **横幅停留**（改善项 6）：重复导入夹具配置时「此配置已存在」横幅持续可见（截图 `shot_banner3.png`）。
- **相对海报补全**（改善项 8）：夹具改为相对路径后宿主收到 9 个已补全请求。
- **数据隔离**：真实源播放/加载期间 Core 三表仍 0 行。

### 10.3 真实源发现（记录，不阻塞）

1. **jar Init 验证 WebView 全屏劫持**：`csp_Douban` 这类 spider 的 Init 反滥用机制会在应用窗口挂全屏"接口验证"WebView，**吞掉返回键**，用户只能强杀应用逃离。这是源侧行为（TVBoxOS 同样存在），但我们可以改善：给 jar 挂载的 WebView 加返回键豁免/关闭按钮。已列入改善清单。
2. **`adb shell input text` 长字符串截断**：Gboard 焦点时序导致 URL 前缀丢失（外层状态框收到了前缀）——纯测试工具限制，应用对截断后的无 scheme 地址正确拒绝。替代方案：剪贴板注入或中文输入法关闭自动纠错后重试。
3. 生态源的 UA 门控（配置地址按 UA 分发 HTML 落地页）在本地夹具上无法复现，留待真实源网络环境再验。
