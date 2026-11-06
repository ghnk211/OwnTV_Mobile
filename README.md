<p align="center">
  <picture>
    <source media="(prefers-color-scheme: light)" srcset="extras/brand/app-logos/logo_eggshell_light.png">
    <img src="extras/brand/app-logos/logo_eggshell.png" alt="OwnTV Mobile" width="360">
  </picture>
</p>

<p align="center">
  <b>Your own IPTV player for Android phones and tablets</b><br>
  <sub>Touch-first · the same engine as the television — bring your own M3U, Xtream or Stalker (MAC) sources</sub>
</p>

<p align="center">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%20phone%20%26%20tablet-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Compose" src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white">
  <img alt="Player" src="https://img.shields.io/badge/engines-libmpv%20%2B%20ExoPlayer-FB8C00">
  <img alt="License" src="https://img.shields.io/badge/license-GPLv3-blue">
  <img alt="Built with the help of AI" src="https://img.shields.io/badge/built%20with-the%20help%20of%20AI-8A2BE2">
  <a href="https://hosted.weblate.org/engage/owntv/">
    <img alt="Translation status" src="https://hosted.weblate.org/widget/owntv/svg-badge.svg">
  </a>
</p>

<p align="center">
  <a href="https://github.com/ahXN00/OwnTV_Mobile/actions/workflows/android.yml">
    <img alt="Android CI" src="https://github.com/ahXN00/OwnTV_Mobile/actions/workflows/android.yml/badge.svg">
  </a>
</p>

---

