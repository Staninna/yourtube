package com.staninna.yourtube.web

import com.staninna.yourtube.data.AdLayer
import com.staninna.yourtube.data.SegmentCategory
import com.staninna.yourtube.data.Settings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * What the page scripts are told, as one JSON object baked into `early.js` and handed on to `main.js`:
 * - `css`: the app's stylesheet for YouTube pages.
 * - `prune`: [PlayerAdPrune]'s rules while the player-ads layer is on, else null.
 * - `adSafetyNet`: whether `main.js` mutes and skips an ad that still plays.
 * - `removeShorts`: whether `main.js` sends Shorts links to the watch page.
 * - `segmentColors`: CSS colors by SponsorBlock category API name, for the progress bar marks.
 * - `fillCss`: the stylesheet `early.js` turns on in picture-in-picture.
 */
object PageConfig {

    fun of(settings: Settings): JsonObject = buildJsonObject {
        put("css", JsonPrimitive(css(settings)))
        put("fillCss", JsonPrimitive(FILL_CSS))
        put("prune", if (settings.adBlocking.isOn(AdLayer.PLAYER)) PlayerAdPrune.json else JsonNull)
        put("adSafetyNet", JsonPrimitive(settings.adBlocking.isOn(AdLayer.SAFETY_NET)))
        put("removeShorts", JsonPrimitive(settings.removeShorts))
        put("segmentColors", JsonObject(SegmentCategory.entries.associate { it.apiName to JsonPrimitive(cssColor(it.color)) }))
    }

    /** The app's CSS for YouTube pages under [settings]. */
    fun css(settings: Settings): String = buildList {
        // The app is the chrome: YouTube's pivot bar (the app has its navigation bar), header and nags go.
        add("ytm-pivot-bar-renderer{display:none !important}")
        add("ytm-app,#app,[has-pivot-bar=\"true\"] ytm-app{padding-bottom:0 !important}")
        add(HEADER_CSS)
        add(NAG_CSS)
        if (settings.adBlocking.isOn(AdLayer.DISPLAY)) add(DISPLAY_ADS_CSS)
        if (settings.removeShorts) add(SHORTS_CSS)
    }.joinToString("\n")

    /** `#rrggbb` for an opaque ARGB color. */
    internal fun cssColor(argb: Int): String = "#%06x".format(argb and 0xFFFFFF)

    /**
     * YouTube's header (logo, search, "Open App", account) goes: Watch's native top bar replaces it.
     * YouTube lays the page out around a 48 px header with literal offsets, so each one that
     * matters is zeroed: the app's top padding, the sticky player, the comments and description
     * panel under the player, and the scroll anchor. Shorts and fullscreen keep their own layout.
     */
    private val HEADER_CSS = listOf(
        "ytm-mobile-topbar-renderer,#header-bar,.mobile-topbar-header-background{display:none !important}",
        // Except while YouTube's own search is open (home's in-page search box): it lives in the header.
        "#header-bar:has(.mobile-topbar-header[data-mode=\"searching\"]){display:block !important}",
        "ytm-app,ytm-app.sticky-player{padding-top:0 !important}",
        ".player-container,.player-container.sticky-player{top:0 !important}",
        "html.watch-scroll{scroll-padding-top:0 !important}",
        "html.watch-scroll.sticky-player{scroll-padding-top:56.25vw !important}",
        "@media (orientation:portrait){ytm-engagement-panel:not(.is-shorts-style):not(.force-fullscreen)" +
            "{top:56.25vw !important}}",
        "@media (orientation:landscape) and (min-width:931px){ytm-engagement-panel:not(.is-shorts-style)" +
            "{top:0 !important}}",
        ".mweb-phone-metadata-split-view ytm-engagement-panel:not(.is-shorts-style){top:0 !important}",
    ).joinToString("\n")

    /**
     * Picture-in-picture: the video alone, filling the window on black. Sized by `inset:0`, not `vh`.
     * `#movie_player` has `z-index:0`, which caps the video under the page, so the player is raised
     * instead; YouTube's drawer peeks into a window this small, so it goes with the header (stage 0).
     */
    internal val FILL_CSS = listOf(
        "html,body{overflow:hidden !important;background:#000 !important}",
        ".player-container,#player-container-id,#player,#movie_player,#movie_player .html5-video-container" +
            "{position:fixed !important;inset:0 !important;width:100% !important;height:100% !important;" +
            "margin:0 !important;padding:0 !important;transform:none !important;z-index:2147483647 !important;" +
            "background:#000 !important}",
        "#movie_player video{position:fixed !important;inset:0 !important;" +
            "width:100% !important;height:100% !important;object-fit:contain !important;transform:none !important}",
        "#movie_player .player-controls-content,#movie_player .player-controls-background," +
            "#movie_player .ytp-unmute,#movie_player .ytm-autonav-bar,#movie_player .ytp-ad-overlay-container" +
            "{display:none !important}",
        ".ytmDrawerLayoutContainerDrawerLayout,ytm-mobile-topbar-renderer,#header-bar{display:none !important}",
    ).joinToString("\n")

