# OwnTV_Mobile 精简改造方案

> 范围：仅手机端 App（`OwnTV_Mobile`）。共享引擎 `tv.own.owntv:core:1.0.69` 不改、不升级 pin。
> 基线：`extras/FEATURE_MAP.md`、分支 `mobile-mini`、2026-11-06。
> 状态：D1–D10 已定稿，阶段 1–3 已在本工作树实施完毕；实施结果、偏差与遗留项见 §10。
> §7.1 单测、§7.3 构建检查（lint + debug/release assemble + 合并清单 + APK/release 语言转储）均已在本机跑通（第 10 轮重跑已收尾：28 例单测 0 失败；同一条命令里的 lint 报 **0 errors, 64 warnings, 5 hints**、日志末行 **`BUILD SUCCESSFUL in 10m 32s`**；产物全部重新生成——`app-standard-debug.apk` 82 218 788 B、`app-standard-release-unsigned.apk` 54 650 611 B，后者仍未签名）：编译要用 `-Powntv.corePath` 指向本机 core 检出（只读，不改 core）。§7.2 真机矩阵已在 `Pixel5_API35` 模拟器上分十一轮实测：整行通过 **19 行**（第 1/6/9/10/27 行 + 第 5 轮的备份/恢复行 3/12/13/14/17 与旧语言标签行 21/22 + 第 6 轮的升级行 4/5 + 第 7 轮的带 PIN 备份行 2 与旧 Home 配置行 24 + 第 9/11 轮的本地同步行 18/19/20），另有 **9 行各测了一半**（第 7 行的旋转、第 11 行的位置与开关往返拨动、第 25 行的深链面/显式 intent/热恢复、第 28 行的 PIN 门与新旧 PIN 与移除锁后不再提示只差分类锁、第 15 行的取消不改动与第 16 行的多档案拒绝 + 文件未损坏），加上"新行可搜索"与语言选择器 3 行 + 运行期换语言两项检查；**已没有完全未跑的行**，剩下 9 行各差一半截（7 的改字体后不再问 PIN、8 的真 lmkd 压力、11 的下载按钮消失、15 的恢复中途失败回滚、16 的休眠同名、23 的旧闹钟真的被取消、25 的通知点击与 PiP、26 的系统侧确证、28 的分类锁）。第 20 行（伴侣服务器把多档案包 `POST /backup` 推给本机）已在第 11 轮用同一台 PC 当对端真跑通：先取消残留的 "What to merge" 弹窗、从托管页读到活 PIN 761769，配对拿到 secret 后推 3 档案样例，设备弹出单档案拒绝且 `profiles` 一行未动；细节见 §7.2 第 18/19/20 行与 §10.5 第 13 条。未跑的行里，需要"可用内容源或正在播的会话"的（11 的按钮消失、25 的通知/PiP、4–5 的旧数据迁移演示）在装机 0 titles 状态下看不到；备份与同步大部分行不依赖内容源，第 5 轮已经把备份侧做完，第 20 行需要的那个"能与本机完成 PIN 配对的伴侣/对端"已在第 11 轮用同一个假对端反方向补齐，见 §7.2 第 18/19/20 行与 §10.4 第 11 轮两行。androidTest `StageFieldTest` 已接同一模拟器跑通（6 例 0 失败），但前提是先修该用例本身的选择器缺陷——它在本分支与 `main` 上都是红的，见 §10.4。详见 §10.4、§10.5。

---

## 0. 结论摘要

### 0.1 三项改动

| 项 | 现状 | 目标 | 主要手段 |
|---|---|---|---|
| i18n | 目录 45 种、APK 打包 27 种、选择器 26 行、3 种 RTL | 只保留 **en-US、zh-CN、zh-TW** | 构建白名单 + 选择器白名单 + 持久化标签归一化；core 语言表不动 |
| 体育 | App 内 13 个页面文件约 4.2k 行，另有启动、导航、Home、播放器、设置、同步提示、通知深链 | App 内无入口、无后台、无通知 | 删除 App 模块；Manifest 移除 core 的 3 个组件；升级时清旧闹钟；core 残留接受（D8） |
| 多账户 | 创建/编辑/删除/切换/每个档案独立 PIN、备份多选、同步多选 | **单一隐式档案** + 简化 App 锁 | 保留 `profileId` 数据模型；删除管理 UI；门禁改为单档案解锁页；备份/同步只处理当前档案 |

### 0.2 总体策略

1. **App-only**：不修改 core 的数据库、DataStore、字符串、备份格式、同步协议、体育类。所有删减在 App 层完成。
2. **不删数据**：旧的多档案行、体育配置、Home 的 `LIVE_SPORTS` 行、旧的下载开关值都保留在库里，只是不再显示或不再参与逻辑。降级回旧版仍能读回。
3. **一个活动档案**：`settings.activeProfileId` 继续存在；`profileId` 继续作用域化收藏、历史、续播、定制、启动页、分类锁、下载许可、备份、同步。只是不再提供“换人”的 UI。
4. **三道门禁保留**：App 锁（原 `ProfileGate` 的简化版）、设置 PIN 门（`SettingsPinGate`）、分类锁（`CategoryLock`）全部保留；它们共用 `ProfileEntity.pinHash`。
5. **备份/同步单档案化**：导出、托管、发送、合并回传永远只带当前档案；收到多档案、匿名档案、与休眠档案同名的档案时拒绝并说明原因（D9）。
6. **文档同步**：README、CHANGELOG、FEATURE_MAP、Master Product Brief、PARITY_CHECKLIST、USER_GUIDE 一起改（D10）。

### 0.3 规模估算

| 子项 | 删除 | 新增/改写 | 触碰文件 | 人日 |
|---|---:|---:|---:|---:|
| i18n | 构建目录解析约 20 行 | `AppLocales` + 归一化 + 选择器约 120 行 | 约 10 | 1 |
| 体育 | 约 4.2k 行 + 1 个 DI 模块 | 引用清理约 150 行 | 约 30 | 1.5–2 |
| 多账户 → 单档案 | 约 700 行 | 约 350 行 | 约 22 | 3–4 |
| 文档 | — | 6 份 + 本方案 | 7 | 1 |
| **开发合计** | — | — | — | **6–9** |
| QA（手工矩阵 + 回归） | — | — | — | **2–3** |

多账户子项的文件数按“净改动文件”计；体育的 30 个文件里包含 14 个整文件删除和约 16 个引用修改。总工期不含发版与商店流程。

### 0.4 验收口径

- **i18n**：APK 只打包 `en`、`zh-rCN`、`zh-rTW`；选择器只有“跟随系统 + 3 行”；旧持久化的 `en-GB` 变成 `en-US`，其他语言静默回落到系统默认；三份 App 本地字符串键完全一致。
- **体育**：App 内找不到体育入口、体育设置、比赛深链、比赛比分条、其他频道按钮、体育同步提示；升级后不再收到体育通知；合并后的 Manifest 不含 core 的体育组件。
- **多账户**：App 内找不到“谁在看”、档案列表、添加/编辑/删除/切换入口；旧档案仍在库中但休眠；App 锁、儿童模式、分类锁、设置 PIN 门仍有效；备份/同步只处理当前档案，多档案文件被明确拒绝。
- **回归**：现有单元测试、lint、`assembleStandardDebug` 全绿；手工矩阵无 P0/P1。

---

## 1. 现状与约束

### 1.1 工程结构

- App 是纯 UI 层：`OwnTVMobileApp` 启动 Koin，`MainActivity` 承载 Compose，`MobileShell` 负责门禁与全局外壳，`MobileNavHost` 负责路由。
- core 拥有：Room 数据库、DataStore、设置仓库、`ProfileManager`、`SourceImporter`、`BackupManager`、`LocalSyncManager`、EPG、下载、播放器、体育、全部 `strings_*.xml`。
- App 目前**没有** `values/strings.xml`；只有 `colors.xml`、`themes.xml`、`values-v31/themes.xml`。本方案新增三份 App 本地字符串（`values/strings_mobile.xml`、`values-zh-rCN/strings_mobile.xml`、`values-zh-rTW/strings_mobile.xml`）。
- 构建：`minSdk 26`、`targetSdk 36`、`compileSdk 37`、Java 17；flavor `standard`（真机）与 `x86_64`（模拟器）；Compose BOM 2026.09.00、Koin 4.2.2。
- 单测：`unitTests.isReturnDefaultValues = true`，只带 JUnit。Android 的 `org.json` 方法在 JVM 单测里返回默认值，因此**读取备份 JSON 的逻辑不能直接单测**；要把纯策略拆出来测。

### 1.2 硬约束

1. **不碰 core**：不升级 `1.0.69`，不提 core PR。core 的体育类、多档案 API、45 种语言表都留在依赖里。
2. **不改数据库 schema**：`ProfileEntity`、`ProfileDao`、收藏/历史/续播/定制的表结构不变。旧多档案行不迁移、不删除。
3. **不改备份格式**：`.own` 仍是 ZIP + `backup.json`，密封仍是 `OWNTVBK1`。新 App 只改变“写几个档案”，不改变“怎么写”。
4. **不改同步协议**：`LocalSyncManager` 的配对、密钥、容器格式不变；只改变传入的 `profileIds` 和到达后的校验。
5. **不新增远程服务、不新增权限**：App 锁、重命名、拒绝提示都是本地行为。
6. **不保留 en-GB**（D6）：`en-*` 一律归一化为 `en-US`；不新增 `values-en-rGB`。
7. **不支持多档案备份/同步**（D9）：导出、托管、发送、回传都只带当前档案；收到的多档案文件拒绝。

### 1.3 profileId 的耦合面

`profileId` 不能删，因为它已经写进了下列数据和 core API：

| 数据/行为 | 作用域 | 本方案 |
|---|---|---|
| 收藏、历史、续播 | profile | 保留，永远当前档案 |
| 首页行顺序/隐藏、分类隐藏/置顶 | profile | 保留 |
| 启动页/启动频道 | profile | 保留 |
| 分类锁（`CategoryLock`） | profile | 保留，PIN 共用 |
| 下载许可 `downloadsAllowed` | profile | 保留，开关移到 Settings → App → Downloads（D5） |
| 备份 | profileIds | 只导出当前档案 |
| 本地同步 | profileIds | 只托管/发送当前档案 |
| 体育配置 | profile | 保留在库里但不再读取（D8） |

因此“删除多账户”在实现上是：**保留一个活动档案，隐藏其余档案和所有管理入口**，而不是删除 `profileId`。

### 1.4 门禁与认证现状

| 门禁 | 位置 | 保护对象 | 认证 | 本方案 |
|---|---|---|---|---|
| App 锁（原 `ProfileGate`） | `MobileShell`，App 冷启动时 | 整个 App UI | `profile.pinHash` + 4–6 位 PIN | 保留并简化为单档案解锁页（D3 变体 B） |
| 设置 PIN 门（`SettingsPinGate`） | `MobileNavHost` 的设置/备份/同步路由 | 设置及其子页 | 同一 `pinHash`，每次访问会话一次 | 保留不动 |
| 分类锁（`CategoryLock`） | Live/Guide/Movies/Series | 被锁分类 | 同一 `pinHash` | 保留不动 |
| 伴侣服务器 PIN | core `CompanionServer` | 远程备份下载/上传 | core 自己的 PIN | 保留不动 |
| 本地同步配对 | core `LocalSyncManager` | 局域网同步 | 配对 PIN + 会话密钥 | 保留不动 |

### 1.5 数据兼容原则

- **只读过滤，不做破坏性迁移**：旧档案、旧体育配置、旧 Home 行、旧语言标签都可以留在库里。
- **写回只写当前档案**：新开关、App 锁、儿童模式、下载许可都写活动档案。
- **降级可读**：新 App 导出的单档案备份，旧 App 仍可导入；旧 App 的多档案备份，新 App 拒绝但文件不损坏。
- **唯一不可逆的是 APK 语言资源**：旧语言不再打包，降级回旧版才能重新选择。这是 D1/D6 的预期代价。

---

## 2. 已确认决策（D1–D10）

| 编号 | 决策 | 状态 | 影响 |
|---|---|---|---|
| D1 | 本期只改 App；不升 core pin、不提 core PR | 已确认（范围前提） | core 的体育类、多档案 API、45 语言表保留 |
| D2 | 旧多档案备份/数据：**提示不能导入**；设备上的旧档案保留但休眠 | 已确认 | 不删行、不迁移；恢复时给明确文案 |
| D3 | 档案 PIN 采用**变体 B**：保留简化的单档案 App 锁 | 已确认 | 重写 `ProfileGate`，保留 `ProfileGateSession`、`ProfilePinSheet` |
| D4 | 保留**全局儿童模式开关** | 已确认 | 写到活动档案的 `isKids`；关闭时若有 PIN 要验证 |
| D5 | “允许下载”移到 **Settings → App → Downloads** | 已确认 | 写活动档案的 `downloadsAllowed` |
| D6 | **不保留 en-GB**；`en-*` 归一化为 `en-US` | 已确认 | 不打包 `en-rGB`，选择器不显示 en-GB |
| D7 | 不支持的持久化语言标签**静默重置** | 方案默认（未被反对） | 启动时归一化并写回 `""` 或 `en-US`，不弹窗 |
| D8 | 体育 core 残留本期接受；**app-only 验收** | 已确认 | 不删 core 的 `SportsRepository`/`SportsCatalog`/接收器类；只保证 App 内无入口、无后台 |
| D9 | **不支持备份/同步多档案** | 已确认 | 导出/托管/发送/回传只带当前档案；多档案文件拒绝 |
| D10 | 所有文档一起更新 | 已确认 | README、CHANGELOG、FEATURE_MAP、Master Brief、PARITY_CHECKLIST、USER_GUIDE |

---

## 3. i18n 精简

### 3.1 现状

- `tools/i18n/locales.json` 有 45 个条目；`app/build.gradle.kts` 读取其中 `packaged = true` 的 `resourceQualifier`，交给 `androidResources.localeFilters`。
- 结果：APK 打包约 27 种语言（含 `en-rGB`），选择器 `SupportedLocales.pickerRows` 约 26 行，core 支持 45 种；RTL 有 3 种。
- `LocaleStore` 会按 core 的 45 种校验持久化标签；App 没有自己的白名单。
- App 没有本地 `strings.xml`；所有 UI 字符串来自 core 的 `strings_*.xml`。

### 3.2 目标

| 层 | 目标 | 实现 |
|---|---|---|
| APK 资源 | 只打包 `en`、`zh-rCN`、`zh-rTW` | `androidResources.localeFilters.addAll("en", "zh-rCN", "zh-rTW")` |
| 选择器 | “跟随系统” + 3 行 | `AppLocales.rows` 过滤 `SupportedLocales.all` |
| 持久化标签 | 只接受 `""`、`en-US`、`zh-CN`、`zh-TW` | `AppLocales.normalize` + 启动写回 |
| 调试伪语言 | debug 仍可用 `en-XA`、`ar-XB` | 保留现有 per-variant 写法 |
| App 本地字符串 | 3 种语言键一致 | 三份 `strings_mobile.xml` + 单测 |

### 3.3 新增 `AppLocales`

新建 `app/src/main/java/tv/own/owntv/mobile/i18n/AppLocales.kt`：

```kotlin
package tv.own.owntv.mobile.i18n

import tv.own.owntv.core.i18n.SupportedLocale
import tv.own.owntv.core.i18n.SupportedLocales
import tv.own.owntv.mobile.BuildConfig

object AppLocales {
    /** 运行时可持久化的三种语言，顺序即选择器顺序。 */
    val shippedTags = listOf("en-US", "zh-CN", "zh-TW")

    /** Android 资源限定符，与 shippedTags 一一对应。 */
    val shippedQualifiers = listOf("en", "zh-rCN", "zh-rTW")

    /** 选择器行；从 core 的目录里取，保留 endonym/englishName/coverage 等字段。 */
    val rows: List<SupportedLocale> =
        shippedTags.mapNotNull { tag -> SupportedLocales.all.firstOrNull { it.languageTag == tag } }

    private val debugPseudoTags = if (BuildConfig.DEBUG) setOf("en-XA", "ar-XB") else emptySet()

    /**
     * 把任意持久化标签收口到白名单：
     * - "" 继续表示跟随系统；
     * - en / en-* 一律 en-US（D6，不保留 en-GB）；
     * - 其他不支持的语言回落到 ""（跟随系统；系统若是未打包语言，Android 会回落默认英文）。
     */
    fun normalize(tag: String): String {
        val t = tag.trim()
        return when {
            t.isEmpty() -> ""
            t in shippedTags -> t
            t in debugPseudoTags -> t
            t.equals("en", ignoreCase = true) || t.startsWith("en-", ignoreCase = true) -> "en-US"
            else -> ""
        }
    }

    fun isShipped(tag: String): Boolean =
        tag.isEmpty() || tag in shippedTags || tag in debugPseudoTags
}
```

要点：

- `rows` 不硬编码 `SupportedLocale`，避免和 core 的 `endonym`/`coverage` 漂移。
- `normalize` 只认三种标签；`zh-CN`/`zh-TW` 不做 `zh-Hans`/`zh-Hant` 映射，因为 core 的 45 种表里没有这两个标签。
- `en-GB` 没有特判；它落进 `startsWith("en-")`，变成 `en-US`。不新增 `values-en-rGB`。
- debug 伪语言只在 debug 生效；release 里 `ar-XB` 会回落到 `""`，`en-XA` 会变成 `en-US`，不会留下无效标签。

### 3.4 构建白名单

`app/build.gradle.kts`：

1. 删除顶部读取 `tools/i18n/locales.json` 的整段（`localesCatalogueFile`、`packagedLocaleQualifiers`）。
2. 把 `androidResources` 改成：

```kotlin
androidResources {
    // 手机端只保留三种语言。core 仍带 27 种资源，但不会进入 APK；这不是删 core 的语言表。
    // 资源限定符必须写 en/zh-rCN/zh-rTW，不能把 BCP-47 标签直接塞进 localeFilters。
    localeFilters.addAll("en", "zh-rCN", "zh-rTW")
}
```

3. 保留 debug 伪语言：

```kotlin
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.androidResources.localeFilters.addAll("en-rXA", "ar-rXB")
    }
}
```

4. 更新注释：不再说“24 locales”，改成“3 shipped locales + debug pseudo locales”。
5. `tools/i18n/locales.json` **不删**（本地化脚本仍可能读它），但 App 构建不再读。更新 `tools/i18n/README.md`，写明“手机端 App 的实际打包白名单在 `AppLocales`，不是这份目录”。

### 3.5 启动归一化与全局兜底

四个入口都要归一化：

1. **`OwnTVMobileApp.attachBaseContext`**：

```kotlin
val tag = AppLocales.normalize(LocaleStore.from(base).readBlocking())
AppLocale.applyGlobally(tag)
super.attachBaseContext(AppLocale.wrap(base, tag))
```

2. **`OwnTVMobileApp.onConfigurationChanged`**：同样先 `AppLocales.normalize(...)`，再 `AppLocale.applyGlobally`。

3. **`MainActivity.attachBaseContext`**：同样归一化，避免 Activity 的 base context 和 Application 不一致。

4. **Koin 启动后的全局守卫**：处理“App 运行期间有人写了旧标签”以及“旧版本升级上来第一次启动”：

```kotlin
appScope.launch {
    val store = GlobalContext.get().get<LocaleStore>()
    store.currentTag.collect { raw ->
        val tag = AppLocales.normalize(raw)
        if (tag != raw) runCatching { store.set(tag) }
    }
}
```

这条守卫是幂等的：`store.set(tag)` 之后 `currentTag` 再发出来的就是 `tag`，不会再写。`store.set` 失败也不影响当前会话，下次启动还会再试。

### 3.6 组合层与播放侧的防御

- **`ui/LocalizedContent.kt`**：`tag` 和 `startTag` 都先 `AppLocales.normalize`，再用于 `scriptOf`、`AppLocale.wrapForCompose`、`LaunchedEffect` 的 key。`scriptOf` 里的 `SupportedLocales.scriptForTag` 也拿到归一化后的标签。
- **`playback/DataSaverGate.kt`**：`AppLocale.wrap(context, AppLocales.normalize(LocaleStore.from(context).readBlocking()))`。
- **`playback/PlaybackService.kt`**：同上。播放服务可能在没有 Activity 的情况下启动，不能假设 Application 已经归一化过。
- **`ui/setup/SetupWizardSteps.kt`**：语言选择器的 `rows` 从 `SupportedLocales.pickerRows` 改成 `AppLocales.rows`。
- **`ui/screens/settings/SettingsLanguagePage.kt`**：`rows` 改成 `AppLocales.rows`；删除覆盖率徽章、Weblate 贡献入口和“Request a language”链接（只保留 3 种语言时这些入口会误导用户）。
- **`ui/screens/settings/SettingsGroupRows.kt`**：`localeLabel` 改成 `AppLocales.rows`。
- **`ui/screens/settings/SettingsAppPage.kt`**：语言数量从 `SupportedLocales.all.count { it.packaged }` 改成 `AppLocales.rows.size`。

### 3.7 行为细节

| 场景 | 行为 |
|---|---|
| 持久化 `en-GB` | 启动时写成 `en-US`；选择器不出现 en-GB |
| 持久化 `de`/`fr`/`ar` 等 | 启动时写成 `""`；选择器显示“跟随系统”；APK 回落到默认英文 |
| 系统语言是 `de`，App 选择“跟随系统” | 资源回落到默认英文；这是“只保留 3 种”的预期结果 |
| 系统语言是 `zh-Hant-HK`，App 选择“跟随系统” | Android 资源解析可能落到 `zh-rTW` 或默认英文；不新增 `zh-rHK` |
| 用户在设置里选 `zh-CN`/`zh-TW`/`en-US` | 正常写入；守卫看到 `normalize(tag) == tag`，不重复写 |
| 从英文切到中文或反过来 | 脚本变化触发 Activity recreate（现有逻辑） |
| debug 伪语言 | `en-XA`/`ar-XB` 只在 debug 被接受；release 不会持久化它们 |

### 3.8 App 本地字符串

新增三份文件，键集合必须完全一致：

- `app/src/main/res/values/strings_mobile.xml`（英文，默认）
- `app/src/main/res/values-zh-rCN/strings_mobile.xml`（简体）
- `app/src/main/res/values-zh-rTW/strings_mobile.xml`（繁体）

键清单见附录 A。来源分两类：

