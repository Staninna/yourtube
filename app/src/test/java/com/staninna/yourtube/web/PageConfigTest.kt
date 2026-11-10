package com.staninna.yourtube.web

import com.staninna.yourtube.data.AdBlocking
import com.staninna.yourtube.data.AdLayer
import com.staninna.yourtube.data.SegmentCategory
import com.staninna.yourtube.data.Settings
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the page scripts are told for each setting, and the player-ads prune rules. */
class PageConfigTest {

    private fun off(vararg layers: AdLayer) = Settings(adBlocking = AdBlocking(layers.toSet()))

    @Test fun everyAdLayerIsOnByDefault() {
        val config = PageConfig.of(Settings())
        assertEquals(PlayerAdPrune.json, config["prune"])
        assertEquals(JsonPrimitive(true), config["adSafetyNet"])
        assertTrue(PageConfig.css(Settings()).contains("ytm-promoted-sparkles-web-renderer"))
    }

    @Test fun eachLayerSwitchesItsOwnPart() {
        assertEquals(JsonNull, PageConfig.of(off(AdLayer.PLAYER))["prune"])
        assertEquals(JsonPrimitive(false), PageConfig.of(off(AdLayer.SAFETY_NET))["adSafetyNet"])
        assertFalse(PageConfig.css(off(AdLayer.DISPLAY)).contains("ad-slot-renderer"))
        // The network layer lives in native code; the page config does not change with it.
        assertEquals(PageConfig.of(Settings()), PageConfig.of(off(AdLayer.NETWORK)))
    }

    @Test fun youTubesSubscribeButtonIsHiddenWhateverTheAdLayers() {
        for (settings in listOf(Settings(), off(*AdLayer.entries.toTypedArray()))) {
            val css = PageConfig.css(settings)
            assertTrue(css.contains("yt-page-header-view-model:has(yt-decorated-avatar-view-model) yt-flexible-actions-view-model{display:none"))
            // Every rule that reaches a page header's action row is the channel's: a playlist's holds Save and Share.
            val headerActions = css.split('{', '}', ',').map { it.trim() }.filter { "yt-flexible-actions-view-model" in it }
            assertEquals(listOf("yt-page-header-view-model:has(yt-decorated-avatar-view-model) yt-flexible-actions-view-model"), headerActions)
            // Hidden but measured: display:none would make YouTube fold the watch page's actions into More.
            // The newer layout's channel bar keeps it the same way.
            assertTrue(css.lines().contains(
                ".slim-subscribe-button,.ytCompactChannelBarViewModelButtons yt-subscribe-button-view-model{visibility:hidden !important}",
            ))
            assertEquals(1, css.lines().count { "yt-subscribe-button-view-model" in it || "slim-subscribe-button" in it })
        }
    }

    @Test fun youTubesLogoTurnsTheAppIconsPurple() {
        val css = PageConfig.css(Settings())
        val home = css.lines().single { it.startsWith(".search-bar-entry-point-top-image{content:url(") }
        // The data URL is percent-encoded: a raw '#' would end it at the fill color.
        assertTrue(home.contains("fill='%237C3AED'"))
        assertFalse(home.substringAfter("url(").substringBefore(") !important").contains('#'))
        assertTrue(css.contains("path[fill=\"#f03\" i],path[fill=\"#ff0033\" i],path[fill=\"#f00\" i],path[fill=\"#ff0000\" i]{fill:#7C3AED !important}"))
        assertTrue(css.contains("--yt-sys-color-baseline--static-brand-red:#7C3AED !important"))
        val bars = css.lines().single { it.startsWith(".ytChapteredProgressBarChapteredPlayerBarChapterSeen,") }
        assertTrue(bars.contains(".ytwThumbnailOverlayResumePlaybackRendererThumbnailOverlayResumePlaybackProgress"))
        assertTrue(bars.endsWith("{background-image:linear-gradient(90deg,#7C3AED 80%,#A855F7) !important}"))
    }

    @Test fun actionsThatNeedAnAccountGoAndShareStays() {
        val hidden = PageConfig.css(Settings()).lines().flatMap { line ->
            if (line.endsWith("{display:none !important}")) line.substringBefore('{').split(',') else emptyList()
        }
        for (selector in listOf(
            "like-button-view-model", "dislike-button-view-model", ".YtmCommentRendererDetails", "ytm-comment-simplebox-renderer",
            "navigation-item-view-model:has(a[href*=\"music.youtube.com\"])",
            "navigation-item-view-model:has(a[href*=\"youtubekids.com\"])",
            "navigation-item-view-model:has(a[href*=\"youtube-create\"])",
            "ytm-compact-channel-renderer .modern-subscribe-button",
            "yt-page-header-view-model .ytFlexibleActionsViewModelAction:has(> toggle-button-view-model)",
            ".fullscreen-action-menu ytm-slim-metadata-toggle-button-renderer",
            ".fullscreen-action-menu ytm-slim-metadata-button-renderer:has(.icon-bookmark)",
            "ytm-media-item .media-item-menu",
        )) {
            assertTrue(selector, selector in hidden)
        }
        // The app has its own home, so YouTube's keeps its compass, voice search and search card.
        for (kept in listOf(".search-bar-entry-point-buttons", "ytm-chip-cloud-chip-renderer.more-drawer", "ytm-feed-nudge-renderer")) {
            assertFalse(kept, hidden.any { kept in it })
        }
        // Share is the action bar's first plain button; only the ones after it go.
        assertTrue(".slim-video-action-bar-actions > button-view-model ~ button-view-model" in hidden)
        assertFalse(hidden.any { it.trim().endsWith("> button-view-model") })
    }

