package com.staninna.yourtube.web

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.staninna.yourtube.YouTube
import org.json.JSONObject

/** What the page tells the app. Called on the main thread with decoded messages. */
interface PageListener {
    fun onNavigated(url: String)
    fun onPageReport(report: PageReport)
    fun onPlayback(event: PlaybackEvent)

    /** Where [videoId] should start, or null for the beginning. */
    fun resumePosition(videoId: String): Double?

    /** [videoId]'s SponsorBlock segments for the page. [reply] is called once, later, on the main thread. */
    fun requestSegments(videoId: String, reply: (SegmentsReply) -> Unit)

    fun onSegmentSkipped(skip: SegmentSkipped)

    /** An ad played on [url]. */
    fun onAdLeak(url: String)
}

/**
 * Exposes `window.TubeBridge.postMessage(json)` to YouTube's main frame and routes decoded messages
 * to [listener]. Uses `addWebMessageListener` (origin-restricted, main frame only, replies through
 * the reply proxy). Without `WEB_MESSAGE_LISTENER` it falls back to `addJavascriptInterface` with the
 * same schema, posted to the main thread, replying through `evaluateJavascript`.
 *
 * Messages from a WebView that [isCurrent] no longer accepts (one being replaced) are dropped.
 */
internal class Bridge(
    private val listener: PageListener,
    private val isCurrent: (WebView) -> Boolean,
    private val onVideoBox: (VideoBox) -> Unit,
) {

    @SuppressLint("JavascriptInterface", "AddJavascriptInterface")
    fun install(webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, NAME, YouTube.ORIGINS) { view, message, _, isMainFrame, proxy ->
                val text = message.data
                // A late reply (segments) goes nowhere once its WebView is replaced.
                if (isMainFrame && text != null && isCurrent(view)) handle(text) { if (isCurrent(view)) proxy.postMessage(it) }
            }
        } else {
            webView.addJavascriptInterface(LegacyBridge(webView), NAME)
        }
    }

    /**
     * Sends [reply] to [webView]'s page unasked, the way the fallback answers: `main.js`'s receive hook.
     * Only a YouTube page gets it; a consent or captcha page showing at the time runs nothing of ours.
     */
    fun push(webView: WebView, reply: BridgeReply) {
        if (originOf(webView.url) !in YouTube.ORIGINS) return
        webView.evaluateJavascript(receiveScript(BridgeMessage.encode(reply)), null)
    }

    private fun handle(text: String, reply: (String) -> Unit) {
        when (val message = BridgeMessage.decode(text) ?: return) {
            is PageReport -> listener.onPageReport(message)
            is PlaybackEvent -> listener.onPlayback(message)
            is ResumeRequest -> reply(BridgeMessage.encode(ResumeReply(message.videoId, listener.resumePosition(message.videoId))))
            is SegmentsRequest -> listener.requestSegments(message.videoId) { reply(BridgeMessage.encode(it)) }
            is SegmentSkipped -> listener.onSegmentSkipped(message)
            is AdLeak -> listener.onAdLeak(message.url)
            is VideoBox -> onVideoBox(message)
        }
    }

    private fun originOf(url: String?): String? = url?.let(Uri::parse)?.let { "${it.scheme}://${it.host}" }

    private fun receiveScript(json: String) = "window.__yourtubeReceive && window.__yourtubeReceive(${JSONObject.quote(json)})"

    /**
     * The `addJavascriptInterface` fallback. It cannot tell frames apart, so it accepts a message only
     * while the main frame shows a YouTube origin; `main.js` itself only runs in the main frame.
     */
    private inner class LegacyBridge(private val webView: WebView) {
        private val main = Handler(Looper.getMainLooper())

        @JavascriptInterface
        fun postMessage(text: String) {
            main.post {
                if (!isCurrent(webView) || originOf(webView.url) !in YouTube.ORIGINS) return@post
                handle(text) { json -> if (isCurrent(webView)) webView.evaluateJavascript(receiveScript(json), null) }
            }
        }

    }

    private companion object {
        const val NAME = "TubeBridge"
    }
}
