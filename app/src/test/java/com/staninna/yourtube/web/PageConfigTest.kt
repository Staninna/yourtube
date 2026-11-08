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
}
