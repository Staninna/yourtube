package com.staninna.yourtube

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.staninna.yourtube.data.AdBlocking
import com.staninna.yourtube.data.AdLayer
import com.staninna.yourtube.data.Settings
import com.staninna.yourtube.web.PageScripts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

/**
 * The page scripts themselves, in a WebView on a local fixture with a watch-page URL: `early.js` with
 * the config a [Settings] gives, then `main.js` with a stand-in bridge and video element. YouTube's
 * API answers come from [client], so nothing goes online.
 */
class PageScriptsTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var webView: WebView
    private val navigations = CopyOnWriteArrayList<String>()

    private val client = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val body = when (request.url.path) {
                "/youtubei/v1/player" -> PLAYER_RESPONSE
                "/youtubei/v1/get_watch" -> "[{\"playerResponse\":$PLAYER_RESPONSE},{\"watchNextResponse\":{}}]"
                "/youtubei/v1/next" -> PLAYER_RESPONSE // not a player answer: left alone
                else -> return null
            }
            return WebResourceResponse("application/json", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(body.toByteArray()))
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            navigations += request.url.toString()
            return true
        }
    }

    @After fun destroy() = compose.runOnUiThread { if (::webView.isInitialized) webView.destroy() }

    // --- player ads (early.js) ---

    @Test fun playerAdsArePrunedFromTheGlobalParsesFetchesAndXhr() = runBlocking {
        load(Settings(), "<script>var ytInitialPlayerResponse = $PLAYER_RESPONSE</script>")
        assertEquals("[\"videoDetails\"]", eval("JSON.stringify(Object.keys(ytInitialPlayerResponse))"))
        assertEquals(
            "[\"streamingData\"]",
            eval("JSON.stringify(Object.keys(JSON.parse('{\"playerResponse\":{\"adSlots\":[1],\"streamingData\":{}}}').playerResponse))"),
        )
        // A Shorts sequence loses its ad entries.
        assertEquals(
            "1",
            eval("String(JSON.parse('{\"entries\":[{\"command\":{\"reelWatchEndpoint\":{\"adClientParams\":{\"isAd\":true}}}},{\"command\":{}}]}').entries.length)"),
        )
        // Read as text, so only the fetch wrapper can have pruned them.
        assertEquals("false", async("fetch('/youtubei/v1/player').then(function (r) { return r.text() }).then(function (t) { return /adPlacements|adSlots|playerAds/.test(t) })"))
        assertEquals("false", async("fetch('/youtubei/v1/get_watch?prettyPrint=false').then(function (r) { return r.text() }).then(function (t) { return /adPlacements|adSlots|playerAds/.test(t) })"))
        assertEquals("[\"videoDetails\"]", async("fetch('/youtubei/v1/player').then(function (r) { return r.json() }).then(function (j) { return JSON.stringify(Object.keys(j)) })"))
        assertEquals("true", async("fetch('/youtubei/v1/next').then(function (r) { return r.text() }).then(function (t) { return t.indexOf('adPlacements') > 0 })"))
        assertEquals(
            "false",
            async(
                "new Promise(function (done) { var x = new XMLHttpRequest(); x.open('GET', '/youtubei/v1/player');" +
                    " x.onload = function () { done(/adPlacements|adSlots|playerAds/.test(x.responseText)) }; x.send() })",
            ),
        )
    }

    @Test fun onlyEntriesFlaggedAsAdsAreDropped() = runBlocking {
        load(Settings(), "")
        // Entries that are not objects, or whose flag is missing or not true, all stay.
        assertEquals("[\"a\",\"b\",1,{},{\"command\":{\"reelWatchEndpoint\":{\"adClientParams\":{\"isAd\":\"yes\"}}}}]", eval(
            "JSON.stringify(JSON.parse('{\"entries\":[\"a\",\"b\",1,{},{\"command\":{\"reelWatchEndpoint\":{\"adClientParams\":{\"isAd\":\"yes\"}}}}," +
                "{\"command\":{\"reelWatchEndpoint\":{\"adClientParams\":{\"isAd\":true}}}}]}').entries)",
        ))
        assertEquals("[\"x\"]", eval("JSON.stringify(JSON.parse('{\"playerResponse\":\"x\",\"entries\":[\"x\"]}').entries)"))
    }

    @Test fun nothingIsPrunedWhileThePlayerLayerIsOff() = runBlocking {
        load(Settings(adBlocking = AdBlocking(setOf(AdLayer.PLAYER))), "<script>var ytInitialPlayerResponse = $PLAYER_RESPONSE</script>")
        assertEquals("true", eval("String('adPlacements' in ytInitialPlayerResponse)"))
        assertEquals("true", async("fetch('/youtubei/v1/player').then(function (r) { return r.text() }).then(function (t) { return t.indexOf('adPlacements') > 0 })"))
    }

    // --- SponsorBlock (main.js) ---

    @Test fun segmentsSkipOnlyWhenPlayedIntoAndUndoHolds() = runBlocking {
        loadWithMain(Settings())
        eval("fire('loadedmetadata')")
        assertEquals("[\"$VIDEO\"]", eval("JSON.stringify(sent('segments').map(function (m) { return m.videoId }))"))
        eval("receive({type: 'segments', videoId: '$VIDEO', segments: $SEGMENTS})")

        assertEquals("20", eval("play(9.8); play(10.1); String(t)"))
        assertEquals("[\"sponsor\",10,20]", eval("var s = sent('skipped')[0]; JSON.stringify([s.category, s.start, s.end])"))

        // The viewer seeks into the segment: it plays.
        assertEquals("15.3", eval("v.currentTime = 15; play(15.3); String(t)"))

        // Undo seeks back to its start, and it is not skipped again, even after new segments arrive.
        eval("v.currentTime = 5; play(5.2)")
        assertEquals("10", eval("receive({type: 'undoSkip', videoId: '$VIDEO', start: 10}); String(t)"))
        assertEquals("10.3", eval("play(10.3); String(t)"))
        eval("receive({type: 'segments', videoId: '$VIDEO', segments: $SEGMENTS}); v.currentTime = 5; play(5.1)")
        assertEquals("10.2", eval("play(9.9); play(10.2); String(t)"))
        assertEquals("1", eval("String(sent('skipped').length)"))
    }

    @Test fun comingBackToAVideoIsANewPlayback() = runBlocking {
        loadWithMain(Settings())
        val atZero = """[{"start":0,"end":5,"category":"intro","action":"skip"},{"start":10,"end":20,"category":"sponsor","action":"skip"}]"""
        eval("fire('loadedmetadata'); receive({type: 'segments', videoId: '$VIDEO', segments: $atZero})")
        assertEquals("5", eval("play(0.2); String(t)"))
        // Undo the sponsor skip, then play on past it.
        assertEquals("10", eval("play(9.9); play(10.1); receive({type: 'undoSkip', videoId: '$VIDEO', start: 10}); String(t)"))
        eval("play(10.4); play(30)")
        // The same video loads again (A, a channel, A): it starts over, and nothing is asked again.
        eval("t = 0; fire('loadedmetadata')")
        assertEquals("5", eval("play(0.2); String(t)"))
        assertEquals("20", eval("play(9.9); play(10.1); String(t)"))
        assertEquals("1", eval("String(sent('segments').length)"))
    }

    @Test fun muteSegmentsMuteOnlyWhileInside() = runBlocking {
        loadWithMain(Settings())
        eval("fire('loadedmetadata'); receive({type: 'segments', videoId: '$VIDEO', segments: $SEGMENTS}); v.muted = false")
        assertEquals("true", eval("play(29.9); play(30.2); String(v.muted)"))
        assertEquals("false", eval("play(40.5); String(v.muted)"))
        // A viewer who had muted stays muted.
        assertEquals("true", eval("v.currentTime = 25; v.muted = true; play(30.5); play(41); String(v.muted)"))
    }

    @Test fun segmentsAreMarkedOnTheProgressBar() = runBlocking {
        loadWithMain(Settings())
        eval("fire('loadedmetadata'); receive({type: 'segments', videoId: '$VIDEO', segments: $SEGMENTS})")
        val css = eval("[].slice.call(document.adoptedStyleSheets).map(function (s) { return [].slice.call(s.cssRules).map(function (r) { return r.cssText }).join() }).join()")
        assertEquals(true, css.contains("yt-chaptered-progress-bar-line::after"))
        // Sponsor's color, SponsorBlock green, however the browser writes it back.
        assertEquals(css, true, css.contains("rgb(0, 212, 0)") || css.contains("#00d400"))
    }

    // --- ad safety net (main.js) ---

    @Test fun aLeakedAdIsCountedMutedSpedUpAndSkipped() = runBlocking {
        loadWithMain(Settings())
        eval("v.muted = false; v.playbackRate = 1.5; d = 15; t = 0; player.classList.add('ad-showing'); fire('timeupdate')")
        assertEquals("[true,16,true,1]", eval("JSON.stringify([v.muted, v.playbackRate, !!window.adSkipClicked, sent('adLeak').length])"))
        // The ad is over: the viewer's sound and speed come back.
        assertEquals("[false,1.5]", eval("player.classList.remove('ad-showing'); d = 100; fire('timeupdate'); JSON.stringify([v.muted, v.playbackRate])"))
    }

    @Test fun withTheSafetyNetOffALeakIsOnlyCounted() = runBlocking {
        loadWithMain(Settings(adBlocking = AdBlocking(setOf(AdLayer.SAFETY_NET))))
        eval("v.muted = false; d = 15; t = 0; player.classList.add('ad-showing'); fire('timeupdate')")
        assertEquals("[false,1,false,1]", eval("JSON.stringify([v.muted, v.playbackRate, !!window.adSkipClicked, sent('adLeak').length])"))
    }

    // --- Shorts (main.js) ---

    @Test fun shortsLinksOpenAsWatchPagesWhileShortsAreRemoved() = runBlocking {
        loadWithMain(Settings(removeShorts = true))
        eval("document.getElementById('short').click()")
        awaitNavigation()
        assertEquals("https://m.youtube.com/watch?v=rL0qEmTRI-I", navigations.single())
    }

    @Test fun shortsLinksAreLeftAloneOtherwise() = runBlocking {
        loadWithMain(Settings(removeShorts = false))
        eval("document.getElementById('short').click()")
        awaitNavigation()
        assertEquals("https://m.youtube.com/shorts/rL0qEmTRI-I", navigations.single())
    }

    // --- harness ---

    private suspend fun load(settings: Settings, body: String) {
        val early = PageScripts(compose.activity.assets).early(settings)
        val html = "<html><head><script>$early</script></head><body>$body</body></html>"
        onMain {
            webView = WebView(compose.activity).apply {
                this.settings.javaScriptEnabled = true
                webViewClient = client
                loadDataWithBaseURL(WATCH_URL, html, "text/html", "utf-8", null)
            }
        }
        // A new WebView shows about:blank, already "complete", until the fixture commits.
        withTimeout(10_000) { while (eval("location.href + '|' + document.readyState") != "$WATCH_URL|complete") delay(50) }
    }

    /** The fixture for `main.js`: a stand-in bridge, a player with a video whose clock the test moves, and a Short link. */
    private suspend fun loadWithMain(settings: Settings) {
        val main = compose.activity.assets.open("main.js").bufferedReader().use { it.readText() }
        load(
            settings,
            """
            <script>window.TubeBridge = { sent: [], postMessage: function (m) { this.sent.push(JSON.parse(m)) } }</script>
            <div id="movie_player"><video id="v"></video>
              <button class="ytp-ad-skip-button-modern" onclick="window.adSkipClicked = true"></button></div>
            <a id="short" href="/shorts/rL0qEmTRI-I">a Short</a>
            <script>$main</script>
            <script>
              var player = document.getElementById('movie_player'), v = document.getElementById('v'), t = 0, d = 100
              Object.defineProperty(v, 'currentTime', { get: function () { return t }, set: function (x) { t = x; v.dispatchEvent(new Event('seeking')) } })
              Object.defineProperty(v, 'duration', { get: function () { return d } })
              function fire(type) { v.dispatchEvent(new Event(type)) }
              function play(x) { t = x; fire('timeupdate') }
              function sent(type) { return TubeBridge.sent.filter(function (m) { return m.type === type }) }
              function receive(message) { window.__yourtubeReceive(JSON.stringify(message)) }
            </script>
            """,
        )
    }

    private suspend fun awaitNavigation() = withTimeout(5_000) { while (navigations.isEmpty()) delay(50) }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    /** Evaluates [script]; a string result comes back without its quotes. */
    private suspend fun eval(script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { done -> webView.evaluateJavascript(script) { done.resume(it.removeSurrounding("\"").replace("\\\"", "\"")) } }
    }

    /** Evaluates the promise [script] makes and returns what it resolves to, as a string. */
    private suspend fun async(script: String): String {
        eval("window.__result = undefined; Promise.resolve($script).then(function (r) { window.__result = String(r) }, function (e) { window.__result = 'error: ' + e })")
        return withTimeout(10_000) {
            var result = eval("window.__result === undefined ? null : window.__result")
            while (result == "null") {
                delay(50)
                result = eval("window.__result === undefined ? null : window.__result")
            }
            result
        }
    }

    private companion object {
        const val VIDEO = "aaaaaaaaaaa"
        const val WATCH_URL = "https://m.youtube.com/watch?v=$VIDEO"
        const val PLAYER_RESPONSE =
            """{"adPlacements":[{"adPlacementRenderer":{}}],"playerAds":[{}],"adSlots":[{}],"videoDetails":{"videoId":"$VIDEO"}}"""
        const val SEGMENTS =
            """[{"start":10,"end":20,"category":"sponsor","action":"skip"},{"start":30,"end":40,"category":"intro","action":"mute"},""" +
                """{"start":50,"end":60,"category":"outro","action":"show"}]"""
    }
}
