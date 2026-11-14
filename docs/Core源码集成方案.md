# OwnTV_Core 源码集成与本地改造方案

> 集成对象：`E:\develop\ideaPoject\git\Own\OwnTV_Core`（来自第三方仓库；本地 clone 关联自己的 GitHub fork，下称 **Core**）
> 集成目标：`E:\develop\ideaPoject\git\Own\OwnTV_Mobile`（下称 **Mobile**）
> 采用方式：**Core 源码入仓（`vendor/OwnTV_Core`）+ 无条件 `includeBuild`；Core 的改造分支推送到自己的 fork，按需吸收上游功能**
> 状态：**P0–P5 已落地并推送 fork（2026-11-13）；TVBOX 下载接入 Core P0 已推送（2026-11-14）**。Core fork：https://github.com/ghnk211/OwnTV_Core，`mobile` @`b3549819`（下载 SPI + schema 55，`origin/mobile` 已同步 `e0556f0f..b3549819`）；Mobile `mobile-mini` 已推送（启动期恢复 TVbox 运行时 `f15c18ed`、py 源方案文档 `7458c777`、Py 采集引擎 `0805cf5b`、TVBOX 下载接入 `412daab`）；`vendor/OwnTV_Core` 快照随 `build: sync vendored core (b3549819)` 同步。两仓 vendor 文件 blob 哈希逐一致。
> 日期：2026-11-07（初版）；2026-11-08 按本地实际状态修订；2026-11-11 P0–P5 实施并本地同步

---

## 0. 本地仓库现状（2026-11-08）

| 仓库 | remote | 日常改动分支 | 镜像分支 |
|---|---|---|---|
| Mobile | `origin` = ahXN00/OwnTV_Mobile（上游，只读参考）<br>`fork` = ghnk211/OwnTV_Mobile（可写） | `mobile-mini` @`62c5058`（跟踪 `fork/mobile-mini`） | `main` @`8572b50`（跟踪 `origin/main`） |
| Core | `origin` = ghnk211/OwnTV_Core（可写）<br>`upstream` = ahXN00/OwnTV_Core（push 已设为 `DISABLED`） | `mobile` @`0619549b`（跟踪 `origin/mobile`） | `main` @`9de56d71`（跟踪 `upstream/main`） |

- `vendor/OwnTV_Core` 与 Core `mobile` @`0619549b` 逐文件一致（1014 个文件，排除 `.git` 与构建产物）。
- Core `mobile` 当前领先上游 `main` 4 个本地提交、落后 12 个上游提交；上游新功能按第 5 节流程按需吸收，不自动合并。
- 本机签名材料在仓库外 `E:\develop\ideaPoject\git\Own\keyStore`（测试/正式 keystore + `owntv-signing.properties`）；`assembleStandardDebug` 与 `assembleStandardRelease` 均产出已签名 APK。

---

## 1. 约束与目标

| # | 约束 | 对方案的直接影响 |
|---|---|---|
| 1 | Core 来自第三方仓库 | 在自己的 GitHub 上 fork 一份；`origin` = 自己的 fork（可写），`upstream` = 第三方（只读） |
| 2 | 不修改原始第三方库，也不向第三方仓库提交 | 改动只推送到自己的 fork；`upstream` 禁用 push |
| 3 | 原仓库功能过多，需要删减改造 | 改动按“一个功能/一组文件”成组提交；维护改造清单；删减后重跑 Core 自身检查 |
| 4 | 本地测试只依赖改造后的代码 | Mobile 构建只使用 `vendor/OwnTV_Core` 快照；Maven 仓库不再解析已发布的 core |
| 5 | 偶尔查看上游新功能，需要时吸收 | 用 `fetch + log/diff` 查看；单功能用 `cherry-pick`，多个功能或差距大时整分支 `merge` |

**最终形态**：

```text
第三方 OwnTV_Core（upstream，只读）
        │  git fetch
        ▼
本地 clone（main = 上游镜像，mobile = 上游 + 你的全部改造）
        │  git push origin mobile
        ▼
你的 GitHub fork（origin，备份 / 可选 CI）
        │
        └── git subtree pull（从 fork 的 mobile 分支）
                ▼
OwnTV_Mobile/vendor/OwnTV_Core（构建快照，includeBuild 的唯一输入）
```

---

## 2. 为什么现在要集成源码

### 2.1 当前构建方式（集成后）

Mobile 现在直接编译仓库内的 Core 源码，Maven 仓库只剩 libmpv 制品：