OwnTV Mobile is the phone and tablet member of the OwnTV family: a native Android IPTV **player**
built with Kotlin and Jetpack Compose, sharing its **entire engine** with
[OwnTV for Android TV](https://github.com/ahXN00/OwnTV) through the
[OwnTV core library](https://github.com/ahXN00/OwnTV_Core). Two interfaces, one database, one sync,
one playback stack — a **dual playback engine** of **libmpv (FFmpeg)** for films and series and
**ExoPlayer (Media3)** for near-instant Live TV.

It is a *player only* — you bring your own Xtream login, M3U playlist (by **URL or a local
`.m3u`/`.m3u8` file** on the device), or **Stalker/Ministra portal (Portal URL + MAC, with optional
Serial Number, Device IDs and Signature)**.

> ⚠️ OwnTV does **not** provide any channels, playlists, subscriptions, streams, or media content.
> You are responsible for adding your own legally accessible sources.

This is an **open-source** project — the code is original (not derived from any other app) and was
**built with the help of AI**.

> ### 📖 New here? **[User Guide →](extras/USER_GUIDE.md)**
> Every feature, where to find it, and the gestures — on one page.

> ### 📱 Already using OwnTV on your television?
> **Local sync** carries your playlists, favourites and watch history across your own
> Wi-Fi, with no account and no cloud — offered on the very first setup screen, so a new phone is
> furnished from the television without going near the menus. A **backup** made on the television
> restores here too, encrypted ones included, as long as it carries one profile.

---

## 💬 Community

Questions, ideas, bug reports — or just want to follow along? **Join the OwnTV Telegram group:**

### 👉 [t.me/owntvplayer](https://t.me/owntvplayer)

Scan to join from your phone:

<a href="https://t.me/owntvplayer"><img src="extras/qr-codes/telegram_qr_code.jpg" alt="Scan to join the OwnTV Telegram group" width="170"></a>

---

## ✨ Features

### 🎬 Playback

- **Two engines** — libmpv for films and series and the widest codec/HDR support, ExoPlayer for
  fast-starting Live TV, with an automatic handover between them and a **per-channel compatibility
  mode** when a stream only likes one of them.
- **Full-screen player** with the complete control bar — including **previous and next episode** —
  and **touch gestures** for skip, scrub, volume, brightness, zoom, speed and mute.
- **Picture-in-Picture**, a **draggable floating window**, and a **docked mini player** above the
  tabs — whichever you prefer, or none.
- **Sound-only mode** with artwork, volume to 150 % and a sleep timer; it can switch itself on when
  the screen goes off or you leave Wi-Fi, and it remembers your choice per channel.
- **Catch-up TV**, **rewind live TV** into your provider's archive, and **Go live** again.
- **Multiview** — several live channels at once, with sound on the one you choose.
- Hand a stream to **another player app** if you would rather.

### 🧭 Browse

- **The Stage design** — the same look as the OwnTV television app: frosted glass, a full-picture
  hero, Dark and Light, and a background of your own picture if you like.
- **Home** with your own rows in your own order, a hero card for what you were watching, and
  continue-watching rows for films and shows.
- **Live TV, Movies and Series** with categories, sorting, grid or list, and **pinch to resize** the
  posters.
- **TV guide in three shapes** — On now, Grid and Timeline — with day chips, jump-to-now, search and
  a category filter.
- **Film and show details** with the cast, the trailer, seasons, episode progress and Resume; a show
  opens on the season you last watched, and starting something part-watched asks whether to resume or
  start over — or stops asking, if you prefer.
- **Search** across channels, films and shows in one field, grouped by kind, loading more as you
  scroll.
- **Bottom bar on a phone, a floating rail on a tablet or held sideways**, with the list and the
  preview side by side where there is room.

### 📥 Sources & EPG

- **Xtream, M3U (URL or local file) and Stalker/MAG portals**, with a large import that runs in the
  background.
- **EPG** from the playlist or your own XMLTV feeds, with **auto-matching**, a review list, per-feed
  counts, catch-up timezone handling and guide statistics.
- OwnTV **measures how many streams your provider actually allows** and tells you before it has to
  refuse one.

### ⏺️ Recording & downloads

- **Record Live TV** from the guide, the channel list or while you are watching — including **every
  showing** of a programme, and a catch-up programme from the archive.
- **Downloads** with pause, resume, retry, free-space and speed, watchable offline.
- Save downloads and recordings to **a folder you pick**, SD card included, and **export** a copy
  anywhere.

### 👤 One profile, sync & settings

- **One profile per phone** — the app opens on it, and it carries your favourites, history, resume
  points, layout and restrictions. An **app lock** (a 4–6 digit PIN asked for when the app opens),
  **Kids Mode** for the whole app, locking single categories behind a PIN, and optionally Settings too.
- **Backup & restore** with a password and your own file picker, choosing which parts of your data
  travel. A backup is always the current profile; a file holding several is refused with a reason.
- **Local sync** over your own Wi-Fi — pair by QR or on the network, see exactly what will change
  before it changes, and send, receive or merge. The current profile only.
- **Settings in eleven groups** with a search box that finds any setting by name, **Quick** for the
  rows you pin, and **Customize** for renaming, hiding, reordering and regrouping everything in your
  library.
- **Three languages** — English, 简体中文 and 繁體中文 — or follow the phone's own language.

---

## 📸 Screenshots

<table>
  <tr>
    <td align="center"><img src="extras/screenshots/Home.png" alt="Home" width="240"><br><sub>Home — Now Trending</sub></td>
    <td align="center"><img src="extras/screenshots/LiveTV.png" alt="Live TV" width="240"><br><sub>Live TV</sub></td>
    <td align="center"><img src="extras/screenshots/Guide.png" alt="TV Guide" width="240"><br><sub>TV Guide</sub></td>
  </tr>
</table>

<p align="center">
  <img src="extras/screenshots/Player.png" alt="The player, full screen" width="740"><br>
  <sub>The player — live channel, full screen</sub>
</p>

---

## 🧱 Tech stack

| | |
|---|---|
| Language | Kotlin 2.4.20 |
| UI | Jetpack Compose, Material 3 |
| Build | AGP 9.4.0, `minSdk` 26, `targetSdk` 36 |
| Engine | [`tv.own.owntv:core`](https://github.com/ahXN00/OwnTV_Core) + `:player-core` — Room, sync, EPG, backup, libmpv and Media3 |
| DI | Koin |
| Networking / images | OkHttp · Coil |

**This repository contains the phone and tablet interface and nothing else.** Every entity,
migration, parser, sync, EPG, backup and playback decision lives in the core library and is shared,
byte for byte, with the television app. If you are looking for how a playlist is parsed or how the
database is versioned, it is [over there](https://github.com/ahXN00/OwnTV_Core).

The one exception is wording only this app can have — the app lock, the single-profile backup
notices: `app/src/main/res/values/strings_mobile.xml` and its `zh-rCN` / `zh-rTW` siblings, three
files with the same keys, checked by a unit test. Anything the television shows too stays in core.

### Project layout

```
app/                     the phone & tablet UI: navigation, screens, player chrome
  src/main/java/tv/own/owntv/mobile/
    ui/screens/          home · live · library · guide · search · downloads ·
                         recordings · multiview · settings
    ui/stage/            the Stage parts every screen is built from (buttons, rows, fields, glass)
    ui/components/       the sheet host, icons, logos and the shared menus and pickers
    i18n/                the three-language whitelist the build, the pickers and the stored tag read
    di/                  Koin modules
tools/i18n/              core's locale catalogue (kept in step with it) and the consumer-side string checks
extras/                  logos, credits artwork, and the TV parity checklist
```

## 📚 Docs (`extras/`)

- 📖 **[User Guide](extras/USER_GUIDE.md)** — every feature and where to find it.
- 🗺️ **[Feature map (中文)](extras/FEATURE_MAP.md)** — 逐屏功能梳理、core 边界、权限门禁与已知取舍，按当前代码核对。
- 📄 **[Master Product Brief](extras/OwnTV_Master_Product_Brief.md)** — the full as-built feature and
  architecture reference.
- ✅ **[Parity checklist](extras/PARITY_CHECKLIST.md)** — every television feature, where it landed
  here, and why the dropped ones cannot exist on a phone.

---

## 📥 Installing

Distribution is **sideloaded** while the app is pre-release: download the APK from the
[Releases page](https://github.com/ahXN00/OwnTV_Mobile/releases) and install it. Each release
carries an arm APK and an `x86_64` one for emulators.

The app checks for a new release itself — on startup if you let it, or from **Settings → App →
Check for updates** — and can download and install it for you. That needs
`REQUEST_INSTALL_PACKAGES`, which the app declares for exactly this and nothing else; Android will
still ask you to allow installs from OwnTV the first time.

---

## 🛠️ Building & running

```bash
git clone https://github.com/ahXN00/OwnTV_Mobile.git
cd OwnTV_Mobile
./gradlew :app:assembleStandardDebug
```

`local.properties` is developer-local and never committed:

```properties
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

**Resolving the core library.** By default the build takes the pinned `owntvCore` version from
OwnTV's public Maven repository (https://ahxn00.github.io/OwnTV_Core/maven) — no account or token
needed. That is what CI does, fork pull requests included. To build against a local checkout of core
instead — a composite build, so an edit there reaches the app with no publish step — add this to the
same file, **never to a file in this repository**:

```properties
owntv.corePath=/path/to/OwnTV_Core
```

On `mobile-mini` that local checkout is not optional yet: this HEAD calls `liveRestoreKey` and
`guideFollowsLiveCategory`, which exist in core's development branch but not in the published `1.0.69`
artifact, so a build with the default pin stops at those two names until core publishes them.

Signing and keystore values belong in `~/.gradle/gradle.properties` or CI secrets, never in a
repository file. With nothing configured the APK simply builds unsigned.

### Checks

```bash
./gradlew :app:testStandardDebugUnitTest      # unit tests
./gradlew :app:lintStandardDebug              # Android lint
python tools/i18n/check_hardcoded_strings.py verify
python tools/i18n/check_pseudo_locales.py     # needs a built APK
```

---

## 🤝 Contributing

Contributions, bug reports and ideas are welcome — open an issue or a pull request. Please keep the
project's player-only, bring-your-own-source positioning, and match the existing code style.

**Where does a change go?** If it is about the database, playlist sync, EPG, backup, profiles,
downloads, settings storage, the playback engine **or any user-visible text the television shares**,
it belongs in [OwnTV_Core](https://github.com/ahXN00/OwnTV_Core), not here. This repository holds the
phone and tablet interface only. Phone-only wording is the single exception, and it has to be added in
all three shipped languages at once.

<!-- i18n-contribution:start -->
## Help translate OwnTV

If your language is already available, contribute interface translations across OwnTV's six Android resource components on [Hosted Weblate](https://hosted.weblate.org/projects/owntv/). If it is not listed, [open a language request ticket](https://github.com/ahXN00/OwnTV/issues/new?template=feature_request.yml&title=%5BLanguage%5D%20Add%20) first. A maintainer will review the request, register the locale, and prepare its base translation files on Hosted Weblate. Once the language appears on Hosted Weblate, you can start translating it there. The strings themselves live in [OwnTV's core library repository](https://github.com/ahXN00/OwnTV_Core), together with the language contributor guide covering identifiers, validation, and promotion policy.
<!-- i18n-contribution:end -->

## 🙏 Credits

<img src="extras/partner-logos/Weblate_logo.svg" alt="Weblate" width="200">

OwnTV speaks 26 languages because people translate it on
[**Weblate**](https://weblate.org/), which hosts the project free of charge for libre software.
Thank you to Weblate and to every translator who has given the app their language. This phone build
ships three of them — English, 简体中文 and 繁體中文 — so the translations it carries are those three;
the rest stay in core for the television.

<img src="extras/partner-logos/tmdb_logo.svg" alt="TMDB" width="200">

Movie & series metadata and trailers are provided by [TMDB](https://www.themoviedb.org/).
**This product uses the TMDB API but is not endorsed or certified by TMDB.**

<img src="extras/partner-logos/opensubtitles_logo.svg" alt="OpenSubtitles" width="200">

Subtitle search & download is powered by [OpenSubtitles.com](https://www.opensubtitles.com/).
**This product uses the OpenSubtitles API but is not endorsed or certified by OpenSubtitles.** You
sign in with your own OpenSubtitles account and are subject to their terms and download quotas.

### ▶️ Playback engines

- **[mpv](https://mpv.io/)** (via [libmpv](https://github.com/mpv-android/mpv-android), built on
  [FFmpeg](https://ffmpeg.org/)) — the default engine, for the widest IPTV/codec, audio and HDR
  support.
- **[Media3 / ExoPlayer](https://github.com/androidx/media)** — the fast-start engine for Live TV
  and HLS.

Which of the two starts a stream is yours to set, separately for Live TV and for Movies & Series
(Settings → Player): either order, or one engine only with the automatic handover turned off.

### 🧩 Built with

[Jetpack Compose](https://developer.android.com/jetpack/compose) ·
[Material 3](https://m3.material.io/) ·
[Room](https://developer.android.com/jetpack/androidx/releases/room) ·
[Koin](https://insert-koin.io/) ·
[OkHttp](https://square.github.io/okhttp/) ·
[Coil](https://coil-kt.github.io/coil/) ·
[ZXing](https://github.com/zxing/zxing) — and the wider Kotlin / AndroidX open-source ecosystem.
Thank you to all their maintainers. See each project for its own license.

### 🎨 Brand

The lowercase **owntv** wordmark was designed for OwnTV by [@m3th0d93](https://github.com/m3th0d93)
in [issue #227](https://github.com/ahXN00/OwnTV/issues/227).

## ⚖️ Legal

OwnTV is a media **player** only. It ships with no channels, playlists, subscriptions or content, and
does not endorse or facilitate access to unauthorized streams. Users are solely responsible for the
sources they add and for complying with the laws and rights that apply to them.

## 📄 License

Released under the **GNU General Public License v3.0 (GPLv3)**, the same as the rest of OwnTV.

In short: you're free to use, study, modify and redistribute OwnTV, including commercially — but any
redistributed version (including forks and commercial products built on it) must also be licensed
under GPLv3 and its source made available.

---

<sub>OwnTV is an open-source, player-only project, built with the help of AI.</sub>
