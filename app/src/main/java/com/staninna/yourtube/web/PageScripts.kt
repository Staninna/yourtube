package com.staninna.yourtube.web

import android.content.res.AssetManager
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.staninna.yourtube.YouTube
import com.staninna.yourtube.data.Settings

/**
 * The two scripts injected into YouTube pages:
 * - `early.js` runs at document start in every frame and touches no DOM elements: the background
 *   guard, the app CSS and the player-ads prune, with the current [PageConfig] baked into the script
 *   text. It keeps the config for `main.js`.
 * - `main.js` waits for `DOMContentLoaded` in the main frame: navigation and playback reporting,
 *   resume, SponsorBlock segments, the ad safety net and Shorts links.
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

    /** Applies [settings] to the document already showing; the early script swaps its config in place. */
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

    /** The early script with [settings]' [PageConfig] in place of its config literal. */
    internal fun early(settings: Settings): String = earlyTemplate.replace(CONFIG_PLACEHOLDER, PageConfig.of(settings).toString())

    private companion object {
        const val CONFIG_PLACEHOLDER = "/*CONFIG*/{}"
    }
}