| 项 | 现状 | 位置 |
|---|---|---|
| 依赖坐标 | `tv.own.owntv:core:1.0.70`、`tv.own.owntv:player-core:1.0.70`（仅坐标占位，实际由 `includeBuild` 替换） | [libs.versions.toml:65](file:///E:/develop/ideaPoject/git/Own/OwnTV_Mobile/gradle/libs.versions.toml#L65)、[75–76](file:///E:/develop/ideaPoject/git/Own/OwnTV_Mobile/gradle/libs.versions.toml#L75) |
| 声明 | `implementation(libs.owntv.core)`、`implementation(libs.owntv.player.core)` | [app/build.gradle.kts:293](file:///E:/develop/ideaPoject/git/Own/OwnTV_Mobile/app/build.gradle.kts#L293)、[296](file:///E:/develop/ideaPoject/git/Own/OwnTV_Mobile/app/build.gradle.kts#L296) |
| 制品仓库 | `https://ahxn00.github.io/OwnTV_Core/maven` 只放行 `tv.own.owntv:libmpv` | [settings.gradle.kts:36–40](file:///E:/develop/ideaPoject/git/Own/OwnTV_Mobile/settings.gradle.kts#L36) |
| 源码替换 | 无条件 `includeBuild("vendor/OwnTV_Core")` | [settings.gradle.kts:49](file:///E:/develop/ideaPoject/git/Own/OwnTV_Mobile/settings.gradle.kts#L49) |
| CI | `check` + `core-check` 直接测试 vendor 源码，`build` 再打包 | [.github/workflows/android.yml:30](file:///E:/develop/ideaPoject/git/Own/OwnTV_Mobile/.github/workflows/android.yml#L30)、[81](file:///E:/develop/ideaPoject/git/Own/OwnTV_Mobile/.github/workflows/android.yml#L81) |

### 2.2 集成前的背景：分支曾被“未发布 API”卡住

集成前，Mobile 分支直接调用了当时只在本地 Core（1.0.70）里存在的 API：

```kotlin
// app/src/main/java/tv/own/owntv/mobile/ui/screens/live/LiveViewModel.kt:65,522,530
import tv.own.owntv.core.live.liveRestoreKey
combine(settings.guideFollowsLiveCategory, settings.lastLiveCategory) { ... }
```

Core 的定义：

- [LiveCategoryMemory.kt:14](file:///E:/develop/ideaPoject/git/Own/OwnTV_Core/core/src/main/java/tv/own/owntv/core/live/LiveCategoryMemory.kt#L14)
- [SettingsRepository.kt:824](file:///E:/develop/ideaPoject/git/Own/OwnTV_Core/core/src/main/java/tv/own/owntv/core/settings/SettingsRepository.kt#L824)
- `core/src/main/res/values/strings_settings.xml:1253`（`settings_guide_follows_live`）

集成前的结果：

- 本地不带 `-Powntv.corePath` → 解析到已发布的 1.0.69 → 编译失败；
- CI 不带该属性 → 必红。

源码入仓后，这个“本地能编、CI 不能编”的割裂已消失，构建输入就是 `vendor/OwnTV_Core` 里的那份代码。

### 2.3 源码体量

| 指标 | 数值 |
|---|---|
| `core/src/main` + `player-core/src/main` 文件数（kt + xml） | 674 |
| Kotlin 行数 | ≈ 84,500 |
| `core/src/main` 体积 | ≈ 16 MB |
| 资源文件 | 316（含 27 个语言目录、字体、图标） |
| Room schema | 50 个 JSON |

仓库增长约 16–20 MB，可接受；composite 模式下本来就在编这份代码。

### 2.4 需要照顾的耦合面

| 耦合面 | 现状 | 改造时的注意点 |
|---|---|---|
| Kotlin 包名 | app 引 `tv.own.owntv.core` 934 处、`tv.own.owntv.player` 75 处 | 保持 namespace 不变，避免全量改 import |
| 资源/R 类 | app 的 `R.string.*` 直接命中 core 资源；依赖 `android.nonTransitiveRClass=false`（[gradle.properties:20](file:///E:/develop/ideaPoject/git/Own/OwnTV_Mobile/gradle.properties#L20)） | 删除 core 字符串前先确认 app 无引用 |
| Manifest | core 提供网络/前台服务/闹钟/Receiver 等 | 删除功能时同步删除组件、权限与 `queries` |
| 构建期常量 | app 在 `Application.onCreate` 注入 `CoreBuildInfo`（[OwnTVMobileApp.kt:88–104](file:///E:/develop/ideaPoject/git/Own/OwnTV_Mobile/app/src/main/java/tv/own/owntv/mobile/OwnTVMobileApp.kt#L88)） | 不要把这些改成 core 自己的 `BuildConfig` |
| 数据库 | core 持有 Room DB、50 个 schema、迁移测试 | 改动 entity 必须处理迁移与旧数据兼容 |
| i18n | core 持有全部文案与 `tools/i18n`；app 另有一份 checker，只扫 `app/`（[check_hardcoded_strings.py:34–36](file:///E:/develop/ideaPoject/git/Own/OwnTV_Mobile/tools/i18n/check_hardcoded_strings.py#L34)） | 删减 Kotlin 文件后要重建 core 的 baseline |
| libmpv | 不在 Core 源码里，仍是 `tv.own.owntv:libmpv:2026.11.0` 制品 | 源码集成不覆盖原生引擎 |
| 工具链 | AGP 9.4.0 / Kotlin 2.4.20 两边一致 | 不要改 Core 的构建脚本，避免上游冲突 |
| Compose BOM | Mobile `2026.09.00`，Core `2026.08.00` | 各自解析，打包时取最高版本 |

---

## 3. 集成方式

### 3.1 目录结构

```text
OwnTV_Mobile/
├── app/
├── vendor/
│   └── OwnTV_Core/            # Core 仓快照（含 core/、player-core/、gradle/、tools/、LICENSE）
├── docs/
│   ├── Core源码集成方案.md
│   └── core-changes.md        # 你的改造清单与上游同步记录
├── settings.gradle.kts
├── build.gradle.kts
└── gradle/
```

Core 的编辑仍在 `E:\develop\ideaPoject\git\Own\OwnTV_Core` 里进行；`vendor/OwnTV_Core` 只作为构建快照，不做日常编辑。

`vendor/OwnTV_Core` 保留 Core 的完整快照（含 `.github/`、`tools/`），不要为了体积手工裁剪：手工删除的文件会在下次 `subtree pull` 变成删除冲突。构建产物（`build/`、`.gradle/`、`.kotlin/`）已被 Mobile 的 `.gitignore` 覆盖，不会进仓库。

### 3.2 fork 与本地改造分支

**为什么建议 fork**：

- 本地改造分支有远程备份，换机器或 clone 损坏时可恢复；
- 可以在 fork 上按需启用 Core 的 CI，或手动触发测试；
- `origin` / `upstream` 职责清晰：日常 push 只到自己的 fork，上游只 fetch，从机制上避免误推送；
- 改造历史、diff、后续的 PR/review 都有地方可查。

**先明确两个 remote**：

| remote | 指向 | 用途 | 是否允许 push |
|---|---|---|---|
| `origin` | 你自己的 GitHub fork | 备份 `mobile` 分支、可选 CI | 允许 |
| `upstream` | `ahXN00/OwnTV_Core` | 查看/合并上游新功能 | **禁止** |

操作步骤：

```bash
# 1) 在 GitHub 网页上 fork ahXN00/OwnTV_Core 到自己的账号

# 2) 已有的本地 clone：把原 origin 改名为 upstream，再把 origin 指向自己的 fork
cd E:/develop/ideaPoject/git/Own/OwnTV_Core
git remote rename origin upstream
git remote add origin https://github.com/ghnk211/OwnTV_Core.git
git fetch upstream
git fetch origin

# 3) 把现有改动收敛到 mobile 分支
git switch -c mobile
git add -A
git commit -m "mobile: baseline from upstream 1.0.70"
git push -u origin mobile

# 4) 本地 main 保持为上游镜像
git branch -f main upstream/main
git branch -u upstream/main main
git push origin main          # 让 fork 的 main 也成为上游镜像；也可用 GitHub 的 Sync fork

# 5) 上游只读
git remote set-url --push upstream DISABLED

# 6) 记录上游同步点
git tag -f core-upstream-base upstream/main
```

> `git branch -f main upstream/main` 只移动本地 `main` 指针，不改动 `mobile`，也不影响上游。若本地 `main` 上有只存在于 main 的提交，先确认它们已在 `mobile` 上。
>
> `core-upstream-base` 标签只在本机使用；换机器或重新 clone 后，按 `git tag -f core-upstream-base upstream/main` 重建即可。新机器从 fork clone 时，先 `git remote add upstream https://github.com/ahXN00/OwnTV_Core`，再从 `upstream/main` 建 `mobile`。

**公开性提醒**：GitHub 上 fork 公开仓库，默认也是公开的，你的改造（包括删减了哪些功能）会对外可见。如果不希望公开，不要用 fork，改为在 GitHub 建一个 **private 仓库**作为镜像，remote 布局完全相同（`origin` = private 镜像，`upstream` = 第三方）；推送和同步命令不变。

**Core CI 的触发限制**：Core 的 `build.yml` 只在 `push: main` 和 `pull_request: main` 时自动运行。`mobile` 分支的 push **不会**自动触发 Core CI。可选做法：

1. 在 fork 的 Actions 页面启用 `Core CI`（fork 默认可能禁用 workflows），然后手动 **Run workflow**，选择 `mobile` 分支（`workflow_dispatch` 已配置）；
2. 在 Mobile CI 增加一个 `core-check` job，在 `vendor/OwnTV_Core` 里跑 Core 的 `test lint`（推荐，见 6.4）；
3. 不要为了 CI 把改造推到 fork 的 `main`——那会失去“fork main 是上游镜像”的对照能力。

另外，fork 里原仓库的 **Release / Publish** 相关 workflow 不要启用；它们需要上游的 secrets 和发布权限，且与你的改造无关。

### 3.3 导入到 Mobile

从 fork 的 `mobile` 分支导入，保证快照就是已备份的那份代码：

```bash
cd E:/develop/ideaPoject/git/Own/OwnTV_Mobile
git switch -c feat/vendor-core
git status --short          # 应为空；subtree add 不接受未提交改动

git subtree add --prefix=vendor/OwnTV_Core \
  https://github.com/ghnk211/OwnTV_Core.git mobile --squash
```

之后每次 Core 有新的改造提交（并且已 `git push origin mobile`），在 Mobile 里执行一次：

```bash
git subtree pull --prefix=vendor/OwnTV_Core \
  https://github.com/ghnk211/OwnTV_Core.git mobile --squash
```

当前状态（2026-11-08）：`vendor/OwnTV_Core` = Core `mobile` @`0619549b`；Mobile 最近一次 vendor 同步提交为 `de64a34`（squash `4ffc0f3`，范围 `75ea4424..0619549b`）。

补充：

- fork 设为 private 时，首次 pull 需要本机 Git 凭据；CI 不需要访问 fork，因为 vendor 快照已提交在 Mobile 仓库里。
- 离线或想省一次网络请求时，可以把 URL 换成本地路径 `"E:/develop/ideaPoject/git/Own/OwnTV_Core"`，但执行前先确认本地 `mobile` 与 `origin/mobile` 一致：

  ```bash
  cd E:/develop/ideaPoject/git/Own/OwnTV_Core
  git fetch origin
  git log --oneline origin/mobile..mobile   # 应为空
  ```

### 3.4 Gradle 接线

**settings.gradle.kts**：把条件式 `includeBuild` 改为无条件；OwnTV Maven 仓库只留给 libmpv。

```kotlin
// 集成前（已替换）
maven {
    name = "OwnTV"
    url = uri("https://ahxn00.github.io/OwnTV_Core/maven")
    content { includeGroup("tv.own.owntv") }
}

// 现在（settings.gradle.kts:36–40）
maven {
    name = "OwnTV"
    url = uri("https://ahxn00.github.io/OwnTV_Core/maven")
    content { includeModule("tv.own.owntv", "libmpv") }
}
```

```kotlin
// 集成前（已替换）
providers.gradleProperty("owntv.corePath").orNull?.takeIf { it.isNotBlank() }?.let { includeBuild(it) }

// 现在（settings.gradle.kts:49）
// Core 源码在本仓库 vendor/OwnTV_Core；Gradle 按 group:name 自动把
// tv.own.owntv:core / :player-core 替换成该 build 的模块。
includeBuild("vendor/OwnTV_Core")
```

**app/build.gradle.kts**：依赖声明不变（仍是被替换的坐标），只更新注释。

**gradle/libs.versions.toml**：

```toml
# 仅作为坐标占位；实际由 vendor/OwnTV_Core 替换。
# 与 vendor 里的 coreVersion 对齐即可（当前 1.0.70）。
owntvCore = "1.0.70"
```

**gradle.properties**：不改。`android.nonTransitiveRClass=false` 继续保留。

**根 build.gradle.kts**：不改。

### 3.5 依赖解析

- `includeBuild` 的替换按 `group:name` 匹配，**版本号被忽略**；这是本地构建只使用改造后代码的保证。
- 把 OwnTV Maven 仓库收窄到 `libmpv` 后，即使某些配置下替换失效，也不会静默回退到已发布的 core，而是直接报错。
- AGP/Kotlin 两边已一致（9.4.0 / 2.4.20）。
- Compose BOM 各自解析，打包时取高版本（`2026.09.00`）；当前 composite 模式已经是这个组合。
- Room/KSP/SQLite 只由 Core 的构建解析，Mobile 不需要重复声明。
- 本地验证是否真的用上了项目替换：

```bash
./gradlew :app:dependencyInsight \
  --dependency tv.own.owntv:core \
  --configuration standardDebugRuntimeClasspath
```

输出应显示来自 `vendor/OwnTV_Core` 的项目替换，而不是已发布的远程制品（`1.0.69`/`1.0.70`）。

### 3.6 libmpv 边界

- 本次集成的是 **Core 的 Kotlin/资源源码**，**不包括 libmpv**。
- `libmpv` 仍由 `tv.own.owntv:libmpv` 制品提供，由 `:player-core` 以 `api` 暴露。
- 本地自编译 libmpv 时继续用 `-Powntv.libmpvLocalRepo=<folder>`，该属性会传入 composite 内的所有 build。

---

## 4. 本地改造 Core 的工作流

### 4.1 日常循环

```text
改 Core（mobile 分支）
   → Core 自身 test/lint/i18n
   → 提交
   → git push origin mobile
   → git subtree pull 到 Mobile
   → Mobile 验证矩阵
   → 记录 docs/core-changes.md
```

### 4.2 改造原则

1. **一个功能/一组文件一个提交**，提交信息用前缀区分：
   - `core-remove: drop sports module`
   - `core-mod: keep Live category on refresh`
   - `core-add: mobile-only diagnostics hook`
2. **优先整文件/整包删除**，不要把一个功能的代码分散删到几十个文件里；这样上游改到该区域时，冲突范围清晰。
3. **不改 Core 的构建脚本和版本目录**，除非删减确实需要。构建脚本是上游高频变更区，改一次冲突一次。
4. **Mobile 专属文案放 app 的 `strings_mobile.xml`**；app 的同名资源会覆盖 library 资源，不需要为了改一句话去动 core 的 27 个语言目录。
5. **能用 app 层扩展点解决的，不写进 core**：Koin 绑定覆盖、接口实现、`CoreBuildInfo`、扩展函数、资源同名覆盖。
6. **数据库 entity/迁移默认不动**；确实要删表/删字段时，先设计好旧数据兼容或升级策略。

### 4.3 删减功能的检查清单

删掉一个功能时，至少检查：

- `grep` 全仓引用：Kotlin、Manifest、XML、资源、Koin module、WorkManager/Receiver；
- 删除对应的 `<service>` / `<receiver>` / 权限 / `<queries>`；
- 删除对应的字符串资源前，确认 app 没有 `R.string.*` 引用；
- 删除/调整对应测试；
- 如果动了 Room entity：更新 schema、迁移测试、数据库版本；
- 重新生成 i18n baseline（见 4.4）；
- 在 `docs/core-changes.md` 记录“删了什么、为什么、影响了哪些文件”。

### 4.4 Core 自身的检查

Core CI 只在 `main` / PR 到 `main` 时自动运行，推 `mobile` 不会自动触发。最低要求是本地跑；另外建议在 Mobile CI 加 `core-check`（见 6.4），或在 fork 的 Actions 里手动触发：

```bash
# Core 自己的 wrapper 版本若尚未缓存，用 Mobile 的 wrapper 跑同一份构建
cd E:/develop/ideaPoject/git/Own/OwnTV_Mobile
./gradlew -p ../OwnTV_Core test lint

# 或者 Core 的 wrapper 已可用时
cd E:/develop/ideaPoject/git/Own/OwnTV_Core
./gradlew test lint

# 删减 Kotlin 文件后，baseline 会出现 stale 条目，需要收缩
cd E:/develop/ideaPoject/git/Own/OwnTV_Core
python tools/i18n/check_hardcoded_strings.py prune-safe
python tools/i18n/check_hardcoded_strings.py generate
python tools/i18n/check_hardcoded_strings.py verify --bootstrap
```

`prune-safe` 清掉已不存在的 safe 条目；`generate` 重新生成 hardcoded baseline；`verify --bootstrap` 做自洽检查。

### 4.5 改造清单

在 Mobile 仓维护 `docs/core-changes.md`，至少记录四类信息：

1. **删除的功能**：功能名、涉及文件、删除原因、替代实现（如有）；
2. **修改的行为**：文件、行为差异、原因；
3. **新增的 Mobile 专属能力**：文件、用途；
4. **上游同步记录**：上次完整合并的上游 commit/日期、吸收过的上游 commit SHA。

模板见附录 C。

### 4.6 同步到 Mobile

Core 的 `mobile` 分支每次提交并自测通过后，先推送到自己的 fork，再同步快照：

```bash
cd E:/develop/ideaPoject/git/Own/OwnTV_Core
git push origin mobile

cd E:/develop/ideaPoject/git/Own/OwnTV_Mobile
git status --short          # 应为空；subtree pull 不接受未提交改动
git subtree pull --prefix=vendor/OwnTV_Core \
  https://github.com/ghnk211/OwnTV_Core.git mobile --squash
```

然后跑第 7 节验证矩阵。`vendor/OwnTV_Core` 不做手工编辑；如果确实需要热修，见 4.7。

建议把 4.6 的命令固化成 `tools/sync-core.sh`：脚本先检查 `git status --short` 是否为空，再执行 `subtree pull`，避免忘记同步或带着未提交改动合并。

### 4.7 紧急热修的例外

如果线上问题必须立刻在 `vendor` 里改：

1. 在 Mobile 正常提交，记下 commit；
2. 生成补丁并回填到 Core 的 `mobile` 分支：

   ```bash
   cd E:/develop/ideaPoject/git/Own/OwnTV_Mobile
   git diff <base>..HEAD -- vendor/OwnTV_Core \
     | git -C ../OwnTV_Core apply -p2
   ```

   （`-p2` 去掉 `vendor/OwnTV_Core/` 前缀，落点正好是 `core/...`、`player-core/...`。）

3. 在 Core 里提交、自测、`git push origin mobile`，并在 `docs/core-changes.md` 标注“热修回填”；
4. 下次正常 `subtree pull` 前确认两边一致。

---

## 5. 按需吸收上游功能

### 5.1 查看上游更新

```bash
cd E:/develop/ideaPoject/git/Own/OwnTV_Core
git fetch upstream

# 上次完整同步之后，上游新增了哪些提交
git log --oneline core-upstream-base..upstream/main

# 只看非合并提交，便于挑功能
git log --oneline --no-merges core-upstream-base..upstream/main

# 某个关键词对应的提交
git log --oneline --all --grep="keyword"

# 看某个提交改了哪些文件
git show --stat <sha>
```

如果不想切换分支，可以给上游开一个只读 worktree：

```bash
git worktree add E:/develop/ideaPoject/git/Own/OwnTV_Core-upstream upstream/main
```

### 5.2 单功能：`cherry-pick`

大多数情况下只需要上游的某一个功能，直接摘提交：

```bash
cd E:/develop/ideaPoject/git/Own/OwnTV_Core
git switch mobile
git cherry-pick -x <sha1> <sha2> ...

# 只摘单个功能时，不移动 core-upstream-base 标签；
# 该标签始终代表“上一次完整合并的上游位置”。
```

- `-x` 会在提交信息里记录来源 SHA，方便以后追溯。
- 如果该功能是一个合并提交，先看它的子提交；优先摘子提交，而不是直接摘 merge commit。
- 摘取时遇到删减冲突，按 5.4 处理；解决冲突后跑 4.4 的检查，再提交并 `git push origin mobile`。

### 5.3 多个功能或差距很大：整分支 `merge`

当下游与上游差距过大，逐个 `cherry-pick` 的成本会超过一次合并：

```bash
git switch mobile
git merge upstream/main
```

原则：

- **不要用 `rebase` 重写 `mobile`**；`subtree` 依赖提交可达性，merge 更稳。
- 合并冲突按“保留删除”优先：上游对已删除功能的改动，通常继续删除；上游新功能依赖被删代码时，才恢复最小必要部分。
- 合并后把同步点前移，并推送到自己的 fork：

  ```bash
  git tag -f core-upstream-base upstream/main
  git push origin mobile
  ```

- 在 `docs/core-changes.md` 记录本次合并的上游 commit 与日期。

### 5.4 冲突处理

常见冲突是“上游修改了某个文件，而你已经删除了它”（modify/delete）。处理顺序：

1. 先判断上游这次改动是否只服务于你已删除的功能：
   - 是 → 保持删除；
   - 否 → 恢复文件或恢复最小必要部分。
2. 如果上游新功能依赖某个已删模块，**在 cherry-pick/merge 之前**先临时恢复该模块的删除提交，并提交这个临时状态（`cherry-pick`/`merge` 要求工作区干净）：

   ```bash
   git revert --no-commit <removal-commit>
   git commit -m "temp: restore <module> for upstream feature <name>"
   git cherry-pick -x <sha>        # 或 git merge upstream/main
   # 完成后再按需重新删除未使用的部分
   ```

   这个临时提交不要 `rebase`/`squash` 掉；在 `docs/core-changes.md` 里标注它存在的原因即可。

3. 开启 `git rerere`，让重复冲突只解一次：

   ```bash
   git config rerere.enabled true
   git config rerere.autoupdate true
   ```

4. 冲突解决后，必须重跑 Core 的 `test lint` 和 i18n 检查。

### 5.5 吸收后同步到 Mobile

先推送，再从 fork 同步快照：

```bash
cd E:/develop/ideaPoject/git/Own/OwnTV_Core
git push origin mobile

cd E:/develop/ideaPoject/git/Own/OwnTV_Mobile
git subtree pull --prefix=vendor/OwnTV_Core \
  https://github.com/ghnk211/OwnTV_Core.git mobile --squash
```

然后：

- 跑第 7 节验证矩阵；
- 更新 `docs/core-changes.md` 的上游同步记录；
- 如果吸收的功能在 Mobile 有 UI/行为入口，补充对应测试。

### 5.6 审计

```bash
cd E:/develop/ideaPoject/git/Own/OwnTV_Core

# 自上次完整同步以来的所有差异（含本地改动与已摘取的上游提交）
git log --oneline core-upstream-base..mobile
git diff --stat core-upstream-base...mobile

# 本地分支是否有未提交改动
git status --short

# 本地 mobile 是否已经推送到 fork（应为空）
git fetch origin
git log --oneline origin/mobile..mobile

# 确认 local mobile 与 vendor 快照一致（排除 Gradle 构建产物）
git worktree add --detach /tmp/owntv-core-tip mobile
cd E:/develop/ideaPoject/git/Own/OwnTV_Mobile
diff -ru --exclude=.git --exclude=build --exclude=.gradle --exclude=.kotlin \
  /tmp/owntv-core-tip vendor/OwnTV_Core
git -C ../OwnTV_Core worktree remove /tmp/owntv-core-tip
```

---

## 6. CI 与检查改造

现状（2026-11-08）：`.github/workflows/` 下只有 `android.yml` 与 `i18n.yml`；`android.yml` 含 `check`、`core-check`、`build` 三个 job，`automerge-core-bump.yml` 已删除。

### 6.1 `.github/workflows/android.yml`

| 改动 | 位置 | 原因 |
|---|---|---|
| 删除 `verify-pin` job | 41–84 行 | 不再有 core pin |
| `check` job 去掉 bump 分支条件 | 86–88 行 | 不再有 bump 分支 |
| 更新顶部注释 | 20–23 行 | 不再“只验证 pin 的制品” |
| 新增 locales 一致性检查 | `check` job | `tools/i18n/locales.json` 与 `vendor/OwnTV_Core/tools/i18n/locales.json` 必须一致 |

```yaml
- name: locales.json matches vendored core
  run: |
    diff -u tools/i18n/locales.json vendor/OwnTV_Core/tools/i18n/locales.json
```

### 6.2 `.github/workflows/i18n.yml`

- 删除所有 `bump/core-` 条件（43–44、78–79、95 行）。
- `baseline` job 保持不变（仍只 ratchet `app/`）。
- 新增 `core-baseline` job，直接跑 vendor 里 Core 自己的 checker：

```yaml
core-baseline:
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v7
    - uses: actions/setup-python@v7
      with:
        python-version: '3.12'
    - run: python3 vendor/OwnTV_Core/tools/i18n/check_hardcoded_strings.py verify --bootstrap
```

说明：该脚本的 `ROOT` 由自身路径推导为 `vendor/OwnTV_Core`，`--bootstrap` 不依赖 git 根路径，可直接扫 `core/` 与 `player-core/`。另外，`includeBuild` 编译 Core 时也会触发 Core 自己的 `preBuild → verifyI18nLiterals`，等于多一道保险。

### 6.3 `.github/workflows/automerge-core-bump.yml`

- 删除。不再有 core pin bump。
- Core 仓是第三方仓库，不应去改它的 `bump-consumers.yml`；如果它仍向本仓开 bump PR，在本仓直接关闭并忽略即可。也可以在过渡期加一个自动关闭 `bump/core-*` PR 的 workflow。

### 6.4 Core 测试的 CI 落点

Core 的 `build.yml` 只监听 `main` / PR 到 `main`，推动 `mobile` 不会自动触发。推荐在 **Mobile CI** 里加一个 `core-check` job，直接对 vendor 快照跑 Core 的测试与 lint。这样每次 Mobile PR 都会验证改造后的 Core，不依赖 fork 的 CI 配置：

```yaml
core-check:
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v7
    - uses: actions/setup-java@v6
      with:
        distribution: temurin
        java-version: '25'
    - uses: android-actions/setup-android@v4
      with:
        packages: 'platform-tools'
    - uses: gradle/actions/setup-gradle@v6
    - name: Core unit tests and lint
      run: |
        ./gradlew :OwnTV_Core:core:test :OwnTV_Core:player-core:test \
          :OwnTV_Core:core:lint :OwnTV_Core:player-core:lint --stacktrace
```

如果不想增加 CI 时长，至少保留本地检查：

```bash
cd E:/develop/ideaPoject/git/Own/OwnTV_Core
./gradlew test lint
python tools/i18n/check_hardcoded_strings.py verify --bootstrap
```

Mobile CI 负责验证 vendor 快照能编译、测试、lint 通过。

---

## 7. 验证矩阵

| # | 命令 | 期望 |
|---|---|---|
| 1 | `./gradlew :app:compileStandardDebugKotlin` | 不需要 `-Powntv.corePath` |
| 2 | `./gradlew :app:testStandardDebugUnitTest` | 现有 JVM 用例全绿 |
| 3 | `./gradlew :app:lintStandardDebug` | 0 errors，warning 数量与基线持平 |
| 4 | `./gradlew :app:assembleStandardDebug :app:assembleX86_64Debug` | 两个 ABI 均产出 APK |
| 5 | `./gradlew :app:assembleStandardRelease` | R8 通过，产物可安装 |
| 6 | `./gradlew :app:dependencyInsight --dependency tv.own.owntv:core --configuration standardDebugRuntimeClasspath` | 显示 vendor 项目替换，而非远程制品 |
| 7 | `:app:processStandardDebugMainManifest` 后检查合并清单 | `uses-permission` 数量与改造前一致；core 的 service/receiver 与删减结果一致 |
| 8 | `python tools/i18n/check_hardcoded_strings.py verify --bootstrap` | app 侧 ratchet 通过 |
| 9 | `python vendor/OwnTV_Core/tools/i18n/check_hardcoded_strings.py verify --bootstrap` | core/player-core 侧 ratchet 通过 |
| 10 | `diff tools/i18n/locales.json vendor/OwnTV_Core/tools/i18n/locales.json` | 无输出 |
| 11 | `aapt2 dump resources` 检查语言 config | release 仅 `en` / `zh-rCN` / `zh-rTW`；debug 另含伪语言 |
| 12 | 干净 clone 跑 #1–#4 | 证明构建不依赖任何本地未提交状态 |

真机回归建议覆盖：冷启动、导入源、Live 播放、VOD 播放、EPG、备份恢复、App 锁、本地同步，以及你删减过的功能周边。

---

## 8. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 改造面大，上游合并冲突多 | 吸收功能成本高 | 整文件删除、按功能成组提交、维护 `docs/core-changes.md`、开启 `git rerere` |
| 上游修改了已删功能的文件 | modify/delete 冲突 | 先判断改动是否只服务于已删功能；必要时 `git revert --no-commit <removal>` 临时恢复 |
| 删除 Room entity/字段 | 旧数据无法升级 | 默认不动数据库；必须动时先设计迁移或明确的升级策略 |
| 删除字符串/资源 | app 编译失败或运行时缺资源 | 删除前全局 `grep`；Mobile 专属文案放 app 的 `strings_mobile.xml` |
| 忘记同步 vendor | 构建用的是旧代码 | 每次 Core 提交后固定执行 `subtree pull`；发布前用 5.6 的 diff 校验 |
| 误推送到第三方仓库 | 违反约束 2 | `upstream` 的 push URL 设为 `DISABLED`；日常只 `git push origin mobile` |
| 本地 Core clone 损坏/丢失 | 改造历史丢失 | `mobile` 分支已推送到自己的 fork，可重新 clone；也可定期 `git bundle create core-mobile.bundle mobile` |
| fork 是公开的 | 改造内容对外可见 | 不希望公开时改用 private 镜像仓库，remote 布局相同 |
| Core CI 不跑 `mobile` 分支 | 改造分支没有自动测试 | 在 Mobile CI 加 `core-check`（6.4），或在 fork 的 Actions 里手动 Run workflow 选择 `mobile` |
| 修改了 Core 的构建脚本 | 每次上游同步都冲突 | 尽量不改；必须改时单独提交并在清单中标注 |
| Compose BOM 版本不同 | 首次全量编译出现 API 差异 | 切换后跑 #1–#5；必要时把 Mobile BOM 对齐到 Core |
| libmpv 被误认为“已源码集成” | 离线/无网构建失败 | 文档写明边界；CI 保留对 OwnTV Maven 的访问；本地可用 `-Powntv.libmpvLocalRepo` |
| GPLv3 合规 | 分发修改版需提供源码 | vendor 源码随仓库公开；保留 `LICENSE` 与版权头 |

---

## 9. 许可证与发布合规

- Core 为 **GPLv3**，Mobile 同为 GPLv3，源码入仓本身合规。
- 一旦修改 Core 并发布 APK，修改后的 Core 源码必须随分发可获取；`vendor/OwnTV_Core` 在仓库内即可满足。
- 保留 `vendor/OwnTV_Core/LICENSE` 与各文件版权头；不要删除上游作者信息。
- 删减功能不改变许可证义务。

---

## 10. 落地步骤与工作量

以下为初始落地计划，均已完成（2026-11-08 复核）。

| 步骤 | 内容 | 估算 |
|---|---|---|
| 1 | 在 GitHub fork Core；本地 clone 关联 `origin`=fork、`upstream`=第三方；建立 `mobile` 分支并 push | 0.5 h |
| 2 | 在 Mobile 从 fork 的 `mobile` 执行 `subtree add` | 0.5 h |
| 3 | 改 `settings.gradle.kts`、收窄 Maven 仓库、更新 catalog 注释 | 0.5 h |
| 4 | 本地跑验证矩阵 #1–#5 | 2–4 h |
| 5 | 改 CI（android/i18n/删除 automerge，可选 core-check） | 1–2 h |
| 6 | 建立 `docs/core-changes.md`、更新 README/FEATURE_MAP | 1 h |
| 7 | 干净 clone 复验 + 合并 | 1–2 h |
| **合计** | | **0.5–1 人天**（不含 Core 功能改造本身） |

Core 改造本身的工作量取决于删减范围，按功能单独估算。每次吸收上游功能，预留 1–3 小时用于冲突与回归。

---

## 附录 A：改动文件清单

| 文件 | 改动 |
|---|---|
| `vendor/OwnTV_Core/**` | 新增（subtree 导入） |
| `settings.gradle.kts:35–39` | OwnTV 仓库收窄为 `includeModule("tv.own.owntv","libmpv")` |
| `settings.gradle.kts:43–49` | 条件 `includeBuild` → `includeBuild("vendor/OwnTV_Core")` |
| `gradle/libs.versions.toml:66` | `owntvCore` 与 vendor 对齐（坐标占位） |
| `app/build.gradle.kts:290–301` | 仅更新注释（依赖声明不变） |
| `.github/workflows/android.yml:20–23,41–88` | 删除 verify-pin、bump 条件；新增 locales diff；可选新增 core-check job |
| `.github/workflows/i18n.yml:43–44,78–79,95` | 删除 bump 条件；新增 vendor checker job |
| `.github/workflows/automerge-core-bump.yml` | 删除 |
| `docs/core-changes.md` | 新增：改造清单与上游同步记录（模板见附录 C） |
| `README.md` / `extras/FEATURE_MAP.md` / `extras/SLIMMING_PLAN.md` | 更新“依赖方式/构建命令”描述 |

## 附录 B：关键事实索引

| 事实 | 证据 |
|---|---|
| 坐标占位 1.0.70 | `gradle/libs.versions.toml:65` |
| Core/vendor 版本 1.0.70 | `OwnTV_Core/build.gradle.kts:14`、`vendor/OwnTV_Core/build.gradle.kts:14` |
| includeBuild 接线 | `OwnTV_Mobile/settings.gradle.kts:49` |
| CI 直接构建 vendor 源码 | `OwnTV_Mobile/.github/workflows/android.yml:30,81,116` |
| 当前快照 | Core `mobile` @`0619549b`；Mobile `mobile-mini` @`62c5058` |
| 原“未发布 API” | 已随 vendor 快照进入 `vendor/OwnTV_Core`；`LiveViewModel.kt` 引用 `liveRestoreKey` |
| core 资源被 app 直接引用 | `gradle.properties:20` 的 `android.nonTransitiveRClass=false` |
| CoreBuildInfo 运行期注入 | `OwnTVMobileApp.kt:88–104`；`CoreBuildInfo.kt:16–78` |
| Room schema | `OwnTV_Core/core/schemas/`（50 个 JSON） |
| i18n 工具与 baseline | `OwnTV_Mobile/tools/i18n/`、`OwnTV_Core/tools/i18n/` |
| libmpv 不在 Core 源码内 | `OwnTV_Core/player-core/build.gradle.kts` 的 `api(libs.libmpv)` |

## 附录 C：`docs/core-changes.md` 模板

```markdown
# Core 改造记录

## 上游与 fork

- 上游仓库：https://github.com/ahXN00/OwnTV_Core
- 个人 fork：https://github.com/ghnk211/OwnTV_Core
- 本地改造分支：mobile（跟踪 origin/mobile）
- 上次完整同步：<upstream-commit> / <日期>
- 已吸收的上游提交：<sha>（功能名）、...

## 删除的功能

| 功能 | 涉及文件 | 删除原因 | 替代实现 | 提交 |
|---|---|---|---|---|
| Sports | core/.../sports/ | 手机端不使用 | 无 | core-remove: drop sports |

## 修改的行为

| 文件 | 行为差异 | 原因 | 提交 |
|---|---|---|---|
| core/.../LiveCategoryMemory.kt | 分类刷新时保留选中项 | 手机端底部栏会重建分类 | core-mod: keep Live category |

## 新增的 Mobile 专属能力

| 文件 | 用途 | 提交 |
|---|---|---|
| core/.../MobileDiagnostics.kt | 手机端诊断入口 | core-add: mobile diagnostics |

## 上游同步记录

| 日期 | 方式（merge/cherry-pick） | 上游提交 | 备注 |
|---|---|---|---|
| 2026-11-07 | merge | <sha> | 首次同步，无冲突 |
```
