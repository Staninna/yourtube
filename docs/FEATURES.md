# Features and backlog

The source of truth for what YourTube does and does not do yet. Keep it current: when a feature lands,
move it up and flip its status. New ideas go in the backlog.

**Status legend**

| Mark | Meaning |
|------|---------|
| done | works in the current build |
| partial | basic version works, rough edges or missing pieces noted |
| todo | not started |
| maybe | wanted eventually, not committed |
| no | deliberately out of scope |

## Design constraints (do not break these)

- **No login, no Google account.** De-Googled. No OAuth, no sign-in WebView. Anything that would
  need an account (personal subscriptions feed, watch-later sync, likes) is replaced by a local or
  anonymous equivalent, or left out.
- **Local only.** History, library and feeds live on the device. No server, no cloud sync account.
- **Watching stays in the WebView.** The native side is the shell around it.

## v0, the foundation (current build)

| Feature | Status | Notes |
|---------|--------|-------|
| YouTube in a WebView (`m.youtube.com`) | done | native Compose activity hosting the WebView |
| Fullscreen video | done | YouTube's fullscreen button covers the screen with the video, system bars hidden (a swipe from the edge shows them for a moment). The screen turns to the video's shape, landscape for a wide video and portrait for a tall one, either way up, and goes back to how it was on leaving. Back leaves fullscreen. |
| Resume where you left off | done | each history entry keeps its playback position in the native store (last 2000 videos), so it survives identity changes. Rewinds 5 s; skips videos under 5 s in or within 15 s of the end; a `t=` URL parameter wins. Saved every 10 s while playing and on pause, end and leaving the app. |
| Fresh identity every launch | done | each process starts with an empty WebView data directory; each new WebView (cold Activity start, renderer crash) gets a new WebView profile, and the old one is wiped once its WebView is destroyed. WebViews without profile support restart the app process instead. `SOCS` (reject all) and `PREF` (theme, language, region) are set before the first request, so no consent page. |
| Launch behavior | done | a cold launch opens Feeds (Library without subs) and loads nothing; the Watch tab loads YouTube home when empty; after process death the last URL reloads. Config changes (rotation, theme, PiP size) never recreate the screen. |
| Renderer crash recovery | done | the WebView is replaced under a new identity and the page reloads and resumes. |
| Links leaving YouTube | done | YouTube, its consent page and the `google.com/sorry` captcha stay in the app; every other link opens in the browser or the matching app. |
| Ad blocking | done | four layers, each a switch in Settings > Content and all on by default. **Video ads**: the early script deletes `adPlacements`, `adSlots` and `playerAds` from player responses before YouTube's player reads them (the `ytInitialPlayerResponse` global, `JSON.parse`, `fetch` and XHR answers from `/youtubei/v1/player`, `get_watch` and friends), after uBlock Origin's `json-prune` rules, and drops ad entries from Shorts sequences. **Ad servers**: requests to doubleclick, googlesyndication, googleadservices, googletagservices and YouTube's ad paths (`/pagead/`, `/api/stats/ads`, `/ptracking`, `/get_midroll_info`, `/youtubei/v1/player/ad_break`, `/pcs/activeview`) get an empty answer; the video streams and `/youtubei/v1/player` never match. **Ads in pages**: CSS hides promoted videos, "sparkles" ad cards, ad slots and companion ads. **Skip leaked ads**: an ad that still plays is muted, played at 16x, and its skip button pressed once it shows; the viewer's mute state and speed come back after it. Of four leaked ads timed with it, two ended within 5 s, one ran 19.5 s and one went unmeasured, so it "tries" to skip. Every ad that shows counts as `ad.leak` in Settings > About > Diagnostics. Measured over 10 loads per setting: 2 ads with nothing on, 0 with all four on, 0 with video ads or ad servers alone. Measurements and sources: `docs/research/ad-blocking.md`. |
| SponsorBlock | done | segments come from SponsorBlock's hash-prefix API (`/api/skipSegments/<first 4 hex of sha256(id)>`, so the server never learns the video), fetched natively with a generic user agent and no cookie, cached for the last 32 videos. Each category (sponsor, self-promotion, interaction reminder, intro, outro, preview, filler, non-music section) is Skip, Mute, Show or Off; sponsor and self-promotion skip by default, the rest show. A skip happens only when playback runs into a segment, never after a seek into one, and shows "Skipped sponsor" with Undo, which seeks back and leaves that segment alone for the rest of the playback. Mute holds only inside the segment and restores the mute state after. Every segment that is not Off is marked on the progress bar in SponsorBlock's colors. Offline, a 404 (no segments) or a server error: the video plays as it is and nothing shows. The server is configurable (https only, checked as you type); settings changes reach the video on screen at once. |
| Background playback | done | a video that plays keeps playing after Home, another app, the screen going off, or back at Watch's root (which sends the app to the background instead of closing it). A media session shows it in the notification, on the lock screen and to Bluetooth and headset buttons: title, channel, still, play/pause, and a seek bar; no next or previous. Paused, the notification stays for 10 minutes away from the app, then goes. Swiping the app away from recents pauses the video and removes the notification. Unplugged headphones or a lost Bluetooth device pause it. The app neither declares nor asks for the notification permission; media notifications do not need it. Media buttons need the video to have made sound first (see known risks). |
| Picture-in-picture | done | leaving the app (Home, the home swipe, switching to another app; the recents screen once you leave it) while Watch shows a playing video moves the video into a picture-in-picture window: by itself from Android 12, from the leave hint before that. Only the video shows, filling the window on black; expanding brings back the page and the app's bars with the same page still playing, and rotating or resizing the window never reloads it. The window's play and pause are the media session's, as in the notification. The window takes the video's shape, clamped to Android's 2.39:1 either way, and grows from where the player sits. Closing the window pauses the video and removes the notification. A paused video, a video under a native tab, Settings or search, and a device without picture-in-picture or with it turned off for the app get the background audio instead. Settings > Playback > Picture-in-picture: Automatic (default), Fullscreen (only from YouTube's fullscreen) or Manual (Watch's menu has Picture-in-picture while a video plays; leaving the app plays the sound alone). Without picture-in-picture the setting is locked and says why. |
| Back navigation | done | predictive back throughout: Watch goes back through the page's history, a native tab goes back to Watch once it has something, Settings and search close (Settings shrinks toward the swiped edge as you drag), and at the root the system back-to-home animation plays, except while a video plays: then the app goes to the background and the video plays on. |
| App shell (Material 3 Expressive) | done | Expressive theme and motion, wallpaper colors (Android 12+) or a red fallback palette, light and dark, edge to edge with no launch flash. Bottom navigation (Watch, Feeds, Library, History) with fade-through tab motion. The WebView stays alive under the native tabs. |
| Watch top bar | done | back (when the page can go back), star, search, YouTube home, and an overflow with Share (system share sheet, `www.youtube.com` link) and Settings. No title; its color follows YouTube's own header (`#0f0f0f` on watch pages and in dark theme, white otherwise), so bar and page read as one surface. |
| YouTube's own chrome hidden | done | CSS hides YouTube's header, pivot bar, sign-in and Premium nags and app promos, and moves the player and comments panel up into the freed space. YouTube's search UI comes back while its in-page search box is in use. |
| Native search | done | a search action on every tab opens a full-screen field (prefilled and selected on a results page) and loads `m.youtube.com/results` in Watch. |
| Load progress and errors | done | a thin progress line under the Watch bar while a page loads; a failed main-frame load shows a native "You're offline" or "This page didn't load" state with Retry instead of Chromium's error page. |
| Theme sync with YouTube | done | switching the system theme while the app runs rewrites YouTube's `PREF` theme flag (keeping YouTube's other flags) and reloads the page, unless a video is playing; then the next page load picks it up. The WebView background follows the theme. |
| Now-playing bar | done | above the navigation bar on native tabs while Watch shows a video: still, title, channel, a play/pause button and a progress line that waves while playing; tap returns to Watch. The button acts like the notification's. |
| Settings | partial | from the overflow menu. Playback (picture-in-picture), Content (Ad blocking with a switch per layer, SponsorBlock with its segment actions in a bottom sheet and the server in a dialog, Remove all Shorts), Feed (videos per page), Appearance (dynamic color), Library (Manage tags, export, import) and About (app and WebView versions, Diagnostics counts). Privacy appears when its settings land. |
| Accessibility baseline | done | content and state descriptions (star "Starred" / "Not starred", watched progress on thumbnails, switches), 48 dp targets, TalkBack skips whatever lies under an open tab or Settings, layouts hold at font scale 1.3. |

