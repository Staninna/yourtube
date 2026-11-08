# YourTube v-next plan (god-tier), revision 3

Status: revision 3, final before build. Folds in a self-review and two rounds of technical and
product/UX review. Anonymization follows `docs/research/webview-anonymization.md`. Every stage is
held to `docs/CODE_QUALITY.md`.

## Context

YourTube is a login-free, de-Googled YouTube client: a Kotlin + Jetpack Compose shell around a
WebView of `m.youtube.com`. v0.1.0 shipped the shell, resume, ad/shorts hiding, a local
library/history/feeds store, and a signed release pipeline. Goals came out of a grilling session
with the user.

## Goals (decided with the user)

1. Rebuild the fragile core so star / library / feeds are reliable.
2. Rename: label "YourTube", `applicationId` + `namespace` + package `com.staninna.yourtube`.
3. Tags replace folders: one shared pool, many tags per item, created inline, managed in Settings.
4. Library tab: segmented `Subs | Videos | Playlists`, tag-chip filter, channel avatars.
5. Context star with an instant tag sheet.
6. Feeds: all subs by default, tag chips, page size 25/50/100/250 (default 50), Load more.
7. Background audio with lockscreen/notification controls.
8. PiP: default auto-enter on any playback when leaving; Settings can restrict to fullscreen-only
   or manual.
9. Fresh WebView identity every launch, anonymized as far as reasonable.
10. Material 3 Expressive across the native shell; Settings via a top-bar overflow menu.
11. Optional reroll "mask" button next to Home: new identity on demand.

## Core principle: first-party state is ours

History, resume positions, library, tags and settings live in the native `Store` and never change
when the identity changes. Identity changes only touch YouTube/Chromium session state. The aim is
anonymization the user barely notices: fresh anonymous sessions, while history fills, videos resume,
player preferences carry over, and the library is always there.

## Non-goals

