# Ad blocking on m.youtube.com

Researched and measured 8 November 2026 on the emulator (Android 16, WebView 133.0.6943.137, Dutch
network, signed out, a fresh identity per run). Question: which uBlock Origin rules apply to
m.youtube.com inside a WebView, and how many ads still show with each of the app's layers?

## Sources

Fetched 8 November 2026 from `raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/`:

- `filters.txt` ("uBlock filters"), the YouTube block at the top of the file:
  - `m.youtube.com,…##+js(set, ytInitialPlayerResponse.playerAds, undefined)`, the same for
    `adPlacements` and `adSlots`, and `playerResponse.adPlacements`. The app's global setter trap.
  - `m.youtube.com,music.youtube.com,…##+js(json-prune, playerResponse.adPlacements
    playerResponse.playerAds playerResponse.adSlots adPlacements playerAds adSlots important)`.
    The app's `JSON.parse` prune and its key list.
  - `…##+js(json-prune, entries.[-].command.reelWatchEndpoint.adClientParams.isAd)` ("Shorts Ad").
    The app drops those Shorts entries.
  - `m.youtube.com##+js(json-edit-fetch-response, ..itemSectionRenderer.contents.*[?.adSlotRenderer],
    propsToMatch, /get_watch)` ("Sponsored"). The app hides `ad-slot-renderer` by CSS instead.
  - `||youtube.com/youtubei/v1/get_watch?$xhr,1p,replace=/"adPlacements"/"no_ads"/`, the same for
    `adSlots`. Confirms `get_watch` carries player ads.
- `quick-fixes.txt`:
  - `www.youtube.com##+js(json-prune-fetch-response, adPlacements adSlots playerResponse.adPlacements
    playerResponse.adSlots [].playerResponse.adPlacements [].playerResponse.adSlots, , propsToMatch,
    /\/player(\?|$)/)`, and the `json-prune-xhr-response` twin. The app's fetch and XHR prune, with
    `[]` (array answers) handled.
  - `m.youtube.com,www.youtube.com,…##+js(json-prune-fetch-response,
    endpoint.watchEndpoint.unserializedPrefetchData.playerResponse.playerAds …, propsToMatch,
    /resolve_url?)`. The app's third container path.
  - The `trusted-*` and `rpnt` rules for `www.youtube.com` (server-stitched ads, `clientScreen`
    rewrites, `onAbnormalityDetected`) are desktop-only countermeasures and are not used.
- EasyList (`easylist.to/easylist/easylist.txt`): `||youtube.com/pagead/`,
  `||youtube.com/youtubei/v1/player/ad_break`, and the m.youtube.com element rules
  `lazy-list > ad-slot-renderer`, `ytm-companion-slot[data-content-type] > ytm-companion-ad-renderer`,
  `ytm-rich-item-renderer > ad-slot-renderer`.
- EasyPrivacy (`easylist.to/easylist/easyprivacy.txt`): `||youtube.com/api/stats/ads?`,
  `||youtube.com/ptracking?…`, `||youtube.com/pcs/activeview?`, `||fcmatch.youtube.com^`.
  `||youtube.com/api/stats/atr` is left out: uBO's quick fixes `badfilter` it.

## What m.youtube.com does (observed over WebView remote debugging)

- The first player response is an inline `var ytInitialPlayerResponse = {…}` (after a
  `var ytInitialPlayerResponse = null` in its own script), then copied to
  `ytplayer.bootstrapPlayerResponse`. In-app navigation fetches `/youtubei/v1/get_watch` with
  `fetch`; its answer is an array `[{playerResponse}, {watchNextResponse}]`.
- With ads served, a player response has `playerAds`, `adPlacements` and `adSlots`. On a 34-minute
  Veritasium video `adPlacements` held 14 ad breaks, each an `adBreakServiceRenderer` whose
  `getAdBreakUrl` is `https://m.youtube.com/get_midroll_info?…`. In the measurements below the
  player fetched its mid-roll ads from `/youtubei/v1/player/ad_break` instead (13 calls blocked,
  none to `/get_midroll_info`), so the blocklist has both. Pruning `adPlacements` stops either.
- Curl with a Chrome UA got no ad keys in the same pages; the WebView's UA does. Ads have to be
  measured in the WebView.
- Ad requests seen during one playback: `googleads.g.doubleclick.net/pagead/id`,
  `static.doubleclick.net/instream/ad_status.js`, `pagead2.googlesyndication.com/ytcs/adview`,
  `www.youtube.com/pagead/viewthroughconversion/…`, `www.google.com/pagead/1p-user-list/…` and the
  same on `www.google.nl`, `m.youtube.com/ptracking`, `m.youtube.com/api/stats/atr`.