## Next milestone, the login-free NouTube features the user asked for

These are the priority after v0 compiles and runs. All local, no login.

| Feature | Status | Plan |
|---------|--------|------|
| **Star channels and videos (library)** | done | the star in the Watch top bar acts on what Watch shows: a video, a channel or a playlist. What gets starred comes from the URL plus the player's own data, so it stays right after in-page navigation; `/@handle` channels are resolved to their channel id and avatar in the background (cached 30 days). Starring opens the tag sheet at once; tapping a filled star unstars with Undo, no sheet. Library is one tab with a Subs, Videos and Playlists segment, the segment's tags as filter chips (any selected tag matches; each segment keeps its own selection while the app runs), newest first, and a search field once a segment passes 30 items. Each segment has its own empty state. Unstarring a row has Undo too. Channels show their avatars; a channel without one (subscribed from a video, imported) gets it from the channel resolver, two fetches at a time, with the initial on a colored circle until then. |
| **Watch history** | done | every played video is recorded (last 2000) with title, channel, length badge and a watched bar; finished videos are dimmed. Grouped by day: Today, Yesterday, then the weekday and date (with the year once it is not this year). Swipe a row toward the start to remove it and its resume position, with Undo that brings both back; TalkBack offers "Remove from history" as an action. Clear all asks first, then offers Undo. |
| **Feeds (subscriptions)** | done | every starred channel's newest uploads, merged newest first, all channels by default. Per channel the app reads the public RSS feed (two tries, since the feed server flaps between 200, 404 and 500) and falls back to the channel's mobile videos page when RSS fails; both are anonymous, with the consent cookie and no account or key (see `docs/research/feeds-source.md`). Six channels load at a time and the list fills in as each one answers. Tag chips over the list filter by the channels' tags (any selected tag matches; a new selection starts at the top). One page at a time: Load more at the end adds a page, with "Showing 50 of 180" under it; the page size is Settings > Feed > Videos per page (25, 50, 100 or 250, default 50). Pull to refresh with the Expressive indicator, or the Refresh button for TalkBack. A channel that fails keeps its last videos on screen, and a quiet notice names it ("Veritasium didn't load.", "You're offline. …") with Retry for just the failed ones. After a refresh that brought new videos while the list is scrolled down, an "N new videos" pill scrolls to the top. Rows show the still with the watched bar, the channel's avatar and name, and the age as YouTube writes it ("3 days ago"). States: first load, offline, YouTube not answering, no videos, nothing under the selected tags. The last list is cached on the device (`feeds.json`, 30 videos per subscribed channel, left out of backups), so a cold start shows it at once and refreshes behind it; a new subscription fetches just that channel, and an unsubscribed one leaves the list and the cache. With Remove all Shorts on, Shorts leave the list and its counts: RSS links each Short to `/shorts/`, and the videos-page fallback lists none. That fallback also leaves out live streams, which RSS includes. |
| **Tags for the library** | done | replaces folders: one shared pool, and an item can carry many tags. The tag sheet (from the star, or a long press on a Library row) shows every tag as a chip to attach or detach, and a "New tag" field that creates one and attaches it; a name the pool already has (any case) reuses that tag. On a video the sheet also has "Subscribe to <channel>"; switching it off and on again within the sheet keeps the channel's tags and date. Changes apply as you tap, so dismissing the sheet any way keeps the star and the tags. Settings > Library > Manage tags renames (no blank or duplicate names), recolors from an 8-color palette, and deletes after a confirm that says how many items lose the tag; the items stay starred. |
| **Remove all Shorts** | done | one switch in Settings > Content, off by default, replacing stage 3's Hide Shorts (a stored `hideShorts` reads as it, in settings files and backups alike). CSS hides Shorts shelves, single Shorts in results, the channel Shorts tab and YouTube's Shorts tab, whole items so no empty tiles stay. A `/shorts/<id>` link, deep link or in-page navigation opens `/watch?v=<id>` instead, and turning the switch on while a Short shows turns it into its watch page. Feeds leaves Shorts out. |
| App chrome (top bar, drawer) | done | see the shell rows above. |
| Settings + import/export | partial | Settings > Library > Export library writes one JSON file (`yourtube-backup-<date>.json`, with a format marker and schema version) holding library, tags, history with resume positions, and settings, through the system's file picker. Import library reads such a file, checks it, and merges it in: items by id (an existing item keeps its fields and gains the file's tags), tags by id and then by name, history by video id (the more recent watch wins), and the file's settings replace the current ones. The snackbar reports the counts ("Imported 1 item, 1 tag and 4 history entries"). A file that is not a YourTube export, is from a newer version, does not decode, or is over 16 MB is refused with a message and changes nothing; single bad entries (malformed ids, blank names) are skipped and counted, and links are rebuilt from ids. When a store file was unreadable at startup, a snackbar says so and offers Export; it waits until answered, and other notices cannot push it out. Subscription import from NewPipe and Google Takeout is not built yet. |

