# OwnTV Mobile 项目功能梳理

> **口径**：本文以 `mobile-mini` 分支"精简改造"完成后的**实际代码**为准逐条核对（基线 commit `f0118cd`
> 之上的工作树），不沿用 `extras/` 里既有文档的结论。三项改动（语言收成三种、体育移除、多账户改单档案）
> 的决策与偏差见 `extras/SLIMMING_PLAN.md`。
> 文中路径相对仓库根，行号为核对时读到的位置，代码演进后可能漂移；引用 core 的地方标为 `core:`，指依赖库
> [`tv.own.owntv:core` 1.0.69](https://github.com/ahXN00/OwnTV_Core)（本地对照检出为 `E:/develop/ideaPoject/git/Own/OwnTV_Core`）。
> 面向读者：接手本项目的开发、产品与测试。

---

## 0. 一页速览

| 项 | 结论 |
|---|---|
| 是什么 | OwnTV 家族的**手机/平板端 IPTV 播放器**，触屏优先，不含任何频道或内容（bring-your-own-source） |
| 与电视端关系 | 与 [OwnTV for Android TV](https://github.com/ahXN00/OwnTV) 共用整个引擎：一个数据库、一套同步、一套播放栈；本仓**只有界面** |
| 播放引擎 | libmpv（FFmpeg/mpv，默认）+ Media3/ExoPlayer（直播快启），双引擎自动接管 + 按频道兼容模式 |
| 技术栈 | Kotlin 2.4.20 · AGP 9.4.0 · Compose BOM 2026.09.00 + Material 3 · Koin 4.2.2 · Room/Paging3/DataStore/WorkManager（均由 core 带入） |
| SDK | `minSdk 26` / `targetSdk 36` / `compileSdk 37`；Java 17（`app/build.gradle.kts:37-52`） |
| 规模 | `app/src/main` 下 193 个 Kotlin 文件，其中 `ui/` 167 个；startKoin 注册 12 个模块（本仓 `di/` 9 个 + core 的 core/database/data 3 个）；9 个导航目的地 |
| 本地化 | **只打包与只提供三种语言**：`en-US`、`zh-CN`、`zh-TW`（+ 仅 debug 的伪语言）；白名单在 `i18n/AppLocales.kt`，`localeFilters` 写死同一份限定符；core 侧仍是 45 种目录 |
| 档案 | **单一隐式档案**：无档案列表、无新建/编辑/删除/切换；其他档案留在库里但不显示；门禁是单档案 App 锁 |
| 体育 | 本 App 内**无入口、无后台、无通知**；core 的体育类与文案仍在依赖里（D8），Manifest 用 `tools:node="remove"` 去掉 core 的 3 个体育组件 |
| 许可 | GPLv3 |
| 质量 | 6 个测试类共 34 个用例（`BackupProfilesPolicyTest` 10 + `AppLocalesTest` 7 + `MobileStringsParityTest` 5 + `SettingsSearchCoverageTest` 3 + `StartupLiveSelectionTest` 3 + androidTest 的 `StageFieldTest` 6）；CI 三条流水线（android / i18n / automerge-core-bump）；播放与后台路径**无自动化测试** |

---

## 1. 架构与代码地图

### 1.1 分层

```
Compose 屏（ui/screens/**） 
   ↓ 事件
屏级 ViewModel（*ViewModel.kt） + 屏级 Tuner（LiveTuner / VodTuner，Koin single）
   ↓ 调用
OwnTV_Core：repository / DAO / 引擎 / EPG / 录制 / 下载 / 备份 / 设置
   ↓
Room DB · DataStore · 文件 · 网络（provider / EPG / TMDB / OpenSubtitles）
```

- **状态**：`StateFlow` + `collectAsStateWithLifecycle`；Tuner 是常驻 single，跨导航存活，因此活动档案变化时需要显式重读（见 4.10、8）。
- **DI**：`di/` 九个模块按屏拆分（Home/Library/Live/Guide/Search/Downloads/Player/Settings/Shell），加上 core 提供的 `coreModule`/`databaseModule`/`dataModule`，共 12 个在 `OwnTVMobileApp.kt:161-165` 一次 `startKoin`。`sportsModule` 已随体育模块删除。
- 进程启动时还剩一个跨端开关：图标切换器指向 `MainActivity`。体育时代的 `MatchAlertHost.backgroundAllowed = true` 已不再设置，core 侧因此保持默认 `false`；启动只跑两段收尾：**语言白名单守卫**（把持久化标签折叠回三种，`OwnTVMobileApp.kt:171-177`）与**旧体育闹钟清理**（`MatchAlertScheduler.rearmAll()` + `stopService(LiveMatchCardService)`，`OwnTVMobileApp.kt:184-189`，`runCatching` 兜底，只做一次）。
- **持久化在本仓为零**：数据库、DataStore（`owntv_settings` 与浏览位置 `owntv_positions`）、EPG、备份、下载记录、ForceMpv/AudioOnly 记忆全在 core（`core:settings/SettingsRepository.kt:229,236`）。本仓只写缓存：字幕下载到 `cacheDir/subtitles`、Coil 图像缓存 256MB（`PlayerScreen.kt:283`、`OwnTVMobileApp.kt:111-144`）。
- **本仓有字符串了**：只有手机才说的话写在 `app/src/main/res/{values,values-zh-rCN,values-zh-rTW}/strings_mobile.xml`（26 个 `mobile_*` 键，三份键集一致，由 `MobileStringsParityTest` 守住）；其余一切用户可见文本仍来自 core。

### 1.2 目录

```
app/src/main/java/tv/own/owntv/mobile/
  MainActivity.kt / MainActivityColours.kt / OwnTVMobileApp.kt
  backup/      1 个文件：BackupProfiles —— 备份/同步入站档案的策略（纯函数，可单测）
  cast/        5 个文件：Chromecast 整块（电视端没有）
  di/          Koin 模块
  i18n/        1 个文件：AppLocales —— 三语言白名单与持久化标签归一化
  playback/    PlaybackService · DataSaverGate · PipController
  reminder/    提醒的通知与弹层
  sync/        1 个文件：LocalPlaylistSync —— 本地文件 playlist 的无网络约束重同步
  ui/
    nav/         MobileDestination · MobileNavHost · WindowSize · shared element
    screens/     home live guide library search downloads recordings
                 multiview settings(+customize) + Favorites/History/More
    player/      21 个文件：全屏播放器的全部 chrome 与弹层
    profiles/    单档案 App 锁与 PIN 底座
    categorylock/分类锁 · 设置 PIN · 密封播放
    components/  sheet 宿主、长按菜单底座、移动/分类选择器、导出
    setup/       首启向导（无建档步骤）
    shell/       底部栏/侧栏外壳、页头、同步提示、ShellViewModel（活动档案恢复）
    stage/ theme/ 视觉底座（Stage 组件、调色板/形状/字体/动效映射）
app/src/main/res/values*/strings_mobile.xml  手机端独有文案（三种语言）
tools/i18n/     core 语言目录的对照副本 + 四条字符串校验脚本
baselineprofile/ Macrobenchmark 录制基线画像（CI 不跑）
extras/         品牌资源、字体许可、五份文档（含本梳理与精简方案）、四张截图
```

### 1.3 入口与进程

- **9 个启动 Activity**：`MainActivity`（Eggshell）+ 8 个纯色后缀子类，Manifest 里全部 `android:enabled="false"`，由 core `AppIconSwitcher` 只留一个开启 —— 这是"换 App 图标"的实现方式（`AndroidManifest.xml:96-248`、`MainActivityColours.kt:5-12`）。均为 `singleTop` 且声明 `supportsPictureInPicture` + `configChanges`（PiP 必需）。
- **只有 `MAIN/LAUNCHER`，没有 `VIEW`** → 全 App **无深链**；也**不声明 LEANBACK**（`AndroidManifest.xml:95`、`:11`）。体育的比赛深链（`EXTRA_MATCH_KEY`）随模块一起删除。
- `MainActivity` 继承 `FragmentActivity` 是为了 Cast 设备选择器；主题父类必须是 `AppCompat DayNight`，否则选择器崩（`values/themes.xml:7-20`）。
- App 初始化顺序：`attachBaseContext` 阻塞读语言并 `AppLocales.normalize` → `Perf.begin` → 注入 `CoreBuildInfo` → CrashRecorder → Coil 管线 → startKoin → 语言守卫 + 旧体育闹钟清理 → 图标切换器 → 提醒通知渠道 → 新机默认开启 Stage+玻璃（`OwnTVMobileApp.kt:60-223`）。
- 退到后台自动降为**纯音频**播放（`MainActivity.kt:283-316`）。

---

## 2. 导航与自适应布局

| 目的地（route） | 位置 |
|---|---|
| `home` `live` `guide` `library` | 手机底部栏 4 项（Library 把电影+剧集合成一格；Sports 已随模块移除） |
| `home` `live` `guide` `movies` `series` `downloads` `more` | 平板/横屏侧边 rail 7 项（Settings 不在 rail，靠 More 进） |
| `more` `settings` `downloads` | 二级入口 |

`MobileDestination.kt:28-58`；可见性由 core `NavVisibility` 过滤，`more` 无 section 永不被隐藏。

二级页与规则：

- 详情：`<place>/TAB/itemId`；搜索 `search?q=`；播放器 `player`；向导 `setup?entry=`（`MobileNavHost.kt`）。体育的 `sports/match/{key}`、`sports/team/...` 路由已删除。
- Settings 11 组 + 24 个叶子页；More 6 个叶子页。
- **点当前 tab = 回到顶部**；切 tab 用 `popUpTo + saveState/restoreState` 保住每个 tab 的滚动位（`MobileShell.kt:804-839`）。
- More → 下载/设置故意 **push 而不是切 tab**，否则返回会落到 Home（`MobileNavHost.kt:126-132`）。
- **Shared element**：`Modifier.sharedPoster(key)` 让海报连续放大成详情页头图（`MobileSharedElement.kt:36-47`）。
- **栏数**：≥600dp 用侧栏；屏高 <600dp 走紧凑 rail；**≥840dp 才双栏**（列表 + 预览/详情），只剩 Live 与 Library 两处使用（`WindowSize.kt:19-21`、`LiveScreen.kt:144`、`LibraryScreen.kt:169`）。

---

## 3. 功能域详解

### 3.1 首启向导与内容接入

- 没有活动档案时**整个 App 被向导替换**（不是覆盖层，无法返回绕过）：`SetupFlow`（`MobileShell.kt:139-147`）。向导结束**不再代表"已解锁"**：恢复回来的档案若带 PIN，仍然要先过 App 锁（`SetupFlow(onDone = { _ -> })`）。
- 步骤：WELCOME → DISPLAY_SIZE → DISCLAIMER → CHOICE →（SYNC_DEVICE | ADD_CONTENT → FORM → IMPORTING | RESTORE → IMPORTING）（`SetupFlow.kt:85-87,200-215`）。
- **CHOICE 三选一**：重新开始（现在就加播放列表，或先跳过）、恢复一个备份、从另一台设备同步（`SetupWizardSteps.kt:206-219`）。**建档步骤与"使用其他档案的播放列表"步骤已随多档案管理一起删除**：档案由向导自己落地 —— 导入时由 core 建，什么都不选时 `SetupViewModel.finish` 用 core 的 `setup_default_profile` 名静默建一个并切为活动（`SetupViewModel.kt:199-220`）。
- **向导里的恢复更严格**：不能在恢复前展示重命名提示、也无法可靠回滚，所以凡需要改名的备份一律拒绝并指引"到 Settings → Backup & Restore 里恢复"；多档案、匿名档案、与休眠档案同名的档案同样拒绝，被拒绝的文件不损坏、可重新选择（`SetupViewModel.kt:171`、`SetupFlow.kt:282-289`）。
- **三类源**：M3U（URL 或设备本地 `.m3u`/`.m3u8`）、Xtream、Stalker/Ministra（Portal URL + MAC，可选 Serial/Device IDs/Signature），按类型决定同步范围；导入交给 core `SourceImporter`，可"后台运行"，失败走 Back + Try again（`SetupViewModel.kt:69-104`）。
- **Remote 配对表单**：本机起 HTTP 服务，另一台设备打开浏览器填表并回填本机 —— 免去在小屏上敲长 URL（`SetupViewModel.kt:44-49`、`AddSourcePage.kt:85-102`）。
- 语言选择器只列 `AppLocales.rows`（English / 简体中文 / 繁體中文）+ 跟随系统（`SetupWizardSteps.kt:265-267`）。
- EPG：播放列表自带或自填 XMLTV；`epgOffsetMinutes`、按列表覆盖、自动匹配与复核（见 3.4）。

### 3.2 Home 首页

- core 的 `HomeRow` 有 9 个值，手机端**画 8 个**：`LIVE_SPORTS` 在读取时被过滤掉（`HomeScreen.kt:186-189`），Settings → Home 的行列表同样过滤（`SettingsHomePage.kt:89-92`）—— 旧配置里的这一行**不删除**，降级回旧版仍然读得回来。其余：Trending / Keep watching / 最近频道 / 收藏频道 / 收藏电影 / 收藏剧集 / 继续观看（电影·剧集）；行序与内容全部由 core `HomeFeedReader.load(profileId)` 决定，与电视端逐条一致（`HomeViewModel.kt:119`、`HomeScreen.kt:286-392`、`core:home/HomeFeed.kt:161-268`）。
- **TrendingHero**：满幅 hero 背景由外壳绘制并随滚动淡出；每 10 秒轮播下一条，手指按住或有弹层即暂停；横滑 ±1、长按出菜单、预取下一条背景；按钮为 Play / Open episodes / Trailer / All versions(计数) / ⓘ / ♥，宽度不够时 Trailer 先退位；另一种样式是 Posters（`TrendingHero.kt:148-408`、`HomeScreen.kt:236-250`）。
- 卡片：点击=直接续播（过 `ResumeGate`）、频道=开直播；长按=收藏/下载/隐藏并重建 feed；底部进度条，≥95% 记为看完；空行不画（`HomeScreen.kt:140,423-460`）。
- 可配：Settings→Home 管行开关与排序、Trending 样式；首页两条频道行标题旁可直接切 CARDS↔ON NOW（`SettingsHomePage.kt:67-105`）。首页**不做**行编辑，customize 只供改名/隐藏/上锁。
- "All versions"带同名条目计数并跳搜索页；Keep watching 副文案形如"S1·E2 · 剩 31 分钟 / 上次观看 8 分钟前"（`HomeText.kt:33-57`）。

### 3.3 Live TV

- **分类条** = 内置项（All / Favorites / History / Catch-up）+ provider 文件夹 + 用户自建分类；Catch-up 仅当有内容且内置项开关打开才出现；隐藏/改名/移出原文件夹在 Paging 链里二次过滤（`LiveViewModel.kt:210-241,452-464`）。
- 分页：pageSize 60、maxSize 400、无占位；排序 Provider（播放列表序）↔ A–Z 一键切换，行内可手动拖序（`LiveViewModel.kt:365-443,745`）。
- **预览**：点行=静音预览（ExoPlayer），再点=全屏；预览不写 History；平板/宽屏为双栏，右栏初始为空不自动播（`LiveScreen.kt:156,319-373`）。
- **时移**：拖拽/跳档/一键回到直播边缘，跳转档位与归档窗口由 core `LiveTimeshift` 给出；本地缓冲可续看 `timeshiftResumeAt`（`LiveTuner.kt:472-508,1013-1123`）。
- **时回看（catch-up）**：从节目单回放 `playCatchup`，播完按 `CatchupContinue` 接下一档；无归档 URL 会明确 Toast；"Start of the programme"入口在播放器回看弹层（`LiveTuner.kt:942-1005`）。
- 频道长按菜单 14 项：catchup / 录现在（再点停止）/ 计划录制 / 加入 Multiview / 外部播放器 / 收藏 / 改名 / 隐藏 / 移出历史 / 手动匹配 EPG / 时偏 / 移动 / 移入分类 / 移出分类（`ChannelActions.kt:120-173`）。
- 数字直接换台（按 provider 号，当前列表优先，多命中报歧义）；±台在 500ms 内连按做防抖（`LiveTuner.kt:671-683,1058-1082`）。
- **与 Guide 双向联动**：进 Guide 时沿用 Live 记住的分类；在 Guide 里选分类会**立刻回写** Live 的记忆（`GuideScreen.kt:259-263,414-417` ←→ `LiveViewModel.kt:583-589`）。Live 的 "Guide view" 借用 Guide 的网格但用自己这份列表（`LiveScreen.kt:236-297`、`GuideViewModel.kt:229-235`）。

### 3.4 TV Guide 与 EPG

- **网格**：共享 `timeScroll` 横向滚动；密度 100% 时 2 小时铺满宽度（可调 70–130%）；半小时刻度 + "现在"高亮线；今天窗口起点为"回退 1 小时的半点"，使 now 线居中；入场自动定位（`GuideGrid.kt:112-167,482-500`）。
- 逐行读一天（LRU 缓存 240 行），行内空档渲染 GapCell（`GuideViewModel.kt:471-498`、`GuideGrid.kt:276-299`）。
- **三种视图**：Grid / On now / Timeline，存 `guideView`（`GuideViewModel.kt:284-289`）。
- `GuideMatchUi` 的名字有迷惑性：它**不是节目搜索**，而是 **EPG 匹配复核**——`EpgReviewSheet` 列出 Auto-match 结果与置信度%，可 Accept / Skip / Accept all，并带"连台标一起采纳"开关；另有频道长按的 GUIDE DATA 菜单和匹配结论行。真正的搜索在 `GuideScreen.kt:296-316`（按频道名）（`GuideMatchUi.kt:71-243`）。
- 从节目单可触发：Watch channel / Watch from start / Play catch-up in… / Record / **Record every showing**（系列规则）/ 收藏 / 手动匹配 / 时偏；未来节目才有 Remind me；录制冲突先提示（`GuideScreen.kt:721-1047`）。

### 3.5 提醒（reminder/）

- 三种模式：**ASK** = 应用内底部弹层（Watch channel / Dismiss，由外壳绘制）；**SWITCH** = 自动换台；**NOTIFY** = 只发通知；若在后台或退出时还有未答提示，会补发通知（`ReminderPrompt.kt:36-73`、`MobileShell.kt:692-702`、`ReminderNotifier.kt:56-84`）。
- 通知渠道 `owntv_reminders`、IMPORTANCE_HIGH、点击拉起 App；唤醒用 AlarmManager 精确闹钟（`SCHEDULE_EXACT_ALARM`，core 清单声明），默认提前 5 分钟（`ReminderNotifier.kt:40-118`、`GuideViewModel.kt:669`）。
- 设置提醒时按需申请 `POST_NOTIFICATIONS`（Android 13+），**拒绝仍保留应用内提醒**（`GuideScreen.kt:786-803`）。
- 被分类锁封住的频道：提醒既不发通知也不弹窗，保持到期状态（`ReminderNotifier.kt:56`、`MobileShell.kt:677`）。

### 3.6 录制

- 列表与电视端同一张表（core `recordings.observe(pid)`），被锁分类的记录隐藏；分组 Now / Scheduled / Completed / Failed+Missed（`RecordingsViewModel.kt:80-169`）。
- **入口不是独立 tab**：Recordings 作为 Downloads 屏的 "Live TV" 标签页嵌入（`RecordingsScreen.kt:50-57`、`DownloadsScreen.kt:210`）。
- 计划录制：今天起 7 天、手输 HH:MM 起止、跨天自动顺延、冲突只警告不阻止（`ScheduleRecordingSheet.kt:67-182`）；系列规则 `addSeriesRule`（"每场都录"）。
- 录制走**独立引擎**而非 mpv stream-record（代码注释说明 HLS 下原方案完全失效）；`canRecordOn` 先问连接预算（`LiveTuner.kt:120-200`）。
- 成品经 `VodTuner.playRecording` 播放（**有意不写历史、不存进度、不带字幕上下文**）；删除二次确认；"导出"实为**移动**（复制 → 持久授权 → relocate → 删原文件）；文件丢失会被检测（`RecordingsViewModel.kt:66-113`、`VodTuner.kt:392-405`）。
- 录像观看/下载与直播争用同一路流时，选择项为 ASK / PAUSE / KEEP（`DownloadsViewModel.kt:267`）。

### 3.7 Multiview 多画面

- 上限 **4 格**（core `MAX_MULTIVIEW_TILES`），默认 2；需 `multiviewEnabled`；每格一个引擎实例（`LiveEnginePool`，默认静音）（`MobileMultiviewState.kt:25-72`、`PlayerScreen.kt:187`）。
- 布局：横屏每行 2 格、竖屏每行 1 格堆叠；每格用 **TextureView**（硬件平面只有一个，SurfaceView 会打架）（`MultiviewScreen.kt:120-327`）。
- 音频：单格出声，点空格=把声音给它；`soundOnly` 可只要声音不要画面（仍占一路连接）；某格失败则静音并把声音转移（`MobileMultiviewState.kt:93-138`）。
- 两条入口：Live 长按"Add to Multiview"攒够后开格；播放器 HUD 以当前频道为 1 号格。打开前先停两个引擎避免双声道；`ON_STOP` 退出，期间 keepScreenOn（`PlayerScreen.kt:228-256,780-803`）。
- **明确缺口**：Multiview 里"录制这一格"是故意没做的（`MultiviewTileSheet.kt:17-18`）。

### 3.8 影音库（Movies / Series）与详情

- 分页：Paging3，`pageSize 60 / prefetch 30 / maxSize 300 / 无占位`；网格随滚动续页（`LibraryViewModel.kt:965`）。
- **分类面板**：All · Favourites · History + provider 文件夹（解析名 + tag、计数、锁/sealed 时不计数、长按出菜单）；隐藏类目与成人类目会过滤（`VodCategoriesSheet.kt:37-108`）。
- 可调项：排序 `PLAYLIST/ALPHA/RATING/DATE_ADDED`、Grid|List、海报尺寸条、**双指缩放列数**（列数唯一写入者就是手势，0 表示交回屏幕自适应）（`LibraryScreen.kt:470-578`、`LibraryViewModel.kt:179-188,632`）。
- 角标：评分、playlist mark、✓已看、底部进度条（剧集取最新一集）；列表行显示季数（`LibraryScreen.kt:307-418`）。
- 电影页：420dp backdrop + TMDB logo + 面包屑"Movies · 分类" + 元信息（年·类型·时长·★·画质）+ 剧情 + Play/Trailer/⋯ + 收藏/下载/All versions + 演职员 + "More in 分类"（`MoviePage.kt:89-282`）。
- 剧集页：380dp backdrop + Next-up 主按钮 + ♥ + 整季下载 + 季 tab（带集数，Specials 置后）+ Grid|List + 集列表；默认停在**最后看过的季**；集可双向排序/隐藏已看；单集长按菜单（`SeriesPage.kt:128-681`）。
- 详情页动作（VodMenu）：下载/重试/删除、移除历史、标记已看、隐藏、Move/Move-to、TMDB 详情、重取 TMDB、手设 TMDB 名、删字幕、外部播放（`VodActions.kt:131-288`）。
- 字段优先级：本地 provider 数据与 TMDB 记录按 `tmdbWins` 决定谁胜出；进度：电影 progress、逐集 episodeProgress、≥95% 视为完成、nextUpId（`DetailViewModel.kt:125-285`）。
- `VodTuner` **不是设置面板**，是 VOD 播放控制器（LiveTuner 的孪生）：`playMovie/playEpisode`（整季排队 + 自动续播）/`playDownload`/`playRecording`/`playExternal`/`stop`/`saveProgress`（每 5 秒）/Cast 交接；受外部播放器设置、省流量闸、类目锁约束（`VodTuner.kt:212-527`）。
- **档案变更跟随**：ViewModel 比"当前档案是哪一行"活得久（界面上已无档案可切，但一次恢复/同步可以把 `activeProfileId` 指向另一行），因此 `init` 里按 `profileId` 变化重读记忆分类；库里没有那个文件夹就回落到 All（`LibraryViewModel.kt:634-666`）。

### 3.9 搜索

- 范围：频道、EPG 节目、电影、剧集，检索由 core `SearchReader` 完成；≥2 字符、300ms 防抖（`SearchViewModel.kt:68-154`）。
- 结果分组：LIVE TV（前 4 + "All n channels"）/ ON TV / MOVIES / SERIES；`kinds` 多选筛选（全不选=全选）；JUMP TO：Continue / Unwatched / Channels（`SearchScreen.kt:194-342`）。
- 历史只在**真正打开结果**时写入，可一键清空；搜索页自身"只展示，不播放不编辑"，长按无动作（`SearchViewModel.kt:42,146`）。
- **点频道结果 = 打开 Live TV 并落在该频道所在文件夹**：先 `startupLive.requestReveal(channelId)`（一次性句柄），预览开着则 `tuner.preview`，否则先切 tab 再全屏播；Live 侧把选中分类设为该频道的文件夹（**仅显示，不写记忆**）、清掉 Live 搜索框并滚动定位（`MobileNavHost.kt:255-279`、`LiveViewModel.kt:545-568`、`LiveScreen.kt:218-573`）。

### 3.10 下载与离线

- 创建入口三处，均调 core `DownloadManager.enqueue`：库内长按整剧（全部集）、详情页电影/单集/整季、Home/Search 短菜单；批量前用 `BulkDownloadConfirm` 报集数（`LibraryViewModel.kt:738`、`DetailViewModel.kt:394-437`、`ContentActions.kt:81`）。
- 队列、并发、断点续传、Wi-Fi only 策略**全在 core**；手机端只做排序与 "starts after" 文案，pause/resume/retry/delete 是纯转发；PAUSED/FAILED 保留部分文件（`DownloadsViewModel.kt:274-358`、`DownloadsScreen.kt:167-443`）。
- 存储：`MediaFolders.MOVIES/SERIES/TV`，剧集带 `seasonDir`；文件名 `StorageAccess.sanitize(name).ext`（优先 containerExt）；根目录可选 App 私有目录 / 可插拔卷 / SAF 目录，持久化授权与回收由手机端管理（`LibraryViewModel.kt:976-985`、`DownloadsViewModel.kt:204-254`）。
- 离线播放：`VodTuner.playDownload` **完全不问 provider**，继承进度，并做儿童档案与分类锁校验（`VodTuner.kt:356-465`）。
- "Save a copy" 同样是**移动**而非复制（改写行的 filePath），配套 `ExportDocument` 申请可持久授权（`DownloadsViewModel.kt:322`）。
- **本仓没有下载通知**，只有播放媒体通知；队列通知归 core（`AndroidManifest.xml:258`）。
- `DataSaverGate` 只拦**直播流**（开流前判断计量网络并 Toast 拒绝），不管下载（`DataSaverGate.kt:32-47`）。

### 3.11 播放器

- 手势（单循环识别器）：左列亮度、右列音量、横向 scrub、中部下滑停靠/上滑换台、双指捏合缩放、双指单击静音、长按 2×、左右双击跳过；HUD 分 Level/Zoom/Speed/Muted/Skip 五类（`PlayerGestures.kt:38-154`、`PlayerGestureHud.kt:54-106`；灵敏度 `gestureSensitivityPct` 可调）。
- 控制条：玻璃 Dock + TransportCapsule + 速度键 + **EngineToggle（MPV↔EXO 一键切换）** + PlayerToast；进度条拖动加高（`PlayerChrome.kt:155-347`、`PlayerSeekBar.kt:60-76`）。
- 弹层（`PlayerSheets.kt:112-369`）：音量、亮度、字幕、音轨、画质、倍速（0.5–2.0）、信息、频道、回看、睡眠定时；字幕时延；**AV 同步 25ms 步进**。
- 其他弹层：同频道多版本切换（Sources）、"Go back to…" 墙钟时间轴（Catch-up）、**OpenSubtitles 搜索**（语言过滤、下载即挂载、401/406 错误映射）（`PlayerSourcesSheet.kt:20`、`PlayerCatchupSheet.kt:41`、`SubtitleSearchViewModel.kt:24-182`）。
- 断点续播策略 `ResumeGate`：10 秒门限，Ask / Auto / Never（`ResumeGate.kt:22-63`）。
- 纯音频模式：背景 + 波形 + 睡眠弹层，音量可到 150%；可在熄屏或离开 Wi-Fi 时自动进入，并按频道记忆选择（`AudioOnlyStage.kt:85-296`）。
- 迷你播放：停靠式（tab 之上）与**可拖拽悬浮窗**两种；PiP 由 `PipController` 上报 videoBounds 与动作（`MiniPlayer.kt:54`、`FloatingMiniPlayer.kt:110-400`、`MainActivity.kt:187-251,385-418`）。
- 剧集：下一集倒计时卡片；下载争用流提示；错误面板；Multiview 网格在同一个 Player 宿主里接管（`PlayerScreen.kt:228-367,858-1024`）。

### 3.12 投屏（Chromecast，手机端独有）

- 唯一入口 `CastController.offer(owner, request)`：返回 true 表示电视已接走、**本地引擎不得启动**；已有会话则接管（`releaseToCast()`）；会话断开/挂起把手流还给手机；无 Google Play 服务则整块静默不可用（`CastController.kt:44-150`）。
- `CastPlaybackEngine` 让接收端**伪装成一个 PlaybackEngine**，于是锁屏、媒体键、通知不需要第二套实现；轨道/缩放/字幕为 no-op，音量作用于电视；接收端不支持时返回 `CastUnsupported` 且**不回退本地**（`CastPlaybackEngine.kt:20-192`）。
- 三类确定拒投：带每请求头、裸 `.ts` 流、DRM；User-Agent 会被静默丢弃（`CastRequest.kt:12-41`）。
- 接收端用 Google 免费默认接收器，换接收端只改一行（`CastOptionsProvider.kt:13-47`）；入口按钮是平台原生 `MediaRouteButton`（因此需要 FragmentActivity + AppCompat 主题）。
- 交接细节：交出去时停两个引擎并撤掉回退阶梯；回来时直播重开、**时回看从节目头重播**；投屏期间预览点击直接当换台，**时移与回看控件停用**（`LiveTuner.kt:608-1212,1106-1114`）。

### 3.13 后台播放与系统集成

- `PlaybackService`：前台服务类型 `mediaPlayback`，渠道 `owntv_playback`、通知 ID 4301，`Notification.MediaStyle` + core `PlaybackSession.token` 提供锁屏与进度；±10 秒、纯音频与停止动作；`START_NOT_STICKY`，Recents 划走即停（`PlaybackService.kt:133-304`）。
- 内置更新器：启动或 Settings→App 检查 Releases 新版本，可下载并安装，因此声明 `REQUEST_INSTALL_PACKAGES`（代码注释明确：若上 Play 必须连同该设置项一起撤）（`AndroidManifest.xml:13-19`、`SettingsGroupRows.kt:646-654`）。
- CrashRecorder、`Perf` 打点、`measuredStreamStats` / `detailedDiagnostics` 两行诊断（`OwnTVMobileApp.kt:106-107`、`SettingsGroupRows.kt:691-697`）。
- core 的体育告警组件（`MatchAlertReceiver` / `MatchAlertBootReceiver` / `LiveMatchCardService`）在清单里被 `tools:node="remove"` 摘掉，手机不再注册任何比赛提醒闹钟；`MatchAlertHost.backgroundAllowed` 保持 false，于是 `rearmAll()` 的行为是 `cancelAll()`（`AndroidManifest.xml`、`OwnTVMobileApp.kt:186-187`）。

### 3.14 档案、门禁与权限矩阵

**档案模型：一台手机一个档案，而且界面上不再出现"档案"这件事。** `ShellViewModel.recovering` 在 Room 与 `activeProfileId` 都回答之前撑住空白帧，并把存储的 id 指回一行真实档案；`MobileShell` 拿到结果后只有三种画面：没有可用档案 → `SetupFlow`；有档案且 `pinHash != null` 且本进程未解锁 → `ProfileGate`；其余 → 正常 App（`MobileShell.kt:116-164`）。More 顶部那张卡只是被动地显示头像、名字与当前 playlist/EPG 状态，**没有切换、新建、删除入口**（`MoreScreen.kt:108-110,168`）。库里多出来的档案保持原样躺在磁盘上、不再被展示，也不会被自动删除（D3）。

四道相互独立的门禁：

| 门禁 | 触发条件 | 覆盖范围 | 实现 |
|---|---|---|---|
| **App 锁（ProfileGate）** | 唯一档案的 `pinHash != null`——在 Settings→App→App 锁里设 | 整个 App：以"替代 App"的方式渲染，无返回手势可绕过；5 次失败后 30 秒冷却；校验跑在 `Dispatchers.Default`；会话是 ViewModel（旋转保活、进程死则失效）；**没有"忘记密码"通路**，忘了只能清 App 数据 | `ProfileGate.kt:52-196`、`ProfileGateSession.kt:9-36`、`MobileShell.kt:153-164` |
| **分类锁（CategoryLock）** | 给 provider 文件夹/自定义分类设 PIN | 该分类的列表与菜单动作（`guarded()` 把动作挂起等 PIN 通过后执行）；仅 Folder/Custom 可锁；被锁分类不计数、不出现在列表、提醒不发 | `CategoryLock.kt:33-103`、`LiveViewModel.kt:144-254` |
| **设置 PIN（SettingsPinGate）** | 打开 Settings 或其任一子页、More 里的 Backup / Local sync | "一次访问"粒度：离开即重新上锁；取消则退出该页 | `SettingsPinGate.kt:20-66`、`MobileNavHost.kt:368-375` |
| **密封播放（SealedPlayback）** | 分类被封锁时 | 停掉被密封分类里的直播、点播与 Multiview 单格，`stopped` 触发播放器页 pop | `SealedPlayback.kt:16-49`、`MainActivity.kt:151-152` |

**一把 PIN三处通用**：App 锁、分类锁、设置门读写的是同一个 `ProfileEntity.pinHash`；`ProfileManager.edit(...)` 是唯一写入口、`verifyPin()` 是唯一校验口，`SettingsViewModel` 的 App 锁与儿童模式两行都走它（`SettingsViewModel.kt:128-162`）。PIN 4–6 位（`ProfileSheets.kt:25-26`），输入框渲染后取消 autofill 会话以防密码管理器保存；档案编辑器、档案选择器、改名/换头像表单整体删除，`ProfileSheets.kt` 只剩 `ProfilePinSheet`。

能力矩阵（只有一个档案，所以没有"档案类型"这一列）：

| 能力 | 默认 | 关掉之后 | 存储与入口 |
|---|---|---|---|
| 儿童模式 | 关 | 成人类目被 `AdultCategoryClassifier` 过滤（Home/Library/Live/Guide 全链路） | 全局开关，Settings→Content→Kids Mode；位仍写在档案行的 `isKids` 上，**关闭时若设了 App 锁要先问 PIN**（D4，`SettingsGroupRows.kt:533-555`） |
| 下载 | 允许 | Home 与详情页的下载动作整块消失（`onDownload = null`） | 位仍按档案存储，开关随档案编辑页一起搬到 Settings→App→Downloads（D5，`SettingsGroupRows.kt:670-690`、`HomeScreen.kt:126`、`MoviePage.kt:138`、`SeriesPage.kt:186`） |
| 备份/恢复 | 7 个分区可选，锁定需 PIN | — | 只导当前档案；容器里多于一个档案直接拒（见 3.17） |
| 提醒 | ASK/SWITCH/NOTIFY | 被锁频道不发提醒 | 体育提醒已随体育模块撤除，见 4.2 |
| 设置 | 全量 | 可被设置 PIN 拦住 | — |

其它仍然成立的约束：档案名唯一（备份与同步按名合并），而且**休眠档案的名字也占位**——当前档案改名撞上库里另一个档案的名字会被拒并提示原因（`BackupProfiles.kt`）。头像不再有选择界面：Setup 建档案时写死 `avatarId = 0`，`StageChrome.kt:547-548` 只在有图时绘制。

### 3.15 设置体系

- **结构**：11 个组 + 24 个叶子页 + More 6 页（`SettingsCommon.kt:39-50`、`SettingsLeaf.kt:16-47`）。原来的 Profiles 组随多档案界面一起删除，`SettingsProfilePage` 不复存在，其成员各归其位：App 锁 → 新页 `SettingsAppLock.kt`，儿童模式 → Content，允许下载 → App 的 Downloads 段。
- 11 组：Quick / Sources / Appearance / Layout / Content / Player / Picture / Sound / Live / Watching / App。要点抽样：
  - Player：`liveEnginePreference`、`vodEnginePreference`、按列表覆盖、已记住解码、外接播放器、`vodBufferSecs`、超时与重连、`mobileDataMaxVideoHeight`、`dataSaver`。
  - Picture：`hwDecoding`、`softwareDecodeAbove1080p`、`preferSdr`、`maxVideoHeight`、`tunneledPlayback`、`defaultZoom`、`autoFrameRateFilms/Live`（Android 12 以下需确认）。
  - Sound：`defaultVolume`、`surroundMode`、`audioPassthrough`、FFmpeg 音轨（需重启）、`nightMode`、`volumeLevelling`、`audioDelayMs`、首选音/字幕语言。
  - Live：`directTune`、预览开关与声音、时移开关/窗口/续看模式、`liveLatencyMode`、`livePrerollSecs`、`liveTuneTimeoutSecs` + 三张按列表覆盖页。
  - Watching：跳过步长、直播回退步长、`resumeMode`、`autoPlayNext`、手势灵敏度、Multiview 开关与格数、迷你窗样式、PiP 开关/尺寸/吸附、后台播放、熄屏/蜂窝/按频道纯音频、录制预留连接、蜂窝录制、"录我正在看的"、前/后延展。
  - Content：Kids Mode 开关（全局，关时要过 App 锁）、Customize、Metadata、OpenSubtitles（`SettingsGroupRows.kt:522-581`）。
  - App：语言、**App 锁（新叶子页 `app-lock`）**、App 图标、品牌三角、启动落点（HOME / LAST_CHANNEL / LAST_CHANNEL_GUIDE / LAST_CHANNEL_LIVE / FAVORITES / SPECIFIC_CHANNEL，选"指定频道"时再弹一个频道搜索表）、立即检查更新、开机检查更新、代理、DNS、Downloads 段（允许下载 / 下载目录 / `downloadsWifiOnly`）、诊断两行（`SettingsGroupRows.kt:586-698,783-790`）。**体育一行都没有。**
  - 语言页只有"跟随系统 + 三种语言"四行：覆盖率、Weblate、请求新语言的入口随本仓字符串迁移一起撤下（`SettingsLanguagePage.kt`）。
- **设置搜索**：`SettingsScreen.kt` 读与页面同源的 `allGroupSections`，叶子页行靠手写表 `LEAF_ROWS`；守护测试要求每个词必须命中条目名（`SettingsSearchCoverageTest.kt`）。搜索源里已经没有体育与档案管理的词。
- **Quick**：长按任意设置行 → Pin / Unpin / 上下移，存 `quickPinnedKeys`；键与电视端同名，通过 `QUICK_ALIASES` 映射，TV 专属遥控项在手机端不绘制（`SettingsQuick.kt:11-13,25-132`）。
- **语言行的显示值**由 `AppLocales.rows` 决定，因此"跟随系统"与三种语言之外的任何存储值都只会显示成真正生效的那一行（`SettingsCommon.kt`/`SettingsGroupRows.kt` 的 `localeLabel`）。
- **连接预算**：core 会实测 provider 实际允许的并发路数并在拒绝前提示（README 亦声明；手机端在 `canRecordOn`、`OpenStreamRegistry` 路径消费）。

### 3.16 内容定制（Customize）

隐藏分类/条目、改名（单条 + 批量 + 自动清洗）、排序（顶/上/下/底 + 区间移动）、自定义分类（新建/移入）、"新分类默认隐藏"、页面 PIN 门、标题条显示分类计数；持久化为 DataStore 里的 JSON（**不是 Room**）（`customize/`、`core:customize/CustomizationStore.kt:119`）。

影响面（改了就重建）：Home、Library/详情、Live、VOD、Guide、Downloads、提醒、长按动作。

### 3.17 备份恢复与本地同步

**格式、协议、数据库 schema 一律没动**（D6/D9）：`.own` ZIP 容器、`OWNTVBK1` 加密包、`profiles` 数组、按名合并的语义全都与电视端一致，手机写的包电视照样能读、电视写的包手机照样能读。改的只有"这台只保留一个档案的手机接受什么"。

- **Backup & Restore**：粒度 = **当前档案** × 7 个分区（SOURCES / CUSTOMIZE / FAVORITES / HISTORY / RESUME / MANUAL_REORDER / SETTINGS）；导出侧 `exportedProfileIds()` 只返回 `activeProfileId`，没有档案勾选步骤，UI 直说"这份备份只含当前档案"（`BackupViewModel.kt:213-214`、`SettingsBackupPage.kt:461` + `mobile_backup_single_profile_note`）；密码加密导出、恢复按类勾选、**与电视端备份包完全互通**（含加密包）都不变。
- **恢复前的档案判定（`backup/BackupProfiles.kt`）**：先 `read()` 探容器（`BackupContainer.probe` → 加密 / 普通 / legacy JSON / 未知；读不出来返回 null，意思是"这不是该由我们拦的理由，交给 core 报错"），再 `decide()`：

  | 情形 | Settings 里的决定 | Setup 里的决定 |
  |---|---|---|
  | 多于一个档案 | 拒（`mobile_multi_profile_import`） | 拒 |
  | 档案没有名字 | 拒（`mobile_nameless_profile_import`） | 拒 |
  | 名字撞上库里**休眠**档案 | 拒（`mobile_hidden_profile_import`）——改名会让两份数据事后看不出来自同一台机器 | 拒 |
  | 名字与本机不同且勾了 SOURCES | **先告知、后改名**：把本机档案改成进来的名字，应用失败则回滚（`renameDeviceProfile` / `restoreProfileName`，回滚跑在 `NonCancellable` 上） | **直接拒**并说明"去 Settings › Backup & Restore 恢复，那里能确认改名"（`mobile_restore_from_settings`） |
  | 名字相同 / 没勾 SOURCES / 本机没有档案 / 摘要读不出 | 放行 | 放行 |

  改名前先 `ProfileDao.getById` 重读整行，避免用旧实体覆盖掉工作表打开期间被别处设好的 PIN（`BackupProfiles.kt:119-131`）。策略本体不依赖 Android，由 `BackupProfilesPolicyTest` 十条用例守着。另有两条 PIN 提醒：密封包与同步来的档案可能带 PIN，恢复/同步之后**开 App 的 PIN 会变**（`mobile_restore_pin_notice`、`mobile_sync_pin_notice`）。
- **Local sync**：同 Wi-Fi 设备直连（发现/扫码/手输地址 → 6 位 PIN → 方向 Send/Receive/Merge → 勾选分区 → **干跑预览**列出源/收藏/历史/续播/排序/设置/删除的每一项改动 → 结果）。同步的是当前档案，接收/合并走**同一套 `BackupProfiles.decide`**，拒绝文案换成 `mobile_*_profile_sync`，改名同样在干跑里预告并可回滚（`LocalSyncViewModel.kt:286-288,350-365,398-401`、`SettingsLocalSyncPage.kt:552-598`）。区别只在：备份走文件与离线合并，同步走实时传输、无账号无云。QR 只带地址不带 PIN；已配对设备不再要 PIN。
- **Error log**：`PlaybackErrorLog` 条目（EVENT/ERROR/REPORT + 引擎 + 直播/VOD + 流地址 + 机型与 Android 版本），可导出可清空（`SettingsAppPage.kt:174-283`）。
- **About**：版本号、许可文字与上次检查时间、Telegram QR、GitHub 与语言入口、检查更新。**没有开源依赖清单页**。

### 3.18 外观：Stage、玻璃、字体、动效

- 颜色全部来自 core `OwnTVPalette`，手机端只做 M3 映射；视频上层强制暗色角色（`MobileColors.kt:41-118`）。**全 App 未使用 Android 12 DynamicColors**；`res/values-v31` 与动态取色无关，只是把 9 个图标色换成动画闪屏图 + 底部 wordmark。
- Squircle 连续曲率圆角、Sheet 28dp/16dp 边距、正文 14sp / 标题 20sp、6 套可变字体；玻璃背景手机默认 51% 不透明度；氛围光仅在"纯暗色 + 玻璃关闭"时出现。
- 字体：界面 60–140%、弹窗 50–120%（硬上限在 core `FontCustomization.kt:21-47`）；`uiZoomPercent` 50–150%，低于 LOW_RAM_WARN 需确认；系统 `fontScale` 与应用缩放**相乘**（`MobileTheme.kt:91`）。
- 动效分 spatial/fast，关闭动画时改为 snap；注释说明因 material3 1.4.0 的 `MotionScheme` 仍是 internal，Material 自带组件继续用系统动效（`MobileMotion.kt:22-38`）。

### 3.19 多语言、RTL 与无障碍

- **白名单在代码里，不在目录里**：`i18n/AppLocales.kt` 是这个 App 唯一的语言真源——`shippedTags` = `en-US` / `zh-CN` / `zh-TW`，`shippedQualifiers` = `en` / `zh-rCN` / `zh-rTW`，`rows` 给选择器用，`normalize(tag)` 把任何输入折成三种之一或 `""`（跟随系统），`isShipped()` 供守卫判断。匹配先按完整 tag（含伪语言）大小写无关命中，再把 `en` / `en-*` 归到 `en-US`，其余一律 `""`（`AppLocales.kt`）。**`tools/i18n/locales.json` 里那 45 个条目不再参与打包**，App 构建不读它，只剩 CI 拿它跟 core 对账（见 6.2 的偏差）。
- **构建侧**：`app/build.gradle.kts` 里 `androidResources.localeFilters` 直接写死三个 qualifier（`en`、`zh-rCN`、`zh-rTW`），不再从目录文件生成；只在 debug 变体上追加 `en-rXA` / `ar-rXB` 两个伪语言。
- **四个归一化入口**（任何一条漏了都会出现"存着 xh、界面写英文"）：`OwnTVMobileApp.attachBaseContext` 与 `onConfigurationChanged`（`AppLocale.applyGlobally` + `wrap`）、`MainActivity.attachBaseContext` 与 `LocalizedContent`（Compose 侧 `wrapForCompose`）、以及两个跑在 App 之外的上下文 `PlaybackService:150` 与 `DataSaverGate:36`。另有一条守卫协程把存储里不合规的 tag 直接改写归一化结果（`OwnTVMobileApp.kt:172-176`）。**跨书写系统时仍会 recreate Activity 一次**（`LocalizedContent.kt:36-44`）。
- **选择器与计数都读 `AppLocales.rows`**：Settings→App→语言页只有"跟随系统 + 三行"，覆盖率数字是 `AppLocales.rows.size`（`SettingsLanguagePage.kt:48-52`、`SettingsAppPage.kt:159`）；Setup 第一步同源（`SetupWizardSteps.kt:265-267`）；设置行上的当前语言标签由 `localeLabel()` 从 `rows` 里查，查不到就显示"跟随系统"，因此**存储值与本构建实际渲染的语言永远显示同一行**。页面上的 Weblate / 请求新语言 / 覆盖率进度入口已删除。
- **本仓第一次有了自己的字符串**：`res/values{,-zh-rCN,-zh-rTW}/strings_mobile.xml` 各 26 个 `mobile_*` 键 = 25 个 `<string>` + 1 个 `<plurals>`（门禁冷却那句带计数，项目的 lint 把 `PluralsCandidate` 设为 fatal，用 `<string>` 会被拦）（体育撤除、单档案、App 锁、三种语言、拒绝文案这些 core 里没有的话术）。三份的键集由 `MobileStringsParityTest` 守着，缺一个就红。除此以外的用户可见文本仍然全部来自 core AAR——改它要去 core 仓。
- `tools/i18n` 四条校验脚本（硬编码串、数字本地化、文本溢出、伪语言），`verifyI18nLiterals` 挂在 `preBuild`；硬编码串的门禁现在也覆盖本仓新增文件（`safe_literals.txt` / `hardcoded_baseline.txt` 的 ratchet）。
- RTL：`LocalLayoutDirection` 注入；进度条强制 LTR；箭头图标镜像；弹层按方向判断（`LocalizedContent.kt:42`、`PlayerSeekBar.kt:326`、`MobileIcon.kt:35`）。打包的三种语言都是 LTR，RTL 通路保留是因为 core 的 locale 机制仍可能带进来阿拉伯语等资源。
- 无障碍：**没有 TalkBack 专属逻辑**，仅靠 `role` / `onClickLabel` / 图标语义标签，长按含义同时写进可访问名；**每个手势都另有控制条按钮**，手势从来不是唯一通路（`PlayerGestures.kt:36`）；触屏优先，不声明 leanback；TV 的遥控交互在手机端被替换为箭头按钮等触屏控件（`SettingsHomePage.kt:154-175`）。

---

## 4. 权限、数据与外部服务

### 4.1 Android 清单权限

| 权限 | 用途 | 备注 |
|---|---|---|
| `INTERNET`、`POST_NOTIFICATIONS` 等 | 基础 | 由 core 清单合入 |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 后台播放前台服务 | Manifest:34 |
| `REQUEST_INSTALL_PACKAGES` | 内置更新器安装 APK | Manifest:19；上 Play 需连同设置项删除 |
| `CAMERA`（`uses-feature` required=false） | 扫 Local sync 二维码 | 可拒绝，拒绝后仍可手输地址 |
| `SCHEDULE_EXACT_ALARM` | 提醒精确闹钟 | core 清单声明；手机端的**频道**提醒照用，core 的比赛提醒闹钟不注册（见下方合并规则） |
| `ACCESS_COARSE_LOCATION` | **注释写"为天气"** | 手机端代码**零** weather 引用，天气属电视端；见 6.1 |
| `WRITE/READ_EPG_DATA` | 被 `tools:node="remove"` 强制移除 | 手机端不使用 TV Provider（Manifest:27-30） |

其余：`provider` 有 WorkManager 自动初始化移除（由 App 供 Configuration）、`FileProvider ${appId}.share`（备份分享）、Cast `OPTIONS_PROVIDER`。运行时按需申请的还有通知权限。

**体育相关的清单合并**：core AAR 会带进 `MatchAlertReceiver`、`MatchAlertBootReceiver`、`LiveMatchCardService` 三个组件，手机端用三条 `tools:node="remove"` 把它们的**清单声明**整体摘掉，于是既没有入口、也没有任何注册过的后台；类本身仍在依赖里（D8，见 §7 第 24 条），release 包由 R8 决定去留。**没有新增也没有删除任何权限**（`AndroidManifest.xml:263` 附近）。

### 4.2 外部服务与数据流向

| 服务 | 用途 | 凭据位置 |
|---|---|---|
| 用户的 Xtream / M3U / Stalker 源 | 频道、影片、剧集流与元数据 | 本地 Room（core） |
| XMLTV EPG（列表自带或自填） | 节目单、回看、匹配 | 本地 |
| TMDB | 海报/背景/简介/演职员/预告片 | `tmdbApiKey`（edge key，可自填）+ 服务器代理 |
| OpenSubtitles.com | 字幕搜索与下载 | 用户自己的账号，Settings→Content |
| GitHub Releases | 版本检查与自更新 | 无 |

**ESPN 公开接口不再是这个 App 的外部服务**：体育整块撤除后，`app/src/main` 里没有任何比分/赛程请求，`SportsSource` / `SportsCatalog` 只剩 core AAR 内的定义，手机端不调用（D1）。

**无账号体系、无自有云**；跨设备只走本地网络或文件。

---

## 5. 与 OwnTV_Core 的能力边界

| 归 core（本仓只是调用方） | 归手机端 |
|---|---|
| Room 全部实体与 DAO、迁移 | Compose 屏、Stage 视觉底座、sheet 宿主 |
| 播放列表解析与同步、Stalker 流地址解析、`CatalogSyncScheduler` | `LiveTuner` / `VodTuner` 播放编排（预览、投屏、sound-only、历史、媒体会话） |
| EPG：`LiveEpgReader`、`GuideReader`、`EpgAutoMatcher`、`EpgShift`、`ArchiveGuideFiller`、`EpgLogoStore` | `GuideGrid`/`LivePreview` 等全部 UI；分页参数与 `PagingData.filter` 二次过滤 |
| 直播路由与降级 `LiveTuneController` + `EnginePair`、`LiveTimeshift`、`CatchupContinue`、`StreamUrlResolver` | 手势、HUD、弹层、迷你窗、PiP 边界 |
| 连接预算 `OpenStreamRegistry` / `StreamGrant` | `MobileMultiviewState`（电视端 `MultiviewState` 的手机版：查播放列表需挂起，故另写一份） |
| 录制：`RecordingManager`（含系列规则、冲突、窗口、relocate） | `RecordingsViewModel` 与 UI 分组 |
| 提醒：`ReminderManager` / `ReminderScheduler` | `ReminderNotifier`（通知渠道）+ `ReminderPrompt`（应用内弹层） |
| 下载：`DownloadManager` / `DownloadQueue`（队列、并发、断点、Wi-Fi 策略、通知） | 排序与文案、速率/剩余时间渲染、儿童/隐藏/锁过滤、目录授权与卷名 |
| 设置存储 `SettingsRepository`（DataStore）、自定义 `CustomizationStore` | 何时重读、失效回落、UI 状态 |
| 备份/恢复、`UserDataWriter`、本地同步伴侣服务器 | 页面、干跑预览呈现 |
| 档案 `ProfileManager`、`AdultCategoryClassifier`、`WatchSession` | `ProfileGate` 的渲染方式、门禁组合、以及"只认一个档案"的取舍（多档案的行留在库里不展示） |
| Home feed 规划 `HomeFeedReader`、搜索 `SearchReader` | 搜索防抖/最小长度/历史写入时机；读取时过滤掉 `LIVE_SPORTS` 型行 |
| 字幕 `SubtitleController` | OpenSubtitles 搜索 UI 与错误映射 |
| 语言目录 `LocaleCatalog` / `locales.json`（45 条，电视端仍全量） | **手机端实际打包的三种语言**：`i18n/AppLocales.kt` 白名单 + `localeFilters` + 四个归一化入口（见 3.19） |
| 备份容器与格式 `BackupContainer` / `BackupManager`（`profiles` 数组、按名合并） | **接受什么**：`backup/BackupProfiles.kt` 的拒绝与改名策略，格式一位没动 |
| core 清单里的 `MatchAlert*` / `LiveMatchCardService`（电视端在用） | 三条 `tools:node="remove"` + 启动时一次性 `rearmAll()`/`stopService()` 清账（`MatchAlertHost.backgroundAllowed` 保持 false，所以 `rearmAll()` 等于 `cancelAll()`） |
| — | **本仓自己的字符串**：`res/values{,-zh-rCN,-zh-rTW}/strings_mobile.xml`，26 个 `mobile_*` 键（core 里没有话术的那部分） |
| — | **Chromecast 整块**（`cast/` 5 个文件，电视端不存在） |

---

## 6. 构建、发布与质量保障

### 6.1 构建

- `./gradlew :app:assembleStandardDebug`；checks：`testStandardDebugUnitTest`、`lintStandardDebug`、两条 Python i18n 校验。
- **flavor 只按 ABI 分**：`standard`（arm64-v8a + armeabi-v7a）与 `x86_64`（模拟器）——播放器 .so 太大，通用 APK 不值得。
- release 开 minify + shrinkResources；debug 开伪语言；签名顺序：环境变量（CI）→ 用户级 Gradle 属性 → 仓外 `owntv-signing.properties`；未配置则产出未签名 APK。与电视端**共用同一 keystore 与属性名**。
- core 解析：源码在 `vendor/OwnTV_Core`（git subtree，来自 `ghnk211/OwnTV_Core` 的 `mobile` 分支），`includeBuild` 直接替换 `tv.own.owntv:core` / `:player-core`；不再有 pin 与 `owntv.corePath`。只有 `libmpv` 仍从 OwnTV Maven 解析；`owntv.libmpvLocalRepo` 用于本地构建的 libmpv，**只能写在 `~/.gradle/gradle.properties`，绝不写进仓库**。更新流程见 `docs/Core源码集成方案.md`。
- ProGuard 保点：libmpv JNI、WorkManager、`Room_Impl`、枚举名、`CastOptionsProvider`。
- `baselineprofile`：Macrobenchmark 在真机录制基线画像并 `mergeIntoMain`，CI 不跑。

### 6.2 CI 与测试现状

- 工作流：`android.yml`（verify-pin / check / build 三作业）、`i18n.yml`、`automerge-core-bump.yml`（core 版本 bump 自动合并）。`android.yml` 仍然把 `tools/i18n/locales.json` 与 core 对应 tag 的那份做 diff——**这份目录不再决定手机打包什么**，只是防止与 core 漂移。
- 测试 6 个类、34 条用例：JVM 侧 `AppLocalesTest`（7，白名单与归一化）、`MobileStringsParityTest`（5，三份 `strings_mobile.xml` 键集一致）、`BackupProfilesPolicyTest`（10，拒绝/改名/放行矩阵）、`SettingsSearchCoverageTest`（3）、`StartupLiveSelectionTest`（3）——`./gradlew -Powntv.corePath=<本机 core> :app:testStandardDebugUnitTest` 实测 **28 例全绿**；androidTest 的 `StageFieldTest`（6）已在 `Pixel5_API35` 模拟器经 `:app:connectedStandardDebugAndroidTest` 实测 **6 例 0 失败**，但前提是先修该用例自己的选择器：它把 `testTag` 加在 `StageField` 最外层 Column 上，而 `RequestFocus`/`PerformImeAction`/`Focused` 都只挂在内层文本节点，合并语义树不会上提，所以修前 6 例全红。这是 `main` 上同样存在的既有缺陷（`git diff main...HEAD -- app/src/androidTest app/src/main/java/tv/own/owntv/mobile/ui/stage/` 为空），与本期精简无关；修法只动测试，生产代码一行未改。
- **缺口**：播放器、后台服务、PiP、引擎切换、录制、下载、本地同步**均无自动化测试**；lint 全局关闭 `UnsafeOptInUsageError`。改动这些区域需要真机手测。
- **构建与检查现状（2026-11-06 实测）**：`:app:compileStandardDebugKotlin`、`:app:testStandardDebugUnitTest`、`:app:lintStandardDebug`、`:app:assembleStandardDebug`、`:app:assembleX86_64Debug`、`:app:assembleStandardRelease`、`:app:connectedStandardDebugAndroidTest`、`:app:processStandardDebugMainManifest` 全部转绿，条件是带 `-Powntv.corePath` 指向本机 core 检出（`settings.gradle.kts:49`，只读，不改 core）。不带它时解析到已发布的 `1.0.69`，才会缺 `liveRestoreKey` / `guideFollowsLiveCategory` / `settings_guide_follows_live`——那是"未发布"，不是"不存在"，本节先前写的"本分支编不过"据此作废。release 产物未签名（`app-standard-release-unsigned.apk`）。APK 语言转储（`aapt2 dump resources` 按 config 行计数）：release 只有 `()` 3617 / `zh-rCN` 2066 / `zh-rTW` 2066，`ar`/`ja`/伪语言全 0；debug 另含 `en-rXA` 3404 / `ar-rXB` 3404 伪语言与 core 真阿拉伯语 `(ar)` 3380 条。§7.2 真机矩阵已在 `Pixel5_API35` 模拟器（x86_64 debug 包）上分十一轮走查：**整行过了 19 行**（全新安装走向导、四格底栏无体育、App 锁跨冷启动与正确 PIN 进入、第 5 次错误 PIN 出冷却复数文案、儿童模式开与"关闭必须过 PIN"、导入多档案备份被拒且文件不被改坏、导出文件只含当前档案、同名单档案直接恢复、不同名给重命名提示且恢复后名字真变、密封包先要密码再给 sections 与 PIN 提示、旧语言 en-GB 就地归一为 en-US 且 de 回落"跟随系统"、升级库里三档案时直接进 active 且其余两行不删、active id 失效也直接落 Home）、全新安装从向导那一步恢复带 `pinHash` 的备份后当场就要 PIN（第 2 行）、旧 Home 配置里的 LIVE_SPORTS 不再显示任何行（第 24 行），**另 9 行各过一半**（旋转不重复问 PIN；下载开关的位置与 On↔Off 往返拨动，但"关掉后按钮消失"仍未观察——装机 0 titles，界面上没有 Download 按钮；深链/显式 intent/HOME 后热恢复都进不了门禁，通知点击与 PiP 未测；PIN 门覆盖受保护动作与改锁/移锁，分类锁未测；恢复的"取消/错密码不改动"过了而"中途失败回滚"没造出来；多档案拒绝过了而休眠同名一例只有单测（第 8 轮补上了匿名那一例的运行期拒绝）；旧体育残留的升级旁证过了而"取消一条真存在的闹钟"要一台装过旧版的设备才铺得出来），加上设置搜索命中与语言选择器 3 行 + 运行期换语言两项检查；**已没有完全未跑的行**（同步 18–20 在第 9/11 轮跑齐）；低内存杀进程第 8 行在第 7 轮收了大半（后台态用 `am kill` 杀掉真进程后重启仍要 PIN，0 崩溃 0 ANR；只剩真正的 lowmemorykiller 水位压力造不出来）；RTL 第 26 行在第 7 轮收了 App 侧一半（`system_locales` 写 ar-SA 优先后，指定英文与跟随系统都是英文界面、0 阿拉伯字母、0 崩溃、底栏仍 LTR），但这台镜像 adbd 非 root，"系统真的按 ar 生效"没能独立确证：备份第 2 行与旧体育残留 23–24 已在第 7 轮实测——向导里恢复一条 `pinHash` 有值的备份，"8 items restored." 之后当场弹 "Profile — enter PIN"，备份里的 2468 放行、9999 被 "Wrong PIN — try again" 拒掉；恢复一条旧形状的 `homeConfigs` 后 DataStore 明文里确实带着 `LIVE_SPORTS`，而 Settings → Layout → Home screen 只列 7 行、整屏 `sport` 0 次（同页徽标曾按未过滤的 order 数成 "8 rows"，本轮已修，见 §7 第 35 条）；手工铺一份旧版 `files/alerts.json` 冷启动后本包 0 条闹钟、0 条通知、`LiveMatchCardService` 运行期 Not found、广播无人接，文件本身字节未变，唯独"取消一条真闹钟"要一台装过旧版的设备才铺得出来；升级迁移 4/5 在第 6 轮，这些行要么要"有条目的内容源或正在播的会话"，要么要"先铺旧数据再升级"——已核实 core 的 `SourceType` 只有 M3U/XTREAM/LOCAL_BACKUP/STALKER，VOD 条目只能来自 IPTV provider，往设备推媒体文件不会让 Library 出现条目。第 4 轮补了 PIN 的三条：`Change PIN` 与 `Remove app lock` 都要先输旧 PIN（错 PIN 不改、不移除），改号后旧 PIN 在冷启动门禁上被拒而新 PIN 放行，锁移除后冷启动无门禁、儿童模式开关也不再弹 PIN。第 5 轮补备份/恢复：见 `extras/SLIMMING_PLAN.md` §7.2 的实测列与 §10.4 的"12–17 备份与恢复"行。另记一个既有缺陷（本轮已修）：导出成功的提示报的是建议文件名而不是实际保存的文件名（`BackupViewModel.kt:201` 回填传给 SAF 的 `name`；同页 `SettingsAppPage.kt:224` 已用 `exportPath`），与精简无关，见 §7 第 33 条。 第 8 轮（2026-11-06）：16 行整过 / 9 行半过 / 3 行未跑没有变化，但第 16 行的"匿名档案"补上了运行期拒绝（弹窗原文 "This backup’s profile has no name, so it cannot be matched. Name the profile on the other device and export again."），第 15 行的"恢复中途失败"确认造不出来（内层载荷写坏照样 "8 items restored."，见已知限制 36/37）。第 9 轮推进到 **17 整 / 10 半 / 1 未跑**：本地同步的托管页（QR + "PIN: 932782" + `http://10.0.2.15:8089/`，无凭证请求一律 401）、NSD 搜索、手填地址配对（`POST /sync/pair` → secret 复用 `X-Companion-Pin`）、三向选择与**入站多档案拒绝**（"The other device sent more than one profile… so it cannot be merged."，`profiles` 一行未动）都在真机上看到了；未跑只剩"对端把多档案包 POST 给本机"，因为屏上 PIN 从外部配不上对（限制 38）。第 11 轮（2026-11-06）把最后这行补齐：先 Cancel 掉上一轮残留的 "What to merge" 弹窗，托管页 dump 出活 "PIN: 761769"，同一台 PC 由此拿到 secret、`GET /backup.own` 下走本机 1348 B 寁封容器、并用 `POST /backup` 推入 3 档案样例，设备随即弹单档案拒绝且 `profiles` 一行未动，负向复核（伪 secret/错 PIN/无凭证）一律 401；现为 **19 整 / 9 半 / 0 未跑**。第 12 轮（2026-11-06，本轮）装上真实内容源（6 个直播频道 + 3 个 provider 分组 + 6 部影片）后推进两行，现为 **21 整 / 7 半 / 0 未跑**：**第 25 行**（通知/PiP/播放服务）——`owntv_playback` 渠道 id 4301、`FOREGROUND_SERVICE|NO_CLEAR`、展开后 5 个动作（含 Audio only / Close），全屏播放按 Home 真进 PiP（`mLastReportedPictureInPictureMode=true`），把通知那条 Intent 在冷启动下重放，落到的是 PIN 门禁而不是播放器（即通知路径绕不过 App 锁）；**第 28 行**（分类锁）——长按 provider 分组 → Lock with PIN → 新建/确认 PIN → 分组变密封（"This category is locked" + Enter PIN，All channels 计数从 6 掉到 4）。仍各半的 7 行：第 7（改字体后不再问 PIN）、11（下载按钮消失，有内容了但未测）、8（真 lmkd 压力）、15（恢复中途失败回滚）、16（休眠档案同名）、23（旧闹钟真被取消）、26（系统侧 RTL 确证）——后五行要 root 镜像 / 旧版本构建 / 往 DB 里造休眠行，这台模拟器给不了。本轮同时发现并修掉四个产品缺陷与一个调度缺陷，见 §7 第 33、35、36、39、40 条；四处修法都在同一台模拟器上复验：Settings → Layout 的 Home screen 徽标由 "8 rows" 变 "7 rows"（页内也是 7 行）、本地 playlist 的 Re-sync now 从"Preparing catalog"变成 "6 channels · 6 movies" 且日志 `result=Success`、140% 字体下滚动能取到 `Backup password` 且按钮变 `Encrypt & export`、分类锁 PIN 框读到 `••••`。顺带抓到一个既有崩溃并修掉（§7 第 41 条）。

---

## 7. 已知限制与"文档别踩的坑"

| # | 事实 | 位置 |
|---|---|---|
| 1 | **无深链**（只有 LAUNCHER），也别写"支持从浏览器打开" | `AndroidManifest.xml:95` |
| 2 | `ACCESS_COARSE_LOCATION` 的注释提到天气，但**手机端没有天气功能**（WeatherChip 已在 Stage 改版时移除，`WeatherRepository` 只电视端用） | `AndroidManifest.xml:36-41` |
| 3 | "记住最后的分类"**不是按档案分键存储**：`last_live_category`/`last_movies_category`/`last_series_category` 是 `owntv_positions` 里的全局单键——单档案之后这件事不再有歧义，但存储形状没改 | `core:SettingsRepository.kt:484,630,804-814`、`LibraryViewModel.kt:634-666` |
| 4 | Multiview 内**没有"录制这一格"**，是有意为之 | `MultiviewTileSheet.kt:17-18` |
| 5 | "录我正在看的"按钮需要 `recordWatchingEnabled` 且只在直播时出现 | `PlayerScreen.kt:826-829` |
| 6 | 投屏时移/回看**被停用**（设计取舍，不是缺陷）；投屏期间点预览等于换台 | `LiveTuner.kt:1013-1114,608` |
| 7 | 投屏被接收端拒绝时**不回退本地播放** | `CastPlaybackEngine.kt:120-129` |
| 8 | 录制"导出"与下载"Save a copy"都是**移动**，不是复制 | `RecordingsViewModel.kt:66-78`、`DownloadsViewModel.kt:322` |
| 9 | `playRecording` 有意不写历史/进度/字幕上下文 | `VodTuner.kt:392-404` |
| 10 | 电影页/剧集页的 ⋯ 会把 library VM 锁到 All，因此**标题页里"Move"永不出现** | `MoviePage.kt:127-133`、`VodActions.kt:126` |
| 11 | `LiveKey.All / History / Catchup` 没有稳定 order 上下文，对它们 Move 直接不可用 | `LibraryViewModel.kt:800-806` |
| 12 | 收藏页的频道行**进不了回看**：`MoreLeafPage` 丢弃了 `openCatchup` 参数 | `MoreLeafPage.kt:26`、`MobileNavHost.kt:318` |
| 13 | 剧集缩略图：provider 不存集图，只能用 show 的 backdrop/poster 顶替 | `SeriesPage.kt:816` |
| 14 | 关于页**无开源依赖清单**；`BuildConfig.DEV_TOOLS` 行在发布包被 R8 删除且仅英文；没有独立的"开发者工具"页 | `MoreScreen.kt:155` |
| 15 | 陈旧注释：`MoreLeafPage.kt:13-16` 仍写"Five of the eight"并列了并不存在的 Recordings 叶（实际 6 项） | 同上 |
| 16 | `MiniPlayerStyle.OFF` 已从设置移除但仍需兼容旧值；`EpgLogoStore` 注释承认此前手机端从未启用该功能 | `MobileShell.kt:258-265`、`OwnTVMobileApp.kt:184` |
| 17 | 因 material3 1.4.0 的 `MotionScheme` 是 internal，Material 自带组件不受"关闭动画"约束 | `MobileMotion.kt:22-27` |
| 18 | 字幕字号在手机端是 Exo/mpv **单值同写**，电视端的双引擎分离设置未开放 | `SettingsSubtitleAppearancePage.kt:128-135` |
| 19 | 全 `app/src/main` 无任何 `TODO` / `FIXME` 标记；`MobileNavHost.kt:64-65` 明确"无占位屏" | — |
| 20 | **App 锁没有"忘记密码"通路**：档案编辑器删掉之后，忘了 PIN 只能清 App 数据。文档别写"可通过 XX 找回" | `ProfileGate.kt`（无入口）、`ProfileSheets.kt`（只剩 `ProfilePinSheet`） |
| 21 | **旧设备的多余档案不显示也不删**：升级后库里可能有多个 `ProfileEntity`，App 只认 `activeProfileId` 那一行，没有切回的入口。别写"多档案已迁移/已清理" | `ShellViewModel.kt`、`MobileShell.kt:141-164`、D3 |
| 22 | **多档案的备份/同步容器一律拒**，改名冲突也拒；只有"名字不同"这一种会在 Settings 里改名后放行（Setup 里同样拒）。别写"可以导入电视端任意备份" | `backup/BackupProfiles.kt:95-117` |
| 23 | 存储的语言若不在三种内 → **跟随系统**（不是"回落到英文"），`en-GB` 归一为 `en-US`（美式拼写） | `AppLocales.normalize`、`AppLocalesTest` |
| 24 | 体育的 **core 字节码仍在依赖里**（`SportsRepository`/`SportsCatalog`/`MatchAlertHost`…，D8）：手机端保证的是"无入口、无后台、无组件"，不是"APK 里没有体育类" | `AndroidManifest.xml` 的 `tools:node="remove"`、SLIMMING_PLAN §4.6 |
| 25 | 本仓 `res/` 下**已有** `values-zh-rCN` / `values-zh-rTW`：新增 `mobile_*` 键必须三份同写，否则 `MobileStringsParityTest` 红 | `strings_mobile.xml` × 3 |
| 26 | `tools/i18n/locales.json` 与 `check_pseudo_locales.py`、`android.yml` 的 diff 仍按 45 条目录工作，**不跟随** `AppLocales` 白名单——已知偏差，别把它当打包真源 | `tools/i18n/check_pseudo_locales.py:15-43`、`.github/workflows/android.yml:78-84` |
| 27 | **不带 `-Powntv.corePath` 就编不过**：`liveRestoreKey` / `guideFollowsLiveCategory` / `settings_guide_follows_live` 只在本机 core 检出（`1.0.70-dev`）里存在，已发布的 `1.0.69` 没有。带上该属性后编译、单测、lint、assemble 全绿；"跑绿了"只在带属性时成立，见 6.2 | `LiveViewModel.kt:61`、`SettingsLayoutPages.kt:136-137` |
| 28 | **设置页的开关在 a11y 树上读不出状态**：Stage 的行是 `View clickable=true`，右侧开关既没有 `checkable/checked` 也没有 `content-desc`。别写"TalkBack 可读出开关开/关"；自动走查只能靠搜索列表右侧的值文本（"On"/"Off"）判定 | `StageControls.kt`（switch 绘制）、`SettingsGroupRows.kt`（`switchItem`） |
| 29 | **"允许下载"是 App 页里的一个 Downloads 小节，不是独立 leaf**：搜索给这三行标的组名是 **App**。别写"Settings → App → Downloads 三级页" | `SettingsGroupRows.kt:541-561` |
| 30 | `StageFieldTest` 这类用例**不能用 `testTag` 选输入框**：`StageField` 把调用方的 modifier 加在最外层 Column，`RequestFocus`/`PerformImeAction`/`Focused`/`EditableText` 只在内层文本节点上，合并语义树上提不到。要断言焦点/输入就用 `hasSetTextAction()` | `StageControls.kt:609`、`app/src/androidTest/.../StageFieldTest.kt` |
| 31 | **设置搜索结果行只导航，不改变值**：搜索列表点 `Kids mode` / `Allow downloads` 跳到所属组页（停在组页顶部，不滚动到目标行），开关不变；搜索列表因此只适合**读**值文本。另外组页里的开关行同样不显示值文本，只有搜索列表与"动作型"行（`App lock` → "PIN lock"）显示 | `SettingsSearch.kt`（结果行 onClick = 打开组页）、`SettingsGroupRows.kt` |
| 32 | **手机端没有任何深链入口**，`dumpsys package` 的 Activity Resolver Table 只有 Non-Data 的 `MAIN`/`LAUNCHER`（9 个图标变体），全表没有 `android.intent.action.VIEW`、没有 `Scheme:` 段。所以"深链绕过 App 锁"这一类场景是**攻击面不存在**，不是"已验证挡得住"；显式 `am start -n …/.MainActivity --es route live` 只会把 intent 交给仍在门禁上的同一实例 | `app/src/main/AndroidManifest.xml:107-111` 及其后各图标变体 |
| 33 | **已修（本轮）**：导出成功的提示以前写的是**建议文件名**，不是用户实际保存的文件名——保存框里改名后文件按新名落盘，提示仍是默认名。现在从 `OpenableColumns.DISPLAY_NAME` 读回落盘后的真实名字，只有提供方答不出来才退回 `owntv-backup.own` | `SettingsBackupPage.kt` 的 `savedFileName()` → `BackupViewModel.export(name)`；对比 `SettingsAppPage.kt:224` 的 `exportPath` |
| 34 | 单档案外壳里仍有三句 core 话术写成复数，读起来像还有多档案："playlists, **profiles** and saved passwords included"（导出加密说明）、"…and any **profile PINs**."（恢复成功提示）、"Off hides every Download button for **this profile**"（下载开关说明）。同一批键里也已有 App 口径的句子（"the PIN you use to open this app"），新旧混用 | core `settings_backup_*` 键；本期按"复用 core 键"不覆盖 |
| 35 | **已修（本轮）**：Settings → Layout 的 Home screen 行右侧徽标写 "N rows"，旧算法是 `home.order.count { it !in home.hidden }`，**没有**过滤已撤除的 `LIVE_SPORTS`——刚从旧版恢复过来的设备说 "8 rows" 而 Home screen 页只列 7 行。现在与页内同一口径：`home.settingsRows` 去掉 `LIVE_SPORTS` 再减 hidden，顺带也不再数 Trending | `SettingsGroupRows.kt:323` 对比 `SettingsHomePage.kt:92`；第 7 轮实测，第 12 轮修 |
| 36 | **已修（本轮）**：导出 sheet 的 "ENCRYPT THIS BACKUP?" 小节在 **140% 字体**下曾不再出现 `Backup password` 输入框，点说明文字也不展开。根因是标题在滚动区之外，24 个分区行把高度吃满后，带 `weight(1f, fill = false)` 的滚动列被压成 0——内容既看不见也滚不动。现在标题、分区、说明与密码框在同一个滚动块里，按钮固定在底部 | `SettingsBackupPage.kt` 的 `ExportSheet`；与单档案/体育/语言精简无关，属既有布局问题 |
| 37 | 备份文件的**内层载荷损坏不会报错**：`backup.json` 里 `epgSources` 写成非法 JSON 后恢复，界面仍给 "8 items restored."，没有任何"某段没吃进去"的提示。这是 core `BackupManager.import` 的 Result 聚合行为，**本仓未改**（要改得先在 core 里把每段解析失败计入 summary） | 部分损坏的备份静默通过；也意味着"恢复中途失败 → 档案名回滚"这条既有代码路径无法用数据注入验证，只有 `BackupViewModel.kt:320-324`/`:363` 的代码证据与策略单测 |
| 38 | 本机同步的屏上配对 PIN 是会话级的：`CompanionController` 每次 `start()` 现取一个新的 6 位随机 PIN，所以必须读**当前**托管页上的那个 — —前两轮走查读的是被残留 "What to merge" 弹窗挡住后的过期屏值，才会连续 401 | 第 11 轮清掉模态、重读 "PIN: 761769" 后，一台 PC 就能完成对端双向：配对拿 secret、`GET /backup.own` 下走本机寁封容器（1348 B）、`POST /backup` 推入 3 档案样例后设备弹单档案拒绝且库未动；鉴权面仍是好消息（无凭证/错 PIN/伪 secret 一律 401、只回 "Unauthorized. Check the PIN and try again."） |
| 39 | **本地文件的 playlist 也被要求"已验证的互联网"**（第 12 轮新发现，已在本仓修）：core 的 `CatalogSyncScheduler.enqueueSync` 一律 `NetworkType.CONNECTED`，现行 WorkManager 把它翻成 `INTERNET&TRUSTED&VALIDATED`；模拟器是 `PARTIAL_CONNECTIVITY`（ping 8.8.8.8 全丢）时任务被 `Constraints not met: Cancelling work ID` 挂起，播放列表行永远停在 "Preparing catalog"。playlist 就在本机，重同步却要联网，属**调度层语义错误**：断网/局域网/门户认证环境下本地源完全无法重同步（P1，本仓已可绕过）。修法：本仓对 `/`、`file://`、`content://` 的源改用自己的入队（同一个 unique work name、不带约束，进度与状态照旧可读），远程源仍走 core；core 侧的根治点是按源类型给 `NOT_REQUIRED` | `sync/LocalPlaylistSync.kt`、`SettingsViewModel.resync`、`LiveViewModel.refresh`；core `sync/work/CatalogSyncScheduler.kt:46-50` |
| 40 | **分类锁与设置 PIN 门的输入框曾经明文显示**（第 12 轮新发现，已修并在模拟器复验）：两处都走 `TextPromptDialog` → `StageSearchField(password = false)`，`uiautomator dump` 因此直接读到 `9999`；App 锁那一屏读到 `····`（那是 placeholder）／输入后读到 `••••`，是因为它走 `StageField(password = true)`。现在 `TextPromptDialog` 有 `password` / `keyboardType` 两个参数，`PinEntrySheet` 传 `true` + `NumberPassword`，三处 PIN 输入框口径一致；同类入口（设置 PIN 门、Customize PIN）共用这一条 | `CategoryLockPrompts.PinEntrySheet`、`CustomizeSupport.TextPromptDialog`；对比 `ProfileSheets.kt` 的 `StageField` |
| 41 | **打开一个已锁（也就是空）分类会让 App 当场崩溃**（第 12 轮新发现，已修）：`LiveScreen` 的"时钟 tick 重读可见行"与"可见行变化"两处都拿 `listState.layoutInfo.visibleItemsInfo` 的索引去 `channels.peek(index)`，而这一帧的索引可能来自上一帧那份更长的列表；分类切到已锁/空列表时 `LazyPagingItems.peek` 抛 `IndexOutOfBoundsException: Illegal attempt to access index 0 in ItemSnapshotList of size 0`，进程直接死（crash buffer 有栈、前台掉回桌面/短信）。同一写法在 Guide 的 `loadOnNow` 与 Library 的季数读取各有一处。三处改走 `peekOrNull`：越界当作"这一行现在没有"，不是故障 | `LiveScreen.kt:200,207`、`GuideScreen.kt:855`、`LibraryScreen.kt:212`、新增 `ui/components/PagingPeek.kt` |
---

## 8. 术语表

| 术语 | 含义 |
|---|---|
| **Stage** | 与电视端同源的视觉与控件体系（玻璃、hero、行、标签、按钮） |
| **Glass** | 背景玻璃效果，手机默认 51% alpha |
| **Trending / TrendingHero** | TMDB 趋势缓存行与首页满幅轮播头图 |
| **Catch-up** | 从 provider 归档回看已播节目 |
| **Timeshift** | 直播暂停后向后拖拽（本地缓冲） |
| **Multiview** | 最多 4 路直播同屏，单格出声 |
| **Sealed playback** | 分类被封锁时对该分类播放的拦停机制（与"加密备份包"无关） |
| **Quick** | 设置里长按置顶的开关集合 |
| **App 锁** | 唯一档案上的 `pinHash`：开 App 时问的那道 PIN，同时也是分类锁与设置门用的那一道；在 Settings→App→App 锁里设/改/撤 |
| **休眠档案** | 升级前留在库里、当前 App 不显示也不使用的其它 `ProfileEntity` 行；它们的**名字仍然占用**，所以改名会撞 |
| **Local sync** | 同 Wi-Fi 下与电视/另一台手机的**当前档案**数据直连同步（无账号、无云） |
| **Connection budget** | 实测的 provider 并发路数上限，取流前要申请授权 |
| **Rail / Bottom bar** | 平板/横屏侧栏（`MobileDestination.rail`，7 项）与手机底部栏（`bottomBar`，4 项：Home / Live / Guide / Library） |
| **EnginePair / LiveTuneController** | core 的双引擎路由与自动降级阶梯（手机端在 `LiveTuner.kt:243-291` 消费 `liveOnExo` / `activeEngine`） |
| **Provider folder** | 播放列表自带的分类目录，可锁、可改名、可隐藏 |
| **`AppLocales` 白名单** | 本 App 打包并允许选择的三种语言（en-US / zh-CN / zh-TW），`i18n/AppLocales.kt` 是唯一真源；`normalize()` 把任何其它 tag 折成"跟随系统" |

---

## 9. 近期演进

- `mobile-mini`（本期，2026-11）：**瘦身三阶段**——① 语言收到三种并把白名单搬进代码（`AppLocales` + `localeFilters` + 四个归一化入口 + 本仓 `strings_mobile.xml`）；② 体育整块撤除（13 个页面文件删除、`di/SportsModule.kt` 摘掉、清单三条 `tools:node="remove"`、启动时清旧闹钟与卡片服务、读取时过滤 `LIVE_SPORTS` 行，core 残留按 D8 接受）；③ 档案模型收成隐式单档案（PROFILE 设置组与档案编辑页删除、`ProfileGate` 重写为纯 App 锁、新增 Settings→App→App 锁页、儿童模式改全局开关、允许下载搬到 Settings→App、Setup 简化、备份/同步按 `BackupProfiles` 拒多档案）。方案与偏差记录见 `extras/SLIMMING_PLAN.md`（§10 为实施结果）。
- `15a85f0` 合并第一阶段：剧集日期、按截止日下载节目单、~~按档案的分类记忆~~、Guide 跟随 Live、Search 打开 Live 并落在该频道文件夹；`a58c6ad` Guide 里选分类即时反映到 Live；`ede807e` 电影/剧集跟随档案切换免重启；`277d144` 搜索跳频道改用一次性句柄。（单档案之后，"切档"相关表述只剩存储层含义，界面上已无档案可切。）