No login, account, cloud sync, YouTube Music, native browse/home screens (issue #1), or IP-level
anonymization (OS/VPN layer).

## Decisions

### D1. Fresh install

`com.staninna.yourtube` is a new app: the `com.staninna.tube` install is not upgraded and its data
is unreadable (user's informed choice; v0.1.0-1 has one install, one day old). Release notes say to
uninstall the old app and re-add the repo in Obtainium. No folder migration code. Every store file
carries `schemaVersion`; future transforms read raw JSON first. JSON export/import (stage 4) makes
future moves painless.

### D2. Lifetimes

- **The WebView lives exactly as long as `MainActivity`; an identity lives as long as its WebView.**
  `WebViewHost` is Activity-scoped. No process-level WebView, no context swapping.
- All config changes are absorbed in the manifest (`screenSize|smallestScreenSize|screenLayout|
  orientation|density|fontScale|locale|layoutDirection|keyboard|keyboardHidden|navigation|uiMode`),
  so PiP, split-screen, rotation and theme changes never recreate the Activity.
- A cold Activity creation is a launch: new identity, start destination is **Feeds** (Library when
  there are no subs). Signed-out YouTube home is typically empty ("Try searching"), so Home is not
  the start screen; this is confirmed in the stage 0 spike.
- Activity recreated after process death (`savedInstanceState != null`): new identity, reload the
  last URL; native resume restores the position. The user keeps their place.
- Renderer crash (`onRenderProcessGone`, returns true): recreate the WebView (new identity), reload
  the URL, resume.

### D3. `WebViewHost` owns the WebView

Activity-scoped owner of the WebView and everything bound to it: profile, document-start scripts,
the message bridge, navigation policy, observable nav state (`canGoBack`, URL, progress, load
error), and a `generation` counter. Compose renders
`key(generation) { AndroidView(factory = { host.create(it) }, onRelease = { it.destroy() }) }`,
so creation and destruction follow composition. A process-level `PlaybackHub` holds a nullable
reference the Activity registers and clears; the media session reaches the WebView only through it.

### D4. Leaving the app while playing (pure function, unit-tested)

| Situation | Result |
|---|---|
| Playing, video visible, PiP allowed by setting, device supports PiP | Enter PiP. The media session drives PiP's play/pause. |
| User closes the PiP window or swipes the task away | Playback pauses and the session is released. Closing PiP only stops the Activity (stage 0 finding e), so "PiP ended while the Activity is stopped" is detected and treated as close. |
| Playing, PiP not allowed / not supported / Manual | Background audio with the media notification. |
| Screen off while in the app | Background audio. |
| Paused when leaving | Session kept in the notification for the grace period, then released. |
| Switching to a native tab while playing | Keeps playing; now-playing bar shows on native tabs. |
| Back at the WebView root while playing | `moveTaskToBack` instead of finish. |
| Returning to the app | Exit PiP; same WebView and session continue. |

The foreground service stays foreground through pauses and video transitions (autoplay) for a
grace period (10 min), then stops. It is only ever started while the Activity is visible.

## Architecture

### Store

- One serialized writer; every mutation via `MutableStateFlow.update {}`; `AtomicFile` writes;
  missing vs corrupt read (corrupt file moved to `.corrupt`, defaults used, one-time snackbar with an
  export action); `schemaVersion`.
- Resume and history progress update in memory per report and persist debounced (10 s while
  playing, plus pause, end, background, destroy). Resume map cap 2000, history cap 2000.
- Resume rules carried over: rewind 5 s, skip if under 5 s in or within 15 s of the end, honor `t=`.
- Clearing history also clears resume positions, with Undo.

### Bridge and injection

- `WebViewCompat.addWebMessageListener` (`WEB_MESSAGE_LISTENER`) restricted to YouTube origins,
  accepting only main-frame messages; delivered on the main thread. Fallback: `addJavascriptInterface`
  with the same message schema, posted to the main looper.
- Messages are typed (`PageReport`, `PlaybackEvent`, `ResumeRequest`), decoded once at the edge.
  Resume is async: on `loadedmetadata` the page posts a request, native replies via the reply proxy.
- Document start (`DOCUMENT_START_SCRIPT`), split into: (a) a tiny DOM-safe early script (background
  guard, CSS with current settings baked into the script string, pref re-seed); (b) the main script,
  which waits for `DOMContentLoaded`. Scripts are re-registered (`ScriptHandler.remove()` + add) when
  settings change. Fallback: inject on page start/finish (and skip the fingerprint script there).
- No interval timers. Navigation comes from a `pushState`/`replaceState`/`popstate` hook plus
  `state-navigateend` on `window` (the only YouTube navigation event m.youtube.com fires, per stage 0);
  playback from media events (`play`, `pause`, `ended`, `timeupdate`,
  `loadedmetadata`, `emptied`).
- Navigation policy: YouTube origins, Google consent, and `www.google.com/sorry` (bot-check captcha)
  load in-app; everything else opens externally.

### Page identity

- `PageIdentity` in Kotlin, unit-tested: derives type/id/channelId from the URL plus raw facts.
- Video facts come from `movie_player.getPlayerResponse().videoDetails` (the only current source of
  the channel id after SPA navigation, per stage 0), accepted only when their video id equals the
  URL's `v`. Every `/@handle` page needs `ChannelResolver`, whose HTTP fetch sends `SOCS`. `og:*`/canonical are accepted only when they match
  the current URL. `ytInitialData` is never used after the first load.
- Channel id and avatar from a single native `ChannelResolver`: HTTP GET of the mobile channel page
  (mobile UA + consent cookie), read the canonical `UC` id and `og:image`; bounded concurrency (2),
  cached per channel, refreshed after 30 days. Used for `/@handle` resolution, star targets, and
  avatar backfill. The star shows a loading state while resolving and times out to disabled.
- Each report carries the URL it describes; reports for a URL that is no longer current are dropped.
- Markup-drift logging when a page that should yield identity yields none (Settings > About >
  Diagnostics shows counts).

### Tags

- `Tag(id, name, color)`; `StarredItem.tagIds: Set<String>`.
- Ops: create inline (auto color), attach/detach, rename, recolor, delete (cascades in one write,
  items stay starred). Destructive ops get Undo.
- A starred channel is a sub; Feeds is every starred channel.
- Library chips show tags present in the active segment; multi-select OR; selection kept per segment
  in memory; if the active chip disappears the view falls back to all. Default sort: recently added;
  search field appears past 30 items.

### Star and tag sheet

- Unstarred page: star immediately (zero tags fine), open the sheet: tag chips, inline "New tag",
  and on video pages a separate "Channel" section with "Subscribe to <channel>".
- Already starred: the sheet opens with current tags; explicit Unstar (with Undo).
- Dismiss keeps the star and tags. Library rows open the same sheet on long-press.

### Feeds

- Client-side paging over the merged RSS set (RSS has ~15 per channel, no cursor).
- First load renders progressively; afterwards the cached list shows instantly and fresh results
  collect behind a "N new videos" pill that inserts on tap. Revealed count survives refreshes.
- Fetch concurrency 6; per-channel failures shown with retry; adding a sub fetches just that channel;
  pull-to-refresh; flags reset in `finally`. Hide-shorts filters `/shorts/` entries.
- Page size from Settings; Load more until exhausted. States: first load, refreshing, empty,
  filtered-empty, offline, partial failure.

### Playback

- media3: `SimpleBasePlayer` adapter whose state comes from `PlaybackEvent`s and whose commands go to
  the WebView via `PlaybackHub`; `MediaSessionService` provides the notification, foreground
  promotion, and system controls. Position/speed set on state changes and seeks only.
- PiP: params kept current while playing (`setAutoEnterEnabled` per D4, aspect ratio clamped,
  `sourceRectHint` from the video rect converted from CSS px). On entering PiP hide Compose chrome and
  inject video-fill CSS; restore on exit. Play/pause in PiP comes from the media session.
- Stage 0 amendments: Chromium pauses media when the WebView's window becomes invisible (Home,
  screen off), so the WebView subclass overrides `onWindowVisibilityChanged` to always report
  visible while background playback is allowed. YouTube pauses itself when the window shrinks to PiP
  size, so the early script pins `window.outerWidth`/`outerHeight` to their full-size values; the
  PiP fill CSS raises `#movie_player` and hides YouTube's drawer and top bar instead of using `vh`.
- No notification-permission prompt (media-session notifications are exempt on Android 13+).
- `ACTION_AUDIO_BECOMING_NOISY` pauses; audio focus verified in the spike.
- Fullscreen: immersive (system bars hidden), rotation follows the video's aspect and returns, back
  exits fullscreen, no chrome flash.

### Identity (fresh start and reroll)

- **Profile path** (`MULTI_PROFILE`): at process start, delete every profile from
  `getAllProfileNames()` except `Default`, without loading them. Each new WebView gets a fresh
  profile name, bound with `WebViewCompat.setProfile` as its very first call. A reroll clears the old
  profile (`deleteBrowsingData` on its web storage where supported, `removeAllCookies`) and creates a
  new WebView on a new profile.
- **Fallback path**: `ProcessGlobalConfig.setDataDirectorySuffix(random)` in `Application.onCreate`,
  before anything touches WebView (asserted), then delete other `app_webview_*` dirs. A reroll uses
  `deleteBrowsingData` on `WebStorage.getInstance()` when `DELETE_BROWSING_DATA` is supported;
  otherwise it restarts the process and relaunches into the same URL so the identity is really fresh.
- **Seeding** before the first load (waiting for the cookie callbacks): `SOCS` consent (the
  reject-all value recorded in the stage 0 findings; `SOCS=CAI` also works), `PREF` with
  theme and hl/gl from the device locale, and an allowlist of non-identifying player preferences
  (quality, speed, captions on/off and language, autoplay) snapshotted from the previous identity and
  re-applied by the early script.
- **Recovery**: consent pages get an injected "Reject all"; bot-check pages show a native banner
  "YouTube wants a check. Try a fresh identity" with a reroll action (works even if the mask button is
  hidden). Both counted in Diagnostics.
- **Fingerprint script** (document start only, prototype-safe): normalized UA with matching Client
  Hints via `setUserAgentMetadata` (`USER_AGENT_METADATA`), `navigator.webdriver` masked,
  platform/UA consistent. Canvas/WebGL/audio noise only if it does not hurt playback.
- **Reroll button** (shown by default): new identity, reload current URL, playback auto-resumes if it
  was playing, back goes to the start destination. Snackbar: "New identity. Your library and history
  are untouched."
- Backup rules (`dataExtractionRules` + `fullBackupContent`) exclude WebView data, keep the store.

### Shell and design (Material 3 Expressive)

- Compose BOM / material3 bumped to a version with the Expressive APIs (pinned in stage 0; list of
  components used).
- Theme: dynamic color with fallback, light/dark, full edge-to-edge, predictive back. Watch top bar
  uses YouTube's background color so there is no seam; theme change re-applies `PREF`.
- YouTube's own chrome inside the shell is hidden by CSS (pivot bar, sign-in nags, app promos, its
  header); a native search action loads `/results?search_query=`.