- **新增键**：App 锁、拒绝提示、重命名提示、单档案说明、Setup 文案。
- **复用 core 键**：`profiles_allow_downloads`、`profiles_allow_downloads_description`、`settings_profile_kids`、`profiles_enter_pin`、`profiles_wrong_pin`、`setup_default_profile`、`settings_customize_pin_mismatch`。

注意：`app/build.gradle.kts` 的硬编码字符串检查扫描 Kotlin 字面量，不扫描 XML；把新文案放进 `strings_mobile.xml` 不会触发该门禁。不要把新文案写成 Kotlin 字符串。

### 3.9 测试与验收

**新增单测**

`app/src/test/java/tv/own/owntv/mobile/i18n/AppLocalesTest.kt`：

| 断言 | 期望 |
|---|---|
| `normalize("")` | `""` |
| `normalize("en-US")` | `en-US` |
| `normalize("en-GB")` | `en-US` |
| `normalize("en")` | `en-US` |
| `normalize("zh-CN")` | `zh-CN` |
| `normalize("zh-TW")` | `zh-TW` |
| `normalize("de")` | `""` |
| `rows.size` | 3 |
| `rows.map { it.languageTag }` | 与 `shippedTags` 一致 |
| `shippedQualifiers` | `en`、`zh-rCN`、`zh-rTW` |

**新增单测**

`app/src/test/java/tv/own/owntv/mobile/i18n/MobileStringsParityTest.kt`：

- 读取三份 `strings_mobile.xml`（模块相对路径，Gradle 单测工作目录是 `app/`）。
- 解析 `<string name="...">`，比较三个集合完全相等。
- 断言没有空值、没有重复键。

**构建检查**

- `./gradlew :app:testStandardDebugUnitTest`
- `./gradlew :app:lintStandardDebug`
- `./gradlew :app:assembleStandardDebug`
- 用 `apkanalyzer`/`aapt2` 检查 APK 的 locale config 只含 `en`、`zh-rCN`、`zh-rTW`（debug 另含伪语言）。
- 手工检查选择器只有“跟随系统 + 3 行”。
- 升级测试：把旧版本语言设为 `en-GB`，升级后应为 `en-US`；设为 `de`，升级后应为“跟随系统”。

---

## 4. 体育模块移除

### 4.1 现状

App 的体育模块包括：

- `ui/screens/sports/` 13 个文件，约 4.2k 行：`SportsScreen`、`SportsMatchScreen`、`SportsTeamScreen`、`SportsViewModel`、`SportsSettingsViewModel`、`SportsSettingsPages`、`SportsPlayback`、`SportsPlayerParts`、`SportsParts`、`SportsCards`、`SportsFilterSheet`、`SportsLeaguesTab`、`SportsCalendarTab`。
- `di/SportsModule.kt`：`SportsPlayback`、`SportsViewModel`、`SportsSettingsViewModel`。
- 启动：`MatchAlertHost.backgroundAllowed = true`、延迟调用 `SportsRepository.appStarted()`、`SPORTS_START_DELAY_MS`。
- 导航：`MobileDestination.SPORTS`、底部栏/侧栏、match/team 路由、深链。
- Home：`HomeRow.LIVE_SPORTS` 行、比赛卡片、比分。
- 播放器：`SportsPlayback` 会话、比分条、其他频道按钮。
- 设置：体育页、联赛/球队/提醒页。
- 同步提示：`SyncStatusPill` 的体育重建行。
- 通知：core 的 `MatchAlertReceiver`、`MatchAlertBootReceiver`、`LiveMatchCardService`。

注意：`ui/screens/guide/GuideMatchUi.kt` 是 **EPG 自动匹配**，不是体育；grep 到它只是因为注释里出现 “Sky Sports” 示例。**不要删这个文件。**

### 4.2 App 内删除/修改清单

**整文件删除**

| 文件/目录 | 说明 |
|---|---|
| `app/src/main/java/tv/own/owntv/mobile/ui/screens/sports/` | 13 个文件全部删除 |
| `app/src/main/java/tv/own/owntv/mobile/di/SportsModule.kt` | Koin 模块删除 |

**引用修改**

| 文件 | 改法 |
|---|---|
| `OwnTVMobileApp.kt` | 删除 `sportsModule` 注册；删除 `MatchAlertHost.backgroundAllowed = true`；删除延迟 `SportsRepository.appStarted()` 和 `SPORTS_START_DELAY_MS`；加入旧闹钟清理（见 4.5） |
| `MainActivity.kt` | 删除通知权限委托（`MatchAlertHost.requestNotificationPermission`）；删除 `EXTRA_MATCH_KEY` 深链读取和 `SportsPlayback.openMatchKey` 写入；删除 `onDestroy` 里的委托清理 |
| `ui/nav/MobileNavHost.kt` | 删除 sports imports、`sportsPlayback`、`openMatchKey` 收集、`SPORTS_MATCH`/`SPORTS_TEAM` 路由、`sportsMatchRoute`/`sportsTeamRoute`、`SportsScreen`/`SportsMatchScreen`/`SportsTeamScreen` 分支、`onOpenMatch`/`onOpenTeam`/`onChooseSports` 回调 |
| `ui/nav/MobileDestination.kt` | 删除 `SPORTS` 枚举；底部栏从 5 项变 4 项（HOME、LIVE、GUIDE、LIBRARY）；侧栏删除 SPORTS |
| `ui/shell/MobileShell.kt` | 删除 `SportsPlayback`/`SportsTab` imports、`sportsPlayback` 变量、`onNavClick` 的 `sportsPlayback` 参数、`MobileDestination.SPORTS` 分支 |
| `ui/shell/SyncStatusPill.kt` | 删除 `SportsActivityTracker`、`RebuildState`、`sportsRebuild`、`sportsFailed`、`SyncLine.Sports`、`sports_rebuilding`/`sports_rebuild_failed` 文案 |
| `ui/screens/home/HomeScreen.kt` | 删除 `sportsVm`、`sportsCards`、`sportsConfig`、`HomeRow.LIVE_SPORTS` 分支和 `hasContent` case；在 `visibleOrder` 里过滤 `HomeRow.LIVE_SPORTS` |
| `ui/screens/settings/SettingsHomePage.kt` | 从 Home 行列表/顺序中过滤 `HomeRow.LIVE_SPORTS`；删除 `titleRes`/`descriptionRes` 的体育 case |
| `ui/screens/settings/SettingsGroupRows.kt` | 删除 App 组的体育页行 |
| `ui/screens/settings/SettingsLeaf.kt` | 删除 `SPORTS`、`SPORTS_LEAGUES`、`SPORTS_TEAMS`、`SPORTS_ALERTS` 路由 |
| `ui/screens/settings/SettingsDestination.kt` | 删除四个体育页的分发 |
| `ui/player/PlayerScreen.kt` | 删除 `sportsPlayback`、`sportsSession`、`sportsConfig`、`matchSession`、`matchLine`、`OtherChannelsSheet` |
| `ui/player/PlayerControls.kt` | 删除 `matchLine`/`onOtherChannels` 参数、比分条、其他频道按钮 |
| `ui/components/MobileIcon.kt` | 删除 `MobileIcons.Sports`；确认无其他引用后删 |
| `app/src/main/AndroidManifest.xml` | 加入 3 个 core 组件的 `tools:node="remove"`（见 4.4） |

`GuideMatchUi.kt`、`SettingsHomePage.kt`（除体育行）、`HomeScreen.kt`（除体育行）都保留。

### 4.3 启动与 DI

`OwnTVMobileApp` 最终应变成：

```kotlin
// 不再设置 MatchAlertHost.backgroundAllowed = true；默认 false 是清理旧闹钟的前提。
startKoin {
    androidContext(this@OwnTVMobileApp)
    modules(
        // ... 其他模块
        // sportsModule 删除
    )
}
```

删除：

```kotlin
appScope.launch {
    delay(SPORTS_START_DELAY_MS)
    runCatching { GlobalContext.get().get<SportsRepository>().appStarted() }
}
```

`SportsRepository`、`SportsCatalog`、`SportsConfig`、`MatchAlertHost` 等 core 类保留在依赖里（D8），但 App 不再创建/调用它们。

### 4.4 Manifest 移除 core 组件

`app/src/main/AndroidManifest.xml` 的 `<application>` 内加入：

```xml
<!-- 手机端移除体育后台：core 的 Manifest 会合并进来，这里显式去掉。
     旧闹钟由 4.5 的 rearmAll 清理；移除组件后旧 AlarmManager 项即使触发也不会送达。 -->
<receiver
    android:name="tv.own.owntv.core.sports.MatchAlertReceiver"
    tools:node="remove" />
<receiver
    android:name="tv.own.owntv.core.sports.MatchAlertBootReceiver"
    tools:node="remove" />
<service
    android:name="tv.own.owntv.core.sports.LiveMatchCardService"
    tools:node="remove" />
```

不要删除 `POST_NOTIFICATIONS`、`SCHEDULE_EXACT_ALARM`、`RECEIVE_BOOT_COMPLETED` 等权限：它们还被下载、播放、提醒、同步使用。App 没有体育专属权限需要删。

### 4.5 旧闹钟与旧通知清理

升级后第一次启动，`MatchAlertHost.backgroundAllowed` 已经是默认 `false`。core 的 `MatchAlertScheduler.rearmAll()` 在这个条件下会走 `cancelAll()`：

```kotlin
appScope.launch {
    runCatching {
        GlobalContext.get().get<tv.own.owntv.core.sports.MatchAlertScheduler>().rearmAll()
        stopService(Intent(this@OwnTVMobileApp, tv.own.owntv.core.sports.LiveMatchCardService::class.java))
    }
}
```

说明：

- `MatchAlertScheduler` 在 core 的 `DataModule` 里有 `single` 定义，可以直接注入。
- `rearmAll()` 读 `AlertState`，`backgroundAllowed == false` 时调用 `cancelAll()`，取消所有比赛闹钟和提醒闹钟。
- `stopService` 停掉可能还在前台的通知卡片；组件已从 Manifest 移除时它通常返回 `false`，`runCatching` 兜底。
- 旧 `AlertState` 文件可以保留（不再被读取），不删除用户数据。
- 旧 WorkManager 任务如果还在队列里，会因为 `backgroundAllowed == false` 自行退出；验收时确认升级后不再出现体育通知。
- 清理只做一次；后续启动 `rearmAll()` 也会保持取消状态。

### 4.6 core 残留（D8）

本期不删：

- `tv.own.owntv.core.sports.*` 的所有类（`SportsRepository`、`SportsCatalog`、`SportsConfig`、`MatchAlertScheduler`、`SportsActivityTracker` 等）。
- core 的 `strings_features.xml`/`strings_settings.xml` 里的体育文案。
- core 的 `HomeRow.LIVE_SPORTS` 枚举（App 在读取 Home 配置时过滤）。
- core 的 `StageGlyph.SPORTS`（`MobileIcons.Sports` 删除后不再引用）。

验收口径：**App 内无入口、无后台、无通知**；APK 里仍有 core 的体育类不算失败。

### 4.7 测试与验收

- 合并 Manifest 检查：`./gradlew :app:processStandardDebugMainManifest`，确认 `MatchAlertReceiver`、`MatchAlertBootReceiver`、`LiveMatchCardService` 不在合并后的 Manifest 里。
- 安装升级包（旧版先排一个比赛提醒）→ 启动 → 等待/触发旧闹钟时间 → 不应有体育通知。
- 检查底部栏 4 项、侧栏无 Sports、Home 无体育行、设置无体育页、播放器无比分条/其他频道、同步提示无体育行。
- 深链测试：旧通知里的 match key 不再打开比赛页（App 不认识该 extra）。
- `./gradlew :app:assembleStandardDebug` 通过。
- 回归：EPG 自动匹配（`GuideMatchUi`）仍正常。

---

## 5. 多账户管理移除 → 单档案

### 5.1 目标模型

- App 永远只有一个**活动档案**；`settings.activeProfileId` 指向它。
- 旧库里的其他档案保留但**休眠**：不列出、不切换、不删除、不备份、不同步。
- 没有“谁在看”选择器，没有 Add profile，没有编辑/删除/换头像/改名入口。
- App 锁、儿童模式、分类锁、设置 PIN 门保留；它们都作用于活动档案。
- 备份/同步只处理活动档案；收到多档案/匿名/与休眠档案同名的文件时拒绝（D9）。

**已知取舍**：本期不提供单档案改名/换头像 UI。旧活动档案保留原名称/头像；新装默认名是 core 的 `setup_default_profile`（“Profile”）。如果产品后续需要，可加一行 Settings → App → Profile，复用精简版 `ProfileEditorSheet`，约 +0.5 人日。

### 5.2 启动恢复与 Shell 分支

`ShellViewModel` 增加一次性恢复状态，解决“升级后 activeProfileId 指向已删除/无效行”与“Room 还没回答就闪 Setup”的两个问题。

`ui/shell/ShellViewModel.kt`：

```kotlin
private val _recovering = MutableStateFlow(true)
val recovering: StateFlow<Boolean> = _recovering.asStateFlow()

init {
    viewModelScope.launch {
        runCatching {
            val all = profileDao.getAllOnce()
            val active = settings.activeProfileId.first()
            if (all.isNotEmpty() && all.none { it.id == active }) {
                settings.setActiveProfile(all.first().id)
            }
        }
        _recovering.value = false
    }
}
```

`ui/shell/MobileShell.kt` 的门禁分支改成：

```kotlin
val shellViewModel: ShellViewModel = koinViewModel()
val recovering by shellViewModel.recovering.collectAsStateWithLifecycle()
if (recovering) return

val profilesViewModel: ProfilesViewModel = koinViewModel()
val gateSession: ProfileGateSession = koinViewModel()
val profiles by profilesViewModel.profiles.collectAsStateWithLifecycle()
val activeProfileId by profilesViewModel.activeProfileId.collectAsStateWithLifecycle()

val loadedProfiles = profiles ?: return
val activeId = activeProfileId ?: return
val activeProfile = loadedProfiles.firstOrNull { it.id == activeId }
if (activeId < 0L || activeProfile == null) {
    // 没有可用档案：走 Setup。onDone 不做 unlock；档案落地后由下面的 LaunchedEffect 处理。
    SetupFlow(onDone = { _ -> }, modifier = modifier)
    return
}

LaunchedEffect(activeId) { gateSession.invalidateIfNotProfile(activeId) }

val locked = activeProfile.pinHash != null
LaunchedEffect(activeId, locked) { if (!locked) gateSession.unlock(activeId) }
if (locked && gateSession.unlockedProfileId != activeId) {
    ProfileGate(
        profile = activeProfile,
        onUnlocked = { gateSession.unlock(activeId) },
        modifier = modifier,
        vm = profilesViewModel,
    )
    return
}
```

删除：

- `profileGateRequired` / `shellMayCompose` 的 import 和调用。
- “More › Switch” 的 `choosingProfile` Dialog 和 `ProfileGate(profiles = ...)` 调用。
- `onSwitchProfile` 从 `MobileShell` → `MobileNavHost` 的传递。
- 旧注释里“Who is watching?”的多档案描述，改成单档案门禁说明。

为什么 `SetupFlow(onDone = { _ -> })`：

- 旧代码把 `onDone(profileId)` 当成“本次会话已解锁”。单档案下这会让“恢复了一个带 PIN 的档案”直接绕过 App 锁。
- 新逻辑只让 `activeProfile.pinHash` 决定是否上锁；设置完成后 recomposition 自然进入门禁。无 PIN 的新档案由 `LaunchedEffect` 自动解锁。

### 5.3 `ProfileGate` 改写

`ui/profiles/ProfileGate.kt` 从“头像选择器”改成“单档案解锁页”：

```kotlin
@Composable
fun ProfileGate(
    profile: ProfileEntity,
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier,
    vm: ProfilesViewModel,
)
```

布局：

- Stage 背景 + 顶部品牌（复用 `BrandMark`/`Wordmark`）。
- 96–104dp `ShellAvatar(profile, null, ...)`。
- 档案名。
- `StageField`：数字密码键盘，4–6 位，`isError` + `profiles_wrong_pin`。
- 主按钮“解锁”（`mobile_unlock`），长度不足时禁用。
- 不做“忘记 PIN”链接；见 5.12 的风险与后续选项。

行为：

- 提交时在 `Dispatchers.Default` 调 `vm.verifyPin(profile, pin)`（PIN 哈希不应阻塞主线程）。
- 成功：`onUnlocked()`；失败：清空输入、显示错误、保持页面。
- 连续失败 5 次后按钮冷却 30 秒（页面内状态，不持久化），降低 4–6 位 PIN 被暴力尝试的风险。
- 页面全屏替代 App，不是一个可返回的 Dialog；返回手势不能露出后面的内容。
- 不显示“其他档案”或“添加档案”。

删除 `ProfileTile`、`AddProfileTile`、`AVATAR_SIZE`、`pinFor`、`addProfile` 等旧选择器代码。

### 5.4 `ProfileGateSession` 不动

`ui/profiles/ProfileGateSession.kt` 保持原样：

- `unlockedProfileId` 是“本次进程已解锁的档案 id”，不是布尔值。
- ViewModel 生命周期：旋转/字体缩放不重新问 PIN；进程被杀后重新问。
- `invalidateIfNotProfile(activeId)` 继续在 Shell 里调用；活动档案变化（恢复/采用）会清掉旧解锁。

### 5.5 `ProfilesViewModel` 与 `ProfileSheets` 收口

`ui/profiles/ProfilesViewModel.kt` 只保留门禁需要的三件事：

```kotlin
class ProfilesViewModel(
    profileDao: ProfileDao,
    settings: SettingsRepository,
    private val manager: ProfileManager,
) : ViewModel() {
    val profiles: StateFlow<List<ProfileEntity>?> = profileDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val activeProfileId: StateFlow<Long?> = settings.activeProfileId
        .map<Long, Long?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun verifyPin(profile: ProfileEntity, pin: String): Boolean = manager.verifyPin(profile, pin)
}
```

删除：`context`、`downloadsAllowed`、`setDownloadsAllowed`、`switchTo`、`create`、`edit`、`setPicture`、`clearPicture`、`delete`。

DI：`ProfilesViewModel(get(), get(), get())`。

`ui/profiles/ProfileSheets.kt`：

- **保留** `ProfilePinSheet`（App 锁当前 PIN 验证、关闭儿童模式验证）。
- **删除** `ProfileEditorSheet`（130–283 行）和只为它服务的 `AvatarStrip`/图片选择逻辑。
- 把 `PIN_MIN`/`PIN_MAX` 从 `private` 改成 `internal`，供 App 锁的新 PIN 表单复用。
- 保留 `SheetButtons`。

### 5.6 导航与 More

`ui/screens/MoreScreen.kt`：

- 删除 `onOpenProfiles`、`onSwitchProfile` 参数。
- `ProfileCard` 保留：“头像 + 名称 + 当前播放列表 · N 个播放列表”的玻璃卡。
- 删除卡片的点击和 **Switch** 按钮；卡片只显示信息。
- 顶部注释从“profile card with Switch”改成“current profile card”。

`ui/nav/MobileNavHost.kt`：

- 删除 `onSwitchProfile` 参数。
- More 分支删除 `onOpenProfiles`/`onSwitchProfile`。
- 删除 `SettingsGroup.PROFILE.route` 的导航。

### 5.7 设置页重组

#### 5.7.1 删除 Profile 组

| 文件 | 改法 |
|---|---|
| `ui/screens/settings/SettingsProfilePage.kt` | **整文件删除** |
| `ui/screens/settings/SettingsCommon.kt` | `SettingsGroup` 删除 `PROFILE`（12 → 11 组） |
| `ui/screens/settings/SettingsScreen.kt` | 删除 `SettingsGroup.PROFILE` 的特殊计数分支；更新相关注释 |
| `ui/screens/settings/SettingsGroupRows.kt` | 删除 `profileSections()` 分发 |
| `ui/screens/settings/SettingsLeaf.kt` | 删除 Profile 相关 leaf（如有） |
| `ui/screens/settings/SettingsDestination.kt` | 删除 Profile 路由分发（如有） |
| `ui/screens/settings/BackupViewModel.kt` | 不再需要 `profiles`/`activeProfileId` 的公开流（导出选择用不到了） |

`SettingsProfilePage` 删除后，`SettingsSearchCoverageTest` 的页面扫描会自动少一个文件；测试里的 `notSettings` 豁免列表同步删掉 profile 相关键。

#### 5.7.2 App 锁

新增 `ui/screens/settings/SettingsAppLock.kt`：

- **没有 PIN**：显示“设置 App 锁”，输入新 PIN + 确认；不一致用 `settings_customize_pin_mismatch`；确认后 `vm.setAppLockPin(pin)`。
- **已有 PIN**：显示“更改 PIN”和“移除 App 锁”；两者都先用 `ProfilePinSheet` 验证当前 PIN；验证通过后，更改进入新 PIN 表单，移除调用 `vm.setAppLockPin("")`。
- 说明文案（`mobile_app_lock_warning`）：
  - 下次启动 App 时询问；
  - 同一个 PIN 也保护“设置 PIN 门”和分类锁；
  - 忘记 PIN 只能清除应用数据（本期）。
- 保存后不需要立刻锁屏：当前会话已经通过门禁，下次冷启动生效。

