# WebView fresh-start and anonymization

Research for YourTube: start the `m.youtube.com` WebView fresh on every launch and anonymize the
client as far as is reasonable, with no login. Audience is the engineer implementing it. Findings
below were gathered by a multi-agent deep-research pass and adversarially verified (21 of 25 checked
claims confirmed 3-0; 4 refuted). Primary sources are Android developer docs, androidx.webkit, OWASP
MASTG, and vendor issue trackers.

## Verdict

- **Cleanest per-launch wipe:** androidx.webkit multi-profile. Create a named `Profile` and tear it
  down with `ProfileStore.deleteProfile(name)`, which wipes that session's cookies, web storage,
  service workers, HTTP cache and geolocation in one call. Keep the classic API set as a fallback
  for devices whose WebView provider lacks the feature.
- **Fingerprinting** can be meaningfully reduced but never eliminated from inside a WebView. Patch
  the most-queried surfaces with JS injected before any page script, and keep prototype chains
  consistent or the spoof is itself a tell.
- **IP anonymity is out of scope** for the app. WebRTC can leak IPs and WebView gives no API to gate
  it; true IP hiding belongs to the OS/VPN/Tor layer. On GrapheneOS that is the user's call.
- **Tradeoff:** a fully fresh, spoofed session will hit YouTube's consent interstitial on most
  launches and occasional bot checks. The sweet spot is: wipe state, normalize a small set of
  surfaces, neutralize WebRTC where possible, pre-seed the consent cookie, and keep our own
  first-party state (history, resume) native so the churn stays invisible to the user.

## 1. Fresh start / state wiping

**Classic API set (fallback, works everywhere):**
- `CookieManager.getInstance().removeAllCookies(null)` then `flush()` for cookies (API 21+, null
  callback is safe off any thread).
- `WebView.clearCache(true)` wipes disk + RAM resource cache for all WebViews in the app (`false` is
  RAM only).
- `WebStorage.getInstance().deleteAllData()` clears Web SQL and HTML5 Web Storage (local/session).
  Note: the docs do not claim it covers IndexedDB or service workers.
- `clearFormData()`, `clearHistory()`, `clearHttpAuthUsernamePassword()`.

These are **scoped narrowly and insufficient alone**. Production privacy browsers (DuckDuckGo PR
#516, Firefox Focus) additionally recursively delete the on-disk WebView data directory
(`app_webview` under `applicationInfo.dataDir`), excluding the `Cookies` file (owned by
`CookieManager`) and the Chromium disk-cache dir, or WebView stops using the disk cache. WebView
on-disk artifacts include `app_webview`, `Cookies`, `pref_store`, `blob_storage`, `Session Storage`,
`Web Data`, and `Service Worker`.

**Modern androidx.webkit path (preferred when available):**
- `Profile` (added webkit 1.9.0) is one isolated browsing session with its own cookies, web storage,
  service workers, HTTP cache and geolocation. `ProfileStore.getOrCreateProfile(name)` /
  `getProfile(name)` / `deleteProfile(name)` manage lifecycle. `deleteProfile` is a hard teardown;
  afterwards the profile's accessors throw `IllegalStateException`.
- `WebStorageCompat.deleteBrowsingData()` (added webkit 1.13.0) deletes **all** website-stored data
  in a `WebStorage` instance in one call: network cache, cookies, and any JS-readable storage
  (localStorage/IndexedDB etc.). Broader than the legacy APIs.

**Gating and caveats (must handle):**
- All multi-profile APIs are gated behind `WebViewFeature.MULTI_PROFILE`; `getHttpCache()` also needs
  `HTTP_CACHE_MANAGER`; `deleteBrowsingData` needs `DELETE_BROWSING_DATA`. Every call must be guarded
  by `WebViewFeature.isFeatureSupported(...)` or it throws `UnsupportedOperationException`.
  Availability depends on the installed WebView provider version, so **ship both paths**.
- `deleteProfile` throws if a living WebView is still attached to that profile or if the profile was
  loaded this session, and throws for the default profile. Deletion is **async and non-atomic**: data
  is not guaranteed off disk by the time the call returns.
- `ProcessGlobalConfig.setDataDirectorySuffix()` (webkit 1.6.0) can point WebView at a different
  data/cache dir per process using a fresh random suffix at cold start. It is a workaround, not a
  true ephemeral profile: `apply()` runs once per process before WebView loads, cannot change on a
  warm start, and a new suffix each launch **orphans old dirs** with no auto-cleanup.

**Recommended pattern:** at cold start, if `MULTI_PROFILE` is supported, create a fresh per-launch
`Profile`, attach the WebView to it, and `deleteProfile` the previous launch's profile. If not
supported, run the classic wipe set plus recursive `app_webview` deletion (with the documented
exclusions). Because deletion is async, do the wipe/teardown of the prior session at startup before
the first `loadUrl`, not on exit.

## 2. Fingerprinting surfaces

Inject anti-fingerprinting JS **before any site script runs** via
`WebViewCompat.addDocumentStartJavaScript` (the WebView equivalent of CDP
`addScriptToEvaluateOnNewDocument`). A spoof registered after page scripts can be detected or
overwritten first.

Keep it **consistent**: detection scripts (e.g. CreepJS) check `navigator.webdriver` prototype-chain
placement, patched-getter tells (`Function.prototype.toString`, `function.name`/`length`, descriptor
flags), and `navigator.platform` vs `userAgent` mismatches. Defining a property on the instance
instead of on the prototype is itself detectable, so patch on the prototype with native-looking
getters.

Worth touching (low breakage, real signal): normalize `User-Agent` to a common mobile value, mask
`navigator.webdriver`, keep `platform`/UA/Client-Hints internally consistent, trim obviously
automated tells. For canvas/WebGL/AudioContext, **noise injection beats blocking**: blanket blocking
breaks much of the web and paradoxically makes the client more identifiable. Which specific surfaces
matter for YouTube (vs break playback) was not established and needs a device/emulator spike.

## 3. Network / IP

Out of scope for the WebView. The WebRTC local-IP leak cannot be blocked from stock WebView:
`WebChromeClient`'s permission callback fires only for audio/video access, not for ICE-candidate
gathering, and stock WebView exposes no WebRTC toggle. In-WebView mitigations are limited to
JS-gating/disabling WebRTC (only works with JS enabled) or blocking STUN. Blocking the local-IP leak
only hides LAN addresses, not the public IP, which any server sees anyway. Even Tor/Orbot can leak
via WebRTC. Modern Chromium does mask LAN IPs via mDNS `.local` hostnames by default, which softens
the local-IP concern. Verdict: do not attempt IP anonymization in the app; leave it to the OS network
layer (VPN/Tor), which fits the GrapheneOS context.

## 4. Tradeoffs

A fully fresh + spoofed session on `m.youtube.com`:
- Hits the EU/consent interstitial on most launches unless a consent cookie is pre-seeded. NewPipe's
  approach is to set a `CONSENT`/`SOCS` cookie with a pending-accept value to skip the redirect.
- Loses YouTube's own resume and quality prefs each launch (they live in cookies/localStorage we
  wipe). Mitigated by keeping our own resume native (see below).
