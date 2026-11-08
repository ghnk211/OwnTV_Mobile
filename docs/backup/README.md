# 播放页改造备份（2026-11-08）

需求「播放页继续改造：去除下方所有功能组 / 去除屏幕中间按钮组」之前的播放页原文件，逐字节复制。

| 文件 | 对应源码 |
|---|---|
| `PlayerScreen.kt.bak` | `app/src/main/java/tv/own/owntv/mobile/ui/player/PlayerScreen.kt` |
| `PlayerControls.kt.bak` | `app/src/main/java/tv/own/owntv/mobile/ui/player/PlayerControls.kt` |
| `PlayerSheets.kt.bak` | `app/src/main/java/tv/own/owntv/mobile/ui/player/PlayerSheets.kt` |

`PlayerControls.kt.bak` 里保留了被移除的 `TransportRow`（中间播放/上一台/下一台）、`SeekBar`、
`LiveBar`、`ToolBar`（下方功能组）以及 `RoundControl` 等实现。要恢复某一组，把对应 Composable
连同其调用处一起复制回 `PlayerControls.kt` 即可；文件顶部的 `PlayerControls(...)` 签名未改动，
调用参数仍在，因此不需要改 `PlayerScreen.kt`。

备份不参与编译：`.bak` 不是 Kotlin 源集的后缀，`app/src/main/java/**/*.kt` 的扫描也不会读到它。

---

# TVbox 点播迁移备份（2026-11-08）

需求「按 docs/TVbox点播迁移方案.md 实施_TVbox 点播功能迁入 OwnTV_Mobile」改动前的原文件，
逐字节复制（方案 §8 清单）。恢复方法：把 `.bak` 复制回对应源码路径（去掉 `.bak` 后缀）。

| 文件 | 对应源码 |
|---|---|
| `VodScreen.kt.bak` | `app/src/main/java/tv/own/owntv/mobile/ui/screens/vod/VodScreen.kt`（占位页原状） |
| `MobileNavHost.kt.bak` | `app/src/main/java/tv/own/owntv/mobile/ui/nav/MobileNavHost.kt`（`VOD -> VodScreen()` 原行） |
| `VodTuner.kt.bak` | `app/src/main/java/tv/own/owntv/mobile/ui/screens/library/VodTuner.kt`（无 playTvbox/TVBOX_ITEM_ID 原状） |
| `OwnTVMobileApp.kt.bak` | `app/src/main/java/tv/own/owntv/mobile/OwnTVMobileApp.kt`（modules 未加 tvboxModule） |
| `app.build.gradle.kts.bak` | `app/build.gradle.kts`（**含未提交的本机签名/test keystore 改动**，方案 §8 特别要求保留工作区版本） |
| `libs.versions.toml.bak` | `gradle/libs.versions.toml` |
| `proguard-rules.pro.bak` | `app/proguard-rules.pro` |
| `strings_mobile.xml.bak` | `app/src/main/res/values/strings_mobile.xml` |
| `strings_mobile.xml.zh-rCN.bak` | `app/src/main/res/values-zh-rCN/strings_mobile.xml` |
| `strings_mobile.xml.zh-rTW.bak` | `app/src/main/res/values-zh-rTW/strings_mobile.xml` |

注意：
- 本迁移**未修改** `app/src/main/AndroidManifest.xml` 与 `SettingsScreen.kt`，故无对应备份。
- `app.build.gradle.kts` 当前工作区版本除迁移新增依赖外仍含本机签名路径（`keyStroe`）与独立
  test keystore；恢复备份会一并去掉 TVbox 依赖，仅用于整体回退迁移时使用。
- 回归测试报告见 `docs/TVbox回归测试报告-模拟器.md`。
