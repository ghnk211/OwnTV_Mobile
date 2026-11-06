# Feature parity with the television — final pass

**Status: complete. Every row below was checked against the code in this repository on 2026-09-14,
not against a plan or a changelog. The rows the 2026-11-06 slimming touched were read again against
the code on 2026-11-06, and each of those carries that date.**

Every feature of [OwnTV for Android TV](https://github.com/ahXN00/OwnTV), with its status on the
phone: **Port**, **Redesign**, **New**, **Dropped** or **Television-only** — every row accounted for,
every drop and every omission justified in writing.

Two rows took a second look before they settled. Both are explained below, under
[Two decisions worth recording](#two-decisions-worth-recording).

What **Television-only** means here, and why the phone's sports module and its multi-profile
management moved there on 2026-11-06, is under
[Television-only, on purpose](#television-only-on-purpose).

The ✅ column has always meant *wired up in this repository*. It has never meant *proven by a test run*.
For the 2026-11-06 rows it now means more than wiring read by eye: the app compiles, lints clean, both
debug APKs and the unsigned release APK assemble, 28 JVM unit tests pass and the 6 instrumented
text-entry tests pass on an emulator — on condition that the build reads core from a local checkout
(`-Powntv.corePath=…`), because `liveRestoreKey` and `guideFollowsLiveCategory` live there and not yet in
the published core 1.0.69 artifact. Those instrumented cases only went green after their selector was
corrected: as committed they queried the wrapper `Column` for the editable node's semantics, so they
failed six of six here and on `main` alike. Of the 28 manual scenarios, nineteen are now fully observed on an Round nine drove local sync against a fake companion server on the PC over `adb forward`: hosting publishes a QR, a six-digit PIN and `http://10.0.2.15:8089/`, every unauthenticated `/sync/hello`, `/sync/pair` or `/backup.own` answers 401 with no data, pairing by typed address works end to end (`POST /sync/pair`, then `GET /sync/hello` carrying the returned secret in `X-Companion-Pin`), and a multi-profile payload fetched by `GET /backup.own` is refused with "Sync failed - The other device sent more than one profile. This app keeps a single profile, so it cannot be merged." while the profiles table stays untouched. Round eleven ran that direction too: with the stale sheet dismissed and the live hosting PIN read off the screen, a PC paired for a secret, downloaded the phone's sealed container (1348 B) and pushed a multi-profile backup in with POST /backup, which the phone refused while leaving profiles untouched; bogus credentials still only get a 401. The eighth round added the nameless-profile refusal at runtime (the file is rejected the moment it is picked, message quoted in the plan) and showed that a corrupt inner `epgSources` payload restores silently, so the mid-restore rollback path cannot be provoked without an old build.
emulator (fresh install through the wizard, restoring a backup whose profile carries a PIN, which locks the app the moment the restore finishes and accepts only the PIN inside the file, and an old Home config that still lists LIVE_SPORTS drawing no sports row, the four-slot bar without Sports, app lock across a cold start,
the cooldown after a fifth wrong PIN, Kids mode on and off with the PIN asked before it will switch off, and
the backup set: an exported file carrying exactly one profile with the sheet saying so in those words, a
three-profile file refused on screen while the file itself stays byte-for-byte identical, a same-named
single-profile file restoring straight through with no profile picker, a differently-named one asking before
it renames and the database really holding the new name afterwards, and a password-sealed file that is an
`OWNTVBK1` AES-GCM/PBKDF2 blob demanding the password before it will show what to restore), and the two stale-language-tag rows (a saved en-GB is rewritten to en-US by the next cold start; a saved de falls back to follow the system), and the two upgrade-migration rows (a three-profile database opens into the active profile with no picker and the other rows survive; a dangling active-profile id still lands on Home)
and nine only in part (no re-prompt after rotation, the Downloads switch flipping On → Off → On, the lock
holding against a deep link / explicit intent / warm relaunch, the PIN guarding more than the gate —
changing and removing the lock both ask the current PIN, both refuse a wrong one, and after removal neither
the gate nor the protected-switch prompt comes back —, cancelling the rename prompt and a wrong sealed
password changing nothing, and the multi-profile refusal itself; the anonymous / dormant-name refusals and a
restore that fails half-way are still decision-table evidence only; and the old sports residue, where a hand-seeded legacy alert file is left byte-for-byte alone while the package keeps zero alarms, zero notifications and a score-card service the OS reports as Not found — the one step never witnessed is cancelling an alarm that really existed; a background process killed with `am kill` re-asks the PIN on relaunch (no crash, no ANR), leaving only genuine low-memory pressure untested; and RTL, where writing Arabic into `system_locales` leaves the app English, LTR and crash-free whether the app pins English or follows the system — the system side is not confirmed on this non-rootable image). Every other ✅ below still says "built and statically checked",
never "observed on a phone" — above all the local sync, category-lock and migration rows, and the visible
half of the Downloads row, which needs a source with titles before any Download button exists to hide.

---

## Direct port — same behaviour, new layout

| Feature | Done | Where |
|---|---|---|
| Xtream / M3U / Stalker sources | ✅ | Setup flow and Settings → Playlists; all parsing is core's |
| Multiple playlists | ✅ | Playlists page; the provider name shows on rows, which matters more here than on a television |
| Catch-up & live rewind | ✅ | The same `LiveLadder` as the television; a drag replaces the D-pad |
| Pause and rewind live TV (saved copy) | ✅ | Core's `TimeshiftManager`; rewind/forward buttons, bar, Go live, resume popup; PiP and sound-only keep saving |
| Series detail, seasons, episodes | ✅ | Opens on the last-watched episode. Since 2026-10-12 Specials sit after the last season, as on the television, and an unstarted show plays from S1 E1 (core's `EpisodeOrder`) |
| Autoplay next episode / season | ✅ | Core logic, untouched. **Previous / Next** buttons joined the transport row on 2026-09-17 — until then the next episode was reachable only from the card in the last thirty seconds |
| Resume / watch history | ✅ | Core. The **Resume playback** setting (Always / Ask / Never) was displayed but never read until 2026-09-17, so the phone always resumed silently; all three modes now behave as the television's, including its ten-second threshold |
| Favourites | ✅ | Core, plus a Favourites screen under More |
| Home rows, order, hidden set | ✅ | Eight rows since 2026-11-06. core's `HomeRow` has nine: `LIVE_SPORTS` is filtered out when the saved config is read, and the stored value itself is left alone so a downgrade still reads the row back |
| Trending (TMDB) | ✅ | Core, with a detailed-card or posters-only choice |
| Search | ✅ | Grouped results with a count per kind. Scrolling to the end loads the next page — the television still stops at the first 40 of each kind |
| Downloads | ✅ | With SAF for the folder — no all-files permission. The **Allow downloads** switch moved onto the Settings → App page under its own Downloads heading on 2026-11-06 (not a separate page); it is still stored per profile, it just no longer sits beside the profile it belongs to |
| Customize categories & items | ✅ | Including bulk rename, spans and the PIN lock |
| Category locks and the Settings PIN | ✅ | Core's lock logic (`core.categorylock`), the TV's rules: lock from a category's hold menu, Enter PIN, relock on leaving the app, the floating window included; Settings, Backup & Restore and Local sync behind the PIN when asked. Since 2026-11-06 "switching profile" is no longer one of the relock triggers, because there is no switching — and the lock answers to the same `pinHash` the app lock and the Settings gate do |
| Profile, PIN, restrictions | ✅ | 2026-11-06: one profile per phone, so its PIN and its restrictions are the only ones there are. The PIN is the **app lock** — 4–6 digits, five wrong tries and the button waits thirty seconds, a screen that replaces the app rather than sitting over it, edited from Settings → App → App lock. Kids Mode stayed one app-wide switch, in Settings → Content. Naming a profile, choosing its picture, adding one or switching between them is [Television-only, on purpose](#television-only-on-purpose) |
| Backup / restore | ✅ | Including the television→phone migration path, encrypted backups included. Since 2026-11-06 an export carries the current profile only — the container format is core's and unchanged, so the television still opens what the phone writes. A file holding several profiles, an unnamed one, or a name belonging to a profile this device keeps dormant is refused with the reason; a single profile under another name is matched by renaming this device's, which the sheet says before you confirm and undoes if the restore fails. Setup can do neither of those, so it points at Settings → Backup & Restore instead |
| Theme, accent, Glass & background | ✅ | The same stored values; a colour picker was added for all three colour settings |
| App icon (eight colours) | ✅ | The same stored choice and the same restart question; the phone has no banner or TV home channel |
| Locales | ✅ | Three packaged since 2026-11-06 — English, Simplified Chinese, Traditional Chinese — under one *System default* row that follows the phone. core's own catalogue still lists 45 locales, 27 of them packaged, and this app leaves it alone: what reaches the APK is the whitelist, `AppLocales` mirrored by `localeFilters` in `app/build.gradle.kts`, and the two lists are edited together. A stored `en-*` collapses to `en-US`, and any tag the APK has no strings for falls back to following the system, quietly, on the next launch. Debug builds keep the `en-XA` / `ar-XB` pseudolocales |
| Player: engine ladder, mpv/Exo | ✅ | `:player-core`, untouched, including the per-channel compatibility pin. The phone ran **half** the ladder until 2026-09-14 — one engine was watched and the other was a terminus. It now walks core's own `LiveLadder`: four engine/format rungs, each at most once, with the whole-tune budget behind Settings → "Give up after" |
| External player handoff | ✅ | Richer here than on the television, because a phone has more players installed |
| Weather | ✅ | Core, in °C or °F |
| **In-app update check** | ✅ | **Settings → App.** See [Two decisions worth recording](#two-decisions-worth-recording) |

## Redesigned — same function, different shape

| Feature | Done | Shape it took |
|---|---|---|
| Bottom bar | ✅ | 2026-10-17 as five, **four since 2026-11-06**: Home · Live TV · TV Guide · Library. Movies and Series still share one **Library** tab (Movies \| Series switch), which is what freed the fifth place for Sports when Sports was here; the tablet rail keeps them apart |
| Live TV browse | ✅ | Three panels became a crumb + categories sheet, the list, and a preview on top (beside the list when wide) |
| EPG / Guide | ✅ | The grid was kept, and *On now* added as the portrait default; Timeline is a third shape |
| Movies / Series browse | ✅ | Two tabs, as the television's rail; a glass title card beside the grid when wide |
| Long-press menus | ✅ | Bottom sheets, with the same actions in the same saved order |
| Settings — 11 groups | ✅ | The television's twelve groups as cards in two columns; search by setting name. Eleven here since 2026-11-06: the Profile group went out with the profile manager, and the sports pages that hung off App went with the module |
| Player: subtitles, zoom, speed, volume boost | ✅ | The same functions on a gesture *and* a button surface — no gesture-only function. The panels are sheets rather than centred dialogs, and **they scroll**: until 2026-09-17 a sheet taller than a sideways phone silently dropped its last rows, which hid A/V sync, subtitle timing and subtitle search. The audio button is also always on the bar, as the television's is, so A/V sync is reachable on a single-soundtrack film |
| Mini player / audio bar | ✅ | Three choices, not one: a floating window, a docked bar, or off |

## New — no television equivalent

| Feature | Done | Where it lives |
|---|---|---|
| System Picture-in-Picture | ✅ | `PipController`, with a window size and edge snapping of the user's own |
| Audio focus (calls, other apps) | ✅ | `PlaybackSession` in `:player-core` — shared, so the television gained it too |
| Headphone / Bluetooth routing | ✅ | `ACTION_AUDIO_BECOMING_NOISY` in the same file |
| Background playback + media notification | ✅ | `MediaSession`, with lock-screen controls and ±10 s |
| Screen-off behaviour | ✅ | Audio continues, the video surface is released; remembered per channel. **It was true for films and downloads only until 2026-09-14** — live channels play on the other engine, and this asked the first one, which is stopped there. Every lifecycle rule now asks whichever engine holds the stream |
| Mobile data vs Wi-Fi | ✅ | Data-saver behaviour and download-on-Wi-Fi-only |
| Portrait layouts | ✅ | Every screen; tablets and landscape get the two-pane variants |
| Predictive back gesture | ✅ | `android:enableOnBackInvokedCallback="true"` |
| SAF storage picker | ✅ | `MediaTarget` / `MediaRoot` in core; no `MANAGE_EXTERNAL_STORAGE` anywhere |

## Dropped — and why

Each of these is a television feature with nothing on a phone for it to be. None was dropped for
being difficult, and none is deferred: the ship-complete policy applies here too.

| Feature | Why it cannot exist here |
|---|---|
| **Panel width adjustment** | It sets the relative width of side-by-side panels. A phone shows one column at a time, so there is nothing to apportion. The tablet two-pane layouts use a fixed split, which is the right answer for a screen that is sometimes a phone. |
| **Remote shortcuts** | Colour keys, channel-number entry and the D-pad ladders exist because a remote control has those buttons. There is no remote. Every function they reached is reachable by tap or long-press — that was checked feature by feature, not assumed. |
| **Watch Next / launcher channels** | An Android TV launcher feature. This app has no `LEANBACK_LAUNCHER` entry, so a row it published would be a row nothing could open. `CoreBuildInfo.tvHome = false` turns it off in core rather than leaving the publish to fail quietly, and the two TV-provider permissions are explicitly removed from the merged manifest. |
| **Auto frame-rate switching** | **Not dropped after all** — see below. |

## Television-only, on purpose

These are television features a phone *could* have. None of them is a gap being tracked: each went out
on 2026-11-06 because this app was carrying work neither the device nor its users asked for, and each
went without touching core, the database schema, the backup format or the sync protocol. The decisions
are D2, D8 and D9 in `extras/SLIMMING_PLAN.md`.

| Feature | Why its absence here is the decision, not a shortfall |
|---|---|
| **Sports** — Scores · Calendar · Leagues, team pages, the match page, Find channels, filters, Broadcasts, league order, the Home row, the player's score line | The module left the phone completely: no tab, no screen, no route, no settings page, no match deep link, no score line, no goal alert, no notification. core's `MatchAlertReceiver`, `MatchAlertBootReceiver` and `LiveMatchCardService` are struck from the merged manifest with `tools:node="remove"`, and every launch runs `MatchAlertScheduler.rearmAll()` with `MatchAlertHost.backgroundAllowed` left at core's own `false` default — which cancels the alarms an upgraded device still holds — plus `stopService` for a score card still sitting in the shade. What is left of sports is in the dependency, not in this app: core's classes and strings stay, accepted rather than worked around, because the acceptance bar is app-only (D8). The saved `LIVE_SPORTS` row and the saved sports config are filtered when read, never deleted, so a downgrade reads them back |
| **Several profiles, and switching between them** — the "Who is watching?" chooser, add / edit / delete, the avatar strip, a name and a picture of your own | One profile per phone. The chooser became a single-profile unlock screen, the profile editor is gone, `ProfilesViewModel` keeps only what the gate needs, and the Profile settings group went with it; More shows the current profile as a card with nothing to tap. Extra profile rows stay in the database, dormant and unreachable — not one row is deleted, so an older build shows them again (D2). Naming a profile and choosing its picture are television-only choices now: an upgraded phone keeps the name and picture its profile already had, a fresh install takes core's default name and no picture |
| **A backup or a sync container holding more than one profile** | The television writes them. This app exports, hosts, sends and merges back the current profile only, and refuses a file with several in it *with the reason* rather than pouring profiles into rows no screen can reach. The container and the protocol are core's and untouched, so a single-profile file still crosses either way, in both directions (D9) |
| **The languages beyond the three** | core's catalogue still lists 45 locales and their strings stay in the dependency; this app packages three: English, Simplified Chinese, Traditional Chinese. That is the app's choice, not a resource gap — `AppLocales`, `localeFilters` and the pickers are three layers of one whitelist. The coverage chips and the *Help translate* wall left with the languages they described, because a percentage for a language nobody can choose only misleads. The cost is stated plainly in the changelog: a stored language outside the three follows the phone, and `en-GB` becomes `en-US` |

One thing this left genuinely unfinished, and it belongs here rather than in a changelog footnote: the
app lock has no *forgotten PIN* path. Clearing the app's data is the only way back in, which is why
the App lock page warns you before you set one, and why one PIN rather than three opens the app, the
locked categories and the Settings gate — one secret to remember, and one secret whose loss costs all
three.

---

## Two decisions worth recording

**1. The in-app update check was nearly dropped, and should not have been.**

Its parity status was always **Port**, with the note *"kept while sideloaded; removed if Play ships"*.
It was nearly written out anyway: `SettingsAppPage` carried a comment saying there would
never be an update check here, the manifest listed `REQUEST_INSTALL_PACKAGES` among permissions this
app would never declare, and `CHANGELOG_APP.md` said the app had no update sheet. The reasoning was
Google Play — the same reasoning that correctly chose SAF over `MANAGE_EXTERNAL_STORAGE`.

It was restored. Distribution is sideloaded, so nothing
else was ever going to tell anyone a new version existed. The permission is declared for the updater
and nothing else, and the manifest says so. **The original condition still stands: if this app
ever ships on Play, the permission and the two Settings rows come out together.**

Worth keeping as a lesson: three files had been made to agree with a decision, which made it look
far more settled than it was. Only the parity list disagreed, and it was right.

**2. Auto frame-rate switching was dropped, then built.**

It was first dropped on the grounds that phone panels do not switch refresh rate for content. Some
now do. It exists as `autoFrameRate` on the Playback settings page, **off by default**, and it warns
before turning on where the display cannot be asked — which is the honest shape for a setting whose
hardware support cannot be detected reliably. Since 2026-09-23 it acts: full screen only, seamless
switches only — through the same core surface hint the television uses.

---

## One gap, deliberately left open

The television has a **Check for update** *quick toggle* — an action tile that can be pinned to the
top of Settings. This app's quick toggles are switches only (`QuickToggle` carries a `Flow<Boolean>`
and a setter), so an action tile would mean a second kind of quick toggle for a single use.

**Check updates on startup** is a switch and could be made pinnable if anyone asks for it. Nobody
has. The check itself is one tap away on the App page and is findable through the Settings search
box, which is where the television's tile leads anyway.

## Signals core 1.0.49 added, supplied here too

Core gained two signals a host app raises from its own screens. Both are supplied.

| Signal | Where | What it does |
|---|---|---|
| `WatchSession` | `PlayerScreen` | Opens while a channel, film or episode from a playlist is playing, so core's background Stalker catalogue drain steps aside. A downloaded file carries no `sourceId` and holds no session — it spends no provider connection |
| `CatalogPriority` | `LibraryViewModel.select` | Names the category the user just opened, so the drain fills that one next instead of following the provider's order |

Neither changes whether the catalogue ends up correct, only when. On a provider that allows one
stream at a time, the first is what stops a background download competing with the picture.
