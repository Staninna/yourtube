package com.staninna.yourtube.web

import android.content.res.AssetManager
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.staninna.yourtube.YouTube
import com.staninna.yourtube.data.Settings
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The two scripts injected into YouTube pages:
 * - `early.js` runs at document start in every frame and touches no DOM elements: the background
 *   guard and the app CSS, with the current settings baked into the script text.
 * - `main.js` waits for `DOMContentLoaded` in the main frame: navigation and playback reporting and
 *   resume.
 *
 * With `DOCUMENT_START_SCRIPT` both are registered on the WebView and re-registered when settings
 * change. Without it they are injected on page start and finish; both scripts guard against running
 * twice.
 */
internal class PageScripts(assets: AssetManager) {
    private val earlyTemplate = assets.open("early.js").bufferedReader().use { it.readText() }
    private val main = assets.open("main.js").bufferedReader().use { it.readText() }
    private val documentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    private var handlers: List<ScriptHandler> = emptyList()

    /** Registers the scripts for every future document of [webView], replacing earlier ones. */
    fun register(webView: WebView, settings: Settings) {
        handlers.forEach { it.remove() }
        handlers = if (documentStart) {
            listOf(early(settings), main).map { WebViewCompat.addDocumentStartJavaScript(webView, it, YouTube.ORIGINS) }
        } else {
            emptyList()
        }
    }

    /** Applies [settings] to the document already showing; the early script updates its CSS in place. */
    fun applyToCurrentPage(webView: WebView, settings: Settings) = webView.evaluateJavascript(early(settings), null)

    /** Fallback for WebViews without document-start scripts. */
    fun onPageStarted(webView: WebView, settings: Settings) {
        if (!documentStart) webView.evaluateJavascript(early(settings), null)
    }

    fun onPageFinished(webView: WebView, settings: Settings) {
        if (!documentStart) webView.evaluateJavascript(early(settings) + "\n" + main, null)
    }

    /** Handlers die with their WebView; forget them so the next WebView starts clean. */
    fun forget() {
        handlers = emptyList()
    }

    private fun early(settings: Settings): String {
        val config = buildJsonObject { put("css", JsonPrimitive(css(settings))) }
        return earlyTemplate.replace(CONFIG_PLACEHOLDER, config.toString())
    }

    companion object {
        private const val CONFIG_PLACEHOLDER = "/*CONFIG*/{}"

        /** The app's CSS for YouTube pages under [settings]. */
        fun css(settings: Settings): String = buildList {
            add(
                "ytm-promoted-video-renderer,ytm-companion-ad-renderer,ytm-promoted-sparkles-web-renderer," +
                    ".ad-container,ytm-player-ad,ytm-ad-slot-renderer,ad-slot-renderer," +
                    "ytm-rich-grid-slot-renderer:has(ytm-ad-slot-renderer){display:none !important}",
            )
            // The app has its own bottom navigation, so YouTube's pivot bar goes.
            add("ytm-pivot-bar-renderer{display:none !important}")
            add("ytm-app,#app{padding-bottom:0 !important}")
            if (settings.hideShorts) {
                add(
                    "ytm-reel-shelf-renderer,ytm-rich-section-renderer:has([href*=\"/shorts\"])," +
                        "ytm-pivot-bar-item-renderer:has([aria-label=\"Shorts\" i]),a[href^=\"/shorts\"]" +
                        "{display:none !important}",
                )
            }
        }.joinToString("\n")
    }
}