## Later backlog (from NouTube, not yet scoped)

| Feature | Status | Notes |
|---------|--------|-------|
| Download video | todo | |
| Play original audio (un-dub) | todo | |
| Live chat / live chat replay | todo | |
| Dislike counts (Return YouTube Dislike) | todo | |
| Comment translation | todo | |
| Custom CSS / user styles | todo | |
| Custom launcher icon | todo | v0 ships the default icon. |
| Signed release pipeline (GitHub Actions + Obtainium) | todo | mirror the droid release flow once v0 is worth shipping. |
| Cloud sync account | no | conflicts with de-Googled / local-only. A plain local export/import covers portability. |
| YouTube Music | no | out of scope. |
| Any Google login | no | de-Googled by design. |

## Known risks / open questions

- **Background audio in a WebView leans on Chromium.** Chromium pauses media when its window turns
  invisible, so while a media session lives the WebView reports a visible window whatever it is. A
  Chromium that pauses on something else, or OEM power management that kills the foreground service,
  would still stop it.
- **YouTube pauses in small windows.** Signed out, m.youtube.com pauses a video when the window's
  outer area drops sharply, which it takes for picture-in-picture. While the window is under half
  the largest outer area seen, the early script reports that largest size instead. If YouTube reads
  another signal (inner size, a media query), picture-in-picture will pause again.