- Display ads: a `ytm-promoted-sparkles-web-renderer` card under the player, `ad-slot-renderer`
  items in the watch-next list.
- The progress bar is `yt-chaptered-progress-bar-line` (absolutely positioned, 3 px), inside
  `ytm-custom-control`, outside `#movie_player`. A `::after` gradient from a page stylesheet marks
  SponsorBlock segments on it without touching YouTube's elements.

## Measurements

Ten loads per configuration, the same ten videos each time: six over 20 minutes (66, 48, 35, 34,
33 and 22 min), one of 17 minutes, and three music videos of 3 to 5 minutes. Each run started a
fresh app process (a new identity) with the settings file written before launch and history
cleared. Per video: load, start playback, watch 15 s; videos over 20 minutes were then sought to
45% and to 75% with 15 s of playback after each, to cross mid-roll cue points. A script over
WebView remote debugging polled `#movie_player` for `ad-showing` / `ad-interrupting` every 250 ms;
`ad.leak` is the app's own count from the page script. Raw data: `m_*.jsonl` and `m_*.logcat` in
the session scratchpad, not committed.

| Layers on | Ads shown | Loads with an ad | Responses with ad placements | Display ads visible | Requests blocked | Played |
|---|---|---|---|---|---|---|
| none | 2 (mid-rolls, about 58 s and 30 s) | 2/10 | 10/10 (1 to 33 breaks) | 7/10 | 0 | 10/10 |
| Video ads only | 0 | 0/10 | 0/10 | 2/10 | 0 | 10/10 |
| Ad servers only | 0 | 0/10 | 10/10 | 3/10 | 78 | 10/10 |
| Skip leaked ads only | 7 | 6/10 | 10/10 | 2/10 | 0 | 10/10 |
| all four (default) | 0 | 0/10 | 0/10 | 0/10 | 63 | 10/10 |

What the numbers say:

- No pre-roll showed in any of the 50 loads, with or without blocking. Every ad seen was a mid-roll
  after a seek on a long video.
- Either the prune or the network block alone took ads to zero over ten loads. With both on, the
  player never learns of an ad break and never asks for one.
- Display ads (the "sparkles" card under the player, `ad-slot-renderer` items) showed in 2 to 7 of
  10 loads whenever the display layer was off, and in none with it on. The count varies with what
  YouTube serves, not with the other layers.
- Ad delivery to a fresh signed-out identity varies a lot: 2 ads in the first run with nothing on,
  7 in the safety-net run. Ten loads per configuration can show that a layer stops ads; it cannot
  rank two layers that both reach zero.
- **The first safety net (seek to the ad's end) did not end ads fast.** It muted all 7 and counted
  them, but each still held `ad-showing` for about 20 s (78 polls), against 30 to 58 s with nothing
  on. Behind the other two layers it never had to act.

The blocked requests were: `googleads.g.doubleclick.net/pagead/id` (20 in the network-only run),
`m.youtube.com/youtubei/v1/player/ad_break` (13), and 10 each of `www.youtube.com/pagead/`,
`www.google.com/pagead/`, `static.doubleclick.net/instream/ad_status.js` and `m.youtube.com/ptracking`;
then `pagead2.googlesyndication.com/ytcs/adview` (3) and `tpc.googlesyndication.com/simgad` (2).

## Safety net probe

Caught live mid-rolls over remote debugging, sampling the player every 0.5 s:

- Mid-rolls come in pods ("Sponsored 1 of 2"), each ad its own media (6 s, 10 s, 15 s, 20 s) in the
  same `<video>`, and `getPlayerResponse()` still describes the main video during them.
- **Why seeking failed:** setting `currentTime` to the ad's duration does end its media at once
  (`t = 20.02 of 20.02`, `ended-mode`), but the player keeps `ad-showing` for about 10 s more per ad.
  Its ad state machine runs on its own clock, not on the media's `ended`, so a two-ad pod still took
  about 20 s.
- **Skip button:** `button.ytp-ad-skip-button-modern` with the text "Skip". It is in the DOM from the
  ad's start but has zero size until about 5.5 s in, and only on skippable ads.
- **What works:** `playbackRate = 16` (Chromium's highest). Done by hand on a 20 s + 15 s pod, the
  pod was over in 3.8 s, and the player put the main video back at rate 1 itself. The safety net
  now mutes, plays the ad at 16x, presses the skip button once it shows, and restores the viewer's
  mute state and speed afterwards.
- **Measured with only Skip leaked ads on:** ten loads gave 1 ad, gone in about 2 to 3 s (8 polls).
  Three more caught by hand took 4.8 s, 19.5 s and an unknown time (the emulator crashed during the
  third). So 16x shortens most leaked ads to a few seconds, but one of four still ran 19.5 s, and
  four ads is too few to call it reliable. The setting keeps saying it "tries to skip" an ad.