- Watch top bar: back, star, mask, Home, overflow (Share, Settings); no title on Watch.
- Thin load-progress indicator; main-frame load errors show a native error state with Retry.
- Now-playing bar above the navigation bar on native tabs.
- Shared `MediaRow` with progress bar and watched dimming, used by Feeds, Library, History.
- History: grouped by day, swipe to remove, confirmed clear-all with Undo, progress bars.
- Deep links (`youtube.com/watch`, `youtu.be`, `/@handle`, `/channel`) and an `ACTION_SEND` share
  target open in the app.
- Accessibility: state descriptions, content descriptions, 48 dp targets, TalkBack pass.
- Settings: Playback (PiP mode, background audio), Content (ad blocking layers, SponsorBlock
  categories and actions, remove all Shorts), Feed (page size), Appearance (dynamic color), Library
  (Manage tags, import/export), Privacy (show reroll button), About (Diagnostics).

### Content filtering: ads, SponsorBlock, Shorts

Requested after revision 3. All three are configurable in Settings > Content.

- **Ad blocking**, in layers, each switchable:
  - *Player ads*: prune ad data before the player sees it, the way uBlock Origin's `json-prune`
    scriptlets do: strip `adPlacements`, `adSlots`, `playerAds` from `ytInitialPlayerResponse` and from
    `/youtubei/v1/player` responses (patched `JSON.parse` / `fetch` / XHR in the early script).
  - *Network*: `shouldInterceptRequest` returns empty responses for ad and ad-tracking hosts and paths
    (doubleclick, googleadservices, `pagead`, `/api/stats/ads`, `/ptracking`, `/pagead/`), from one
    maintained blocklist file.
  - *Display ads*: the existing CSS hiding (promoted videos, ad slots, companion ads).
  - *Safety net*: if an ad still plays (`.ad-showing` on the player), mute it, jump to its end, and
    click skip; count it in Diagnostics so leaks are visible.
