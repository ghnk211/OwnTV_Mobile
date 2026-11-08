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