`SettingsViewModel` 新增：

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
val activeProfile: StateFlow<ProfileEntity?> = settings.activeProfileId
    .flatMapLatest { id -> if (id < 0) flowOf(null) else profileDao.observeById(id) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

fun verifyPin(profile: ProfileEntity, pin: String): Boolean = profileManager.verifyPin(profile, pin)

/** null 保留现有 PIN；"" 移除；其他值设置。 */
fun setAppLockPin(pin: String?) {
    viewModelScope.launch {
        val id = settings.activeProfileId.first()
        val profile = profileDao.getById(id) ?: return@launch
        profileManager.edit(profile, profile.name, profile.avatarId, profile.isKids, pin)
    }
}
```

构造器新增 `profileManager: ProfileManager`；`SettingsModule` 的 `SettingsViewModel(...)` 加 `profileManager = get()`。

#### 5.7.3 儿童模式

放在 Settings → Content 的第一组（`contentSections`）：

```kotlin
val active by vm.activeProfile.collectAsStateWithLifecycle()
var kidsPin by remember { mutableStateOf(false) }

if (kidsPin) {
    ProfilePinSheet(
        profileName = active?.name.orEmpty(),
        onSubmit = { pin ->
            val ok = active?.let { vm.verifyPin(it, pin) } == true
            if (ok) { vm.setKidsMode(false); kidsPin = false }
            ok
        },
        onDismiss = { kidsPin = false },
    )
}

switchItem(
    "kids_mode",
    MobileIcons.Person,
    stringResource(R.string.settings_profile_kids),
    stringResource(R.string.mobile_kids_mode_description),
    active?.isKids == true,
) { on ->
    if (!on && active?.pinHash != null) kidsPin = true else vm.setKidsMode(on)
}
```

`SettingsViewModel`：

```kotlin
fun setKidsMode(enabled: Boolean) {
    viewModelScope.launch {
        val id = settings.activeProfileId.first()
        val profile = profileDao.getById(id) ?: return@launch
        profileManager.edit(profile, profile.name, profile.avatarId, enabled, pin = null)
    }
}
```

规则：

- 打开儿童模式不需要 PIN；关闭时如果活动档案有 PIN，先验证。
- 没有 PIN 时，任何人可以关闭儿童模式；`mobile_kids_mode_description` 提示“设置 App 锁可防止被关闭”。
- `isKids` 的内容过滤仍由 core 负责，本方案不改过滤规则。

#### 5.7.4 允许下载

放在 Settings → App → Downloads 组，排在“下载文件夹”和“仅 Wi-Fi”之前：

```kotlin
val downloadsAllowed by s.activeDownloadsAllowed.pref(true)
switchItem(
    "downloads_allowed",
    MobileIcons.Download,
    stringResource(R.string.profiles_allow_downloads),
    stringResource(R.string.profiles_allow_downloads_description),
    downloadsAllowed,
) { vm.setDownloadsAllowed(it) }
```

`SettingsViewModel`：

```kotlin
fun setDownloadsAllowed(allowed: Boolean) {
    viewModelScope.launch {
        val id = settings.activeProfileId.first()
        if (id >= 0) settings.setDownloadsAllowed(id, allowed)
    }
}
```

旧的 `ProfileEditorSheet` 里的 `Allow downloads` 开关随编辑页一起删除；行为不变，只是位置变了（D5）。

#### 5.7.5 App 组删除体育行、语言标签白名单

- `appSections` 删除 `SettingsLeaf.SPORTS` 行。
- `localeLabel` 改用 `AppLocales.rows`：

```kotlin
@Composable
private fun localeLabel(tag: String): String =
    if (tag.isBlank()) stringResource(R.string.settings_language_device)
    else AppLocales.rows.firstOrNull { it.languageTag == tag }?.endonym ?: tag
```

- `SettingsGroupRows.kt` 里新增的 `mobile_*` 字符串会被 `SettingsSearchCoverageTest` 自动索引；不需要手工改索引。

### 5.8 Setup 简化

#### 5.8.1 流程

删除 `CREATE_PROFILE` 和 `EXISTING` 两步：

```
WELCOME → DISPLAY_SIZE → DISCLAIMER → CHOICE
                                      ├─ SYNC_DEVICE
                                      ├─ ADD_CONTENT → FORM → IMPORTING
                                      └─ RESTORE → IMPORTING
```

- `SetupEntry` 删除 `EXISTING`，保留 `ADD_CONTENT`、`FORM`、`IMPORT`（从 Settings 的“添加源”进入）。
- `Step` 删除 `CREATE_PROFILE`、`EXISTING`；`Step.SYNC_DEVICE` 保持不变。
- `AddContentStep` 删除 `hasExisting`/`onExisting`；不再有“使用已有播放列表”的入口。
- `Step.ADD_CONTENT` 的 Back：`firstRun` 时回 `CHOICE`，否则回 `onCancel`。
- `SetupChoiceStep`：
  - “Start fresh”（`mobile_start_fresh` / `mobile_start_fresh_description`）→ `ADD_CONTENT`；
  - “Restore a backup”（`mobile_restore_backup_description`）→ `RESTORE`；
  - “Sync from a device”保持不变。
- `WizardFrame.WizardStep.PROFILE` 改成 `CONTENT`（步骤条不再显示“Profile”）。
- 删除 `ProfileStep`、`AvatarStrip`、`PIN_MIN`/`PIN_MAX`（Setup 不再建档案）。
- `SetupWizardSteps` 的语言选择器改用 `AppLocales.rows`。

#### 5.8.2 档案落地

`SetupViewModel` 删除 `createProfile`、`availableExistingSources`、`linkExisting`；保留 `attachToProfile`（导入前确保有档案）和 `reset`/`cancelImport`。

`finish` 不再调用 `importer.finish()`，改成：

```kotlin
fun finish(onDone: (Long?) -> Unit) {
    appScope.launch {
        val existing = settings.activeProfileIdNow().takeIf { it >= 0 }
            ?: profileDao.getAllOnce().firstOrNull()?.id
        if (existing != null) {
            if (settings.activeProfileIdNow() != existing) profiles.switchTo(existing)
            onMain { onDone(existing) }
            return@launch
        }
        val id = profiles.create(
            name = context.getString(R.string.setup_default_profile),
            avatarId = 0,
            isKids = false,
            pin = null,
            fallbackName = context.getString(R.string.setup_default_profile),
        )
        profiles.switchTo(id)
        onMain { onDone(id) }
    }
}
```

说明：

- `attachToProfile` 在导入前如果库为空，会用 `importer.createProfile(...)` 建一个默认档案；`finish` 通过 `activeProfileIdNow()` 或 `getAllOnce().firstOrNull()` 找到它，不再重复创建。
- 新装且用户直接“Skip”：没有档案，`finish` 用 `ProfileManager.create` 建默认档案。
- 恢复的档案如果带 PIN，`Shell` 会显示 App 锁；`onDone` 不再直接解锁。

#### 5.8.3 Setup 中的备份策略

Setup 不能像 Settings 那样在恢复前展示重命名提示，也不能可靠地回滚，所以策略更严格：

| 入站档案 | 设备状态 | 结果 |
|---|---|---|
| >1 个 | 任意 | 拒绝 `mobile_multi_profile_import` |
| 1 个无名称 | 任意 | 拒绝 `mobile_nameless_profile_import` |
| 1 个同名 | 已有活动档案 | 允许 |
| 1 个名 = 休眠档案 | 已有活动档案 | 拒绝 `mobile_hidden_profile_import` |
| 1 个不同名 + 含 SOURCES | 已有活动档案 | 拒绝 `mobile_restore_from_settings`（引导去 Settings → Backup & Restore） |
| 1 个不同名 + 不含 SOURCES | 已有活动档案 | 允许，不改名 |
| 0 个（仅设置/旧格式） | 任意 | 允许，交给 core |
| 损坏/无法读取 | 任意 | 允许，交给 core 报错 |

`SetupViewModel` 新增：

```kotlin
private val _blocked = MutableStateFlow<Int?>(null)
val blocked: StateFlow<Int?> = _blocked.asStateFlow()

fun dismissBlocked() { _blocked.value = null }
```

`importBackup` / `restoreWithPassword` 在调用 core 之前先 `BackupProfiles.decide`：

- 明文文件：`importBackup` 里直接读摘要并决策。
- 密封文件：`importBackup` 允许 core 返回 `NeedPassword`；`restoreWithPassword` 拿到密码后再读摘要并决策。
- 拒绝时不调用 core，`pending` 文件可保留给用户重新选择；`_blocked` 显示提示。
- `SetupFlow` 根节点显示一个 `MobileBottomSheet`，标题用 `settings_backup_restore_title`，正文 `stringResource(vm.blocked)`，OK 调 `dismissBlocked()`。

### 5.9 备份策略

#### 5.9.1 导出

只导出当前档案：

- `BackupViewModel.exportRemote`、`exportForShare`、`export` 删除 `profileIds` 参数；内部用 `setOf(activeId)`。
- `ExportRequest` 删除 `profileIds`：

```kotlin
internal data class ExportRequest(
    val sections: Set<BackupManager.Section>,
    val password: String?,
)
```

- `ExportSheet` 删除 `profiles`、`activeProfileId`、`verifyPin`、`profileIds`、`ProfilePinSheet`、`for each profile` 的 SwitchRow。
- 保留“备份内容（sections）”和“加密密码”。
- 在 sheet 顶部加一行 `mobile_backup_single_profile_note`：“备份只包含当前档案”。
- `confirmEnabled = sections.isNotEmpty()`。
- 如果 `activeId < 0`（理论上不会发生），返回 `Outcome.Failed(R.string.settings_backup_export_error)`。

导出文件格式不变：单档案的 `profiles` 数组长度为 1。旧 App 可以导入。

#### 5.9.2 恢复

`BackupViewModel` 的 `PendingRestore` 扩展：

```kotlin
data class PendingRestore(
    val file: File,
    val inspection: BackupManager.Inspection? = null,
    val password: String? = null,
    val sealed: Boolean = false,
    val wrongPassword: Boolean = false,
    val summary: BackupProfiles.Summary? = null,
    val deviceProfile: ProfileEntity? = null,
    val otherNames: List<String> = emptyList(),
)
```

`Outcome` 增加：

```kotlin
sealed interface Outcome {
    data class Exported(val name: String, val passwordsOmitted: Boolean) : Outcome
    data class Restored(val summary: BackupManager.ImportSummary) : Outcome
    data class Refused(@param:StringRes val messageRes: Int) : Outcome
    data class Failed(@param:StringRes val messageRes: Int) : Outcome
}
```

流程：

1. `beginRestore(file)`：
   - 读活动档案和设备上的其他档案名。
   - 明文文件：`BackupProfiles.read(file)` 得到摘要；`MULTIPLE`/`UNNAMED` 立即 `Outcome.Refused`，删除缓存文件，不展示 sections。
   - 密封文件：只设置 `sealed = true`，等密码。
2. `unseal(password)`：
   - 成功后 `BackupProfiles.read(file, password)`；同样先做 `MULTIPLE`/`UNNAMED` 早期拒绝。
   - 失败保持 `wrongPassword`。
3. `decisionFor(sections)`：
   - 用 `pending.summary`、`pending.deviceProfile`、`pending.otherNames`、当前 sections 计算完整决策。
   - `RestoreSheet` 每次切换 section 调用它：
     - `block == HIDDEN`：显示 `mobile_hidden_profile_import`，禁用恢复；
     - `renameTo != null`：显示 `mobile_profile_rename_notice`（带目标名）；
     - 其他：正常。
   - `RestoreSheet` 在 `pending.sealed` 时额外显示 `mobile_restore_pin_notice`。
4. `restore(sections, password, deviceSettings)`：
   - 再计算一次 `decisionFor(sections)`，防止 sheet 数据过期。
   - `block != null`：`Outcome.Refused`，删除文件，清 `pending`。
   - `renameTo != null`：`renameDeviceProfile(profileDao, deviceProfile, renameTo)`，然后再 `backup.import(...)`。
   - `backup.import` 成功：`Outcome.Restored`，保留新名字。
   - 失败/取消：用 `withContext(NonCancellable) { restoreProfileName(profileDao, original) }` 回滚名字，再按原逻辑处理 `WrongPassword` / `Failed`。
   - 进程在恢复中被杀：名字可能已经改、数据未落地；这是已知风险（见第 8 节），本期不加 rename journal。

`SettingsBackupPage`：

- 删除 `profiles`/`activeProfileId` 的收集和传给 `ExportSheet` 的参数。
- `RestoreSheet` 增加 `decisionFor`、`sealed` 参数，渲染重命名/PIN 提示和阻断错误。
- `OutcomeSheet` 增加 `Refused` 分支，用 `stringResource(outcome.messageRes)` 显示。
- `ProfilePinSheet` import 删除（导出不再验证他档 PIN）。

#### 5.9.3 新增 `BackupProfiles`

放在 `app/src/main/java/tv/own/owntv/mobile/backup/BackupProfiles.kt`（中性包，Settings/Setup/LocalSync 共用）：

```kotlin
object BackupProfiles {
    data class Summary(val count: Int, val name: String?)
    enum class Block { MULTIPLE, HIDDEN, UNNAMED }
    data class Decision(val block: Block? = null, val renameTo: String? = null)

    fun read(file: File, password: String? = null): Summary? = runCatching {
        val json = when (BackupContainer.probe(file)) {
            BackupContainer.Kind.ENCRYPTED_CONTAINER -> BackupContainer.open(file, password).json
            BackupContainer.Kind.CONTAINER -> ZipFile(file).use { zip ->
                zip.getEntry(BackupContainer.JSON_ENTRY)?.let { entry ->
                    zip.getInputStream(entry).bufferedReader().readText()
                }
            }
            BackupContainer.Kind.LEGACY_JSON -> file.readText()
            BackupContainer.Kind.UNKNOWN -> null
        } ?: return@runCatching null

        val array = JSONObject(json).optJSONArray("profiles") ?: return@runCatching Summary(0, null)
        Summary(
            count = array.length(),
            name = (array.optJSONObject(0)?.opt("name") as? String)?.takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    fun decide(
        summary: Summary?,
        deviceProfile: ProfileEntity?,
        otherNames: List<String>,
        sections: Set<BackupManager.Section>,
    ): Decision {
        if (summary == null) return Decision()
        if (summary.count > 1) return Decision(block = Block.MULTIPLE)
        if (summary.count != 1) return Decision()
        val incoming = summary.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Decision(block = Block.UNNAMED)
        if (deviceProfile == null) return Decision()
        if (BackupManager.Section.SOURCES !in sections) return Decision()

        val key = incoming.lowercase()
        val matchesDormant = otherNames.any { it.trim().lowercase() == key }
        return when {
            matchesDormant -> Decision(block = Block.HIDDEN)
            key == deviceProfile.name.trim().lowercase() -> Decision()
            else -> Decision(renameTo = incoming)
        }
    }
}
```

规则表：

| 摘要 | deviceProfile | sections 含 SOURCES | 结果 |
|---|---|---|---|
| null（读不出/加密未解） | 任意 | 任意 | 允许，交给 core 报错 |
| count > 1 | 任意 | 任意 | `MULTIPLE` |
| count == 0 | 任意 | 任意 | 允许（仅设置/旧格式） |
| count == 1，name 空 | 任意 | 任意 | `UNNAMED` |
| count == 1，无活动档案 | null | 任意 | 允许 |
| count == 1，同名 | 有 | 任意 | 允许 |
| count == 1，名 = 休眠档案 | 有 | 是 | `HIDDEN` |
| count == 1，不同名 | 有 | 是 | `renameTo = incoming` |
| count == 1，不同名 | 有 | 否 | 允许，不改名 |

辅助函数：

```kotlin
suspend fun renameDeviceProfile(profileDao: ProfileDao, profile: ProfileEntity, name: String): ProfileEntity {
    val renamed = profile.copy(name = name.trim())
    profileDao.update(renamed)
    return renamed
}

suspend fun restoreProfileName(profileDao: ProfileDao, original: ProfileEntity) {
    val current = profileDao.getById(original.id) ?: return
    if (current.name != original.name) profileDao.update(current.copy(name = original.name))
}
```

说明：

- `read` 使用 Android `org.json`，不能直接 JVM 单测（`isReturnDefaultValues = true` 下 `JSONObject` 返回默认值）。单测只测 `decide`；`read` 用设备上的 `BackupProfilesReadTest`（androidTest）或手工矩阵覆盖。
- 明文 ZIP 只读 `backup.json` 条目，不读其他条目；密封容器用 `BackupContainer.open`，密码错误会抛异常，`runCatching` 返回 null，由 core/UI 报错。
- `count == 0` 允许是刻意的：旧格式/仅设置备份没有 `profiles` 数组，不能因为读不到档案就拒绝。
- `HIDDEN` 的文案是“这个备份的档案名和本机一个休眠档案重名”，提示用户去另一台设备改名或全新安装恢复。

### 5.10 本地同步策略

#### 5.10.1 只同步当前档案

`LocalSyncViewModel` 构造器改成：

```kotlin
class LocalSyncViewModel(
    private val sync: LocalSyncManager,
    private val settings: SettingsRepository,
    private val profileDao: ProfileDao,
) : ViewModel()
```

- `startHosting()`：取活动档案，`sync.startHosting(profileIds = setOf(activeId))`；没有活动档案则 `error = SyncFailure.Unknown`。
- `start(sections)` 的 SEND：`sync.send(device, sections, setOf(activeId))`。
- `confirm(...)` 合并成功后的回传：`sync.send(device, current.sections, setOf(activeId))`。
- 这样托管出去的容器、发出去的容器、回传的容器都只含一个档案。

#### 5.10.2 入站校验

`Step.Confirm` 增加：

```kotlin
data class Confirm(
    val device: PairedDevice?,
    val direction: SyncDirection,
    val file: File,
    val preview: BackupManager.Preview,
    val sections: Set<BackupManager.Section>,
    val password: String?,
    val renameTo: String? = null,
    val originalProfile: ProfileEntity? = null,
) : Step
```

- `previewIncoming` / `fetch` 返回后，读摘要并 `BackupProfiles.decide`：
  - `block != null`：设置 `blocked = messageRes`，删除缓存文件，不进入 Confirm。
  - `renameTo != null`：进入 Confirm，显示 `mobile_profile_rename_notice_sync`。
- `confirm(deviceSettings)`：
  - 如果有 `renameTo`，先 `renameDeviceProfile`；
  - `sync.apply(...)`；
  - 失败且已改名：`withContext(NonCancellable) { restoreProfileName(...) }`；
  - 成功：保留新名字，回传当前档案。
- Confirm sheet 在密封同步时显示 `mobile_sync_pin_notice`（同步包可能带 PIN）。
- `blocked` 是 `Int?`（`@StringRes`），新增 `dismissBlocked()`；`SettingsLocalSyncPage` 根节点显示 BlockedSheet。

#### 5.10.3 页面调整

- `SettingsLocalSyncPage.kt`：`WizardStep.PROFILE` → `WizardStep.CONTENT`；确认页增加重命名/PIN 提示；根节点增加 BlockedSheet。
- 同步的 sections 选择、配对、PIN、发现、二维码都不变；只是不再有“选择档案”这一步。
- `SettingsLocalSyncPage` 的 Setup 版本（本地同步 onboarding）同样把步骤条改为 `CONTENT`。

### 5.11 数据兼容与回滚

| 数据 | 本方案行为 | 降级回旧版 |
|---|---|---|
| 多档案行 | 保留；只显示活动档案 | 全部恢复显示、可切换 |
| 活动档案 id | 保留；无效时恢复为第一个 | 保持 |
| `downloadsAllowed` | 保留；开关移到 App 组 | 旧编辑页仍能读 |
| `isKids` | 保留；开关移到 Content 组 | 旧编辑页仍能读 |
| `pinHash` | 保留；App 锁/设置门/分类锁共用 | 旧门禁仍能用 |
| 旧体育配置 | 保留但不读 | 旧版重新启用 |
| Home `LIVE_SPORTS` | 读取时过滤 | 旧版重新显示 |
| 旧语言标签 | 归一化写回 | 旧版重新选择 |
| 单档案备份 | 新 App 可导出/恢复 | 旧 App 可导入 |
| 多档案备份 | 新 App 拒绝，文件不动 | 旧 App 可导入 |
| 同步容器 | 只含当前档案 | 旧 App 可接收单档案 |

回滚方式：revert 本次提交即可；数据层没有不兼容迁移。唯一不可逆的是 APK 不再打包旧语言资源。

### 5.12 权限、数据范围、认证与审计

#### 5.12.1 角色矩阵

| 角色 | 可以 | 不可以 | 认证 |
|---|---|---|---|
| 未解锁用户 | 看锁屏、输入 PIN | 进入内容/设置/备份/同步/播放器 UI | App 锁 PIN |
| 已解锁成人 | 全部内容、设置、备份、同步、App 锁管理 | — | App 锁 PIN |
| 儿童模式使用者 | 儿童内容；开启儿童模式 | 关闭儿童模式（有 PIN 时）、访问被锁分类 | 关闭时验证 PIN |
| 局域网同步对端 | 发送/接收当前档案的同步包 | 选择其他档案、发送多档案、接收多档案 | 配对 PIN + 会话密钥 |
| 伴侣服务器访问者 | 下载/上传备份 | 绕过 companion PIN | core PIN |
| 备份文件持有者 | 读取/恢复单档案备份 | 恢复多档案/匿名备份 | 密封密码（可选） |

#### 5.12.2 数据范围

| 数据 | 范围 | 说明 |
|---|---|---|
| 收藏/历史/续播 | 活动档案 | 不变 |
| 首页顺序/隐藏、分类隐藏/置顶 | 活动档案 | 不变 |
| 启动页/启动频道 | 活动档案 | 不变 |
| 分类锁 | 活动档案 | 不变，PIN 共用 |
| 下载许可 | 活动档案 | 开关位置变化 |
| 备份导出 | 活动档案 | `setOf(activeId)` |
| 本地同步托管/发送/回传 | 活动档案 | `setOf(activeId)` |
| 体育配置 | 休眠 | 不读、不写、不展示 |

#### 5.12.3 内部接口认证

| 接口 | 认证 | 本方案 |
|---|---|---|
| App 门禁 | `ProfileGateSession`（进程内） | 重写页面，保留会话 |
| 设置 PIN 门 | 活动档案 `pinHash` + `SettingsPinSession` | 不动 |
| 分类锁 | 活动档案 `pinHash` + core `CategoryLockFlow` | 不动 |
| 伴侣服务器 | core PIN | 不动 |
| 本地同步 | core 配对 PIN + 会话密钥 | 不动；托管/发送只带当前档案 |
| 备份密封 | core 密码 | 不动 |

#### 5.12.4 审计

App 是本地优先产品，本方案**不新增远程审计/遥测**：

| 事件 | 记录方式 | 保留 |
|---|---|---|
| App 锁失败 | 页面内计数 + 冷却；诊断构建可看 logcat | 本次会话 |
| App 锁解锁 | 不记录（隐私） | — |
| 设置/移除 PIN | UI 确认；不写远程 | — |
| 备份导出/恢复 | `Outcome` UI + 现有 `CrashRecorder`/日志 | App 日志 |
| 本地同步 | `Step.Result` UI + `LocalSyncViewModel.failure` 日志 | App 日志 |
| 多档案拒绝 | UI 文案 | 本次会话 |
| 体育清理 | 一次性启动日志（可选） | logcat |

如果后续有合规要求，再单独设计审计存储；本期不引入。

#### 5.12.5 权限清单（不新增）

| 权限 | 用途 | 本方案 |
|---|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | IPTV/EPG/元数据 | 不变 |
| `POST_NOTIFICATIONS` | 下载/播放/更新 | 不变 |
| `FOREGROUND_SERVICE*` | 播放/同步/下载 | 不变 |
| `CAMERA` | 本地同步扫码 | 不变 |
| `ACCESS_COARSE_LOCATION` | 天气 | 不变 |
| `REQUEST_INSTALL_PACKAGES` | 自更新 | 不变 |
| `WRITE_EPG_DATA` / `READ_EPG_DATA` | 已显式移除 | 不变 |

体育移除不涉及权限删除；`POST_NOTIFICATIONS` 等仍被其他功能使用，不能顺手删。

#### 5.12.6 权限测试

- 冷启动、旋转、进程被杀后重新打开：有 PIN 必须重新解锁；无 PIN 直接进入。
- 深链/通知/PiP/播放服务/伴侣服务器/本地同步/Setup：都不能绕过 App 锁。
- 错误 PIN：只显示错误，不泄露档案名以外的信息；连续 5 次冷却。
- 关闭儿童模式：有 PIN 时必须验证；取消后保持开启。
- 分类锁：仍要求 PIN；移除 App 锁 PIN 前提示“分类锁和设置 PIN 门也会失效”。
- 设置 PIN 门：仍按原有会话规则询问。
- 备份：多档案/匿名/休眠同名被拒绝；单档案同名可恢复；重命名有提示。
- 同步：托管/发送/回传只含当前档案；入站多档案被拒绝；配对 PIN 错误仍失败。
- 伴侣服务器：PIN 错误仍失败；上传多档案被拒绝。
- 忘记 PIN：本期无绕过；文档写明需清除应用数据。后续可选设备凭据重置（P2，未计入估算）。

### 5.13 测试与验收

**单测**

- `BackupProfilesPolicyTest`：只测 `Summary`/`Decision` 纯策略，覆盖 5.9.3 的规则表，包括大小写/空白、休眠同名、无 SOURCES、`deviceProfile == null`。
- `AppLocalesTest`、`MobileStringsParityTest`：见 3.9。
- 更新 `SettingsSearchCoverageTest`：删除 Profile 页/体育页相关豁免；确认新增的 App 锁/儿童模式/下载开关字符串被索引。
- 现有测试全跑：`./gradlew :app:testStandardDebugUnitTest`。

**手工矩阵**

见第 7 节。

**验收**

- 全新安装：Welcome → Display size → Disclaimer → Choice → Start fresh → 添加源/跳过 → 默认档案 → 无选择器直接进入。
- 升级安装（3 个档案，active 是第 2 个）：直接进入第 2 个档案；More 只显示当前档案卡；设置无 Profile 组；旧档案数据仍在。
- App 锁：设置/更改/移除；冷启动询问；旋转不重复；进程被杀后重复；错误 PIN 有提示和冷却。
- 儿童模式：开/关；有 PIN 时关闭需验证；无 PIN 时可自由关闭。
- 下载：移到 App → Downloads；关闭后下载按钮消失；旧档案的值保留。
- 备份：导出只含当前档案；单档案同名恢复；不同名提示重命名；多档案/匿名/休眠同名拒绝；密封文件先密码；PIN 覆盖提示出现。
- 同步：托管/发送/回传只含当前档案；入站多档案拒绝；不同名提示；失败回滚名字。
- 体育：无入口/无后台/无通知；旧闹钟清理。
- i18n：APK 只含 3 种语言；选择器 3 行；旧标签归一化。

---

## 6. 实施阶段与排期

| 阶段 | 内容 | 产出 | 人日 |
|---|---|---|---:|
| 0 | 基线：建分支、跑现有测试、抓 3 个样本备份（单档案、多档案、密封）、记录当前 APK 语言列表 | 基线报告 | 0.5 |
| 1 | i18n：`AppLocales`、构建白名单、启动/组合/播放归一化、选择器、三份字符串、单测 | i18n 可独立验收 | 1 |
| 2 | 体育：删模块/页面、清引用、Manifest、旧闹钟清理、Home 过滤、测试 | 体育可独立验收 | 1.5–2 |
| 3a | 单档案 Shell/门禁：`ShellViewModel.recovering`、`MobileShell` 分支、`ProfileGate`、`ProfilesViewModel`、`ProfileSheets` | 冷启动门禁可用 | 1 |
| 3b | 设置：删 Profile 组、App 锁、儿童模式、下载开关、导航/More | 设置可用 | 1 |
| 3c | Setup：删两步、改 Choice、`finish`、`blocked`、字符串 | 新装/加源可用 | 0.5–1 |
| 3d | 备份/同步：`BackupProfiles`、导出单档案、恢复策略、重命名/回滚、BlockedSheet | 备份/同步可用 | 1–1.5 |
| 4 | QA + 文档：手工矩阵、修 P0/P1、6 份文档 + 本方案 | 可发布 | 2–3 + 1 |

依赖：阶段 1、2 独立；阶段 3 依赖 1 的字符串（App 锁/拒绝/重命名文案）。阶段 3a 必须先于 3b/3c/3d，因为 `activeProfile` 和门禁是它们的基础。

---

## 7. 测试计划

### 7.1 单元测试

| 测试 | 覆盖 |
|---|---|
| `AppLocalesTest` | 归一化、3 行、限定符 |
| `MobileStringsParityTest` | 三份 `strings_mobile.xml` 键一致 |
| `BackupProfilesPolicyTest` | 多档案/匿名/休眠同名/同名/不同名/无 SOURCES/空摘要 |
| `SettingsSearchCoverageTest`（更新） | 新设置行可搜索；删除的 Profile/体育行不再要求 |
| `StartupLiveSelectionTest` | 启动期 Live 选择跟随档案（单档案外壳下的 3 例） |
| 现有测试 | 全量回归 |

第 10 轮（2026-11-06 20:08–20:18）在本机重跑过 `:app:testStandardDebugUnitTest`：**5 个测试类、28 例、0 失败 0 跳过**（`BackupProfilesPolicyTest` 10 / `AppLocalesTest` 7 / `MobileStringsParityTest` 5 / `SettingsSearchCoverageTest` 3 / `StartupLiveSelectionTest` 3）；`:app:lintStandardDebug` 与 debug/release assemble 在同一条后台命令里一并复核，**已收尾**：日志 `app/build/reports/instrumented/r10_build.log`（399 行）末行是 **`BUILD SUCCESSFUL in 10m 32s`**、退出码 0，期间实际执行过 `:app:lintAnalyzeStandardDebug` / `:app:lintReportStandardDebug` / `:app:lintStandardDebug` / `:app:lintVitalStandardRelease`；`app/build/reports/lint-results-standardDebug.txt` 末尾记 **0 errors, 64 warnings, 5 hints**（`abortOnError = true` 下 0 错即通过，warnings 含上文那条 `FlowPreview` 预览 API 提醒）。产物本轮全部重新生成：`app-standard-debug.apk` 20:14 的 82 218 788 B、`app-standard-release-unsigned.apk` 20:18 的 54 650 611 B（release 仍缺签名，口径与下方表格一致）。

### 7.2 手工矩阵

| # | 场景 | 期望 | 实测（详见 §10.4） |
|---|---|---|---|
| 1 | 全新安装，跳过添加源 | 建默认档案，直接进入 App | ✅ 第 1 轮 |
| 2 | 全新安装，恢复单档案备份（带 PIN） | 恢复后显示 App 锁 | ✅ 第 7 轮：`pm clear` 后走向导到 "Start fresh, or bring back…" 那步点 **Restore a backup** → File on this phone → 手工样例 `row2-pin.own`（与真机导出只差 `profiles[0].pinHash` 写成 core 的 `Pin.hash("2468")` 形式 `0123456789abcdef:9985…`），WHAT TO RESTORE 直接给（无改名提示、无档案选择器）→ "8 items restored." 之后**当场**弹出 "Profile — enter PIN"；输 9999 得 "Wrong PIN — try again"，输 2468 进 Home（四格底栏）。顺带记录：设置向导这一步本身就是全新安装的恢复入口 |
| 3 | 全新安装，恢复多档案备份 | 拒绝，提示不能导入 | ✅ 第 5 轮：真机导入造好的 `fixture-multi.own`（3 档案）→ 弹窗原文 "This backup contains more than one profile. This app keeps a single profile, so it cannot be restored."，按 OK 后回到 Backup & Restore 页，档案名/语言/源都没变；`BackupProfilesPolicyTest` 10 例是同路径的单测 |
| 4 | 升级（3 档案，active 有效） | 直接进 active；其他档案不显示 | ✅ 第 6 轮：直接往装机库里加两行档案（id=2 Kids Profile、id=3 Dormant Old，activeProfileId 仍是 1）→ 冷启动落 Home（四格底栏 + Start watching），没有向导、没有门禁、没有档案选择器；用完一次之后 `profiles` 三行都还在，即旧档案数据没被动过（§10.4 第 6 轮那行） |
| 5 | 升级（active id 无效） | 自动选第一个档案，不闪 Setup | ✅ 第 6 轮：删掉 id=1，让存的 activeProfileId 指向不存在的档案 → 冷启动仍直接落 Home（单次 dump 看不到一闪而过的向导，所以严格说证据是"落地即 Home"而不是"绝对没闪屏"），无崩溃、无档案相关异常 |
| 6 | 设置 App 锁，冷启动 | 显示锁屏；正确 PIN 进入 | ✅ 第 1、3 轮（第 3 轮换的是新设的 PIN，不依赖上一轮残留） |
| 7 | 旋转/字体缩放 | 不重复问 PIN | ◐ 旋转半 ✅；字体缩放半 ⬜（第 7 轮只验证了 App 自带的字体控件确实生效：向导 "Make it comfortable to read" 里按 + 从 100% 走到 140%，每行实时重排、Continue 从 y=1107 移到 y=1238，走完向导后 App 仍是四格底栏。但"改完字体后门禁不再问 PIN"这一路当时锁还没设，没测成） |
| 8 | 进程被杀后重开 | 重新问 PIN | ◐ 第 7 轮：设好 App 锁 → 进 Home → 按 HOME 退到后台（`ps` 里进程还在，RSS 312 MB）→ `am kill tv.own.owntv.mobile`（框架对缓存进程的杀法，不是 force-stop）→ 进程消失 → 重新 `am start` → 又是 "Profile — enter PIN"，crash buffer 0 行、无 ANR。force-stop 等价场景（第 2 轮）也仍在。仍未做的是真正的 lowmemorykiller 水位压力：这台 headless 镜像造不出可复现的内存告警，但"进程被系统杀掉后不会带着已解锁状态回来"已经是真杀进程的观测 |
| 9 | 错误 PIN ×5 | 提示 + 冷却 | ✅ 第 1 轮；第 3 轮复现 "Wrong PIN — try again" |
| 10 | 儿童模式开/关（有 PIN） | 关闭需 PIN | ✅ 第 3 轮：开 ✅、关需 PIN ✅、Cancel 不放行 ✅、错 PIN 不放行 ✅、对 PIN 放行 ✅ |
| 11 | 下载开关关 | 下载按钮消失；开恢复 | ◐ 位置 ✅、On↔Off 往返 ✅；"按钮消失"⬜（装机无内容源，界面上没有 Download 按钮） |
| 12 | 导出 | 文件只含当前档案 | ✅ 第 5 轮：导出对话框顶部原文 "This backup contains the current profile only."；落盘 `owntv-backup.own` 715 B，是 ZIP 含 `backup.json`，`version: 26`、`profiles` 长度 **1**（`{"id":1,"name":"Profile",…,"pinHash":null}`）、`activeProfileId: 1`、`sections` 7 项、`sports {}` 空壳（D8 core 残留）。缺陷一枚见 §10.4：成功提示报的是建议文件名，不是用户实际保存的文件名 |
| 13 | 恢复单档案同名 | 直接恢复 | ✅ 第 5 轮：档案名已等于备份档案名时再导入同一文件 → 无重命名句，直接 WHAT TO RESTORE（只列 Sources / App settings）→ "8 items restored."，`profiles` 仍是 1 行 |
| 14 | 恢复单档案不同名 + SOURCES | 提示重命名，恢复后名字改变 | ✅ 第 5 轮：`fixture-renamed.own`（单档案 "Renamed Profile"）→ 原文 "This backup’s profile is “Renamed Profile”. This device’s profile will be renamed to match before restoring." → Restore 后直接读 Room `profiles` 得到 `(1,'Renamed Profile')`；反向再导入原备份同样提示，恢复后回到 `(1,'Profile')` |
| 15 | 恢复失败/取消 | 名字回滚 | ◐ 取消半 ✅：在重命名提示上按 Cancel，DB 仍是 `(1,'Profile')`；密封包错密码也停在原对话框、不写入。"恢复中途失败"这一路到第 8 轮仍没造出来：`row15-rollback.own` 把档案改名 "Rollback Probe" + 把 `epgSources` 写成 `{{{"broken`（外层 JSON 合法、内层载荷坏掉），sheet 照给 "This backup’s profile is "Rollback Probe". This device's profile will be renamed to match before restoring."，按 Restore 得 "8 items restored."，DB 读到 `(1,'Rollback Probe', pinHash 有值)`——即**改名与恢复都成功，坏掉的 `epgSources` 被静默吞掉**，app-only 铺不出一次真的中途失败；回滚本身仍只有 `BackupViewModel.kt:320-324`+`:363` 的代码证据与 `BackupProfilesPolicyTest` 的决策表 |
| 16 | 恢复多档案/匿名/休眠同名 | 拒绝，文件不损坏 | ◐ 三分支过了其二：多档案拒绝 ✅（`fixture-multi.own` 被拒后仍是 697 B、mtime 10:31 未变）；第 8 轮补上**匿名档案**的运行期证据 ✅——`row16-unnamed.own`（`profiles[0].name` 改成空串）在挑完文件那一刻就被弹回，原文 "This backup’s profile has no name, so it cannot be matched. Name the profile on the other device and export again."，OK 后停在 Backup & Restore 页、没进 WHAT TO RESTORE（`BackupViewModel.kt:357` 的 earlyBlock 路径），样例文件也仍在 `ls` 里。只剩休眠同名（`HIDDEN`）还只有单测：它要求装机库里存在一条**休眠**档案同名，而 `profiles` 现在只有 id=1 一行，app-only 再铺一次休眠行要动库（第 6 轮那种手法），本轮没做 |
| 17 | 密封备份 | 先密码，再 sections；PIN 提示 | ✅ 第 5 轮：填 `Backup password` 后按钮从 "Export unencrypted" 变 "Encrypt & export"，落盘 `sealed.own` 978 B，头 8 字节 `OWNTVBK1` + 4 字节版本 + JSON 头 `{"scheme":"container-aes-gcm","kdf":"PBKDF2WithHmacSHA256","iterations":210000,"salt":…}`（不再是可读 ZIP）；导入先弹 "Enter backup password"，错密码给 "That password didn’t match. This backup is encrypted — it can’t be opened without the password it was created with." 并留在原框；对密码后才出 WHAT TO RESTORE，并带原文 "A sealed backup can carry a profile PIN. If it does, the PIN you use to open this app will change after restoring."。PIN 真被改写这一路未测（本次密封包 `pinHash: null`） |
| 18 | 同步托管/发送/回传 | 只含当前档案 | ✅ 第 9 轮开头、第 11 轮补齐（用一台 PC 上的假伴侣服务器当"另一台设备"，两个方向都试过：PC 经 `adb forward tcp:18089 tcp:8089` 直连本机的 8089，本机经 `10.0.2.2:8123` 连 PC）：**托管侧**拨起 Sync mode 后页面给出 "QR code with this device's address"、机名 `sdk_gphone64_x86_64`、"PIN: 932782" 与 `http://10.0.2.15:8089/`，说明句 "Lets your other devices find this one. Stays on while this page is open."；从 PC 打过去的无凭证 `GET /sync/hello` 得到 **401 "Unauthorized. Check the PIN and try again."**。**客户端侧** Connect → "Find a device"（"SEARCHING THE NETWORK…" 就是 NSD 广播）→ "OR TYPE THE ADDRESS" 手填 `10.0.2.2:8123` → Continue → "Enter the PIN"（"Type the six digits shown on your other device. You only need to do this once."）→ Pair，PC 侧日志依次是 `POST /sync/pair`（头 `X-Companion-Pin: 123456`，体 64 B）与 `GET /sync/hello`（头 `X-Companion-Pin: 44444444444444444444444444444444`，即配对返回的 secret 复用同一个头），界面随即出现 "FakeHost" 与三向 "Send to FakeHost / Receive from FakeHost / Merge with FakeHost" + "Forget this device"。第 9 轮当时**没拿到**的两处，第 11 轮全部补上（原因见 §10.5 第 13 条：屏上 PIN 被上一轮残留的 "What to merge" 弹窗挡住，前两轮根本没读到当前值）。取消弹窗后托管页给出活 "PIN: 761769"，PC 侧 `POST /sync/pair?pin=761769`（体 `name=FakePeer&id=pc-peer-1`）得 **200 + `{"secret":"08bbaf…"}`（64 hex）**，随后 `GET /backup.own?pin=761769` 得 **200、1348 B**、头 `Content-Disposition: attachment; filename="owntv-backup.own"`，字节以 `OWNTVBK1` 开头并带 `{"scheme":"container-aes-gcm","kdf":"PBKDF2WithHmacSHA256","iterations":210000,…}` ——托管侧确实把本机自己那一份单档案容器发了出去（第 17 行设过备份密码，所以是密封的，未解密内层）；上行方向见第 20 行 |
| 19 | 同步入站多档案 | 拒绝 | ✅ 第 9 轮：配对后走 **Merge with FakeHost** → "What to merge"（"The same things a backup holds. Untick anything you would rather leave out."）→ Continue → 客户端 `GET /backup.own` 取到本机造的 3 档案样例 `fixture-multi.own`（697 B）→ 界面给 **"Sync failed / The other device sent more than one profile. This app keeps a single profile, so it cannot be merged."** + OK/Cancel；随后 `run-as … cat databases/owntv.db` 回读 `profiles` 仍是 `(1,'Rollback Probe', pinHash 有值)`，**一行没动**。Receive 方向是同一套策略（`LocalSyncViewModel.kt:288` + `BackupProfiles.decide`），本轮把它推到了取包那步。D2 的同步侧由此从"只有单测"变成运行期证据 |
| 20 | 伴侣服务器上传多档案 | 拒绝 | ✅ **第 11 轮真跑通**（第 9/10 轮停在鉴权门外的原因是屏上 PIN 被残留弹窗挡住、没读到当前值；PIN 是 `CompanionController.kt:142` + `:290` 里每次 `start()` 现取的 6 位随机数）。已补上的两部分证据：**代码级**——`CompanionHttpServer.kt:274/284/308/318` 一律先取 `?pin=` 查询串、空则退回 `X-Companion-Pin` 头；`POST /sync/pair` 只认 PIN（注释原文 "The PIN and only the PIN: a secret cannot mint another secret"），`/sync/hello`、`/backup.own`、`POST /backup` 认 PIN 或已签发的 secret（`requireSyncAuth`），而 `POST /backup` 属于 BACKUP_RESTORE 模式、把 body 交给 `onBackup(body)` 走同一条导入路径；**运行期**——设备侧 8089 服务真在应答（`adb forward` 打过去 401 而不是拒连），第 9/10 轮那三次都是**拿着过期屏值**打过去，一律 401、响应体 42 B、不含数据。第 11 轮读到活 PIN 761769 后：`GET /sync/hello?pin=761769` → 200（`{"name":"sdk_gphone64_x86_64","app":"99.99.99","payload":21,"device":"8433e7ef-…","session":"ba56f0a4…"}`）→ `POST /sync/pair?pin=761769` → 200 + secret → `POST /backup`（`X-Companion-Pin: <secret>`，体 `data:application/octet-stream;base64,…` 即 `fixture-multi.own` 的 697 B）→ 200，logcat `CompanionController: Received remote backup (697 bytes) → owntv-remote-restore3352318200608448180.own`；**设备侧界面随即弹出 "Sync failed / The other device sent more than one profile. This app keeps a single profile, so it cannot be merged." + OK/Cancel**，`run-as … cat databases/owntv.db` 回读 `profiles` 仍是单行 `(1,'Rollback Probe')`、`sources` 0 行；临时文件被 `incomingDecision` 的 `file.delete()`（`LocalSyncViewModel.kt:356`）清掉，`cache` 里已无 `remote-restore`。**负向复核**：伪造 secret 的 `POST /backup`、伪造 secret 的 `GET /sync/hello`、错 PIN 的 `?pin=000000` 三种打法一律 **401 "Unauthorized. Check the PIN and try again."** ——门确实关着，而过了门之后多档案样活被拒 |
| 21 | 旧语言 en-GB → 升级 | 变 en-US | ✅ 第 5 轮：装机数据里把 `shared_prefs/owntv_locale.xml` 的 `ui_language` 写成 `en-GB`，force-stop 冷启动后该文件**就地被改写为 `en-US`**（不是只读时兼容），界面英文 |
| 22 | 旧语言 de → 升级 | 变"跟随系统"，UI 英文 | ✅ 第 5 轮：同法写 `de` → 冷启动后该键变成空串（即"跟随系统"），设备 `ro.product.locale` 为 `en-US`，界面 Home/Live TV/Library 全英文 |
| 23 | 旧体育提醒 → 升级 | 无体育通知，旧闹钟被取消 | ◐ 第 7 轮：手工铺一份旧版留下的 `files/alerts.json`（750 B，1 条 watched + 1 条 reminder，开球时间 +3h，JSON 键按 core 的 `SportsCacheJson.match`：`id/sp/f/ls/ln/t0/st/tx/ti/c/b/e`），force-stop 冷启动走启动期清理（`OwnTVMobileApp.kt:184-189`）→ 前后 `dumpsys alarm` 里本包 **0 条闹钟**、`MatchAlert` 引用 **0** 条、`Notification Record` **0** 条、logcat crash buffer **0** 行；`am start-foreground-service …/core.sports.LiveMatchCardService` → `Error: Not found; no service started.`，`am broadcast -n …/core.sports.MatchAlertReceiver` 无人接（result=0），本包 BOOT_COMPLETED 接收器只有 Recording/Reminder/WorkManager/ProfileInstaller/Diagnostics。`alerts.json` 本身字节与 mtime 未变（D1"不删数据"）。仍没亲眼看到的是"取消一条真存在的闹钟"：那需要一台装过带体育版本、真排上过闹钟的设备，app-only 铺不出这条闹钟 |
| 24 | 旧 Home 配置含 LIVE_SPORTS | 不显示空行 | ✅ 第 7 轮：恢复一条旧版形状的 `homeConfigs`（样例 `row24-home.own`，`{"1":{"order":[…,"LIVE_SPORTS",…]}}`）→ `files/datastore/owntv_settings.preferences_pb` 里 `home_config_1` 的明文 JSON 确实带着 `"LIVE_SPORTS"`；Settings → Layout → Home screen 的 SECTIONS 列表只有 Keep Watching / Recent channels / Favorite channels / Favorite movies /（下滚）Favorite series / Continue movies / Continue series，整屏 `sport` 出现 **0** 次；Home 页只有 continue-watching 占位，没有空行。唯一没跟上的地方：Layout 页 Home screen 行的徽标写 "8 rows"，因为 `shownRows = home.order.count { it !in home.hidden }`（`SettingsGroupRows.kt:323`）没做同样的过滤——纯计数残留，见 §10.5 第 10 条 |
| 25 | 深链/通知/PiP/播放服务 | 不能绕过 App 锁 | ◐ 显式 intent ✅、HOME 后热恢复仍要 PIN ✅；深链这一路是"攻击面不存在"：`unable to resolve Intent` 且 Resolver 表只有 MAIN/LAUNCHER、无 VIEW 无 Scheme。通知点击 / PiP / 播放服务 ⬜ |
| 26 | RTL 系统 + App 选英文/中文 | 布局按 App 语言 LTR，不崩 | ◐ 第 7 轮：`settings put system system_locales ar-SA,en-US`（shell 可写 secure setting；`adb root` 在这台镜像上不可用——`id` 仍是 uid=2000，`setprop persist.sys.locale` 写不进去，`cmd locale set-locale` 在 API 35 模拟器镜像里也没有，所以没法用重启 framework 的方式确证生效）后把 App 两种状态各冷启动一次：`ui_language=en-US`（指定英文）与 `ui_language=""`（跟随系统）。两次界面都是英文，a11y 树里 `ar` 字母 **0** 个、crash buffer **0** 行；解锁后底栏仍是 Home(173) → Live TV(418) → TV Guide(663) → Library(907) 的 LTR 顺序。**没确证到的是系统真的按 ar 生效**：Settings 自己仍是英文，也没拿到 #1 是 العربية 的界面证据。要做实这一行得换一台 rootable 镜像或手动物色拉序 |
| 27 | 平板/横屏 | 侧栏无 Sports，底部栏 4 项 | ✅ 第 1 轮（手机镜像 + 横屏；平板实体未测） |
| 28 | 设置 PIN 门 + 分类锁 | 仍工作；App 锁移除 PIN 后提示失效 | ◐ PIN 门 ✅（冷启动 + 受保护动作 + Change/Remove 都要旧 PIN）；旧 PIN 失效 ✅（改号后 1234 被拒、5678 放行；移除锁后任何开关都不再弹 PIN）；分类锁 ⬜（装机无分类可锁） |

✅ = 运行期实测通过，◐ = 部分通过（列出哪一半），⬜ = 未跑。⬜ 里的"仅单测覆盖"指 JVM 侧 `BackupProfilesPolicyTest` 断言了策略，不等于设备上的恢复路径跑过。

### 7.3 构建检查

```bash
./gradlew :app:testStandardDebugUnitTest
./gradlew :app:lintStandardDebug
./gradlew :app:assembleStandardDebug
./gradlew :app:assembleX86_64Debug
./gradlew :app:processStandardDebugMainManifest
```

另外：

- 用 `apkanalyzer manifest print` 确认三个体育组件不在合并 Manifest。
- 用 `apkanalyzer resources configs --type string`（或 `aapt2 dump resources`）确认 locale config 只有 `en`、`zh-rCN`、`zh-rTW`（debug 另含伪语言）。
- 手工 grep 确认 `SportsModule`、`ui/screens/sports`、`SportsPlayback`、`MobileDestination.SPORTS` 无引用。

### 7.4 验收清单

- [x] 3 种语言、3 行选择器、APK 白名单（`AppLocalesTest` 7 例 + release 转储只有 `()`/`zh-rCN`/`zh-rTW`；真机向导首屏显示 “All 3 languages” 组 + “System default”）
- [x] 旧标签归一化（en-GB → en-US；其他 → 系统默认）—— `AppLocales.normalize` 单测口径，且第 5 轮已在真机侧证实：把 `shared_prefs/owntv_locale.xml` 的 `ui_language` 分别写成 `en-GB` 与 `de`，冷启动后值就地变成 `en-US` 与空串，界面均英文（§10.4 的 21/22 行）
- [x] 三份 App 本地字符串键一致（`MobileStringsParityTest` 5 例：25 字符串 + 1 复数 ×3）
- [x] 无体育 UI/后台/通知/深链（源码 grep 0 引用、合并清单 0 个体育命名组件、编译绿；真机底栏与设置首页 dump 里 `sport` 出现 0 次）
- [x] 旧体育闹钟清理（代码在；第 7 轮补上运行期旁证：铺一份旧版 `alerts.json` 后冷启动，本包 0 条闹钟、0 条体育通知、core 的 `LiveMatchCardService` 运行期 `Not found`、`MatchAlertReceiver` 广播无人接，且 `alerts.json` 字节未变。"取消一条真闹钟"这一步只有代码路径与升级后 0 闹钟的旁证，要一台装过旧版的设备才能亲眼看到）
- [x] 无”谁在看”/档案管理/切换（`ProfileStep`/`ProfileEditorSheet`/切换 Dialog 已删，编译与 lint 绿；真机向导五步无建档，跳过即静默建默认档案）
- [x] 旧档案休眠且数据不丢（决策函数有单测；第 6 轮补上 DB 行为：库里放 3 行档案，App 直接进 active、其余两行不显示也不删除，一次冷启动后 `profiles` 仍是 3 行）
- [ ] App 锁设置/更改/移除/冷启动/旋转/进程死亡（实测：设置 + "Confirm PIN" 两段式 + 冷启动锁屏 + 正确 PIN 进入 + 第 5 次错误后 "Too many attempts. Try again in 25 seconds." + 旋转不重复问 + force-stop 重开重新问 + **Change PIN 需旧 PIN、改号后旧 PIN 被"Wrong PIN — try again"拒掉而新 PIN 放行 + Remove app lock 需 PIN（错 PIN 不移除），移除后冷启动无门禁、开关类受保护动作不再弹 PIN**；未测：低内存杀进程）
- [x] 儿童模式开关 + 关闭验证（第 3 轮实测：开→关必须过 "Profile — enter PIN"，Cancel 与错 PIN 都不放行、值仍 On，正确 PIN 后值变 Off；见 §10.4）
- [x] 下载开关移到 App → Downloads（行存在且可搜索，`SettingsSearchCoverageTest`；真机实测：搜 "download" 得 3 命中，全在 App 页的 Downloads 小节，`Allow downloads` 值 On。开关 On↔Off 往返已在 App 页实测可拨动；仍缺的是"关掉后按钮消失"——装机没有任何播放列表，Library 显示 "· 0 titles"，界面上没有 Download 按钮可隐藏，机制只能在 `HomeScreen.kt:389`/`MoviePage.kt:221`/`SeriesPage.kt:280` 代码里核对）
- [x] 备份导出/恢复单档案；多档案拒绝；重命名提示/回滚（`BackupProfilesPolicyTest` 10 例绿 = 决策表绿；第 5 轮真机跑通文件与 UI 流程：导出 sheet 顶部 "This backup contains the current profile only."、落盘 `backup.json` 的 `profiles` 长度 1；3 档案样例被拒且文件字节未变；单档案改名样例给重命名提示、恢复后 Room `profiles` 真变成 `(1,'Renamed Profile')`，同名时不提示、Cancel 时不改名；密封包是 `OWNTVBK1` + AES-GCM/PBKDF2 头、先要密码且错密码不写入。仍缺：休眠同名一例只有单测（第 8 轮已把匿名一例补成运行期拒绝，原文见 §7.2 第 16 行）、"恢复中途失败"到第 8 轮仍造不出来（坏内层载荷被静默吞掉，见 §7.2 第 15 行与 §10.5 第 12 条）、密封包真改写 PIN 未测（第 5 轮密封包 `pinHash: null`；第 7 轮已用**非密封**包证过改写：恢复 `row2-pin.own` 后当场就要 PIN，2468 放行、9999 被拒。密封侧只剩"密封容器里的那条 PIN 也被写入"，本轮在导出 sheet 的 ENCRYPT THIS BACKUP? 处没续上，见 §10.5 第 11 条））
- [ ] 同步托管/发送/回传单档案；入站多档案拒绝（同上：决策绿，传输未跑）
- [x] 设置无 Profile 组、无体育页；新行可搜索（`SettingsSearchCoverageTest` 3 例绿；真机设置首页 11 组无 Profile 组，搜 “lock” 命中 “App / App lock / No lock”）
- [x] 单测/lint/assemble/instrumented 全绿（JVM 28 例 0 失败；lint 含 fatal `PluralsCandidate` 绿；两个 debug assemble + release assemble 绿；`Pixel5_API35` 上 `:app:connectedStandardDebugAndroidTest` 6 例 0 失败——前提是修了 `StageFieldTest` 的选择器，该缺陷在 `main` 上同样存在，见 §10.4）
- [x] 6 份文档 + 本方案更新

---

## 8. 风险与对策

| # | 风险 | 概率/影响 | 对策 |
|---|---|---|---|
| 1 | 忘记 App 锁 PIN 后无法进入 | 中/高 | 本期文档写明只能清除应用数据；App 锁设置页明确提示；P2 可加“设备凭据重置”（`KeyguardManager`，不新增权限，约 0.5 人日） |
| 2 | 移除 App 锁 PIN 同时让分类锁/设置 PIN 门失效 | 中/中 | 设置/移除页明确提示“同一 PIN 也保护分类锁和设置 PIN 门”；移除前二次确认 |
| 3 | 旧多档案数据休眠，用户以为丢失 | 中/中 | 文档/CHANGELOG 说明“保留但不再显示”；不删行；降级可恢复 |
| 4 | 多档案备份被拒绝，用户不理解 | 中/中 | 明确文案 + 文档；提示在旧版恢复或导出单档案 |
| 5 | 重命名恢复在进程被杀时只改了名、没恢复数据 | 低/中 | 本期不加 journal；验收覆盖失败/取消回滚；文档标注风险；如需强一致再加一次性 rename journal |
| 6 | 密封备份带 PIN，恢复后覆盖本机 PIN | 中/中 | `mobile_restore_pin_notice`/`mobile_sync_pin_notice`；core 行为不改（D1）；文档说明 |
| 7 | 休眠档案与入站档案同名，core 可能合并到错误行 | 中/高 | `HIDDEN` 拒绝；错误文案引导改名/全新安装 |
| 8 | 旧体育闹钟/通知在升级后仍出现 | 中/中 | `rearmAll()`（`backgroundAllowed=false` → `cancelAll`）+ `stopService`；升级测试 |
| 9 | core 体育残留让用户仍能看到体育相关权限/类 | 低/低 | D8 接受；App 无入口/后台；后续 core 清理另立 |
| 10 | i18n 旧标签在运行期被写回 | 低/低 | 启动归一化 + 全局守卫 + 组合/播放侧防御 |
| 11 | 旧语言用户升级后看到英文 | 高/低 | 预期行为；CHANGELOG/用户指南说明；选择器只保留 3 种 |
| 12 | `SettingsSearchCoverageTest` 因删除页面/新增字符串失败 | 中/低 | 单独更新豁免列表；新增行都在 `SettingsGroupRows.kt` 自动索引 |
| 13 | App 锁和设置 PIN 门重复询问 | 中/低 | 现有行为；文档说明两个开关独立，可只开一个 |
| 14 | 单档案名称/头像不可编辑 | 中/低 | 本期取舍；文档写明；后续可加一行 Profile（约 +0.5 人日） |
| 15 | Home 旧配置含 `LIVE_SPORTS` 造成空行 | 低/低 | 读取时过滤；不删旧配置 |
| 16 | 本地化脚本/CI 仍假设 27 种语言 | 中/低 | 检查 CI；`tools/i18n/README` 说明 App 白名单在 `AppLocales`；不运行 core 生成器 |

---

## 9. 文档更新（D10）

### 9.1 六份文档

| 文档 | 必改内容 |
|---|---|
| `README.md` | 功能列表去掉 Sports/多档案；语言只写 3 种；单档案 + App 锁；备份/同步单档案说明；构建/测试命令 |
| `CHANGELOG.md` | 新增“Mobile 精简”条目：i18n、体育移除、单档案；迁移/兼容提示 |
| `extras/FEATURE_MAP.md` | 删除 Sports 章节和多档案管理章节；更新 i18n、Settings、Backup、Local sync、Home 行 |
| `extras/OwnTV_Master_Product_Brief.md` | 产品范围/角色/语言/单档案；明确电视端仍有多档案/体育 |
| `extras/PARITY_CHECKLIST.md` | 删除手机端 Sports/多档案项；标注“TV 有、Mobile 无”的有意差异 |
| `extras/USER_GUIDE.md` | 普通用户语言；单档案、App 锁、儿童模式、下载、备份/恢复、本地同步、语言；多档案备份不能导入的说明 |

### 9.2 本方案

`extras/SLIMMING_PLAN.md`（本文）随实施更新：把“计划”改成“已完成/偏差”，记录实际人日、测试结果、遗留项。

### 9.3 决策记录

| 编号 | 决策 | 状态 |
|---|---|---|
| D1 | App-only，不升 core pin | 已确认（范围前提） |
| D2 | 旧多档案备份提示不能导入；旧档案休眠 | 已确认 |
| D3 | 变体 B：保留简化单档案 App 锁 | 已确认 |
| D4 | 保留全局儿童模式开关 | 已确认 |
| D5 | 允许下载移到 Settings → App → Downloads | 已确认 |
| D6 | 不保留 en-GB；en-* → en-US | 已确认 |
| D7 | 不支持的持久化语言标签静默重置 | 方案默认 |
| D8 | 体育 core 残留接受；app-only 验收 | 已确认 |
| D9 | 不支持备份/同步多档案 | 已确认 |
| D10 | 所有文档一起更新 | 已确认 |

### 9.4 发布说明要点

- 手机端现在只保留一个档案；旧档案保留但不再显示。
- 多档案备份/同步文件会被拒绝；请导出当前档案的单档案备份。
- 体育模块移除；旧提醒会被清理。
- 语言只保留 English、简体中文、繁体中文；其他语言回落到英文/系统默认。
- App 锁与分类锁/设置 PIN 共用同一个 PIN；忘记 PIN 需清除应用数据。

## 10. 实施结果与偏差（2026-11-06）

本文的 §3–§5 是方案，本节是实际落地的结果。约定：与方案不一致的地方一律写"偏差"并给理由，不回填方案正文，以免看不出改过什么。

### 10.1 阶段完成情况

| 阶段 | 方案 | 结果 | 验收口径 |
|---|---|---|---|
| 0 | 基线 | 完成（分支 `mobile-mini`，基线报告即 `extras/FEATURE_MAP.md`） | — |
| 1 | i18n 三种语言 | 已完成 | `AppLocalesTest` 7 例 + `MobileStringsParityTest` 5 例绿；APK 语言转储见 §10.4 |
| 2 | 体育移除 | 已完成 | 合并 Manifest 35 个组件中 0 个体育命名；源码 grep 无引用；assemble 绿 |
| 3a | 单档案 Shell/门禁 | 已完成 | `compileStandardDebugKotlin` + lint 绿；运行期行为只在 §7.2，未跑 |
| 3b | 设置改造 | 已完成 | `SettingsSearchCoverageTest` 3 例绿；lint（含 fatal `PluralsCandidate`）绿 |
| 3c | Setup 简化 | 已完成 | 编译绿；向导五步的实测在 §7.2，未跑 |
| 3d | 备份/同步策略 | 已完成 | `BackupProfilesPolicyTest` 10 例逐行覆盖决策表 |
| 4 | QA + 文档 | §7.1 与 §7.3 全跑通；文档 6 份更新；§7.2 五轮走查后整行过 10 行、6 行各过半，余 12 行未跑 | 见 §10.4 / §10.5 |

阶段依赖按 §6 执行，未出现返工。实际人日不再单独估算：本轮在同一工作树连续完成，改动量与验证状态比人日更可核对，见 §10.2。

**关于此前"core API 缺失阻塞编译"的记录已作废**：`:app` 从未真正编译过 core 的缺失 API；阻塞感来自构建没有带 `-Powntv.corePath`，因此解析到已发布的 `1.0.69`。带上该属性做 includeBuild（`settings.gradle.kts:49`，只读 core 源码、不改 core 一行）后，`liveRestoreKey`、`guideFollowsLiveCategory` 都在本机 core 检出里，编译全绿。§10.4 的数字全部来自这条路径。

### 10.2 改动量

- 相对 HEAD 的已跟踪改动（2026-11-06 第五轮走查后的 `git diff --shortstat`）：**73 个文件，+1413 / −6731**（含 15 个删除；其中 1 个改动是 §10.4 所说的 instrumented 用例选择器修复 `ui/stage/StageFieldTest.kt`，只动测试）。第五轮只动文档，代码文件数与行数与第四轮末尾一致。另有 **10 个新文件**未跟踪，合计 **893 行**：`i18n/AppLocales.kt`(56)、`backup/BackupProfiles.kt`(138)、`playback/SlimPlayerDefaults.kt`(114)、`ui/screens/settings/SettingsAppLock.kt`(179)、`res/values{,-zh-rCN,-zh-rTW}/strings_mobile.xml`(41/32/32，各 25 个 `mobile_*` 字符串 + 1 个 `mobile_*` 复数)、`app/src/test/.../backup/BackupProfilesPolicyTest.kt`(137)、`.../i18n/AppLocalesTest.kt`(76)、`.../i18n/MobileStringsParityTest.kt`(88)；`git status --short` 列 **12 项**未跟踪，因为上面这些文件分处 6 个新目录（`backup/`、`i18n/` 的主侧与测试侧、两个 `values-zh-rXX/`），再加上本方案自己（`extras/SLIMMING_PLAN.md`）、`extras/FEATURE_MAP.md` 与 `docs/设置页改造方案.md`。前两轮误记为"9 个新文件 / 779 行""14 项未跟踪"，此处按 `git status` 与 `wc -l` 实测校正；走查用的临时脚本与样例文件都放在 gitignore 的 `app/build/reports/instrumented/` 下，不进仓库。**全部改动仍未提交，也没有得到提交授权。**
- 口径提醒：这个工作树同时带着另一份未提交方案（`docs/设置页改造方案.md`）的改动，例如 `playback/SlimPlayerDefaults.kt`。上面的数字是"工作树 vs HEAD"，不是"仅本方案"的净改动；本方案的验收不依赖该统计，只依赖 §10.4 的构建结果。
- 删除 **15 个文件**：`ui/screens/sports/` 下 13 个页面/ViewModel + `di/SportsModule.kt` + `ui/screens/settings/SettingsProfilePage.kt`。
- 硬约束保持：core 仓零改动（只用 `includeBuild` 读源码）、无 DB schema 变更、备份格式与同步协议未变、合并清单 `uses-permission` 仍为 **14** 条（增删为 0）、不删用户数据。

### 10.3 与方案的偏差
1. **§5.8.1 要求删除 `PIN_MIN`/`PIN_MAX`**：常量保留在 `ui/profiles/ProfileSheets.kt:25-26`。`ProfileStep` 与 `AvatarStrip` 已删除，Setup 不再建档；但 D3 变体 B 的 App 锁仍要知道 PIN 是 4–6 位，`ProfileGate` 与 `SettingsAppLock` 共用这两个常量，删掉等于把位数策略写死两遍。
2. **§3 的 `tools/i18n/locales.json`**：仍留着 45 条目录，`check_pseudo_locales.py` 与 `.github/workflows/android.yml` 的 diff 照旧按 45 条跑。App 构建已不读它，打包内容由 `AppLocales.kt` + `localeFilters` 决定。这是已知偏差，记录在 `extras/FEATURE_MAP.md` §7 第 26 条，本期不收敛（改 CI 目录超出 app-only 范围）。同一原因留下两处**过期注释**，本期按范围不动，改 CI 时一起清：`.github/workflows/android.yml:75` 与 `.github/workflows/automerge-core-bump.yml:8` 都还写着"Gradle 在 configure 期读 locales.json 生成 localeFilters"。
3. **§4.6 体育 core 残留**：按 D8 原样接受——core 里的体育类与文案仍在依赖里，手机端保证"无入口、无后台、无组件"，不保证 APK 里没有体育字节码。清单用三条 `tools:node="remove"` 摘掉 core AAR 的三个组件声明。
4. **硬编码字符串棘轮**：`tools/i18n/hardcoded_baseline.txt` 收敛到 **0 条**（绝对棘轮），`safe_literals.txt` 现 **749 条**（清掉约 200 条体育/多档案遗留行，新增 21 条本轮引入的技术串并按类目 `classify-safe`）。XML 字符串不受该门禁约束。
   **门禁的"绿"要看是不是真的重跑过**：`:app:verifyI18nLiterals` 在 debug 那几次都是 `UP-TO-DATE`，所以 §10.4 记的"门禁绿"当时只等于"上次跑过且没再触发"。第一次以 release 配置真正重跑时立刻暴露 **12 条失效记录 + 1 条未分类日志串**——全部属于本工作树里另一份方案（`docs/设置页改造方案.md`，`SettingsLeaf.kt`、`SettingsPlayerRows.kt`、`SlimPlayerDefaults.kt`）改动的文件，不是本期精简写的串。修法只动记录、不动代码：`prune-safe` 清失效行、`classify-safe --category log` 登记 `applySlimLiveDefaults failed`，随后 `verify --bootstrap` 报 **0 baseline + 749 safe**。**遗留结论**：以后凡是"门禁绿"，必须写明是重跑结果还是缓存命中。
5. **`SetupEntry`/`Step` 命名**：与 §5.8.1 一致，无偏差；列在此处只为对照——`enum class SetupEntry { ADD_CONTENT, FORM, IMPORT }`（`SetupFlow.kt:72`），`Step` 已无 `CREATE_PROFILE`、`EXISTING`（`SetupFlow.kt:85-87`）。
6. **附录 A / 附录 B 对账**：附录 B 的 58 个去重路径按 HEAD 与工作树双重解析后**全部命中**，含简写形式（`ui/shell/MobileShell.kt` 这类相对包根的路径）。本节先前写"`di/SportsModule.kt`、`ui/screens/settings/SettingsProfilePage.kt` 两个真不存在"是**测量口径错误**：它们存在于 HEAD、已被本轮删除，只对 `git ls-tree HEAD` 才看得见。附录 A 的键全部落地，实施时多出 2 个（见上表），已回填补正。
7. **debug APK 里仍有 core 的真阿拉伯语**（实测，不是推断）：`aapt2 dump resources app-standard-debug.apk` 的 locale config 为 `()`（英文默认）3406 条、`(zh-rCN)` 3406、`(zh-rTW)` 3406、`(en-rXA)` 3404、`(ar-rXB)` 3404、`(ar)` **3380**，其余 26 个语言为 0。`(ar)` 是 §3 第 140 行"保留现有 per-variant 写法"的直接后果：把 `ar-rXB` 加进 debug 的 `localeFilters` 等于承认 `ar` 这个语言限定符，core AAR 里的真阿拉伯语翻译就跟着进包。这是 **debug-only**：release 侧 `processStandardReleaseResources` 的转储只有 `()`、`(zh-rCN)`、`(zh-rTW)` 三种 locale config，`(ar)`/`(ja)`/`(es)` 全部为 0，即 §7.3 的口径在发布产物上严格成立。本期按 D 项不改（保留伪语言是方案明确要求的），若要连 debug 一起收敛，只需删 `app/build.gradle.kts:185-189` 里的 `"ar-rXB"` 一项，代价是失去 RTL 伪语言的布局压测——而手机端三种语言全是 LTR，该压测已无实际受众。留给用户定夺。
8. **D5 的落点是"App 页里的 Downloads 小节"，不是一个叫 Downloads 的独立 leaf**：`SettingsGroupRows.kt:541-561` 把 `downloads_allowed` / `download_folder` / `downloads_wifi_only` 三行挂在 `stringResource(R.string.common_nav_downloads)` 这个 `SettingSection` 下，紧跟 NETWORK 组；设置搜索给这三行标的组名因此是 **App**（实测"3 matches"都在 App 页）。方案 §3 与 D5 写的 "Settings → App → Downloads" 读起来像三级页面，实际是两级页面 + 一个节标题。语义等价，故不改代码，只把措辞钉在这里；若一定要独立 leaf，需要在 `SettingsDestination`/`SettingsLeaf` 增一个路由并拆分这 3 行，属于新的设置页改造，超出本期。

### 10.4 测试结果（实际做到 / 未做到）

构建口径：全部命令都带 `-Powntv.corePath=E:/develop/ideaPoject/git/Own/OwnTV_Core`（includeBuild 读 core 源码，core 仓零改动）。不带该属性时解析到已发布的 `1.0.69`，才会出现 §10.1 已作废的"缺 API"假象。

已跑通（绿色，逐条有产物可查）：

| 检查 | 命令 | 结果 |
|---|---|---|
| 单测 | `:app:testStandardDebugUnitTest` | **BUILD SUCCESSFUL**，5 个 JVM 测试类 **28** 例、0 失败 0 跳过：`BackupProfilesPolicyTest` 10、`AppLocalesTest` 7、`MobileStringsParityTest` 5、`SettingsSearchCoverageTest` 3、`StartupLiveSelectionTest` 3 |
| lint | `:app:lintStandardDebug` | **BUILD SUCCESSFUL**（`abortOnError = true`、fatal 含 `PluralsCandidate`）；首轮曾报 1 个错，见下。第 10 轮重跑同样绿，报告 `app/build/reports/lint-results-standardDebug.txt` 末尾 **0 errors, 64 warnings, 5 hints** |
| 主源编译 | `:app:compileStandardDebugKotlin` | 绿，含直播/设置对 core `liveRestoreKey`、`guideFollowsLiveCategory` 的调用 |
| 打包 | `:app:assembleStandardDebug`、`:app:assembleX86_64Debug` | 两个 APK 均绿（standard debug 79,997,243 字节） |
| release 打包 | `:app:assembleStandardRelease`（R8 + 资源压缩，未签名） | **BUILD SUCCESSFUL**，`app-standard-release-unsigned.apk` 54,651,531 字节；首轮在 `:app:verifyI18nLiterals` 因缓存命中被跳过，重跑后暴露 §10.3 第 4 条的记录漂移，只动记录修复后转绿。第 10 轮重跑再次 **BUILD SUCCESSFUL**，产物 20:18、54 650 611 字节 |
| instrumented | `:app:connectedStandardDebugAndroidTest`（`Pixel5_API35`） | **BUILD SUCCESSFUL in 1m 31s**，`TEST-Pixel5_API35(AVD) - 15.xml` 报 `tests="6" failures="0" errors="0" skipped="0"`。前提是 §10.4 末尾所述的选择器修复；修前同一命令在真机上是 6 例全红 |
| 清单 | `:app:processStandardDebugMainManifest` | 合并清单 35 个组件、14 个 `uses-permission`、**0** 个体育命名组件（core AAR 的三个声明被 `tools:node="remove"` 摘掉） |
| 语言转储 | `aapt2 dump resources`（build-tools 37.0.0，两个 APK 同一命令同一算法） | release 只有 `()` 3617、`(zh-rCN)` 2066、`(zh-rTW)` 2066，`(ar)`/`(ja)`/`(en-rXA)`/`(ar-rXB)` 全 **0**；debug 另含 `(zh‑rCN)` 3406、`(en-rXA)` 3404、`(ar-rXB)` 3404、`(ar)` **3380**，见 §10.3 第 7 条。release 表里 `plurals/mobile_unlock_too_many_attempts` 为 `()` size=2（one/other）、`(zh-rCN)`/`(zh-rTW)` 各 size=1（只有 other）|
| 硬编码门禁 | `:app:verifyI18nLiterals`（`--bootstrap`）、对 merge-base 的 `verify-ci` | 绿；`hardcoded_baseline.txt` **0** 条（绝对棘轮），`safe_literals.txt` **749** 条 |
| i18n 脚本 | 数字本地化 / 文本溢出 / 伪语言三条校验 | 绿 |

lint 首轮唯一错误与修复（本轮引入的文案，非既有债务）：

- `strings_mobile.xml:21 PluralsCandidate`——"Too many attempts. Try again in %1$d seconds." 里 `%1$d` 紧跟名词，项目把该检查设为 fatal。
- 修法：三份 `strings_mobile.xml` 把该键从 `<string>` 改为 `<plurals>`（英文 `one`/`other`，中文只 `other`，故每份仍是 25 字符串 + 1 复数、键集一致），`ProfileGate.kt:182` 改用 `pluralStringResource(R.plurals.mobile_unlock_too_many_attempts, cooling, cooling)`。改后 lint 与单测同时转绿，`aapt2` 转储能看到 release 表里 `plurals/mobile_unlock_too_many_attempts size=2 (one/other)`。

真机走查（§7.2 的一个子集，2026-11-06 在本机 `Pixel5_API35` / Android 15 / 1080×2340 headless 镜像上实测，装的是 `app-x86_64-debug.apk`，共九轮：第 1 轮全新装机走向导 + 门禁 + 底栏 + 旋转，第 2 轮 connected 跑完后重装（数据被清空）再走设置与语言，第 3 轮在第 2 轮那份全新数据上新设 App 锁 PIN 后补儿童模式、下载开关与绕过门禁三类，第 4 轮接着测改 PIN / 移除锁 / 移除后受保护动作不再提示，第 5 轮测备份导出与恢复（含 D2 拒绝、重命名提示与密封包）并补旧语言标签回落，第 6 轮用直接改 Room 库的办法测升级多档案库（第 4/5 行与 §7.4 的旧档案休眠项），第 7 轮补带 PIN 备份与旧 Home 配置/旧体育残留，第 8 轮用备份注入测恢复策略的匿名拒绝与"先改名再应用"（第 15/16 行）并复核导出 sheet 的加密小节，第 9 轮用一台 PC 上的假伴侣服务器 + `adb forward` 打通本地同步的托管/鉴权/配对/入站拒绝（第 18/19 行）；第 11 轮先取消残留的模态再读屏上活 PIN，托管侧真下载 `GET /backup.own` 与入站上行 `POST /backup` 都拿到运行期证据，第 18/20 行由此转绿；共十一轮，下表每行都标了轮次；证据都是 `uiautomator dump` 的实际文本，不是推演。设备收尾状态：App 锁已移除（"No lock"，冷启动直接进 Home），Room `profiles` 只有 1 行且名字已恢复成装机默认的 "Profile"（第 5 轮为测重命名临时改成过 "Renamed Profile"），儿童模式最后一次是在无锁状态下拨到 On 后未再回读值，`ui_language` 是空串（跟随系统；设备 locale 为 en-US，所以界面英文——本轮最后一步把测试用的 `de` 归一化掉了），数据仍是没有任何播放列表的装机状态；`/sdcard/Download/` 里留了本轮造的四份文件（`owntv-backup.own`、`sealed.own`、`fixture-multi.own`、`fixture-renamed.own`），都在模拟器公共目录、不在仓库。第 6 轮结束时因回滚手法不可靠（只推主库不回灌 WAL 会得到旧镜像），改用 `pm clear` 把 App 归位成**全新安装**：无档案数据、无播放列表、`ui_language` 为空（跟随系统，设备 en-US 所以界面英文）。第 8 轮收尾：Room `profiles` 仍只有 1 行，但名字是本轮重命名样例留下的 `Rollback Probe`（`pinHash` 有值 = App 锁仍是 PIN 2468）——因为那次恢复**成功**了，没有回滚可观察；两份样例 `row16-unnamed.own`、`row15-rollback.own` 都还在 `/sdcard/Download/`，仓库里不留）：

| §7.2 行 | 实测到的文本 | 判定 |
|---|---|---|
| 1 全新安装 + 跳过添加源 | 向导四步依次出现 Welcome（"All 3 languages" + "System default"）→ "Make it comfortable to read"（Font size + App icon 两行，没有 UI 缩放行）→ "Before you start" → "Start fresh, or bring back your profiles, playlists and favorites…" → 卡片副标题 "Add a playlist now, or skip and add one later." → Skip for now 之后直接进 Home | **通过**：没有建档步骤，默认档案静默落地 |
| 27 底栏与体育 | 底栏只有 `Home / Live TV / TV Guide / Library` + 头像 More；整份 dump 里 `sport` 出现 **0** 次；设置首页 11 个组里无 Profile 组 | **通过** |
| 6 设置 App 锁 → 冷启动 | 管理页 "APP LOCK / Set app lock"，表单两个字段各 "4–6 digits" + "Confirm PIN"，保存后变 "Change PIN / Remove app lock"；force-stop 重启后是锁屏 "Profile — enter PIN" + "Unlock"；正确 PIN 进入 Home | **通过**：变体 B 的两段式确认与门禁都成立 |
| 9 错误 PIN ×5 | 第 1–4 次 "Wrong PIN — try again"，第 5 次 "Too many attempts. Try again in 25 seconds."（30 秒冷却已在计时） | **通过**：`<plurals>` 在运行期渲染正确（复数 `other` 分支） |
| §7.4 新行可搜索 | 设置搜索 "lock" → "1 match"，命中 "App / App lock / No lock" | **通过** |
| 11 下载开关（2026-11-06 第二、三次实测，全新数据状态） | 设置搜 "download" → **"3 matches"**，三条都在 **App 页**的 Downloads 小节里：`Allow downloads`（搜索行右侧值显示 **On**）、`Download folder`（值 `Internal shared storage`，点进去是 Downloads 屏）、`Download over Wi-Fi only`；App 页从上到下的分组是 APP(7) → NETWORK(2) → DOWNLOADS → DIAGNOSTICS。第三次在 App 页按**当次 dump 的 y=2004** 点开关一侧（x=965）：搜索回读值变 **Off**，再点一次变回 **On**——往返两次都成立 | **位置通过**（D5 落点是 App 页里的一节，不是独立 leaf，见 §10.3 第 8 条）；**开关本身已实测可拨动**；**"关掉后下载按钮消失"仍未验证**：装机后没有任何播放列表，Library 显示 "No movies here." / "· 0 titles"，界面上根本没有 Download 按钮可隐藏。机制在代码里可核对（`HomeScreen.kt:389` 把 `onDownload` 置 null，`MoviePage.kt:221`、`SeriesPage.kt:280` 整颗按钮不渲染，都读 `SettingsRepository.activeDownloadsAllowed`），但"看得见按钮消失"要等配好源再跑 |
| 10 儿童模式（2026-11-06 第三次实测，App 锁已设） | 搜索 "kid" → "1 match"，`Kids mode` 值 Off → 进 Content & metadata 页点开关 → 搜索回读 **On**（叶子文案 "Keeps browsing to child-friendly categories. Set an app lock so it cannot be turned off."）；再点开关 → 弹出 "Profile — enter PIN" + PIN 输入框 + OK/Cancel；**Cancel 后回读仍是 On**（拒绝即撤销）；输错 9999 + OK → 回读仍 On；输对 1234 + OK → 回读 **Off** | **通过**：D4 的全局开关可开可关，且"有 App 锁时关闭受 PIN 保护"这条在运行期成立——错 PIN、取消都不会放行 |
| 25 绕过门禁（2026-11-06 第三次实测，App 锁已设，门禁显示中） | ① `am start -a android.intent.action.VIEW -d "owntv://live/ch/1"` → `Error: Activity not started, unable to resolve Intent`；`dumpsys package` 的 Activity Resolver Table **只有 Non-Data 的 MAIN/LAUNCHER**（9 个图标变体），整张表没有 VIEW、没有 `Scheme:` 段——App 根本没有可供深链命中的入口。② `am start -n …/.MainActivity --es route live`（显式组件 + 额外参数）→ intent 投递给已在门禁上的同一实例，dump 仍是 "Profile — enter PIN"，**没有**跳到直播页。③ 门禁显示中按 HOME、再用 LAUNCHER 重新拉起 → 仍是 "Profile — enter PIN"，不会因为进程还活着而放进 Home | **通过（深链 / 显式 intent / 热恢复三点）**；**通知点击、PiP、播放服务三点仍未测**：需要先有录制或播放中的会话 |
| 28 PIN 门 + 分类锁 | 已实测的是"PIN 门"这一半：冷启动门禁、错 PIN 提示与冷却（第 9 行）、受保护动作（儿童模式关闭）需要 PIN、取消/错 PIN 不放行；App 锁设好以后 App 页 `App lock` 行的值文本由 "No lock" 变为 **"PIN lock"**（搜索列表里同样显示 "PIN lock / 1 match"）。**第 4 轮补测**：`Change PIN` 先要旧 PIN（弹 "Profile — enter PIN"），改完 force-stop 重启后**旧 PIN 1234 → "Wrong PIN — try again"，新 PIN 5678 直接进 Home**；`Remove app lock` 同样先要 PIN，错 PIN 报 "Wrong PIN — try again" 且页面回到管理页（锁没被移除），正确 PIN 后管理页只剩 "APP LOCK / Set app lock"，冷启动不再有门禁、直接 Home，**并且此后拨儿童模式开关不再弹 PIN（`enter PIN` 在整份 dump 里出现 0 次）**——"移除 App 锁后旧 PIN 提示失效"这半句成立。**只剩"分类锁"未测**：装机状态没有任何分类可锁（`Customize categories & items` 进去没有条目） | **大半通过**，分类锁部分仍留在未跑清单 |
| 12–17 备份与恢复（2026-11-06 第 5 轮实测，App 锁已移除、无播放列表） | 这一组不依赖内容源，用两条通道做：真机导出/导入 + 手工造的 `.own` 样例（样例与脚本都放在 gitignore 的 `app/build/reports/instrumented/`）。①**导出**：More → Backup & Restore → "Back up now" 下的 `File on this phone` → sheet 顶部原文 "This backup contains the current profile only."（D9 的界面证据）→ `Export unencrypted` → SAF 保存 → 提示 "Saved to owntv-backup.own. Passwords and PINs were not included."。落盘 715 B 是 ZIP，内含 `backup.json`：`version: 26`、`profiles` **长度 1**（`{"id":1,"name":"Profile","avatarColor":0,"avatarId":0,"isKids":false,…,"pinHash":null}`）、`activeProfileId: 1`、`sections` 为 SOURCES/CUSTOMIZE/FAVORITES/HISTORY/RESUME/MANUAL_REORDER/SETTINGS、`sports {}` 空壳仍在（D8 已接受的 core 残留）。②**D2 拒绝**：把 `profiles` 复制成 3 条（追加 `{"id":2,"name":"Kids",…}`、`{"id":3,"name":"",…}`）推为 `fixture-multi.own`，导入 → 弹窗原文 "This backup contains more than one profile. This app keeps a single profile, so it cannot be restored."，OK 后停在 Backup & Restore 页；再次 `ls -l` 该文件仍是 697 B、mtime 未变，即"拒绝但不改坏文件"。③**重命名提示**：单档案改名为 "Renamed Profile" 造 `fixture-renamed.own` → 对话框先给 WHAT TO RESTORE（只列 Sources / App settings，**没有任何档案选择器**），再给原文 "This backup's profile is "Renamed Profile". This device's profile will be renamed to match before restoring." → Restore 报 "8 items restored."，用 `run-as … cat databases/owntv.db` 把 Room 拉回本机读 `profiles` 得 `(1,'Renamed Profile')`；同名再导入时该句消失、直接可恢复；反向导入原备份后恢复回 `(1,'Profile')`，设备状态已复原。④**取消不回滚名字**：在重命名句上按 Cancel，DB 仍是 `(1,'Profile')`。⑤**密封包**：`Backup password` 填 8 位后输入框显示 8 个点、按钮从 `Export unencrypted` 变 `Encrypt & export`，落盘 978 B 不再是 ZIP，而是 `OWNTVBK1` + 4 字节版本 + JSON 头 `{"scheme":"container-aes-gcm","kdf":"PBKDF2WithHmacSHA256","iterations":210000,"salt":"…"}` + 密文；导入先弹 "Enter backup password"（"…to open it and choose what to restore."），错密码给 "That password didn't match. This backup is encrypted — it can't be opened without the password it was created with." 并留在原框不写入，对密码后才出现 WHAT TO RESTORE 与原文 "A sealed backup can carry a profile PIN. If it does, the PIN you use to open this app will change after restoring." | **第 3/12/13/14/17 行通过**；第 15 行只过"取消/错密码不改动"，第 16 行只过"多档案拒绝 + 文件未损坏"，匿名与休眠同名两例当时只有单测（第 8 轮把**匿名**补成运行期拒绝，见下一行；休眠同名仍只有单测）；第 2 行（全新安装导入带 PIN 的备份后显示 App 锁）与"密封包真改写 PIN"未跑，因为本轮的密封包 `pinHash: null` |
| 21/22 旧语言标签（第 5 轮，不依赖内容源） | 装机数据里 `shared_prefs/owntv_locale.xml` 只有一个 `ui_language` 键（App 侧没有写它的代码，由 core 的 locale 存储持有）。① 写 `en-GB` → force-stop 冷启动 → 再读该文件，值**就地变成 `en-US`**，界面英文：D6 的归一化不是"读时兼容"，它真的改写了旧标签。② 写 `de` → 冷启动 → 值变成空串（即"跟随系统"），设备 `ro.product.locale` 是 `en-US`，四格底栏 Home/Live TV/Library 全英文。两次都不重启系统、无崩溃。方法上踩到的坑：`printf` 造这份 XML 会把引号弄坏，SharedPreferences 于是读不出任何值（看起来像"键没了"），改成"本机写文件 + `run-as … sh -c` 用 stdin 覆盖"才是可复现的做法 |
| 4/5 升级多档案库（2026-11-06 第 6 轮） | 手法：force-stop → `exec-out cat` 把 Room 主库与 -wal 各取一份 → 本机 sqlite3 插两行/删一行档案 → `base64 -w 0` 单行编码 → `run-as … sh -c base64 -d > databases/owntv.db` 写回，两端 md5 相同。① 三档案 + active=1 有效：冷启动落 Home，没有向导/门禁/档案选择器，`profiles` 三行事后仍在（旧档案休眠且数据不丢的运行期证据）。② 删掉 id=1 使 active 指向空：仍落 Home，无崩溃。**两条方法教训**：a) `adb shell` 是文本通道，把二进制 DB 直接从 stdin 灌进去会得到 680,984 字节的残件，Room 在 `configureJournalMode` 抛 `database disk image is malformed` 并崩在启动（logcat 里那 7 条 crash 记录是这个原因，不是被测改动）；改成 `base64 -w 0` 才字节精确。b) 回滚时若只推主库、不带当时的 -wal，等于把一个早于 WAL 合并的旧镜像放回去——App 因此显示"Choose a language to begin"，看着像向导没做完；本轮收尾用 `pm clear` 归位成干净装机，所以设备现在的状态是**全新安装**（无档案数据、无播放列表） |
| 2 带 PIN 的备份（2026-11-06 第 7 轮，全新安装态） | 向导 "Start fresh…" 步的 Restore a backup → File on this phone → `row2-pin.own` → WHAT TO RESTORE 无改名提示 → "8 items restored." → 立刻 "Profile — enter PIN"；9999 → "Wrong PIN — try again"；2468 → Home | **通过**：全新安装导入带 PIN 的备份即锁，且改写的就是备份里那条 PIN |
| 24 旧 Home 配置（第 7 轮，恢复一条带 `LIVE_SPORTS` 的 `homeConfigs`） | DataStore 明文 `home_config_1` 含 `"LIVE_SPORTS"`；Settings → Layout → Home screen 七行无体育行，整屏 `sport` 0 次；Home 无空行。Layout 页徽标仍算成 "8 rows" | **通过**（计数徽标另记 §10.5 第 10 条） |
| 23 旧体育残留（第 7 轮，手工 `files/alerts.json` 750 B） | 冷启动前后本包 0 条闹钟、0 Notification Record、crash 0 行；`LiveMatchCardService` → Not found；`MatchAlertReceiver` 广播 result=0；本包 BOOT 接收器无体育；`alerts.json` 未变 | **部分**：升级后确实没有任何体育后台/通知可跑；"真闹钟被 cancel"这一步铺不出来 |
| 26 RTL 系统 + App 英文/跟随系统（第 7 轮） | `settings put system system_locales ar-SA,en-US` 后两种 `ui_language`（en-US 与 ""）各冷启动一次：界面全英文、a11y 树 0 个阿拉伯字母、crash 0 行、底栏 x=173/418/663/907 保持 LTR。但 `adb root` 不可用（uid=2000）、`setprop persist.sys.locale` 写不进、`cmd locale set-locale` 不存在，framework 重启没做成，所以"系统真的切到 ar"这一步只是 setting 层面的写入 | **部分**（App 侧表现通过，系统侧生效未独立确证） |
| 8 进程死亡（第 7 轮） | 锁设好 → 进 Home → HOME 退后台（`ps` 有进程，RSS 312 MB）→ `am kill` → 进程消失 → 重新 `am start` → 仍是 "Profile — enter PIN"；crash 0 行、ANR 0 条 | **部分**：真杀进程已观测，lowmemorykiller 水位压力在这台 headless 镜像上造不出来 |
| 15/16 恢复策略（2026-11-06 第 8 轮，App 锁 2468 已设、无播放列表） | 路径：More → Backup & Restore → 精确点 `Restore`（不是页头那行 `Backup & Restore`）→ "Restore a backup" 下的 `File on this phone` → SAF 里点样例文件名。①**匿名档案** `row16-unnamed.own`：挑完文件当场弹窗原文 "This backup’s profile has no name, so it cannot be matched. Name the profile on the other device and export again." + `OK`，没出现 WHAT TO RESTORE，文件仍在 Download 列表里 → `Block.UNNAMED` 的运行期证据补齐。②**先改名再应用** `row15-rollback.own`（name 改 "Rollback Probe"、`epgSources` 写成 `{{{"broken`）：sheet 预告 "This backup’s profile is "Rollback Probe". This device's profile will be renamed to match before restoring." → Restore → "8 items restored." + "Re-sync your sources to load content."；`run-as … cat databases/owntv.db` 回读 `profiles` = `(1,'Rollback Probe', pinHash 有值)`，即改名生效、坏内层载荷没让恢复失败。③**导出 sheet 的加密小节**：140% 字体下 sheet 只有 "ENCRYPT THIS BACKUP?" + 那句 "With a backup password, the whole file is encrypted — playlists, logins & PINs are protected." + `Export unencrypted` + `Cancel`，点那句说明文字界面无变化（没有密码框出现），而第 5 轮在 100% 字体下同一个小节是有 `Backup password` 输入框且按钮会变成 `Encrypt & export` 的 | **第 16 行**推进到"三分支过其二"（休眠同名仍只有单测）；**第 15 行**保持 ◐：中途失败造不出来，回滚只有代码证据；140% 字体下密码框缺失单列 §10.5 第 11 条，坏载荷静默通过单列 §10.5 第 12 条 |
| 18–20 本地同步（2026-11-06 第 9 轮：本机当客户端 + PC 当假伴侣，PC 也直连过本机 8089） | ①**托管**：More → Local sync → 拨 "Sync mode" → 页面给出 "QR code with this device's address" / 机名 / "PIN: 932782" / `http://10.0.2.15:8089/`；`adb forward tcp:18089 tcp:8089` 后从 PC `GET /sync/hello` → **401 "Unauthorized. Check the PIN and try again."**，`/sync/pair`（含用屏上 PIN 932782 那次）与 `/backup.own` 同样 401。`/proc/net/tcp` 里看不到这条 LISTEN（shell 视图限制，不代表没监听）。②**配对**：Connect → "Find a device" → "SEARCHING THE NETWORK…" → "OR TYPE THE ADDRESS" 填 `10.0.2.2:8123` → Continue → "Enter the PIN" 填 6 位 → Pair；PC 日志：`POST /sync/pair`（`X-Companion-Pin: 123456`，64 B）→ 假主机回 `{"secret":"4…4"}` → `GET /sync/hello`（`X-Companion-Pin: 4444…`），界面出现 FakeHost 三向 Send/Receive/Merge + "Forget this device"。③**入站多档案**：Merge → "What to merge" 段列表 → Continue → `GET /backup.own` 取 `fixture-multi.own` → **"Sync failed：The other device sent more than one profile. This app keeps a single profile, so it cannot be merged."**，之后 `profiles` 仍是 `(1,'Rollback Probe', pinHash 有值)`。④当时**没做到**：Send 方向的上行 `POST /backup` 体、托管侧一次真下载（两处均已由下面第 11 轮两行补齐） | **第 19 行通过**；第 18 行当时 ◐（托管页/鉴权/配对/三向 UI 全过，缺一次真对端收发，第 11 轮转 ✅）；第 20 行当时未跑，第 11 轮转 ✅ |
| More 页（单档案外壳） | 页头 "Profile / All playlists · 0 playlists"，条目为 Settings / Downloads / Favorites / History / Backup & Restore / Local sync / Error log / About；**没有**"谁在看"、档案管理与切换入口 | **通过**（与第 27 行互为佐证；注意这是"App 组页/More 页无 Profile 组"的运行期证据，不是迁移证据） |
| 通知权限 | 重装后冷启动首帧弹 "Allow OwnTV to send you notifications?"（Allow / Don't allow），选 Don't allow 后正常进向导 | 观察项：POST_NOTIFICATIONS 的授权请求仍在，本期未新增权限（清单仍 14 条）；第 25 行的深链/显式 intent/热恢复三点已在下面实测，通知点击与 PiP 仍未跑 |
| 7 旋转不重复问 PIN | 正确 PIN 进入 Home 后 `settings put system user_rotation 1`，屏幕仍是 Home（`Start watching…` + 四格底栏），没有回到 "Profile — enter PIN" | **通过**（旋转后的门禁会话保持；低内存杀进程仍是第 8 行，未测） |
| §0.1 选择器 3 行 + 运行期换语言 | 设置搜 "language" → App / Language 行 → 语言页 dump 只有 3 行：**简体中文 / 繁體中文 / English**（各带英文名）+ "System default / Follows device language"；选中简体中文后整壳实时改中文（"语言"、"搜索语言…"、content-desc "投放。已断开连接 / 更多"），切回 English 后恢复 | **通过**：26 行选择器确已收敛为 3 行，且不需要重启 |

| 18/20 本地同步对端双向（第 11 轮） | 取消残留的 "What to merge" 弹窗后从托管页 dump 出活 "PIN: 761769"；PC 侧 `GET /sync/hello?pin=…` → 200、`POST /sync/pair?pin=…` → 200 + 64 hex secret；`GET /backup.own?pin=…` → 200、1348 B、`attachment; filename="owntv-backup.own"`、字节 `OWNTVBK1` + container-aes-gcm/PBKDF2-210000 头；`POST /backup`（secret 头 + `data:application/octet-stream;base64,…` 的 `fixture-multi.own` 697 B）→ 200，logcat `CompanionController: Received remote backup (697 bytes)` | **通过**：设备随即弹 "Sync failed / The other device sent more than one profile. This app keeps a single profile, so it cannot be merged."，`run-as` 回读 `profiles` 仍是 `(1,'Rollback Probe')` 单行、`sources` 0 行，`cache` 里的临时 `.own` 已被删；第 18 行的托管侧真下载与第 20 行的入站上行得到运行期证据 |
| 伴侣鉴权负向复核（第 11 轮） | 伪造 secret 的 `POST /backup` 与 `GET /sync/hello`、错 PIN 的 `?pin=000000` | **通过**：三种打法一律 401，响应体只有 "Unauthorized. Check the PIN and try again."（42 B），不回一个字节数据 |
四处运行期措辞观察（不算偏差，按附录 A 的"复用 core 键"必然结果）：第 7 轮再加两处——① 向导那一步的副标题仍写 "Start fresh, or bring back your **profiles**, playlists and favorite…"，单档案外壳里档案只有一个，复数读起来像还能带回来好几份；② Settings → Layout 的 Home screen 行徽标 "8 rows" 按未过滤的 `home.order` 计数（`SettingsGroupRows.kt:323`），旧配置的 `LIVE_SPORTS` 也被算进去，与下面只列 7 行的 Home screen 页不一致，见 §10.5 第 10 条。原两处：门禁页标题是 core 的 `profiles_enter_pin` 渲染成 "Profile — enter PIN"（档案名默认就是 "Profile"）；App 锁设好以后，设置行的值显示 core 的 "PIN lock" 而非 App 自己的话术。若想让两处都读作"App 锁"，需要在 `strings_mobile.xml` 加两条键并改两个调用点——本期没做，记在这里。第三处同类观察：App 页 `Allow downloads` 的说明仍是 core 的话术 "Off hides every Download button for **this profile**"——档案编辑器已经删掉，"this profile" 这个说法在单档案外壳里指的就是唯一那个档案，语义不错但读起来像还剩多个档案；同样属于"复用 core 键"的代价，本期没新增覆盖键。第 5 轮又见到两处同类残留，都记下来不动 core：导出 sheet 的加密说明写 "playlists, **profiles** and saved passwords included"（复数），恢复成功的提示写 "Re-enter any saved passwords in Sources/Proxy, and any **profile PINs**."——在单档案外壳里那个 PIN 就是 App 锁 PIN，话术却仍是复数"档案 PIN"；另外密封包恢复对话框的 "A sealed backup can carry a profile PIN. If it does, the PIN you use to open this app will change after restoring." 已经是 App 侧口径，说明这三句是同一批 core 键里的新旧混用。

第 5 轮发现的一个真实缺陷（既有代码，与精简无关，本期未改）：导出成功后提示用的是**建议文件名**而不是用户实际保存的文件名。`BackupViewModel.kt:201` 把 `Outcome.Exported(name, …)` 里的 `name` 直接原样回填，而 `name` 是传给 SAF 的建议名（`owntv-backup.own`）；本轮在保存框里把它改成 `sealed.own` 后，文件确实写成了 `/sdcard/Download/sealed.own`（978 B），提示仍是 "Saved to owntv-backup.own."。同一 App 里 `SettingsAppPage.kt:224` 用的是 `exportPath`，也就是说正确的显示名在别处是拿得到的，只是这条路径没用。改法很小（用返回 URI 的 last path segment 覆盖建议名），但它动的是备份主流程的措辞与状态，不在 D1–D10 的任何一条里，本期只记录不修。

走查方法上的四条硬限制（第 1、2 条第二轮踩到，第 3、4 条第三轮踩到；都不是 App 的 bug，但都会造成假红/假绿）：

1. Stage 的开关在 a11y 树上**不暴露状态**：整行的节点是 `cls=View clickable=true long-clickable=true`，右侧开关是 `View` 且 `checkable=false`、`checked=false`、无 `content-desc`，所以 `uiautomator` 既读不出"开/关"，也无法用 `checked` 判定点击是否生效。可行的读法是**搜索列表右侧的值文本**（"On" / "Off"，如 `Kids mode → Off`、`Allow downloads → On`），或进叶子页看值行。TalkBack 用户同样读不到开关状态，这是既有组件的可访问性缺口，不是本期引入。
2. **dump 与实际渲染位置会错位**：`uiautomator dump` 自身约 2.3 秒，且这页有置顶/回弹动画。第二轮按上一次 dump 的坐标点 `Allow downloads`（y=1274–1323）两次都落在别处——第二次打开了 "App startup" 选择 sheet。第三轮改成"每次点击前重新 dump、用当次 bounds 的 centre"（y=2004），一次就拨动了，On→Off→On 往返可复现。所以旧坐标不可复用，别把方法问题当成组件问题——第二轮那句"拨不动"的结论已被本轮推翻。
3. **搜索结果行是导航，不是开关**：在搜索列表点 `Kids mode` / `Allow downloads` 只会跳到所属组页（还停在组页顶部，不滚动到目标行），值不变。真正要点的仍是组页里的行；搜索列表只用来**读**值。
4. **两个会静默骗人的地方**：一是 `dump` 写文件失败时旧文件仍在，解析脚本照常输出上一屏——本轮差点把"冷启动后仍是设置页"当成门禁没生效，改成每次 dump 前先删旧文件，才看清那确实是 "Profile — enter PIN"；二是 IME 一弹就把对话框整体上移约 130px（PIN 输入框 y 1961→1829、OK 2095→1963），按弹键盘前的坐标点 OK 实际点的是 Cancel，看起来像"正确 PIN 也不放行"。对话框一律按当次 dump 的文本重新定位（`t "OK"` 这类），输入前后各 dump 一次。

`:app:connectedStandardDebugAndroidTest` 跑完会把**被测包从设备上卸掉**（本轮实测：connected 绿后 `pm list packages` 里没有 `tv.own.owntv.mobile`，`am instrument` 报 `Unable to find instrumentation target package`，重装 `app-standard-debug.apk` 才能继续）。连带后果：卸载会清掉应用数据，所以装机后 App 回到全新向导（门禁 PIN 也随之消失，"App lock" 行显示 "No lock"）。这既解释了早前那次 `tests="0"` 的时间线，也意味着**同一台模拟器上的手工矩阵与 instrumented 跑不能简单串联**——先跑 connected，再重装，再走查。

instrumented 首轮 6 例全红、根因与修复（本轮唯一动到的既有测试文件，且只动测试）：

- 首轮 `adb shell am instrument -w -e class tv.own.owntv.mobile.ui.stage.StageFieldTest …` 报 `Tests run: 6, Failures: 6`。六条信息全是语义树断言，不是超时或崩溃：`(RequestFocus is defined)`、`(PerformImeAction is defined)`、`(Focused = 'true')`，节点描述一律是 `Tag: 'field' … Has 2 children`。
- 根因静态可核对：`StageField` 把调用方的 modifier（含 `testTag`）加在最外层 `Column`（`StageControls.kt:609`），而 `RequestFocus`/`PerformImeAction`/`Focused`/`EditableText` 都挂在内层 `StageSearchField` 的文本节点上；合并语义树不会把这些属性上提到那个 Column，所以 `onNodeWithTag("field")` 永远选不到可输入节点。这是选择器问题，不是 headless 镜像问题（headless 只会让时序类断言不稳，不会让"某 action 是否定义"变红）。
- 这 6 条红是既有债务，不是精简引入的：`git diff main...HEAD -- app/src/androidTest app/src/main/java/tv/own/owntv/mobile/ui/stage/` 无输出，本分支既没碰组件也没碰该测试，`main` 上跑同样会红。
- 为了把"选择器写错"与"焦点/IME 真回归"分开，临时加了一个孪生探针类：6 个场景与 6 组断言完全照抄，只把 `onNodeWithTag` 换成 `onNode(hasSetTextAction())`（多字段用 `onAllNodes(...)[index]`）。同一台模拟器 → `OK (6 tests)`，即被测行为本身成立：点选获得焦点、输入/粘贴/删除回写、数字 PIN 过滤、Search 触发 `onImeDone`、Next 把焦点移到下一个、滚动表单里最后一个字段仍可见可聚焦。
- 据此把 `StageFieldTest.kt` 的选择器改成 `hasSetTextAction()`，删掉探针类与已无意义的 `testTag`，生产代码一行未改。改后 `:app:connectedStandardDebugAndroidTest` 报 `tests="6" failures="0"`，JVM 28 例复跑仍 0 失败。
- 一处仍未解释的：更早一次同一条 connected 任务写出过 `tests="0"` 的 XML，当时直接 `am instrument` 报 `Unable to find instrumentation target package: tv.own.owntv.mobile`（AGP 已卸载被测包），重装两个 APK 才跑起来。本轮两条路径都能执行 6 例，`tests="0"` 没能复现，记录在案，不当作已解释。

未做到（诚实口径）：

- §7.2 的 28 行：十一轮走查后**整行过了 19 行**（1/6/9/10/27 + 3/12/13/14/17 + 21/22 + 4/5 + 2 与 24 + 18/19/20），**9 行各过一半**（7 旋转与缩放后不再问 PIN、8 真 lmkd 压力、11 位置与拨动但"按钮消失"未见、15 取消不改动但"中途失败回滚"未造出、16 多档案 + 匿名拒绝过了而休眠同名只有单测、23 旁证过了而"真闹钟被 cancel"未跑、25 三点过了而通知点击与 PiP 未跑、26 系统侧未确证、28 的 PIN 门过了而分类锁未测），另有"新行可搜索"与语言选择器两项检查；**已没有完全未跑的行**（第 9 轮时本地同步只到 19 行，18/20 由第 11 轮用一台 PC 当对端补齐，不需要第二台设备）。第 7 轮把第 8 行从"只有 force-stop"推进到"真杀进程"：后台态 `am kill` 掉进程后重启确实重新要 PIN（0 崩溃、0 ANR），只剩真正的 lowmemorykiller 水位压力造不出来。第 7 轮又收掉 26 的 App 侧一半（`system_locales` 写成 ar-SA 优先后，指定英文与跟随系统两种状态都是英文界面、无阿拉伯字母、无崩溃、底栏仍 LTR），但那一半要求"系统真的按 ar 生效"在这台不可 root 的镜像上确证不了，只能算 ◐。**剩下的前置条件仍分两类**：一类要"有条目的内容源或正在播的会话"（11 的按钮消失、25 的通知与 PiP、7 的缩放后不再问 PIN、28 的分类锁），装机 0 titles 看不到，且已核实 VOD 只能来自 IPTV provider（core `SourceType` 没有本地文件夹），推媒体文件没用；一类要"旧数据 + 升级"，本期没有迁移工具（D1），第 5–7 轮已用改 `shared_prefs` / 改 Room 库 / 手工 `.own` 样例 / 手工 `alerts.json` 四种手法把 21/22、4/5、2、23、24 全部铺出来，只有"真闹钟被 cancel"这一步铺不出一台设备从未排上过体育闹钟。
- androidTest 与 `assembleStandardRelease` 本轮都跑了（见上表）；release 只缺签名，产物是 `app-standard-release-unsigned.apk`。instrumented 首轮 6 例全红，原因与修复见本节末尾。
- 该镜像当时内存吃紧：装机首启时 `com.google.android.dialer` 与 `com.android.systemui` 都弹过 ANR，logcat 有 lowmemorykiller "device is not responding / low watermark is breached" 记录，**没有**我方进程的 FATAL/tombstone；等 System UI 恢复后 App 全程无崩溃。所以这些 ANR 归因于宿主压力，不是被测改动；但同一压力下，任何依赖时序的行（如第 8、25 行）结论都不该当真机定案，重跑时建议换 `-gpu swiftshader_indirect` 或先关掉 Google 后台。

### 10.5 遗留项

| # | 项 | 触发条件 |
|---|---|---|
| 1 | §7.2 已**没有完全未跑的行**，余下 **9 行的另一半**（整行已过 1/6/9/10/27 + 3/12/13/14/17 + 21/22 + 4/5 + 2/24，§7.4 的"新行可搜索"与语言选择器也已过，见 §10.4 的实测列） | 第 11 轮已把 20 与 18 的另外半截跑通（对端配对 + 入站 `POST /backup` 被拒 + 托管侧 `GET /backup.own` 真下载，见 §7.2 第 18/20 行），剩下的半截是：26 的系统侧确证（这台镜像 adbd 非 root：`adb root` 不生效、`setprop persist.sys.locale` 写不进、`cmd locale set-locale` 不存在，要么换 rootable 镜像要么在 Settings 语言列表里把阿拉伯语拖到 #1）、8 的真 lmkd 压力与 7 的缩放后不再问 PIN、11 的"按钮消失"、25 的通知点击与 PiP 与播放服务、28 的分类锁、15 的"真失败回滚"、16 的休眠同名、17 的"密封包真改写 PIN"、23 的"真闹钟被 cancel"。**两类前置条件**：一类要"有条目的内容源或正在播的会话"（11、25、28、7 缩放），装机 0 titles 看不到，且已核实 VOD 只能来自 IPTV provider（core `SourceType` 没有本地文件夹），推媒体文件没用；一类要"旧数据 + 升级"（本期没有迁移工具，D1），第 5–7 轮已把 21/22、4/5、2、23、24 都铺出来了，手法见 §10.4 各轮行。装机与走查脚本已跑通十一轮；按 §10.4 末尾四条方法限制走，别复用旧坐标（140% 字体会把整页坐标全挪一遍）；`connectedAndroidTest` 会卸载并清空数据，要放在手工走查之前 |
| 2 | instrumented 已在 `Pixel5_API35` 模拟器跑绿（6 例 0 失败），但选择器修复与 `main` 上同红的既有债务这一事实都还没提交、也没在真机上复跑 | 真机（或另一镜像）复跑一次 `:app:connectedStandardDebugAndroidTest` 即可关闭；顺带决定要不要把同一修法回灌 `main` |
| 3 | release 产物已构建并复核语言转储（54,651,531 字节、未签名） | 缺的只有签名与上架口径验证；`isMinifyEnabled` 对反射/播放的运行时影响仍未在设备上跑过 |
| 4 | 是否删掉 debug 的 `ar-rXB`（连带清掉 debug 包里 core 的真阿拉伯语 3380 条） | 用户定夺，见 §10.3 第 7 条；代价是失去 RTL 伪语言压测 |
| 5 | 是否把 `locales.json`/CI diff 一并收敛到三种语言，并清 `android.yml:75`、`automerge-core-bump.yml:8` 两处过期注释 | 需要超出 app-only 的授权 |
| 6 | 旧版本存过 `en-GB` 等标签的用户：回落"跟随系统"，不改写数据库里的值 | 已是既定行为（D6/D7），无需动作 |
| 7 | **CI 在本分支仍会红**：`.github/workflows/android.yml` 按 D1 用发布 pin（`1.0.69`）解析 core，而 HEAD 需要 `liveRestoreKey`/`guideFollowsLiveCategory`，那两个名字只在 core 的开发分支里。本机带 `-Powntv.corePath` 全绿，CI 不带就得等 core 发版。已在 `README.md` 的"Resolving the core library"写明这一条 | 需要 core 发布（超出 app-only，本期不碰） |
| 8 | 导出成功提示报**建议文件名**而不是实际保存的文件名（第 5 轮实测：存成 `sealed.own`，提示仍写 "Saved to owntv-backup.own."）：`BackupViewModel.kt:201` 回填的是传给 SAF 的 `name`，而同页 `SettingsAppPage.kt:224` 已经在用 `exportPath` | 既有缺陷，与精简无关；修法很小（改用返回 URI 的 last path segment），但动的是备份主流程，不在 D1–D10 里，要不要本期一起修由用户定 |
| 9 | 三处 core 话术在单档案外壳里仍写成复数："playlists, **profiles** and saved passwords included"（加密说明）、"…and any **profile PINs**."（恢复成功提示）、"Off hides every Download button for **this profile**"（下载开关说明）；同批键里也有已是 App 口径的句子（"the PIN you use to open this app"），新旧混用 | 要清得在 `strings_mobile.xml` 加覆盖键并改调用点（附录 A 的口径），本期按"复用 core 键"的既定取舍不动 |

| 10 | Settings → Layout 的 Home screen 行徽标用 `home.order.count { it !in home.hidden }`（`SettingsGroupRows.kt:323`），旧配置里的 `LIVE_SPORTS` 仍被计入，于是"8 rows"对上下面只有 7 行 | 一行改成同一处过滤即可，要不要本期修由用户定（第 7 轮实测发现） |
| 11 | 导出 sheet 的 "ENCRYPT THIS BACKUP?" 小节在 140% 字体下**没有密码输入行**：第 8 轮直接复核，界面上只剩那句 "With a backup password, the whole file is encrypted — playlists, logins & PINs are protected." + `Export unencrypted` + `Cancel`，点说明文字也不会展开输入框；同一小节的 `Backup password` 框与 `Encrypt & export` 按钮在第 5 轮（100% 字体）是存在的。也就是说把 App 字体调到 140% 后，密封备份这条路在导出 sheet 里走不通 | 与精简无关的既有布局/滚动缺陷（高字体下该小节内容被裁掉）。它是第 17 行"密封包真改写 PIN"这半条的唯一剩余障碍：把字体调回 100% 即可复现第 5 轮的输入框并重跑，要不要本期查由用户定 |
| 12 | 备份文件里的**内层载荷坏了不会报错**：第 8 轮把 `epgSources` 写成非法 JSON（外层 `backup.json` 仍合法）后恢复，界面照常给 "8 items restored."，没有任何一句"某段没吃进去"。这条路径是 `BackupManager.import` 的 Result 聚合，不是本期新增的代码 | 影响两件事：一是用户拿到一份部分损坏的备份时得不到提示；二是 §7.2 第 15 行"恢复中途失败 → 名字回滚"因此没法用数据注入的办法造出来。要不要本期加一句 warning 由用户定（改动会落在 core 的 import 结果上，与 D1 有冲突风险） |

| 13 | 本机同步的**屏上 PIN 前两轮"从外部用不上"是走查手法问题，不是产品缺陷**：第 9/10 轮屏幕上留着上一轮没关的 "What to merge" 弹窗，读到的 "PIN: 932782" 是过期值，而 `CompanionController.kt:142`/`:290` 每次 `start()` 都用 `SecureRandom` 现取一个新的 6 位 PIN，所以打过去一律 401。第 11 轮克 Cancel 掉弹窗、从托管页 dump 出活 "PIN: 761769"，同一台 PC 立刻配对成功（200 + 64 hex secret）、真下载了本机容器（1348 B 密封 `OWNTVBK1`）、也真把 3 档案包推了进来（设备弹单档案拒绝、`profiles` 未动） |要跑"对端 → 本机"不需要第二台真设备，前提是走查时先清掉残留模态再读 PIN（已写进 §10.4 本轮方法）。鉴权面仍是好消息：伪造 secret、错 PIN、无凭证一律 401 且只回 "Unauthorized. Check the PIN and try again."（42 B） |
### 10.6 文档（D10）实际更新

| 文档 | 本轮改了什么 |
|---|---|
| `README.md` | 单档案 + App 锁、三种语言、无体育、备份/同步只带当前档案、11 个设置组、core 边界与 `strings_mobile.xml` |
| `CHANGELOG.md` | 新增"Mobile 精简"条目：i18n / 体育撤除 / 单档案，含迁移与兼容提示 |
| `extras/FEATURE_MAP.md` | 删体育与多档案章节，改写 i18n、Settings、Backup、Local sync、Home、清单、外部服务、已知限制与术语表；§9 记录本期演进；**第二轮**再改 §6.2 与 §7 第 27 条：作废"本分支编不过"，改成"不带 `-Powntv.corePath` 才编不过"，并记录 28 例单测绿 + `StageFieldTest` 6 例接机绿（含选择器缺陷与它在 `main` 上同红的事实）+ release/debug 两侧按 config 行计数的语言转储数字。**第三轮**：已知限制加第 31 条（搜索结果行只导航不改值）与第 32 条（手机端没有任何深链入口，"深链绕过门禁"属攻击面不存在），§7 的走查结论从"4 行过 / 24 行未跑"改为"5 行整 + 4 行半 + 19 行未跑"。**第五轮**：该行再改为"10 行整 + 6 行半 + 12 行未跑"并写入备份/恢复实测与两类前置条件；§7 已知限制加第 33 条（导出提示用建议文件名）与第 34 条（三处 core 复数话术 + 新旧混用），并把末尾重复的"见 §10"一句删掉。**第七轮**：走查结论行改为 16 整 / 7 半 / 5 未跑，已知限制加第 35 条（Home screen 行数徽标把休眠的 `LIVE_SPORTS` 计入） |
| `extras/OwnTV_Master_Product_Brief.md` | 产品范围/角色/语言/单档案；明确电视端仍有多档案与体育；**第二轮**改写 §1 的 Status：编译/lint/debug+release assemble/28 例单测/6 例 instrumented 已绿。**第三轮**再把 Status 的实测段改成"5 个场景看全（含儿童模式开与关闭需 PIN）+ 4 个看半（旋转、下载开关拨动但无按钮可隐藏、三类入口不绕门禁、PIN 保护受保护动作）"，并把"Kids Mode / Downloads / bypass 全无证据"那句删掉。**第四轮**补 Change/Remove PIN 与"移除后不再提示"。**第五轮**：Status 的实测段改为"10 个看全 + 6 个看半"，新增备份/恢复五句（含三条界面原文与 `OWNTVBK1` 密封格式）、"仍无证据"清单把 backup 换成 local sync 与"全新安装导入带 PIN 的备份"，末尾补两类前置条件（含 `SourceType` 无本地文件夹）与导出提示用建议名这一缺陷。**第七轮**：16 个看全（新增带 PIN 备份在向导里恢复即锁、旧 Home 配置不显示体育行）+ 7 个看半（新增旧体育残留的升级旁证）+ 5 个未跑 |
| `extras/PARITY_CHECKLIST.md` | 删手机端体育/多档案项，新增"TV 有、Mobile 无"有意差异一节（体育、多档案与切换、多档案容器）；**第二轮**改文首 ✅ 口径：从"仅静态核对"改为"已编译、lint 绿、28 例单测绿、6 例 instrumented 在模拟器绿"。**第三轮**把场景数从"5 个看过"改成"5 个看全 + 4 个看半"，并点明 Downloads 那行的可见部分仍需要一个有条目的源。**第五轮**：文首口径改为"10 个看全（新增备份五条：单档案导出、多档案拒绝且文件不变、同名直恢复、异名先提示且 DB 真改名、密封包先要密码）+ 6 个看半（新增取消/错密码不改名与多档案拒绝本身）"，"其余 ✅ 只是静态核对"的点名从"backup, sync,…"改成"local sync, category-lock, migration" |
| `extras/USER_GUIDE.md` | 面向普通用户改写：向导无建档步骤、四格底栏、删 Sports 章节、"👤 Your profile & App lock"、备份/同步与多档案拒绝、三种语言、下载开关位置、排障新增四行；**第三轮不改**：本轮实测到的行为（"有 App 锁时关闭儿童模式要先输 PIN"、"关掉下载开关就没有 Download 按钮"、"同一个 PIN 也开分类锁"）文档早就这么写了，实测只是把它从"写着"变成"看过"；**第五轮同样不改**，但按实测核对过它的备份段三句：拒绝并说明理由 + "your file is left exactly as it is"（被拒文件字节数与 mtime 复测一致）✅、异名"asks first, on the screen where you confirm" ✅（提示原文与恢复后 DB 里的名字都看过）；"puts the old name back if the restore fails" 只有代码证据（`BackupViewModel.kt:320-324` 先改名、保留 `before` 行供回滚），本轮没能制造一次中途失败；"starts downloading the restored playlists straight away" 在装机状态下无法判定——本轮那份备份里没有任何播放列表，恢复完只给了 "Re-sync your sources to load content."，既不能据此说指南写错，也不能说它已被验证 |
| `extras/SLIMMING_PLAN.md` | 本节（§10）+ 文首状态行 + 附录 A 的复数键标注；§10.4/§10.5 按实测重写。**第三轮**：§7.2 表加"实测"列（✅/◐/⬜ + 轮次），第 10 行由"仍未通过"改为通过，第 11 行拆开"拨动已实测/按钮消失未测"，第 25、28 行新增实测半截，走查方法限制从 2 条扩到 4 条（搜索结果行只导航、IME 弹起使对话框上移 130px、dump 失败留旧文件会假绿、旧坐标不可复用），并推翻第二轮"开关拨不动"的结论。**第四轮**：§10.4 第 28 行补 Change/Remove PIN 与"移除后不再提示"，§7.4 的 App 锁行随之改写，走查说明改为四轮并记录收尾设备状态。**第五轮**：§7.2 的 3/12/13/14/15/16/17 七行全部改写为实测口径（5 行整 + 2 行半），§10.4 新增"12–17 备份与恢复"一行并补第 5 轮设备收尾状态，措辞残留从三处扩到五处（导出加密说明的复数 profiles、恢复提示的 "any profile PINs"），新增 §10.5 第 8 条（导出提示用建议名而非实际文件名，附 `BackupViewModel.kt:201` 与 `SettingsAppPage.kt:224` 的对照）与第 9 条（复数话术），§7.4 的备份行由未勾改为已勾并列明仍缺的四点，§10.2 的未跟踪文件口径按 `git status`/`wc -l` 校正为 10 个新文件 893 行 / 12 项未跟踪（此前"9 个 779 行""多出的 5 项是 tools/i18n 与 .github"两句都是错的），文首与 §10.1/§10.5 的轮次与行数同步改为"五轮 / 10 整 / 6 半 / 12 未跑"，并把"几乎全部行都需要内容源"改成两类前置条件（有条目的源或播放会话 / 旧数据 + 升级），并补上"`SourceType` 无本地文件夹，推媒体文件不产生条目"这一核实结论；同一轮又用改 `shared_prefs/owntv_locale.xml` 的办法实测了 21/22（旧标签在冷启动时被就地归一化），文首、§7.2、§7.4、§10.4、§10.5 与另外三份文档的行数随之改为 12 整 / 6 半 / 10 未跑。**第六轮**：用改 Room 库的办法实测 §7.2 第 4/5 行与 §7.4 的旧档案休眠行（14 整 / 6 半 / 8 未跑），§10.4 记下两条方法教训（二进制必须 `base64 -w 0` 否则 `adb shell` 文本通道会截断成 malformed image 并让 Room 崩在 configureJournalMode；只推主库不回灌 WAL 等于回滚到旧镜像，会让 App 显示未完成的向导，收尾改用 `pm clear` 归位为全新安装） |

---

## 附录 A：新增 App 本地字符串

三份文件键一致；英文为默认值，中文为翻译。核心键不在本表。

| 键 | 英文示例 | 用途 |
|---|---|---|
| `mobile_app_lock` | App lock | 设置行标题 |
| `mobile_app_lock_description` | Ask for a PIN when the app opens | 设置行说明 |
| `mobile_app_lock_set` | Set app lock | 新 PIN 表单标题/按钮 |
| `mobile_app_lock_new` | New PIN | 新 PIN 输入 |
| `mobile_app_lock_change` | Change PIN | 管理页按钮 |
| `mobile_app_lock_remove` | Remove app lock | 管理页按钮 |
| `mobile_app_lock_warning` | The same PIN protects locked categories and the Settings PIN. If you forget it, you must clear the app data. | 警告 |
| `mobile_unlock` | Unlock | 锁屏按钮 |
| `mobile_kids_mode_description` | Limits browsing to child-friendly categories. Set an app lock so it cannot be turned off. | 儿童模式说明 |
| `mobile_multi_profile_import` | This backup contains more than one profile. This app keeps a single profile, so it cannot be restored. | 恢复拒绝 |
| `mobile_multi_profile_sync` | The other device sent more than one profile. This app keeps a single profile, so it cannot be merged. | 同步拒绝 |
| `mobile_hidden_profile_import` | This backup's profile has the same name as another profile stored on this device. Rename it on the other device, or restore on a fresh install. | 休眠同名 |
| `mobile_hidden_profile_sync` | The incoming profile has the same name as another profile stored on this device. Rename it on the other device and sync again. | 休眠同名 |
| `mobile_nameless_profile_import` | This backup's profile has no name, so it cannot be matched. Name the profile on the other device and export again. | 匿名档案 |
| `mobile_nameless_profile_sync` | The incoming profile has no name, so it cannot be matched. Name it on the other device and sync again. | 匿名档案 |
| `mobile_profile_rename_notice` | This backup's profile is “%1$s”. This device's profile will be renamed to match before restoring. | 恢复重命名 |
| `mobile_profile_rename_notice_sync` | The incoming profile is “%1$s”. This device's profile will be renamed to match before merging. | 同步重命名 |
| `mobile_restore_pin_notice` | A sealed backup can carry a profile PIN. If it does, the PIN you use to open this app will change after restoring. | PIN 覆盖提示 |
| `mobile_sync_pin_notice` | A synced profile can carry a PIN. If it does, the PIN you use to open this app will change after syncing. | PIN 覆盖提示 |
| `mobile_restore_from_settings` | This backup's profile name differs from this device's profile. Open Settings → Backup & Restore to restore it, where the rename can be confirmed. | Setup 拒绝重命名 |
| `mobile_start_fresh` | Start fresh | Setup 卡片 |
| `mobile_start_fresh_description` | Add a playlist now, or skip and add one later. | Setup 卡片 |
| `mobile_restore_backup_description` | Restore a backup from this phone or another device. | Setup 卡片 |
| `mobile_backup_single_profile_note` | This backup contains the current profile only. | 导出说明 |
| `mobile_app_lock_confirm` | Confirm PIN | App 锁页（实施时新增） |
| `mobile_unlock_too_many_attempts` | Too many attempts. Try again in %1$d second/seconds. | 门禁冷却（实施时新增；`<plurals>`，非 `<string>`） |

实际落地 **26** 个键 = 25 个 `mobile_*` 字符串 + 1 个 `mobile_*` 复数（本表原列 24 个 + 上面两行）；三份文件键集一致由 `MobileStringsParityTest` 守着。

复用 core 键：

- `profiles_allow_downloads`
- `profiles_allow_downloads_description`
- `settings_profile_kids`
- `profiles_enter_pin`
- `profiles_wrong_pin`
- `setup_default_profile`
- `settings_customize_pin_mismatch`
- `settings_language_device`
- `settings_backup_restore_title`
- `settings_backup_*`（导出/恢复现有文案）

---

## 附录 B：文件改动清单

### B.1 i18n

| 文件 | 动作 |
|---|---|
| `app/src/main/java/tv/own/owntv/mobile/i18n/AppLocales.kt` | 新增 |
| `app/build.gradle.kts` | 删除 locales.json 解析；3 语言白名单；注释更新 |
| `app/src/main/java/tv/own/owntv/mobile/OwnTVMobileApp.kt` | attach/onConfigurationChanged 归一化；全局守卫 |
| `app/src/main/java/tv/own/owntv/mobile/MainActivity.kt` | attachBaseContext 归一化；体育钩子删除（同时属于体育） |
| `app/src/main/java/tv/own/owntv/mobile/ui/LocalizedContent.kt` | 归一化 tag/startTag |
| `app/src/main/java/tv/own/owntv/mobile/playback/DataSaverGate.kt` | 归一化 |
| `app/src/main/java/tv/own/owntv/mobile/playback/PlaybackService.kt` | 归一化 |
| `app/src/main/java/tv/own/owntv/mobile/ui/setup/SetupWizardSteps.kt` | 语言选择器 3 行；删除 ProfileStep |
| `app/src/main/java/tv/own/owntv/mobile/ui/screens/settings/SettingsLanguagePage.kt` | 3 行；删除贡献/请求入口 |
| `app/src/main/java/tv/own/owntv/mobile/ui/screens/settings/SettingsAppPage.kt` | 语言数量 3 |
| `app/src/main/java/tv/own/owntv/mobile/ui/screens/settings/SettingsGroupRows.kt` | `localeLabel` 用 `AppLocales.rows` |
| `app/src/main/res/values/strings_mobile.xml` | 新增 |
| `app/src/main/res/values-zh-rCN/strings_mobile.xml` | 新增 |
| `app/src/main/res/values-zh-rTW/strings_mobile.xml` | 新增 |
| `tools/i18n/README.md` | 说明 App 白名单 |
| `app/src/test/java/tv/own/owntv/mobile/i18n/AppLocalesTest.kt` | 新增 |
| `app/src/test/java/tv/own/owntv/mobile/i18n/MobileStringsParityTest.kt` | 新增 |

### B.2 体育

| 文件 | 动作 |
|---|---|
| `app/src/main/java/tv/own/owntv/mobile/ui/screens/sports/*` | 删除（13 个） |
| `app/src/main/java/tv/own/owntv/mobile/di/SportsModule.kt` | 删除 |
| `OwnTVMobileApp.kt` | 删模块/后台/延迟；加旧闹钟清理 |
| `MainActivity.kt` | 删权限委托/深链/onDestroy 清理 |
| `ui/nav/MobileNavHost.kt` | 删路由/回调/imports |
| `ui/nav/MobileDestination.kt` | 删 SPORTS |
| `ui/shell/MobileShell.kt` | 删 SportsPlayback/分支/参数 |
| `ui/shell/SyncStatusPill.kt` | 删体育重建 |
| `ui/screens/home/HomeScreen.kt` | 删体育行/卡片 |
| `ui/screens/settings/SettingsHomePage.kt` | 过滤 LIVE_SPORTS |
| `ui/screens/settings/SettingsGroupRows.kt` | 删体育行 |
| `ui/screens/settings/SettingsLeaf.kt` | 删体育 leaf |
| `ui/screens/settings/SettingsDestination.kt` | 删体育分发 |
| `ui/player/PlayerScreen.kt` | 删体育会话/比分/其他频道 |
| `ui/player/PlayerControls.kt` | 删体育参数/按钮 |
| `ui/components/MobileIcon.kt` | 删 `Sports` 图标 |
| `app/src/main/AndroidManifest.xml` | 3 个 `tools:node="remove"` |

`ui/screens/guide/GuideMatchUi.kt` **不动**。

### B.3 多账户 → 单档案

| 文件 | 动作 |
|---|---|
| `ui/profiles/ProfileGate.kt` | 重写为单档案解锁页 |
| `ui/profiles/ProfileGateSession.kt` | 不动 |
| `ui/profiles/ProfilesViewModel.kt` | 只留 profiles/activeProfileId/verifyPin |
| `ui/profiles/ProfileSheets.kt` | 保留 `ProfilePinSheet`；删 `ProfileEditorSheet`；常量 internal |
| `ui/shell/MobileShell.kt` | 新门禁分支；删选择器 Dialog |
| `ui/shell/ShellViewModel.kt` | 加 `recovering` |
| `ui/screens/settings/SettingsViewModel.kt` | 加 activeProfile/verifyPin/setAppLockPin/setKidsMode/setDownloadsAllowed/profileManager |
| `ui/screens/settings/SettingsGroupRows.kt` | 删 Profile/体育；加 App 锁/儿童模式/下载 |
| `ui/screens/settings/SettingsCommon.kt` | 删 PROFILE 组 |
| `ui/screens/settings/SettingsProfilePage.kt` | 删除 |
| `ui/screens/settings/SettingsScreen.kt` | 删 PROFILE 分支/注释 |
| `ui/screens/settings/SettingsAppLock.kt` | 新增 |
| `ui/screens/settings/BackupViewModel.kt` | 单档案导出；`BackupProfiles` 决策；`Refused`；重命名/回滚 |
| `ui/screens/settings/SettingsBackupPage.kt` | 删多选/他档 PIN；重命名/PIN/拒绝 UI |
| `ui/screens/settings/LocalSyncViewModel.kt` | 单档案托管/发送/回传；入站决策；重命名/回滚；blocked |
| `ui/screens/settings/SettingsLocalSyncPage.kt` | WizardStep.CONTENT；BlockedSheet；提示 |
| `ui/setup/SetupViewModel.kt` | 删 create/existing；blocked；新 finish |
| `ui/setup/SetupFlow.kt` | 删 CREATE_PROFILE/EXISTING；Choice/Back；blocked 根 sheet |
| `ui/setup/SetupWizardSteps.kt` | Choice 文案；删 ProfileStep/AvatarStrip |
| `ui/setup/WizardFrame.kt` | PROFILE → CONTENT |
| `ui/screens/MoreScreen.kt` | 删 Switch/回调；卡片只读 |
| `ui/nav/MobileNavHost.kt` | 删 onSwitchProfile/onOpenProfiles |
| `di/ShellModule.kt` | `ProfilesViewModel(get(), get(), get())`；`BackupViewModel`/`LocalSyncViewModel` 新参数 |
| `di/SettingsModule.kt` | `profileManager = get()` |
| `backup/BackupProfiles.kt` | 新增 |
| `app/src/test/java/tv/own/owntv/mobile/backup/BackupProfilesPolicyTest.kt` | 新增 |
| `app/src/test/java/tv/own/owntv/mobile/ui/screens/settings/SettingsSearchCoverageTest.kt` | 更新豁免/索引 |

### B.4 不改的文件

- `ui/screens/guide/GuideMatchUi.kt`（EPG 匹配）
- core 的 `ProfileManager`、`ProfileDao`、`ProfileEntity`、`BackupManager`、`LocalSyncManager`、`SupportedLocales`、体育类
- 数据库 schema、备份格式、同步协议

---

## 附录 C：实施顺序速查

1. 建分支，跑基线测试，抓样本备份。
2. 上 i18n（独立，先让字符串/语言稳定）。
3. 上体育（独立，先减少编译面）。
4. 上 Shell/门禁（单档案骨架）。
5. 上设置（App 锁/儿童/下载）。
6. 上 Setup（新装/加源）。
7. 上备份/同步（策略/重命名/拒绝）。
8. 跑单测 + 手工矩阵 + 修 P0/P1。
9. 更新 6 份文档 + 本方案，发版说明。