- **SponsorBlock**: segments fetched natively per video with the privacy-preserving hash-prefix API
  (`/api/skipSegments/<first 4 hex of sha256(videoId)>`, so the server never learns the exact video),
  cached per video. Per-category action (skip, mute, show only, off) for sponsor, self-promo,
  interaction reminder, intro, outro, preview, filler, non-music section; defaults: sponsor and
  self-promo skip, the rest show only. A skip shows a native snackbar with Undo. Segments go to the
  page over the bridge; the main script applies them on `timeupdate`. Server URL configurable.
- **Remove all Shorts** (one switch): hides Shorts shelves, tabs, links and results in the WebView,
  rewrites `/shorts/<id>` navigations to `/watch?v=<id>`, and filters Shorts out of Feeds (RSS entries
  whose link is `/shorts/`). Replaces the narrower "hide shorts" setting.

### Import / export (onboarding)

JSON export/import of the whole store; subscription import from a NewPipe export and a Google Takeout
`subscriptions.csv`. Empty Feeds offers "Import subscriptions".

## Stages

Each stage builds, passes unit tests, is verified on the emulator, passes a review against
`docs/CODE_QUALITY.md` by a reviewer who did not write it, updates `docs/FEATURES.md`, and lands as
its own commit.

0. **Spike**: bump dependencies (androidx.webkit, media3, Compose BOM with Expressive); a debug
   feature-probe screen; confirm on the emulator: signed-out home content, inline PiP (CSS fill vs
   auto-enter animation, sourceRectHint), PiP-close behavior, WebView audio focus, `og:*` staleness on
   SPA navigation, `movie_player` data availability. Findings: see "Stage 0 findings" below.
1. **Rename**: label, ids, package; README/FEATURES updated; signed build verified.
2. **Foundation**: `WebViewHost` (D2/D3), identity core (profile/fallback wipe, setProfile-first,
   seeding), message bridge + split document-start scripts, navigation policy, `PageIdentity`,
   `ChannelResolver`, Store rewrite, native resume + history, render-process recovery, config
   changes.
3. **Design system + shell**: theme, components, state components, a11y baseline, YouTube chrome
   hiding, search action, progress/error states, now-playing bar slot, Settings skeleton, Watch top bar.
4. **Library + tags + star + history + import/export**.
5. **Feeds**.
6. **Content filtering**: ad blocking layers, SponsorBlock, remove all Shorts, Settings > Content.
   → **Release 1** (`v0.2.0`).
7. **Background audio** (media3) + fullscreen polish + D4 non-PiP rows.
8. **PiP** + D4 PiP rows.
9. **Anonymization extras**: fingerprint script, UA metadata, consent/bot-check recovery, pref
   carry-over, reroll button, backup rules.
10. **QA pass** → **Release 2** (`v0.3.0`).

