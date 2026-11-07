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
}

/**
 * Exposes `window.TubeBridge.postMessage(json)` to YouTube's main frame and routes decoded messages
 * to [listener]. Uses `addWebMessageListener` (origin-restricted, main frame only, replies through
 * the reply proxy). Without `WEB_MESSAGE_LISTENER` it falls back to `addJavascriptInterface` with the
 * same schema, posted to the main thread, replying through `evaluateJavascript`.
 *
 * Messages from a WebView that [isCurrent] no longer accepts (one being replaced) are dropped.
 */
internal class Bridge(private val listener: PageListener, private val isCurrent: (WebView) -> Boolean) {

    @SuppressLint("JavascriptInterface", "AddJavascriptInterface")
    fun install(webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, NAME, YouTube.ORIGINS) { view, message, _, isMainFrame, proxy ->
                val text = message.data
                if (isMainFrame && text != null && isCurrent(view)) handle(text) { proxy.postMessage(it) }
            }
        } else {
            webView.addJavascriptInterface(LegacyBridge(webView), NAME)
        }
    }

    private fun handle(text: String, reply: (String) -> Unit) {
        when (val message = BridgeMessage.decode(text) ?: return) {
            is PageReport -> listener.onPageReport(message)
            is PlaybackEvent -> listener.onPlayback(message)
            is ResumeRequest -> reply(BridgeMessage.encode(ResumeReply(message.videoId, listener.resumePosition(message.videoId))))
        }
    }

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
                handle(text) { json ->
                    webView.evaluateJavascript("window.__yourtubeReceive && window.__yourtubeReceive(${JSONObject.quote(json)})", null)
                }
            }
        }

        private fun originOf(url: String?): String? = url?.let(Uri::parse)?.let { "${it.scheme}://${it.host}" }
    }

    private companion object {
        const val NAME = "TubeBridge"
    }
}
