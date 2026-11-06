# OwnTV Mobile — User Guide

Everything the phone and tablet app can do, **where to find it**, and the gestures that make it fast.

Each entry is one line about what it does and one line about where it lives. Skim the headings and
stop where something looks useful.

> **The basics:** tap to open · **long-press** almost anything for its menu · swipe back to go up.
> Long-press is where favourites, rename, hide, record, catch-up and download live.
>
> **No gesture is the only way to do something.** Every gesture in the player also has a button.

---

## 🚀 Start here

A fresh install walks you through the same steps as the television:

```
Welcome  →  Display  →  Disclaimer  →  Set up OwnTV  →  Add a playlist · Restore a backup · From another device  →  All set
```

1. **Welcome** — the language picker is on this screen: **English**, **简体中文**, **繁体中文**, or
   **System default** (the phone's own language). Tap **Get started**.
2. **Display** — interface zoom and font size, with a sample that resizes as you adjust, the
   **App icon**, and whether Live TV shows a preview.
3. **Disclaimer** — OwnTV is a player; you bring the sources.
4. **Set up OwnTV** — **Start fresh**, **Restore a backup**, or **From another device** (copy
   everything off a television you already have — see below). There is no profile to make: the app
   keeps the one profile it opens with, and whichever of these lands first is the one it is made for.
5. **Start fresh** → **Add a playlist** — **Remote** (type it on a computer or another device's browser), **Type it
   here** (Xtream, M3U or Stalker), or **Import** a backup file. **Skip for now** is a valid answer,
   and you can add a playlist later in Settings → Sources & guide.
6. **Import** runs, counting channels, films and series as they arrive.
7. **All set** — **Add a TV guide** goes straight to
   [Settings → Sources & guide → EPG sources](#-add-guide-data); the guide is never added for you.

### 📺 Coming from the television?
**Where:** step 4 → **From another device**, or **Restore a backup**
No file needed for the first one: on the television open **More → Local sync** and turn **Sync
mode** on, then pick **From another device** here. The phone finds it on the network or scans its
code, you type the 6-digit PIN once, tick what to bring across, and see exactly what will change
before it changes. A phone being set up only ever *receives* — nothing on the television is altered.
A backup made on the TV also restores here, encrypted ones included — and **Restore a backup** asks
what to bring back before it applies anything, so you can take the playlists and leave the old
device's settings behind. One rule covers both routes: this phone keeps a single profile, so a file
or a sync that carries **more than one profile** is refused, with the reason on the screen, and your
file is left exactly as it is — run it on the television. While you are still in the setup wizard, a
backup whose single profile has *another name* is refused too, and the app tells you to open
**Settings → Backup & Restore**, where that rename can be confirmed. Once you are in the app, such a
file is applied by renaming this phone's profile to match — the app says so and asks first, on the
screen where you confirm, and puts the old name back if the restore fails.
The app opens once the restore is complete and starts downloading the
restored playlists straight away. An Xtream playlist whose password was not in the backup waits
instead: the last screen names it, and typing the password in that playlist's **Edit** form loads it.
Later on, the full two-way version lives at
More → [Local sync](#-local-sync-with-your-tv).

---

## 🧭 Getting around

**On a phone** — a bottom bar of four: Home · Live TV · TV Guide · Library (Movies | Series at its top).
**On a tablet, or a phone held sideways** — the bar becomes a floating rail down the left (with
Downloads and More too), Live TV puts the preview beside the list, and Movies and Series show the
picked title on a card beside the posters.

If you are upgrading from an older build, the phone has no **Sports** tab any more, and any match
reminders or goal alerts it had saved are cancelled the first time this version opens.

Each page has its title and, under it, where you are — **"‹ Sky Cinema · 9 channels"**. Tap that line
to change category. Top right: the **playlist** button, **search** and **your avatar**, which opens **More**.

- **Tap the tab you are already on** — or long-press it — to jump that list back to the top.
- **More** (tap your avatar) shows your profile and holds **Settings**, Downloads, Favourites,
  History, **Backup & Restore**, Local sync, the error log and About. There is no profile list and
  nothing to switch to — this is the only profile (see [Your profile & App lock](#-your-profile--app-lock)).
- The **playlist button** at the top right switches which playlist the whole app is showing.

---

## 👆 Player gestures — the cheat sheet

| Gesture | Does |
|---|---|
| **Tap** | Show or hide the controls |
| **Double-tap left / right** | Skip back / forward by your seek step — on a live channel with catch-up, by your **Live rewind step** |
| **Drag sideways** | Scrub. The bar shows where you will land before you let go |
| **Drag up/down, left third** | Brightness |
| **Drag up/down, right third** | The phone's volume (same as the volume keys); past the top, a boost up to 150% |
| **Swipe down, middle** | Shrink into the mini player, still playing |
| **Swipe up, middle** | The channel list (live only) |
| **Pinch** | Zoom — fit or fill |
| **Press and hold** | 2× speed while your finger is down |
| **Two-finger tap** | Mute / unmute |

**Too sensitive?** **Settings → Watching & recording → Gesture sensitivity** changes how far a value
moves per centimetre of finger, without changing how far you must travel to start a gesture.

---

## 📥 Playlists & sources

### ➕ Add a playlist
**Where:** Settings → Sources & guide → **Playlists** → Add, or the first-run wizard
**Xtream** (server, user, password), **M3U** (a URL, or a file picked with your phone's file picker),
or **Stalker/Ministra** (portal URL + MAC, with an optional username and password, and optional Serial
Number, Device IDs and Signature).
An M3U link of the form `…/get.php?username=…&password=…` is really an Xtream account: when the
provider's server confirms it, it is added as **Xtream**, with movies and series in their own tabs.
A name another playlist already has gets a number, so two unnamed playlists are **My IPTV** and
**My IPTV 2**.
Under **User-Agent** there is an optional **Referer** — leave it empty unless your provider asks
for one; it is then sent with every stream of that playlist.

### 📱 Setting up the television too?
**Where:** on the **TV** — Settings → Sources & guide → Playlists → Add Source → **Remote**
Typing an Xtream password with a TV remote is miserable, so the television can hand the job to this
phone. It shows a QR code and a 6-digit PIN; scan it, fill the form in your browser, and press
**Send to TV** — then press **Start Import** on the television. It is a web page served by the TV, so
it works from any phone on the same Wi-Fi, this app installed or not.
A backup you take from this phone always carries the current profile only, so the television can
always open it. A television backup that carries several profiles cannot be restored here — see
[Backup & restore](#-backup--restore).

### ℹ️ Info & Re-test
**Where:** Settings → Sources & guide → Playlists → tap a playlist → **Info**
Whether the account is alive, when it expires, and **how many streams your provider allows** —
measured once at the first sync. **Re-test** re-measures it; it warns first, because it stops
anything playing and takes a few minutes, and what it measures is kept even when the provider's own
number says less. **Ignore connection limit**, under Re-test, lets Multiview and recordings try more
streams than the playlist reports; a stream the provider refuses still says so.

### ⚡ What to sync, and running it in the background
**Where:** while adding, and Settings → Sources & guide → Playlists → Edit
Choose per section whether Live, Movies and Series are fetched. A big import offers **Run in
background** so you can start using the app immediately; a status pill shows progress.

### ✨ Clean channel names
**Where:** Settings → Sources & guide → Playlists → tap a playlist → **Names**
Turn on **Clean channel names** and that playlist's channels lose a country prefix (`UK|`, `[US]`,
`DE:`) and a quality tag at the end (`HD`, `FHD`, `4K`, `HEVC`) everywhere they appear: "UK| News
One HD" shows as "News One". **Also remove** takes your own words, separated by `;`, removed from
the start or end of every name. Saving refreshes the playlist. Guide matching still uses the
provider's names, channels you renamed in Customize keep your name, and favourites and history stay
put. Switch it off and the next refresh brings the original names back.

### 🗂️ Several playlists
**Where:** the playlist button at the top right
Show them all merged, or narrow the whole app to one. Rows carry the provider's name when more than
one is loaded.

### 📡 Stalker portals
Behave like any other playlist once added. A portal that asks for a login on top of the MAC takes it
in **Username** and **Password** under the MAC. If the portal refuses the login, re-check the MAC **and
your phone's date & time** — Stalker validates timestamps.

---

## 📺 Live TV

### ▶️ Watch a channel
**Where:** Live TV → tap a channel
The channel plays in a **preview at the top** of the list, with what is on now and next — muted
unless Settings → Live TV → **Preview sound** is on. **Tap the same channel again**, or the picture,
for full screen. Back closes the preview. Sideways or on a tablet the preview sits beside the list.
A channel on **Home**, in **Favourites** or in **History** plays full screen straight away; one found
in **Search** opens Live TV with it in the preview.

### ⭐ The long-press menu
**Where:** long-press any channel
Favourite · Rename · Hide · Match EPG · EPG offset · Move · Move to category · **Record** ·
**Add to Multiview** · Catch-up · Play in another app. Inside a custom category: **Remove from this category**.
What you **Move** keeps its place in **A–Z** and **Provider** alike; everything you never moved follows the
sort around it. Deleting a download or a recording asks first. **Settings → Layout → Long-press menus**
rearranges each menu with ↑ ↓, hides an action with the eye, and **Reset** brings it back as shipped.

### 🏷️ Categories
**Tap the line under the title** for the categories sheet: Favourites, Recently watched, Catch-up,
All channels, then your provider's groups with counts and a search field. **Settings → Layout → Browsing &
lists** can leave out **Catch-up** or **All channels**; Live TV then opens on your first category. The
last category is remembered (only the ones you pick); **TV Guide follows Live TV** makes the
TV Guide open on it and share it. A channel tapped in **Search** opens Live TV on that channel's own
category, scrolled to it.
**Long-press a category** to hide or move it, or **Restore to playlist default** — hidden channels
come back, moved ones return and the order resets; it tells you what will change first.

### 🔧 Compatibility mode
**Where:** full-screen player → the **⇄** engine button
If a channel stutters or won't open, one press flips it to the other engine and remembers that
channel's choice.

### 🏛️ Which engine channels start on
**Where:** Settings → Player → **Live TV player**
**ExoPlayer, then mpv** (default) · **mpv, then ExoPlayer** · **ExoPlayer only** · **mpv only**, with
a **per playlist** override below it.

> **A channel that won't play is worked through every combination** — each engine on each stream
> format, up to four, each tried once. Then it stops and tells you.
> **Give up on a channel after** (Settings → Live TV) bounds how long that may take, and
> **Give up after, per playlist** gives one provider its own time.

---

## 🗓️ TV Guide — three shapes

**Where:** the **TV Guide** tab → the **order** button in the tools row → View

| Shape | Best for |
|---|---|
| **On now** | A phone held upright — one channel per row, what's on, how far through, what's next |
| **Grid** | A tablet or a phone sideways — the classic guide, with a shared timeline |
| **Timeline** | One channel read top to bottom |

The app remembers which you chose. Also in that sheet: **sort**, **Auto-match EPG** with a review
list — tick **Include guide logos** there and the matched channels also take the guide's logo — and a **Size** slider for the grid's time scale. A day strip sits above the guide — as many days
as **Guide days to keep** is set to — and there's a search box.

**Tap a programme** for its synopsis, **Watch channel**, **Watch from start** (catch-up),
**Record**, **Record every showing** and **Favourite**.

### ➕ Add guide data
**Where:** Settings → Sources & guide → **EPG sources**
**The guide is opt-in, and the phone never offers it by itself** — if you have no programme names,
this is why. Add XMLTV feeds here, **Fill from playlist** to take the URL your playlist already
carries, set a User-Agent, pick a refresh interval — which can be **every N days** — and choose
whether to use that feed's channel logos. The refresh runs when the app opens or comes back, and
waits while something is playing.

When two feeds list the same channel, the one higher on the list is used. Tap a feed and choose
**Move up** or **Move down** to set the order; a lower feed still fills any hours the higher one
leaves empty.

**Guide days to keep** is under Settings → Sources & guide → Guide & catch-up, beside EPG time offset: how many days of upcoming guide to store, 1–14, seven
by default. The same number decides how much is downloaded, how much is kept, and how many days the
strip above the guide offers. Old programmes are kept only on channels with catch-up, since those are
the only ones that can play them back. With catch-up channels the day list also goes back up to a
week, and the past days of their guide come from the provider's archive as their rows come on screen.

A large guide takes a while, and it **keeps downloading when the screen goes off** — a notification
shows while it does, so the phone lets it finish instead of stopping it and starting it over.

> **Playlist has no channel logos?** Turn on **Use this guide's channel logos** on the feed. Logos
> then come from the XMLTV guide instead; channels the feed has no logo for keep the playlist's one.

---

## ⏪ Catch-up & ⏺️ recording

### ▶️ Replay something
**Where:** Guide → a past programme → **Watch from start**, or long-press a channel → **Catch-up**
If you replay a programme that is still on air and the provider stops sending it partway, the
channel switches to live by itself instead of freezing.

### 🕐 Rewind live
**Where:** full-screen player, on a channel with an archive
Drag the live bar back, tap the rewind button, or use **Go back to…** for a list of times — its first
row, when the guide knows what is on, is **Start of “…”**, back to the beginning of the programme on
air. **Go live** returns.

### ⏸️ Pause and rewind channels without catch-up
**Where:** **Settings → Live TV → Pause and rewind live TV**
Off by default. While you watch a channel full screen, OwnTV saves it on this phone and plays it
from that copy, so it still looks live — and you can **pause**, rewind and go forward on channels
whose provider keeps no archive. Pause, the rewind button, forward (once you are behind), the bar and **Go live** work as on a catch-up channel. A dark stretch on the bar is a moment the connection
dropped; playback jumps over it. If your internet drops, saving waits and carries on when it is back; the channel picks up by itself.

- **Rewind length:** 15 (default), 30, 45 or 60 minutes. At least **1 GB** of the phone's storage always
  stays free; the oldest part goes first.
- **Picture-in-picture and sound-only playback are not leaving: they keep saving.**
- **The delete rules:**
  - Leave the channel and its copy is kept for **5 minutes**. Come back within them and OwnTV asks
    **Continue where you left off?** — **Resume** plays on from where you left, **Go live** jumps to now.
    **Resume a saved channel**, just below, turns the question into **Always resume** or **Never resume**.
  - Watch another channel for **2 minutes** and the copy you left is deleted at once — you have moved on.
  - After 5 minutes it is deleted anyway, and every copy is deleted when OwnTV starts. The 5 minutes
    are real time, the screen off included: come back later and the channel opens live.
- Catch-up channels still rewind into the provider's archive. Protected (DRM) and encrypted channels
  play as before, without a copy.

### ⏺️ Record
**Where:** long-press a programme in the Guide, or a channel in the list, or the **Record** button in
the player
**Record every showing** sets a standing rule. **Schedule recording…** in a channel's menu records it
between a day and times you pick, guide or not; an overlap with another recording is shown first. While
a channel records, its menu offers **Stop recording**. Recordings land in **Downloads → Live TV**, and the
status pill shows one running even over the player.

> A recording costs one of your provider's connections and says so before it starts.

> **Copy-protected channels cannot be recorded.** Where a provider protects a channel with DRM,
> it can be watched but not saved — the protection is theirs, not OwnTV's. Asking to record one
> stops straight away and tells you why, rather than leaving a file that will not play.

---

## 🔲 Multiview

**Where:** the **Multiview** button in the player, or long-press channels → **Add to Multiview**
*(Turn it on first: Settings → Watching & recording → Multiview.)*

| Gesture | Does |
|---|---|
| **Tap a tile** | Give it the sound |
| **Tap an empty tile** | Pick a channel |
| **Double-tap** | That channel, full screen |
| **Long-press** | The tile menu — change channel, sound only, add a tile, remove |
| **Back** | Leave, stopping everything |

Landscape puts two tiles per row; portrait stacks them, which is honest rather than clever. The
Settings number is a **ceiling**, not a size — the grid opens with two and grows when you ask.
Leaving the app stops the tiles.

---

## 🎬 Movies & Series

### 🖼️ Browse
**Where:** the **Movies** and **Series** tabs
Search, sort, and **Grid | List** in the tools row; the line under the title picks the category.
**Pinch to resize** the posters, or use **Poster size** in the sort sheet. A part-watched film carries a progress
bar, in the grid and the list alike; a show carries the bar of its latest part-watched episode.

### ▶️ A film or a show
Tap for its page: backdrop, cast, chips, **Resume** or **Play**, favourite, download, season chips
(**Specials** last, when the show has them) and episode progress. A show **opens on the episode you last watched**; one you have not started
plays from Season 1 Episode 1, with **Specials** after the last season.

Starting something part-watched anywhere else — a **Continue watching** row, the hero card, an
episode in a series — asks **Resume or start over?** Settings → Watching & recording → **Resume playback**
changes that to always resuming, or always starting from the beginning. A film you left in the first
ten seconds never asks.

### 🔧 The long-press menu
Favourite · Download · Hide · Rename · Move · Move to category · Mark watched/unwatched ·
TMDB details · Trailer · Play in another app.

---

## 🎬 TMDB metadata

**Where:** Settings → Content & metadata → **Metadata (TMDB)**
Posters, plots, cast and trailers. **Metadata source** picks provider-only, provider + TMDB, or TMDB
only; **Language** sets the language of plots and artwork, separately from the app's own language.

**Your own key** removes the shared allowance — the page shows what is left of it and has a **Test
connection** so you can prove a key or a self-hosted server works.

---

## 🔎 Search · 🕐 History · ⭐ Favourites

### 🔎 Search
**Where:** the magnifying glass at the top right
One field over channels, films and shows, grouped with a count for each. Once you type, filter
chips — **Live TV / TV Shows / Movies / Series** — narrow the results; tap to turn one on or off,
several at once, none for everything. Recent searches are kept,
and three chips offer **Continue watching**, **Unwatched favourites** and **Channels**.
**TV Shows** lists programmes from the stored guide whose title matches, on now or within the next 12
hours, with the channel and time; tap one to open that channel.
Every match is listed, up to 500 of each kind, so a word your provider carries hundreds of — "CNN",
say — doesn't need a narrower search to reach the rest. Words match from their start, and a dash
or other mark counts as a gap: "Spider-M" finds every Spider-Man.

### ⭐ Favourites & 🕐 History
**Where:** More → Favourites / History
Long-press a history row to remove it; **Clear** wipes it by type.

---

## 📥 Downloads & recordings

**Where:** More → **Downloads**
Three tabs — **Live TV** (your recordings), **Movies**, **Series** — with a free-space bar, a
measured transfer rate, and pause · resume · retry · delete per item. Downloads keep running when you
leave the app. A download adds connections, up to eight, while each makes it faster, never more than
your playlist allows. A failed one says why (account busy, connection lost, not enough space, …), and
a finished one whose file is gone says **File missing**.
On a playlist that allows one stream, pressing Play during a download pauses it and asks **Pause
download and watch** or **Keep downloading**, with **Don't ask again**; change the answer later under
**Watching while downloading** in the download options.

### 📂 Choose where they are saved
**Where:** Settings → App → **Download folder**
Pick any folder with your phone's own folder picker, an SD card included. **Export** copies a
finished download or recording anywhere you like. If that folder goes missing — a card removed, or
permission withdrawn — downloads go to OwnTV's own folder until it is back, and the Downloads screen
says so.

### 📶 Wi-Fi only
**Where:** Settings → App → **Download over Wi-Fi only**, and Settings → Player → **Data saver**, which
refuses to start a stream on mobile data. Downloads as a whole are switched on and off by
**Allow downloads**, under the **Downloads** heading on the **Settings → App** page; off removes every
**Download** button.

---

## 🎛️ The player

Every gesture above also has a button on the control bar:

| Button | What |
|---|---|
| **Go live** | Back to the live edge |
| **Volume · Brightness** | Sliders, with a mute row |
| **Speed** | 0.5× to 2× |
| **Subtitles · Audio** | Tracks (the language you pick is remembered per channel, film or series), plus subtitle search, subtitle timing and A/V sync. Both are always on the bar — a film with one soundtrack still has A/V sync. Scroll the panel to reach what is below the tracks |
| **Previous · Next** | The episode either side of this one, beside play. Series only |
| **⏮ · ⏭** | Live: the previous or next channel in the list you are watching, wrapping round at both ends. Shown when the list has two or more channels |
| **Aspect** | Fit · Fill · Stretch · Original · Force 16:9 · Force 4:3 |
| **Quality** | Only when the stream offers several picture sizes. Auto, or one size for what is playing now |
| **Favourite** | Adds what is playing |
| **Catch-up** | *Go back to…* on archive channels |
| **Sources** | Live: the same channel in another quality, category or playlist, the one playing ticked; tap one to switch. Locked and hidden channels are left out |
| **Previous channel** | Back to the channel you watched before; tap again to flip back. Headphone "previous" does the same on live |
| **⇄** | Swap the player engine |
| **Channels** | The channel list, categories first |
| **Mini player** | Shrink and keep browsing |
| **Sound only** | Drop the picture, keep the sound |
| **Record · Multiview** | Once enabled in Settings |
| 🌙 **Sleep timer** | 15–90 min, end of programme on a live channel, or end of movie / episode (the next episode then does not start); green while running. Stopping playback yourself cancels it. **Also turn off the screen** locks the phone when it ends — Android asks for permission once |
| **Info** | The technical readout (on mpv including **Interlacing**: none, deinterlaced by the player or by the phone, or not deinterlaced) — and **Report** appears while it is open |

**Back stops the stream.** The mini-player button and the swipe down are what keep it playing.

### 🪟 The mini player
**Where:** Settings → Watching & recording → **Mini-player**
- **Floating window** (default) — drag it anywhere, **pinch** to resize between three sizes, it snaps
  to the edges, **double-tap** to go full screen, **long-press** for its menu, swipe it down to stop.
- **Docked bar** — above the tabs, with the title and transport buttons.

Sound-only and casting always use the docked bar: there is no picture for a window to hold.

### 📱 Picture-in-Picture
Press **Home** while watching full screen and the picture follows you into the system's own floating
window, over other apps, with skip and play/pause buttons. Turn it off in
**Settings → Watching & recording → Picture-in-Picture**.

### 🎧 Sound only
**Where:** the headphones button in the player, or the floating window's menu
Keeps the sound, drops the picture — the phone's biggest battery and data saving. It can switch
itself on **when the screen goes off** or **on mobile data**, and it remembers your choice per
channel. A **sleep timer** lives in the same menu — and on the full-screen player bar.

---

## 📺 Cast to a television

**Where:** the cast button at the top right or the player
Appears only when there is a receiver on your network. The television plays the stream itself, so
the phone's lock screen and notification then drive *it*.

> The receiver decodes the stream, and a Chromecast cannot play raw MPEG-TS — a large share of an
> IPTV catalogue. OwnTV says so plainly rather than failing silently.

---

## 🔄 Local sync with your TV

**Where:** More → **Local sync**
Swap playlists, settings, favourites, history and resume positions with the television over your own
Wi-Fi. No account, no cloud, no file. Everything that arrives lands on this phone's one profile.

```
Both devices open Local sync  →  pair (scan the QR, or pick from the list)
          →  choose a direction: send · receive · merge
          →  preview exactly what will change  →  apply
```

- **Both devices must have the screen open.** Hosting runs only while it is, which is what keeps it
  cheap and deliberate. The exception is a phone still being set up: it offers this during setup and
  only receives, so only the *other* device needs Sync mode on.
- **Nothing is applied on arrival** — you see what will change first.
- **A deletion stays deleted** on both devices, rather than being undone by the merge.
- **More than one profile cannot come across.** A sync that offers several profiles is refused and
  says so — this phone holds one. Nothing is applied and nothing on the other device is touched, so
  you can change your mind and try again.
- **A single profile under another name** is applied by renaming this phone's profile to match. You
  are told that on the screen where you confirm, before anything changes, and the old name comes back
  if the sync fails.
- If that incoming name belongs to a profile an older version left behind here, the sync is refused
  rather than quietly relabelling it; rename it on the other device and sync again.

---

## 👤 Your profile & App lock

**One profile per phone.** Its favourites, history, resume points and layout are simply the app's
own. There is no "who is watching" screen: nothing to add, rename or delete, and nothing to switch
between. The name and the face at the top right are this phone's profile.
**Where:** More — your profile is shown there, with **Settings**, Downloads, Favourites and History.

Profiles an older version left behind are **not deleted and not shown**: they stay in the app's
storage, invisible to this version, and an older build installed again would still see them. Nothing
here throws away your data or your backup files.

### 🔒 App lock
A 4–6 digit PIN that opens the app itself — the screen you answer before Home appears.
**Where:** Settings → App → **App lock** → **Set app lock**. Once set, the same page offers
**Change PIN** and **Remove app lock**, and both ask for the digits you have first.
- **Five wrong PINs in a row** and the button waits about 30 seconds before the next try.
- It is **one PIN, not three**: the same digits open a locked category and answer the Settings PIN.
- **Kids mode** (Settings → Content & metadata → **Kids mode**) is one switch for the whole app, and
  switching it off while a lock is set asks for the PIN first.
- **Forget the PIN and there is no way back in from inside the app.** The only answer is to clear the
  app's data, which starts OwnTV over and takes everything saved with it — favourites, history, the
  downloads list. The **App lock** page says so, in plain words, above the buttons.
- Turning the lock on does not interrupt what you are watching: it takes effect the next time the app
  opens.

### 📥 Allow downloads
**Where:** **Settings → App**, under the **Downloads** heading — **Allow downloads** (it moved out of the
old profile editor). Off removes every **Download** button. Downloading a whole show or season first asks
"Download N episodes?".

### 🚪 Where the app opens
**Where:** Settings → App → **App startup** — Home, the last channel (full screen),
**Last channel · TV Guide** or **Last channel · Live TV** (that tab opens on the channel's category with
the channel in the preview), Favourites, or one chosen channel.

---

## 🎨 Make it yours

### 🎨 Theme, accent & glass
**Where:** Settings → **Appearance**
Light, dark or system, with an accent colour. Three colour settings — accent, selection highlight and
subtitle text — each open a **real colour picker**: hue bar, saturation square, live preview and a
hex box.

**App icon** (Settings → App): eight colours for the OwnTV icon and logo — Petrol,
Sunflower, Cobalt, Tomato, Station Board, **Eggshell** (default), Olive and Olive on Cream. **Restart
now** switches at once: OwnTV closes and opens again by itself, on Home, with the new colour.
**Later** switches when you next leave the app. Some launchers take a moment
to show the new icon, and a few move it from the home screen to the app list.

**Glass & background** (Settings → Appearance) picks the background — **Stage**, a **picture** from
your phone, or **Plain** — its look, darkening and blur, the accent light, and the **Glass** switch
with its opacity. Real frost needs Android 12+.

### 🔤 Fonts & size
**Where:** Settings → Appearance → **Fonts & text size**, **Popup size** and **UI zoom**
Six font families for the interface and for popups, and interface zoom.

### 🗣️ Language
**Where:** Settings → App → **Language**
Three to choose from — **English**, **简体中文**, **繁体中文** — plus **System default**, which follows
the language the phone itself is set to. A language an older version had saved that is not one of
these becomes **System default**, and any English variant becomes **English**.

### 🗂️ Customize
**Where:** Settings → Content & metadata → **Customize categories & items**
Hide, rename, reorder and regroup categories and items, with **bulk rename** (rules, automatic
cleanup, a review step and restore-originals — for category names too, from the categories page),
**custom categories**, span selection, and an optional
**PIN lock**. With a PIN set, **⋯ → Ask for PIN to open Settings** makes Settings, Backup & Restore and
Local sync ask for the same PIN — which is also the PIN that opens the app when **App lock** is on.
Removing the PIN turns it off.

**Lock a category with a PIN** — in the categories sheet of Live TV, the TV Guide, Movies or Series,
hold a playlist category → **Lock with PIN**. It uses the Customize PIN (the first lock asks you to set
one). The category stays in the list with a lock; its channels, films and shows stay out of everything
else (search, Guide, Home, favourites, history, Downloads, recordings) until you open it with
**Enter PIN**. It stays open until you leave the app — leaving also stops anything
playing from it, the floating window included. **Remove lock** in the same menu asks the PIN, and
removing the Customize PIN unlocks every category. 5 wrong PINs → a 30 s wait.

### 🏠 Home & layout
**Where:** Settings → Layout → **Home screen**
Reorder or hide Home rows — including **Favourite movies** and **Favourite series**, which open the
film or show — switch channel rows between cards and *On now*, and choose whether Now
Trending is the detailed hero or a plain poster row.

---

## ⚙️ Settings worth knowing

Settings is eleven group cards — Quick among them, and no **Profiles** card any more. There's a
**search box** at the top that
finds any setting by its name, and **Quick** holds the rows you pin yourself by **long-pressing a row**.

| Setting | Where | Why |
|---|---|---|
| **Live TV player** (+ per playlist) | Player | Which engine opens a channel |
| **Give up on a channel after** (+ per playlist) | Live TV | Bounds how long a dead channel can spin |
| **Movies & Series player** (+ per playlist) | Player | Which engine opens films and episodes |
| **Catch-up time zone** | Sources & guide | **Automatic** (default) uses the Xtream panel's own zone; Device or a fixed offset otherwise |
| **Catch-up time zone per playlist** | Sources & guide | One provider's archive on a different clock (quarter-hour zones included) |
| **Live latency** (+ per playlist) | Live TV | Closer to live, or steadier |
| **Pre-buffer** (+ per playlist) | Live TV | Collect a few seconds first on a flaky provider |
| **Multiview** | Watching & recording | Off by default; also sets the tile ceiling |
| **Record what I'm watching** | Watching & recording | Adds the Record button to the player |
| **Auto frame rate for films and series** · **for Live TV** | Picture | Two switches; an earlier single setting carries into both. Off by default; full screen, seamless switches only; warns where the display can't be asked |
| **Film buffer** · **Network timeout** · **Reconnect attempts** | Player | Films, episodes and catch-up on a bad line. Auto / 1 = as before |
| **Reset saved live TV player choices** | Player | Undo per-channel player choices |
| **Delay for this output** | Sound & subtitles | An audio delay for the output playing now — Bluetooth, the phone's speakers, headphones, HDMI — each kept on its own |
| **FFmpeg audio for ExoPlayer** | Sound & subtitles | Decode sound the phone has no decoder for. Off by default; applies after a restart |
| **Prefer SDR** | Picture | When a stream offers both SDR and HDR, play the SDR one. Off by default |
| **Dolby and DTS passthrough** · **Night mode** · **Volume leveling** | Sound & subtitles | Send Dolby/DTS undecoded (on) or decode in the app; turn loud scenes down; even out loudness between channels. Night mode and leveling work on both engines |
| **Maximum video quality** · **Maximum quality on mobile data** | Picture | The highest picture played when a stream offers several; the mobile-data limit follows a Wi-Fi ⇄ mobile switch while playing |
| **Software decoding above 1080p** | Picture | Off. Lets 4K play in software when hardware decoding is off or fails; only for a fast phone |
| **Tunneled playback** | Picture | Experimental, off. Only on phones that support it; turns itself off after a failure |
| **Preferred audio / subtitle language** | Sound & subtitles | For the whole app, 50 languages. **Original language** (audio) plays a film or series in the language it was made in, when the stream has it |
| **Subtitle appearance** | Sound & subtitles | Size, **font**, colour, position, background |
| **Gesture sensitivity** | Watching & recording | How far a value moves per centimetre of finger |
| **Background playback** | Watching & recording | Keep the sound when the app leaves the screen |
| **Data saver** | Player | Refuse to stream on mobile data |
| **Language** | App | Three languages, or **System default** |
| **App lock** | App | The PIN that opens the app — the same PIN locked categories and the Settings PIN use |
| **Kids mode** | Content & metadata | One switch for the whole app; off asks the PIN when a lock is set |
| **Allow downloads** | App, under Downloads | Off removes every **Download** button |
| **Check for updates** | App | On startup, or on demand |
| **Error log** | More → Error log | Recent playback failures, exportable |

### 💾 Backup & restore
**Where:** More → **Backup & Restore**
One `.own` file with this phone's profile, sources, settings and downloaded subtitles, **optionally
encrypted with your own password**. The export always carries the **current profile only**, so a phone
backup opens fine on the television. Restores a television backup too — but a TV file that carries
**several profiles** is refused, the reason given on the screen, and the file is left untouched so you
can run it there instead. A file whose one profile has a different name renames this phone's profile to
match, and says so on the screen where you confirm.

---

## 🩺 Troubleshooting

| Problem | Try this |
|---|---|
| A channel stutters or won't open | The **⇄** button in the player — compatibility mode |
| Channel won't play at all | It already tried every engine and format. See More → **Error log** |
| "Too many connections" | Wait — OwnTV counts down and retries by itself |
| The screen keeps sleeping | Only if nothing is playing; check the channel actually started |
| Stalker portal refuses the login | Re-check the MAC and your phone's **date & time** |
| Guide is blank | Settings → Sources & guide → **EPG sources** — add a feed, sync it, then **Auto-match** |
| A series says "Could not load the episodes" | The provider refused the episode list; the code in brackets (e.g. HTTP 407) says why. Stop any other stream on the account and open the series again |
| Downloads won't start | Settings → App — **Download over Wi-Fi only** may be on, or **Allow downloads** (same page, Downloads section) may be off |
| Locked out by the app PIN | There is no reset inside the app. Clearing the app's data starts OwnTV over and takes favourites, history and the downloads list with it |
| A TV backup refuses to restore | It carries more than one profile and this phone keeps one. Your file is not changed — restore it on the television |
| The backup's profile has another name | Confirm on the screen that says so: this phone's profile is renamed to match, and the old name comes back if the restore fails |
| No **Sports** tab | The phone app no longer has it. Match reminders and goal alerts an older version saved are cancelled the first time this one opens |
| Nothing casts | The receiver has to decode it; raw MPEG-TS channels cannot be cast |
| Update won't install | Android asks you to allow installs from OwnTV the first time |

**Still stuck?** More → **Error log** exports a report. Bring it to
[t.me/owntvplayer](https://t.me/owntvplayer).

---

## 💡 Tips

- **Long-press** is the answer to "where is that option?" nine times out of ten.
- **Swipe down** in the player keeps the stream; **Back** stops it. That is the whole difference.
- Turn the phone **sideways** on a channel screen to go full screen without reaching for a button.
- **Pin your own Quick toggles** — long-press any switch in Settings.
- A **free personal TMDB key** removes the shared metadata limit.
- If you also use the television, **Local sync** saves setting everything up twice.