**Cut order** if something slips: canvas/WebGL/audio noise; avatar backfill (keep placeholders);
PiP fullscreen-only/manual modes; tag recolor; the 250 page size; Diagnostics UI (keep logging);
Takeout import (keep JSON + NewPipe). If the PiP spike fails, PiP moves to the next release whole.

## Testing and QA

- JVM: Store (writer, atomicity, corrupt file, debounce, caps, tags + cascade + undo), `PageIdentity`
  and stale-report dropping, D4 function, feed merge/paging/new-pill, chip semantics, import parsers.
- Instrumented on the emulator: tab shell, segment and chips, tag sheet, star states, Settings, and a
  `newIdentity()` test that sets a cookie + localStorage, rerolls, and asserts both are gone.
- Manual on the emulator with screen recordings: page identity on real navigation, resume across
  reroll, PiP, background audio, fullscreen, theme sync, consent/bot handling.
- Acceptance checklist per flow with expected results; budgets measured: cold start to first frame,
  launch to playable video.
- The user's phone runs Vanadium; a read-only feature probe there happens only with the user's
  go-ahead, after which the probe build is uninstalled.

## Stage 0 findings

Measured on the emulator (Android 16, API 36, Google APIs image, Android System WebView
133.0.6943.137) over WebView remote debugging, from a Dutch network (YouTube reports `GL=NL`).

### Dependencies

- androidx.webkit `1.18.0`; media3 `media3-session` and `media3-common` `1.11.1`.
- Compose BOM `2026.09.00` (ui `1.12.1`) with material3 pinned to `1.5.0-rc01`. The stable BOM maps
  material3 to `1.4.0`, which ships `@ExperimentalMaterial3ExpressiveApi` but not `ButtonGroup`,
  `FloatingToolbar` or `LoadingIndicator`; `1.5.0-rc01` has them.
- **Contradicts the plan's "compileSdk 36".** Compose `1.12` and material3 `1.5.0-rc01` require
  compileSdk 37 and AGP 9.1 or newer. The build now uses AGP `9.5.1` with built-in Kotlin (the
  `org.jetbrains.kotlin.android` plugin and `kotlinOptions` are gone; JVM target follows
  `compileOptions`), Gradle `9.9.0`, Kotlin `2.3.21` for the compose and serialization plugins,
  compileSdk 37, targetSdk 36, and Robolectric `4.16.1` (needed for SDK 36). `buildConfig` is on for
  the probe. activity, core, lifecycle, coil and kotlinx-serialization are unchanged.

### Feature probe

`WebViewProbe` logs under `YT-PROBE` in debug builds and turns on WebView remote debugging:

```
webview package=com.google.android.webview version=133.0.6943.137
MULTI_PROFILE=true
DELETE_BROWSING_DATA=true
DOCUMENT_START_SCRIPT=true
WEB_MESSAGE_LISTENER=true
USER_AGENT_METADATA=true
HTTP_CACHE_MANAGER=false
STARTUP_FEATURE_SET_DATA_DIRECTORY_SUFFIX=true
```

The probe loads the WebView provider, so it runs in `MainActivity.onCreate`, not in
`Application.onCreate`. Stage 2 must keep it after any `ProcessGlobalConfig` call. Nothing in the plan
needs `HTTP_CACHE_MANAGER`, so its absence on WebView 133 changes nothing.

### Answers

- **a. Signed-out home.** A fresh WebView shows only "Try searching to get started" with an empty
  feed. This confirms D2: Feeds (or Library) stays the start destination.
- **b. Staleness after SPA navigation.** After video to video, `og:url`, `og:title`, `og:image` and
  `ytInitialData` still describe the first video; `link[rel=canonical]` is current.
  `#movie_player` is present. `getVideoData()` returns the current `video_id`, `title` and `author`
  but no channel id. `getPlayerResponse().videoDetails` returns the current `videoId`, `title`,
  `author` and `channelId`. On channel pages reached by SPA, `#movie_player` stays in the DOM with
  the previous video's id and an empty title, and `getPlayerResponse()` is null. Channel to channel
  behaves the same: og tags and `ytInitialData` are stale, canonical is current. The plan's rules
  hold. Two additions for `PageIdentity`: take the channel id only from `getPlayerResponse()`, and on
  `/@handle` pages canonical is the handle URL, not `/channel/UC...`, with no UC id anywhere in the
  page after SPA navigation. So `ChannelResolver` is needed for every handle page, not only for
  backfill.