    @Test fun theDisplayLayerLeavesThePlayersAdOverlayAlone() =
        assertFalse(PageConfig.css(Settings()).contains(".video-ads"))

    @Test fun removeShortsReachesBothCssAndScript() {
        assertEquals(JsonPrimitive(true), PageConfig.of(Settings(removeShorts = true))["removeShorts"])
        assertEquals(JsonPrimitive(false), PageConfig.of(Settings())["removeShorts"])
        val css = PageConfig.css(Settings(removeShorts = true))
        for (selector in listOf("grid-shelf-view-model:has(ytm-shorts-lockup-view-model)", "yt-tab-shape[tab-title=\"Shorts\"]")) {
            assertTrue(selector, css.contains(selector))
        }
    }

    @Test fun segmentColorsAreCssHexByApiName() {
        val colors = PageConfig.of(Settings())["segmentColors"]!!.jsonObject
        assertEquals(SegmentCategory.entries.map { it.apiName }.toSet(), colors.keys)
        assertEquals("#00d400", colors["sponsor"]!!.jsonPrimitive.content)
        assertEquals("#0202ed", colors["outro"]!!.jsonPrimitive.content)
    }

    // --- prune rules ---

    @Test fun pruneKeysAreUblockOriginsYouTubeKeys() {
        assertEquals(listOf("adPlacements", "adSlots", "playerAds"), PlayerAdPrune.KEYS)
        val json: JsonObject = PlayerAdPrune.json
        assertEquals(PlayerAdPrune.KEYS, json["keys"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(PlayerAdPrune.CONTAINERS, json["containers"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue("the root object itself", "" in PlayerAdPrune.CONTAINERS)
    }

    @Test fun pruneUrlsAreThePlayerResponsesOnly() {
        val urls = Regex(PlayerAdPrune.URLS)
        for (url in listOf(
            "https://m.youtube.com/youtubei/v1/player?prettyPrint=false",
            "https://m.youtube.com/youtubei/v1/get_watch?prettyPrint=false",
            "https://www.youtube.com/youtubei/v1/navigation/resolve_url",
            "https://m.youtube.com/youtubei/v1/reel/reel_watch_sequence?x",
            "https://m.youtube.com/watch?v=x&pbj=1",
        )) assertTrue(url, urls.containsMatchIn(url))
        for (url in listOf(
            "https://m.youtube.com/youtubei/v1/log_event?alt=json",
            "https://m.youtube.com/youtubei/v1/next",
            "https://m.youtube.com/api/stats/qoe",
            "https://rr1---sn-x.googlevideo.com/videoplayback?youtubei/v1/player",
            "https://example.com/youtubei/v1/player",
        )) assertFalse(url, urls.containsMatchIn(url))
    }

    @Test fun theFillStylesheetGoesToThePageAndSizesWithoutVh() {
        assertEquals(JsonPrimitive(PageConfig.FILL_CSS), PageConfig.of(Settings())["fillCss"])
        assertTrue(PageConfig.FILL_CSS.contains("#movie_player video{position:fixed !important;inset:0 !important"))
        assertTrue(PageConfig.FILL_CSS.contains(".ytmDrawerLayoutContainerDrawerLayout"))
        assertFalse(PageConfig.FILL_CSS.contains("vh"))
        // Only in picture-in-picture: never part of the always-on stylesheet.
        assertFalse(PageConfig.css(Settings()).contains("object-fit"))
    }

    @Test fun signInOnlyButtonsAreStrippedWhateverTheSettings() {
        for (settings in listOf(Settings(), off(*AdLayer.entries.toTypedArray()), Settings(removeShorts = true))) {
            assertEquals(SignInStrip.json, PageConfig.of(settings)["signIn"])
        }
        val rules = SignInStrip.json
        val neutral = rules["neutral"]!!.jsonArray.map { it.jsonPrimitive.content }
        // Actions that keep a button: never neutral, or Share, links and menus would go with it.
        for (kept in listOf("shareEntityEndpoint", "watchEndpoint", "browseEndpoint", "urlEndpoint", "showSheetCommand")) {
            assertFalse(kept, kept in neutral)
        }
        assertTrue("signInEndpoint" in rules["prompts"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun signInUrlsAreEveryInnerTubeCallAndThePagesOnly() {
        val urls = Regex(SignInStrip.URLS)
        for (url in listOf(
            "https://m.youtube.com/youtubei/v1/next?prettyPrint=false",
            "https://m.youtube.com/youtubei/v1/browse",
            "https://m.youtube.com/youtubei/v1/search",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ&pbj=1",
        )) {
            assertTrue(url, urls.containsMatchIn(url))
        }
        for (url in listOf("https://m.youtube.com/s/player/abc/base.js", "https://i.ytimg.com/vi/x/hq.jpg", "https://evil.example/youtubei/v1/next")) {
            assertFalse(url, urls.containsMatchIn(url))
        }
    }
}
