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
| Fullscreen video | partial | WebChromeClient custom-view handling; rotation/immersive polish pending |
| Resume where you left off | done | each history entry keeps its playback position in the native store (last 2000 videos), so it survives identity changes. Rewinds 5 s; skips videos under 5 s in or within 15 s of the end; a `t=` URL parameter wins. Saved every 10 s while playing and on pause, end and leaving the app. |
| Fresh identity every launch | done | each process starts with an empty WebView data directory; each new WebView (cold Activity start, renderer crash) gets a new WebView profile, and the old one is wiped once its WebView is destroyed. WebViews without profile support restart the app process instead. `SOCS` (reject all) and `PREF` (theme, language, region) are set before the first request, so no consent page. |
| Launch behavior | done | a cold launch opens Feeds (Library without subs) and loads nothing; the Watch tab loads YouTube home when empty; after process death the last URL reloads. Config changes (rotation, theme, PiP size) never recreate the screen. |
| Renderer crash recovery | done | the WebView is replaced under a new identity and the page reloads and resumes. |
| Links leaving YouTube | done | YouTube, its consent page and the `google.com/sorry` captcha stay in the app; every other link opens in the browser or the matching app. |
| Ad hiding | partial | CSS hides common ad elements only. No network/manifest-level ad blocking (which is what removes mid-roll ad breaks). See backlog. |
| Background playback | partial | foreground service + Page-Visibility guard keep audio going when backgrounded. No lock-screen / notification transport controls yet (needs MediaSession). |
| Back navigation | done | predictive back throughout: Watch goes back through the page's history, a native tab goes back to Watch once it has something, Settings and search close (Settings shrinks toward the swiped edge as you drag), and at the root the system back-to-home animation plays. |
| App shell (Material 3 Expressive) | done | Expressive theme and motion, wallpaper colors (Android 12+) or a red fallback palette, light and dark, edge to edge with no launch flash. Bottom navigation (Watch, Feeds, Library, History) with fade-through tab motion. The WebView stays alive under the native tabs. |
| Watch top bar | done | back (when the page can go back), star, search, YouTube home, and an overflow with Share (system share sheet, `www.youtube.com` link) and Settings. No title; its color follows YouTube's own header (`#0f0f0f` on watch pages and in dark theme, white otherwise), so bar and page read as one surface. |
| YouTube's own chrome hidden | done | CSS hides YouTube's header, pivot bar, sign-in and Premium nags and app promos, and moves the player and comments panel up into the freed space. YouTube's search UI comes back while its in-page search box is in use. |
| Native search | done | a search action on every tab opens a full-screen field (prefilled and selected on a results page) and loads `m.youtube.com/results` in Watch. |
| Load progress and errors | done | a thin progress line under the Watch bar while a page loads; a failed main-frame load shows a native "You're offline" or "This page didn't load" state with Retry instead of Chromium's error page. |
| Theme sync with YouTube | done | switching the system theme while the app runs rewrites YouTube's `PREF` theme flag (keeping YouTube's other flags) and reloads the page, unless a video is playing; then the next page load picks it up. The WebView background follows the theme. |
| Now-playing bar | partial | above the navigation bar on native tabs while Watch shows a video: still, title, channel and a progress line that waves while playing; tap returns to Watch. Play/pause arrives with background audio (stage 7). |
| Settings | partial | from the overflow menu. Content (Hide Shorts), Appearance (dynamic color) and About (app and WebView versions, Diagnostics counts). Playback, Feed, Library and Privacy appear when their settings land. |
| Accessibility baseline | done | content and state descriptions (star "Starred" / "Not starred", watched progress on thumbnails, switches), 48 dp targets, TalkBack skips whatever lies under an open tab or Settings, layouts hold at font scale 1.3. |

## Next milestone, the login-free NouTube features the user asked for

These are the priority after v0 compiles and runs. All local, no login.

| Feature | Status | Plan |
|---------|--------|------|
| **Star channels and videos (library)** | partial | star in the Watch top bar. What gets starred comes from the URL plus the player's own data, so it stays right after in-page navigation. `/@handle` channels are resolved to their channel id and avatar in the background (cached 30 days). Tag sheet and Library redesign come in stage 4. |
| **Watch history** | partial | every played video is recorded (last 2000) with title, channel, length badge and a watched bar; finished videos are dimmed. Clear all has Undo. Day grouping and swipe to remove come in stage 4. |
| **RSS feed reader for channels** | partial | fetches `https://www.youtube.com/feeds/videos.xml?channel_id=...` (anonymous, no login) for every starred channel and lists the newest uploads with watched bars. YouTube's feed endpoint answers 404 for many requests in a row (seen November 2026), so a refresh can come back empty; per-channel retry, paging and the new-videos pill come in stage 5. |
| **Tags for the library** | partial | replaces folders. The store has tags (create, attach, delete with cascade); the UI comes in stage 4. |
| **Hide shorts** | partial | Settings > Content > Hide Shorts hides Shorts shelves and links by CSS. The full "remove all Shorts" (redirects, Feeds filtering) comes in stage 6. |
| App chrome (top bar, drawer) | done | see the shell rows above. |
| Settings + import/export | partial | Settings screen exists (see above); JSON export/import comes in stage 4. |

## Later backlog (from NouTube, not yet scoped)

| Feature | Status | Notes |
|---------|--------|-------|
| Network-level ad blocking (mid-roll removal) | todo | hardest ad piece; needs request interception or a SponsorBlock-style approach. |
| SponsorBlock (skip sponsored segments) | todo | query the SponsorBlock API by video id, seek past segments from `inject.js`. |
| Lock-screen / notification playback controls | todo | MediaSession wired to the WebView's `<video>`; upgrades background playback from "keeps going" to "controllable". |
| Picture-in-picture | todo | Android PiP for the player surface. |
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

- **Background audio in a WebView is fragile.** The visibility guard + foreground service is a
  best-effort approach; some YouTube changes or Android OEM power management can still pause it.
  A MediaSession bound to the `<video>` is the robust fix (in the backlog).
- **Ad hiding by CSS does not remove the ad video itself.** Real ad-free playback needs more than
  hiding elements.
- **No login means no personal subscriptions feed.** The RSS reader over starred channels is the
  intended replacement.