- **c. Navigation events.** On m.youtube.com SPA navigation fires `state-navigatestart`, then
  `pushState`, then `state-navigateend`, all on `window`. Back fires `state-navigatestart`,
  `replaceState`, `popstate`, `state-navigateend`. `yt-navigate-start`, `yt-navigate-finish`,
  `yt-page-data-updated`, `yt-page-data-fetched` and `yt-player-updated` never fired. The plan's
  "YouTube's navigation events" should name `state-navigateend` on `window`, next to the
  `pushState`/`replaceState`/`popstate` hook.
- **d. Audio focus.** Chromium's `AudioFocusDelegate` holds `AUDIOFOCUS_GAIN` with `USAGE_MEDIA`
  while a video plays audibly; muted autoplay holds no focus. An incoming call (Telecom,
  `GAIN_TRANSIENT`) paused the video, and it resumed by itself when the call ended. Chrome starting
  audio while the app was in front paused the video for good. WebView handles focus itself, so the
  media3 adapter must not request focus as well.
- **e. PiP with inline video.** Auto-enter (`setAutoEnterEnabled(true)`, 16:9) on Home works, and
  with the D2 `configChanges` list the Activity is not recreated. **Contradicts the plan in three
  places.** First, YouTube's player pauses itself in PiP: it compares `outerWidth * outerHeight`
  with the largest screen area it has seen, flags a large drop as picture-in-picture, and on the
  next progress tick pauses any video whose `backgroundable` flag is false, which is always the
  case signed out. Pinning `window.outerWidth` and `outerHeight` to their full-size values before
  entering PiP kept the video playing and rendering in the PiP window. The early script needs that
  pin. Second, the video-fill CSS cannot use `vh` (`100vh` is 0 in the PiP-sized WebView). It needs
  `position:fixed; inset:0; width:100%; height:100%` on the video. It must also raise the player
  container, since `#movie_player` has `z-index:0` and caps the video, and hide
  `.ytmDrawerLayoutContainerDrawerLayout` (a 360 px fixed drawer that peeks into the 228 px
  viewport) and the top bar. Third, D4's row "User closes the PiP window: Activity destroyed" is
  wrong. Closing PiP calls `onStop` and then `onPictureInPictureModeChanged(false)`. The Activity
  stays alive in an invisible background task, the process and foreground service stay up, and
  audio kept playing. D4 needs an explicit rule: PiP mode ending while the Activity is stopped
  means the user closed it, so pause through `PlaybackHub` and release the session. The PiP menu
  shows only settings, expand and close; play/pause has to come from media session actions.
- **f. Consent and bot pages.** No consent page and no bot check appeared in any fresh WebView
  session, and no `SOCS` cookie was set. Three HTTP runs per case show why: YouTube exempts the
  Android WebView user agent (the `Version/4.0` token) from consent. The same requests with a
  Chrome mobile UA get a 302 to `consent.youtube.com` on channel pages and an in-page consent form
  on home and watch pages. With a Chrome UA override in the WebView, the pending cookie before any
  choice was `SOCS=CAAaBgiA1LnXBg`. "Reject all" set
  `SOCS=CAESNQgDEitib3FfaWRlbnRpdHlmcm9udGVuZHVpc2VydmVyXzIwMjYxMTA2LjA3X3AwGgJlbiACGgYIgNS51wY` and
  "Accept all" set
  `SOCS=CAISNQgDEitib3FfaWRlbnRpdHlmcm9udGVuZHVpc2VydmVyXzIwMjYxMTA2LjA3X3AwGgJlbiACGgYIgNS51wY`,
  both on `.youtube.com` with a 13-month expiry. Seeded on a Chrome UA, both full values and the
  short `SOCS=CAI` skip consent; `SOCS=CAE=` does not. Plan changes: seed the reject value. Note in
  stage 8 that normalizing the UA away from the WebView form brings consent back unless `SOCS` is
  seeded. `ChannelResolver`'s plain HTTP GET must send `SOCS`, or channel pages answer with the
  consent redirect.

### Other observations

- **Contradicts the plan's background audio.** In the current build, pressing Home pauses the video
  even with the foreground service running and `document.hidden` spoofed. No page script calls
  `pause()`; Chromium pauses the media when the WebView window goes invisible. Overriding
  `WebView.onWindowVisibilityChanged` to always pass `View.VISIBLE` kept playback going after Home,
  with the screen off, and after closing PiP. Stage 6 needs this override in `WebViewHost`.
- Fresh loads sometimes autoplay muted ("Tap to unmute"). The first watch load played with sound.
- Two mid-roll video ads played during the PiP test, and the current ad hiding did not stop them.