- **Picture-in-picture enters with a flash.** For about half a second the window shows the page at
  its small size, then one black frame, before only the video fills it.
- **Picture-in-picture expands with a flash.** The growing window is empty (white in the light
  theme) for about 0.2 s before the app redraws.
- **Closing picture-in-picture is inferred.** Picture-in-picture ending while the Activity is stopped
  counts as a close, which needs onStop to arrive before the mode change. Verified on Android 16
  (API 36) only.
- **Media buttons after a muted start.** YouTube sometimes starts a video muted ("Tap to unmute"), and
  Android sends headset and Bluetooth buttons only to the app that last played audio. Until the video
  has made sound, those buttons go elsewhere; the notification and lock-screen controls still work.
- **Ad blocking follows YouTube's ad delivery.** The prune keys and blocked paths match what
  m.youtube.com does in November 2026. When YouTube moves ads elsewhere (server-stitched ads, new
  keys), the safety net still mutes and skips them and `ad.leak` in Diagnostics climbs; that count
  is the signal to update `PlayerAdPrune` and `AdBlocklist` from uBlock Origin's lists.
- **No login means no personal subscriptions feed.** Feeds over starred channels replaces it. It
  depends on YouTube's flaky RSS endpoint and, as a fallback, on the mobile videos page's markup;
  Settings > About > Diagnostics counts fallbacks (`feed.rss.fallback`) and unreadable pages
  (`feed.unreadable`).
