<p align="center">
  <picture>
    <source media="(prefers-color-scheme: light)" srcset="brand/app-logos/logo_eggshell_light.png">
    <img src="brand/app-logos/logo_eggshell.png" alt="OwnTV Mobile" width="300">
  </picture>
</p>

<h1 align="center">OwnTV Mobile — Master Product Brief</h1>

<p align="center">
  <b>Native Android IPTV player for phones and tablets · dual playback engine · Stage design</b><br>
  <sub>Bring your own M3U, Xtream or Stalker/Ministra portal sources</sub>
</p>

---

## 1. Overview

OwnTV Mobile is the phone and tablet member of the OwnTV family: a native Android IPTV player written
in Kotlin with Jetpack Compose, sharing its **entire engine** with
[OwnTV for Android TV](https://github.com/ahXN00/OwnTV) through the
[OwnTV core library](https://github.com/ahXN00/OwnTV_Core).

**Two interfaces, one database, one sync, one playback stack.** Everything below the screen — the
Room schema and every migration, playlist parsing, EPG, backup, profiles, downloads, recording,
settings storage, both playback engines and **every translated string the two apps share** — is core's,
shared byte for byte with the television.

It is a **player only**: you supply an Xtream login, an M3U playlist (URL or a local file) or a
Stalker/Ministra portal.

**Key principles**

| | |
|---|---|
| **Player only** | No bundled content, no subscriptions, no paywalls |
| **Touch-first** | Gestures, a document picker, the share sheet, rotation, a keyboard that covers half the screen |
| **Not the TV app relaid out** | Touch-sized Stage parts, not a ten-foot focus ladder |
| **One engine, two shells** | A behaviour fixed here is fixed on the television, and the reverse |
| **Language-first** | Three packaged interface languages — English, 简体中文 and 繁體中文 — chosen before anything else |
| **Companion-sized** | One profile, three languages, no Sports: the engine is the television's whole engine, the scope is a phone's |
| **Open source** | GPLv3, original code, built with the help of AI |

### 1.1 What is deliberately absent

| Not here | Why |
|---|---|
| `androidx.tv.material` | Its components assume a focus-driven, ten-foot interface |
| D-pad focus engineering | Focus still matters for keyboards and accessibility, but the ten-foot focus ladder is a TV answer to a TV problem |
| `MANAGE_EXTERNAL_STORAGE` | A phone has a document picker — SAF is used instead |
| Watch Next / launcher channels | An Android TV surface; there is no leanback launcher entry here |
| Panel width adjustment | A phone shows one column at a time; the tablet two-pane split is fixed |
| Remote shortcuts | There is no remote. Every function they reached is reachable by tap or long-press |
| Sports | Scores, leagues, team and match pages, goal alerts and their alarms — no entry, no background. The television keeps them |
| Multi-profile management | One profile — the one this app opens with; older builds' rows lie dormant, not deleted. The television keeps them all |

`REQUEST_INSTALL_PACKAGES` **is** declared, for the in-app updater and nothing else — distribution is
sideloaded, so nothing else can tell a user a new version exists. If the app ever ships on Google
Play, that permission and the two update rows in Settings come out together.

**Status.** This describes a build, not a release. The single-profile, three-language, Sports-free shape
is implemented on this branch and it now compiles: the suite (`:app:testStandardDebugUnitTest`, 28 JVM
cases green), `:app:lintStandardDebug`, both debug assembles, the release assemble (unsigned, R8 plus
resource shrinking) and the merged manifest all pass, on condition that the build reads core from a local
checkout (`-Powntv.corePath=…`) — the two core APIs this shape leans on, `liveRestoreKey` and
`guideFollowsLiveCategory`, are in that checkout but not yet in the published core 1.0.69 artifact. Nineteen
of the 28 emulator scenarios are now watched end to end — the fresh-install wizard skips profile creation
and lands in the app, the bottom bar is four slots with no Sports, an app lock survives a cold start and
the right PIN opens it, a fifth wrong PIN brings the cooldown sentence, and Kids mode: turning it on works,
turning it off while a lock is set demands the PIN, and neither Cancel nor a wrong PIN lets it through; and
the backup half of the matrix — an exported file holds exactly one profile and its own sheet says so
("This backup contains the current profile only."), a three-profile file is refused on screen ("This backup
contains more than one profile. This app keeps a single profile, so it cannot be restored.") and the refused
file comes out byte-for-byte unchanged, a same-named single-profile file restores straight through with no
profile picker anywhere on it, a differently-named one asks first and Room really carries the new
name afterwards — the screen says it exactly as
("This backup’s profile is “Renamed Profile”. This device’s profile will be renamed to match before restoring.") —
and a password-sealed file is not a readable container at all but an `OWNTVBK1` AES-GCM /
PBKDF2(210,000) blob whose restore asks for the password before it will show what to restore; and the two stale-language-tag
rows, where a saved en-GB is rewritten to en-US by the next cold start and a saved de is reset to follow the
system; and the two upgrade-migration rows, where a database holding three profiles opens straight into the active one with no picker and leaves the other two rows untouched, and a stored active-profile id that points at a deleted profile still lands on Home without crashing; and the two rows closed on 2026-11-06 in round 7 — restoring a backup whose profile carries a PIN from the setup wizard locks the app on the spot (the restored PIN opens it, a wrong one is refused), and a Home config written by the old build that still lists LIVE_SPORTS draws no sports row anywhere, although the Layout page still counts that dormant row in its "8 rows" badge; and a background process killed with `am kill` comes back asking for the PIN rather than handing over an unlocked session, and writing Arabic into the device `system_locales` leaves the app English, left-to-right and crash-free both when the app pins English and when it follows the system, though the system side of that claim cannot be independently confirmed on this non-rootable image (adbd stays uid=2000, so `persist.sys.locale` and a framework restart are out of reach). Nine
more are half-watched: rotating after unlocking does not ask again (the app's own font-size control was exercised in the wizard, 100% to 140% re-lays every row out live, but "no re-prompt after a font change" was never run with a lock set), the
Downloads switch on the App page really flips (search reads `On` → `Off` → `On` again) but there is nothing
to download yet — a fresh install has no playlist, Library says "· 0 titles", so "the Download buttons
disappear" has still never been seen — and the lock holds against every entry the phone offers: a `VIEW`
deep link resolves to nothing at all (the package registers only `MAIN`/`LAUNCHER`, no scheme), an explicit
component intent with route extras is handed to the instance still sitting on the gate, and HOME then
relaunch re-prompts. Changing and removing the lock both demand the current PIN first, and both refuse a
wrong one: after the number is changed the old PIN gets "Wrong PIN — try again" at the gate while the new
one walks straight in, and once the lock is removed there is no gate at all and a protected switch such as
Kids mode stops asking. Two more halves come from the same restore walk: cancelling that rename prompt
leaves the name untouched and a wrong sealed-backup password is refused in place without writing anything,
but a restore that fails half-way has never actually been made to fail, and the anonymous / dormant-name
refusals and the case where a sealed file really carries a PIN are still unit-test evidence only. The settings search finds the new App lock row, which flips from "No lock" to "PIN
lock", and the language picker is three rows that re-localize the running app without a restart. The six
instrumented text-entry tests pass on the same emulator (`:app:connectedStandardDebugAndroidTest`, 6/0) —
after their selector was corrected, because the case as committed asked the wrapper `Column` for the
editable node's semantics and so failed six of six on this branch and on `main` alike. Everything else on
the list — every local-sync path, the upgrade migrations and the fresh-install restore of a PIN-carrying
backup, the category lock, notification and
picture-in-picture entry, the old-sports fallbacks, right-to-left layouts — is still
unevidenced. What is left splits into two kinds: rows that need a source with titles or a live playback
session — and titles here can only come from an IPTV provider, since core's `SourceType` offers
M3U/XTREAM/Stalker and no local folder, so pushing media files at the device cannot produce them — and rows
that need an older installation to upgrade from, which this phase deliberately did not build. One real defect
surfaced on the way: after an export the app names the file it *suggested*, not the one the user actually
saved, so renaming in the save dialog leaves the message saying "Saved to owntv-backup.own." while the file
on disk is `sealed.own`. That is pre-existing and outside the slimming scope, so it is recorded rather than
fixed here. Round eight closed the nameless-profile refusal for real: picking such a file is rejected on the spot with "This backup’s profile has no name, so it cannot be matched." It also settled the mid-restore rollback row — a backup whose inner `epgSources` payload is corrupt still reports "8 items restored." and keeps the announced rename, so that branch stays code-and-unit-test evidence only, and the sealed-export half of row 17 stays blocked because the `Backup password` field disappears at 140% font. Round nine took local sync off the paper: hosting shows a QR, a six-digit PIN and `http://10.0.2.15:8089/`, and every unauthenticated request to it is answered 401 with nothing but "Unauthorized. Check the PIN and try again."; pairing by typed address works end to end (`POST /sync/pair`, then `GET /sync/hello` reusing the returned secret as `X-Companion-Pin`); and a multi-profile backup fetched over the wire is refused with "Sync failed - The other device sent more than one profile. This app keeps a single profile, so it cannot be merged." leaving the profiles table untouched. Round eleven closed the last unrun scenario: after dismissing a stale sheet and reading the live PIN off the hosting page, a PC paired, downloaded this phone's sealed container (1348 B) and pushed a multi-profile backup in with POST /backup; the phone refused it with the single-profile sentence and left the profiles table untouched, while bogus credentials still drew only a 401.

---

## 2. Playback

### 2.1 Dual-engine design

The same two engines as the television, behind the same `PlaybackEngine` interface in
`:player-core`.

| Engine | Role |
|---|---|
| **libmpv (FFmpeg)** | Films, series, recordings, downloads, and any live stream ExoPlayer cannot open |
| **ExoPlayer / Media3** | Live TV by default, and every Multiview tile |

- **Engine preference per section**, globally and **per playlist** — either engine first with the
  automatic handover, or one engine only with it switched off.
- **Track memory** — the audio and subtitle language picked in the player is remembered per
  channel, film and series (per profile).
- **Per-playlist provider quirks** — catch-up time zone, "give up after" time and an HTTP Referer.
  The catch-up time zone defaults to **Automatic**: the Xtream panel's own zone, read at every sync.
- **Per-channel compatibility mode** — the **⇄** button pins a channel to mpv and remembers it,
  using the same store the television uses, so a pin syncs between the two devices.
- **The fallback ladder** — core's `LiveLadder`: up to four rungs, each tried once,
  `ExoPlayer+HLS → ExoPlayer+TS → mpv+HLS → mpv+TS` or the same led by mpv. Both engines are watched:
  `LiveExoWatchdog` for one, a 35-second open watcher for the other. **Give up after** bounds the
  whole tune, and a provider back-off does not count against it.
- **Protected (DRM) channels** — Widevine and ClearKey, from a licence server or ClearKey keys written
  inline in the playlist — play on ExoPlayer, which outranks every other preference. They
  **cannot be recorded** — the CDM decrypts only into a secure decoder for immediate display, so a
  recording is refused before it starts rather than left as a file that will not play. Apple FairPlay
  cannot play on any Android device: such a channel says so at once, and ClearKey keys a playlist files
  under a FairPlay label play as ClearKey.
- **Container is decided from evidence**, not from the file extension: the playlist's declared
  `manifest_type`, then what the response actually turns out to be, then what the same provider has
  already been caught serving. HLS, MPEG-DASH and raw MPEG-TS. The response sniff is the only route
  available to Stalker portals and Xtream panels, whose stream addresses carry no declaration.

### 2.2 The player surface

A full-screen route rather than an activity, so going full screen and coming back is a change of view
and never a restart. It asks for landscape on arrival and then lets go of the orientation again.

**Gestures — and every one has a button.**

| Gesture | Does |
|---|---|
| Tap | Controls on/off |
| Double-tap left/right | Skip by the seek step (or the live rewind step) |
| Drag sideways | Scrub, with the target shown before you let go |
| Drag vertically, outer thirds | Brightness (left) · volume (right) |
| Swipe down/up, middle third | Mini player · channel list |
| Pinch | Zoom — fit or fill |
| Press and hold | 2× speed while held |
| Two-finger tap | Mute |

Gestures are classified **once per gesture** in a single pointer loop, rather than stacked detectors
that consume each other's events. **Gesture sensitivity** scales how far a value moves, not how far
the finger must travel to be recognised, so a drag is still a drag at 50%.

The transport row carries **Previous · Rewind · Play/Pause · Forward · Next**, with the two episode
buttons present only when the engine reports an episode on that side — never on a film or a live
channel. Every tool panel is a bottom sheet capped at the landscape-aware sheet height and scrolled
inside it, because the player is always landscape and an uncapped sheet drops its last rows rather
than growing.

### 2.3 The mobility layer — what only a phone has

- **Picture-in-Picture** — leaving the app while the picture is full screen carries it into the
  system's own floating window, shaped to the picture, with skip and play/pause.
- **The app's own floating window** — draggable, pinch-resizable across three sizes, edge-snapping,
  double-tap to expand, long-press for its menu, swipe down to stop. Its position is deliberately not
  stored: a window the user drags has no setting to set.
- **Docked mini player** — the alternative, as a bar above the tabs. Sound-only and casting always
  use it, because there is no picture for a window to hold.
- **Sound only** — drops the video decoder and keeps the audio, with artwork and a sleep timer. It
  can arm itself when the screen goes off or on mobile data, and remembers the choice per channel.
- **Background playback** — a `mediaPlayback` foreground service hung on core's `PlaybackSession`
  token, so the lock screen, the notification and the media buttons are the session's own rather than
  a second copy that can disagree with it.
- **Audio focus for a phone** — a call **pauses** the film rather than playing it quietly under the
  caller, and it plays on by itself when the call ends; WhatsApp and other internet calls too (from
  Android 12). A notification sound only turns it down for a moment. Unplugging headphones stops it
  instead of switching to the loudspeaker. These differ from the television's defaults on purpose.
- **Casting (Google Cast)** — sender-side only, and absent from the television by nature: an Android
  TV box *is* a receiver. `CastPlaybackEngine` is another `PlaybackEngine`, so the lock screen and
  notification followed it for free. A stream a receiver cannot decode gets a readable, translated
  refusal rather than a silent failure.

### 2.4 Subtitles & audio

Text, image (PGS/VOBSUB/DVB) and closed captions, with size, **font**, colour, position and
background; the chosen font file is handed to the engine so mpv draws it too. OpenSubtitles search
and local files, a timing nudge, preferred audio and subtitle languages (per profile, 50 languages, plus "Original
language" from TMDB), A/V sync with a
remember-this-delay, surround handling and 150% volume boost, Night mode and Volume leveling (both
engines), a Dolby/DTS passthrough switch, Maximum video quality with a mobile-data limit and a per-item
Quality button, and experimental tunneled playback — all core's, all shared.

---

## 3. Browse

### 3.1 Shell & navigation

The **Stage** design, shared with the television: a glass capsule bottom bar on a phone — four items
now, Home · Live TV · TV Guide · Library, with Movies | Series inside — and the TV's floating rail from
**600 dp**, chosen by **window width** rather than device type — so a phone in landscape and a tablet in
split screen are treated as what they are. Sideways, Live TV draws its list and preview side by side, and
Movies and Series a glass card for the picked title beside the grid.

Each page has a large title and a crumb under it ("‹ Sky Cinema · 9 channels") that opens the
categories. Top right: the playlist button (its mark), search, cast and the avatar, which opens
**More**. Every pop-up is a Stage bottom sheet. Dark and Light, a Stage, picture or plain background,
and glass that can be switched off. Tapping or long-pressing the current tab returns its list to the
top. Predictive back is on.

### 3.2 Sections

- **Home** — a snapping full-width hero rail, **Now Trending** as either the detailed hero or a plain
  poster row, continue-watching rows, favourite movie and series rows, and favourite/recent channel rails in **Cards** or **On now**.
  Anything part-watched started from here obeys core's **Resume playback** mode — Always, Ask or
  Never — through one shared gate, so the six routes into a saved position cannot disagree.
- **Live TV** — a categories sheet from the crumb, channel rows with number, logo, now/next,
  progress, provider tags and catch-up, favourite and playlist marks. A tap plays the channel in a
  **muted preview** on top (beside the list when wide), not watched until a second tap opens it full
  screen — the television's preview. A channel on Home, in Favourites or History plays full screen;
  one from Search opens in Live TV's preview.
- **No Sports** — the television's Sports section does not exist here: none of the Scores · Calendar ·
  Leagues tabs, no team or match pages, no score line or Other channels in the player, no goal alerts
  and no live score tile. core's three sports components are struck from the merged manifest, and the
  first launch after an upgrade cancels the match reminders an older build had scheduled, so nothing
  can arrive for an entry that is gone. The section itself is untouched over there.
- **Movies & Series** — two tabs with search, sort, Grid | List and **pinch to resize** the
  grid, progress bars on part-watched films and shows, and a detail page with backdrop, chips, resume,
  favourite, download, season chips and episode progress. The page's title drops a leading provider
  tag such as "|MULTI|".
- **Guide** — **three shapes the user picks between and the app remembers**: *On now* (the portrait
  default), *Grid* (the landscape and tablet default) and *Timeline*. A phone loads one day, one row
  at a time as the row appears; the television's whole-lineup window would be thousands of rows to
  draw twelve.
- **Search** — one debounced field over channels, films and shows, grouped with counts, with recent
  terms and three curated chips. Results page as the list is scrolled rather than stopping at the
  shared reader's first forty of each kind. Once typing, filter chips (Live TV / TV Shows / Movies /
  Series, several at once) narrow the list. A **TV Shows** group lists stored-guide programmes whose
  title matches, on now or in the next 12 hours; a tap opens the channel. A channel result opens
  Live TV on that channel's own category, scrolled to it (not saved as the remembered category).
- **Category memory** — the last Live TV / Movies / Series category is remembered on the profile this
  app opens with and applied without a restart; *TV Guide follows Live TV* (Browsing & lists) shares it
  with the Guide.
- **More** (the avatar) — the current profile card, shown rather than switched, then Settings,
  Downloads, Favourites, History, Backup, Local sync, the error log and About.

### 3.3 Long-press, everywhere

Fifty-nine long-press handlers across twenty-three files — channels, films, shows, episodes,
categories, downloads, recordings, guide rows, search results, playlists, EPG sources, quick toggles,
the floating window and the navigation bar. The menus use core's own action keys and the user's saved
order and hidden actions, so both apps offer the same actions in the same arrangement; downloads
switched off leaves no Download anywhere, and a whole show or season asks first with the count.

---

## 4. EPG, catch-up & recording

The guide, catch-up, live rewind, auto-matching, guide offsets and multiple XMLTV feeds (their order
in EPG sources is the guide priority) are core's
and behave as they do on the television — including **Pause and rewind live TV** (a channel without
catch-up saved on the phone while watched, kept through picture-in-picture and sound-only playback).
Mobile-specific shapes:

- The **three guide views** above, with a day strip, a jump-to-now button and a category filter.
- A **programme sheet** whose synopsis is fetched when it opens, carrying watch, watch-from-start,
  record, record-every-showing and favourite.
- **Recording** from the guide, the channel list or the player, with the status pill appearing over
  the player in recordings-only mode so a running recording is visible during playback. A channel's
  menu also schedules one by day, start and end (no guide needed) and stops a running one.
- **Multiview** — up to four tiles, drawn through a **TextureView** rather than a SurfaceView,
  because a device has very few hardware video planes and a second SurfaceView gets audio and no
  picture. Landscape puts two per row; portrait stacks them.
- **Connection limits** — measured once at a playlist's first sync and used to refuse a tile or a
  recording with a sentence rather than a failure. Re-test's measurement outranks the panel's number;
  **Ignore connection limit** (per playlist) lets Multiview and recordings try more streams.
- **Past days in the guide** — up to a week back when there is catch-up, filled from the provider's
  archive as rows come on screen; **Go back to…** offers **Start of “…”** first, and the player's
  **Sources** sheet lists the other versions of the channel playing.

---

## 5. Storage, downloads & sync

- **Downloads and recordings to a folder the user picks**, through SAF — `MediaTarget` and
  `MediaRoot` in core. Chosen over `MANAGE_EXTERNAL_STORAGE` because a media player will not be
  granted it on Google Play. **Export** moves a finished file anywhere, asking for a *persistable*
  grant so the row still points at something playable next week.
- **Faster downloads** — core adds connections while each helps, within the playlist's limit, writing
  in order; a failure states its reason, a missing file says so, and on a one-stream playlist playback
  pauses the download and asks whether to watch or keep downloading.
- **Wi-Fi-only downloads** and a **data saver** that refuses to start a stream on mobile data.
- **Backup & restore** — core's `.own` container, optionally encrypted, through the phone's own file
  picker, choosing which parts travel. **The format and the protocol are core's, unchanged**, so a
  television backup still restores here; what changed is what the phone accepts. An export is always
  this profile and nothing else. A container holding one profile under another name is met by renaming
  this device's profile to match — announced on the sheet before anything is applied, rolled back if the
  restore fails — while a container holding several, or one whose profile has no name, is refused with
  the reason why. Setup cannot show that prompt or undo a rename, so it refuses the differing name and
  sends the user to Settings → Backup & Restore instead.
- **Local sync** — the two devices pair over the LAN (QR code scanned with the camera, or picked from
  network discovery), preview exactly what a merge will change, and apply it deliberately. Hosting
  runs only while the screen is open. Deletions propagate as deletions. What is hosted, sent or handed
  back is this profile alone, and an inbound container is held to the same test as a backup file. It is
  also offered on the **first setup screen**, where a phone with nothing on it only receives and never
  hosts, so a new device is furnished from the television without going near the menus.

---

## 6. Profile & settings

**One profile** — the one this app opens with. It carries the favourites, history, resume points, row
order, category locks and restrictions exactly as core scopes them, and the avatar in the header still
opens **More**, whose card now only says who this is. This phone is one person's device: there is no
"who's watching" gate, no profile list, nothing to add, edit, delete or switch, and no face asked for —
the tile a synced profile carries still renders, and so does a photo an older build took, but the
picker went with the editor. The television keeps full multi-profile management. Profiles left behind by
the builds that had it stay in the database, dormant and unreachable: no row is migrated, none is
deleted, and a build that shows them reads them back.

What survives of profiles is protection, not identity — and it is one secret, not several. An **app
lock**: a 4–6 digit PIN asked for when the app opens, five wrong answers bringing a 30-second cooldown,
on a full screen that *replaces* the app rather than standing over it, so no back gesture, deep link or
notification tap can show a library behind a dialog. The unlock is a session rather than a flag — it
survives a rotation or a font-scale change and does not survive a killed process. Set, change or remove
it in **Settings → App → App lock**; the same PIN answers the locked categories and the Settings PIN
gate, so taking it off loosens all three and the page says so. **Kids Mode** is one switch for the
whole app, in Settings → Content, and it wants that PIN to come off. **Allow downloads** moved to
Settings → App, under the Downloads heading, when the profile editor went; the value still belongs to the
profile.

**Settings is three levels**, across **eleven groups** now that the Profile group has gone: root →
group → leaf, with a registry that drives the routes, the breadcrumbs, the back titles and the search
index together, so a page can never be reachable by one and invisible to another. A search field over
every setting; **quick toggles the user builds by
long-pressing any switch to pin it**; sub-pickers as bottom sheets, because a sheet is the one form
that frosts the app's own wallpaper.

Every value is read and written through core's `SettingsRepository`, so a backup taken here means the
same thing on a television.

---

## 7. Surface language

The app draws **every icon itself** on the same 24×24 grid as the television's set, so the two look
like one product; nothing depends on the stock Material glyph set. Everything touchable is built from
the Stage parts (`ui/stage/`): sheets, menu items, setting rows, buttons, fields and the size bar.

**Glass** is one switch and an opacity, stored by core and shared with the television. On, the bars
and sheets frost what is behind them — a RenderEffect backdrop blur (API 31+) of the page and the
picture — under a tint at the chosen opacity, with a top highlight and a rim. The player's controls
over the video are their own black glass.

The wallpaper and its blurred copy sit **outside** the shell, so the frost every panel samples is one
image for the whole app rather than one per panel.

---

## 8. Language & accessibility

Three packaged interface languages — English, 简体中文 (`zh-CN`) and 繁體中文 (`zh-TW`) — chosen on the
first screen, or follow the phone. core still knows 45 locales and still ships their strings for the
television; the phone's whitelist is `i18n/AppLocales.kt`, mirrored by `localeFilters` in the build so a
picker can never offer a language whose resources were not packaged, and the two lists are edited
together. A persisted tag outside it is collapsed rather than trusted: every `en-*` becomes `en-US`,
anything else falls back to following the phone, which lands on the APK's English when the phone's own
language was never packaged. Debug builds keep the pseudolocales, so a stretched or mirrored layout is
still testable without either one shipping in release. The layouts stay direction-aware, though no
language here reads right-to-left.

**Wording the television shares is still added in core, never here** — a string added here would never
reach Weblate and would be English-only for ever. The one exception is wording only this app can have:
the app lock, the single-profile notices and the refusal texts live in `strings_mobile.xml` and its
`zh-rCN` and `zh-rTW` siblings — three files whose key sets a unit test holds identical, because a
phone-only string in one language is a phone-only string in none.

A consumer-side gate enforces it: a hardcoded literal fails the compile rather than the push, and its
baseline is **empty**, so there is no recorded debt for a new literal to hide behind. The exits are a
core `strings_*.xml` key, a key in the three `strings_mobile.xml` files, or a declared technical literal.

Accessibility work covers state as well as labels — a tick and an accent colour say "selected" to an
eye and to nothing else, so selection is announced.

---

## 9. Tech stack

| Area | Choice |
|---|---|
| Language | Kotlin 2.4.20 |
| Build | AGP 9.4.0 / Gradle 9.8.2, JDK 25 |
| UI | Jetpack Compose, Material 3, Compose BOM 2026.09.00, adaptive layouts |
| Engine | `tv.own.owntv:core` + `:player-core` — Room, sync, EPG, backup, libmpv and Media3 |
| Media | Media3 1.11.1 (the subtitle view only; the player itself is core's) |
| DI | Koin 4.2.2 |
| Networking / images | OkHttp 5 · Coil 3.6.2 |
| Casting | Google Cast 22.3.1 + MediaRouter |
| Camera / QR | CameraX 1.6.2 + ZXing (pairing for local sync) |
| SDK | `minSdk 26`, `targetSdk 36`, `compileSdk 37`, `applicationId tv.own.owntv.mobile` |

`applicationId` differs from the television's on purpose, so both install side by side on one device
and each keeps its own data. Both apps sign with the **same keystore** — a certificate is stable per
`applicationId`, not unique per app.

---

## 10. Architecture

### 10.1 Three repositories

| Repo | Holds |
|---|---|
| **[OwnTV_Core](https://github.com/ahXN00/OwnTV_Core)** | Database, sync, EPG, backup, profiles, downloads, recording, settings storage, both engines, the shared strings |
| **[OwnTV](https://github.com/ahXN00/OwnTV)** | The Android TV interface |
| **[OwnTV_Mobile](https://github.com/ahXN00/OwnTV_Mobile)** | This app — the phone and tablet interface |

A core change is made in core, verified against the television before it is released, and taken here
by an automatic pin-bump pull request. Nothing is worked around locally: a workaround here is a bug
the television will hit again. What the phone leaves out is therefore filtered at its own edges rather
than taken out of core — a three-qualifier `localeFilters` list answering `AppLocales`, three
`tools:node="remove"` entries against core's sports receivers and service, a Home row skipped on read —
and core's sports classes, 45-locale table and profile API stay in the dependency untouched, exactly as
the television uses them.

### 10.2 This repository's shape

```
app/src/main/java/tv/own/owntv/mobile/
├── ui/screens/      home · live · library · guide · search · downloads ·
│                    recordings · multiview · settings
├── ui/player/       the full-screen player, gestures, sheets, mini players
├── ui/stage/        the Stage parts every screen is built from — buttons, rows, fields, glass
├── ui/components/   the sheet host, icons, logos and the shared menus and pickers
├── ui/shell/        the bar/rail shell, the page header, the sync pill
├── i18n/            the three-language whitelist behind the build filter, the pickers and the stored tag
├── backup/          whose profile a backup or sync container carries, and what may be done about it
├── playback/        the foreground service, PiP, the data-saver gate (the sleep timer is core's)
├── cast/            the Google Cast sender
└── di/              Koin modules
tools/i18n/          core's locale catalogue, kept in step with it but no longer read by the build, and
                     the consumer-side string checks
extras/              the guide, this brief, the parity checklist, logos and screenshots
```

### 10.3 Two tuners, one surface

`LiveTuner` and `VodTuner` outlive every screen deliberately: Live TV's preview, the full-screen
player and the mini player are three views of **one** stream, so the tuning state cannot belong to a
view model that dies with its route. Only an explicit stop ends playback. There is one engine and one
surface, so the two tuners can never both be playing — the VOD one stops the live one before it
starts.

### 10.4 Build & release

ABI flavours (`standard` for real devices, `x86_64` for emulators), R8 with keep rules on release, locale
filtering pinned to the three shipped qualifiers as a literal in the build script beside `AppLocales`
rather than read from core's catalogue file, with the debug pseudolocales re-added per variant, a
baseline-profile module, and CI that builds, lints, runs the string gates and publishes a signed release
from a tag.

---

## 11. Legal

OwnTV is a media **player** only. It ships with no channels, playlists, subscriptions or content, and
does not endorse or facilitate access to unauthorized streams. Users are solely responsible for the
sources they add.

Released under the **GNU General Public License v3.0**.

Metadata and trailers from [TMDB](https://www.themoviedb.org/) (not endorsed or certified by TMDB).
Subtitles from [OpenSubtitles](https://www.opensubtitles.com/) (not endorsed or certified by
OpenSubtitles). Translations hosted free of charge by
[Hosted Weblate](https://hosted.weblate.org/projects/owntv/).