    /** Sign-in prompts, Premium and app-install promos: nothing a signed-out viewer can act on. */
    private val NAG_CSS = listOf(
        "ytm-mealbar-promo-renderer", ".mealbar-promo-renderer", "ytm-upsell-dialog-renderer",
        "ytm-statement-banner-renderer", "ytm-sign-in-promo-renderer", "ytm-account-promo-renderer",
        ".mobile-topbar-header-sign-in-button", "ytm-open-app-button-renderer", "ytm-app-promo-renderer",
        "ytm-inline-survey-renderer", "ytm-brand-video-singleton-renderer",
    ).joinToString(",") + "{display:none !important}"

    /**
     * Display ads in pages: promoted videos and "sparkles" cards (under the player and in results),
     * ad slots in feeds and the watch-next list, and companion ads. EasyList's m.youtube.com rules
     * plus what the live page showed (`docs/research/ad-blocking.md`). The player's own ad overlay
     * (`.video-ads`) stays: the safety net clicks its skip button.
     */
    private val DISPLAY_ADS_CSS = listOf(
        "ytm-promoted-video-renderer", "ytm-promoted-sparkles-web-renderer", "ytm-promoted-sparkles-text-search-renderer",
        "ytm-search-pyv-renderer", "ytm-companion-ad-renderer", "ytm-companion-slot:has(ytm-companion-ad-renderer)",
        ".ad-container", "ytm-player-ad", "ytm-ad-slot-renderer", "ad-slot-renderer",
        "ytm-rich-item-renderer:has(> ad-slot-renderer)", "ytm-rich-grid-slot-renderer:has(ytm-ad-slot-renderer)",
    ).joinToString(",") + "{display:none !important}"

    /**
     * Every Shorts surface seen on m.youtube.com: shelves (`grid-shelf-view-model` in results, the reel
     * shelf on channel and watch pages), single Shorts in results and feeds, the channel's Shorts tab
     * and YouTube's own Shorts tab. Whole items go, so no empty tile is left behind.
     */
    private val SHORTS_CSS = listOf(
        "ytm-reel-shelf-renderer", "ytm-shorts-lockup-view-model",
        "grid-shelf-view-model:has(ytm-shorts-lockup-view-model)",
        "ytm-rich-section-renderer:has(ytm-shorts-lockup-view-model)",
        "ytm-rich-section-renderer:has(ytm-reel-shelf-renderer)",
        "ytm-rich-item-renderer:has(a[href^=\"/shorts/\"])",
        "ytm-video-with-context-renderer:has(a[href^=\"/shorts/\"])",
        "ytm-compact-video-renderer:has(a[href^=\"/shorts/\"])",
        "yt-tab-shape[tab-title=\"Shorts\"]",
        "ytm-pivot-bar-item-renderer:has(.pivot-shorts)",
    ).joinToString(",") + "{display:none !important}"
}

/**
 * The player-ads layer, after uBlock Origin's `json-prune` and `json-prune-fetch-response` rules for
 * YouTube (`docs/research/ad-blocking.md`). `early.js` deletes [KEYS] from every object at a
 * [CONTAINERS] path of any JSON YouTube parses or fetches from [URLS], from an array answer's elements
 * as well, and from the `ytInitialPlayerResponse` global when the page assigns it. It also drops Shorts
 * feed entries that carry [REEL_AD_FLAG].
 */
object PlayerAdPrune {
    /** Where a player response keeps its ads: pre-rolls and mid-roll breaks, ad slots, and the ad overlay. */
    val KEYS = listOf("adPlacements", "adSlots", "playerAds")

    /**
     * Dotted paths to objects that may hold [KEYS]: the player response itself (`/youtubei/v1/player`,
     * the initial global), the `playerResponse` of a watch answer (`/youtubei/v1/get_watch`, an array on
     * m.youtube.com), and the prefetched one in a `resolve_url` answer.
     */
    val CONTAINERS = listOf("", "playerResponse", "endpoint.watchEndpoint.unserializedPrefetchData.playerResponse")

    /** A Shorts sequence entry that is an ad, by the path to its flag. */
    const val REEL_AD_FLAG = "command.reelWatchEndpoint.adClientParams.isAd"

    /** Fetch and XHR answers worth parsing: the ones that carry player responses or Shorts sequences. */
    const val URLS = """^https://(m|www)\.youtube\.com/(youtubei/v1/(player|get_watch|navigation/resolve_url|reel/reel_watch_sequence)|watch\?|playlist\?)"""

    val json: JsonObject = buildJsonObject {
        put("keys", JsonArray(KEYS.map(::JsonPrimitive)))
        put("containers", JsonArray(CONTAINERS.map(::JsonPrimitive)))
        put("reelAdFlag", JsonPrimitive(REEL_AD_FLAG))
        put("urls", JsonPrimitive(URLS))
    }
}