- Risks occasional bot/captcha challenges; the exact frequency is unverified and must be measured.

Pragmatic sweet spot: wipe per launch, pre-seed the consent cookie right after the wipe, normalize a
small set of fingerprint surfaces, neutralize WebRTC where possible, and stop there. Do not chase
perfect anonymity at the cost of a usable app.

## 5. Recommended implementation (priority order)

1. **Per-launch wipe.** `MULTI_PROFILE` path (fresh `Profile` + delete previous) with the classic
   wipe + `app_webview` recursive delete as fallback. Guard every androidx call with
   `isFeatureSupported`. Run at cold start before first load.
2. **Pre-seed consent** cookie immediately after the wipe so the interstitial does not appear every
   launch.
3. **First-party state stays native.** Move resume out of YouTube `localStorage` into the native
   `Store`, keyed by video id; keep history recording from the watch bridge. This makes the wipe
   invisible to the user: history keeps filling and videos resume where they left off regardless of
   session identity.
4. **Fingerprint hardening** via `addDocumentStartJavaScript`: UA normalization, `navigator.webdriver`
   mask, consistent platform/UA/Client-Hints, prototype-safe patches; canvas/WebGL/audio noise only
   if the spike shows it does not break playback.
5. **WebRTC**: gate/disable via JS where feasible; otherwise document it as an OS-layer concern.
6. **Reroll ("new identity")**: expose the same wipe+rotate as an on-demand action (the optional
   mask button) that then reloads the current URL under the fresh identity.

### Spikes to confirm on a device/emulator

- Does `deleteProfile` / `deleteBrowsingData` actually remove IndexedDB and service-worker data from
  disk, given async non-atomic deletion?
- How often does a fresh + spoofed session trigger consent/bot friction on `m.youtube.com`, and which
  subset of fingerprint spoofing stays under YouTube's bot threshold without breaking playback?
- Realistic minimum WebView provider version on the target GrapheneOS devices: will `MULTI_PROFILE` /
  `DELETE_BROWSING_DATA` actually be available, or will the app mostly run the classic fallback?
- Profile create-then-delete vs rotating `setDataDirectorySuffix`, and how to garbage-collect orphaned
  data dirs if the suffix approach is used.

## Key sources

- Android `CookieManager`, `WebStorage`, OWASP MASTG WebView cleanup test.
- androidx.webkit `Profile`, `ProfileStore`, `WebStorageCompat`, `ProcessGlobalConfig`.
- DuckDuckGo Android PR #516 (classic wipe orchestration), issue #429 (WebRTC leak).
- Stoutner Privacy Browser #62 (no WebView WebRTC toggle); uBlock Origin WebRTC wiki.
- Anti-fingerprinting references on inject-before-scripts and consistency tells.
